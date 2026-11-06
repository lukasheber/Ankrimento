// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.ankrimento.ui.IncrementoIcons
import com.ichi2.anki.ankrimento.ui.IncrementoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import kotlin.math.roundToInt

/**
 * Incremento's settings on one screen, in the order they matter while
 * studying: the session, then reviewing, extracting and reading, then this
 * device. Session and Incremento settings are upstream's own (stored as
 * desktop stores them, and normalized by upstream on every change).
 */
class IncrementoSettingsActivity : ComponentActivity() {
    private var session by mutableStateOf<Ankrimento.SessionSettings?>(null)
    private var presets by mutableStateOf<Ankrimento.SessionPresets?>(null)
    private var reviewButtons by mutableStateOf<JSONObject?>(null)
    private var choosingPreset by mutableStateOf(false)

    /** The preset being renamed, or "" for a new one. */
    private var namingPreset by mutableStateOf<String?>(null)
    private var deletingPreset by mutableStateOf<String?>(null)
    private var values by mutableStateOf<JSONObject?>(null)

    /** Incremento note types to update (null until read); [confirmingFormats] asks before changing them. */
    private var formatUpdates by mutableStateOf<List<Pair<String, List<String>>>?>(null)
    private var confirmingFormats by mutableStateOf(false)
    private val device by lazy { AnkrimentoSettings(this) }
    private var profile by mutableStateOf("")
    private var syncFolder by mutableStateOf<Uri?>(null)
    private var backingUp by mutableStateOf(false)
    private var automaticBackups by mutableStateOf(AutomaticBackups.Policy())

    /** A progress message while a backup is checked or restored. */
    private var restoring by mutableStateOf<Int?>(null)
    private var restoreCheck by mutableStateOf<Ankrimento.RestoreCheck?>(null)
    private val restoreFile by lazy { File(cacheDir, "full-backup-restore.zip") }

