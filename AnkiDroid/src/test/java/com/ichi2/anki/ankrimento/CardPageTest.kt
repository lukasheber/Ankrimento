// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.not
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.io.path.createTempDirectory

@RunWith(AndroidJUnit4::class)
class CardPageTest {
    @Test
    fun `math is drawn with AnkiDroid's MathJax, typeset once loaded`() {
        val page = CardPage.html("", """Euler: \(e^{i\pi} + 1 = 0\)""")

        assertThat(page, containsString("""<script src="/_anki/js/mathjax.js">"""))
        assertThat(page, containsString("""<script src="/_anki/js/vendor/mathjax/tex-chtml-full.js">"""))
        // AnkiDroid's MathJax config leaves typesetting to the page.
        assertThat(page, containsString("MathJax.typesetPromise"))
    }

    @Test
    fun `media is answered whole, saying ranges may be asked for`() {
        // WebView applies a Range itself, skipping into the stream it gets: a stream already at the range
        // was skipped twice, and seeking an audio card past its middle failed.
        val media = createTempDirectory("media").toFile()
        File(media, "clip.mp3").writeBytes("0123456789".toByteArray())

        val whole = CardPage.mediaFile(media, "clip.mp3")!!
        assertThat(whole.statusCode == 206, equalTo(false)) // never a part: WebView makes the 206
        assertThat(whole.responseHeaders["Accept-Ranges"], equalTo("bytes"))
        // WebView sets the length for the range it serves; another one confused the player.
        assertThat(whole.responseHeaders.containsKey("Content-Length"), equalTo(false))
        assertThat(whole.data.readBytes().decodeToString(), equalTo("0123456789"))

        assertThat(CardPage.mediaFile(media, "../outside.mp3"), equalTo(null))
    }

    @Test
    fun `a card without math loads no MathJax`() {
        assertThat(CardPage.html("", "Plain words"), not(containsString("mathjax")))
    }

    @Test
    fun `a page without media stays as it was`() {
        val page = CardPage.html("", "Words [anki:play:q:0]")

        assertThat(page, not(containsString("[anki:play")))
        assertThat(page, not(containsString("reviewer.css")))
    }

    @Test
    fun `play buttons, typed answers and image occlusion load Anki's review styles`() {
        assertThat(CardPage.html("", """<a class="replay-button soundLink" href=playsound:q:0>"""), containsString("reviewer.css"))
        val typed = CardPage.html("", """<input id="typeans">""")
        assertThat(typed, containsString("""href="/_anki/css/reviewer.css""""))
        assertThat(typed, not(containsString("reviewer_extras_bundle.js")))

        val occlusion = CardPage.html("", """<div id="image-occlusion-container"></div>""")
        assertThat(occlusion, containsString("""href="/_anki/css/reviewer.css""""))
        assertThat(occlusion, containsString("""<script src="/_anki/js/reviewer_extras_bundle.js">"""))
    }
}
