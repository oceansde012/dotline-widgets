package io.github.oceansde012.dotline

import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent

/** What the player widget shows; null track = idle face. */
class Track(
    val pkg: String,
    val title: String,
    val artist: String,
    val art: Bitmap?,
    val artKey: Long,
    val durationMs: Long,
    val playing: Boolean,
    private val position: Long,
    private val updateTime: Long,
    private val speed: Float,
    val sessionActivity: PendingIntent?,
) {
    /** everything that needs a full (bitmap) update when it changes; position is not part of it */
    val faceKey: List<Any?> = listOf(pkg, title, artist, artKey, durationMs, playing)

    val hasPosition: Boolean get() = position >= 0

    /** PlaybackState extrapolation: position + (now - lastPositionUpdateTime) * speed */
    fun positionNow(): Long {
        if (position < 0) return -1
        var p = position
        if (playing && updateTime > 0) p += ((SystemClock.elapsedRealtime() - updateTime) * speed).toLong()
        if (durationMs > 0) p = p.coerceAtMost(durationMs)
        return p.coerceAtLeast(0)
    }

    fun speedOrOne(): Float = if (speed > 0f) speed else 1f
}

/**
 * Follows media sessions (needs notification access) and drives the player widget.
 * Everything runs on the main thread. Rendering is event driven: session list changes and
 * MediaController callbacks trigger a re-render; the only timer is the progress tick, which runs
 * only while something is playing, the screen is interactive and a player widget exists.
 */
object MediaTracker {
    private const val TAG = "NdMedia"
    private const val PREFS = "nd"
    private const val KEY_LAST_PKG = "last_pkg"
    private const val PAUSE_IDLE_MS = 30 * 60 * 1000L

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var msm: MediaSessionManager? = null
    private var listening = false
    private val watched = ArrayList<Pair<MediaController, MediaController.Callback>>()

    var controller: MediaController? = null
        private set
    var track: Track? = null
        private set

    private var screenOn = true
    private var screenReceiverRegistered = false
    private var lastFace: List<Any?>? = null
    private var lastFaceValid = false

    fun listenerComponent(ctx: Context) = ComponentName(ctx, MediaListenerService::class.java)

    fun hasAccess(ctx: Context): Boolean = try {
        ctx.getSystemService(NotificationManager::class.java)
            ?.isNotificationListenerAccessGranted(listenerComponent(ctx)) == true
    } catch (e: Exception) {
        false
    }

    fun lastPackage(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_PKG, null)

