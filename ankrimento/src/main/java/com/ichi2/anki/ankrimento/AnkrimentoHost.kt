// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.os.Handler
import android.os.Looper
import anki.backend.BackendError
import com.google.protobuf.ByteString
import net.ankiweb.rsdroid.Backend
import net.ankiweb.rsdroid.BackendException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * What Python's `anki._rsbridge` stand-in calls: AnkiDroid's open [Backend].
 *
 * Each call returns `[ok, err]` with exactly one set, `err` being a serialized
 * [BackendError], which is the contract of the native `_rsbridge` that
 * Python's `anki` library expects.
 */
class AnkrimentoHost(
    private val backend: Backend,
    private val onEvent: (event: String, fieldsJson: String) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    fun command(
        service: Int,
        method: Int,
        data: ByteArray,
    ): Array<ByteArray?> =
        call {
            try {
                runMethodRaw.invoke(backend, service, method, data) as ByteArray
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }

    fun dbCommand(data: ByteArray): Array<ByteArray?> = call { backend.runDbCommand(ByteString.copyFrom(data)).toByteArray() }

    fun runOnMain(runnable: Runnable) {
        mainHandler.post(runnable)
    }

    fun onEvent(
        event: String,
        fieldsJson: String,
    ) = onEvent.invoke(event, fieldsJson)

    private inline fun call(block: () -> ByteArray): Array<ByteArray?> =
        try {
            arrayOf(block(), null)
        } catch (e: BackendException) {
            arrayOf(null, e.toBackendError().toByteArray())
        } catch (e: Exception) {
            arrayOf(null, backendError(e).toByteArray())
        }

    private companion object {
        /**
         * Every backend RPC goes through this; it is protected because AnkiDroid
         * wraps it in typed methods, which Python's `anki` already provides.
         */
        val runMethodRaw: Method =
            Backend::class.java
                .getDeclaredMethod("runMethodRaw", Int::class.java, Int::class.java, ByteArray::class.java)
                .apply { isAccessible = true }
    }
}

/** The original backend error, which [BackendException] keeps privately. */
private fun BackendException.toBackendError(): BackendError =
    runCatching {
        BackendException::class.java
            .getDeclaredField("error")
            .apply { isAccessible = true }
            .get(this) as BackendError?
    }.getOrNull() ?: backendError(this)

private fun backendError(e: Exception): BackendError =
    BackendError
        .newBuilder()
        .setMessage(e.message ?: e.javaClass.name)
        .build()
