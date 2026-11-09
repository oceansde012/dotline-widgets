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
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import java.time.Instant
import java.time.LocalDate
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
        context.getSystemService(AlarmManager::class.java)?.cancel(rolloverIntent(context))
    }

    companion object {
        const val SECTION = "outages"
        private const val ROLLOVER = "io.github.oceansde012.dotline.OUTAGE_ROLLOVER"

        fun hasWidgets(ctx: Context): Boolean =
            AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, OutageWidgetProvider::class.java)).isNotEmpty()

        fun reschedule(ctx: Context) {
            if (!hasWidgets(ctx)) return
            updateAll(ctx)
            OutageJob.schedule(ctx)
            val midnight = OutageSchedule.date().plusDays(1).atStartOfDay(OutageSchedule.zone).toInstant()
            ctx.getSystemService(AlarmManager::class.java)?.set(AlarmManager.RTC, midnight.toEpochMilli(), rolloverIntent(ctx))
        }

        private fun rolloverIntent(ctx: Context): PendingIntent =
            PendingIntent.getBroadcast(ctx, 121, Intent(ctx, OutageWidgetProvider::class.java).setAction(ROLLOVER), PendingIntent.FLAG_IMMUTABLE)

        fun updateAll(ctx: Context) {
            val manager = AppWidgetManager.getInstance(ctx)
            val ids = manager.getAppWidgetIds(ComponentName(ctx, OutageWidgetProvider::class.java))
            for (id in ids) {
                try {
                    val size = WidgetSize.of(ctx, manager, id, 290f, 145f)
                    manager.updateAppWidget(id, buildPreview(ctx, size.width, size.height))
                } catch (e: Exception) {
                    Log.w("OutageWidget", "Could not update widget", e)
                }
            }
        }

        internal fun dateLabel(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("dd.MM", Locale.ROOT))

        internal fun status(ctx: Context, day: OutageRepo.Day): String = when {
            day.windows?.isEmpty() == true -> ctx.getString(R.string.outage_none)
            day.windows != null -> day.windows.joinToString(" · ") { it.label() }
            day.failed || (day.checkedAt != 0L && day.stale) -> ctx.getString(R.string.outage_unavailable)
            day.checkedAt == 0L -> ctx.getString(R.string.outage_waiting)
            else -> ctx.getString(R.string.outage_unpublished)
        }

        internal fun checkedLabel(ctx: Context, day: OutageRepo.Day): String {
            val format = DateTimeFormatter.ofPattern("dd.MM HH:mm", Locale.ROOT).withZone(OutageSchedule.zone)
            val time = format.format(Instant.ofEpochMilli(day.checkedAt))
            return ctx.getString(if (day.stale) R.string.outage_saved_at else R.string.outage_checked_at, time)
        }

        fun buildPreview(base: Context, w: Float, h: Float): RemoteViews {
            val ctx = Lang.wrap(base)
            val queue = OutageRepo.queue(ctx)
            val date = OutageSchedule.date().plusDays(1)
            val day = queue?.let { OutageRepo.day(ctx, date, it) }
            val windows = day?.windows
            val rv = RemoteViews(ctx.packageName, R.layout.widget_outage)
            Tile.apply(rv, R.id.tile, R.id.zone, h)
            rv.setViewVisibility(R.id.outage_fallback, View.GONE)
            rv.setOnClickPendingIntent(R.id.zone, Taps.app(ctx, 122, SECTION))

            val pad = Tile.pad(h)
            val width = (w - 2 * pad).coerceAtLeast(96f)
            val headline = ctx.getString(R.string.outage_title_date, dateLabel(date))
            val header = rv.label(ctx, R.id.outage_header, headline, 12f, Nd.FG, width, medium = true, minSizeDp = 9f)
            rv.margins(R.id.outage_header, start = pad, top = pad)

            val group = queue?.let { ctx.getString(R.string.outage_queue, it) }.orEmpty()
            val info = if (day == null) ctx.getString(R.string.outage_choose) else status(ctx, day)
            val groupTop = pad + header.hDp + 5f
            val groupHeight = if (group.isNotEmpty()) {
                val art = rv.label(ctx, R.id.outage_group, group, 11f, Nd.DIM, width - 12f, minSizeDp = 9f)
                rv.margins(R.id.outage_group, start = pad, top = groupTop)
                art.hDp + 5f
            } else {
                rv.setViewVisibility(R.id.outage_group, View.GONE)
                0f
            }
            val hasWindows = !windows.isNullOrEmpty()
            rv.setViewVisibility(R.id.outage_dot, if (hasWindows) View.VISIBLE else View.GONE)
            rv.margins(R.id.outage_dot, top = groupTop + 3f, end = pad)

            val footHeight = if (day != null && day.checkedAt > 0L && (day.windows != null || !day.stale)) {
                val checked = rv.label(ctx, R.id.outage_checked, checkedLabel(ctx, day), 9f, Nd.DIM, width, minSizeDp = 8f)
                rv.margins(R.id.outage_checked, start = pad, top = h - pad - checked.hDp)
                checked.hDp + 4f
            } else {
                rv.setViewVisibility(R.id.outage_checked, View.GONE)
                0f
            }

            val timelineTop = groupTop + groupHeight
            val showTimeline = windows != null && h >= 124f
            if (showTimeline) {
                rv.setViewVisibility(R.id.outage_timeline, View.VISIBLE)
                rv.setImageViewBitmap(R.id.outage_timeline, timeline(ctx, width, windows))
                rv.place(R.id.outage_timeline, pad, timelineTop, width, 6f)
            } else {
                rv.setViewVisibility(R.id.outage_timeline, View.GONE)
            }
            val infoTop = timelineTop + if (showTimeline) 12f else 0f
            val infoHeight = h - pad - footHeight - infoTop
            rv.label(ctx, R.id.outage_ranges, info, 12f, Nd.FG, width, maxLines = if (infoHeight >= 27f) 2 else 1, minSizeDp = 9f)
            rv.margins(R.id.outage_ranges, start = pad, top = infoTop)
            rv.setContentDescription(R.id.zone, listOf(headline, group, info).filter { it.isNotEmpty() }.joinToString(". "))
            return rv
        }

        private fun timeline(ctx: Context, width: Float, windows: List<OutageWindow>): Bitmap {
            val density = ctx.resources.displayMetrics.density
            val w = (width * density).roundToInt().coerceIn(48, 1200)
            val h = (6f * density).roundToInt().coerceAtLeast(2)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val pitch = w / 48f
            for (slot in 0 until 48) {
                val start = slot * 30
                paint.color = if (windows.any { it.start < start + 30 && it.end > start }) Nd.FG else Nd.OFF
                canvas.drawCircle((slot + 0.5f) * pitch, h / 2f, minOf(pitch * 0.34f, h * 0.48f), paint)
            }
            return bitmap
        }
    }
}
