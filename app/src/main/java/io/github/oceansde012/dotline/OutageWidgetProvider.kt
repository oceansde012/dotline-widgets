package io.github.oceansde012.dotline

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

class OutageWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = reschedule(context)

    override fun onAppWidgetOptionsChanged(
        context: Context, manager: AppWidgetManager, id: Int, options: Bundle,
    ) = updateAll(context)

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action in listOf(ROLLOVER, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_LOCALE_CHANGED)) {
            reschedule(context)
        }
    }

    override fun onDisabled(context: Context) {
        OutageJob.cancel(context)
        refreshAt = Long.MAX_VALUE
        context.getSystemService(AlarmManager::class.java)?.cancel(rolloverIntent(context))
    }

    companion object {
        const val SECTION = "outages"
        private const val ROLLOVER = "io.github.oceansde012.dotline.OUTAGE_ROLLOVER"
        private val OFF_COLOR = 0xfff4978f.toInt()
        private val ON_COLOR = 0xffa6dbb2.toInt()
        private val OFF_BAR = 0xffd66b61.toInt()
        private val ON_BAR = 0xff6bb487.toInt()
        private val dateFormat = DateTimeFormatter.ofPattern("dd.MM", Locale.ROOT)
        private val checkFormat = DateTimeFormatter.ofPattern("dd.MM HH:mm", Locale.ROOT).withZone(OutageSchedule.zone)
        private var refreshAt = 0L

        private data class Forecast(
            val date: LocalDate, val queue: String?,
            val today: OutageRepo.Day?, val tomorrow: OutageRepo.Day?, val next: OutageTransition?,
        )

        fun hasWidgets(ctx: Context): Boolean =
            AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, OutageWidgetProvider::class.java)).isNotEmpty()

        fun reschedule(ctx: Context) {
            if (!hasWidgets(ctx)) return
            updateAll(ctx)
            OutageJob.schedule(ctx)
        }

        internal fun refreshIfDue(ctx: Context) {
            if (System.currentTimeMillis() >= refreshAt) updateAll(ctx)
        }

        private fun rolloverIntent(ctx: Context): PendingIntent =
            PendingIntent.getBroadcast(ctx, 121, Intent(ctx, OutageWidgetProvider::class.java).setAction(ROLLOVER), PendingIntent.FLAG_IMMUTABLE)

        fun updateAll(ctx: Context) {
            val manager = AppWidgetManager.getInstance(ctx)
            val ids = manager.getAppWidgetIds(ComponentName(ctx, OutageWidgetProvider::class.java))
            if (ids.isEmpty()) {
                refreshAt = Long.MAX_VALUE
                return
            }
            val now = ZonedDateTime.now(OutageSchedule.zone)
            val forecast = forecast(ctx, now)
            for (id in ids) {
                try {
                    val size = WidgetSize.of(ctx, manager, id, 380f, 66f)
                    manager.updateAppWidget(id, render(ctx, size.width, size.height, forecast))
                } catch (e: Exception) {
                    Log.w("OutageWidget", "Could not update widget", e)
                }
            }
            // Advance past the shown outage on restoration, and roll the date at midnight.
            val midnight = forecast.date.plusDays(1).atStartOfDay(OutageSchedule.zone).toInstant()
            val restoration = forecast.next?.onAt
                ?.takeIf { it < OutageSchedule.MINUTES_PER_DAY }
                ?.let { forecast.date.atTime(it / 60, it % 60).atZone(OutageSchedule.zone).toInstant() }
                ?.takeIf { it > now.toInstant() }
            val staleAt = listOfNotNull(forecast.today, forecast.tomorrow)
                .filter { it.windows != null && !it.stale }
                .minOfOrNull { it.staleAt }
            val at = minOf((restoration ?: midnight).toEpochMilli(), staleAt ?: Long.MAX_VALUE)
            if (at != refreshAt) {
                refreshAt = at
                ctx.getSystemService(AlarmManager::class.java)
                    ?.setAndAllowWhileIdle(AlarmManager.RTC, at, rolloverIntent(ctx))
            }
        }

        internal fun dateLabel(date: LocalDate): String = date.format(dateFormat)

        internal fun status(ctx: Context, day: OutageRepo.Day, compact: Boolean = false): String = when {
            day.windows?.isEmpty() == true -> ctx.getString(if (compact) R.string.outage_short_none else R.string.outage_none)
            day.windows != null -> day.windows.joinToString(" · ") { it.label() }
            day.failed || (day.checkedAt != 0L && day.stale) ->
                ctx.getString(if (compact) R.string.outage_short_unavailable else R.string.outage_unavailable)
            day.checkedAt == 0L -> ctx.getString(if (compact) R.string.outage_short_waiting else R.string.outage_waiting)
            else -> ctx.getString(if (compact) R.string.outage_short_unpublished else R.string.outage_unpublished)
        }

        internal fun checkedLabel(ctx: Context, day: OutageRepo.Day): String {
            val time = checkFormat.format(Instant.ofEpochMilli(day.checkedAt))
            return ctx.getString(if (day.stale) R.string.outage_saved_at else R.string.outage_checked_at, time)
        }

        fun buildPreview(base: Context, w: Float, h: Float): RemoteViews =
            render(base, w, h, forecast(base, ZonedDateTime.now(OutageSchedule.zone)))

        private fun forecast(ctx: Context, now: ZonedDateTime): Forecast {
            val date = now.toLocalDate()
            val queue = OutageRepo.queue(ctx)
            val (today, tomorrow) = queue?.let { OutageRepo.days(ctx, date, it) } ?: (null to null)
            val next = OutageSchedule.nextOutage(now.hour * 60 + now.minute, today?.windows, tomorrow?.windows)
            return Forecast(date, queue, today, tomorrow, next)
        }

        private fun render(base: Context, w: Float, h: Float, f: Forecast): RemoteViews {
            val ctx = Lang.wrap(base)
            val rv = RemoteViews(ctx.packageName, R.layout.widget_outage)
            Tile.apply(rv, R.id.tile, R.id.zone, h)
            rv.setViewVisibility(R.id.outage_fallback, View.GONE)
            rv.setOnClickPendingIntent(R.id.zone, Taps.app(ctx, 122, SECTION))
            for (id in listOf(R.id.outage_off_label, R.id.outage_on_label, R.id.outage_on_time, R.id.outage_divider,
                    R.id.outage_day_bar, R.id.outage_second_bar, R.id.outage_today_detail, R.id.outage_tomorrow_detail)) {
                rv.setViewVisibility(id, View.GONE)
            }

            val pad = Tile.pad(h)
            val width = (w - 2f * pad).coerceAtLeast(64f)
            val wide = width >= 200f && h >= 52f
            val short = h < 48f
            val saved = listOfNotNull(f.today, f.tomorrow).any { it.windows != null && it.stale }
            val useTomorrow = f.today?.windows == null && f.tomorrow?.windows != null
            val displayedDate = if (useTomorrow) f.date.plusDays(1) else f.date
            val displayedDay = if (useTomorrow) f.tomorrow else f.today
            val date = dateLabel(displayedDate)
            val title = f.queue?.let {
                when {
                    !wide -> "$date · $it" + if (saved) " " + ctx.getString(R.string.outage_compact_saved) else ""
                    useTomorrow && width >= 280f -> ctx.getString(R.string.outage_only_tomorrow, date, it) +
                        if (saved) " · " + ctx.getString(R.string.outage_compact_saved) else ""
                    saved -> ctx.getString(R.string.outage_widget_saved, date, it)
                    else -> ctx.getString(R.string.outage_widget_heading, date, it)
                }
            } ?: ctx.getString(R.string.outage_widget_label)
            val description = mutableListOf(title)
            // Store each label's measured height and position before centring the whole block.
            val positions = mutableListOf<Triple<Int, Float, Float>>()
            fun put(id: Int, text: String, x: Float, y: Float, size: Float, maxWidth: Float = width,
                    color: Int = Nd.FG, minSize: Float = 8f, lines: Int = 1): Float {
                positions += Triple(id, x, y)
                return rv.label(ctx, id, text, size, color, maxWidth, medium = true,
                    minSizeDp = minSize, maxLines = lines).hDp
            }

            var y = put(R.id.outage_header, title, pad, 0f, if (short) 8.5f else 10f, color = Nd.DIM) + 3f
            val next = f.next
            if (next == null) {
                val message = emptyForecast(ctx, f)
                description += message
                y += put(R.id.outage_off_time, message, pad, y, if (wide) 15f else if (short) 9f else 11f, lines = 2)
            } else {
                val off = timeLabel(f.date, next.offAt)
                val on = next.onAt?.let { timeLabel(f.date, it) }
                val offLabel = ctx.getString(R.string.outage_off_label)
                val onLabel = ctx.getString(R.string.outage_on_label)
                description += "$offLabel $off"
                description += if (on != null) "$onLabel $on" else ctx.getString(R.string.outage_on_unknown)
                val eventsTop = y
                val restorationColor = if (on == null) Nd.DIM else ON_COLOR
                if (wide && h >= 100f) {
                    val column = (width - 12f) / 2f
                    val right = pad + column + 12f
                    val offHeight = put(R.id.outage_off_label, offLabel, pad, y, 10f, column, OFF_COLOR)
                    val onHeight = put(R.id.outage_on_label, onLabel, right, y, 10f, column, restorationColor)
                    y += maxOf(offHeight, onHeight) + 2f
                    val size = if (h >= 64f) 19f else 16f
                    val offTimeHeight = put(R.id.outage_off_time, off, pad, y, size, column, minSize = 11f)
                    val onTimeHeight = put(R.id.outage_on_time, on ?: ctx.getString(R.string.outage_time_unknown),
                        right, y, if (on == null) 13f else size, column, minSize = 9f)
                    y += maxOf(offTimeHeight, onTimeHeight)
                } else {
                    val fullLabels = wide && width >= 270f
                    val offText = if (fullLabels) "$offLabel $off" else ctx.getString(R.string.outage_off_short, off)
                    val onText = on?.let { if (fullLabels) "$onLabel $it" else ctx.getString(R.string.outage_on_short, it) }
                        ?: ctx.getString(R.string.outage_on_unknown_short)
                    if (wide) {
                        val column = (width - 12f) / 2f
                        val leftHeight = put(R.id.outage_off_time, offText, pad, y, 15f, column, OFF_COLOR, 9f)
                        val rightHeight = put(R.id.outage_on_time, onText, pad + column + 12f, y, 15f, column, restorationColor, 9f)
                        y += maxOf(leftHeight, rightHeight)
                    } else {
                        val size = if (short) 8.5f else 11f
                        y += put(R.id.outage_off_time, offText, pad, y, size, color = OFF_COLOR) + 1f
                        y += put(R.id.outage_on_time, onText, pad, y, size, color = restorationColor)
                    }
                }
                if (wide) {
                    rv.size(R.id.outage_divider, 1f, y - eventsTop)
                    rv.setViewVisibility(R.id.outage_divider, View.VISIBLE)
                    positions += Triple(R.id.outage_divider, pad + width / 2f - 0.5f, eventsTop)
                }
            }

            fun putBar(id: Int, day: OutageRepo.Day?, gap: Float) {
                val track = if (short) 3f else 5f
                val available = h - y - gap
                if (available < track) return
                val axes = wide && available >= track + 13f
                val barHeight = track + if (axes) 13f else 0f
                y += gap
                rv.setViewVisibility(id, View.VISIBLE)
                rv.setImageViewBitmap(id, dayBar(ctx, width, track, day?.windows, axes))
                rv.size(id, width, barHeight)
                positions += Triple(id, pad, y)
                y += barHeight
            }
            if (f.queue != null) {
                putBar(R.id.outage_day_bar, displayedDay, if (short) 2f else 4f)
                if (h < 124f && displayedDay != null) description += date + " · " + status(ctx, displayedDay)
            }

            fun dayLine(id: Int, name: Int, date: LocalDate, day: OutageRepo.Day) {
                val value = status(ctx, day, compact = true)
                val hours = if (day.windows.isNullOrEmpty()) value else ctx.getString(R.string.outage_off_ranges, value)
                val detail = ctx.getString(name, dateLabel(date)) + " · " + hours
                description += detail
                y += 5f
                val lines = if (h >= 140f) 2 else 1
                if (h - y >= 12f) y += put(id, detail, pad, y, 10f, color = Nd.DIM, lines = lines)
            }
            if (h >= 124f && f.today != null) {
                dayLine(R.id.outage_today_detail, R.string.outage_today_date, f.date, f.today)
            }
            if (h >= 88f && f.tomorrow != null) {
                dayLine(R.id.outage_tomorrow_detail, R.string.outage_title_date, f.date.plusDays(1), f.tomorrow)
                if (h >= 124f && !useTomorrow) putBar(R.id.outage_second_bar, f.tomorrow, 3f)
            }

            val top = ((h - y) / 2f).coerceAtLeast(0f)
            for ((id, x, offset) in positions) rv.margins(id, start = x, top = top + offset)
            rv.setContentDescription(R.id.zone, description.joinToString(". "))
            return rv
        }

        private fun dayBar(ctx: Context, width: Float, track: Float, windows: List<OutageWindow>?, axes: Boolean): Bitmap {
            val scale = ctx.resources.displayMetrics.density.coerceAtMost(3f)
            val pixels = (width * scale).roundToInt().coerceAtLeast(1)
            val trackPx = track * scale
            val height = ((track + if (axes) 13f else 0f) * scale).roundToInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(pixels, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val bar = RectF(0f, 0f, pixels.toFloat(), trackPx)
            canvas.save()
            val corners = Path().apply { addRoundRect(bar, trackPx / 2f, trackPx / 2f, Path.Direction.CW) }
            canvas.clipPath(corners)
            paint.color = if (windows == null) Nd.CHIP else ON_BAR
            canvas.drawRect(bar, paint)
            paint.color = OFF_BAR
            for (window in windows.orEmpty()) {
                val start = pixels * window.start.toFloat() / OutageSchedule.MINUTES_PER_DAY
                val end = pixels * window.end.toFloat() / OutageSchedule.MINUTES_PER_DAY
                canvas.drawRect(start, 0f, end, trackPx, paint)
            }
            if (axes && windows != null) {
                paint.color = 0x55101012
                paint.strokeWidth = 0.5f * scale
                for (hour in 1 until 24) {
                    val x = pixels * hour / 24f
                    canvas.drawLine(x, 0f, x, trackPx, paint)
                }
            }
            paint.color = Nd.BG
            paint.strokeWidth = scale
            for (mark in 1..3) {
                val x = pixels * mark / 4f
                canvas.drawLine(x, 0f, x, trackPx, paint)
            }
            canvas.restore()
            if (axes) {
                paint.color = Nd.DIM
                paint.textSize = 8f * scale
                paint.typeface = ctx.resources.getFont(R.font.label_regular)
                for ((i, label) in listOf("00", "06", "12", "18", "24").withIndex()) {
                    val textWidth = paint.measureText(label)
                    val x = (pixels * i / 4f - textWidth / 2f).coerceIn(0f, (pixels - textWidth).coerceAtLeast(0f))
                    canvas.drawText(label, x, trackPx + 10f * scale, paint)
                }
            }
            return bitmap
        }

        private fun timeLabel(date: LocalDate, minute: Int): String {
            val local = minute % OutageSchedule.MINUTES_PER_DAY
            val time = "%02d:%02d".format(Locale.ROOT, local / 60, local % 60)
            return if (minute < OutageSchedule.MINUTES_PER_DAY) time else dateLabel(date.plusDays(1)) + " " + time
        }

        private fun emptyForecast(ctx: Context, f: Forecast): String = when {
            f.queue == null -> ctx.getString(R.string.outage_choose)
            f.today?.windows != null && f.tomorrow?.windows != null -> ctx.getString(R.string.outage_no_more_known)
            f.today?.windows != null -> ctx.getString(R.string.outage_no_more_today)
            f.tomorrow?.windows != null -> ctx.getString(R.string.outage_no_tomorrow)
            f.today != null -> status(ctx, f.today, compact = true)
            else -> ctx.getString(R.string.outage_waiting)
        }
    }
}
