// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UpdateCheckTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val day = 24 * 60 * 60 * 1000L

    private fun releases(vararg tags: String) =
        tags.joinToString(",", "[", "]") { tag ->
            val draft = tag.startsWith("draft:")
            val name = tag.removePrefix("draft:")
            """{"tag_name":"$name","name":"Ankrimento $name","html_url":"https://github.com/x/releases/tag/$name","draft":$draft}"""
        }

    @Before
    fun fresh() {
        AnkrimentoSettings(context).apply {
            checkForUpdates = true
            updateCheckedAt = 0
            updateDeclined = ""
        }
    }

    @Test
    fun `betas come before their release and versions compare by number`() {
        assertTrue(UpdateCheck.isNewer("v0.1.0-beta8", "0.1.0beta7"))
        assertTrue(UpdateCheck.isNewer("v0.1.0", "0.1.0beta7"))
        assertTrue(UpdateCheck.isNewer("v0.1.0-beta10", "0.1.0beta9-debug"))
        assertFalse(UpdateCheck.isNewer("v0.1.0-beta7", "0.1.0beta7"))
        assertFalse(UpdateCheck.isNewer("v0.1.0-beta6", "0.1.0beta7"))
        assertFalse(UpdateCheck.isNewer("v2.26.0alpha14", "0.1.0beta7")) // AnkiDroid's own tags are not ours
    }

    @Test
    fun `the newest published release is offered`() {
        val found = UpdateCheck.check(context, now = day, current = "0.1.0beta7") { releases("draft:v0.1.0-beta9", "v0.1.0-beta8") }

        assertEquals("v0.1.0-beta8", found?.tag)
        assertEquals("Ankrimento v0.1.0-beta8", found?.name)
        assertEquals("https://github.com/x/releases/tag/v0.1.0-beta8", found?.url)
    }

    @Test
    fun `nothing is offered when this is the newest`() {
        assertNull(UpdateCheck.check(context, now = day, current = "0.1.0beta7") { releases("v0.1.0-beta7", "v0.1.0-beta6") })
    }

    @Test
    fun `github is asked at most once a day`() {
        var asked = 0
        val fetch = {
            asked++
            releases("v0.1.0-beta8")
        }

        UpdateCheck.check(context, now = day, current = "0.1.0beta7", fetch = fetch)
        assertNull(UpdateCheck.check(context, now = 2 * day - 1, current = "0.1.0beta7", fetch = fetch))
        UpdateCheck.check(context, now = 2 * day, current = "0.1.0beta7", fetch = fetch)

        assertEquals(2, asked)
    }

    @Test
    fun `a skipped version is not offered again but a later one is`() {
        UpdateCheck.skip(context, "v0.1.0-beta8")

        assertNull(UpdateCheck.check(context, now = day, current = "0.1.0beta7") { releases("v0.1.0-beta8") })
        assertEquals("v0.1.0-beta9", UpdateCheck.check(context, now = 2 * day, current = "0.1.0beta7") { releases("v0.1.0-beta9") }?.tag)
    }

    @Test
    fun `switched off or offline means nothing`() {
        AnkrimentoSettings(context).checkForUpdates = false
        assertNull(UpdateCheck.check(context, now = day, current = "0.1.0beta7") { releases("v0.1.0-beta8") })
        AnkrimentoSettings(context).checkForUpdates = true
        assertNull(UpdateCheck.check(context, now = day, current = "0.1.0beta7") { throw java.io.IOException("offline") })
        assertNull(UpdateCheck.check(context, now = 3 * day, current = "0.1.0beta7") { "not json" })
    }
}
