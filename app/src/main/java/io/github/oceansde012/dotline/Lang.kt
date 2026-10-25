package io.github.oceansde012.dotline

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * App language. "" (default) follows the device: values-uk / values-ru, everything else falls back to English.
 * A picked language is applied by [wrap]: the activity and every widget render use that context, so the strings,
 * upper-casing and weekday names all come out in one language whatever the system is set to.
 */
object Lang {
    const val SYSTEM = ""
    val CODES = listOf(SYSTEM, "en", "uk", "ru")

    private const val K_LANG = "lang"
    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("nd_settings", Context.MODE_PRIVATE)

    fun pref(ctx: Context): String = sp(ctx).getString(K_LANG, SYSTEM)?.takeIf { it in CODES } ?: SYSTEM

    fun setPref(ctx: Context, code: String) = sp(ctx).edit().putString(K_LANG, code).apply()

    /** the language the user sees: the picked one, else the device's when the app has it, else English */
    fun locale(ctx: Context): Locale {
        val picked = pref(ctx)
        if (picked.isNotEmpty()) return Locale.forLanguageTag(picked)
        val dev = Locale.getDefault()
        return if (dev.language in setOf("en", "uk", "ru")) dev else Locale.ENGLISH
    }

    /** two-letter language of [locale], for web APIs */
    fun apiCode(ctx: Context): String = locale(ctx).language

    /** [ctx] with the picked language (its own configuration otherwise kept); the same context when the device language is used */
    fun wrap(ctx: Context): Context {
        val code = pref(ctx)
        if (code.isEmpty()) return ctx
        val loc = Locale.forLanguageTag(code)
        val conf = Configuration(ctx.resources.configuration)
        conf.setLocale(loc)
        conf.setLocales(LocaleList(loc))
        return ctx.createConfigurationContext(conf)
    }

    /** `07:30` / `7:30 PM` by the system 12 / 24 h setting, with the AM / PM marker in the app language */
    fun clock(ctx: Context, at: Long): String =
        SimpleDateFormat(if (DateFormat.is24HourFormat(ctx)) "HH:mm" else "h:mm a", locale(ctx)).format(Date(at))
}
