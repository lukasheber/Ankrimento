// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

/** What the day holds: cards due now, new material still to come, and cards answered today. */
internal data class TodayPlan(
    val due: Int,
    val done: Int,
    /** New material the day still brings, of the daily amount. */
    val new: Int = 0,
) {
    /** Cards left today: what is due and the new material still to come. */
    val left: Int get() = due + new

    /** How much of the day's work is finished, 0 to 1; a day with nothing planned is complete. */
    val progress: Float get() = if (left + done == 0) 1f else done.toFloat() / (done + left)
}

/**
 * The kind of session to start next, with its number of cards: today's while anything is left of the day (the number
 * is the daily amount of new material), more new material once nothing is, and upstream's own with the daily plan off.
 */
internal fun nextSessionMode(
    plan: TodayPlan?,
    dailyPlan: Boolean,
    newPerDay: Int,
): Pair<Ankrimento.SessionMode, Int> =
    when {
        !dailyPlan || plan == null -> Ankrimento.SessionMode.CLASSIC to 0
        plan.left > 0 -> Ankrimento.SessionMode.TODAY to newPerDay
        else -> Ankrimento.SessionMode.AHEAD to (newPerDay.takeIf { it > 0 } ?: AnkrimentoSettings.DEFAULT_NEW_PER_DAY)
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

    /** Cards are left today and no session is under way: today's session takes all of them. */
    data class Start(
        val left: Int,
    ) : DayState

    /** Nothing is left: the day is done. More new material is offered, as a choice. */
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
                plan.left > 0 -> Start(plan.left)
                else -> Done(plan.done)
            }
    }
}
