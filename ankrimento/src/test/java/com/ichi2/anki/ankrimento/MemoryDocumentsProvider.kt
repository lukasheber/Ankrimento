// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * A document provider that keeps its tree in memory (file contents in temporary files), to test code written
 * against [android.provider.DocumentsContract]. Ids are paths. With [renameOnConflict] it adds a suffix to a
 * taken name, as some real providers do, instead of refusing it.
 */
class MemoryDocumentsProvider : DocumentsProvider() {
    class Node(
        var id: String,
        var name: String,
        val isDirectory: Boolean,
        var modified: Long? = 0L,
    ) {
        val file: File? = if (isDirectory) null else File.createTempFile("document", ".bin").also { it.deleteOnExit() }
    }

    companion object {
        const val AUTHORITY = "com.ichi2.anki.test.memory"
        const val ROOT = "root"
        val nodes = LinkedHashMap<String, Node>()
        var renameOnConflict = false
        var clock = 1_700_000_000_000L

        fun reset() {
            nodes.values.forEach { it.file?.delete() }
            nodes.clear()
            nodes[ROOT] = Node(ROOT, "root", true)
            renameOnConflict = false
        }

        private fun parentOf(id: String) = id.substringBeforeLast('/', "").ifEmpty { ROOT }

        fun childrenOf(parent: String) = nodes.values.filter { it.id != ROOT && parentOf(it.id) == parent }
    }

    override fun onCreate(): Boolean {
        reset()
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: arrayOf(Root.COLUMN_ROOT_ID)).also {
            it.addRow(arrayOfNulls<Any?>(it.columnCount))
        }

    private val columns =
        arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )

    private fun row(
        cursor: MatrixCursor,
        node: Node,
    ) {
        val builder = cursor.newRow()
        builder.add(Document.COLUMN_DOCUMENT_ID, node.id)
        builder.add(Document.COLUMN_DISPLAY_NAME, node.name)
        builder.add(Document.COLUMN_MIME_TYPE, if (node.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream")
        builder.add(Document.COLUMN_SIZE, node.file?.length())
        builder.add(Document.COLUMN_LAST_MODIFIED, node.modified)
        builder.add(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or Document.FLAG_SUPPORTS_WRITE)
    }

    override fun queryDocument(
        documentId: String,
        projection: Array<out String>?,
    ): Cursor =
        MatrixCursor(projection ?: columns).also {
            row(it, nodes[documentId] ?: throw FileNotFoundException(documentId))
        }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor =
        MatrixCursor(projection ?: columns).also { cursor ->
            childrenOf(parentDocumentId).forEach { row(cursor, it) }
        }

    override fun isChildDocument(
        parentDocumentId: String,
        documentId: String,
    ) = parentDocumentId == ROOT || documentId == parentDocumentId || documentId.startsWith("$parentDocumentId/")

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        var name = displayName
        if (childrenOf(parentDocumentId).any { it.name == name }) {
            if (!renameOnConflict) throw IOException("$name exists")
            name = "$name (1)"
        }
        val id = if (parentDocumentId == ROOT) name else "$parentDocumentId/$name"
        clock += 1000
        nodes[id] = Node(id, name, mimeType == Document.MIME_TYPE_DIR, clock)
        return id
    }

    override fun deleteDocument(documentId: String) {
        nodes.keys
            .filter { it == documentId || it.startsWith("$documentId/") }
            .forEach { nodes.remove(it)?.file?.delete() }
    }

    override fun renameDocument(
        documentId: String,
        displayName: String,
    ): String? {
        val node = nodes[documentId] ?: throw FileNotFoundException(documentId)
        val parent = parentOf(documentId)
        if (childrenOf(parent).any { it.name == displayName }) throw IOException("$displayName exists")
        val newId = if (parent == ROOT) displayName else "$parent/$displayName"
        nodes.remove(documentId)
        node.id = newId
        node.name = displayName
        nodes[newId] = node
        return newId
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val node = nodes[documentId] ?: throw FileNotFoundException(documentId)
        val file = node.file ?: throw FileNotFoundException("$documentId is a folder")
        if (mode.contains('w')) {
            clock += 1000
            node.modified = clock
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }
}

/**
 * What Robolectric registers in place of [MemoryDocumentsProvider]. Its resolver calls a provider object directly,
 * in the pre-Android-O query form that [DocumentsProvider] refuses; on a device the resolver reaches the provider
 * through its Bundle form. This front makes the same hop, so the tests run the code a device runs.
 */
class DocumentsFront : ContentProvider() {
    private val documents = MemoryDocumentsProvider()

    override fun attachInfo(
        context: Context,
        info: ProviderInfo,
    ) {
        super.attachInfo(context, info)
        documents.attachInfo(context, info)
    }

    override fun onCreate() = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = documents.query(uri, projection, Bundle.EMPTY, null)

    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle? = documents.call(MemoryDocumentsProvider.AUTHORITY, method, arg, extras)

    override fun call(
        authority: String,
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle? = documents.call(authority, method, arg, extras)

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor? = documents.openFile(uri, mode)

    override fun getType(uri: Uri): String? = documents.getType(uri)

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = throw UnsupportedOperationException()

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException()

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException()
}
