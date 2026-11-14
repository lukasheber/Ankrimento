// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

/** What the schedule says today: cards due now, and cards answered today. */
internal data class TodayPlan(
    val due: Int,
    val done: Int,
) {
    /** How much of the day's work is finished, 0 to 1; a day with nothing planned is complete. */
    val progress: Float get() = if (due + done == 0) 1f else done.toFloat() / (done + due)
}

/**
 * The kind of session to start next: today's when cards are due, new material (study ahead) once nothing is, and
 * upstream's own with the daily plan off.
 */
internal fun nextSessionMode(
    plan: TodayPlan?,
    dailyPlan: Boolean,
    studyAhead: Int,
): Pair<Ankrimento.SessionMode, Int> =
    when {
        !dailyPlan || plan == null -> Ankrimento.SessionMode.CLASSIC to 0
        plan.due > 0 -> Ankrimento.SessionMode.TODAY to 0
        else -> Ankrimento.SessionMode.AHEAD to studyAhead
    }

/**
 * Where the day stands, which decides what the session card offers. The schedule alone sets it: the new material
 * that is always available never makes the day longer, and studying it is a separate, explicit choice.
 */
internal sealed interface DayState {
    /** A session is under way: carry on with it. */
    data class Continue(
        val remaining: Int,
    ) : DayState

    /** Cards are due and no session is under way: today's session takes all of them. */
    data class Start(
        val due: Int,
    ) : DayState

    /** Nothing is due: the day is done. New material is offered as studying ahead. */
    data class Done(
        val done: Int,
    ) : DayState

    /** Upstream's own session behaviour: one button, a session from the whole pool (desktop's). */
    data class Classic(
        val remaining: Int?,
    ) : DayState

    companion object {
        /**
         * @param plan the day's counts, or null before they are known
         * @param remaining cards left in the session deck, or null before that is known
         * @param dailyPlan whether the daily plan is on (off: upstream's own sessions)
         */
        fun of(
            plan: TodayPlan?,
            remaining: Int?,
            dailyPlan: Boolean,
        ): DayState =
            when {
                !dailyPlan || plan == null -> Classic(remaining)
                remaining != null && remaining > 0 -> Continue(remaining)
                plan.due > 0 -> Start(plan.due)
                else -> Done(plan.done)
            }
    }
}
