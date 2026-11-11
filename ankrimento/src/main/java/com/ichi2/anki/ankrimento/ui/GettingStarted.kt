// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ichi2.anki.ankrimento.R

/**
 * The version of the first-run guide. Mirrors `frontend/onboarding_dialog.py:ONBOARDING_VERSION`: a guide whose
 * version is higher than the one completed is shown again, once.
 */
const val ONBOARDING_VERSION = 1

/** Whether the guide is due. Mirrors `frontend/onboarding_dialog.py:should_show_onboarding`. */
fun shouldShowOnboarding(
    completedVersion: Int,
    version: Int = ONBOARDING_VERSION,
): Boolean = completedVersion.coerceAtLeast(0) < maxOf(1, version)

/** What a step's button does; the screen only asks for it, the activity carries it out. */
enum class GuideAction { ADD_DOCUMENT, SESSION_SETTINGS, BACKUP_SETTINGS }

/** One step of the guide: its words, and the optional button that goes to the place it describes. */
internal class GuideStep(
    val title: Int,
    val body: Int,
    val action: GuideAction? = null,
    val actionLabel: Int = 0,
)

/**
 * Desktop's first-run guide (`default_onboarding_steps`: welcome, add a document, extract, a small session, the
 * browser extension, a backup) in the phone's words. The extension step has no phone counterpart: Android's share
 * sheet is how things reach Incremento from other apps, so that step says so.
 */
internal val GUIDE_STEPS =
    listOf(
        GuideStep(R.string.ankrimento_guide_welcome_title, R.string.ankrimento_guide_welcome_body),
        GuideStep(
            R.string.ankrimento_guide_add_title,
            R.string.ankrimento_guide_add_body,
            GuideAction.ADD_DOCUMENT,
            R.string.ankrimento_guide_add_action,
        ),
        GuideStep(R.string.ankrimento_guide_extract_title, R.string.ankrimento_guide_extract_body),
        GuideStep(
            R.string.ankrimento_guide_session_title,
            R.string.ankrimento_guide_session_body,
            GuideAction.SESSION_SETTINGS,
            R.string.ankrimento_guide_session_action,
        ),
        GuideStep(R.string.ankrimento_guide_share_title, R.string.ankrimento_guide_share_body),
        GuideStep(
            R.string.ankrimento_guide_backup_title,
            R.string.ankrimento_guide_backup_body,
            GuideAction.BACKUP_SETTINGS,
            R.string.ankrimento_guide_backup_action,
        ),
    )

/**
 * The guide, one step to a screen: the words large enough to read at a glance, its button when it has one, and
 * Skip, Back and Next (Finish on the last) within a thumb's reach at the bottom.
 */
@Composable
internal fun GettingStartedScreen(
    onAction: (GuideAction) -> Unit,
    onDone: () -> Unit,
    steps: List<GuideStep> = GUIDE_STEPS,
) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    val step = steps[index]
    val last = index == steps.lastIndex
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                Text(
                    stringResource(R.string.ankrimento_guide_progress, index + 1, steps.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { (index + 1f) / steps.size },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(stringResource(step.title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(step.body), style = MaterialTheme.typography.bodyLarge)
                step.action?.let { action ->
                    Spacer(Modifier.height(24.dp))
                    FilledTonalButton(onClick = { onAction(action) }, modifier = Modifier.heightIn(min = 52.dp)) {
                        Text(stringResource(step.actionLabel))
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDone) { Text(stringResource(R.string.ankrimento_guide_skip)) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { index -= 1 }, enabled = index > 0) { Text(stringResource(R.string.ankrimento_guide_back)) }
                Spacer(Modifier.padding(horizontal = 4.dp))
                Button(
                    onClick = { if (last) onDone() else index += 1 },
                    modifier = Modifier.heightIn(min = 52.dp),
                ) { Text(stringResource(if (last) R.string.ankrimento_guide_finish else R.string.ankrimento_guide_next)) }
            }
        }
    }
}
