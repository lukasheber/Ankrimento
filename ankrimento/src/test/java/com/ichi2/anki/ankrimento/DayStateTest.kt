// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ankrimento

import org.junit.Assert.assertEquals
import org.junit.Test

class DayStateTest {
    @Test
    fun `a session under way is continued whatever is due`() {
        assertEquals(DayState.Continue(7), DayState.of(TodayPlan(due = 30, done = 5), remaining = 7, dailyPlan = true))
        assertEquals(DayState.Continue(7), DayState.of(TodayPlan(due = 0, done = 5), remaining = 7, dailyPlan = true))
    }

    @Test
    fun `cards due and no session starts today's session`() {
        assertEquals(DayState.Start(30), DayState.of(TodayPlan(due = 30, done = 5), remaining = 0, dailyPlan = true))
        assertEquals(DayState.Start(3), DayState.of(TodayPlan(due = 3, done = 0), remaining = null, dailyPlan = true))
    }

    @Test
    fun `new material still to come is part of what is left of the day`() {
        assertEquals(DayState.Start(14), DayState.of(TodayPlan(due = 4, done = 0, new = 10), remaining = 0, dailyPlan = true))
        assertEquals(0.5f, TodayPlan(due = 4, done = 7, new = 3).progress, 0f)
        assertEquals(DayState.Done(9), DayState.of(TodayPlan(due = 0, done = 9, new = 0), remaining = 0, dailyPlan = true))
    }

    @Test
    fun `nothing due means the day is done, however much new material waits`() {
        assertEquals(DayState.Done(12), DayState.of(TodayPlan(due = 0, done = 12), remaining = 0, dailyPlan = true))
    }

    @Test
    fun `with the daily plan off, or before the plan is known, sessions are upstream's own`() {
        assertEquals(DayState.Classic(4), DayState.of(TodayPlan(due = 30, done = 5), remaining = 4, dailyPlan = false))
        assertEquals(DayState.Classic(null), DayState.of(null, remaining = null, dailyPlan = true))
    }

    @Test
    fun `progress is the share of the day's cards answered`() {
        assertEquals(0.25f, TodayPlan(due = 30, done = 10).progress, 0f)
        assertEquals(0f, TodayPlan(due = 5, done = 0).progress, 0f)
        assertEquals(1f, TodayPlan(due = 0, done = 8).progress, 0f)
        assertEquals(1f, TodayPlan(due = 0, done = 0).progress, 0f)
    }

    @Test
    fun `cards due start today's session, nothing due studies ahead in the chosen size`() {
        assertEquals(Ankrimento.SessionMode.TODAY to 10, nextSessionMode(TodayPlan(due = 12, done = 3), dailyPlan = true, newPerDay = 10))
        assertEquals(
            Ankrimento.SessionMode.TODAY to 10,
            nextSessionMode(TodayPlan(due = 0, done = 3, new = 4), dailyPlan = true, newPerDay = 10),
        )
        assertEquals(Ankrimento.SessionMode.AHEAD to 10, nextSessionMode(TodayPlan(due = 0, done = 3), dailyPlan = true, newPerDay = 10))
    }

    @Test
    fun `more new material, once the day is done, is the daily amount or a default when it is none`() {
        assertEquals(Ankrimento.SessionMode.AHEAD to 7, nextSessionMode(TodayPlan(due = 0, done = 3), dailyPlan = true, newPerDay = 7))
        assertEquals(
            Ankrimento.SessionMode.AHEAD to AnkrimentoSettings.DEFAULT_NEW_PER_DAY,
            nextSessionMode(TodayPlan(due = 0, done = 3), dailyPlan = true, newPerDay = 0),
        )
    }

    @Test
    fun `without the daily plan or its counts the session is upstream's own`() {
        assertEquals(
            Ankrimento.SessionMode.CLASSIC to 0,
            nextSessionMode(TodayPlan(due = 12, done = 3), dailyPlan = false, newPerDay = 10),
        )
        assertEquals(Ankrimento.SessionMode.CLASSIC to 0, nextSessionMode(null, dailyPlan = true, newPerDay = 10))
    }
}
