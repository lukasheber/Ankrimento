// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.ankrimento.ui.IncrementoIcons
import com.ichi2.anki.ankrimento.ui.IncrementoTheme
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import kotlin.math.roundToInt

/**
 * The scheduler options beyond the session settings, as desktop's dialog shows them in its
 * Advanced view: narrowing filters, tag quotas, content types scheduled first, strict
 * enforcement and the scope quotas are balanced over. Each change is saved at once.
 */
class SessionOptionsActivity : ComponentActivity() {
    private var options by mutableStateOf<JSONObject?>(null)

    /** The filter being edited ("topics_filter" or "items_filter"), or "tag" for a new tag quota. */
    private var editing by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { IncrementoTheme { Options() } }
        lifecycleScope.launch {
            runCatching {
                withCol {
                    Ankrimento.start(this@SessionOptionsActivity, this)
                    Ankrimento.sessionOptions()
                }
            }.onSuccess { options = it }
                .onFailure { error ->
                    Timber.w(error, "Loading session options failed")
                    toast(error.message ?: error.javaClass.simpleName)
                }
        }
    }

    /** Saves [changes] and shows what upstream kept. */
    private fun change(changes: JSONObject) {
        lifecycleScope.launch {
            runCatching { withCol { Ankrimento.setSessionOptions(changes) } }
                .onSuccess { options = it }
                .onFailure { error ->
                    Timber.w(error, "Saving session options failed")
                    toast(getString(R.string.ankrimento_options_not_saved, (error.message ?: "").substringAfter("Error: ")))
                }
        }
    }

    private fun change(
        key: String,
        value: Any,
    ) = change(JSONObject().put(key, value))

    private fun toast(text: String) = showToast(text)

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Options() {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.ankrimento_options_title)) },
                    navigationIcon = {
                        IconButton(onClick = ::finish) {
                            Icon(IncrementoIcons.Back, contentDescription = stringResource(R.string.ankrimento_navigate_up))
                        }
                    },
                )
            },
        ) { padding ->
            val current = options ?: return@Scaffold
            Column(
                Modifier
                    .padding(top = padding.calculateTopPadding())
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp),
            ) {
                Filters(current)
                TagQuotas(current)
                ContentFirst(current)
                Balancing(current)
                Phases(current)
            }
        }
        editing?.let { key ->
            TextDialog(
                key,
                when {
                    key == TAG -> ""
                    key.startsWith(ORDER) -> orderOf(key)?.toString().orEmpty()
                    else -> options?.optString(key).orEmpty()
                },
            )
        }
    }

    @Composable
    private fun Filters(current: JSONObject) {
        Header(R.string.ankrimento_options_filters, R.string.ankrimento_options_filters_help)
        for ((key, label) in listOf(
            "topics_filter" to R.string.ankrimento_options_topics_filter,
            "items_filter" to R.string.ankrimento_options_items_filter,
        )) {
            ListItem(
                headlineContent = { Text(stringResource(label)) },
                supportingContent = { Text(current.optString(key).ifEmpty { stringResource(R.string.ankrimento_options_filter_none) }) },
                modifier = Modifier.clickable { editing = key },
            )
        }
    }

    @Composable
    private fun TagQuotas(current: JSONObject) {
        Header(R.string.ankrimento_options_tags, R.string.ankrimento_options_tags_help)
        val tags = current.getJSONArray("tags")
        for (index in 0 until tags.length()) {
            val row = tags.getJSONObject(index)
            QuotaSlider(row.getString("tag"), row.getInt("weight"), onRemove = { change("tags", tags.without(index)) }) { weight ->
                change("tags", JSONArray(tags.toString()).apply { getJSONObject(index).put("weight", weight) })
            }
            if (current.optBoolean("priority_order")) OrderButton(row, "$ORDER:tags:$index")
        }
        ListItem(
            headlineContent = { Text(stringResource(R.string.ankrimento_options_add_tag)) },
            leadingContent = { Icon(IncrementoIcons.Add, contentDescription = null) },
            modifier = Modifier.clickable { editing = TAG },
        )
        Toggle(R.string.ankrimento_options_other_cards, R.string.ankrimento_options_other_cards_help, current.getBoolean("other_cards")) {
            change("other_cards", it)
        }
    }

    @Composable
    private fun ContentFirst(current: JSONObject) {
        Header(R.string.ankrimento_options_content, R.string.ankrimento_options_content_help)
        val rows = current.getJSONArray("content_types")
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val label = stringResource(CONTENT_LABELS[row.getString("type")] ?: R.string.ankrimento_options_content_pdf)
            val enabled = row.getBoolean("enabled")
            ListItem(
                headlineContent = { Text(label) },
                trailingContent = {
                    Switch(checked = enabled, onCheckedChange = { on ->
                        change(
                            "content_types",
                            JSONArray(rows.toString()).apply {
                                val changed = getJSONObject(index).put("enabled", on)
                                // At 0% a turned-on type would change nothing: start it with a share.
                                if (on && changed.optInt("weight") == 0) changed.put("weight", DEFAULT_CONTENT_SHARE)
                            },
                        )
                    })
                },
            )
            if (enabled) {
                QuotaSlider(stringResource(R.string.ankrimento_options_share), row.getInt("weight"), onRemove = null) { weight ->
                    change("content_types", JSONArray(rows.toString()).apply { getJSONObject(index).put("weight", weight) })
                }
                if (current.optBoolean("priority_order")) OrderButton(row, "$ORDER:content_types:$index")
            }
        }
        Toggle(
            R.string.ankrimento_options_media_outside_tags,
            R.string.ankrimento_options_media_outside_tags_help,
            current.getBoolean("media_outside_tags"),
        ) { change("media_outside_tags", it) }
    }

    @Composable
    private fun Balancing(current: JSONObject) {
        Header(R.string.ankrimento_options_balancing, null)
        Toggle(R.string.ankrimento_options_strict, R.string.ankrimento_options_strict_help, current.getBoolean("strict")) {
            change("strict", it)
        }
        Toggle(R.string.ankrimento_options_order, R.string.ankrimento_options_order_help, current.optBoolean("priority_order")) {
            change("priority_order", it)
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.ankrimento_options_scope), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.ankrimento_options_scope_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            val scopes =
                listOf(
                    "session" to R.string.ankrimento_options_scope_session,
                    "daily" to R.string.ankrimento_options_scope_today,
                    "lifetime" to R.string.ankrimento_options_scope_all_time,
                )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                scopes.forEachIndexed { index, (value, text) ->
                    SegmentedButton(
                        selected = current.getString("scope") == value,
                        onClick = { change("scope", value) },
                        shape = SegmentedButtonDefaults.itemShape(index, scopes.size),
                    ) { Text(stringResource(text)) }
                }
            }
        }
    }

    /** Desktop's phase funnel: the parts a session fills, in order; with strict enforcement, one turned off is skipped. */
    @Composable
    private fun Phases(current: JSONObject) {
        Header(R.string.ankrimento_options_phases, R.string.ankrimento_options_phases_help)
        val phases = current.optJSONArray("phases") ?: return
        val ids = List(phases.length()) { phases.getJSONObject(it).getString("id") }
        val strict = current.getBoolean("strict")
        for (index in ids.indices) {
            val phase = phases.getJSONObject(index)

            fun move(by: Int) {
                val order = ids.toMutableList()
                order.add(index + by, order.removeAt(index))
                change("phase_order", JSONArray(order))
            }
            ListItem(
                headlineContent = { Text("${index + 1}. ${phase.getString("label")}") },
                supportingContent = { Text(phase.optString("hint")) },
                leadingContent = {
                    Column {
                        IconButton(onClick = { move(-1) }, enabled = index > 0) {
                            Icon(
                                IncrementoIcons.ExpandMore,
                                contentDescription = stringResource(R.string.ankrimento_options_phase_up),
                                modifier = Modifier.rotate(180f),
                            )
                        }
                        IconButton(onClick = { move(1) }, enabled = index < ids.lastIndex) {
                            Icon(IncrementoIcons.ExpandMore, contentDescription = stringResource(R.string.ankrimento_options_phase_down))
                        }
                    }
                },
                trailingContent = {
                    Switch(
                        checked = phase.getBoolean("enabled"),
                        enabled = strict,
                        onCheckedChange = { on -> change("phases_enabled", JSONObject().put(phase.getString("id"), on)) },
                    )
                },
            )
        }
    }

    /** A row's Order value (desktop's priority order): its number, or none; tapping edits it. */
    @Composable
    private fun OrderButton(
        row: JSONObject,
        key: String,
    ) {
        val order = row.optInt("order", 0).takeIf { !row.isNull("order") && it > 0 }
        TextButton(onClick = { editing = key }, modifier = Modifier.padding(start = 4.dp)) {
            Text(
                if (order == null) {
                    stringResource(R.string.ankrimento_options_order_none)
                } else {
                    stringResource(R.string.ankrimento_options_order_value, order)
                },
            )
        }
    }

    /** The Order value an [ORDER] key ("order:tags:2") points at. */
    private fun orderOf(key: String): Int? {
        val (_, list, index) = key.split(':')
        val row = options?.optJSONArray(list)?.optJSONObject(index.toInt()) ?: return null
        return row.optInt("order", 0).takeIf { !row.isNull("order") && it > 0 }
    }

    private fun setOrder(
        key: String,
        text: String,
    ) {
        val (_, list, index) = key.split(':')
        val rows = JSONArray(options?.optJSONArray(list)?.toString() ?: return)
        val order = text.trim().toIntOrNull()?.takeIf { it > 0 }
        rows.getJSONObject(index.toInt()).put("order", order ?: JSONObject.NULL)
        change(list, rows)
    }

    @Composable
    private fun Header(
        label: Int,
        help: Int?,
    ) {
        HorizontalDivider(Modifier.padding(top = 8.dp))
        Text(
            stringResource(label),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        )
        help?.let {
            Text(
                stringResource(it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }

    @Composable
    private fun Toggle(
        label: Int,
        help: Int,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
    ) {
        ListItem(
            headlineContent = { Text(stringResource(label)) },
            supportingContent = { Text(stringResource(help)) },
            trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
            modifier = Modifier.clickable { onChange(!checked) },
        )
    }

    /** A share of the session, saved when the thumb is let go. */
    @Composable
    private fun QuotaSlider(
        label: String,
        value: Int,
        onRemove: (() -> Unit)?,
        onChange: (Int) -> Unit,
    ) {
        var shown by remember(value) { mutableFloatStateOf(value.toFloat()) }
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$label: ${shown.roundToInt()}%", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                onRemove?.let {
                    IconButton(onClick = it) {
                        Icon(IncrementoIcons.Delete, contentDescription = stringResource(R.string.ankrimento_options_remove_tag, label))
                    }
                }
            }
            Slider(
                value = shown,
                onValueChange = { shown = it },
                onValueChangeFinished = { if (shown.roundToInt() != value) onChange(shown.roundToInt()) },
                valueRange = 0f..100f,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
    }

    /** Edits a filter, or names a new tag quota. */
    @Composable
    private fun TextDialog(
        key: String,
        initial: String,
    ) {
        var text by remember(key) { mutableStateOf(initial) }
        val adding = key == TAG
        val ordering = key.startsWith(ORDER)
        AlertDialog(
            onDismissRequest = { editing = null },
            title = {
                Text(
                    stringResource(
                        when {
                            adding -> R.string.ankrimento_options_add_tag
                            ordering -> R.string.ankrimento_options_order
                            key == "topics_filter" -> R.string.ankrimento_options_topics_filter
                            else -> R.string.ankrimento_options_items_filter
                        },
                    ),
                )
            },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = {
                        Text(
                            stringResource(
                                when {
                                    adding -> R.string.ankrimento_options_tag_hint
                                    ordering -> R.string.ankrimento_options_order_hint
                                    else -> R.string.ankrimento_options_filter_hint
                                },
                            ),
                        )
                    },
                    keyboardOptions = if (ordering) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(enabled = !adding || text.isNotBlank(), onClick = {
                    editing = null
                    if (ordering) {
                        setOrder(key, text)
                    } else if (adding) {
                        val tags = options?.getJSONArray("tags") ?: JSONArray()
                        change(
                            "tags",
                            JSONArray(tags.toString()).put(JSONObject().put("tag", text.trim()).put("weight", DEFAULT_TAG_SHARE)),
                        )
                    } else {
                        change(key, text.trim())
                    }
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }

    private fun JSONArray.without(index: Int) = JSONArray((0 until length()).filter { it != index }.map { get(it) })

    companion object {
        private const val TAG = "tag"
        private const val ORDER = "order"
        private const val DEFAULT_TAG_SHARE = 20
        private const val DEFAULT_CONTENT_SHARE = 10

        private val CONTENT_LABELS =
            mapOf(
                "pdf" to R.string.ankrimento_options_content_pdf,
                "youtube" to R.string.ankrimento_options_content_video,
                "webpage" to R.string.ankrimento_options_content_web,
            )

        fun intent(context: Context) = Intent(context, SessionOptionsActivity::class.java)
    }
}
