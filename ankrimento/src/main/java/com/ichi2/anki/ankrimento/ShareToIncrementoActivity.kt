// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.CollectionManager.withCol
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * "Add to Incremento" in Android's share sheet: a shared link becomes an
 * Incremento Web topic, as desktop's Web Page does.
 */
class ShareToIncrementoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val url = Patterns.WEB_URL.matcher(text).let { if (it.find()) it.group() else null }
        if (url == null || !Ankrimento.isSupported) {
            Toast.makeText(this, R.string.ankrimento_share_no_link, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val title = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
        lifecycleScope.launch {
            runCatching {
                withCol {
                    Ankrimento.start(this@ShareToIncrementoActivity, this)
                    Ankrimento.importWeb(url, title)
                }
            }.onSuccess {
                Toast
                    .makeText(
                        this@ShareToIncrementoActivity,
                        getString(R.string.ankrimento_pdf_added, title.ifEmpty { url }),
                        Toast.LENGTH_LONG,
                    ).show()
            }.onFailure { error ->
                Timber.w(error, "Adding a shared page failed")
                val message = getString(R.string.ankrimento_pdf_add_failed, error.message ?: error.javaClass.simpleName)
                Toast.makeText(this@ShareToIncrementoActivity, message, Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }
}
