package io.translatetube.local.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class AppLogSearchQueryTest {
    @Test
    fun spaceSeparatedWordsAreAllRequired() {
        val query = AppLogSearchQuery.parse("  翻译   失败 ")
        assertEquals(listOf("翻译", "失败"), query.include)
        assertTrue(query.exclude.isEmpty())
    }

    @Test
    fun fullWidthSpaceAlsoSeparatesWords() {
        assertEquals(listOf("翻译", "失败"), AppLogSearchQuery.parse("翻译　失败").include)
    }

    @Test
    fun dashPrefixExcludesAndLoneDashIsAPlainTerm() {
        val query = AppLogSearchQuery.parse("timeout -retry -")
        assertEquals(listOf("timeout", "-"), query.include)
        assertEquals(listOf("retry"), query.exclude)
    }

    @Test
    fun timestampsWithDashesAreNotTreatedAsExclusions() {
        val query = AppLogSearchQuery.parse("2026-11-15 10:58")
        assertEquals(listOf("2026-11-15", "10:58"), query.include)
        assertTrue(query.exclude.isEmpty())
    }

    @Test
    fun quotedPhraseKeepsItsSpacesAndCanBeExcluded() {
        val query = AppLogSearchQuery.parse("\"play blocked\" -“no media” 失败")
        assertEquals(listOf("play blocked", "失败"), query.include)
        assertEquals(listOf("no media"), query.exclude)
    }

    @Test
    fun unterminatedQuoteTakesTheRestOfTheInput() {
        assertEquals(listOf("a b c"), AppLogSearchQuery.parse("\"a b c").include)
    }

    @Test
    fun blankInputIsEmptyAndDuplicatesAreDropped() {
        assertTrue(AppLogSearchQuery.parse("   ").isEmpty)
        assertEquals(listOf("abc"), AppLogSearchQuery.parse("ABC abc").include)
    }

    @Test
    fun matchesFieldsRequiresEveryIncludeTermAndNoExcludeTerm() {
        val fields = listOf("字幕翻译", "翻译失败：安全过滤", "translation")
        assertTrue(AppLogSearchQuery.parse("翻译 安全").matchesFields(fields))
        assertFalse(AppLogSearchQuery.parse("翻译 配音").matchesFields(fields))
        assertFalse(AppLogSearchQuery.parse("翻译 -安全").matchesFields(fields))
        assertTrue(AppLogSearchQuery.parse("翻译 -配音").matchesFields(fields))
    }

    @Test
    fun highlightRangesAreCaseInsensitiveSortedAndMerged() {
        assertEquals(
            listOf(0..2, 9..11),
            AppLogSearchHighlight.ranges("Foo bar, foo", listOf("foo")),
        )
        // Overlapping and adjacent hits merge into one highlighted span.
        assertEquals(
            listOf(0..5),
            AppLogSearchHighlight.ranges("abcdef", listOf("abc", "cde", "ef")),
        )
        assertTrue(AppLogSearchHighlight.ranges("abc", listOf("zzz")).isEmpty())
        assertTrue(AppLogSearchHighlight.ranges("abc", emptyList()).isEmpty())
    }

    @Test
    fun historyKeepsNewestFirstWithoutDuplicatesAndIgnoresTinyQueries() {
        var history = emptyList<String>()
        history = AppLogSearchHistoryPolicy.push(history, "翻译 失败")
        history = AppLogSearchHistoryPolicy.push(history, "a")
        history = AppLogSearchHistoryPolicy.push(history, "timeout")
        history = AppLogSearchHistoryPolicy.push(history, "  翻译 失败 ")
        assertEquals(listOf("翻译 失败", "timeout"), history)

        (1..10).forEach { history = AppLogSearchHistoryPolicy.push(history, "query $it") }
        assertEquals(AppLogSearchHistoryPolicy.MAX_ITEMS, history.size)
        assertEquals("query 10", history.first())
    }

    @Test
    fun historyRoundTripsThroughItsStorageFormat() {
        val history = listOf("翻译 失败", "timeout -retry")
        assertEquals(history, AppLogSearchHistoryPolicy.decode(AppLogSearchHistoryPolicy.encode(history)))
        assertTrue(AppLogSearchHistoryPolicy.decode(null).isEmpty())
    }

    @Test
    fun timeRangesStartWhereTheirLabelsSay() {
        val utc = TimeZone.getTimeZone("UTC")
        val now = Calendar.getInstance(utc).apply {
            clear()
            set(2026, Calendar.NOVEMBER, 15, 10, 58, 30)
        }.timeInMillis
        val midnight = Calendar.getInstance(utc).apply {
            clear()
            set(2026, Calendar.NOVEMBER, 15, 0, 0, 0)
        }.timeInMillis

        assertEquals(0L, AppLogTimeRange.ALL.sinceMs(now, utc))
        assertEquals(now - 600_000L, AppLogTimeRange.LAST_10_MIN.sinceMs(now, utc))
        assertEquals(now - 3_600_000L, AppLogTimeRange.LAST_HOUR.sinceMs(now, utc))
        assertEquals(midnight, AppLogTimeRange.TODAY.sinceMs(now, utc))
    }
}
