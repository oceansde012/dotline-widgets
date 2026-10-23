package io.github.oceansde012.dotline

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/** One geocoding hit, see [WeatherRepo.search]. */
class City(val name: String, val admin: String, val country: String, val lat: Double, val lon: Double) {
    /** `Springfield, Illinois, United States` */
    fun label(): String = listOf(name, admin, country).filter { it.isNotBlank() }.distinct().joinToString(", ")
}

/** Last successful forecast for the stored location (DESIGN_SPEC.md 2.3). */
class Weather(
    val temp: Double,
    val code: Int,
    val isDay: Boolean,
    val hi: Double,
    val lo: Double,
    /** start (epoch ms) of the first hour within the next 2 h with precipitation probability >= 60%, or -1 */
    val rainAt: Long,
    /** that hour as the location's local `HH:mm` */
    val rainHm: String,
    val fetchedAt: Long,
)

/**
 * Open-Meteo client + the stored location (SharedPreferences). Network calls are blocking: call them
 * off the main thread ([refreshAsync] / [searchAsync] do that). Phase 3 UI uses [setLocation] / [search].
 */
object WeatherRepo {
    private const val TAG = "NdWeather"
    private const val PREFS = "nd_weather"
    private const val K_LAT = "lat"
    private const val K_LON = "lon"
    private const val K_NAME = "name"
    private const val K_DATA = "data"

    const val STALE_MS = 3 * 60 * 60 * 1000L
    private const val RAIN_PERCENT = 60

    private val io = Executors.newSingleThreadExecutor()

    // --- location ----------------------------------------------------------------------------

    fun hasLocation(ctx: Context): Boolean = ctx.prefs().contains(K_LAT) && ctx.prefs().contains(K_LON)

    /** `OK 200` or the last HTTP / network error text, for the diagnostics card */
    fun lastStatus(ctx: Context): String = ctx.prefs().getString("status", null) ?: "no request yet"

    fun locationName(ctx: Context): String = ctx.prefs().getString(K_NAME, "") ?: ""

    /** stores the city, drops the old city's forecast and fetches the new one in the background */
    fun setLocation(ctx: Context, lat: Double, lon: Double, name: String, done: (() -> Unit)? = null) {
        ctx.prefs().edit()
            .putString(K_LAT, lat.toString()).putString(K_LON, lon.toString()).putString(K_NAME, name)
            .remove(K_DATA).apply()
        WeatherWidgetProvider.updateAll(ctx)
        refreshAsync(ctx, minAgeMs = 0, done = done)
    }

    // --- cache -------------------------------------------------------------------------------

    fun cached(ctx: Context): Weather? {
        val s = ctx.prefs().getString(K_DATA, null) ?: return null
        return try {
            val j = JSONObject(s)
            Weather(
                j.getDouble("t"), j.getInt("c"), j.getBoolean("d"), j.getDouble("hi"), j.getDouble("lo"),
                j.getLong("ra"), j.getString("rh"), j.getLong("at"),
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun save(ctx: Context, w: Weather) {
        val j = JSONObject().put("t", w.temp).put("c", w.code).put("d", w.isDay).put("hi", w.hi).put("lo", w.lo)
            .put("ra", w.rainAt).put("rh", w.rainHm).put("at", w.fetchedAt)
        ctx.prefs().edit().putString(K_DATA, j.toString()).apply()
    }

    private fun Context.prefs() = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // --- refresh -----------------------------------------------------------------------------

    /** background fetch if the stored forecast is older than [minAgeMs]; re-renders the widgets either way */
    fun refreshAsync(ctx: Context, minAgeMs: Long, done: (() -> Unit)? = null) {
        val a = ctx.applicationContext
        io.execute {
            try {
                refresh(a, minAgeMs)
            } finally {
                done?.invoke()
            }
        }
    }

    /** blocking; returns false when there is no location or the request failed (old data is kept) */
    fun refresh(ctx: Context, minAgeMs: Long): Boolean {
        val a = ctx.applicationContext
        var ok = false
        if (hasLocation(a)) {
            val age = System.currentTimeMillis() - (cached(a)?.fetchedAt ?: 0L)
            if (age >= minAgeMs || age < 0) {
                try {
                    val p = a.prefs()
                    val w = fetch(p.getString(K_LAT, "")!!, p.getString(K_LON, "")!!)
                    save(a, w)
                    ok = true
                    a.prefs().edit().putString("status", "OK 200").apply()
                } catch (e: Exception) {
                    Log.w(TAG, "forecast failed", e)
                    a.prefs().edit().putString("status", (e.message ?: e.javaClass.simpleName).take(80)).apply()
                }
            } else {
                ok = true
            }
        }
        WeatherWidgetProvider.updateAll(a)   // also moves the stale label on after a failed fetch
        return ok
    }

    private fun fetch(lat: String, lon: String): Weather {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code,is_day&hourly=precipitation_probability" +
            "&daily=temperature_2m_max,temperature_2m_min&forecast_days=2&timezone=auto"
        val root = JSONObject(get(url))
        val cur = root.getJSONObject("current")
        val offsetMs = root.optLong("utc_offset_seconds", 0L) * 1000L
        val daily = root.getJSONObject("daily")

        // hourly times are local to the location: read them as UTC, then shift by the offset to real time
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val now = System.currentTimeMillis()
        val hourly = root.getJSONObject("hourly")
        val times = hourly.getJSONArray("time")
        val probs = hourly.getJSONArray("precipitation_probability")
        var rainAt = -1L
        var rainHm = ""
        for (i in 0 until times.length()) {
            val ts = times.getString(i)
            val start = (fmt.parse(ts)?.time ?: continue) - offsetMs
            // the hour that contains now, up to the one that starts within 2 h
            if (start + HOUR_MS <= now || start > now + 2 * HOUR_MS) continue
            if (!probs.isNull(i) && probs.getInt(i) >= RAIN_PERCENT) {
                rainAt = start
                rainHm = ts.substring(11, 16)
                break
            }
        }
        return Weather(
            temp = cur.getDouble("temperature_2m"),
            code = cur.getInt("weather_code"),
            isDay = cur.getInt("is_day") == 1,
            hi = daily.getJSONArray("temperature_2m_max").getDouble(0),
            lo = daily.getJSONArray("temperature_2m_min").getDouble(0),
            rainAt = rainAt,
            rainHm = rainHm,
            fetchedAt = now,
        )
    }

    // --- geocoding ---------------------------------------------------------------------------

    fun searchAsync(query: String, result: (List<City>) -> Unit) {
        io.execute { result(search(query)) }
    }

    /** blocking Open-Meteo geocoding search (up to 8 hits, in the system language); empty on any failure */
    fun search(query: String): List<City> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return try {
            val lang = if (Locale.getDefault().language == "uk") "uk" else "en"
            val url = "https://geocoding-api.open-meteo.com/v1/search?name=" + URLEncoder.encode(q, "UTF-8") +
                "&count=8&language=$lang&format=json"
            val arr = JSONObject(get(url)).optJSONArray("results") ?: return emptyList()
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                City(
                    name = o.optString("name"),
                    admin = o.optString("admin1"),
                    country = o.optString("country"),
                    lat = o.getDouble("latitude"),
                    lon = o.getDouble("longitude"),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "geocoding failed", e)
            emptyList()
        }
    }

    private const val HOUR_MS = 60 * 60 * 1000L

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            c.setRequestProperty("User-Agent", "DotlineWidgets/0.1")
            c.setRequestProperty("Accept", "application/json")
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}

