// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.ankrimento.ui.CaptionOverlay
import com.ichi2.anki.ankrimento.ui.IncrementoIcons
import com.ichi2.anki.ankrimento.ui.MIN_SEGMENT_SECONDS
import com.ichi2.anki.ankrimento.ui.MarkBookmark
import com.ichi2.anki.ankrimento.ui.MarksSheet
import com.ichi2.anki.ankrimento.ui.ReaderTool
import com.ichi2.anki.ankrimento.ui.SegmentDraft
import com.ichi2.anki.ankrimento.ui.SubtitlesSheet
import com.ichi2.anki.ankrimento.ui.VideoSegmentBar
import com.ichi2.anki.ankrimento.ui.clockText
import com.ichi2.anki.ankrimento.ui.initialSegment
import com.ichi2.anki.ankrimento.ui.textAt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * Incremento's video reader: YouTube through upstream's `web/video_player.html`,
 * Vimeo in Vimeo's own player page, and downloaded videos with Android's player,
 * as desktop's video dock uses its web pages and Qt's player. Saves where
 * playback stopped and observed watch time, as desktop does. Bookmarks keep
 * moments (desktop's video bookmarks, with comments); playback moves by ten
 * seconds and plays at desktop's speeds.
 */
class VideoReaderActivity : ReaderActivity() {
    /** Current position and whether it is playing, read from whichever player is shown. */
    private var readPlayer: (suspend () -> Pair<Double, Boolean>?)? = null
    private var lastPosition = 0.0

    /** Moves playback by [seconds] in whichever player is shown. */
    private var seekBy: ((Double) -> Unit)? = null

    /** Moves playback to [seconds]. */
    private var seekTo: ((Double) -> Unit)? = null

    /** Desktop's paired subtitles and whether each is shown; the captions follow the playing time. */
    private var subtitles by mutableStateOf<Ankrimento.Subtitles?>(null)
    private var showTarget by mutableStateOf(true)
    private var showReference by mutableStateOf(true)
    private var captionTime by mutableDoubleStateOf(-1.0)

    /** The slot a picked subtitle file goes into. */
    private var pickingSlot = "target"

    private val pickSubtitle =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) attachSubtitle(pickingSlot, uri) }

    /** Pauses whichever player is shown. */
    private var pausePlayer: (() -> Unit)? = null

    /** The segment being chosen for an extract (the bar over the video), or null; and where a linked segment must stop. */
    private var segment by mutableStateOf<SegmentDraft?>(null)
    private var stopAt: Double? = null

    /** The video's length in seconds (0 until the player knows it), and starting playback. */
    private var readDuration: (suspend () -> Double)? = null
    private var playPlayer: (() -> Unit)? = null

    /** The subtitles' captions; the overlay shows them unless the segment bar is open. */
    private var captions by mutableStateOf<(@Composable BoxScope.() -> Unit)?>(null)

    /** Plays at a speed (1.0 is normal). */
    private var setSpeed: ((Float) -> Unit)? = null
    private var speed = 1.0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showTools()
        overlay = {
            val draft = segment
            if (draft == null) {
                captions?.invoke(this)
            } else {
                VideoSegmentBar(
                    segment = draft,
                    onRange = ::moveSegment,
                    onStartHere = { segmentHere(atStart = true) },
                    onEndHere = { segmentHere(atStart = false) },
                    onPreview = ::previewSegment,
                    onExtract = ::extractSegment,
                    onClose = { segment = null },
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .widthIn(max = 560.dp)
                            .padding(8.dp),
                )
            }
        }
        lifecycleScope.launch {
            // A video extract's link opens the video at its moment (Vimeo's page needs it in the URL it loads).
            val start = intent.getDoubleExtra(EXTRA_START, -1.0)
            val video =
                runCatching { withCol { Ankrimento.videoOpen(cardId, start) } }.getOrElse { error ->
                    Timber.w(error, "Opening a video topic failed")
                    this@VideoReaderActivity.showToast(error.message ?: error.javaClass.simpleName)
                    finish()
                    return@launch
                }
            // A segment extract plays from its start and stops at its end.
            stopAt = intent.getDoubleExtra(EXTRA_END, -1.0).takeIf { start >= 0 && it > start }
            lastPosition = video.position
            when (video.kind) {
                "local" -> showLocal(video)
                "youtube" -> showYouTube(video)
                "vimeo" -> showVimeo(video)
                else -> {
                    openExternally(video.url)
                    finish()
                    return@launch
                }
            }
            loadSubtitles()
            launch { followCaptions() }
            launch { stopAtSegmentEnd() }
            trackProgress()
        }
    }

    private fun showLocal(video: Ankrimento.Video) {
        val player = VideoView(this)
        setReaderContent(player)
        player.setMediaController(MediaController(this).apply { setAnchorView(player) })
        player.setVideoURI(Uri.fromFile(File(video.path)))
        player.setOnPreparedListener { media ->
            player.seekTo((video.position * 1000).toInt())
            player.start()
            pausePlayer = { player.pause() }
            playPlayer = { player.start() }
            setSpeed = { rate ->
                // A speed above zero also starts playback; a paused video stays paused.
                val playing = player.isPlaying
                runCatching { media.playbackParams = media.playbackParams.setSpeed(rate) }
                    .onFailure { Timber.w(it, "Setting the playback speed failed") }
                if (!playing) player.pause()
            }
        }
        readPlayer = { player.currentPosition / 1000.0 to player.isPlaying }
        readDuration = { (player.duration / 1000.0).coerceAtLeast(0.0) }
        seekBy = { seconds -> player.seekTo((player.currentPosition + seconds * 1000).toInt().coerceAtLeast(0)) }
        seekTo = { seconds -> player.seekTo((seconds * 1000).toInt().coerceAtLeast(0)) }
    }

    private fun showTools() {
        tools =
            listOfNotNull(
                ReaderTool(getString(R.string.ankrimento_video_extract), IncrementoIcons.Add, onClick = ::extractHere),
                // A segment of this video as an extract, chosen on a bar over the video. Nothing is cut or copied.
                ReaderTool(getString(R.string.ankrimento_video_segment_open), IncrementoIcons.Segment, onClick = ::toggleSegmentBar),
                ReaderTool(getString(R.string.ankrimento_marks_bookmarks), IncrementoIcons.Bookmark, onClick = ::showBookmarks),
                ReaderTool(getString(R.string.ankrimento_video_subtitles), onClick = ::showSubtitles),
                ReaderTool(getString(R.string.ankrimento_video_back_10)) { seekBy?.invoke(-10.0) },
                ReaderTool(getString(R.string.ankrimento_video_forward_10)) { seekBy?.invoke(10.0) },
                ReaderTool(getString(R.string.ankrimento_video_speed, speedLabel(speed))) {
                    // Desktop's speeds, in turn.
                    speed = SPEEDS[(SPEEDS.indexOf(speed) + 1) % SPEEDS.size]
                    setSpeed?.invoke(speed)
                    showTools()
                },
            )
    }

    private fun speedLabel(rate: Float) = (if (rate == rate.toInt().toFloat()) rate.toInt().toString() else rate.toString()) + "×"

    /** Desktop's video bookmarks: the moment playing now can be kept; each opens there, takes a comment, or goes. */
    private fun showBookmarks() {
        lifecycleScope.launch {
            val now = readPlayer?.invoke()?.first ?: lastPosition
            changeBookmarks(now) { Ankrimento.videoBookmarks(cardId) }
        }
    }

    private fun changeBookmarks(
        now: Double,
        change: com.ichi2.anki.libanki.Collection.() -> List<MarkBookmark>,
    ) {
        lifecycleScope.launch {
            val bookmarks =
                runCatching { withCol { change() } }.getOrElse { error ->
                    Timber.w(error, "Changing video bookmarks failed")
                    toast(error.message ?: error.javaClass.simpleName)
                    return@launch
                }
            sheet = {
                MarksSheet(
                    page = now.toInt(),
                    bookmarks = bookmarks,
                    highlights = emptyList(),
                    onOpenPage = { seconds ->
                        sheet = null
                        seekTo?.invoke(seconds.toDouble())
                    },
                    onAddBookmark = { changeBookmarks(now) { Ankrimento.videoAddBookmark(cardId, now) } },
                    onDeleteBookmark = { id -> changeBookmarks(now) { Ankrimento.videoDeleteBookmark(cardId, id) } },
                    onBookmarkComment = { id, comment -> changeBookmarks(now) { Ankrimento.videoBookmarkComment(cardId, id, comment) } },
                    onHighlightColor = { _, _ -> },
                    onHighlightNote = { _, _ -> },
                    onDeleteHighlight = {},
                    onDismiss = { sheet = null },
                    withHighlights = false,
                    placeOf = { it.label },
                    addLabel = stringResource(R.string.ankrimento_video_bookmark_here, clock(now)),
                    canAdd = true,
                )
            }
        }
    }

    private fun clock(seconds: Double) = clockText(seconds)

    /** Vimeo's own player page, as desktop's dock loads it; its video element reports and moves playback. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun showVimeo(video: Ankrimento.Video) {
        val webView = WebView(this)
        setReaderContent(webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.webViewClient = WebViewClientCompat()
        webView.loadUrl(video.url)
        readPlayer = {
            val result = evaluate(webView, VIMEO_STATE_JS)
            runCatching { JSONObject(JSONObject("{\"v\":$result}").getString("v")) }
                .getOrNull()
                ?.let { it.getDouble("t") to it.getBoolean("p") }
        }
        seekBy =
            { seconds -> webView.evaluateJavascript(VIMEO_VIDEO + "if (v) v.currentTime = Math.max(0, v.currentTime + $seconds);", null) }
        seekTo = { seconds -> webView.evaluateJavascript(VIMEO_VIDEO + "if (v) v.currentTime = Math.max(0, $seconds);", null) }
        setSpeed = { rate -> webView.evaluateJavascript(VIMEO_VIDEO + "if (v) v.playbackRate = $rate;", null) }
        pausePlayer = { webView.evaluateJavascript(VIMEO_VIDEO + "if (v) v.pause();", null) }
        playPlayer = { webView.evaluateJavascript(VIMEO_VIDEO + "if (v) v.play();", null) }
        readDuration = {
            evaluate(webView, "(function () { $VIMEO_VIDEO return v ? v.duration : 0; })()").toDoubleOrNull()?.takeIf { it.isFinite() }
                ?: 0.0
        }
    }

    /** The segment bar opens at the moment playing now (30 seconds on), or closes. */
    private fun toggleSegmentBar() {
        if (segment != null) {
            segment = null
            return
        }
        lifecycleScope.launch {
            val now = readPlayer?.invoke()?.first ?: lastPosition
            segment = initialSegment(now, readDuration?.invoke() ?: 0.0)
            // Some players (Vimeo's) only know the length once playback has started: ask again while the bar is open.
            repeat(DURATION_RETRIES) {
                if (segment?.let { it.duration <= 0 } != true) return@launch
                delay(DURATION_RETRY_MS)
                val duration = readDuration?.invoke() ?: 0.0
                val draft = segment ?: return@launch
                if (duration > 0) {
                    segment =
                        draft.copy(
                            start = minOf(draft.start, duration - MIN_SEGMENT_SECONDS).coerceAtLeast(0.0),
                            end = minOf(draft.end, duration),
                            duration = duration,
                        )
                }
            }
        }
    }

    /** A handle was dragged: keep the range, and show the frame at the handle that moved. */
    private fun moveSegment(
        start: Double,
        end: Double,
        movedStart: Boolean,
    ) {
        val duration = segment?.duration ?: return
        segment = SegmentDraft(start, end, duration)
        seekTo?.invoke(if (movedStart) start else end)
    }

    /** The moment playing now becomes the segment's start or its end. */
    private fun segmentHere(atStart: Boolean) {
        lifecycleScope.launch {
            val draft = segment ?: return@launch
            val now = readPlayer?.invoke()?.first ?: lastPosition
            segment =
                if (atStart) {
                    val end = if (now + 1.0 > draft.end) initialSegment(now, draft.duration).end else draft.end
                    draft.copy(start = now, end = end)
                } else if (now < draft.start + 1.0) {
                    toast(getString(R.string.ankrimento_video_segment_invalid))
                    draft
                } else {
                    draft.copy(end = if (draft.duration > 0) minOf(now, draft.duration) else now)
                }
        }
    }

    /** Plays the segment from its start and pauses at its end. */
    private fun previewSegment() {
        val draft = segment ?: return
        seekTo?.invoke(draft.start)
        stopAt = draft.end
        playPlayer?.invoke()
    }

    private fun extractSegment() {
        val draft = segment ?: return
        segment = null
        lifecycleScope.launch {
            runCatching { withCol { Ankrimento.videoExtract(cardId, draft.start, draft.end) } }
                .onFailure {
                    Timber.w(it, "Starting a video segment extract failed")
                    toast(it.message ?: it.javaClass.simpleName)
                }
        }
    }

    /** A segment's link plays up to its end and pauses there. */
    private suspend fun stopAtSegmentEnd() {
        while (true) {
            delay(CAPTION_INTERVAL_MS)
            val end = stopAt ?: continue
            val (position, playing) = readPlayer?.invoke() ?: continue
            if (playing && position >= end) {
                pausePlayer?.invoke()
                stopAt = null
                toast(getString(R.string.ankrimento_video_segment_done))
            }
        }
    }

    /** Desktop's "Add Card at this point": an extract whose front links back to this moment. */
    private fun extractHere() {
        lifecycleScope.launch {
            val position = readPlayer?.invoke()?.first ?: lastPosition
            runCatching { withCol { Ankrimento.videoExtract(cardId, position) } }
                .onFailure { Timber.w(it, "Starting a video extract failed") }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showYouTube(video: Ankrimento.Video) {
        val webView = WebView(this)
        setReaderContent(webView)
        val assetLoader =
            WebViewAssetLoader
                .Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
                .build()
        webView.settings.javaScriptEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.webViewClient =
            object : WebViewClientCompat() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

                override fun onPageFinished(
                    view: WebView,
                    url: String,
                ) {
                    if (url != PLAYER_URL) return
                    webView.evaluateJavascript("startVideo(${JSONObject.quote(video.videoId)}, ${video.position});", null)
                }
            }
        webView.loadUrl(PLAYER_URL)
        readPlayer = {
            val result = evaluate(webView, PLAYER_STATE_JS)
            runCatching { JSONObject(JSONObject("{\"v\":$result}").getString("v")) }
                .getOrNull()
                ?.let { it.getDouble("t") to (it.getInt("s") == YOUTUBE_PLAYING) }
        }
        seekBy = { seconds ->
            webView.evaluateJavascript(
                "window._ytp && window._ytp.seekTo && window._ytp.seekTo(Math.max(0, window._ytp.getCurrentTime() + $seconds), true);",
                null,
            )
        }
        seekTo = { seconds -> webView.evaluateJavascript("window._ytp && window._ytp.seekTo && window._ytp.seekTo($seconds, true);", null) }
        pausePlayer = { webView.evaluateJavascript("window._ytp && window._ytp.pauseVideo && window._ytp.pauseVideo();", null) }
        playPlayer = { webView.evaluateJavascript("window._ytp && window._ytp.playVideo && window._ytp.playVideo();", null) }
        readDuration = {
            evaluate(webView, "(window._ytp && window._ytp.getDuration ? window._ytp.getDuration() : 0)")
                .toDoubleOrNull()
                ?.takeIf { it.isFinite() } ?: 0.0
        }
        setSpeed =
            { rate ->
                webView.evaluateJavascript("window._ytp && window._ytp.setPlaybackRate && window._ytp.setPlaybackRate($rate);", null)
            }
    }

    /** Every few seconds, save the position and the playback observed since the last check. */
    private suspend fun loadSubtitles() {
        subtitles =
            runCatching { withCol { Ankrimento.videoSubtitles(cardId) } }
                .onFailure { Timber.w(it, "Loading the video's subtitles failed") }
                .getOrNull()
        val shown = subtitles?.takeUnless { it.isEmpty }
        captions =
            shown?.let {
                {
                    val time = captionTime
                    val current = subtitles
                    CaptionOverlay(
                        target = if (showTarget) current?.target?.textAt(time) else null,
                        reference = if (showReference) current?.reference?.textAt(time) else null,
                    )
                }
            }
    }

    /** The playing time, often enough for captions, while the video has subtitles. */
    private suspend fun followCaptions() {
        while (true) {
            delay(CAPTION_INTERVAL_MS)
            if (subtitles?.isEmpty != false) continue
            readPlayer?.invoke()?.let { (position, _) -> captionTime = position }
        }
    }

    /** Desktop's subtitle dialog: each slot from a file, shown or hidden, or removed. */
    private fun showSubtitles() {
        val current = subtitles ?: return
        sheet = {
            SubtitlesSheet(
                subtitles = current,
                showTarget = showTarget,
                showReference = showReference,
                onShow = { slot, show ->
                    if (slot == "target") showTarget = show else showReference = show
                    showSubtitles()
                },
                onAdd = { slot ->
                    pickingSlot = slot
                    // File managers name subtitles' types in many ways: any file; the backend checks it.
                    pickSubtitle.launch(arrayOf("*/*"))
                },
                onRemove = { slot -> changeSubtitles { Ankrimento.videoClearSubtitle(cardId, slot) } },
                onDismiss = { sheet = null },
            )
        }
    }

    private fun attachSubtitle(
        slot: String,
        uri: android.net.Uri,
    ) {
        lifecycleScope.launch {
            val name = DocumentImport.displayName(this@VideoReaderActivity, uri) ?: "subtitle.srt"
            val staged = File(cacheDir, "subtitles").apply { mkdirs() }.resolve(File(name).name)
            runCatching {
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStreamSafely(uri).use { input ->
                        checkNotNull(input) { "cannot read $uri" }
                        staged.outputStream().use { input.copyTo(it) }
                    }
                }
                changeSubtitles { Ankrimento.videoAttachSubtitle(cardId, slot, staged.path, name) }
            }.onFailure { error ->
                Timber.w(error, "Adding a subtitle file failed")
                toast(error.message ?: error.javaClass.simpleName)
            }
            staged.delete()
        }
    }

    private fun changeSubtitles(change: com.ichi2.anki.libanki.Collection.() -> Unit) {
        lifecycleScope.launch {
            runCatching { withCol { change() } }
                .onFailure { error -> toast(error.message ?: error.javaClass.simpleName) }
            loadSubtitles()
            if (sheet != null) showSubtitles()
        }
    }

    private suspend fun trackProgress() {
        var lastCheck = SystemClock.elapsedRealtime()
        while (true) {
            delay(PROGRESS_INTERVAL_MS)
            val now = SystemClock.elapsedRealtime()
            val (position, playing) = readPlayer?.invoke() ?: continue
            val watched = if (playing) (now - lastCheck) / 1000.0 else 0.0
            lastCheck = now
            save(position, watched)
        }
    }

    private suspend fun save(
        position: Double,
        watched: Double,
    ) {
        if (position == lastPosition && watched == 0.0) return
        lastPosition = position
        runCatching { withCol { Ankrimento.videoProgress(cardId, position, watched) } }
            .onFailure { Timber.w(it, "Saving video progress failed") }
    }

    override fun onPause() {
        super.onPause()
        // The poller stops with the activity; save where playback stopped.
        val reader = readPlayer ?: return
        lifecycleScope.launch { reader()?.let { (position, _) -> save(position, 0.0) } }
    }

    private suspend fun evaluate(
        webView: WebView,
        script: String,
    ): String =
        suspendCancellableCoroutine { continuation ->
            webView.evaluateJavascript(script) { continuation.resumeWith(Result.success(it)) }
        }

    private fun openExternally(url: String) {
        if (url.isEmpty()) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (e: ActivityNotFoundException) {
            showToast(url)
        }
    }

    companion object {
        private const val EXTRA_START = "start"
        private const val EXTRA_END = "end"
        private const val PLAYER_URL = "https://appassets.androidplatform.net/assets/ankrimento/web/video_player.html"

        /** Captions follow the playing time this often. */
        private const val CAPTION_INTERVAL_MS = 250L

        /** How often, and how many times, the segment bar asks a player that does not know its length yet. */
        private const val DURATION_RETRY_MS = 500L
        private const val DURATION_RETRIES = 120
        private const val PROGRESS_INTERVAL_MS = 5_000L
        private const val YOUTUBE_PLAYING = 1

        /** Desktop's playback speeds (its local player's rate menu). */
        private val SPEEDS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

        private const val VIMEO_VIDEO = "var v = document.querySelector('video');"
        private const val VIMEO_STATE_JS =
            "(function () { $VIMEO_VIDEO return v ? JSON.stringify({t: v.currentTime, p: !v.paused}) : null; })()"
        private const val PLAYER_STATE_JS =
            "JSON.stringify(window._ytp && window._ytp.getCurrentTime ? " +
                "{t: window._ytp.getCurrentTime(), s: window._ytp.getPlayerState()} : null)"

        fun intent(
            context: Context,
            cardId: Long,
        ): Intent = ReaderActivity.intent(context, "video", cardId, session = false)!!

        /** The video at [seconds] (a video extract's link); with [end] it plays that segment and stops there. */
        fun intentAt(
            context: Context,
            cardId: Long,
            seconds: Double,
            end: Double? = null,
        ): Intent = intent(context, cardId).putExtra(EXTRA_START, seconds).apply { end?.let { putExtra(EXTRA_END, it) } }
    }
}
