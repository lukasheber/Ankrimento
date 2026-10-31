// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.ankrimento.ui.IncrementoIcons
import com.ichi2.anki.ankrimento.ui.IncrementoTheme
import kotlinx.coroutines.launch
import org.json.JSONObject
import timber.log.Timber
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Incremento's statistics, as desktop's statistics dialog reads them: today
 * at a glance, the last days as bars (tap one for its numbers), what was
 * reviewed by kind, and reading and watching.
 */
class StatisticsActivity : ComponentActivity() {
    private var stats by mutableStateOf<JSONObject?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { IncrementoTheme { Statistics() } }
        lifecycleScope.launch {
            runCatching {
                withCol {
                    Ankrimento.start(this@StatisticsActivity, this)
                    Ankrimento.statistics(DAYS)
                }
            }.onSuccess { stats = it }
                .onFailure { error ->
                    Timber.w(error, "Loading statistics failed")
                    Toast.makeText(this@StatisticsActivity, error.message ?: error.javaClass.simpleName, Toast.LENGTH_LONG).show()
                }
        }
    }

    private class Day(
        val date: LocalDate,
        val cards: Int,
        val seconds: Double,
        val pages: Int,
    )

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
            val data = stats ?: return@Scaffold
            val history = data.getJSONArray("history")
            val days =
                List(history.length()) { index ->
                    val day = history.getJSONObject(index)
                    Day(LocalDate.parse(day.getString("date")), day.getInt("cards"), day.getDouble("seconds"), day.getInt("pages"))
                }
            val today = data.getJSONObject("today")
            val media = data.getJSONObject("media")
            Column(
                Modifier
                    .padding(top = padding.calculateTopPadding())
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Tile(
                        R.string.ankrimento_stats_reviewed_today,
                        total(today.getJSONObject("cards")).toInt().toString(),
                        Modifier.weight(1f),
                    )
                    Tile(R.string.ankrimento_stats_time_today, duration(total(today.getJSONObject("seconds"))), Modifier.weight(1f))
                    Tile(R.string.ankrimento_stats_pages_today, (days.lastOrNull()?.pages ?: 0).toString(), Modifier.weight(1f))
                }
                Section(R.string.ankrimento_stats_last_days)
                DailyBars(days)
                Section(R.string.ankrimento_stats_by_kind_today)
                ByKind(today)
                Section(R.string.ankrimento_stats_by_kind_lifetime)
                ByKind(data.getJSONObject("lifetime"))
                Section(R.string.ankrimento_stats_reading)
                Line(R.string.ankrimento_stats_pages_read, media.optInt("pages").toString())
                Line(R.string.ankrimento_stats_videos, media.optInt("videos_watched").toString())
                Line(R.string.ankrimento_stats_watch_time, duration(media.optDouble("video_watch_seconds", 0.0)))
            }
        }
    }

    @Composable
    private fun Tile(
        label: Int,
        value: String,
        modifier: Modifier,
    ) {
        ElevatedCard(modifier) {
            Column(Modifier.padding(16.dp)) {
                Text(value, style = MaterialTheme.typography.headlineMedium)
                Text(
                    stringResource(label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    @Composable
    private fun Section(label: Int) {
        HorizontalDivider(Modifier.padding(top = 12.dp))
        Text(
            stringResource(label),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }

    /**
     * Cards per day: one series, so one colour and no legend. Bars are rounded
     * at the top and sit on the baseline; a tap on a day shows its numbers above.
     */
    @Composable
    private fun DailyBars(days: List<Day>) {
        if (days.isEmpty()) return
        var picked by remember { mutableIntStateOf(days.lastIndex) }
        val bar = MaterialTheme.colorScheme.primary
        val pickedBar = MaterialTheme.colorScheme.tertiary
        val axis = MaterialTheme.colorScheme.outlineVariant
        val day = days[picked.coerceIn(days.indices)]
        Text(
            stringResource(R.string.ankrimento_stats_day, dayName(day.date), day.cards, duration(day.seconds), day.pages),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        val peak = (days.maxOf { it.cards }).coerceAtLeast(1)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(140.dp)
                .padding(horizontal = 16.dp)
                .pointerInput(days) {
                    detectTapGestures { offset -> picked = (offset.x / (size.width / days.size)).toInt().coerceIn(days.indices) }
                },
        ) {
            val slot = size.width / days.size
            val gap = 2.dp.toPx()
            val width = (slot * 0.6f).coerceAtMost(28.dp.toPx())
            val radius = 4.dp.toPx()
            days.forEachIndexed { index, d ->
                val height = size.height * d.cards / peak
                if (height <= 0f) return@forEachIndexed
                val left = index * slot + (slot - width) / 2 + gap / 2
                val top = size.height - height
                val path =
                    Path().apply {
                        addRoundRect(
                            androidx.compose.ui.geometry.RoundRect(
                                left = left,
                                top = top,
                                right = left + width - gap,
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
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            days.forEach { d ->
                Text(
                    dayName(d.date),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    @Composable
    private fun ByKind(block: JSONObject) {
        val cards = block.getJSONObject("cards")
        val seconds = block.getJSONObject("seconds")
        val kinds = (cards.keys().asSequence() + seconds.keys().asSequence()).toSortedSet()
        if (kinds.isEmpty()) {
            Text("–", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            return
        }
        kinds.forEach { kind ->
            Line(kind.replaceFirstChar { it.titlecase() }, "${cards.optInt(kind)} · ${duration(seconds.optDouble(kind, 0.0))}")
        }
    }

    @Composable
    private fun Line(
        label: Int,
        value: String,
    ) = Line(stringResource(label), value)

    @Composable
    private fun Line(
        label: String,
        value: String,
    ) {
        ListItem(headlineContent = { Text(label) }, trailingContent = { Text(value, style = MaterialTheme.typography.bodyLarge) })
    }

    private fun total(block: JSONObject): Double = block.keys().asSequence().sumOf { block.optDouble(it, 0.0) }

    private fun dayName(date: LocalDate): String = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())

    private fun duration(seconds: Double): String {
        val total = seconds.toLong()
        return if (total >= 3600) "%dh %02dm".format(total / 3600, total % 3600 / 60) else "%dm %02ds".format(total / 60, total % 60)
    }

    companion object {
        private const val DAYS = 7

        fun intent(context: Context) = Intent(context, StatisticsActivity::class.java)
    }
}
