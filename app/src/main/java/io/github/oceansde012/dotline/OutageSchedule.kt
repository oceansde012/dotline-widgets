package io.github.oceansde012.dotline

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

internal data class OutageWindow(val start: Int, val end: Int) {
    fun label(): String = "${time(start)}–${time(end)}"

    private fun time(minutes: Int): String = "%02d:%02d".format(Locale.ROOT, minutes / 60, minutes % 60)
}

/** Minutes relative to today's midnight. A null restoration means the published schedule ends first. */
internal data class OutageTransition(val offAt: Int, val onAt: Int?)

internal object OutageSchedule {
    const val MINUTES_PER_DAY = 24 * 60
    val zone: ZoneId = runCatching { ZoneId.of("Europe/Kyiv") }.getOrElse { ZoneId.of("Europe/Kiev") }
    val queues = (1..6).flatMap { group -> listOf("$group.1", "$group.2") }

    private val months = listOf(
        "січня", "лютого", "березня", "квітня", "травня", "червня",
        "липня", "серпня", "вересня", "жовтня", "листопада", "грудня",
    )
    private val content = Regex("""<div\b[^>]*\bclass\s*=\s*["'][^"']*\bpost\b[^"']*["'][^>]*>""", RegexOption.IGNORE_CASE)
    private val heading = Regex("""графік\p{L}*|погодинн\p{L}*\s+відключен\p{L}*|\bГПВ\b""", RegexOption.IGNORE_CASE)
    private val numericDate = Regex("""(?<![\d.])(\d{1,2})\.(\d{1,2})(?:\.(\d{4}))?(?![\d.]\d|\d)""")
    private val textDate = Regex("""(?<!\d)(\d{1,2})\s+(${months.joinToString("|")})(?:\s+(\d{4}))?\b""", RegexOption.IGNORE_CASE)
    private val blockBreak = Regex("""<hr\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val lineBreak = Regex("""<(?:br\b[^>]*|/(?:p|div|li|h[1-6])\s*)>""", RegexOption.IGNORE_CASE)
    private val tags = Regex("""<[^>]*>""")
    private val spaces = Regex("""[\p{Zs}\t]+""")
    private val queue = Regex("""^підчерга\s+([1-6]\.[12])\.?\s*[:\-–—]\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val timeRange = Regex("""(?:з\s+)?(\d{1,2})[:.](\d{2})\s*(?:[-–—−]|до)\s*(\d{1,2})[:.](\d{2})""", RegexOption.IGNORE_CASE)
    private val noOutages = Regex(
        """не\s+(?:застосовуватим\p{L}*|застосовуватис\p{L}*|застосовано|застосовуються|планується|передбачено|заплановано|вимикатим\p{L}*|будуть\s+(?:застос\p{L}*|діяти|вимикатим\p{L}*))|без\s+відключень""",
        RegexOption.IGNORE_CASE,
    )
    private val noSchedule = Regex(
        """(?:графіки?\s+погодинних відключень(?:\s*\(ГПВ\))?|\bГПВ\b|погодинні відключення)\s+(?:(?:на\s+)?(?:${textDate.pattern}|${numericDate.pattern})\s+)?(?:${noOutages.pattern})\b""",
        RegexOption.IGNORE_CASE,
    )

    fun date(now: Instant = Instant.now()): LocalDate = now.atZone(zone).toLocalDate()

    fun nextOutage(
        minute: Int, today: List<OutageWindow>?, tomorrow: List<OutageWindow>?,
    ): OutageTransition? {
        val firstTomorrow = tomorrow?.firstOrNull()
        val currentOrNext = today?.firstOrNull { it.end > minute }
        if (currentOrNext == null) {
            val next = firstTomorrow ?: return null
            val restoration = if (next.end < MINUTES_PER_DAY) MINUTES_PER_DAY + next.end else null
            return OutageTransition(MINUTES_PER_DAY + next.start, restoration)
        }
        val restoration = when {
            currentOrNext.end < MINUTES_PER_DAY -> currentOrNext.end
            tomorrow == null -> null
            firstTomorrow?.start != 0 -> MINUTES_PER_DAY
            firstTomorrow.end < MINUTES_PER_DAY -> MINUTES_PER_DAY + firstTomorrow.end
            else -> null
        }
        return OutageTransition(currentOrNext.start, restoration)
    }

    /** The provider inserts revisions above previous versions. Only read the first block for a date. */
    fun parse(html: String, dates: Set<LocalDate>): Map<LocalDate, Map<String, List<OutageWindow>>> {
        val start = requireNotNull(content.find(html)) { "Schedule page content is missing" }.range.last + 1
        val mainEnd = html.indexOf("</main", start, ignoreCase = true)
        val body = html.substring(start, if (mainEnd < 0) html.length else mainEnd)
        val result = mutableMapOf<LocalDate, Map<String, List<OutageWindow>>>()
        for (block in body.split(blockBreak)) {
            val lines = textLines(block)
            var index = 0
            while (index < lines.size) {
                val header = lines[index++]
                if (!heading.containsMatchIn(header) || queue.matches(header)) continue
                val datesInHeader = dates.filter { matchesDate(header, it) }
                if (datesInHeader.isEmpty()) continue
                val rows = lines.drop(index).takeWhile { line ->
                    !(heading.containsMatchIn(line) && (numericDate.containsMatchIn(line) || textDate.containsMatchIn(line)))
                }
                for (date in datesInHeader) {
                    if (date !in result) result[date] = readRows(header, rows)
                }
            }
        }
        return result
    }

    private fun textLines(html: String): List<String> = html.replace(lineBreak, "\n").replace(tags, "")
        .replace(Regex("""&(?:nbsp|#160|#x0*a0);""", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("""&(?:ndash|#8211|#x2013);""", RegexOption.IGNORE_CASE), "–")
        .replace(Regex("""&(?:mdash|#8212|#x2014);""", RegexOption.IGNORE_CASE), "—")
        .lineSequence().map { it.replace(spaces, " ").trim() }.filter { it.isNotEmpty() }.toList()

    private fun matchesDate(text: String, date: LocalDate): Boolean {
        fun matches(match: MatchResult, month: Int): Boolean =
            match.groupValues[1].toInt() == date.dayOfMonth && month == date.monthValue &&
                (match.groupValues[3].isEmpty() || match.groupValues[3].toInt() == date.year)
        return numericDate.findAll(text).any { matches(it, it.groupValues[2].toInt()) } ||
            textDate.findAll(text).any { matches(it, months.indexOf(it.groupValues[2].lowercase(Locale.ROOT)) + 1) }
    }

    private fun readRows(header: String, lines: List<String>): Map<String, List<OutageWindow>> {
        val rows = mutableMapOf<String, List<OutageWindow>>()
        for (line in lines) {
            val match = queue.matchEntire(line) ?: continue
            val (_, name, hours) = match.groupValues
            require(name !in rows) { "Duplicate outage queue $name" }
            rows[name] = readHours(hours.trimEnd('.', ' '))
        }
        if (rows.isEmpty() && noSchedule.containsMatchIn(header)) return queues.associateWith { emptyList() }
        require(rows.keys == queues.toSet()) { "Incomplete outage schedule" }
        return rows
    }

    private fun readHours(hours: String): List<OutageWindow> {
        if (noOutages.matches(hours) || hours == "—" || hours == "-") return emptyList()
        val windows = hours.split(';', ',').map { entry ->
            val (_, h1, m1, h2, m2) = requireNotNull(timeRange.matchEntire(entry.trim())) { "Invalid outage time range" }.groupValues
            val start = h1.toInt() * 60 + m1.toInt()
            val end = h2.toInt() * 60 + m2.toInt()
            require(m1.toInt() < 60 && m2.toInt() < 60 && start in 0 until end && end <= 1440) { "Outage outside the day" }
            OutageWindow(start, end)
        }.sortedBy { it.start }
        val merged = mutableListOf<OutageWindow>()
        for (window in windows) {
            val last = merged.lastOrNull()
            if (last != null && window.start <= last.end) {
                merged[merged.lastIndex] = OutageWindow(last.start, maxOf(last.end, window.end))
            } else {
                merged.add(window)
            }
        }
        return merged
    }
}
