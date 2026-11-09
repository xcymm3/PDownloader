package com.pdownloader.browser

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class HistoryDayLabelTest {
    @Test fun daySectionsRespectWeekAndYearBoundaries() {
        val monday = LocalDate.of(2026, 11, 9)
        assertEquals("今天 - 2026年11月9日", historyDayLabel(monday, monday))
        assertEquals("昨天 - 2026年11月8日", historyDayLabel(monday.minusDays(1), monday))
        assertEquals("本周 - 2026年11月9日", historyDayLabel(monday, monday.plusDays(2)))
        assertEquals("一周前 - 2026年11月2日", historyDayLabel(monday.minusDays(7), monday))
        val january = LocalDate.of(2027, 1, 1)
        assertEquals("昨天 - 2026年12月31日", historyDayLabel(january.minusDays(1), january))
    }
}
