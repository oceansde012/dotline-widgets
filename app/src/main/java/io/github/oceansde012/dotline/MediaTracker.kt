package io.github.oceansde012.dotline

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
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
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.KeyEvent

/** What the player widget shows; null track = idle face. */
class Track(
    val pkg: String,
    /** the source app's name (`SPOTIFY` in the face) */
    val appName: String,
    val title: String,
    val artist: String,
    val art: Bitmap?,
    val artKey: Long,
    val durationMs: Long,
    val playing: Boolean,
    private val position: Long,
    private val updateTime: Long,
    private val speed: Float,
    /** STATE_PLAYING exactly: only then the position is extrapolated and the tick runs */
    val ticking: Boolean,
    val sessionActivity: PendingIntent?,
    /** an advertisement (Spotify): title as is, no progress */
    val isAd: Boolean = false,
) {
    /** everything that needs a full (bitmap) update when it changes; position is not part of it */
    val faceKey: List<Any?> = listOf(pkg, appName, title, artist, artKey, durationMs, playing, ticking, isAd)

    val hasPosition: Boolean get() = position >= 0

    /** the dot progress bar and the times exist only for a track with a known length */
    val showProgress: Boolean get() = hasPosition && durationMs > 0 && !isAd

    /** PlaybackState extrapolation: position + (now - lastPositionUpdateTime) * speed */
    fun positionNow(): Long {
        if (position < 0) return -1
        var p = position
        if (ticking && updateTime > 0) p += ((SystemClock.elapsedRealtime() - updateTime) * speedOrOne()).toLong()
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
 *
 * Weak sessions (no title / no art bitmap) are completed from the media notification of the same app
 * (title, text, large icon) and from art URIs ([ArtLoader]).
 */
object MediaTracker {
    private const val TAG = "NdMedia"
    private const val PREFS = "nd"
    private const val KEY_LAST_PKG = "last_pkg"
    const val SPOTIFY = "com.spotify.music"

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var msm: MediaSessionManager? = null
    @Volatile var listening = false
        private set
    private val watched = ArrayList<Pair<MediaController, MediaController.Callback>>()

    var controller: MediaController? = null
        private set
    var track: Track? = null
        private set

    private var screenOn = true
    private var screenReceiverRegistered = false
    private var lastFace: List<Any?>? = null
    private var lastFaceValid = false

    // completion data from media notifications, by package
    private class NotifInfo(val title: String, val text: String, val art: Bitmap?)
    private val notifs = HashMap<String, NotifInfo>()

    fun listenerComponent(ctx: Context) = ComponentName(ctx, MediaListenerService::class.java)

    fun hasAccess(ctx: Context): Boolean = try {
        ctx.getSystemService(NotificationManager::class.java)
            ?.isNotificationListenerAccessGranted(listenerComponent(ctx)) == true
    } catch (e: Exception) {
        false
    }

    fun lastPackage(ctx: Context): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_PKG, null)

    fun appLabelOrNull(ctx: Context, pkg: String?): String? {
        if (pkg == null) return null
        return try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            null
        }
    }

    /** label of the last player app, for the idle face; null if unknown / uninstalled */
    fun lastAppLabel(ctx: Context): String? = appLabelOrNull(ctx, lastPackage(ctx))

    /** Idempotent. Called by the listener service, the widget provider and the activity. */
    fun ensureStarted(ctx: Context) {
        val a = ctx.applicationContext
        app = a
        registerScreenReceiver(a)
        HeadWidgetProvider.registerRuntime(a)
        LiveHost.ensure(a)   // battery / power / user-present / Bluetooth / minute-tick receivers
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

    // --- media notifications (weak metadata / art fallback) ------------------------------------

    fun onNotification(ctx: Context, sbn: StatusBarNotification?, removed: Boolean = false) {
        sbn ?: return
        val n = sbn.notification ?: return
        val isMedia = n.category == Notification.CATEGORY_TRANSPORT || n.extras.containsKey("android.mediaSession")
        if (!isMedia) return
        val pkg = sbn.packageName
        val a = ctx.applicationContext
        main.post {
            if (removed) {
                notifs.remove(pkg)
            } else {
                val x = n.extras
                val title = x.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                val text = x.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
                val art = try {
                    n.getLargeIcon()?.loadDrawable(a)?.let { drawableToBitmap(it) }
                        ?: @Suppress("DEPRECATION") x.getParcelable<Bitmap>(Notification.EXTRA_LARGE_ICON_BIG)
                        ?: @Suppress("DEPRECATION") x.getParcelable<Bitmap>(Notification.EXTRA_LARGE_ICON)
                        ?: @Suppress("DEPRECATION") x.getParcelable<Bitmap>(Notification.EXTRA_PICTURE)
                } catch (e: Exception) {
                    Log.w(TAG, "notification art", e)
                    null
                }
                // a new track without a cover must not keep the previous cover
                val old = notifs[pkg]
                notifs[pkg] = NotifInfo(title, text, art ?: old?.art?.takeIf { old.title == title })
            }
            reevaluate()
        }
    }

    private fun drawableToBitmap(d: android.graphics.drawable.Drawable): Bitmap? {
        val w = d.intrinsicWidth.takeIf { it > 0 } ?: 256
        val h = d.intrinsicHeight.takeIf { it > 0 } ?: 256
        val k = minOf(1f, 400f / maxOf(w, h))
        val b = Bitmap.createBitmap((w * k).toInt().coerceAtLeast(1), (h * k).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, b.width, b.height)
        d.draw(Canvas(b))
        return b
    }

    fun notificationSummary(pkg: String): String = notifs[pkg]?.let { "title=${it.title.isNotEmpty()} art=${it.art != null}" } ?: "none"

    // state + metadata callbacks usually arrive in a burst: coalesce them into one render
    private val evaluate = Runnable { evaluateNow() }

    fun reevaluate() {
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
        track = t   // a paused session with metadata stays on the face (frozen bar, play button)
        render(a)
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
    fun pick(list: List<MediaController>): MediaController? =
        list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull { isPlayingState(it.playbackState?.state) }
            ?: list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PAUSED }

    private fun nonBlank(s: String?): String? = s?.takeIf { it.isNotBlank() }

    private fun snapshot(ctx: Context, c: MediaController): Track {
        val md = c.metadata
        val ps = c.playbackState
        val pkg = c.packageName
        val appName = appLabelOrNull(ctx, pkg) ?: ctx.getString(R.string.unknown_title)
        val n = notifs[pkg]
        val title = nonBlank(md?.getString(MediaMetadata.METADATA_KEY_TITLE))
            ?: nonBlank(md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE))
            ?: nonBlank(n?.title)
            ?: appName
        val artist = nonBlank(md?.getString(MediaMetadata.METADATA_KEY_ARTIST))
            ?: nonBlank(md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST))
            ?: nonBlank(md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE))
            ?: nonBlank(n?.text)?.takeIf { it != title }
            ?: ""
        var art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (art == null && md != null) {   // art only as a URI (Spotify): load it off the main thread
            for (key in listOf(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)) {
                val u = ArtLoader.toFetchable(nonBlank(md.getString(key)) ?: continue)
                art = ArtLoader.cached(u)
                if (art != null) break
                ArtLoader.request(ctx, u) { main.post { reevaluate() } }
            }
        }
        if (art == null) art = n?.art   // last resort: the large icon of the media notification
        val duration = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val playing = isPlayingState(ps?.state)
        // the framework has no advertisement flag in MediaMetadata: Spotify marks ads by title / missing length
        val ad = pkg == SPOTIFY && ((title.equals("Advertisement", true) || title.equals("Spotify", true)) && artist.isEmpty())
        return Track(
            pkg = pkg,
            appName = appName,
            title = title,
            artist = artist,
            art = art,
            artKey = artKey(art),
            durationMs = if (duration > 0) duration else 0L,
            playing = playing,
            position = ps?.position ?: -1L,
            updateTime = ps?.lastPositionUpdateTime ?: 0L,
            speed = ps?.playbackSpeed ?: 1f,
            ticking = ps?.state == PlaybackState.STATE_PLAYING,
            sessionActivity = c.sessionActivity,
            isAd = ad,
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
        val face = t?.faceKey ?: listOf("idle", hasAccess(a))
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
            t != null && t.showProgress -> mgr.partiallyUpdateAppWidget(ids, PlayerRenderer.progress(a, t))
        }
        scheduleTick()
    }

    private val tick = Runnable { tickNow() }

    private fun tickNow() {
        val a = app ?: return
        val t = track ?: return
        val ids = playerIds(a)
        if (ids.isEmpty()) return
        if (t.showProgress) AppWidgetManager.getInstance(a).partiallyUpdateAppWidget(ids, PlayerRenderer.progress(a, t))
        scheduleTick()
    }

    /** next tick lands just after the next whole second of the track position */
    private fun scheduleTick() {
        main.removeCallbacks(tick)
        val t = track ?: return
        if (!t.ticking || !screenOn || !t.showProgress) return
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
                    Ticker.tick(context)
                }
            }
        }
    }

    // --- commands from the widget buttons ----------------------------------------------------

    fun command(ctx: Context, action: String) {
        ensureStarted(ctx)
        val c = controller ?: freshPick(ctx)
        val actions = c?.playbackState?.actions ?: 0L
        try {
            when (action) {
                PlayerWidgetProvider.ACTION_PREV ->
                    if (c != null && actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L) c.transportControls.skipToPrevious()
                    else key(ctx, c, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                PlayerWidgetProvider.ACTION_NEXT ->
                    if (c != null && actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L) c.transportControls.skipToNext()
                    else key(ctx, c, KeyEvent.KEYCODE_MEDIA_NEXT)
                PlayerWidgetProvider.ACTION_PLAY_PAUSE -> when {
                    c == null -> key(ctx, null, KeyEvent.KEYCODE_MEDIA_PLAY)
                    isPlayingState(c.playbackState?.state) ->
                        if (actions and (PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE) != 0L) c.transportControls.pause()
                        else key(ctx, c, KeyEvent.KEYCODE_MEDIA_PAUSE)
                    else ->
                        if (actions and (PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE) != 0L) c.transportControls.play()
                        else key(ctx, c, KeyEvent.KEYCODE_MEDIA_PLAY)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "command $action failed, trying the media key", e)
            key(
                ctx, c,
                when (action) {
                    PlayerWidgetProvider.ACTION_PREV -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    PlayerWidgetProvider.ACTION_NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
                    else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                },
            )
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

    /** media key to the session itself when it has no transport action, else (no session) to the system */
    private fun key(ctx: Context, c: MediaController?, code: Int) {
        val down = KeyEvent(KeyEvent.ACTION_DOWN, code)
        val up = KeyEvent(KeyEvent.ACTION_UP, code)
        if (c != null) {
            try {
                if (c.dispatchMediaButtonEvent(down) && c.dispatchMediaButtonEvent(up)) return
            } catch (e: Exception) {
                Log.w(TAG, "session media key", e)
            }
        }
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        am.dispatchMediaKeyEvent(down)
        am.dispatchMediaKeyEvent(up)
    }
}

