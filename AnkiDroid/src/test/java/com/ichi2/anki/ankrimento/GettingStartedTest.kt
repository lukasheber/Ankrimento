// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import com.ichi2.anki.ankrimento.ui.ONBOARDING_VERSION
import com.ichi2.anki.ankrimento.ui.shouldShowOnboarding
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.Test

class GettingStartedTest {
    @Test
    fun `the guide is due until the current version is completed`() {
        assertThat(shouldShowOnboarding(completedVersion = 0), equalTo(true))
        assertThat(shouldShowOnboarding(completedVersion = ONBOARDING_VERSION), equalTo(false))
    }

    @Test
    fun `a newer guide is shown once more`() {
        assertThat(shouldShowOnboarding(completedVersion = 1, version = 2), equalTo(true))
        assertThat(shouldShowOnboarding(completedVersion = 2, version = 2), equalTo(false))
    }

    @Test
    fun `a nonsense stored version still shows the guide`() {
        assertThat(shouldShowOnboarding(completedVersion = -5), equalTo(true))
        // A version below one is read as one, as desktop does.
        assertThat(shouldShowOnboarding(completedVersion = 1, version = 0), equalTo(false))
    }
}
