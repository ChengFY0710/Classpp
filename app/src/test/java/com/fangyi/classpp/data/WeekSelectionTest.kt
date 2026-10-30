package com.fangyi.classpp.data

import com.fangyi.classpp.data.model.Parity
import com.fangyi.classpp.data.model.WeekSegment
import com.fangyi.classpp.data.model.weeksFromSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 周数选择集合并：任何输入都必须与原集合**逐周等价**，
 * 同时尽量合并成少数几段（单测里对典型输入钉住段的形状）。
 */
class WeekSelectionTest {

    private fun assertEquivalent(selected: Set<Int>) {
        val pattern = weeksFromSelection(selected)
        for (week in 1..30) {
            assertEquals("week $week", week in selected, pattern.contains(week))
        }
    }

    @Test
    fun emptySelectionGivesEmptyPattern() {
        assertTrue(weeksFromSelection(emptySet()).segments.isEmpty())
        assertEquivalent(emptySet())
    }

    @Test
    fun consecutiveWeeksMergeIntoOneAllSegment() {
        val selected = (1..12).toSet()
        assertEquals(listOf(WeekSegment(1, 12, Parity.ALL)), weeksFromSelection(selected).segments)
        assertEquivalent(selected)
    }

    @Test
    fun oddWeeksMergeIntoOneOddSegment() {
        val selected = (1..16).filter { it % 2 == 1 }.toSet()
        assertEquals(listOf(WeekSegment(1, 15, Parity.ODD)), weeksFromSelection(selected).segments)
        assertEquivalent(selected)
    }

    @Test
    fun evenWeeksMergeIntoOneEvenSegment() {
        val selected = (1..16).filter { it % 2 == 0 }.toSet()
        assertEquals(listOf(WeekSegment(2, 16, Parity.EVEN)), weeksFromSelection(selected).segments)
        assertEquivalent(selected)
    }

    @Test
    fun singleWeekStaysSingleAllSegment() {
        assertEquals(listOf(WeekSegment(5, 5, Parity.ALL)), weeksFromSelection(setOf(5)).segments)
        assertEquivalent(setOf(5))
    }

    @Test
    fun disjointRunsStaySeparate() {
        val selected = setOf(1, 2, 3, 6, 7)
        assertEquals(
            listOf(WeekSegment(1, 3, Parity.ALL), WeekSegment(6, 7, Parity.ALL)),
            weeksFromSelection(selected).segments,
        )
        assertEquivalent(selected)
    }

    @Test
    fun mixedSelectionsStayEquivalent() {
        listOf(
            setOf(1, 2, 3, 5, 7, 9),
            setOf(2, 4, 6, 8, 11, 12, 13),
            setOf(1, 4, 5, 6, 9),
            (1..20).toSet() - setOf(7, 8),
            setOf(1, 3, 4, 5, 7, 9, 11),
            setOf(1, 2, 4, 6, 8, 10, 11, 12, 15),
        ).forEach { assertEquivalent(it) }
    }
}
