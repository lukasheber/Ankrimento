// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.ichi2.anki.ankrimento.ui.GettingStartedScreen
import com.ichi2.anki.ankrimento.ui.GuideAction
import com.ichi2.anki.ankrimento.ui.IncrementoTheme
import com.ichi2.anki.ankrimento.ui.ONBOARDING_VERSION

/**
 * Desktop's "Getting Started with Incremento": a short guide through the safest first workflow, shown the
 * first time Incremento opens and again from the session card's menu. Finishing or skipping it records the
 * version, so it does not come back unasked (desktop's `onboarding_completed_version`).
 *
 * A step's button opens the place it describes and leaves the guide where it was, so Back returns to it.
 */
class GettingStartedActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val device = AnkrimentoSettings(this)
        setContent {
            IncrementoTheme {
                GettingStartedScreen(
                    onAction = { action ->
                        when (action) {
                            GuideAction.ADD_DOCUMENT -> startActivity(IncrementoHubActivity.intent(this, add = true))
                            GuideAction.SESSION_SETTINGS, GuideAction.BACKUP_SETTINGS ->
                                startActivity(IncrementoSettingsActivity.intent(this))
                        }
                    },
                    onDone = {
                        device.onboardingCompletedVersion = ONBOARDING_VERSION
                        finish()
                    },
                )
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, GettingStartedActivity::class.java)
    }
}
