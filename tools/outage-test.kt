package io.github.oceansde012.dotline

import java.io.File
import java.time.Instant
import java.time.LocalDate

private val today = LocalDate.of(2026, 11, 8)
private val tomorrow = today.plusDays(1)
private val dates = setOf(today, tomorrow)

private fun schedule(heading: String, first: String): String =
    "<p>$heading</p>" + (1..6).flatMap { group ->
        (1..2).map { part ->
            if (group == 1 && part == 1) "<p><strong>Підчерга 1.1</strong>&nbsp;– $first.</p>"
            else "<p>Підчерга $group.$part – 08:00-10:00.</p>"
        }
    }.joinToString("")

private fun page(vararg blocks: String) =
    """<main><div class="post">${blocks.joinToString("<hr />")}</div></main>"""

fun main(args: Array<String>) {
    val heading = "На 9 листопада будуть застосовані графіки погодинних відключень (ГПВ)"
    val update = "Оновлено графік погодинних відключень на 09.11.2026"
    val older = schedule("Графік погодинних відключень на 8 листопада", "00:00-02:00")
    val first = schedule(heading, "10:00-13:00; 22:00-24:00")
    val revised = schedule(update, "9:30 — 11:00; 23:30-24:00")
    val parsed = OutageSchedule.parse(page(revised, older, first), dates)
    check(parsed[today]?.get("1.1") == listOf(OutageWindow(0, 120)))
    check(parsed[tomorrow]?.get("1.1") == listOf(OutageWindow(570, 660), OutageWindow(1410, 1440)))
    check(tomorrow !in OutageSchedule.parse(page(older), dates))
    check(tomorrow !in OutageSchedule.parse(page(schedule("Графік ГПВ на 09.11.2025", "10:00-13:00")), dates))
    check(tomorrow !in OutageSchedule.parse(page(schedule("Графік ГПВ на 19 листопада", "10:00-13:00")), dates))
    check(OutageSchedule.parse(page(schedule(heading, "не вимикатимуть")), dates)[tomorrow]?.get("1.1") == emptyList<OutageWindow>())
    check(OutageSchedule.parse(page("<p>9 листопада графіки погодинних відключень не застосовуватимуться.</p>"), dates)[tomorrow]?.size == 12)
    check(OutageSchedule.parse(page(schedule(heading, "21:00-23:00; 20:00-21:00")), dates)[tomorrow]?.get("1.1") == listOf(OutageWindow(1200, 1380)))
    for (broken in listOf(
        page(schedule(update, "25:00-26:00"), first),
        page(schedule(update, "10:00-13:00; 21:00"), first),
        page("<p>$update</p><p>Підчерга 1.1 – 10:00-13:00.</p>", first),
        page(schedule(update, "10:00-13:00") + "<p>Підчерга 1.1 – 14:00-18:00.</p>"),
        page("<p>Графік ГПВ на 9 листопада діятиме. Додаткових обмежень не планується.</p>"),
        "<html><h1>Access denied</h1></html>",
    )) check(runCatching { OutageSchedule.parse(broken, dates) }.isFailure)
    check(OutageSchedule.date(Instant.parse("2026-11-08T21:59:59Z")) == today)
    check(OutageSchedule.date(Instant.parse("2026-11-08T22:00:00Z")) == tomorrow)
    val newYear = LocalDate.of(2027, 1, 1)
    check(OutageSchedule.parse(page(schedule("Графік ГПВ на 1 січня", "00:00-02:00")), setOf(newYear)).containsKey(newYear))
    if (args.isNotEmpty()) {
        val live = OutageSchedule.parse(File(args[0]).readText(), setOf(tomorrow))
        check(live[tomorrow]?.size == 12)
        check(live[tomorrow]?.get("1.1") == listOf(OutageWindow(600, 900), OutageWindow(1080, 1260)))
        println("Captured official page: latest 9 November schedule verified")
    }
    println("Outage schedule checks passed")
}