    private val pickBackup =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) checkBackup(uri)
        }

    private val saveBackup =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) exportBackup(uri)
        }

    private val pickAutomaticBackupFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            runCatching { AutomaticBackups.chooseFolder(this, uri) }
                .onSuccess { automaticBackups = it }
                .onFailure { error ->
                    Timber.w(error, "Choosing the backup folder failed")
                    toast(error.message ?: error.javaClass.simpleName)
                }
        }

    private val pickSyncFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            runCatching { Ankrimento.setSyncFolder(this, uri) }
                .onSuccess { syncFolder = device.syncFolder }
                .onFailure { error ->
                    Timber.w(error, "Choosing the sync folder failed")
                    toast(error.message ?: error.javaClass.simpleName)
                }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        profile = device.profile
        syncFolder = device.syncFolder
        automaticBackups = AutomaticBackups.load(this)
        setContent { IncrementoTheme { Settings() } }
        lifecycleScope.launch {
            runCatching {
                withCol {
                    Ankrimento.start(this@IncrementoSettingsActivity, this)
                    presets = Ankrimento.sessionPresets()
                    reviewButtons = Ankrimento.reviewButtons()
                    formatUpdates = Ankrimento.cardFormatUpdates()
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

    /**
     * Runs a preset change, then shows the settings it left. The selected preset names the
     * session deck, so the deck list's Incremento card follows it.
     */
    private fun changePresets(change: () -> Unit) {
        lifecycleScope.launch {
            runCatching {
                withCol {
                    change()
                    device.sessionDeckName = Ankrimento.sessionDeckName()
                    Ankrimento.sessionSettings() to Ankrimento.sessionPresets()
                }
            }.onSuccess { (sessionSettings, sessionPresets) ->
                session = sessionSettings
                presets = sessionPresets
            }.onFailure { error ->
                Timber.w(error, "Changing session presets failed")
                // Upstream's message without Python's exception name.
                toast((error.message ?: error.javaClass.simpleName).substringAfter("Error: "))
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

    private fun toast(text: String) = showToast(text)

    private fun setReviewButton(
        name: String,
        visible: Boolean,
    ) {
        lifecycleScope.launch {
            runCatching {
                withCol {
                    Ankrimento.setReviewButton(name, visible)
                    Ankrimento.reviewButtons()
                }
            }.onSuccess { reviewButtons = it }
                .onFailure { toast(it.message ?: it.javaClass.simpleName) }
        }
    }

    private fun chooseBackupFile() {
        lifecycleScope.launch {
            runCatching { withCol { Ankrimento.fullBackupName() } }
                .onSuccess { saveBackup.launch(it) }
                .onFailure { toast(it.message ?: it.javaClass.simpleName) }
        }
    }

    /** Copies the chosen backup beside the app and has upstream check and stage it, for the user to confirm. */
    private fun checkBackup(source: Uri) {
        restoring = R.string.ankrimento_restore_checking
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val input = contentResolver.openInputStreamSafely(source) ?: error("Cannot read the chosen file")
                    input.use { stream -> restoreFile.outputStream().use { stream.copyTo(it) } }
                }
                withCol { Ankrimento.stageRestore(restoreFile) }
            }.onSuccess { restoreCheck = it }
                .onFailure { error ->
                    Timber.w(error, "Checking a full backup failed")
                    withContext(Dispatchers.IO) { restoreFile.delete() }
                    toast(getString(R.string.ankrimento_restore_check_failed, error.message ?: error.javaClass.simpleName))
                }
            restoring = null
        }
    }

    private fun cancelRestore() {
        restoreCheck = null
        lifecycleScope.launch {
            runCatching { withCol { Ankrimento.discardRestore() } }
            withContext(Dispatchers.IO) { restoreFile.delete() }
        }
    }

    private fun restoreBackup() {
        restoreCheck = null
        restoring = R.string.ankrimento_restore_working
        lifecycleScope.launch {
            val restored =
                runCatching { FullBackupRestore.restoreStaged(this@IncrementoSettingsActivity) }
                    .onFailure { error ->
                        Timber.w(error, "Restoring a full backup failed")
                        toast(getString(R.string.ankrimento_restore_failed, error.message ?: error.javaClass.simpleName))
                    }.isSuccess
            withContext(Dispatchers.IO) { restoreFile.delete() }
            restoring = null
            if (!restored) return@launch
            toast(getString(R.string.ankrimento_restore_done))
            // Incremento's state in memory belongs to the replaced profile: start afresh.
            AnkrimentoApp.restartApp?.invoke(applicationContext) ?: finishAffinity()
        }
    }

    /** Writes the backup beside the app, then copies it to the file the user chose (a cloud folder works too). */
    private fun exportBackup(destination: Uri) {
        backingUp = true
        lifecycleScope.launch {
            val staging = File(cacheDir, "full-backup.zip")
            runCatching {
                val backup = withCol { Ankrimento.exportFullBackup(staging) }
                withContext(Dispatchers.IO) {
                    val output = contentResolver.openOutputStream(destination, "wt") ?: error("Cannot write the chosen file")
                    output.use { out -> staging.inputStream().use { it.copyTo(out) } }
                }
                backup
            }.onSuccess { backup ->
                toast(resources.getQuantityString(R.plurals.ankrimento_backup_saved, backup.cards, backup.cards, backup.files))
            }.onFailure { error ->
                Timber.w(error, "Full backup failed")
                runCatching { DocumentsContract.deleteDocument(contentResolver, destination) }
                toast(getString(R.string.ankrimento_backup_failed, error.message ?: error.javaClass.simpleName))
            }
            withContext(Dispatchers.IO) { staging.delete() }
            backingUp = false
        }
    }

    /** The settings' pages, as the home list shows them. */
    private enum class Page(
        val title: Int,
        val icon: androidx.compose.ui.graphics.vector.ImageVector,
    ) {
        SESSION(R.string.ankrimento_settings_session, IncrementoIcons.Tune),
        TOPICS(R.string.ankrimento_settings_topics_items, IncrementoIcons.Check),
        EXTRACTS(R.string.ankrimento_settings_extraction, IncrementoIcons.Add),
        READING(R.string.ankrimento_settings_reading_writing, IncrementoIcons.Book),
        BACKUP(R.string.ankrimento_settings_backup_restore, IncrementoIcons.Document),
        DEVICE(R.string.ankrimento_settings_device, IncrementoIcons.Sync),
        UPKEEP(R.string.ankrimento_settings_maintenance, IncrementoIcons.Settings),
    }

    /**
     * Desktop's many settings, kept apart: a home list of seven pages, each saying what it holds now
     * (the session's size, whether backups run, the sync folder), and a page per group.
     */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Settings() {
        var page by rememberSaveable { mutableStateOf<Page?>(null) }
        BackHandler(enabled = page != null) { page = null }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(page?.title ?: R.string.ankrimento_settings)) },
                    navigationIcon = {
                        IconButton(onClick = { if (page != null) page = null else finish() }) {
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
                when (page) {
                    null -> Home { page = it }
                    Page.SESSION -> session?.let { SessionSection(it) }
                    Page.TOPICS -> values?.let { ReviewSection(it) }
                    Page.EXTRACTS -> values?.let { ExtractionSection(it) }
                    Page.READING ->
                        values?.let { settings ->
                            ReadingSection(settings)
                            WritingSection(settings)
                        }
                    Page.BACKUP -> BackupSection()
                    Page.DEVICE -> DeviceSection()
                    Page.UPKEEP -> UpkeepSection()
                }
            }
        }
        if (confirmingFormats) ConfirmFormatUpdates()
        if (backingUp) Working(R.string.ankrimento_backup_export, R.string.ankrimento_backup_working)
        restoring?.let { Working(R.string.ankrimento_backup_restore, it) }
        restoreCheck?.let { ConfirmRestore(it) }
        if (choosingPreset) presets?.let { PresetSheet(it) }
        namingPreset?.let { PresetNameDialog(it) }
        deletingPreset?.let { DeletePresetDialog(it) }
    }

    /** Desktop's preset row: choose one to load it; save, update, rename or delete. */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun PresetSheet(current: Ankrimento.SessionPresets) {
        ModalBottomSheet(onDismissRequest = { choosingPreset = false }) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
                Text(
                    stringResource(R.string.ankrimento_preset_sheet_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                (listOf<String?>(null) + current.names).forEach { name ->
                    ListItem(
                        headlineContent = { Text(name ?: stringResource(R.string.ankrimento_preset_current)) },
                        leadingContent = { RadioButton(selected = current.selected == name, onClick = null) },
                        modifier =
                            Modifier.clickable {
                                choosingPreset = false
                                if (current.selected != name) changePresets { Ankrimento.loadSessionPreset(name) }
                            },
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                PresetAction(IncrementoIcons.Add, stringResource(R.string.ankrimento_preset_new)) { namingPreset = "" }
                current.selected?.let { selected ->
                    PresetAction(IncrementoIcons.Check, stringResource(R.string.ankrimento_preset_update, selected)) {
                        changePresets { Ankrimento.saveSessionPreset() }
                        toast(getString(R.string.ankrimento_preset_updated, selected))
                    }
                    PresetAction(IncrementoIcons.Edit, stringResource(R.string.ankrimento_preset_rename)) { namingPreset = selected }
                    PresetAction(IncrementoIcons.Delete, stringResource(R.string.ankrimento_preset_delete)) { deletingPreset = selected }
                }
            }
        }
    }

    @Composable
    private fun PresetAction(
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        label: String,
        onClick: () -> Unit,
    ) {
        ListItem(
            headlineContent = { Text(label) },
            leadingContent = { Icon(icon, contentDescription = null) },
            modifier =
                Modifier.clickable {
                    choosingPreset = false
                    onClick()
                },
        )
    }

    /** Names a new preset (from the current settings) or renames [renaming]. */
    @Composable
    private fun PresetNameDialog(renaming: String) {
        var name by remember(renaming) { mutableStateOf(renaming) }
        AlertDialog(
            onDismissRequest = { namingPreset = null },
            title = {
                Text(
                    stringResource(
                        if (renaming.isEmpty()) R.string.ankrimento_preset_new_title else R.string.ankrimento_preset_rename_title,
                    ),
                )
            },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.ankrimento_preset_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        namingPreset = null
                        val chosen = name.trim()
                        changePresets {
                            if (renaming.isEmpty()) {
                                Ankrimento.addSessionPreset(
                                    chosen,
                                )
                            } else {
                                Ankrimento.renameSessionPreset(renaming, chosen)
                            }
                        }
                    },
                ) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { namingPreset = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }

    @Composable
    private fun DeletePresetDialog(name: String) {
        AlertDialog(
            onDismissRequest = { deletingPreset = null },
            title = { Text(stringResource(R.string.ankrimento_preset_delete_title, name)) },
            text = { Text(stringResource(R.string.ankrimento_preset_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    deletingPreset = null
                    changePresets { Ankrimento.deleteSessionPreset(name) }
                }) { Text(stringResource(R.string.ankrimento_preset_delete)) }
            },
            dismissButton = { TextButton(onClick = { deletingPreset = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }

    @Composable
    private fun Working(
        title: Int,
        message: Int,
    ) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text(stringResource(title)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Text(stringResource(message), Modifier.padding(start = 16.dp))
                }
            },
        )
    }

    /** Upstream's confirmation: what the backup holds, and that it replaces everything on this device. */
    @Composable
    private fun ConfirmRestore(check: Ankrimento.RestoreCheck) {
        AlertDialog(
            onDismissRequest = ::cancelRestore,
            title = { Text(stringResource(R.string.ankrimento_restore_confirm_title)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.ankrimento_restore_contents, check.sourceProfile, check.cards, check.media, check.files),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (check.profileMismatch) {
                        Text(stringResource(R.string.ankrimento_restore_other_profile), Modifier.padding(top = 8.dp))
                    }
                    if (check.missingCovers > 0) {
                        Text(stringResource(R.string.ankrimento_restore_missing_covers, check.missingCovers), Modifier.padding(top = 8.dp))
                    }
                    Text(stringResource(R.string.ankrimento_restore_replaces), Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = ::restoreBackup) { Text(stringResource(R.string.ankrimento_restore_confirm)) } },
            dismissButton = { TextButton(onClick = ::cancelRestore) { Text(stringResource(android.R.string.cancel)) } },
        )
    }

    @Composable
    private fun BackupSection() {
        ListItem(
            headlineContent = { Text(stringResource(R.string.ankrimento_backup_export)) },
            supportingContent = { Text(stringResource(R.string.ankrimento_backup_export_summary)) },
            modifier = Modifier.clickable(enabled = !backingUp, onClick = ::chooseBackupFile),
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.ankrimento_backup_restore)) },
            supportingContent = { Text(stringResource(R.string.ankrimento_backup_restore_summary)) },
            modifier =
                Modifier.clickable(enabled = restoring == null) {
                    pickBackup.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                },
        )
        AutomaticBackupRows()
    }

    /** Desktop's automatic full backups, into a folder picked on this device. */
    @Composable
    private fun AutomaticBackupRows() {
        val policy = automaticBackups
        val on = policy.enabled && policy.folder != null
        ListItem(
            headlineContent = { Text(stringResource(R.string.ankrimento_auto_backup)) },
            supportingContent = { Text(stringResource(R.string.ankrimento_auto_backup_summary)) },
            trailingContent = { Switch(checked = on, onCheckedChange = ::setAutomaticBackups) },
            modifier = Modifier.clickable { setAutomaticBackups(!on) },
        )
        if (!on) return
        ListItem(
            headlineContent = { Text(stringResource(R.string.ankrimento_auto_backup_folder)) },
            supportingContent = { Text(folderName(policy.folder)) },
            modifier = Modifier.clickable { pickAutomaticBackupFolder.launch(policy.folder) },
        )
        Toggle(R.string.ankrimento_auto_backup_on_open, policy.onOpen) { changeAutomaticBackups(policy.copy(onOpen = it)) }
        ListItem(
            headlineContent = { Text(stringResource(R.string.ankrimento_auto_backup_every)) },
            supportingContent = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((hours, label) in AUTO_BACKUP_INTERVALS) {
                        FilterChip(
                            selected = policy.intervalHours == hours,
                            onClick = { changeAutomaticBackups(policy.copy(intervalHours = hours)) },
                            label = { Text(stringResource(label)) },
                        )
                    }
                }
            },
        )
        PercentSlider(R.string.ankrimento_auto_backup_versions, policy.versions, 1..AutomaticBackups.MAX_VERSIONS, suffix = "") {
            changeAutomaticBackups(policy.copy(versions = it))
        }
        ListItem(
            headlineContent = { Text(stringResource(R.string.ankrimento_auto_backup_now)) },
            supportingContent = {
                Text(
                    if (policy.lastSuccess > 0) {
                        stringResource(
                            R.string.ankrimento_auto_backup_last,
                            DateUtils.getRelativeDateTimeString(
                                this,
                                policy.lastSuccess,
                                DateUtils.MINUTE_IN_MILLIS,
                                DateUtils.WEEK_IN_MILLIS,
                                0,
                            ),
                        )
                    } else {
                        stringResource(R.string.ankrimento_auto_backup_never)
                    },
                )
            },
            modifier = Modifier.clickable(enabled = !backingUp, onClick = ::backUpNow),
        )
    }

    private fun setAutomaticBackups(on: Boolean) {
        if (on && automaticBackups.folder == null) {
            pickAutomaticBackupFolder.launch(null)
        } else {
            changeAutomaticBackups(automaticBackups.copy(enabled = on))
        }
    }

    private fun changeAutomaticBackups(policy: AutomaticBackups.Policy) {
        AutomaticBackups.save(this, policy)
        automaticBackups = policy
    }

    private fun backUpNow() {
        backingUp = true
        toast(getString(R.string.ankrimento_backup_working))
        lifecycleScope.launch {
            runCatching { AutomaticBackups.backUp(this@IncrementoSettingsActivity) }
                .onSuccess { name -> name?.let { toast(getString(R.string.ankrimento_auto_backup_saved, it)) } }
                .onFailure { error ->
                    Timber.w(error, "Backing up now failed")
                    toast(getString(R.string.ankrimento_backup_failed, error.message ?: error.javaClass.simpleName))
                }
            automaticBackups = AutomaticBackups.load(this@IncrementoSettingsActivity)
            backingUp = false
        }
    }

    @Composable
    private fun Home(open: (Page) -> Unit) {
        Page.entries.forEach { page ->
            ListItem(
                headlineContent = { Text(stringResource(page.title)) },
                supportingContent = { Text(summary(page), maxLines = 2) },
                leadingContent = { Icon(page.icon, contentDescription = null) },
                modifier = Modifier.clickable { open(page) },
            )
        }
    }

    /** What a page holds now, in a line. */
    @Composable
    private fun summary(page: Page): String =
        when (page) {
            Page.SESSION ->
                session
                    ?.let { s ->
                        val size = stringResource(R.string.ankrimento_settings_session_summary, s.cardCount, s.topicsPercent)
                        presets?.selected?.let { "$it · $size" } ?: size
                    }.orEmpty()
            Page.TOPICS -> stringResource(R.string.ankrimento_settings_topics_items_summary)
            Page.EXTRACTS -> stringResource(R.string.ankrimento_settings_extraction_summary)
            Page.READING -> stringResource(R.string.ankrimento_settings_reading_writing_summary)
            Page.BACKUP ->
                stringResource(
                    if (automaticBackups.enabled) {
                        R.string.ankrimento_settings_backup_auto_on
                    } else {
                        R.string.ankrimento_settings_backup_auto_off
                    },
                )
            Page.DEVICE ->
                stringResource(
                    R.string.ankrimento_settings_device_summary,
                    profile,
                    syncFolder?.let(::folderName) ?: stringResource(R.string.ankrimento_settings_no_sync_folder),
                )
            Page.UPKEEP ->
                formatUpdates?.let { updates ->
                    if (updates.isEmpty()) {
                        stringResource(R.string.ankrimento_card_formats_current)
                    } else {
                        stringResource(R.string.ankrimento_card_formats_pending, updates.joinToString { it.first })
                    }
                } ?: stringResource(R.string.ankrimento_card_formats)
        }

    @Composable
    private fun SessionSection(s: Ankrimento.SessionSettings) {
        presets?.let { current ->
            ListItem(
                headlineContent = { Text(stringResource(R.string.ankrimento_preset)) },
                supportingContent = { Text(current.selected ?: stringResource(R.string.ankrimento_preset_current)) },
                trailingContent = { Icon(IncrementoIcons.ExpandMore, contentDescription = null) },
                modifier = Modifier.clickable { choosingPreset = true },
            )
        }
        Header(R.string.ankrimento_settings_size_mix)
        PercentSlider(R.string.ankrimento_settings_cards, s.cardCount, 10..300, suffix = "") { saveSession(s.copy(cardCount = it)) }
        PercentSlider(R.string.ankrimento_settings_topics, s.topicsPercent, 0..100) { saveSession(s.copy(topicsPercent = it)) }
        PercentSlider(R.string.ankrimento_settings_documents, s.documentsPercent, 0..100) { saveSession(s.copy(documentsPercent = it)) }
        PercentSlider(R.string.ankrimento_settings_random, s.randomPercent, 0..100) { saveSession(s.copy(randomPercent = it)) }
        Header(R.string.ankrimento_settings_which_cards)
        Toggle(R.string.ankrimento_settings_include_new, s.includeNew) { saveSession(s.copy(includeNew = it)) }
        Toggle(R.string.ankrimento_settings_include_learning, s.includeLearning) { saveSession(s.copy(includeLearning = it)) }
        Toggle(R.string.ankrimento_settings_include_due, s.includeDue) { saveSession(s.copy(includeDue = it)) }
        Toggle(R.string.ankrimento_settings_auto_refill, s.autoRefill) { saveSession(s.copy(autoRefill = it)) }
        ListItem(
            headlineContent = { Text(stringResource(R.string.ankrimento_options_title)) },
            supportingContent = { Text(stringResource(R.string.ankrimento_options_summary)) },
            modifier = Modifier.clickable { startActivity(SessionOptionsActivity.intent(this)) },
        )
    }

    @Composable
    private fun ReviewSection(v: JSONObject) {
        Header(R.string.ankrimento_settings_answer_buttons, first = true)
        reviewButtons?.let { buttons ->
            Toggle(R.string.ankrimento_settings_review_buttons, buttons.optBoolean("group", true)) { setReviewButton("group", it) }
            if (buttons.optBoolean("group", true)) {
                Toggle(R.string.ankrimento_settings_button_done, buttons.optBoolean("done", true)) { setReviewButton("done", it) }
                Toggle(
                    R.string.ankrimento_settings_button_postpone,
                    buttons.optBoolean("postpone", true),
                ) { setReviewButton("postpone", it) }
                Toggle(R.string.ankrimento_settings_button_extract, buttons.optBoolean("extract", true)) { setReviewButton("extract", it) }
            }
        }
        Toggle(R.string.ankrimento_settings_fail_pass, v.optBoolean("use_fail_pass_on_items")) { set("use_fail_pass_on_items", it) }
        TextRow(R.string.ankrimento_settings_done_tag, v.optString("topic_done_tag")) { set("topic_done_tag", it) }
        Header(R.string.ankrimento_settings_topic_pace)
        PercentSlider(
            R.string.ankrimento_settings_more,
            v.optDouble("topic_more_adjustment_percent", 10.0).roundToInt(),
            0..100,
            help = R.string.ankrimento_settings_sooner_help,
        ) { set("topic_more_adjustment_percent", it.toDouble()) }
        PercentSlider(
            R.string.ankrimento_settings_less,
            v.optDouble("topic_less_adjustment_percent", 10.0).roundToInt(),
            0..100,
            help = R.string.ankrimento_settings_later_help,
        ) { set("topic_less_adjustment_percent", it.toDouble()) }
        NumberRow(R.string.ankrimento_settings_max_interval, v.optInt("topic_maximum_interval_days", 36500)) {
            set("topic_maximum_interval_days", it)
        }
        Header(R.string.ankrimento_settings_postpone_leech)
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
        Header(R.string.ankrimento_settings_reading, first = true)
        Choice(
            R.string.ankrimento_settings_pdf_appearance,
            listOf(
                "original" to R.string.ankrimento_page_original,
                "dark" to R.string.ankrimento_page_dark,
                "night" to R.string.ankrimento_page_night,
            ),
            v.optString("pdf_default_appearance", "original"),
        ) { set("pdf_default_appearance", it) }
        Toggle(R.string.ankrimento_settings_pdf_force, v.optBoolean("pdf_force_default_appearance")) {
            set("pdf_force_default_appearance", it)
        }
        Toggle(R.string.ankrimento_settings_pdf_delete_buttons, v.optBoolean("pdf_show_highlight_delete_buttons", true)) {
            set("pdf_show_highlight_delete_buttons", it)
        }
        Toggle(R.string.ankrimento_settings_pdf_scroll_top, v.optBoolean("pdf_scroll_to_top_on_page_change", true)) {
            set("pdf_scroll_to_top_on_page_change", it)
        }
    }

    @Composable
    private fun WritingSection(v: JSONObject) {
        Header(R.string.ankrimento_settings_writing)
        Toggle(R.string.ankrimento_settings_writing_backups, v.optBoolean("writing_backups_enabled", true)) {
            set("writing_backups_enabled", it)
        }
        if (v.optBoolean("writing_backups_enabled", true)) BackupIntervals(v)
        Choice(
            R.string.ankrimento_settings_word_count,
            listOf("simple" to R.string.ankrimento_settings_word_simple, "word_like" to R.string.ankrimento_settings_word_like),
            v.optString("writing_word_count_mode", "simple"),
        ) { set("writing_word_count_mode", it) }
    }

    /** Desktop's writing backup intervals: each chosen one keeps a rolling copy of each writing. */
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun BackupIntervals(v: JSONObject) {
        val chosen = v.optJSONArray("writing_backup_tiers")?.let { array -> List(array.length()) { array.getString(it) } }.orEmpty()
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(stringResource(R.string.ankrimento_settings_writing_backup_every), style = MaterialTheme.typography.bodyLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BACKUP_INTERVALS.forEach { tier ->
                    val selected = tier in chosen
                    FilterChip(
                        selected = selected,
                        onClick = {
                            val next = if (selected) chosen - tier else chosen + tier
                            set("writing_backup_tiers", org.json.JSONArray(next))
                        },
                        label = { Text(intervalLabel(tier)) },
                    )
                }
            }
            Text(
                stringResource(R.string.ankrimento_settings_writing_backup_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    /** "5m", "6h", "7d" as the phone's language says them. */
    @Composable
    private fun intervalLabel(tier: String): String {
        val count = tier.dropLast(1).toIntOrNull() ?: return tier
        val plural =
            when (tier.last()) {
                'm' -> R.plurals.ankrimento_minutes_ago
                'h' -> R.plurals.ankrimento_hours_ago
                else -> R.plurals.ankrimento_days_ago
            }
        return pluralStringResource(plural, count, count)
    }

    /** Desktop's Tools: card format updates and the support bundle. */
    @Composable
    private fun UpkeepSection() {
        val pending = formatUpdates.orEmpty()
        ListItem(
            headlineContent = { Text(stringResource(R.string.ankrimento_card_formats)) },
            supportingContent = {
                Text(
                    if (pending.isEmpty()) {
                        stringResource(R.string.ankrimento_card_formats_current)
                    } else {
                        stringResource(R.string.ankrimento_card_formats_pending, pending.joinToString { it.first })
                    },
                )
            },
            modifier = Modifier.clickable(enabled = pending.isNotEmpty()) { confirmingFormats = true },
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.ankrimento_support_bundle)) },
            supportingContent = { Text(stringResource(R.string.ankrimento_support_bundle_summary)) },
            modifier = Modifier.clickable(onClick = ::shareSupportBundle),
        )
    }

    @Composable
    private fun ConfirmFormatUpdates() {
        val names = formatUpdates.orEmpty().joinToString { it.first }
        AlertDialog(
            onDismissRequest = { confirmingFormats = false },
            title = { Text(stringResource(R.string.ankrimento_card_formats)) },
            text = { Text(stringResource(R.string.ankrimento_card_formats_confirm, names)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingFormats = false
                    applyFormatUpdates()
                }) { Text(stringResource(R.string.ankrimento_card_formats_apply)) }
            },
            dismissButton = { TextButton(onClick = { confirmingFormats = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }

    private fun applyFormatUpdates() {
        lifecycleScope.launch {
            runCatching { withCol { Ankrimento.applyCardFormatUpdates().also { formatUpdates = Ankrimento.cardFormatUpdates() } } }
                .onSuccess { toast(getString(R.string.ankrimento_card_formats_done)) }
                .onFailure { error ->
                    Timber.w(error, "Updating card formats failed")
                    toast(error.message ?: error.javaClass.simpleName)
                }
        }
    }

    /** Builds desktop's support bundle beside the app and hands it to Android's share sheet. */
    private fun shareSupportBundle() {
        lifecycleScope.launch {
            val folder = File(cacheDir, "support").apply { mkdirs() }
            val bundle = File(folder, "incremento_support_bundle_${java.time.LocalDate.now()}.zip")
            runCatching { withCol { Ankrimento.supportBundle(bundle) } }
                .onSuccess {
                    val uri =
                        androidx.core.content.FileProvider.getUriForFile(
                            this@IncrementoSettingsActivity,
                            "$packageName.apkgfileprovider",
                            bundle,
                        )
                    val share =
                        Intent(Intent.ACTION_SEND)
                            .setType("application/zip")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(Intent.createChooser(share, getString(R.string.ankrimento_support_bundle)))
                }.onFailure { error ->
                    Timber.w(error, "Creating the support bundle failed")
                    toast(getString(R.string.ankrimento_support_bundle_failed, error.message ?: error.javaClass.simpleName))
                }
        }
    }

    @Composable
    private fun DeviceSection() {
        TextRow(R.string.ankrimento_profile, profile, R.string.ankrimento_profile_message) {
            device.profile = it
            profile = device.profile
        }
        ListItem(
            headlineContent = { Text(stringResource(R.string.ankrimento_sync_folder)) },
            supportingContent = {
                Text(syncFolder?.let(::folderName) ?: stringResource(R.string.ankrimento_sync_folder_none))
            },
            modifier = Modifier.clickable { pickSyncFolder.launch(syncFolder) },
        )
    }

    /** The picked folder as the user knows it: "Documents/Incremento" from "primary:Documents/Incremento". */
    private fun folderName(folder: Uri): String =
        runCatching { DocumentsContract.getTreeDocumentId(folder) }
            .getOrNull()
            ?.let { id -> id.substringAfter(':').ifEmpty { id } }
            ?: folder.toString()

    /** A group's title on a page; the first on a page goes without the line above it. */
    @Composable
    private fun Header(
        label: Int,
        first: Boolean = false,
    ) {
        if (!first) HorizontalDivider(Modifier.padding(top = 8.dp))
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
        help: Int? = null,
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
            help?.let {
                Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
        /** Desktop's writing backup intervals (writing_manager._BACKUP_SLOTS), in its order. */
        private val BACKUP_INTERVALS = listOf("1m", "5m", "15m", "30m", "1h", "6h", "1d", "7d")

        /** Desktop's interval in hours (0: off), as the phone offers it. */
        private val AUTO_BACKUP_INTERVALS =
            listOf(
                0 to R.string.ankrimento_auto_backup_off,
                12 to R.string.ankrimento_auto_backup_12h,
                24 to R.string.ankrimento_auto_backup_day,
                72 to R.string.ankrimento_auto_backup_3days,
                168 to R.string.ankrimento_auto_backup_week,
            )

        fun intent(context: Context) = Intent(context, IncrementoSettingsActivity::class.java)
    }
}