    /** label of the last player app, for the idle face; null if unknown / uninstalled */
    fun lastAppLabel(ctx: Context): String? {
        val pkg = lastPackage(ctx) ?: return null
        return try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            null
        }
    }

    /** Idempotent. Called by the listener service, the widget provider and the activity. */
    fun ensureStarted(ctx: Context) {
        val a = ctx.applicationContext
        app = a
        registerScreenReceiver(a)
        HeadWidgetProvider.registerRuntime(a)
        LiveHost.ensure(a)   // battery / power / user-present / Bluetooth receivers of the power, weather and buds widgets
        if (listening || !hasAccess(a)) return
        val m = a.getSystemService(MediaSessionManager::class.java) ?: return
        try {
            m.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent(a), main)
            msm = m
            listening = true
            watch(m.getActiveSessions(listenerComponent(a)))
        } catch (e: SecurityException) {
            Log.w(TAG, "no access to media sessions", e)
        }
    }

    /** Listener disconnected (access revoked or service unbound): drop sessions, show idle. */
    fun stop() {
        try {
            msm?.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (e: Exception) {
            Log.w(TAG, "removeOnActiveSessionsChangedListener", e)
        }
        listening = false
        msm = null
        unwatchAll()
        reevaluate()
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> watch(list) }

    private fun watch(list: List<MediaController>?) {
        unwatchAll()
        for (c in list.orEmpty()) {
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = reevaluate()
                override fun onMetadataChanged(metadata: MediaMetadata?) = reevaluate()
                override fun onSessionDestroyed() = reevaluate()
            }
            try {
                c.registerCallback(cb, main)
                watched.add(c to cb)
            } catch (e: Exception) {
                Log.w(TAG, "registerCallback ${c.packageName}", e)
            }
        }
        reevaluate()
    }

    private fun unwatchAll() {
        for ((c, cb) in watched) {
            try {
                c.unregisterCallback(cb)
            } catch (e: Exception) {
                // session already gone
            }
        }
        watched.clear()
    }

    // state + metadata callbacks usually arrive in a burst: coalesce them into one render
    private val evaluate = Runnable { evaluateNow() }

    private fun reevaluate() {
        main.removeCallbacks(evaluate)
        main.postDelayed(evaluate, 80)
    }

    private fun evaluateNow() {
        val a = app ?: return
        val c = pick(watched.map { it.first })
        controller = c
        val t = c?.let { snapshot(a, it) }
        if (t != null && t.pkg != lastPackage(a)) {
            a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LAST_PKG, t.pkg).apply()
        }
        track = if (t != null && pausedTooLong(t, c)) null else t   // stale pause: idle face (controller stays for resume)
        render(a)
    }

    private var pausedPkg: String? = null
    private var pausedSince = 0L

    /**
     * PAUSED for more than 30 minutes counts as idle (never show a stale track as current). The pause
     * start is the session's own lastPositionUpdateTime when it has one; re-evaluates when the limit passes.
     */
    private fun pausedTooLong(t: Track, c: MediaController): Boolean {
        if (t.playing) {
            pausedPkg = null
            return false
        }
        val now = SystemClock.elapsedRealtime()
        if (pausedPkg != t.pkg) {
            pausedPkg = t.pkg
            val lpu = c.playbackState?.lastPositionUpdateTime ?: 0L
            pausedSince = if (lpu in 1..now) lpu else now
        }
        val left = PAUSE_IDLE_MS - (now - pausedSince)
        if (left <= 0) return true
        main.removeCallbacks(evaluate)
        main.postDelayed(evaluate, left + 500)
        return false
    }

    private fun isPlayingState(s: Int?) = when (s) {
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING,
        PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> true
        else -> false
    }

    /**
     * The session that is PLAYING (transient buffering/skipping counts as playing), else the most
     * recent PAUSED one (getActiveSessions is ordered by priority / recency). STOPPED, NONE and ERROR
     * are idle: such sessions are dead or stale.
     */
    private fun pick(list: List<MediaController>): MediaController? =
        list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull { isPlayingState(it.playbackState?.state) }
            ?: list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PAUSED }

    private fun snapshot(ctx: Context, c: MediaController): Track {
        val md = c.metadata
        val ps = c.playbackState
        val pkg = c.packageName
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)?.takeIf { it.isNotBlank() }
            ?: appLabel(ctx, pkg)
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)?.takeIf { it.isNotBlank() }
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)?.takeIf { it.isNotBlank() }
            ?: ""
        val art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val duration = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val playing = isPlayingState(ps?.state)
        return Track(
            pkg = pkg,
            title = title,
            artist = artist,
            art = art,
            artKey = artKey(art),
            durationMs = if (duration > 0) duration else 0L,
            playing = playing,
            position = ps?.position ?: -1L,
            updateTime = ps?.lastPositionUpdateTime ?: 0L,
            speed = ps?.playbackSpeed ?: 1f,
            sessionActivity = c.sessionActivity,
        )
    }

    /** cheap identity of the artwork: every getMetadata() returns a new Bitmap object */
    private fun artKey(b: Bitmap?): Long {
        if (b == null || b.isRecycled) return 0L
        var h = 31L * b.width + b.height
        if (b.config != Bitmap.Config.HARDWARE) {
            for (i in 1..4) for (j in 1..4) {
                h = h * 31 + b.getPixel(b.width * i / 5, b.height * j / 5)
            }
        }
        return h
    }

    private fun appLabel(ctx: Context, pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        ctx.getString(R.string.unknown_title)
    }

    // --- rendering ---------------------------------------------------------------------------

    private fun playerIds(a: Context): IntArray =
        AppWidgetManager.getInstance(a).getAppWidgetIds(ComponentName(a, PlayerWidgetProvider::class.java))

    /**
     * Full update (bitmaps + layout) when the face changed, the widget was (re)sized or added;
     * otherwise a partial update carrying only progress + time text (a few hundred bytes).
     */
    fun render(ctx: Context, forceFull: Boolean = false, onlyId: Int? = null) {
        val a = ctx.applicationContext
        app = a
        val mgr = AppWidgetManager.getInstance(a)
        val ids = playerIds(a)
        if (ids.isEmpty()) {
            main.removeCallbacks(tick)
            lastFaceValid = false
            return
        }
        val t = track
        val face = t?.faceKey
        val faceChanged = !lastFaceValid || face != lastFace
        when {
            faceChanged -> {
                for (id in ids) mgr.updateAppWidget(id, PlayerRenderer.full(a, mgr, id, t))
                lastFace = face
                lastFaceValid = true
            }
            forceFull -> {
                val targets = if (onlyId != null) intArrayOf(onlyId) else ids
                for (id in targets) mgr.updateAppWidget(id, PlayerRenderer.full(a, mgr, id, t))
            }
            t != null -> mgr.partiallyUpdateAppWidget(ids, PlayerRenderer.progress(a, t))
        }
        scheduleTick()
    }

    private val tick = Runnable { tickNow() }

    private fun tickNow() {
        val a = app ?: return
        val t = track ?: return
        val ids = playerIds(a)
        if (ids.isEmpty()) return
        AppWidgetManager.getInstance(a).partiallyUpdateAppWidget(ids, PlayerRenderer.progress(a, t))
        scheduleTick()
    }

    /** next tick lands just after the next whole second of the track position */
    private fun scheduleTick() {
        main.removeCallbacks(tick)
        val t = track ?: return
        if (!t.playing || !screenOn || !t.hasPosition) return
        val pos = t.positionNow()
        val delay = ((1000 - pos % 1000) / t.speedOrOne()).toLong() + 20
        main.postDelayed(tick, delay.coerceIn(250L, 1500L))
    }

    private fun registerScreenReceiver(a: Context) {
        if (screenReceiverRegistered) return
        screenOn = a.getSystemService(PowerManager::class.java)?.isInteractive ?: true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        // system broadcasts still reach a NOT_EXPORTED receiver; the flag exists from API 33
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            a.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            a.registerReceiver(screenReceiver, filter)
        }
        screenReceiverRegistered = true
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    main.removeCallbacks(tick)
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    render(context)   // catch the progress up, restart the tick if playing
                }
            }
        }
    }

    // --- commands from the widget buttons ----------------------------------------------------

    fun command(ctx: Context, action: String) {
        ensureStarted(ctx)
        val c = controller ?: freshPick(ctx)
        when (action) {
            PlayerWidgetProvider.ACTION_PREV -> c?.transportControls?.skipToPrevious()
            PlayerWidgetProvider.ACTION_NEXT -> c?.transportControls?.skipToNext()
            PlayerWidgetProvider.ACTION_PLAY_PAUSE -> when {
                c == null -> mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY)
                isPlayingState(c.playbackState?.state) -> c.transportControls.pause()
                else -> c.transportControls.play()
            }
        }
    }

    private fun freshPick(ctx: Context): MediaController? = try {
        val a = ctx.applicationContext
        a.getSystemService(MediaSessionManager::class.java)
            ?.getActiveSessions(listenerComponent(a))
            ?.let { pick(it) }
    } catch (e: SecurityException) {
        null
    }

    /** no session: the system routes the key to the last media button receiver (resumes the last player) */
    private fun mediaKey(ctx: Context, code: Int) {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }
}
