// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.content.Context

/**
 * What only the app can do for Ankrimento, registered by the app when it starts.
 * Restoring a full backup uses both, as desktop's restore makes an Anki safety
 * backup first and reopens the profile after.
 */
object AnkrimentoApp {
    /** AnkiDroid's own backup of the collection (listed under Restore from backup); true when made. */
    var safetyBackup: (suspend () -> Boolean)? = null

    /** Restarts the app once background work has finished and the collection is closed. */
    var restartApp: (suspend (Context) -> Unit)? = null
}
