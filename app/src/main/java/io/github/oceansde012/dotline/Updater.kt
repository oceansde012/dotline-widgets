package io.github.oceansde012.dotline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-app updater (release builds only, [BuildConfig.UPDATES_ENABLED]). It looks at the latest GitHub release
 * of the project's GitHub releases, downloads `dotline-widgets.apk`, checks that it is this app (package name and
 * signing certificate) and installs it through a PackageInstaller session. The system install dialog is
 * always shown: the user confirms every update, there are no silent installs.
 *
 * The only thing sent over the network is a plain GET with a generic User-Agent. No device ids.
 */
object Updater {
    private const val TAG = "NdUpdate"
    private const val API = "https://api.github.com/repos/oceansde012/dotline-widgets/releases/latest"
    private const val ASSET = "dotline-widgets.apk"
    private const val FILE = "nd_update"
    private const val CHANNEL = "updates"
    private const val NOTIF_ID = 4102
    const val ACTION_STATUS = BuildConfig.APPLICATION_ID + ".UPDATE_STATUS"
    const val MIN_CHECK_GAP_MS = 60_000L

    enum class State { IDLE, CHECKING, DOWNLOADING, READY, INSTALLING, UP_TO_DATE, ERROR }

    @Volatile var state = State.IDLE
        private set
    @Volatile var progress = 0
        private set
    /** set by the activity while it is visible: decides between launching the install dialog and a notification */
    @Volatile var foreground = false
    @Volatile var onChange: (() -> Unit)? = null

    private val busy = AtomicBoolean(false)

    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // --- persisted facts ---------------------------------------------------------------------

    fun lastCheck(ctx: Context): Long = p(ctx).getLong("last_check", 0L)
    fun readyCode(ctx: Context): Long = p(ctx).getLong("ready_code", 0L)
    fun lastResult(ctx: Context): String = p(ctx).getString("result", "") ?: ""
    fun lastError(ctx: Context): String = p(ctx).getString("error", "") ?: ""

