// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.ankrimento.ui.IncrementoIcons
import com.ichi2.anki.ankrimento.ui.IncrementoTheme
import kotlinx.coroutines.launch
import org.json.JSONObject
import timber.log.Timber
import kotlin.math.roundToInt

/**
 * Incremento's settings on one screen, in the order they matter while
 * studying: the session, then reviewing, extracting and reading, then this
 * device. Session and Incremento settings are upstream's own (stored as
 * desktop stores them, and normalized by upstream on every change).
 */
class IncrementoSettingsActivity : ComponentActivity() {
    private var session by mutableStateOf<Ankrimento.SessionSettings?>(null)
    private var values by mutableStateOf<JSONObject?>(null)
    private val device by lazy { AnkrimentoSettings(this) }
    private var profile by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        profile = device.profile
        setContent { IncrementoTheme { Settings() } }
        lifecycleScope.launch {
            runCatching {
                withCol {
                    Ankrimento.start(this@IncrementoSettingsActivity, this)
                    Ankrimento.sessionSettings() to Ankrimento.settingsValues()
                }
            }.onSuccess { (sessionSettings, settings) ->
                session = sessionSettings
                values = settings
            }.onFailure { error ->
                Timber.w(error, "Loading Incremento settings failed")
                toast(error.message ?: error.javaClass.simpleName)
            }
        }
    }

    private fun saveSession(changed: Ankrimento.SessionSettings) {
        session = changed
        lifecycleScope.launch { runCatching { withCol { Ankrimento.saveSessionSettings(changed) } }.onFailure { toast(it.message ?: "") } }
    }

    /** Saves [value] and shows what upstream kept (it may clamp or clean it). */
    private fun set(
        key: String,
        value: Any,
    ) {
        lifecycleScope.launch {
            runCatching { withCol { Ankrimento.setSetting(key, value) } }
                .onSuccess { kept -> values = JSONObject(values.toString()).put(key, kept) }
                .onFailure { error ->
                    Timber.w(error, "Saving %s failed", key)
                    toast(error.message ?: error.javaClass.simpleName)
                }
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Settings() {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.ankrimento_settings)) },
                    navigationIcon = {
                        IconButton(onClick = ::finish) {
                            Icon(IncrementoIcons.Back, contentDescription = stringResource(R.string.ankrimento_navigate_up))
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .padding(top = padding.calculateTopPadding())
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp),
            ) {
                session?.let { SessionSection(it) }
                values?.let { settings ->
                    ReviewSection(settings)
                    ExtractionSection(settings)
                    ReadingSection(settings)
                }
                DeviceSection()
            }
        }
    }

    @Composable
    private fun SessionSection(s: Ankrimento.SessionSettings) {
        Header(R.string.ankrimento_settings_session)
        PercentSlider(R.string.ankrimento_settings_cards, s.cardCount, 10..300, suffix = "") { saveSession(s.copy(cardCount = it)) }
        PercentSlider(R.string.ankrimento_settings_topics, s.topicsPercent, 0..100) { saveSession(s.copy(topicsPercent = it)) }
        PercentSlider(R.string.ankrimento_settings_documents, s.documentsPercent, 0..100) { saveSession(s.copy(documentsPercent = it)) }
        PercentSlider(R.string.ankrimento_settings_random, s.randomPercent, 0..100) { saveSession(s.copy(randomPercent = it)) }
        Toggle(R.string.ankrimento_settings_include_new, s.includeNew) { saveSession(s.copy(includeNew = it)) }
        Toggle(R.string.ankrimento_settings_include_learning, s.includeLearning) { saveSession(s.copy(includeLearning = it)) }
        Toggle(R.string.ankrimento_settings_include_due, s.includeDue) { saveSession(s.copy(includeDue = it)) }
        Toggle(R.string.ankrimento_settings_auto_refill, s.autoRefill) { saveSession(s.copy(autoRefill = it)) }
    }

    @Composable
    private fun ReviewSection(v: JSONObject) {
        Header(R.string.ankrimento_settings_review)
        Toggle(R.string.ankrimento_settings_fail_pass, v.optBoolean("use_fail_pass_on_items")) { set("use_fail_pass_on_items", it) }
        PercentSlider(R.string.ankrimento_settings_more, v.optDouble("topic_more_adjustment_percent", 10.0).roundToInt(), 0..100) {
            set("topic_more_adjustment_percent", it.toDouble())
        }
        PercentSlider(R.string.ankrimento_settings_less, v.optDouble("topic_less_adjustment_percent", 10.0).roundToInt(), 0..100) {
            set("topic_less_adjustment_percent", it.toDouble())
        }
        NumberRow(R.string.ankrimento_settings_max_interval, v.optInt("topic_maximum_interval_days", 36500)) {
            set("topic_maximum_interval_days", it)
        }
        TextRow(R.string.ankrimento_settings_done_tag, v.optString("topic_done_tag")) { set("topic_done_tag", it) }
        Toggle(R.string.ankrimento_settings_postpone, v.optBoolean("topic_postpone_enabled")) { set("topic_postpone_enabled", it) }
        if (v.optBoolean("topic_postpone_enabled")) {
            NumberRow(
                R.string.ankrimento_settings_postpone_minutes,
                v.optInt("topic_postpone_minutes", 30),
            ) { set("topic_postpone_minutes", it) }
        }
        Toggle(R.string.ankrimento_settings_leech, v.optBoolean("learning_leech_enabled")) { set("learning_leech_enabled", it) }
        if (v.optBoolean("learning_leech_enabled")) {
            NumberRow(
                R.string.ankrimento_settings_leech_threshold,
                v.optInt("learning_leech_threshold", 5),
            ) { set("learning_leech_threshold", it) }
            TextRow(R.string.ankrimento_settings_leech_tag, v.optString("learning_leech_tag")) { set("learning_leech_tag", it) }
        }
    }

    @Composable
    private fun ExtractionSection(v: JSONObject) {
        Header(R.string.ankrimento_settings_extraction)
        Choice(
            R.string.ankrimento_settings_extract_type,
            listOf("item" to R.string.ankrimento_extract_item, "topic" to R.string.ankrimento_extract_as_topic),
            v.optString("extract_default_type", "item"),
        ) { set("extract_default_type", it) }
        Toggle(R.string.ankrimento_settings_highlight_extract, v.optBoolean("highlight_when_extracting", true)) {
            set("highlight_when_extracting", it)
        }
        Toggle(R.string.ankrimento_settings_copy_tags, v.optBoolean("extract_copy_source_tags")) { set("extract_copy_source_tags", it) }
        Toggle(R.string.ankrimento_settings_lower_priority, v.optBoolean("priority_lower_is_more_important", true)) {
            set("priority_lower_is_more_important", it)
        }
    }

    @Composable
    private fun ReadingSection(v: JSONObject) {
        Header(R.string.ankrimento_settings_reading)
        Choice(
            R.string.ankrimento_settings_pdf_appearance,
            listOf(
                "original" to R.string.ankrimento_page_original,
                "dark" to R.string.ankrimento_page_dark,
                "night" to R.string.ankrimento_page_night,
            ),
            v.optString("pdf_default_appearance", "original"),
        ) { set("pdf_default_appearance", it) }
    }

    @Composable
    private fun DeviceSection() {
        Header(R.string.ankrimento_settings_device)
        TextRow(R.string.ankrimento_profile, profile, R.string.ankrimento_profile_message) {
            device.profile = it
            profile = device.profile
        }
    }

    @Composable
    private fun Header(label: Int) {
        HorizontalDivider(Modifier.padding(top = 8.dp))
        Text(
            stringResource(label),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }

    @Composable
    private fun Toggle(
        label: Int,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
    ) {
        ListItem(
            headlineContent = { Text(stringResource(label)) },
            trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
            modifier = Modifier.clickable { onChange(!checked) },
        )
    }

    /** A value chosen on a slider, saved when the thumb is let go. */
    @Composable
    private fun PercentSlider(
        label: Int,
        value: Int,
        range: IntRange,
        suffix: String = "%",
        onChange: (Int) -> Unit,
    ) {
        var shown by remember(value) { mutableFloatStateOf(value.toFloat()) }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text("${stringResource(label)}: ${shown.roundToInt()}$suffix", style = MaterialTheme.typography.bodyLarge)
            Slider(
                value = shown,
                onValueChange = { shown = it },
                onValueChangeFinished = { if (shown.roundToInt() != value) onChange(shown.roundToInt()) },
                valueRange = range.first.toFloat()..range.last.toFloat(),
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Choice(
        label: Int,
        options: List<Pair<String, Int>>,
        selected: String,
        onChange: (String) -> Unit,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, (value, text) ->
                    SegmentedButton(
                        selected = value == selected,
                        onClick = { onChange(value) },
                        shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    ) { Text(stringResource(text)) }
                }
            }
        }
    }

    @Composable
    private fun NumberRow(
        label: Int,
        value: Int,
        onChange: (Int) -> Unit,
    ) {
        TextRow(label, value.toString(), numeric = true) { text -> text.toIntOrNull()?.let(onChange) }
    }

    /** A value shown in its row and edited in a dialog. */
    @Composable
    private fun TextRow(
        label: Int,
        value: String,
        message: Int? = null,
        numeric: Boolean = false,
        onChange: (String) -> Unit,
    ) {
        var editing by remember { mutableStateOf(false) }
        ListItem(
            headlineContent = { Text(stringResource(label)) },
            supportingContent = { Text(value) },
            modifier = Modifier.clickable { editing = true },
        )
        if (!editing) return
        var text by remember(value) { mutableStateOf(value) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(stringResource(label)) },
            text = {
                Column {
                    message?.let {
                        Text(
                            stringResource(it),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = if (numeric) it.filter(Char::isDigit) else it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    editing = false
                    onChange(text.trim())
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }

    companion object {
        fun intent(context: Context) = Intent(context, IncrementoSettingsActivity::class.java)
    }
}
