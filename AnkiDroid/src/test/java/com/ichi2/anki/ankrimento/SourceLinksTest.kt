// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Desktop's links from an extract back to its source, opened in the phone's readers. */
@RunWith(AndroidJUnit4::class)
class SourceLinksTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `a web extract opens its page`() {
        val intent = SourceLinks.intentFor(context, "incremento_open_web:12:https%3A%2F%2Fexample.org%2Fa%3Fb%3D1")!!
        assertEquals(WebReaderActivity::class.java.name, intent.component?.className)
        assertEquals(12L, intent.getLongExtra(ReaderActivity.EXTRA_CARD_ID, 0))
        assertEquals("https://example.org/a?b=1", intent.getStringExtra(WebReaderActivity.EXTRA_URL))
    }

    @Test
    fun `pdf and epub extracts open their page or chapter`() {
        val pdf = SourceLinks.intentFor(context, "incremento_open_pdf:5:7")!!
        assertEquals(PdfReaderActivity::class.java.name, pdf.component?.className)
        assertEquals(7, pdf.getIntExtra(ReaderActivity.EXTRA_START, 0))

        val ref = SourceLinks.intentFor(context, """incremento_open_pdf_ref:{"card_id": 5, "page": 3, "excerpt": "key words"}""")!!
        assertEquals(3, ref.getIntExtra(ReaderActivity.EXTRA_START, 0))
        assertEquals("key words", ref.getStringExtra(ReaderActivity.EXTRA_FIND))

        val epub = SourceLinks.intentFor(context, "incremento_open_epub:3:4:120:0.5")!!
        assertEquals(EpubReaderActivity::class.java.name, epub.component?.className)
        assertEquals(4, epub.getIntExtra(ReaderActivity.EXTRA_START, -1))
        // The place in the chapter too, as desktop restores it.
        assertEquals(0.5, epub.getDoubleExtra(EpubReaderActivity.EXTRA_SCROLL, -1.0), 0.0)
    }

    @Test
    fun `a video extract opens its moment`() {
        val video = SourceLinks.intentFor(context, "incremento_open_video:9:19.5")!!
        assertEquals(VideoReaderActivity::class.java.name, video.component?.className)
    }

    @Test
    fun `anything else is not a source link`() {
        assertNull(SourceLinks.intentFor(context, "incremento_open_web:x:y"))
        assertNull(SourceLinks.intentFor(context, "ankiplay:1"))
    }
}
