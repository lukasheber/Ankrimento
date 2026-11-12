// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.ichi2.anki.ankrimento.R

/** One formatting tool: its look, what a screen reader says, and the browser's editing command it runs. */
private class FormatTool(
    val glyph: String,
    val description: Int,
    val command: String,
    val style: SpanStyle = SpanStyle(),
)

private val FORMAT_TOOLS =
    listOf(
        FormatTool("B", R.string.ankrimento_edit_bold, "bold", SpanStyle(fontWeight = FontWeight.Bold)),
        FormatTool("I", R.string.ankrimento_edit_italic, "italic", SpanStyle(fontStyle = FontStyle.Italic)),
        FormatTool("U", R.string.ankrimento_edit_underline, "underline", SpanStyle(textDecoration = TextDecoration.Underline)),
        FormatTool("•", R.string.ankrimento_edit_bullets, "insertUnorderedList"),
        FormatTool("1.", R.string.ankrimento_edit_numbers, "insertOrderedList"),
        FormatTool("⟲", R.string.ankrimento_edit_undo, "undo"),
        FormatTool("⟳", R.string.ankrimento_edit_redo, "redo"),
        FormatTool("Tx", R.string.ankrimento_edit_clear, "removeFormat"),
    )

/**
 * The formatting tools of the in-place topic editor, a thumb's reach above the keyboard. Each one runs a browser
 * editing command ([run] gets the JavaScript) on the text being edited, so the topic is changed as formatted
 * text and no markup is ever shown.
 */
@Composable
internal fun TopicEditorBar(run: (String) -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FORMAT_TOOLS.forEach { tool ->
            val description = stringResource(tool.description)
            TextButton(
                onClick = { run("document.execCommand('${tool.command}', false, null)") },
                modifier = Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp).semantics { contentDescription = description },
            ) {
                Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        pushStyle(tool.style)
                        append(tool.glyph)
                        pop()
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}
