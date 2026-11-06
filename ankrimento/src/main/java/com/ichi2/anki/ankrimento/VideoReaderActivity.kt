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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.ankrimento.ui.CaptionOverlay
import com.ichi2.anki.ankrimento.ui.IncrementoIcons
import com.ichi2.anki.ankrimento.ui.MarkBookmark
import com.ichi2.anki.ankrimento.ui.MarksSheet
import com.ichi2.anki.ankrimento.ui.ReaderTool
import com.ichi2.anki.ankrimento.ui.SubtitlesSheet
import com.ichi2.anki.ankrimento.ui.VideoClipDialog
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

    /** Plays at a speed (1.0 is normal). */
    private var setSpeed: ((Float) -> Unit)? = null
    private var speed = 1.0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showTools()
        lifecycleScope.launch {
            var video =
                runCatching { withCol { Ankrimento.videoOpen(cardId) } }.getOrElse { error ->
                    Timber.w(error, "Opening a video topic failed")
                    this@VideoReaderActivity.showToast(error.message ?: error.javaClass.simpleName)
                    finish()
                    return@launch
                }
            // A video extract's link opens the video at its moment.
            val start = intent.getDoubleExtra(EXTRA_START, -1.0)
            if (start >= 0) video = video.copy(position = start)
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
            setSpeed = { rate ->
                // A speed above zero also starts playback; a paused video stays paused.
                val playing = player.isPlaying
                runCatching { media.playbackParams = media.playbackParams.setSpeed(rate) }
                    .onFailure { Timber.w(it, "Setting the playback speed failed") }
                if (!playing) player.pause()
            }
        }
        localVideo = File(video.path)
        localDuration = { player.duration / 1000.0 }
        showTools() // with Create clip, now that there is a local copy
        readPlayer = { player.currentPosition / 1000.0 to player.isPlaying }
        seekBy = { seconds -> player.seekTo((player.currentPosition + seconds * 1000).toInt().coerceAtLeast(0)) }
        seekTo = { seconds -> player.seekTo((seconds * 1000).toInt().coerceAtLeast(0)) }
    }

    private fun showTools() {
        tools =
            listOfNotNull(
                ReaderTool(getString(R.string.ankrimento_video_extract), IncrementoIcons.Add, onClick = ::extractHere),
                ReaderTool(getString(R.string.ankrimento_marks_bookmarks), IncrementoIcons.Bookmark, onClick = ::showBookmarks),
                ReaderTool(getString(R.string.ankrimento_video_subtitles), onClick = ::showSubtitles),
                // Desktop's Create Mobile Clip cuts a local copy; the phone has one only for a downloaded video.
                ReaderTool(getString(R.string.ankrimento_video_clip), onClick = ::chooseClip).takeIf { localVideo != null },
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

    private fun clock(seconds: Double): String {
        val total = seconds.toLong().coerceAtLeast(0)
        val (hours, minutes, secs) = Triple(total / 3600, total % 3600 / 60, total % 60)
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs) else "%d:%02d".format(minutes, secs)
    }

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
    }

    /** The local video and its length, for clips (null for a streamed video). */
    private var localVideo: File? = null
    private var localDuration: (() -> Double)? = null

    /** Desktop's "Create Mobile Clip": a segment cut into an MP4 that plays on any device, as a new card. */
    private fun chooseClip() {
        lifecycleScope.launch { showClipDialog(readPlayer?.invoke()?.first ?: lastPosition) }
    }

    private fun showClipDialog(now: Double) {
        val duration = localDuration?.invoke()?.takeIf { it > 0 } ?: 0.0
        sheet = {
            VideoClipDialog(
                now = now,
                duration = duration,
                onCut = { start, end ->
                    sheet = null
                    cutClip(start, end, duration)
                },
                onDismiss = { sheet = null },
            )
        }
    }

    private fun cutClip(
        start: Double,
        end: Double,
        duration: Double,
    ) {
        val source = localVideo ?: return
        toast(getString(R.string.ankrimento_video_clip_cutting))
        lifecycleScope.launch {
            val clip = File(cacheDir, "clips").apply { mkdirs() }.resolve("clip.mp4")
            runCatching {
                val realStart = withContext(Dispatchers.IO) { VideoClipper.cut(source, clip, start, end) }
                withCol { Ankrimento.videoClip(cardId, realStart, end, clip, duration) }
            }.onFailure { error ->
                Timber.w(error, "Cutting a video clip failed")
                toast(
                    if (error is VideoClipper.UnsupportedFormat) {
                        getString(R.string.ankrimento_video_clip_format)
                    } else {
                        error.message ?: error.javaClass.simpleName
                    },
                )
            }
            clip.delete()
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
        overlay =
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
        private const val PLAYER_URL = "https://appassets.androidplatform.net/assets/ankrimento/web/video_player.html"

        /** Captions follow the playing time this often. */
        private const val CAPTION_INTERVAL_MS = 250L
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

        /** The video at [seconds] (a video extract's link). */
        fun intentAt(
            context: Context,
            cardId: Long,
            seconds: Double,
        ): Intent = intent(context, cardId).putExtra(EXTRA_START, seconds)
    }
}
