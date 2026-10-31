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
import android.widget.Toast
import android.widget.VideoView
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.ankrimento.ui.IncrementoIcons
import com.ichi2.anki.ankrimento.ui.ReaderTool
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * Incremento's video reader: YouTube through upstream's `web/video_player.html`
 * and downloaded videos with Android's player, as desktop's video dock uses
 * its web page and Qt's player. Saves where playback stopped and observed
 * watch time, as desktop does.
 */
class VideoReaderActivity : ReaderActivity() {
    /** Current position and whether it is playing, read from whichever player is shown. */
    private var readPlayer: (suspend () -> Pair<Double, Boolean>?)? = null
    private var lastPosition = 0.0

    /** Moves playback by [seconds] in whichever player is shown. */
    private var seekBy: ((Double) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tools =
            listOf(
                ReaderTool(getString(R.string.ankrimento_video_extract), IncrementoIcons.Add, ::extractHere),
                ReaderTool(getString(R.string.ankrimento_video_back_10)) { seekBy?.invoke(-10.0) },
            )
        lifecycleScope.launch {
            var video =
                runCatching { withCol { Ankrimento.videoOpen(cardId) } }.getOrElse { error ->
                    Timber.w(error, "Opening a video topic failed")
                    Toast.makeText(this@VideoReaderActivity, error.message ?: error.javaClass.simpleName, Toast.LENGTH_LONG).show()
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
                else -> {
                    openExternally(video.url)
                    finish()
                    return@launch
                }
            }
            trackProgress()
        }
    }

    private fun showLocal(video: Ankrimento.Video) {
        val player = VideoView(this)
        setReaderContent(player)
        player.setMediaController(MediaController(this).apply { setAnchorView(player) })
        player.setVideoURI(Uri.fromFile(File(video.path)))
        player.setOnPreparedListener {
            player.seekTo((video.position * 1000).toInt())
            player.start()
        }
        readPlayer = { player.currentPosition / 1000.0 to player.isPlaying }
        seekBy = { seconds -> player.seekTo((player.currentPosition + seconds * 1000).toInt().coerceAtLeast(0)) }
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
    }

    /** Every few seconds, save the position and the playback observed since the last check. */
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
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val EXTRA_START = "start"
        private const val PLAYER_URL = "https://appassets.androidplatform.net/assets/ankrimento/web/video_player.html"
        private const val PROGRESS_INTERVAL_MS = 5_000L
        private const val YOUTUBE_PLAYING = 1
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
