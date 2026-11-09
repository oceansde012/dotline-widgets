package io.github.oceansde012.dotline

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

internal object OutageRepo {
    const val SOURCE = "https://www.hoe.com.ua/page/pogodinni-vidkljuchennja"
    const val FIND_QUEUE = "https://hoe.com.ua/shutdown/queue"
    private const val MAX_BYTES = 512 * 1024
    private const val MIN_AGE = 10 * 60 * 1000L
    private const val STALE_AGE = 90 * 60 * 1000L
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val listeners = mutableListOf<() -> Unit>()
    private var refreshing = false

    data class Day(
        val windows: List<OutageWindow>?,
        val checkedAt: Long,
        val failed: Boolean,
    ) {
        val staleAt: Long get() = checkedAt + STALE_AGE + 1
        val stale: Boolean get() = failed || System.currentTimeMillis() !in checkedAt until staleAt
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("outage_schedule", Context.MODE_PRIVATE)

    fun queue(ctx: Context): String? =
        prefs(ctx).getString("queue", null)?.takeIf { it in OutageSchedule.queues }

    fun chooseQueue(ctx: Context, queue: String) {
        require(queue in OutageSchedule.queues)
        prefs(ctx).edit().putString("queue", queue).apply()
    }

    fun days(ctx: Context, date: LocalDate, queue: String): Pair<Day, Day> {
        val snapshot = prefs(ctx).all
        return readDay(snapshot, date, queue) to readDay(snapshot, date.plusDays(1), queue)
    }

    private fun readDay(snapshot: Map<String, *>, date: LocalDate, queue: String): Day {
        val key = date.toString()
        val checked = snapshot["checked_$key"] as? Long ?: 0L
        val failed = snapshot["failed_$key"] == true
        val row = (snapshot["day_$key"] as? String)?.let { JSONObject(it).optJSONArray(queue) }
        val windows = row?.let { list ->
            List(list.length()) { i -> list.getJSONArray(i).let { OutageWindow(it.getInt(0), it.getInt(1)) } }
        }
        return Day(windows, checked, failed)
    }

    // All callers are on the main thread. A foreground refresh and an active job share one request.
    fun refresh(ctx: Context, force: Boolean = false, done: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        if (queue(app) == null) {
            done?.invoke()
            return
        }
        if (refreshing) {
            done?.let(listeners::add)
            return
        }
        val today = OutageSchedule.date()
        val now = System.currentTimeMillis()
        val p = prefs(app)
        val age = now - p.getLong("last_attempt", 0)
        if (!force && age in 0 until MIN_AGE && p.getString("attempt_date", null) == today.toString()) {
            done?.invoke()
            return
        }
        done?.let(listeners::add)
        refreshing = true
        executor.execute {
            try {
                fetch(app)
            } finally {
                main.post {
                    refreshing = false
                    val completed = listeners.toList()
                    listeners.clear()
                    OutageWidgetProvider.updateAll(app)
                    completed.forEach { it() }
                }
            }
        }
    }

    private fun fetch(ctx: Context) {
        val html = runCatching(::download).onFailure { Log.w("OutageRepo", "Schedule fetch failed", it) }
        val now = Instant.now()
        val today = OutageSchedule.date(now)
        val dates = listOf(today, today.plusDays(1))
        val p = prefs(ctx)
        val edit = p.edit()
        for (key in p.all.keys) {
            if (key.startsWith("day_") || key.startsWith("checked_") || key.startsWith("failed_")) {
                if (dates.none { it.toString() == key.substringAfter('_') }) edit.remove(key)
            }
        }
        for (date in dates) {
            val parsed = html.mapCatching { OutageSchedule.parse(it, setOf(date))[date] }
            edit.putBoolean("failed_$date", parsed.isFailure)
            if (parsed.isSuccess) {
                edit.putLong("checked_$date", now.toEpochMilli())
                val schedule = parsed.getOrNull()
                if (schedule == null) {
                    edit.remove("day_$date")
                } else {
                    val json = JSONObject()
                    schedule.forEach { (queue, windows) ->
                        val ranges = JSONArray()
                        windows.forEach { ranges.put(JSONArray().put(it.start).put(it.end)) }
                        json.put(queue, ranges)
                    }
                    edit.putString("day_$date", json.toString())
                }
            } else if (html.isSuccess) {
                Log.w("OutageRepo", "Could not read schedule for $date", parsed.exceptionOrNull())
            }
        }
        edit.putLong("last_attempt", now.toEpochMilli()).putString("attempt_date", today.toString()).apply()
    }

    private fun download(): String {
        val connection = URL(SOURCE).openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.setRequestProperty("Accept", "text/html")
            connection.setRequestProperty("User-Agent", "DotlineWidgets/" + BuildConfig.VERSION_NAME)
            if (connection.responseCode != 200) throw IOException("HTTP " + connection.responseCode)
            return connection.inputStream.use { input ->
                val data = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (data.size() + count > MAX_BYTES) throw IOException("Schedule page is too large")
                    data.write(buffer, 0, count)
                }
                data.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }
}
