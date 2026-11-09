// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.content.Context
import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/** The exchange folder through the Storage Access Framework, against a provider that keeps its files in memory. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SafExchangeTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var exchange: SafExchange

    @Before
    fun setUp() {
        val info =
            ProviderInfo().apply {
                authority = MemoryDocumentsProvider.AUTHORITY
                exported = true
                grantUriPermissions = true
                readPermission = android.Manifest.permission.MANAGE_DOCUMENTS
                writePermission = android.Manifest.permission.MANAGE_DOCUMENTS
            }
        Robolectric.buildContentProvider(DocumentsFront::class.java).create(info)
        MemoryDocumentsProvider.reset()
        val tree: Uri = DocumentsContract.buildTreeDocumentUri(MemoryDocumentsProvider.AUTHORITY, MemoryDocumentsProvider.ROOT)
        exchange = SafExchange(context, tree)
    }

    private fun names(rel: String): List<String> {
        val entries = JSONArray(exchange.list(rel))
        return List(entries.length()) { entries.getJSONObject(it).getString("name") }.sorted()
    }

    @Test
    fun `a written file is read back and appears in its folder with its size`() {
        exchange.write("IncrementoSync/v1/lib/library.json", "{}".toByteArray())

        assertArrayEquals("{}".toByteArray(), exchange.read("IncrementoSync/v1/lib/library.json", 100))
        assertEquals(listOf("library.json"), names("IncrementoSync/v1/lib"))
        val info = JSONObject(exchange.stat("IncrementoSync/v1/lib/library.json")!!)
        assertEquals(2, info.getInt("size"))
        assertFalse(info.getBoolean("dir"))
        assertTrue(JSONObject(exchange.stat("IncrementoSync/v1")!!).getBoolean("dir"))
    }

    @Test
    fun `what is missing is nothing, not an error`() {
        assertNull(exchange.read("a/b", 10))
        assertNull(exchange.stat("a/b"))
        assertEquals("[]", exchange.list("a/b"))
        exchange.delete("a/b")
    }

    @Test
    fun `overwriting replaces the whole file and leaves no temporary file behind`() {
        exchange.write("d/device.json", "first version, longer".toByteArray())
        exchange.write("d/device.json", "second".toByteArray())

        assertArrayEquals("second".toByteArray(), exchange.read("d/device.json", 100))
        assertEquals(listOf("device.json"), names("d"))
    }

    @Test
    fun `a file larger than the limit is refused`() {
        exchange.write("big", ByteArray(100))

        assertThrows(IOException::class.java) { exchange.read("big", 10) }
        val target = File(temporary.root, "out")
        assertThrows(IOException::class.java) { exchange.download("big", target.path, 10) }
        assertFalse(target.exists())
    }

    @Test
    fun `upload and download move a file as it is`() {
        val source = temporary.newFile("source").apply { writeBytes(ByteArray(5000) { (it % 251).toByte() }) }
        val copy = File(temporary.root, "copy")

        exchange.upload(source.path, "blobs/aa/blob")
        exchange.download("blobs/aa/blob", copy.path, 10_000)

        assertArrayEquals(source.readBytes(), copy.readBytes())
    }

    @Test
    fun `download of a missing file fails`() {
        assertThrows(IOException::class.java) { exchange.download("nope", File(temporary.root, "x").path, 10) }
    }

    @Test
    fun `delete removes a file but not a folder`() {
        exchange.write("d/one", byteArrayOf(1))
        exchange.delete("d/one")
        exchange.delete("d")

        assertEquals(emptyList<String>(), names("d"))
        assertNotNull(exchange.stat("d"))
    }

    @Test
    fun `a provider that adds a suffix to a taken name is not trusted`() {
        MemoryDocumentsProvider.renameOnConflict = true
        exchange.write("d/a", byteArrayOf(1))

        // The temporary file has a unique name; what must land on exactly "a" is the rename.
        exchange.write("d/a", byteArrayOf(2))

        assertEquals(listOf("a"), names("d"))
        assertArrayEquals(byteArrayOf(2), exchange.read("d/a", 10))
    }

    @Test
    fun `listing reports the provider's modification time in milliseconds`() {
        exchange.write("d/a", byteArrayOf(1))

        val entry = JSONArray(exchange.list("d")).getJSONObject(0)

        assertTrue(entry.getLong("modified") > 0)
    }

    @Test
    fun `a provider without modification times reports minus one`() {
        exchange.write("d/a", byteArrayOf(1))
        MemoryDocumentsProvider.nodes["d/a"]!!.modified = null

        assertEquals(-1L, JSONArray(exchange.list("d")).getJSONObject(0).getLong("modified"))
    }
}
