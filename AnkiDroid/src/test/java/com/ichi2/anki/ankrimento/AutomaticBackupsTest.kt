// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.ankrimento.AutomaticBackups.Policy
import com.ichi2.anki.ankrimento.AutomaticBackups.Trigger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** When an automatic backup is due: upstream's `backup_schedule.is_due`, without desktop's "on close". */
@RunWith(AndroidJUnit4::class)
class AutomaticBackupsTest {
    private val folder = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ABackups")
    private val hour = 3_600_000L

    @Test
    fun `nothing is due until it is turned on with a folder`() {
        assertFalse(Policy(enabled = false, folder = folder).isDue(Trigger.OPEN, now = 0))
        assertFalse(Policy(enabled = true, folder = null).isDue(Trigger.OPEN, now = 0))
    }

    @Test
    fun `opening the app backs up when chosen`() {
        assertTrue(Policy(enabled = true, folder = folder, onOpen = true).isDue(Trigger.OPEN, now = 0))
        assertFalse(Policy(enabled = true, folder = folder, onOpen = false).isDue(Trigger.OPEN, now = 0))
    }

    @Test
    fun `the interval counts from the last backup that succeeded`() {
        val policy = Policy(enabled = true, folder = folder, intervalHours = 24, lastSuccess = 10 * hour)
        assertFalse(policy.isDue(Trigger.INTERVAL, now = 33 * hour))
        assertTrue(policy.isDue(Trigger.INTERVAL, now = 34 * hour))
        assertFalse(policy.copy(intervalHours = 0).isDue(Trigger.INTERVAL, now = 1_000 * hour))
    }
}
