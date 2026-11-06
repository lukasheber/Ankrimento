// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.net.Uri
import java.io.InputStream
import java.nio.file.FileSystems

/**
 * Opens a file another app handed over (a share, a picked document), refusing one in `/data`: the app's
 * own private storage. Mirrors AnkiDroid's `openInputStreamSafe` (com.ichi2.utils.FileUtil), which this
 * module cannot reach.
 *
 * @throws SecurityException for a path in `/data`
 */
@SuppressLint("UnsafeOpenInputStream") // the check below is the safe wrapper
fun ContentResolver.openInputStreamSafely(uri: Uri): InputStream? {
    val path = uri.path ?: return openInputStream(uri)
    if (FileSystems
            .getDefault()
            .getPath(path)
            .normalize()
            .startsWith("/data")
    ) {
        throw SecurityException("java/android/unsafe-content-uri-resolution")
    }
    return openInputStream(uri)
}
