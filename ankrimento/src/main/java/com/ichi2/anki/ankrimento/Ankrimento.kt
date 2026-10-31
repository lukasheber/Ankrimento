// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import android.content.Context
import android.os.Build
import android.util.Base64
import androidx.annotation.WorkerThread
import anki.collection.OpChanges
import anki.scheduler.CardAnswer.Rating
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.libanki.Collection
import com.ichi2.anki.observability.ChangeManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * Incremento's backend, running in embedded Python on AnkiDroid's collection.
 *
 * Call from the collection queue (`withCol`): Python uses the same backend and
 * undo queue as AnkiDroid, so its work must be serialized with AnkiDroid's.
 */
object Ankrimento {
    /** Desktop's default profile; selects `user_files/<profile>/` as on desktop. */
    const val DEFAULT_PROFILE = "User 1"

    private val SUPPORTED_ABIS = setOf("arm64-v8a", "x86_64")

    /** Messages from Incremento (`showInfo`, `tooltip`). */
    var onMessage: ((kind: String, text: String) -> Unit)? = null

    private var facade: PyObject? = null

    val isSupported: Boolean
        get() = Build.SUPPORTED_ABIS.any { it in SUPPORTED_ABIS }

    val isStarted: Boolean
        get() = facade != null

    @WorkerThread
    @Synchronized
    fun start(
        context: Context,
        col: Collection,
        profile: String = DEFAULT_PROFILE,
    ) {
        if (facade != null) return
        check(isSupported) { "Ankrimento needs a 64-bit device" }
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context.applicationContext))
        }
        val python = Python.getInstance()
        val host = AnkrimentoHost(col.backend, ::onHostEvent)
        // Reinstall the shipped add-on whenever the app itself is updated.
        val buildId =
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .lastUpdateTime
                .toString()
        python.getModule("ankrimento.android").callAttr(
            "start",
            host,
            col.colDb.absolutePath,
            File(context.filesDir, "ankrimento/addons21").absolutePath,
            profile,
            buildId,
        )
        facade = python.getModule("ankrimento.facade")
        ChangeManager.subscribe(operationListener)
        Timber.i("Ankrimento started for profile '%s'", profile)
    }

    /**
     * Desktop Anki fires `undo_state_did_change` after every operation, and
     * Incremento uses it to follow undo/redo of its scheduling. Do the same
     * for every operation AnkiDroid publishes, on the collection queue.
     * Held here because [ChangeManager] keeps subscribers weakly.
     */
    private val operationListener =
        object : ChangeManager.Subscriber {
            override fun opExecuted(
                changes: OpChanges,
                handler: Any?,
            ) {
                operationScope.launch {
                    runCatching { CollectionManager.withCol { requireFacade().callAttr("undo_state_changed") } }
                        .onFailure { Timber.w(it, "Incremento undo reconciliation failed") }
                }
            }
        }

    private val operationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Pick session cards with the saved Incremento scheduler settings. */
    @WorkerThread
    fun selectSession(): List<Long> {
        val result = JSONObject(requireFacade().callAttr("select_session").toString())
        val ids = result.getJSONArray("card_ids")
        return List(ids.length()) { ids.getLong(it) }
    }

    /** Fill the "Incremento Session" filtered deck with [cardIds]; returns its deck id. */
    @WorkerThread
    fun buildSessionDeck(cardIds: List<Long>): Long {
        val result = requireFacade().callAttr("build_session_deck", JSONArray(cardIds).toString())
        return JSONObject(result.toString()).getLong("deck_id")
    }

    /**
     * Incremento's answer buttons for [cardId], or null when AnkiDroid's own
     * buttons apply (Ankrimento not started, or an ordinary card).
     */
    @WorkerThread
    fun answerButtons(cardId: Long): List<AnswerButton>? {
        if (!isStarted) return null
        val result = JSONObject(requireFacade().callAttr("answer_buttons", cardId).toString())
        if (result.getString("mode") == "standard") return null
        val buttons = result.getJSONArray("buttons")
        return List(buttons.length()) { index ->
            val button = buttons.getJSONObject(index)
            AnswerButton(
                rating = Rating.forNumber(button.getInt("slot") - 1),
                label = button.getString("label"),
                ease = button.getInt("ease"),
                due = if (button.isNull("due")) null else button.getString("due"),
            )
        }
    }

    /**
     * The ease (1-4) Anki should record when [rating]'s button is pressed.
     * Topic choices and Pass are recorded as Good.
     */
    @WorkerThread
    fun beforeAnswer(
        cardId: Long,
        rating: Rating,
    ): Int {
        val result = JSONObject(requireFacade().callAttr("before_answer", cardId, rating.number + 1).toString())
        return result.getInt("ease")
    }

    /** Apply Incremento's post-answer scheduling after AnkiDroid answered [cardId]. */
    @WorkerThread
    fun afterAnswer(
        cardId: Long,
        ease: Int,
    ) {
        requireFacade().callAttr("after_answer", cardId, ease)
    }

    /**
     * Reviewer hook, before answering: the [Rating] AnkiDroid should submit.
     * Unchanged until an Incremento session has started Ankrimento.
     */
    @WorkerThread
    fun ratingToSubmit(
        cardId: Long,
        rating: Rating,
    ): Rating {
        if (!isStarted) return rating
        return Rating.forNumber(beforeAnswer(cardId, rating) - 1)
    }

    /** Reviewer hook, after AnkiDroid's answer op created its undo step. */
    @WorkerThread
    fun onCardAnswered(
        cardId: Long,
        rating: Rating,
    ) {
        if (isStarted) afterAnswer(cardId, rating.number + 1)
    }

    private fun requireFacade(): PyObject = checkNotNull(facade) { "Ankrimento not started" }

    private fun onHostEvent(
        event: String,
        fieldsJson: String,
    ) {
        val fields = JSONObject(fieldsJson)
        when (event) {
            "operation_did_execute" -> {
                val bytes = Base64.decode(fields.getString("changes"), Base64.DEFAULT)
                ChangeManager.publish(OpChanges.parseFrom(bytes))
            }
            "message" -> onMessage?.invoke(fields.getString("kind"), fields.getString("text"))
            else -> Timber.d("Ankrimento event %s %s", event, fieldsJson)
        }
    }

    /**
     * One of Incremento's answer buttons, placed on AnkiDroid's [rating] button.
     *
     * @param ease what Anki records; its next-interval text applies when [due] is null
     * @param due the next interval Incremento computed (topics)
     */
    data class AnswerButton(
        val rating: Rating,
        val label: String,
        val ease: Int,
        val due: String?,
    )
}
