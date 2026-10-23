package io.github.oceansde012.dotline

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.text.format.DateFormat
import java.util.Date

/**
 * Live facts for the DIAGNOSTICS card (and its plain-text COPY REPORT). Only device model, app names and
 * states: no account data, no MAC addresses, no track text beyond what is on the widget.
 */
object Diagnostics {
    class Section(val title: String, val lines: List<String>)

    private val registry = java.util.concurrent.CopyOnWriteArrayList<Pair<String, (Context) -> List<String>>>()

    /** other modules add their own section: `Diagnostics.register("TITLE") { ctx -> listOf("line", ...) }` (replaces a section of the same title) */
    fun register(section: String, provider: (Context) -> List<String>) {
        registry.removeAll { it.first == section }
        registry.add(section to provider)
    }

    fun sections(a: Context): List<Section> = builtIn(a) + registry.map { (t, p) -> Section(t, safe { p(a) }) }

    private fun builtIn(a: Context): List<Section> = listOf(
        device(a), notification(a), sessions(a), spotify(a), Section("BLUETOOTH", safe { BudsRepo.diagnose(a) }),
        weather(a), battery(a), updater(a),
    )

    fun report(a: Context): String = sections(a).joinToString("\n\n") { s -> s.title + "\n" + s.lines.joinToString("\n") { "  $it" } }

    private fun safe(block: () -> List<String>): List<String> = try {
        block()
    } catch (e: Throwable) {
        listOf("error: ${e.javaClass.simpleName} ${e.message ?: ""}".take(100))
    }

    private fun device(a: Context) = Section("DEVICE", listOf(
        "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
        "app ${BuildConfig.VERSION_NAME}, package ${a.packageName}",
        "24h clock: ${DateFormat.is24HourFormat(a)}",
    ))

    private fun notification(a: Context) = Section("NOTIFICATION ACCESS", listOf(
        "granted: ${if (MediaTracker.hasAccess(a)) "YES" else "NO"}",
        "listening to sessions: ${MediaTracker.listening}",
    ))

    fun stateName(s: Int?): String = when (s) {
        PlaybackState.STATE_PLAYING -> "PLAYING"
        PlaybackState.STATE_PAUSED -> "PAUSED"
        PlaybackState.STATE_BUFFERING -> "BUFFERING"
        PlaybackState.STATE_STOPPED -> "STOPPED"
        PlaybackState.STATE_NONE -> "NONE"
        PlaybackState.STATE_ERROR -> "ERROR"
        null -> "-"
        else -> "state $s"
    }

    private fun sessions(a: Context): Section = Section("MEDIA SESSIONS", safe {
        if (!MediaTracker.hasAccess(a)) return@safe listOf("no access, cannot list sessions")
        val list = a.getSystemService(MediaSessionManager::class.java)?.getActiveSessions(MediaTracker.listenerComponent(a)).orEmpty()
        if (list.isEmpty()) return@safe listOf("none")
        val picked = MediaTracker.pick(list)
        list.map { c ->
            val md = c.metadata
            val label = MediaTracker.appLabelOrNull(a, c.packageName) ?: "?"
            val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: "-"
            val artUri = listOf(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
                .firstNotNullOfOrNull { md?.getString(it)?.takeIf { s -> s.isNotBlank() } }
            val bmp = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) != null || md?.getBitmap(MediaMetadata.METADATA_KEY_ART) != null
            "${if (c === picked) "> " else "  "}$label (${c.packageName}) ${stateName(c.playbackState?.state)} | $title | " +
                "actions=0x${java.lang.Long.toHexString(c.playbackState?.actions ?: 0L)} art: bitmap=${if (bmp) "Y" else "N"} " +
                "uri=${artUri?.let { android.net.Uri.parse(it).scheme } ?: "-"}"
        }
    })

    private fun spotify(a: Context): Section {
        val installed = MediaTracker.appLabelOrNull(a, MediaTracker.SPOTIFY) != null
        val t = MediaTracker.track
        return Section("SPOTIFY (${MediaTracker.SPOTIFY})", listOf(
            "installed: $installed",
            "in widget now: ${t != null && t.pkg == MediaTracker.SPOTIFY}${if (t?.isAd == true) " (ad)" else ""}",
            "notification fallback: ${MediaTracker.notificationSummary(MediaTracker.SPOTIFY)}",
            "last art load error: ${ArtLoader.lastError.ifEmpty { "-" }}",
        ))
    }

    private fun weather(a: Context): Section {
        val w = WeatherRepo.cached(a)
        val fmt = { t: Long -> DateFormat.format("dd.MM HH:mm:ss", Date(t)).toString() }
        return Section("WEATHER", listOf(
            "city set: ${WeatherRepo.hasLocation(a)}${if (WeatherRepo.hasLocation(a)) " (${WeatherRepo.locationName(a)})" else ""}",
            "last fetch: ${if (w == null) "never" else fmt(w.fetchedAt)}",
            "last status: ${WeatherRepo.lastStatus(a)}",
            "current temp: ${w?.temp ?: "-"} C",
        ))
    }

    private fun battery(a: Context): Section {
        val s = PowerWidgetProvider.current(a)
        return Section("BATTERY", listOf(
            "level ${s.level}%, status ${s.status}, charging=${s.charging}, full=${s.full}, temp ${s.tempC} C",
        ))
    }

    private fun updater(a: Context): Section {
        val cur = Updater.versionOf(Updater.installedCode(a))
        val remote = Updater.readyCode(a).let { if (it > 0) Updater.versionOf(it) else "-" }
        return Section("UPDATER", listOf(
            "current $cur, remote/ready $remote, enabled=${BuildConfig.UPDATES_ENABLED}",
            "last result: ${Updater.lastResult(a).ifEmpty { "-" }}",
            "last error: ${Updater.lastError(a).ifEmpty { "-" }}",
        ))
    }
}


