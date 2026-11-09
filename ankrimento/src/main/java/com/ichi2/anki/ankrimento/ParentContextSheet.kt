// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.res.Configuration
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.CollectionManager.withCol
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Desktop's Parent: the card an extract came from, opened read-only over the card being reviewed so the question can
 * be read against its source. Desktop shows it in a sidebar; on a phone it is a dialog over the card, dismissed with
 * Back. Like desktop's, it loads nothing: no page script, no network, no media.
 */
object ParentContextSheet {
    fun show(
        activity: ComponentActivity,
        cardId: Long,
    ) {
        activity.lifecycleScope.launch {
            runCatching { withCol { Ankrimento.parentContext(cardId) } }
                .onSuccess { context ->
                    when {
                        context == null -> activity.showToast(R.string.ankrimento_parent_none, long = false)
                        !context.available -> activity.showToast(R.string.ankrimento_parent_unavailable, long = false)
                        else -> if (!activity.isDestroyed) open(activity, context)
                    }
                }.onFailure { error ->
                    Timber.w(error, "Loading the parent failed")
                    activity.showToast(error.message ?: error.javaClass.simpleName, long = false)
                }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun open(
        activity: ComponentActivity,
        context: Ankrimento.ParentContext,
    ) {
        val web =
            WebView(activity).apply {
                setBackgroundColor(Color.TRANSPARENT)
                settings.javaScriptEnabled = false
                settings.blockNetworkLoads = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient =
                    object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ) = true

                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse? =
                            // Nothing the parent's markup points at (pictures, sounds) is fetched; the page itself is not a request.
                            if (request.url.scheme in EXTERNAL_SCHEMES) WebResourceResponse("text/plain", "utf-8", null) else null
                    }
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        (activity.resources.displayMetrics.heightPixels * SHEET_HEIGHT).toInt(),
                    )
                loadDataWithBaseURL(null, html(activity, context), "text/html", "utf-8", null)
            }
        // The framework's dialog, in the system's dark or light: the readers' own themes are not AppCompat.
        AlertDialog
            .Builder(
                activity,
                if (isDark(
                        activity,
                    )
                ) {
                    android.R.style.Theme_DeviceDefault_Dialog_Alert
                } else {
                    android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
                },
            ).setTitle(R.string.ankrimento_parent)
            .setView(web)
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener { web.destroy() }
            .show()
    }

    private fun html(
        activity: ComponentActivity,
        context: Ankrimento.ParentContext,
    ): String = page(isDark(activity), context.title, context.fields)

    /** The parent as one self-contained page: its title, then each visible field's name and (sanitised) markup. */
    internal fun page(
        dark: Boolean,
        title: String,
        fields: List<Pair<String, String>>,
    ): String {
        val ink = "#%06X".format((if (dark) DARK_INK else LIGHT_INK) and 0xFFFFFF)
        val body =
            buildString {
                append("<h3>").append(escape(title)).append("</h3>")
                for ((name, markup) in fields) {
                    append("<h4>").append(escape(name)).append("</h4>").append(markup)
                }
            }
        return "<html><head><meta name='viewport' content='width=device-width, initial-scale=1'>" +
            "<style>body{font-family:sans-serif;font-size:16px;line-height:1.45;margin:8px 20px 24px;" +
            "color:$ink;overflow-wrap:anywhere}h3{margin:12px 0}h4{margin:18px 0 4px;opacity:.7;font-size:13px;" +
            "text-transform:uppercase}img{max-width:100%}</style></head><body>" + body + "</body></html>"
    }

    private fun escape(text: String) =
        text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

    private fun isDark(activity: ComponentActivity) =
        (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private val EXTERNAL_SCHEMES = setOf("http", "https", "file", "content")

    private const val DARK_INK = 0xE6E1E5
    private const val LIGHT_INK = 0x1C1B1F
    private const val SHEET_HEIGHT = 0.65
}
