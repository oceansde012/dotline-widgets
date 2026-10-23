package io.github.oceansde012.dotline

import android.content.Context

/** The two user settings of the app screen (DESIGN_SPEC.md section 4). 12h / 24h always follows the system. */
object Prefs {
    private const val FILE = "nd_settings"
    private const val K_FAHRENHEIT = "fahrenheit"
    private const val K_TIME_TOTAL = "player_time_total"

    private fun Context.p() = applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun fahrenheit(ctx: Context): Boolean = ctx.p().getBoolean(K_FAHRENHEIT, false)

    fun setFahrenheit(ctx: Context, on: Boolean) = ctx.p().edit().putBoolean(K_FAHRENHEIT, on).apply()

    /** a Celsius value (the unit the forecast is stored in) in the chosen unit */
    fun temp(ctx: Context, celsius: Double): Double = if (fahrenheit(ctx)) celsius * 9.0 / 5.0 + 32.0 else celsius

    /** true: the player's right-hand time is the track length; false: the time left (with a minus) */
    fun timeTotal(ctx: Context): Boolean = ctx.p().getBoolean(K_TIME_TOTAL, false)

    fun setTimeTotal(ctx: Context, on: Boolean) = ctx.p().edit().putBoolean(K_TIME_TOTAL, on).apply()
}
