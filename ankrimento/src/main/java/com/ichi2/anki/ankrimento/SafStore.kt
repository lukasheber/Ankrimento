// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File
import java.io.FileNotFoundException
import java.io.OutputStream

/**
 * The profile's folder (`<picked folder>/<profile>`) in a sync folder the user
 * picked with the Storage Access Framework, which works with folders kept in
 * step by Syncthing, FolderSync, Autosync and similar apps.
 *
 * Implements Python's `syncfolder.Store` (through `ankrimento.android.SafStore`);
 * paths are `/`-separated and relative to the profile's folder.
 */
class SafStore(
    context: Context,
    private val treeUri: Uri,
    private val profile: String,
) {
    private val resolver = context.contentResolver
    private val profileDirectoryId: String by lazy {
        directory(DocumentsContract.getTreeDocumentId(treeUri), profile)
    }

    fun readText(rel: String): String? =
        find(rel)?.let { uri ->
            resolver.openInputStreamSafely(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            }
        }

    /** Overwrites in place: SAF has no atomic replace. Readers detect torn files. */
    fun writeText(
        rel: String,
        text: String,
    ) = write(rel) { it.write(text.toByteArray(Charsets.UTF_8)) }

    fun upload(
        localPath: String,
        rel: String,
    ) = write(rel) { output -> File(localPath).inputStream().use { it.copyTo(output) } }

    fun download(
        rel: String,
        localPath: String,
    ) {
        val uri = find(rel) ?: throw FileNotFoundException(rel)
        val input = resolver.openInputStreamSafely(uri) ?: throw FileNotFoundException(rel)
        input.use { File(localPath).outputStream().use { output -> it.copyTo(output) } }
    }

    /** The file's size, or -1 when it does not exist. */
    fun size(rel: String): Long = document(rel)?.takeIf { !it.isDirectory }?.size ?: -1L

    fun delete(rel: String) {
        find(rel)?.let { DocumentsContract.deleteDocument(resolver, it) }
    }

    private data class Entry(
        val id: String,
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
    )

    private fun children(parentId: String): List<Entry> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val projection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE)
        return resolver.query(uri, projection, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        Entry(
                            id = cursor.getString(0),
                            name = cursor.getString(1),
                            isDirectory = cursor.getString(2) == Document.MIME_TYPE_DIR,
                            size = if (cursor.isNull(3)) 0L else cursor.getLong(3),
                        ),
                    )
                }
            }
        } ?: emptyList()
    }

    private fun document(rel: String): Entry? {
        var parentId = profileDirectoryId
        val parts = rel.split('/')
        parts.forEachIndexed { index, part ->
            val child = children(parentId).firstOrNull { it.name == part } ?: return null
            if (index == parts.lastIndex) return child
            if (!child.isDirectory) return null
            parentId = child.id
        }
        return null
    }

    private fun find(rel: String): Uri? = document(rel)?.let { DocumentsContract.buildDocumentUriUsingTree(treeUri, it.id) }

    private fun directory(
        parentId: String,
        name: String,
    ): String {
        children(parentId).firstOrNull { it.name == name && it.isDirectory }?.let { return it.id }
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
        val created =
            DocumentsContract.createDocument(resolver, parent, Document.MIME_TYPE_DIR, name)
                ?: throw FileNotFoundException("cannot create $name")
        return DocumentsContract.getDocumentId(created)
    }

    private fun write(
        rel: String,
        block: (OutputStream) -> Unit,
    ) {
        val parts = rel.split('/')
        var parentId = profileDirectoryId
        parts.dropLast(1).forEach { parentId = directory(parentId, it) }
        val name = parts.last()
        val existing = children(parentId).firstOrNull { it.name == name && !it.isDirectory }
        val uri =
            existing?.let { DocumentsContract.buildDocumentUriUsingTree(treeUri, it.id) }
                ?: DocumentsContract.createDocument(
                    resolver,
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId),
                    "application/octet-stream",
                    name,
                )
                ?: throw FileNotFoundException("cannot create $rel")
        val output = resolver.openOutputStream(uri, "wt") ?: throw FileNotFoundException(rel)
        output.use(block)
    }
}
