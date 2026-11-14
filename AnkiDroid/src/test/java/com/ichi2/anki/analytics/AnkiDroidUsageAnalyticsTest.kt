// SPDX-FileCopyrightText: 2026 Ashish Yadav <mailtoashish693@gmail.com>
// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.analytics

import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.EmptyApplicationCategory
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.testutils.EmptyApplication
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.sameInstance
import org.hamcrest.MatcherAssert.assertThat
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = EmptyApplication::class)
@Category(EmptyApplicationCategory::class)
class AnkiDroidUsageAnalyticsTest : RobolectricTest() {
    @Test
    fun `settings switch key matches the key analytics reads`() {
        // the Settings switch is a plain SwitchPreferenceCompat: it writes this key
        // itself, so a mismatch silently stops it controlling analytics
        assertThat(
            targetContext.getString(R.string.analytics_opt_in_key),
            equalTo(AnkiDroidUsageAnalytics.PREF_ANALYTICS_OPTIN),
        )
    }

    @Test
    fun `nobody is asked to share usage while there is no property to report to`() {
        // Ankrimento's build has no analytics property (ga_trackingId is empty): after an update the question would
        // be one whose answer does nothing, even for someone who was never asked.
        assertThat(getPreferences().contains(AnkiDroidUsageAnalytics.PREF_ANALYTICS_OPTIN), equalTo(false))
        assertThat(AnkiDroidUsageAnalytics.shouldAskToOptIn(targetContext), equalTo(false))
    }

    @Test
    fun `opt-in key is the v2 key`() {
        assertThat(AnkiDroidUsageAnalytics.PREF_ANALYTICS_OPTIN, equalTo("analytics_opt_in_v2"))
    }

    @Test
    fun `analytics is disabled by default`() {
        assertThat(getPreferences().getBoolean(AnkiDroidUsageAnalytics.PREF_ANALYTICS_OPTIN, false), equalTo(false))
    }

    @Test
    fun `a preference write cannot turn analytics on while there is no property to report to`() {
        // Ankrimento's build has no analytics property (ga_trackingId is empty): the preference is ignored.
        withStubbedRebuild {
            getPreferences().edit(commit = true) { putBoolean(AnkiDroidUsageAnalytics.PREF_ANALYTICS_OPTIN, true) }

            assertThat(AnkiDroidUsageAnalytics.isEnabled, equalTo(false))
            verify(exactly = 0) { AnkiDroidUsageAnalytics.reinitialize(any()) }
        }
    }

    @Test
    fun `a write that leaves the value unchanged does not rebuild the client`() {
        withStubbedRebuild {
            getPreferences().edit(commit = true) { putBoolean(AnkiDroidUsageAnalytics.PREF_ANALYTICS_OPTIN, false) }

            verify(exactly = 0) { AnkiDroidUsageAnalytics.reinitialize(any()) }
        }
    }

    /** Ankrimento never reports crashes to AnkiDroid's analytics: starting or rebuilding it adds nothing to the chain. */
    @Test
    fun `analytics never puts itself in the crash handler chain`() =
        runTest {
            val base = Thread.getDefaultUncaughtExceptionHandler()
            try {
                // stand in for ThrowableFilterService, the handler the app does install
                val filter = PassThroughHandler(base)
                Thread.setDefaultUncaughtExceptionHandler(filter)

                AnkiDroidUsageAnalytics.initialize(targetContext)
                AnkiDroidUsageAnalytics.rebuild(targetContext)

                assertThat(Thread.getDefaultUncaughtExceptionHandler(), sameInstance<Any>(filter))
            } finally {
                Thread.setDefaultUncaughtExceptionHandler(base)
            }
        }

    private class PassThroughHandler(
        private val next: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(
            thread: Thread,
            throwable: Throwable,
        ) {
            next?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Runs [block] with the analytics listener registered and `reinitialize` stubbed out:
     * the real one rebuilds on [kotlinx.coroutines.Dispatchers.IO] and would outlive the test.
     */
    private fun withStubbedRebuild(block: () -> Unit) {
        mockkObject(AnkiDroidUsageAnalytics)
        every { AnkiDroidUsageAnalytics.reinitialize(any()) } returns Unit
        try {
            AnkiDroidUsageAnalytics.initialize(targetContext)
            block()
        } finally {
            // clear the preference before unmocking, so the resulting change
            // doesn't reach the real `reinitialize`
            getPreferences().edit(commit = true) { remove(AnkiDroidUsageAnalytics.PREF_ANALYTICS_OPTIN) }
            unmockkObject(AnkiDroidUsageAnalytics)
            AnalyticsExceptionHandler.uninstall()
        }
    }
}
