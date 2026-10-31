// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.content.Context
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.CollectionManager.withCol

/**
 * Restoring a full backup in desktop's order: an Anki safety backup, then the
 * profile's collection and Incremento files replaced together.
 */
object FullBackupRestore {
    /**
     * Replaces this device's collection and Incremento files with the backup staged by
     * [Ankrimento.stageRestore]. Incremento's folder is swapped first (the current one is
     * kept), then AnkiDroid restores the collection from the backup's package; if that
     * fails, the folder is put back. Throws when nothing was replaced.
     */
    suspend fun restoreStaged(context: Context) {
        try {
            val safetyBackup = AnkrimentoApp.safetyBackup
            check(safetyBackup == null || safetyBackup()) { context.getString(R.string.ankrimento_restore_no_safety_backup) }
            val packagePath = withCol { Ankrimento.restoreReplaceFiles() }
            try {
                CollectionManager.importColpkg(packagePath)
            } catch (error: Exception) {
                withCol { Ankrimento.finishRestore(succeeded = false) }
                throw error
            }
            withCol { Ankrimento.finishRestore(succeeded = true) }
        } catch (error: Exception) {
            runCatching { withCol { Ankrimento.discardRestore() } }
            throw error
        }
    }
}
