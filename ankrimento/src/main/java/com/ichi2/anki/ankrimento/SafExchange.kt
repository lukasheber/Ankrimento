// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The sync folder the user picked with the Storage Access Framework, as the sync engine's storage.
 *
 * Paths are `/`-separated and relative to the picked folder. Python's `ankrimento.safstore.KotlinStore`
 * calls this and keeps the engine's side of the contract (safe paths, bounded reads); what comes back is
 * plain: JSON text for a listing, bytes for a file, null for what does not exist.
 *
 * A document provider cannot replace a file atomically. [write] therefore writes a hidden temporary file
 * and renames it, so a reader sees the old file, no file, or the whole new one, never half of one. The
 * engine's immutable files (packs, blobs, snapshot parts) are named by their hash, so a damaged copy is
 * found anyway.
 */
class SafExchange(
    context: Context,
    private val treeUri: Uri,
) {
    private val resolver = context.contentResolver
    private val rootId = DocumentsContract.getTreeDocumentId(treeUri)

    private data class Node(
        val id: String,
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
        val modified: Long,
    )

    /** A JSON array of `{name, dir, size, modified}`; `modified` is milliseconds, or -1 when the provider gives none. */
    fun list(rel: String): String {
        val node = resolve(rel)
        val entries = if (node != null && node.isDirectory) children(node.id) else emptyList()
        return JSONArray(entries.map(::json)).toString()
    }

    fun stat(rel: String): String? = resolve(rel)?.let { json(it).toString() }

    /** The file's bytes, null when it does not exist, [IOException] when it is larger than [limit]. */
    fun read(
        rel: String,
        limit: Long,
    ): ByteArray? {
        val node = resolve(rel)?.takeIf { !it.isDirectory } ?: return null
        if (node.size > limit) throw IOException("$rel is larger than $limit bytes")
        return open(node).use { readBounded(it, limit, rel) }
    }

    fun write(
        rel: String,
        data: ByteArray,
    ) = replace(rel) { it.write(data) }

    fun upload(
        localPath: String,
        rel: String,
    ) = replace(rel) { output -> File(localPath).inputStream().use { it.copyTo(output) } }

    fun download(
        rel: String,
        localPath: String,
        limit: Long,
    ) {
        val node = resolve(rel)?.takeIf { !it.isDirectory } ?: throw FileNotFoundException(rel)
        if (node.size > limit) throw IOException("$rel is larger than $limit bytes")
        val target = File(localPath)
        try {
            open(node).use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > limit) throw IOException("$rel is larger than $limit bytes")
                        output.write(buffer, 0, count)
                    }
                }
            }
        } catch (error: IOException) {
            target.delete()
            throw error
        }
    }

    /** Deletes a file; a missing file, and a folder, are left alone. */
    fun delete(rel: String) {
        val node = resolve(rel)?.takeIf { !it.isDirectory } ?: return
        DocumentsContract.deleteDocument(resolver, documentUri(node.id))
    }

    // --- the document provider ---------------------------------------------------------------

    private fun documentUri(id: String) = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)

    private fun json(node: Node) =
        JSONObject()
            .put("name", node.name)
            .put("dir", node.isDirectory)
            .put("size", node.size)
            .put("modified", node.modified)

    private fun children(parentId: String): List<Node> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val projection =
            arrayOf(
                Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE,
                Document.COLUMN_SIZE,
                Document.COLUMN_LAST_MODIFIED,
            )
        return resolver.query(uri, projection, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        Node(
                            id = cursor.getString(0),
                            name = cursor.getString(1),
                            isDirectory = cursor.getString(2) == Document.MIME_TYPE_DIR,
                            size = if (cursor.isNull(3)) 0L else cursor.getLong(3),
                            modified = if (cursor.isNull(4)) -1L else cursor.getLong(4),
                        ),
                    )
                }
            }
        } ?: emptyList()
    }

    private fun resolve(rel: String): Node? {
        var current = Node(rootId, "", true, 0L, -1L)
        if (rel.isEmpty()) return current
        for (part in rel.split('/')) {
            if (!current.isDirectory) return null
            current = children(current.id).firstOrNull { it.name == part } ?: return null
        }
        return current
    }

    private fun directory(
        parentId: String,
        name: String,
    ): String {
        children(parentId).firstOrNull { it.name == name }?.let {
            if (!it.isDirectory) throw IOException("$name is a file")
            return it.id
        }
        val created =
            DocumentsContract.createDocument(resolver, documentUri(parentId), Document.MIME_TYPE_DIR, name)
                ?: throw IOException("cannot create folder $name")
        return DocumentsContract.getDocumentId(created)
    }

    private fun open(node: Node): InputStream = resolver.openInputStream(documentUri(node.id)) ?: throw FileNotFoundException(node.name)

    private fun readBounded(
        input: InputStream,
        limit: Long,
        rel: String,
    ): ByteArray {
        val bytes = input.readNBytes((limit + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        if (bytes.size > limit) throw IOException("$rel is larger than $limit bytes")
        return bytes
    }

    /** Writes [block]'s output to a hidden temporary file next to [rel], then makes it [rel]. */
    private fun replace(
        rel: String,
        block: (OutputStream) -> Unit,
    ) {
        val parts = rel.split('/')
        var parentId = rootId
        parts.dropLast(1).forEach { parentId = directory(parentId, it) }
        val name = parts.last()
        val temporaryName = ".$name.${System.nanoTime()}.tmp"
        val temporary =
            DocumentsContract.createDocument(resolver, documentUri(parentId), "application/octet-stream", temporaryName)
                ?: throw IOException("cannot create $rel")
        try {
            // What openOutputStream does on a device, spelled out: the provider writes into this descriptor.
            val descriptor = resolver.openFileDescriptor(temporary, "wt") ?: throw FileNotFoundException(rel)
            ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use(block)
            children(parentId).firstOrNull { it.name == name && !it.isDirectory }?.let {
                DocumentsContract.deleteDocument(resolver, documentUri(it.id))
            }
            val renamed = DocumentsContract.renameDocument(resolver, temporary, name)
            val landed =
                renamed?.let {
                    children(parentId).any { child ->
                        child.id == DocumentsContract.getDocumentId(it) &&
                            child.name == name
                    }
                }
            if (landed != true) throw IOException("cannot name $rel")
        } catch (error: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, temporary) }
            throw error
        }
    }
}
