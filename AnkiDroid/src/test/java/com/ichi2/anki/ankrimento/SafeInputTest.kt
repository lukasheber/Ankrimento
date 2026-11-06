// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafeInputTest : RobolectricTest() {
    @Test
    fun `a file shared from another app cannot be one of this app's private files`() {
        // A share or a picked file pointing into /data (the app's own storage) is refused, as AnkiDroid's
        // openInputStreamSafe refuses it.
        val private = Uri.parse("file:///data/data/com.ichi2.anki/databases/../files/collection.anki2")
        assertThrows(SecurityException::class.java) { targetContext.contentResolver.openInputStreamSafely(private) }
    }
}
