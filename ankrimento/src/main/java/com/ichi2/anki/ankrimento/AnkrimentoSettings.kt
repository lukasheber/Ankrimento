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
        get() = preferences.getString(PREF_PROFILE, null) ?: Ankrimento.DEFAULT_PROFILE
        set(value) = preferences.edit { putString(PREF_PROFILE, value.trim().ifEmpty { Ankrimento.DEFAULT_PROFILE }) }

    /** The folder Incremento data is synced through, or null when not syncing. */
    var syncFolder: Uri?
        get() = preferences.getString(PREF_SYNC_FOLDER, null)?.let(Uri::parse)
        set(value) = preferences.edit { putString(PREF_SYNC_FOLDER, value?.toString()) }

    /**
     * The pairing code of a hub on the network that Incremento data is synced through, or empty. It holds the
     * device's key, so it is kept only here, in the app's private storage. When set, it is used instead of
     * [syncFolder].
     */
    var syncHubCode: String
        get() = preferences.getString(PREF_SYNC_HUB, null).orEmpty()
        set(value) = preferences.edit { putString(PREF_SYNC_HUB, value.trim()) }

    /** Incremento has been used on this device (worth warming up at launch). */
    var hasStarted: Boolean
        get() = preferences.getBoolean(PREF_HAS_STARTED, false)
        set(value) = preferences.edit { putBoolean(PREF_HAS_STARTED, value) }

    /** The version of the first-run guide finished or skipped (desktop's `onboarding_completed_version`); 0 if none. */
    var onboardingCompletedVersion: Int
        get() = preferences.getInt(PREF_ONBOARDING_VERSION, 0)
        set(value) = preferences.edit { putInt(PREF_ONBOARDING_VERSION, value) }

    /** The filtered deck sessions fill, as upstream names it; known once Incremento has started. */
    var sessionDeckName: String
        get() = preferences.getString(PREF_SESSION_DECK, null) ?: DEFAULT_SESSION_DECK
        set(value) = preferences.edit { putString(PREF_SESSION_DECK, value) }

    /** Shown to other devices, e.g. "Incremento is open on Pixel 8". */
    val deviceName: String
        get() = preferences.getString(PREF_DEVICE_NAME, null) ?: Build.MODEL

    private companion object {
        const val PREF_PROFILE = "profile"
        const val PREF_SYNC_FOLDER = "sync_folder"
        const val PREF_SYNC_HUB = "sync_hub"
        const val PREF_DEVICE_NAME = "device_name"
        const val PREF_HAS_STARTED = "has_started"
        const val PREF_SESSION_DECK = "session_deck"
        const val PREF_ONBOARDING_VERSION = "onboarding_version"

        /** Upstream's default session deck name. */
        const val DEFAULT_SESSION_DECK = "Incremento Session"
    }
}
