// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.ankrimento.ui.IncrementoIcons
import com.ichi2.anki.ankrimento.ui.IncrementoTheme
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Incremento's statistics, as desktop's statistics dialog shows them, by its
 * scopes: today, 7 days, 30 days, a year and all time. Every scope opens with
 * desktop's progress dashboard (pages, study time and cards, compared with the
 * period before, and a chart of days or, for a year, weeks); a period adds its
 * streak and the activity calendar, today and all time what was reviewed by
 * kind and by tag. Numbers and texts are desktop's ([Ankrimento.statisticsView]).
 */
class StatisticsActivity : ComponentActivity() {
    private val views = mutableStateMapOf<String, JSONObject>()
    private var scope by mutableStateOf(FIRST_SCOPE)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        savedInstanceState?.getString(STATE_SCOPE)?.let { scope = it }
        setContent { IncrementoTheme { Statistics() } }
        load(scope)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SCOPE, scope)
    }

    private fun load(key: String) {
        if (key in views) return
        lifecycleScope.launch {
            runCatching {
                withCol {
                    Ankrimento.start(this@StatisticsActivity, this)
                    Ankrimento.statisticsView(key)
                }
            }.onSuccess { views[key] = it }
                .onFailure { error ->
                    Timber.w(error, "Loading statistics failed")
                    this@StatisticsActivity.showToast(error.message ?: error.javaClass.simpleName)
                }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Statistics() {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.ankrimento_statistics_title)) },
                    navigationIcon = {
                        IconButton(onClick = ::finish) {
                            Icon(IncrementoIcons.Back, contentDescription = stringResource(R.string.ankrimento_navigate_up))
                        }
                    },
                )
            },
        ) { padding ->
            // The scopes' names come with any loaded scope; today's loads first.
            val scopes = views.values.firstOrNull()?.getJSONArray("scopes") ?: return@Scaffold
            Column(
                Modifier
                    .padding(top = padding.calculateTopPadding())
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp),
            ) {
                Row(
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    pairs(scopes).forEach { (key, label) ->
                        FilterChip(
                            selected = key == scope,
                            onClick = {
                                scope = key
                                load(key)
                            },
                            label = { Text(label) },
                        )
                    }
                }
                val view = views[scope] ?: return@Column
                Dashboard(view.getJSONObject("dashboard"))
                if (view.has("days")) {
                    Note(view.getString("insight"))
                    Heatmap(view)
                } else {
                    Kinds(view)
                    Tags(view)
                }
                Section(view.getString("media_title"))
                pairs(view.getJSONArray("media")).forEach { (label, value) -> Line(label, value) }
            }
        }
    }

    /** Desktop's ProgressDashboard: three totals, then a chart of the chosen one; a tap on a bar gives its day or week. */
    @Composable
    private fun Dashboard(dashboard: JSONObject) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(dashboard.getString("heading"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(
                    dashboard.getString("streak"),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        val tiles = dashboard.getJSONArray("tiles")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            objects(tiles).forEach { tile -> Tile(tile, Modifier.weight(1f)) }
        }
        val metrics = pairs(dashboard.getJSONArray("metrics"))
        var metric by rememberSaveable { mutableStateOf(metrics.first().first) }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            metrics.forEachIndexed { index, (key, label) ->
                SegmentedButton(
                    selected = key == metric,
                    onClick = { metric = key },
                    shape = SegmentedButtonDefaults.itemShape(index, metrics.size),
                ) { Text(label, maxLines = 1) }
            }
        }
        Note(dashboard.getString("chart_window") + "\n" + dashboard.getString("consistency"))
        Text(
            dashboard.getString("chart_title"),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
        )
        Bars(objects(dashboard.getJSONArray("bars")), metric, dashboard.getString("empty"))
    }

    @Composable
    private fun Tile(
        tile: JSONObject,
        modifier: Modifier,
    ) {
        ElevatedCard(modifier) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(tile.getString("name"), style = MaterialTheme.typography.labelMedium)
                Text(tile.getString("value"), style = MaterialTheme.typography.headlineSmall)
                Text(tile.getString("hint"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                tile.optString("comparison").takeIf { it.isNotEmpty() && it != "null" }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    /**
     * One series, so one colour and no legend: thin bars rounded at the top on a
     * baseline, the picked one darker. A tap picks a bar; its line shows below.
     */
    @Composable
    private fun Bars(
        bars: List<JSONObject>,
        metric: String,
        empty: String,
    ) {
        if (bars.isEmpty()) return
        val values = bars.map { it.optDouble(metric, 0.0) }
        var picked by remember(bars.size) { mutableIntStateOf(bars.lastIndex) }
        val detail = bars[picked.coerceIn(bars.indices)].getJSONObject("detail").getString(metric)
        if (values.all { it <= 0.0 }) Note(empty)
        val bar = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        val pickedBar = MaterialTheme.colorScheme.primary
        val axis = MaterialTheme.colorScheme.outlineVariant
        val peak = values.max().takeIf { it > 0 } ?: 1.0
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(160.dp)
                .padding(horizontal = 16.dp)
                .semantics { contentDescription = detail }
                .pointerInput(bars.size) {
                    detectTapGestures { offset -> picked = (offset.x / (size.width / bars.size)).toInt().coerceIn(bars.indices) }
                },
        ) {
            val slot = size.width / bars.size
            val gap = 2.dp.toPx()
            val width = (slot - gap).coerceIn(1f, 28.dp.toPx())
            val radius = minOf(4.dp.toPx(), width / 2)
            values.forEachIndexed { index, value ->
                val height = (size.height * value / peak).toFloat()
                if (height <= 0f) return@forEachIndexed
                val left = index * slot + (slot - width) / 2
                val path =
                    Path().apply {
                        addRoundRect(
                            RoundRect(
                                left = left,
                                top = size.height - height,
                                right = left + width,
                                bottom = size.height,
                                topLeftCornerRadius = CornerRadius(radius),
                                topRightCornerRadius = CornerRadius(radius),
                            ),
                        )
                    }
                drawPath(path, if (index == picked) pickedBar else bar)
            }
            drawLine(axis, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        }
        // Every label for a week of days; the first and last for longer charts.
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
            if (bars.size <= 7) {
                bars.forEach { Axis(it.getString("label"), Modifier.weight(1f), TextAlign.Center) }
            } else {
                Axis(bars.first().getString("label"), Modifier.weight(1f), TextAlign.Start)
                Axis(bars.last().getString("label"), Modifier.weight(1f), TextAlign.End)
            }
        }
        Text(detail, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    }

    @Composable
    private fun Axis(
        label: String,
        modifier: Modifier,
        align: TextAlign,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = align,
            modifier = modifier,
        )
    }

    /**
     * Desktop's activity calendar: a row per weekday from Monday, a column per
     * week, darker for more activity. It scrolls sideways and opens on the
     * latest weeks; a tap on a day gives its numbers.
     */
    @Composable
    private fun Heatmap(view: JSONObject) {
        Section(view.getString("heatmap_title"))
        val days = objects(view.getJSONArray("days"))
        val months = strings(view.getJSONArray("months"))
        val weekdays = strings(view.getJSONArray("weekdays"))
        val first = view.getInt("first_weekday")
        var picked by remember(days.size) { mutableIntStateOf(-1) }
        val cell = 16.dp
        val gap = 2.dp
        val step = cell + gap
        val empty = MaterialTheme.colorScheme.surfaceVariant
        val fill = MaterialTheme.colorScheme.primary
        val outline = MaterialTheme.colorScheme.onSurface
        val scroll = rememberScrollState()
        LaunchedEffect(days.size, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
        Row(Modifier.padding(horizontal = 16.dp)) {
            Column(Modifier.padding(top = step, end = 6.dp)) {
                weekdays.forEach { day ->
                    Box(Modifier.height(step), contentAlignment = Alignment.CenterStart) {
                        Text(day, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
            Column(Modifier.horizontalScroll(scroll)) {
                Row(Modifier.height(step)) {
                    months.forEach { month ->
                        Box(Modifier.width(step).wrapContentWidth(Alignment.Start, unbounded = true)) {
                            Text(
                                month,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
                Canvas(
                    Modifier
                        .width(step * months.size)
                        .height(step * 7)
                        .semantics { contentDescription = view.getString("heatmap_title") }
                        .pointerInput(days.size) {
                            detectTapGestures { offset ->
                                val slot = (offset.x / step.toPx()).toInt() * 7 + (offset.y / step.toPx()).toInt() - first
                                if (slot in days.indices) picked = slot
                            }
                        },
                ) {
                    val size = cell.toPx()
                    val radius = CornerRadius(3.dp.toPx())
                    days.forEachIndexed { index, day ->
                        val slot = first + index
                        val topLeft = Offset((slot / 7) * step.toPx(), (slot % 7) * step.toPx())
                        val intensity = day.optInt("intensity")
                        val color = if (intensity <= 0) empty else fill.copy(alpha = INTENSITY_ALPHA[intensity.coerceIn(1, 4) - 1])
                        drawRoundRect(color, topLeft, Size(size, size), radius)
                        if (index == picked) drawRoundRect(outline, topLeft, Size(size, size), radius, style = Stroke(2.dp.toPx()))
                    }
                }
            }
        }
        Note(days.getOrNull(picked)?.getString("detail") ?: view.getString("heatmap_hint"))
    }

    @Composable
    private fun Kinds(view: JSONObject) {
        Section(view.getString("kinds_title"))
        val kinds = pairs(view.getJSONArray("kinds"))
        if (kinds.isEmpty()) Note(view.getString("empty"))
        kinds.forEach { (label, value) -> Line(label, value) }
    }

    /** Desktop's tag pies as rows, a better fit for a phone: each tag's cards and time, its bar sized by cards. */
    @Composable
    private fun Tags(view: JSONObject) {
        val titles = view.getJSONObject("tag_titles")
        val tags = view.getJSONObject("tags")
        for (kind in listOf("topics", "items")) {
            Section(titles.getString(kind))
            val rows = tags.getJSONArray(kind)
            if (rows.length() == 0) {
                Note("–")
                continue
            }
            val peak = (0 until rows.length()).maxOf { rows.getJSONArray(it).getDouble(3) }.takeIf { it > 0 } ?: 1.0
            for (index in 0 until rows.length()) {
                val row = rows.getJSONArray(index)
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(row.getString(0), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text("${row.getString(1)} · ${row.getString(2)}", style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { (row.getDouble(3) / peak).toFloat() },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        drawStopIndicator = {},
                        gapSize = 0.dp,
                    )
                }
            }
        }
        // Desktop's hint speaks of pie slices; the phone shows rows.
        Note(stringResource(R.string.ankrimento_stats_tag_rows))
    }

    @Composable
    private fun Section(label: String) {
        HorizontalDivider(Modifier.padding(top = 12.dp))
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }

    @Composable
    private fun Note(text: String) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }

    @Composable
    private fun Line(
        label: String,
        value: String,
    ) {
        ListItem(headlineContent = { Text(label) }, trailingContent = { Text(value, style = MaterialTheme.typography.bodyLarge) })
    }

    private fun objects(array: JSONArray) = List(array.length()) { array.getJSONObject(it) }

    private fun strings(array: JSONArray) = List(array.length()) { array.getString(it) }

    private fun pairs(array: JSONArray) =
        List(array.length()) { array.getJSONArray(it).let { pair -> pair.getString(0) to pair.getString(1) } }

    companion object {
        private const val FIRST_SCOPE = "daily"
        private const val STATE_SCOPE = "scope"

        /** Activity levels 1–4 (desktop's heatmap), one hue from light to full. */
        private val INTENSITY_ALPHA = floatArrayOf(0.3f, 0.5f, 0.75f, 1f)

        fun intent(context: Context) = Intent(context, StatisticsActivity::class.java)
    }
}
