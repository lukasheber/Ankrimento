// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.libanki.SoundOrVideoTag
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.not
import org.junit.Test
import org.junit.runner.RunWith

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
    fun `a card without math loads no MathJax`() {
        assertThat(CardPage.html("", "Plain words"), not(containsString("mathjax")))
    }

    private fun media(
        side: String = "q",
        autoplay: Boolean = true,
        replayQuestion: Boolean = false,
        showButtons: Boolean = true,
    ) = CardPage.SideMedia.of(
        question = listOf(SoundOrVideoTag("a b.mp3"), SoundOrVideoTag("clip.mkv")),
        answer = listOf(SoundOrVideoTag("back.ogg")),
        side = side,
        autoplay = autoplay,
        replayQuestion = replayQuestion,
        showButtons = showButtons,
    )

    @Test
    fun `sound gets a replay button and its file, video a player`() {
        val page = CardPage.html("", "[anki:play:q:0] words [anki:play:q:1]", media())

        assertThat(page, not(containsString("[anki:play")))
        assertThat(page, containsString("""class="replay-button soundLink""""))
        assertThat(page, containsString("""ankrimentoPlay('q:0')"""))
        assertThat(page, containsString("a%20b.mp3"))
        assertThat(page, containsString("""<video src="${CardPage.BASE_URL}clip.mkv""""))
        assertThat(page, containsString("""data-play="q:1""""))
    }

    @Test
    fun `the play buttons can be hidden, as AnkiDroid's option, and sound still plays`() {
        val page = CardPage.html("", "[anki:play:q:0]", media(showButtons = false))

        assertThat(page, not(containsString("replay-button soundLink")))
        assertThat(page, containsString(""""autoplay":["q:0","q:1"]"""))
    }

    @Test
    fun `each side plays its own sound by itself, as desktop`() {
        assertThat(media("q").autoplay, equalTo(listOf("q:0", "q:1")))
        assertThat(media("a").autoplay, equalTo(listOf("a:0")))
        assertThat(media("a", replayQuestion = true).autoplay, equalTo(listOf("q:0", "q:1", "a:0")))
        assertThat(media("q", autoplay = false).autoplay, equalTo(emptyList()))
    }

    @Test
    fun `a file name cannot close the page's script`() {
        val tags = CardPage.SideMedia.of(listOf(SoundOrVideoTag("</script>.mp3")), emptyList(), "q", true, false, true)

        assertThat(CardPage.html("", "", tags), not(containsString("</script>.mp3")))
    }

    @Test
    fun `a page without media stays as it was`() {
        val page = CardPage.html("", "Words [anki:play:q:0]")

        assertThat(page, not(containsString("[anki:play")))
        assertThat(page, not(containsString("ankrimentoPlay")))
        assertThat(page, not(containsString("reviewer.css")))
    }

    @Test
    fun `the type tag's modifiers and field are read as AnkiDroid reads them`() {
        val plain = CardPage.TypedTag.find("Q [[type:Back]]")!!
        assertThat(plain.field, equalTo("Back"))
        assertThat(plain.combining, equalTo(true))
        assertThat(plain.cloze, equalTo(false))
        assertThat(plain.noSuggest, equalTo(false))

        val all = CardPage.TypedTag.find("[[type:nosuggest:nc:cloze:Text]]")!!
        assertThat(all.field, equalTo("Text"))
        assertThat(all.combining, equalTo(false))
        assertThat(all.cloze, equalTo(true))
        assertThat(all.noSuggest, equalTo(true))

        assertThat(CardPage.TypedTag.find("No tag"), equalTo(null))
    }

    @Test
    fun `the question gets a box to type in, sending the answer on Enter`() {
        val typed = CardPage.TypedAnswer("correct", combining = true, noSuggest = true, font = "Arial", fontSize = 20)
        val page = typed.onQuestion("Q [[type:Back]]")

        assertThat(page, not(containsString("[[type:")))
        assertThat(page, containsString("""id="typeans""""))
        assertThat(page, containsString("font-family: 'Arial'; font-size: 20px"))
        assertThat(page, containsString("AnkrimentoItem.submit()"))
        assertThat(page, containsString("""autocorrect="off""""))
    }

    @Test
    fun `the answer shows the comparison where the tag was`() {
        val typed = CardPage.TypedAnswer("correct", combining = true, noSuggest = false, font = "Arial", fontSize = 20)
        val page = typed.onAnswer("A [[type:Back]] end", """<code id=typeans>$1</code>""")

        assertThat(page, containsString("""<code id=typeans>$1</code>"""))
        assertThat(page, containsString("font-size: 20px"))
        assertThat(page, not(containsString("[[type:")))
    }

    @Test
    fun `typed answers and image occlusion load Anki's review styles and script`() {
        val typed = CardPage.html("", """<input id="typeans">""")
        assertThat(typed, containsString("""href="/_anki/css/reviewer.css""""))
        assertThat(typed, not(containsString("reviewer_extras_bundle.js")))

        val occlusion = CardPage.html("", """<div id="image-occlusion-container"></div>""")
        assertThat(occlusion, containsString("""href="/_anki/css/reviewer.css""""))
        assertThat(occlusion, containsString("""<script src="/_anki/js/reviewer_extras_bundle.js">"""))
    }
}
