// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import androidx.core.content.edit

/** Ankrimento's device settings. */
class AnkrimentoSettings(
    context: Context,
) {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences("ankrimento", Context.MODE_PRIVATE)

    /**
     * The Anki profile whose Incremento data this device uses; must match the
     * desktop profile name for syncing (`user_files/<profile>/`).
     * Takes effect the next time the app starts.
     */
    var profile: String
        get() = preferences.getString(KEY_PROFILE, null) ?: Ankrimento.DEFAULT_PROFILE
        set(value) = preferences.edit { putString(KEY_PROFILE, value.trim().ifEmpty { Ankrimento.DEFAULT_PROFILE }) }

    /** The folder Incremento data is synced through, or null when not syncing. */
    var syncFolder: Uri?
        get() = preferences.getString(KEY_SYNC_FOLDER, null)?.let(Uri::parse)
        set(value) = preferences.edit { putString(KEY_SYNC_FOLDER, value?.toString()) }

    /** Incremento has been used on this device (worth warming up at launch). */
    var hasStarted: Boolean
        get() = preferences.getBoolean(KEY_HAS_STARTED, false)
        set(value) = preferences.edit { putBoolean(KEY_HAS_STARTED, value) }

    /** Shown to other devices, e.g. "Incremento is open on Pixel 8". */
    val deviceName: String
        get() = preferences.getString(KEY_DEVICE_NAME, null) ?: Build.MODEL

    private companion object {
        const val KEY_PROFILE = "profile"
        const val KEY_SYNC_FOLDER = "sync_folder"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_HAS_STARTED = "has_started"
    }
}
