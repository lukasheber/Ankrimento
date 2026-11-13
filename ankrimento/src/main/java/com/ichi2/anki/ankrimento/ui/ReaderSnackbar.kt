// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * A snackbar with a second button beside the usual action (Undo): [secondLabel] runs [onSecond] and closes it, so the
 * action's own result (the user took it back) is not reported.
 */
internal class SnackbarWithSecondAction(
    override val message: String,
    override val actionLabel: String?,
    val secondLabel: String,
    val onSecond: () -> Unit,
    override val duration: SnackbarDuration = SnackbarDuration.Long,
) : SnackbarVisuals {
    override val withDismissAction: Boolean = false
}

/** The readers' snackbar host: plain snackbars as usual, and ones with a [SnackbarWithSecondAction]. */
@Composable
internal fun ReaderSnackbarHost(state: SnackbarHostState) {
    SnackbarHost(state) { data ->
        val second = data.visuals as? SnackbarWithSecondAction
        if (second == null) {
            Snackbar(data)
        } else {
            val actionColor = SnackbarDefaults.actionColor
            Snackbar(
                // On a narrow screen the two buttons go under the message instead of squeezing it.
                actionOnNewLine = LocalConfiguration.current.screenWidthDp < NARROW_SCREEN_DP,
                action = {
                    Row {
                        TextButton(
                            onClick = {
                                second.onSecond()
                                data.dismiss()
                            },
                        ) { Text(second.secondLabel, color = actionColor) }
                        second.actionLabel?.let { label ->
                            TextButton(onClick = data::performAction) { Text(label, color = actionColor) }
                        }
                    }
                },
            ) { Text(second.message) }
        }
    }
}

private const val NARROW_SCREEN_DP = 480