    fun installedCode(ctx: Context): Long = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode
    } catch (e: Exception) {
        0L
    }

    private fun apkFile(ctx: Context, code: Long) = File(ctx.cacheDir, "update-$code.apk")

    /** a downloaded, verified APK that is newer than the installed app, else 0 */
    fun readyUpdate(ctx: Context): Long {
        val code = readyCode(ctx)
        return if (code > installedCode(ctx) && apkFile(ctx, code).isFile) code else 0L
    }

    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    /** brings the in-memory state in line with what is on disk (new process, or the app was just updated) */
    fun syncState(ctx: Context) {
        if (state == State.CHECKING || state == State.DOWNLOADING || state == State.INSTALLING) return
        state = when {
            readyUpdate(ctx) > 0 -> State.READY
            lastError(ctx).isNotEmpty() && state == State.ERROR -> State.ERROR
            lastCheck(ctx) > 0 -> State.UP_TO_DATE
            else -> State.IDLE
        }
    }

    private fun set(s: State, pct: Int = 0) {
        state = s
        progress = pct
        onChange?.invoke()
    }

    // --- check + download --------------------------------------------------------------------

    /** true when a check is worth starting now (rate limit for the activity trigger) */
    fun checkDue(ctx: Context): Boolean =
        BuildConfig.UPDATES_ENABLED && System.currentTimeMillis() - lastCheck(ctx) > MIN_CHECK_GAP_MS

    fun checkAsync(ctx: Context, onDone: (() -> Unit)? = null) {
        val a = ctx.applicationContext
        if (!BuildConfig.UPDATES_ENABLED || !busy.compareAndSet(false, true)) {
            onDone?.invoke()
            return
        }
        Thread {
            try {
                run(a)
            } catch (e: Exception) {
                Log.w(TAG, "check failed", e)
                p(a).edit().putString("error", e.message ?: e.javaClass.simpleName).putLong("last_check", System.currentTimeMillis()).apply()
                if (readyUpdate(a) > 0) set(State.READY) else set(State.ERROR)
            } finally {
                busy.set(false)
                onDone?.invoke()
            }
        }.start()
    }

    private fun run(a: Context) {
        set(State.CHECKING)
        val rel = JSONObject(httpGet(API))
        val tag = rel.optString("tag_name")
        val remote = Regex("""v(\d+)""").find(tag)?.groupValues?.get(1)?.toLongOrNull()
            ?: throw IllegalStateException("bad tag")
        val installed = installedCode(a)
        val sp = p(a)
        sp.edit().putLong("last_check", System.currentTimeMillis()).putString("error", "").apply()
        if (remote <= installed) {
            cleanCache(a, keep = 0L)
            sp.edit().putLong("ready_code", 0L).putString("result", "").apply()
            set(State.UP_TO_DATE)
            return
        }
        if (readyCode(a) == remote && apkFile(a, remote).isFile) {
            set(State.READY)
            return
        }
        var url: String? = null
        val assets = rel.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val o = assets.getJSONObject(i)
                if (o.optString("name") == ASSET) url = o.optString("browser_download_url")
            }
        }
        if (url.isNullOrEmpty()) throw IllegalStateException("no $ASSET in release")
        cleanCache(a, keep = 0L)
        sp.edit().putLong("ready_code", 0L).apply()
        download(a, url, remote)
        val err = verify(a, apkFile(a, remote), remote)
        if (err != null) {
            apkFile(a, remote).delete()
            throw IllegalStateException(err)
        }
        sp.edit().putLong("ready_code", remote).putString("result", "").apply()
        set(State.READY)
        if (!foreground) notifyReady(a, remote)
    }

    private fun conn(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", "DotlineWidgets/" + BuildConfig.VERSION_NAME)
        setRequestProperty("Accept", "application/vnd.github+json")
    }

    private fun httpGet(url: String): String {
        val c = conn(url)
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun download(a: Context, url: String, code: Long) {
        set(State.DOWNLOADING, 0)
        val part = File(a.cacheDir, "update-$code.apk.part")
        val c = conn(url)
        c.setRequestProperty("Accept", "application/octet-stream")
        try {
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            var done = 0L
            var last = -1
            c.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else 0
                        if (pct != last) {
                            last = pct
                            set(State.DOWNLOADING, pct)
                        }
                    }
                }
            }
            if (total > 0 && done != total) throw IllegalStateException("incomplete download")
        } finally {
            c.disconnect()
        }
        val dst = apkFile(a, code)
        dst.delete()
        if (!part.renameTo(dst)) throw IllegalStateException("cannot store the download")
    }

    private fun cleanCache(a: Context, keep: Long) {
        a.cacheDir.listFiles()?.forEach {
            if (it.name.startsWith("update-") && it.name != "update-$keep.apk") it.delete()
        }
    }

    /** null = the APK is this app, newer, and signed with the same certificate as the installed one */
    private fun verify(a: Context, f: File, expectCode: Long): String? {
        val pm = a.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val arch = pm.getPackageArchiveInfo(f.path, flags) ?: return "unreadable APK"
        if (arch.packageName != a.packageName) return "wrong package"
        if (arch.longVersionCode != expectCode) return "version mismatch"
        val cur = pm.getPackageInfo(a.packageName, flags)
        val mine = certs(cur)
        val theirs = certs(arch)
        if (mine.isEmpty() || theirs.isEmpty() || mine != theirs) return "signature mismatch"
        return null
    }

    private fun certs(pi: android.content.pm.PackageInfo): Set<String> {
        val si = pi.signingInfo ?: return emptySet()
        val sigs = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        val md = MessageDigest.getInstance("SHA-256")
        return sigs.orEmpty().map { s -> md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    // --- install -----------------------------------------------------------------------------

    /** commits the ready APK; the status receiver then shows the system confirmation */
    fun install(ctx: Context) {
        val a = ctx.applicationContext
        val code = readyUpdate(a)
        if (code == 0L || state == State.INSTALLING) return
        if (!canInstall(a)) {
            p(a).edit().putString("error", "install permission").apply()
            set(State.ERROR)
            return
        }
        set(State.INSTALLING)
        Thread {
            try {
                val f = apkFile(a, code)
                val pi = a.packageManager.packageInstaller
                pi.mySessions.forEach { try { pi.abandonSession(it.sessionId) } catch (_: Exception) {} }
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(a.packageName)
                params.setSize(f.length())
                val id = pi.createSession(params)
                pi.openSession(id).use { s ->
                    s.openWrite("base.apk", 0, f.length()).use { out ->
                        f.inputStream().use { it.copyTo(out) }
                        s.fsync(out)
                    }
                    val i = Intent(a, UpdateStatusReceiver::class.java).setAction(ACTION_STATUS)
                    // the installer adds its status extras, so the PendingIntent has to be mutable
                    val pend = PendingIntent.getBroadcast(a, id, i, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    s.commit(pend.intentSender)
                }
            } catch (e: Exception) {
                Log.w(TAG, "install failed", e)
                p(a).edit().putString("error", e.message ?: e.javaClass.simpleName).apply()
                set(State.ERROR)
            }
        }.start()
    }

    /** from [UpdateStatusReceiver] */
    fun onPendingUserAction(ctx: Context, confirm: Intent?) {
        if (confirm == null) return
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (foreground) {
            try {
                ctx.startActivity(confirm)
                return
            } catch (e: Exception) {
                Log.w(TAG, "cannot open the install dialog", e)
            }
        }
        // in the background: a tap on the notification opens the app, which launches the dialog
        val open = Intent(ctx, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_CONFIRM, confirm)
        notify(ctx, Lang.wrap(ctx).getString(R.string.upd_notif_title, versionOf(readyCode(ctx))), open)
    }

    fun onInstallResult(ctx: Context, status: Int, message: String?) {
        val sp = p(ctx)
        if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
            // the user dismissed the dialog: the download stays, INSTALL works again
            sp.edit().putString("result", "cancelled").apply()
            set(if (readyUpdate(ctx) > 0) State.READY else State.UP_TO_DATE)
        } else {
            sp.edit().putString("error", message ?: "install failed ($status)").apply()
            set(State.ERROR)
        }
    }

    // --- notification ------------------------------------------------------------------------

    fun versionOf(code: Long) = "1.0.$code"

    private fun notifyReady(ctx: Context, code: Long) {
        val open = Intent(ctx, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_INSTALL, true)
        notify(ctx, Lang.wrap(ctx).getString(R.string.upd_notif_title, versionOf(code)), open)
    }

    private fun notify(base: Context, title: String, open: Intent) {
        val ctx = Lang.wrap(base)
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (!nm.areNotificationsEnabled()) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.upd_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val pi = PendingIntent.getActivity(ctx, NOTIF_ID, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(ctx.getString(R.string.upd_notif_text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID, n)
    }

    fun cancelNotification(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
    }

    /** after the app was replaced: the old download is installed, drop leftovers */
    fun onReplaced(ctx: Context) {
        cleanCache(ctx, keep = 0L)
        p(ctx).edit().putLong("ready_code", 0L).putString("error", "").putString("result", "").apply()
        cancelNotification(ctx)
    }
}
