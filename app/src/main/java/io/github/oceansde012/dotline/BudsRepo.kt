package io.github.oceansde012.dotline

import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** What the buds widget shows. Battery values are percent, -1 = unknown. */
class Buds(
    val permission: Boolean,
    val connected: Boolean,
    val name: String,
    val left: Int,
    val right: Int,
    val case: Int,
    /** single level (HFP / getBatteryLevel / broadcast) when the earbuds do not report L / R */
    val single: Int,
    val address: String = "",
    /** upper-case model line for the label, e.g. `NOTHING EAR (3)` */
    val model: String = "",
    /** package of the companion app (Nothing X, Galaxy Wearable ...), if installed */
    val companion: String? = null,
    /** where the winning battery value came from, for the diagnostics card */
    val source: String = "none",
    /** when the battery values were read (ms), 0 = unknown */
    val at: Long = 0L,
) {
    val hasSides get() = left >= 0 || right >= 0
    val lowest: Int get() = listOf(left, right, single).filter { it >= 0 }.minOrNull() ?: -1
    val key get() = listOf(permission, connected, name, left, right, case, single, address, companion ?: "")
    /** same device, battery values dropped: used when the reading is too old to be shown as current */
    fun withoutBattery() = Buds(permission, connected, name, -1, -1, -1, -1, address, model, companion, "stale", at)
    val title: String get() = model.ifBlank { name.uppercase(Locale.getDefault()) }

    companion object {
        fun noPermission() = Buds(false, false, "", -1, -1, -1, -1)
        fun disconnected(last: Buds? = null) =
            Buds(true, false, last?.name ?: "", -1, -1, -1, -1, last?.address ?: "", last?.model ?: "", last?.companion)
    }
}

/** the earbud picture and where it came from (USER / NOTHING_X / METADATA / ILLUSTRATION) */
class BudsPic(val bmp: Bitmap?, val source: String, val tag: String)

/**
 * Connected audio device + earbud battery + earbud picture (DESIGN_SPEC.md 2.6).
 *
 * Battery order: untethered metadata (left / right / case) via reflection, BluetoothDevice.getBatteryLevel()
 * via reflection, the BATTERY_LEVEL_CHANGED broadcast, else connected-only. These are hidden / system APIs:
 * every call is guarded and a failure just means "unknown" (the diagnostics card says `blocked`).
 *
 * Picture order: user-chosen picture (explicit choice wins) -> image read from the installed Nothing X app ->
 * the device icon URIs of Android's Bluetooth metadata -> our own illustration ([BudsArt]); the companion app's
 * icon is shown as a small badge. Everything is cached on disk (filesDir/buds, <= 256 px) and resolved off the
 * main thread; nothing is bundled.
 */
object BudsRepo {
    private const val TAG = "NdBuds"
    private const val PREFS = "nd_buds"
    private const val K_LAST = "last"
    private const val K_DEV = "last_dev"
    private const val MAX_PIC = 256

    /** BluetoothDevice.ACTION_BATTERY_LEVEL_CHANGED is hidden API (a protected broadcast, stable string) */
    const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
    private const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"

    // BluetoothDevice.METADATA_* (verified against AOSP BluetoothDevice.java)
    private const val META_MANUFACTURER = 0
    private const val META_MODEL_NAME = 1
    private const val META_COMPANION_APP = 4
    private const val META_MAIN_ICON = 5
    private const val META_LEFT_ICON = 7
    private const val META_RIGHT_ICON = 8
    private const val META_LEFT_BATTERY = 10
    private const val META_RIGHT_BATTERY = 11
    private const val META_CASE_BATTERY = 12
    private const val META_MAIN_BATTERY = 18
    /** while earbuds are connected the battery is read again this often (in-process; the 15 min job covers a dead process) */
    private const val POLL_MS = 5 * 60_000L
    private const val FRESH_MS = 10 * 60_000L

    private val NOTHING_X = listOf("com.nothing.smartcenter", "com.nothing.hearthstone", "com.nothing.ear", "com.nothing.nothingx", "com.nothingtech.ear")
    private const val GALAXY_WEARABLE = "com.samsung.android.app.watchmanager"

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()        // picture resolving (slow)
    private val ioBattery = Executors.newSingleThreadExecutor() // Bluetooth reads (binder calls, never on main)
    @Volatile private var last: Buds? = null
    @Volatile private var refreshing = false
    private class Level(val percent: Int, val at: Long)
    private val broadcastLevel = ConcurrentHashMap<String, Level>()
    private val tried = HashSet<String>()

    // --- permission / state ----------------------------------------------------------------------

    fun hasPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** last known state for an immediate render; never blocks */
    fun current(ctx: Context): Buds {
        if (!hasPermission(ctx)) return Buds.noPermission()
        last?.let { return fresh(it) }
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val dev = sp.getString(K_DEV, null)?.split('|')
        val known = if (dev != null && dev.size >= 3) Buds(true, false, dev[0], -1, -1, -1, -1, dev[1], dev[2], dev.getOrNull(3)?.ifBlank { null }) else null
        val s = sp.getString(K_LAST, null)
        val parsed = s?.split('|')?.takeIf { it.size == 6 && System.currentTimeMillis() - (it[5].toLongOrNull() ?: 0L) < FRESH_MS }?.let {
            try {
                Buds(true, true, it[0], it[1].toInt(), it[2].toInt(), it[3].toInt(), it[4].toInt(), known?.address ?: "", known?.model ?: "", known?.companion, "saved", it[5].toLong())
            } catch (e: Exception) {
                null
            }
        }
        // prefs hold only a connected state; "no entry" renders as disconnected until the first refresh
        return parsed ?: Buds.disconnected(known)
    }

    /** a battery reading older than [FRESH_MS] is not shown as current */
    private fun fresh(b: Buds): Buds =
        if (b.connected && (b.hasSides || b.single >= 0 || b.case >= 0) && System.currentTimeMillis() - b.at > FRESH_MS) b.withoutBattery() else b

    private fun store(ctx: Context, b: Buds) {
        last = b
        val v = if (b.connected) "${b.name.replace('|', ' ')}|${b.left}|${b.right}|${b.case}|${b.single}|${System.currentTimeMillis()}" else null
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (v == null) remove(K_LAST) else putString(K_LAST, v)
            if (b.connected && b.address.isNotEmpty()) {
                putString(K_DEV, "${b.name.replace('|', ' ')}|${b.address}|${b.model.replace('|', ' ')}|${b.companion ?: ""}")
            }
        }.apply()
    }

    fun onBatteryBroadcast(i: Intent) {
        val d = try {
            @Suppress("DEPRECATION") i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        } catch (e: Exception) {
            null
        } ?: return
        val level = i.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
        val address = try { d.address } catch (e: Exception) { return }
        if (level in 0..100) broadcastLevel[address] = Level(level, System.currentTimeMillis())
    }

    fun forgetTried() = synchronized(tried) { tried.clear() }

    // --- refresh -----------------------------------------------------------------------------------

    /**
     * Asks the A2DP and HEADSET profile proxies (and the bonded list) for connected devices, reads their batteries
     * and re-renders the widgets. Async; cheap when no widget is placed unless [force] (the app screen).
     */
    fun refreshAsync(ctx: Context, force: Boolean = false) {
        val a = ctx.applicationContext
        if (!force && !WeatherJob.hasWidgets(a, BudsWidgetProvider::class.java)) return
        if (!hasPermission(a)) {
            last = null
            BudsWidgetProvider.updateAll(a)
            return
        }
        main.post { start(a) }
    }

    private fun start(a: Context) {
        if (refreshing) return
        val adapter = try {
            a.getSystemService(BluetoothManager::class.java)?.adapter
        } catch (e: Exception) {
            null
        }
        if (adapter == null || !adapter.isEnabled) {
            publish(a, Buds.disconnected(last))
            return
        }
        refreshing = true
        val devices = LinkedHashMap<String, BluetoothDevice>()
        val profiles = intArrayOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)
        var pending = profiles.size
        var finished = false
        fun finish() {
            if (finished) return
            finished = true
            main.removeCallbacksAndMessages(TIMEOUT_TOKEN)
            val found = devices.values.toList()
            ioBattery.execute {   // binder calls (metadata, bonded list) never on the main thread
                val b = try {
                    // proxies first; the bonded list with isConnected() is the second way to find the device
                    read(a, if (found.isNotEmpty()) found else bondedConnected(adapter))
                } catch (e: Throwable) {
                    Log.w(TAG, "read failed", e)
                    Buds.disconnected(last)
                }
                publish(a, b)
            }
        }
        fun done() {
            pending--
            if (pending <= 0) finish()
        }
        main.postAtTime({ finish() }, TIMEOUT_TOKEN, android.os.SystemClock.uptimeMillis() + 4000)
        for (profile in profiles) {
            val ok = try {
                adapter.getProfileProxy(a, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                        main.post {   // binder thread: collect on main
                            try {
                                for (d in proxy.connectedDevices) devices[d.address] = d
                            } catch (e: Exception) {
                                Log.w(TAG, "connectedDevices", e)
                            }
                            try {
                                adapter.closeProfileProxy(p, proxy)
                            } catch (e: Exception) {
                                // already closed
                            }
                            done()
                        }
                    }

                    override fun onServiceDisconnected(p: Int) = Unit
                }, profile)
            } catch (e: Exception) {
                false
            }
            if (!ok) done()
        }
    }

    private val TIMEOUT_TOKEN = Any()
    private val POLL_TOKEN = Any()

    private fun publish(a: Context, b: Buds) {
        refreshing = false
        store(a, b)
        BudsWidgetProvider.updateAll(a)
        resolvePictureAsync(a, b)
        // keep reading while earbuds are connected, so a reading that goes stale is replaced (or dropped) in time
        main.removeCallbacksAndMessages(POLL_TOKEN)
        if (b.connected) main.postAtTime({ refreshAsync(a) }, POLL_TOKEN, android.os.SystemClock.uptimeMillis() + POLL_MS)
    }

    @Suppress("MissingPermission")
    private fun bondedConnected(adapter: BluetoothAdapter): List<BluetoothDevice> = try {
        adapter.bondedDevices.orEmpty().filter { isConnected(it) }
    } catch (e: Exception) {   // SecurityException without BLUETOOTH_CONNECT
        emptyList()
    }

    private fun isConnected(d: BluetoothDevice): Boolean = try {
        BluetoothDevice::class.java.getMethod("isConnected").invoke(d) as? Boolean ?: false
    } catch (t: Throwable) {
        false
    }

    /**
     * Only audio headphones / earbuds count. Watches (Major.WEARABLE), phones, computers and car kits never do,
     * even when connected over A2DP / HFP. A device without a Bluetooth class counts only if it reports earbud batteries.
     */
    fun isHeadphones(d: BluetoothDevice): Boolean {
        val cls = try { d.bluetoothClass } catch (e: Exception) { null }
        if (cls == null) return metaBattery(d, META_LEFT_BATTERY) >= 0 || metaBattery(d, META_RIGHT_BATTERY) >= 0
        if (cls.majorDeviceClass != BluetoothClass.Device.Major.AUDIO_VIDEO) return false
        return when (cls.deviceClass) {
            BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES, BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET -> true
            BluetoothClass.Device.AUDIO_VIDEO_UNCATEGORIZED ->
                metaBattery(d, META_LEFT_BATTERY) >= 0 || metaBattery(d, META_RIGHT_BATTERY) >= 0 ||
                    metaBattery(d, META_MAIN_BATTERY) >= 0 || deviceBattery(d) >= 0
            else -> false   // hands-free car kits, car audio, loudspeaker, hi-fi, portable audio, set-top box, video ...
        }
    }

    /**
     * The real Bluetooth name of the device. The user-chosen alias is never shown (it is cut off in the widget and
     * may carry the owner's name); it is only a fallback, without a prefix up to the first ": ".
     */
    private fun displayName(d: BluetoothDevice): String = try {
        d.name?.takeIf { it.isNotBlank() }
            ?: d.alias?.let { if (": " in it) it.substringAfter(": ") else it }.orEmpty()
    } catch (e: Exception) {   // SecurityException without BLUETOOTH_CONNECT
        ""
    }

    /** picks the device that reports batteries (earbuds) over e.g. a car kit, and reads them */
    private fun read(a: Context, devices: List<BluetoothDevice>): Buds {
        val audio = devices.filter { isHeadphones(it) }
        if (audio.isEmpty()) return Buds.disconnected(last)
        var best: Buds? = null
        for (d in audio) {
            val name = displayName(d)
            val address = try { d.address } catch (e: Exception) { continue }
            val l = metaBattery(d, META_LEFT_BATTERY)
            val r = metaBattery(d, META_RIGHT_BATTERY)
            val c = metaBattery(d, META_CASE_BATTERY)
            var single = -1
            var source = if (l >= 0 || r >= 0) "METADATA L/R" else "none"
            if (l < 0 && r < 0) {
                single = metaBattery(d, META_MAIN_BATTERY)
                if (single >= 0) source = "METADATA main"
                else {
                    single = deviceBattery(d)
                    if (single >= 0) source = "getBatteryLevel"
                    else broadcastLevel[address]?.takeIf { System.currentTimeMillis() - it.at < FRESH_MS }
                        ?.let { single = it.percent; source = "BATTERY_LEVEL_CHANGED broadcast" }
                }
            }
            val modelRaw = metaString(d, META_MODEL_NAME) ?: name
            val b = Buds(true, true, name, l, r, c, single, address, prettyModel(modelRaw), companionFor(a, d, modelRaw), source, System.currentTimeMillis())
            if (best == null || score(b) > score(best)) best = b
        }
        return best ?: Buds.disconnected(last)
    }

    private fun score(b: Buds) = (if (b.hasSides) 2 else 0) + (if (b.single >= 0) 1 else 0)

    /** `Ear (3)` -> `NOTHING EAR (3)`; other models are shown as reported */
    fun prettyModel(raw: String): String {
        val s = raw.trim()
        val l = s.lowercase(Locale.ROOT)
        val full = if (l.startsWith("ear (") || l.startsWith("ear(")) "Nothing $s" else s
        return full.uppercase(Locale.getDefault())
    }

    // --- Bluetooth metadata (hidden @SystemApi, reflection) -----------------------------------------

    @Volatile var metadataBlocked: String? = null
        private set

    private val getMetadataMethod by lazy {
        try {
            BluetoothDevice::class.java.getMethod("getMetadata", Int::class.javaPrimitiveType)
        } catch (t: Throwable) {
            metadataBlocked = t.javaClass.simpleName
            null
        }
    }

    private fun meta(d: BluetoothDevice, key: Int): ByteArray? = try {
        getMetadataMethod?.invoke(d, key) as? ByteArray
    } catch (t: Throwable) {
        metadataBlocked = (t.cause ?: t).javaClass.simpleName
        null
    }

    private fun metaString(d: BluetoothDevice, key: Int): String? =
        meta(d, key)?.let { String(it, Charsets.UTF_8).trim() }?.takeIf { it.isNotEmpty() }

    /** the percent as ASCII text; -1 when unavailable */
    private fun metaBattery(d: BluetoothDevice, key: Int): Int {
        val v = metaString(d, key)?.toIntOrNull() ?: -1
        return if (v in 0..100) v else -1
    }

    /** BluetoothDevice.getBatteryLevel(): hidden; BATTERY_LEVEL_UNKNOWN = -1 */
    private fun deviceBattery(d: BluetoothDevice): Int = try {
        val m = BluetoothDevice::class.java.getMethod("getBatteryLevel")
        val v = m.invoke(d) as? Int ?: -1
        if (v in 0..100) v else -1
    } catch (t: Throwable) {
        -1
    }

    // --- companion app -------------------------------------------------------------------------------

    fun nothingXPackages(a: Context): List<String> {
        val pm = a.packageManager
        val out = LinkedHashSet<String>()
        for (p in NOTHING_X) if (installed(pm, p)) out.add(p)
        try {
            for (ai in pm.getInstalledApplications(0)) {
                val p = ai.packageName
                if (p.startsWith("com.nothingtech.") || p.startsWith("com.nothing.") &&
                    (p.contains("smartcenter") || p.contains("hearthstone") || p.contains("ear") || p.contains("nothingx"))
                ) out.add(p)
                else if (pm.getApplicationLabel(ai).toString().equals("Nothing X", true)) out.add(p)
            }
        } catch (e: Exception) {
            Log.w(TAG, "scan packages", e)
        }
        return out.toList()
    }

    private fun installed(pm: PackageManager, pkg: String) = try {
        pm.getApplicationInfo(pkg, 0); true
    } catch (e: Exception) {
        false
    }

    private fun isNothing(model: String): Boolean {
        val l = model.lowercase(Locale.ROOT)
        return "nothing" in l || l.startsWith("ear (") || l.startsWith("ear(") || "cmf" in l
    }

    private fun companionFor(a: Context, d: BluetoothDevice, modelRaw: String): String? {
        val pm = a.packageManager
        metaString(d, META_COMPANION_APP)?.let { if (installed(pm, it)) return it }
        if (isNothing(modelRaw)) nothingXPackages(a).firstOrNull()?.let { return it }
        if ("galaxy buds" in modelRaw.lowercase(Locale.ROOT) && installed(pm, GALAXY_WEARABLE)) return GALAXY_WEARABLE
        return null
    }

    // --- pictures ------------------------------------------------------------------------------------

    private fun dir(a: Context) = File(a.filesDir, "buds").also { it.mkdirs() }

    private fun hash(address: String): String {
        val md = MessageDigest.getInstance("SHA-256").digest(address.toByteArray())
        return md.take(5).joinToString("") { "%02x".format(it) }
    }

    private fun load(f: File): Bitmap? = if (f.exists()) try { BitmapFactory.decodeFile(f.path) } catch (e: Exception) { null } else null

    private fun save(f: File, src: Bitmap, maxSide: Int = MAX_PIC) {
        val k = minOf(1f, maxSide.toFloat() / maxOf(src.width, src.height))
        val b = if (k < 1f) Bitmap.createScaledBitmap(src, (src.width * k).toInt().coerceAtLeast(1), (src.height * k).toInt().coerceAtLeast(1), true) else src
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun srcPref(a: Context) = a.getSharedPreferences("nd_buds_pic", Context.MODE_PRIVATE)

    /** disk read of the already resolved picture; never blocks on the network */
    fun picture(a: Context, b: Buds): BudsPic {
        if (b.address.isEmpty()) return BudsPic(null, "ILLUSTRATION", "none")
        val h = hash(b.address)
        load(File(dir(a), "${h}_user.png"))?.let { return BudsPic(it, "USER", "user$h") }
        load(File(dir(a), "${h}_$AUTO_FILE"))?.let { return BudsPic(it, srcPref(a).getString("src_$h", "AUTO") ?: "AUTO", "auto$h") }
        return BudsPic(null, "ILLUSTRATION", "none")
    }

    fun hasUserPicture(a: Context, b: Buds) = b.address.isNotEmpty() && File(dir(a), "${hash(b.address)}_user.png").exists()

    fun setUserPicture(a: Context, b: Buds, src: Bitmap) {
        if (b.address.isEmpty()) return
        save(File(dir(a), "${hash(b.address)}_user.png"), src)
        BudsWidgetProvider.updateAll(a)
    }

    fun resetUserPicture(a: Context, b: Buds) {
        if (b.address.isEmpty()) return
        File(dir(a), "${hash(b.address)}_user.png").delete()
        BudsWidgetProvider.updateAll(a)
    }

    /** latest resolver notes, shown in the diagnostics card */
    @Volatile var resolveNotes: String = "not run yet"
        private set

    private fun resolvePictureAsync(a: Context, b: Buds) {
        if (!b.connected || b.address.isEmpty()) return
        val h = hash(b.address)
        synchronized(tried) { if (!tried.add(h)) return }
        io.execute {
            try {
                resolve(a, b, h)
            } catch (e: Throwable) {
                resolveNotes = "error: ${e.javaClass.simpleName}"
                Log.w(TAG, "picture resolve failed", e)
            }
        }
    }

    private fun resolve(a: Context, b: Buds, h: String) {
        val notes = StringBuilder()
        val auto = File(dir(a), "${h}_$AUTO_FILE")
        File(dir(a), "${h}_auto.png").delete()   // v1 cache: Nothing X pictures could be saved upscaled
        // 0 Nothing X resources
        val model = b.model.ifBlank { b.name }
        var src: String? = null
        val prefix = nothingXPrefix(model)
        val pair = if (prefix != null) {
            try { nothingXPair(a, prefix) } catch (e: Throwable) { null }
        } else null
        val scan = if (pair == null) scanNothingX(a, model) else NxScan(nothingXPackages(a), emptyList(), null)
        if (pair != null) {
            notes.append("NothingX pkgs=${scan.packages.size} table=$prefix picked=${pair.second}")
            save(auto, pair.first, MAX_PAIR); src = "NOTHING_X"
        } else {
            notes.append("NothingX pkgs=${scan.packages.size} ")
            if (prefix != null) notes.append("table=$prefix picked=none") else notes.append("candidates=${scan.all.size} picked=${scan.picked?.name ?: "none"}")
        }
        scan.picked?.takeIf { src == null }?.let { c ->
            decodeCand(a, c, MAX_PIC)?.let { save(auto, it); src = "NOTHING_X"; notes.append(" -> ${c.type}/${c.name} ${c.w}x${c.h}") }
        }
        // 1 Android metadata icons
        if (src == null) {
            val bd = try {
                a.getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(b.address)
            } catch (e: Exception) {
                null
            }
            if (bd != null) {
                val mainUri = metaString(bd, META_MAIN_ICON)
                val lUri = metaString(bd, META_LEFT_ICON)
                val rUri = metaString(bd, META_RIGHT_ICON)
                notes.append(" | metadata: ").append(if (getMetadataMethod == null) "blocked ($metadataBlocked)" else "main=${scheme(mainUri)} left=${scheme(lUri)} right=${scheme(rUri)}")
                val pic = try {
                    when {
                        mainUri != null -> ArtLoader.decode(a, mainUri)
                        lUri != null && rUri != null -> sideBySide(ArtLoader.decode(a, lUri), ArtLoader.decode(a, rUri))
                        else -> null
                    }
                } catch (e: Exception) {
                    notes.append(" load failed: ${e.javaClass.simpleName}")
                    null
                }
                if (pic != null) {
                    save(auto, pic); src = if (mainUri != null) "METADATA_MAIN_ICON" else "METADATA_L+R_ICONS"
                }
            }
        }
        val sp = srcPref(a).edit()
        if (src != null) sp.putString("src_$h", src) else { sp.remove("src_$h"); auto.delete() }
        sp.apply()
        notes.append(" | result=${src ?: "ILLUSTRATION"}")
        resolveNotes = notes.toString()
        if (src != null) BudsWidgetProvider.updateAll(a)
    }

    private fun scheme(u: String?) = if (u == null) "none" else (android.net.Uri.parse(u).scheme ?: "?")

    // Nothing X names its images by code name: Bluetooth name (lower case) -> drawable prefix
    private val NX_PREFIX = mapOf(
        "nothing ear (1)" to "ear_one",
        "nothing ear (2)" to "ear_two",
        "nothing ear (stick)" to "ear_stick",
        "nothing ear (a)" to "ear_color",
        "nothing ear" to "ear_three",
    )
    private const val MAX_PAIR = 400
    /** resolved picture cache; bump the suffix when the resolver output changes so old files are not reused */
    private const val AUTO_FILE = "auto2.png"

    private fun nothingXPrefix(model: String): String? = NX_PREFIX[model.trim().lowercase(Locale.ROOT)]

    private fun nxBitmap(res: android.content.res.Resources, name: String, pkg: String): Bitmap? {
        val id = res.getIdentifier(name, "drawable", pkg)
        if (id == 0) return null
        val o = BitmapFactory.Options().apply { inScaled = false }
        // Nothing X keeps these in res/drawable (160 dpi); without DENSITY_NONE drawBitmap would upscale them 3x
        BitmapFactory.decodeResource(res, id, o)?.let { it.density = Bitmap.DENSITY_NONE; return it }
        val d = res.getDrawable(id, null) ?: return null
        val w = d.intrinsicWidth.takeIf { it > 0 } ?: return null
        val h = d.intrinsicHeight.takeIf { it > 0 } ?: return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, w, h)
        d.draw(Canvas(bmp))
        bmp.density = Bitmap.DENSITY_NONE
        return bmp
    }

    /** left + right earbud of [prefix] read from the installed Nothing X app (black, else white, else no colour); null if absent */
    private fun nothingXPair(a: Context, prefix: String): Pair<Bitmap, String>? {
        for (p in nothingXPackages(a)) {
            val res = try { a.packageManager.getResourcesForApplication(p) } catch (e: Exception) { continue }
            for (color in listOf("_black", "_white", "")) {
                val base = prefix + color
                val l = try { nxBitmap(res, base + "_left", p) } catch (e: Exception) { null }
                val r = try { nxBitmap(res, base + "_right", p) } catch (e: Exception) { null }
                if (l == null || r == null) continue
                val pair = overlap(l, r) ?: continue
                return pair to base
            }
        }
        return null
    }

    /** left and right side by side on a transparent background, the right one slightly over the left; longest side <= [MAX_PAIR] */
    private fun overlap(l: Bitmap, r: Bitmap): Bitmap? {
        val h = maxOf(l.height, r.height)
        val shift = (l.width * 0.85f).toInt()
        val w = shift + r.width
        val k = minOf(1f, MAX_PAIR.toFloat() / maxOf(w, h))
        val out = Bitmap.createBitmap((w * k).toInt().coerceAtLeast(1), (h * k).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.scale(k, k)
        c.drawBitmap(l, 0f, (h - l.height) / 2f, null)
        c.drawBitmap(r, shift.toFloat(), (h - r.height) / 2f, null)
        return out
    }

    private fun sideBySide(l: Bitmap?, r: Bitmap?): Bitmap? {
        if (l == null || r == null) return l ?: r
        val h = maxOf(l.height, r.height)
        val out = Bitmap.createBitmap(l.width + r.width, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(l, 0f, (h - l.height) / 2f, null)
        c.drawBitmap(r, l.width.toFloat(), (h - r.height) / 2f, null)
        return out
    }

    // --- Nothing X: pictures read from the installed app's own resources -------------------------------

    class Cand(val pkg: String, val id: Int, val type: String, val name: String, val w: Int, val h: Int, val score: Int)
    class NxScan(val packages: List<String>, val all: List<Cand>, val picked: Cand?)

    @Volatile var lastScan: NxScan? = null
        private set

    private val GENERIC = listOf("buds", "earbud", "headset", "img_ear", "ic_ear", "png_ear")
    private val REJECT = listOf("launcher", "ic_launcher", "background", "notification", "logo", "banner")

    /** right model but not the pair itself: never auto-picked, still offered in the picker */
    private val NOT_THE_PAIR = listOf("case", "charg", "guide", "tutorial", "anim", "left", "right", "box", "pair_")
    private const val AUTO_PICK_MIN = 100

    private fun slugs(model: String): List<String> {
        val m = model.lowercase(Locale.ROOT).replace("nothing", "").trim()
        val base = m.filter { it.isLetterOrDigit() }
        val words = mapOf('1' to "one", '2' to "two", '3' to "three", '4' to "four")
        val out = LinkedHashSet<String>()
        if (base.isNotEmpty()) out.add(base)
        base.lastOrNull()?.let { ch -> words[ch]?.let { out.add(base.dropLast(1) + it) } }
        if (base.startsWith("ear") && base.length > 3) {
            out.add("ear" + base.drop(3)); out.add("ear_" + base.drop(3))
        }
        return out.toList()
    }

    private fun rank(name: String, slug: List<String>): Int {
        val n = name.lowercase(Locale.ROOT)
        val norm = n.filter { it.isLetterOrDigit() }
        if (REJECT.any { it in n }) return 0
        var s = 0
        for (k in slug) if (k.filter { it.isLetterOrDigit() } in norm && k.isNotEmpty()) s = maxOf(s, 100)
        if (s == 0) for (g in GENERIC) if (g.filter { it.isLetterOrDigit() } in norm) s = maxOf(s, 15)
        if (s > 0 && NOT_THE_PAIR.any { it in n }) s = minOf(s, 50)
        return s
    }

    /** blocking: lists the bitmap drawables / mipmaps / raws of every installed Nothing X package */
    fun scanNothingX(a: Context, model: String): NxScan {
        val pm = a.packageManager
        val pkgs = nothingXPackages(a)
        val slug = slugs(model)
        val all = ArrayList<Cand>()
        for (p in pkgs) {
            try {
                val res = pm.getResourcesForApplication(p)
                for (t in 1..0x20) {
                    val first = (0x7f shl 24) or (t shl 16)
                    val type = try { res.getResourceTypeName(first) } catch (e: Exception) { continue }
                    if (type != "drawable" && type != "mipmap" && type != "raw") continue
                    var e = 0
                    while (e < 0x1800) {
                        val id = first or e
                        val name = try { res.getResourceEntryName(id) } catch (x: Exception) { break }
                        e++
                        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true; inScaled = false }
                        try {
                            BitmapFactory.decodeResource(res, id, o)
                        } catch (x: Exception) {
                            continue
                        }
                        if (o.outWidth <= 0 || o.outHeight <= 0) continue
                        all.add(Cand(p, id, type, name, o.outWidth, o.outHeight, rank(name, slug)))
                    }
                }
            } catch (ex: Exception) {
                Log.w(TAG, "scan $p", ex)
            }
        }
        val picked = all.filter { it.score >= AUTO_PICK_MIN && it.w >= 100 && it.h >= 100 }
            .maxWithOrNull(compareBy<Cand>({ it.score }, { it.w * it.h }))
        return NxScan(pkgs, all, picked).also { lastScan = it }
    }

    fun decodeCand(a: Context, c: Cand, maxSide: Int): Bitmap? = try {
        val res = a.packageManager.getResourcesForApplication(c.pkg)
        var s = 1
        while (c.w / (s * 2) >= maxSide && c.h / (s * 2) >= maxSide) s *= 2
        val o = BitmapFactory.Options().apply { inSampleSize = s; inScaled = false }
        BitmapFactory.decodeResource(res, c.id, o)
    } catch (e: Exception) {
        null
    }

    /** product-image-looking bitmaps for the `FROM NOTHING X` picker (>= 200 px, not launcher icons) */
    fun pickerCandidates(a: Context): List<Cand> {
        val scan = lastScan ?: scanNothingX(a, "")
        return scan.all.filter { it.w >= 200 && it.h >= 200 && REJECT.none { r -> r in it.name.lowercase(Locale.ROOT) } }
            .sortedByDescending { it.score * 1_000_000 + it.w * it.h }.take(60)
    }

    // --- diagnostics -----------------------------------------------------------------------------------

    @Suppress("MissingPermission")
    fun diagnose(a: Context): List<String> {
        val out = ArrayList<String>()
        out.add("permission: ${if (hasPermission(a)) "yes" else "NO"}")
        if (!hasPermission(a)) return out
        val adapter = try { a.getSystemService(BluetoothManager::class.java)?.adapter } catch (e: Exception) { null }
        out.add("adapter: ${if (adapter == null) "none" else if (adapter.isEnabled) "on" else "off"}")
        val bonded = try { adapter?.bondedDevices.orEmpty() } catch (e: Exception) { emptySet() }
        getMetadataMethod
        out.add("metadata: ${if (getMetadataMethod == null || metadataBlocked != null) "blocked (${metadataBlocked ?: "?"})" else "available"}")
        for (d in bonded.take(8)) {
            val conn = isConnected(d)
            val nm = displayName(d).ifBlank { "?" }
            val keys = listOf("L" to META_LEFT_BATTERY, "R" to META_RIGHT_BATTERY, "case" to META_CASE_BATTERY)
                .joinToString(" ") { "${it.first}=${metaBattery(d, it.second).let { v -> if (v < 0) "-" else v.toString() }}" }
            val gb = deviceBattery(d).let { if (it < 0) "-" else it.toString() }
            val bc = broadcastLevel[d.address]?.let { "${it.percent}% ${(System.currentTimeMillis() - it.at) / 60_000}m ago" } ?: "-"
            val hp = if (isHeadphones(d)) "headphones" else "not headphones"
            out.add("${if (conn) "*" else " "} $nm | $hp | meta $keys | getBatteryLevel=$gb | broadcast=$bc")
        }
        val b = last
        out.add("source won: ${b?.source ?: "none"}; connected=${b?.connected}")
        out.add("model: ${b?.model.orEmpty().ifBlank { "-" }}; companion: ${b?.companion ?: "-"}")
        if (b != null && b.address.isNotEmpty()) out.add("picture: ${picture(a, b).source}")
        out.add("resolver: $resolveNotes")
        val sc = lastScan
        if (sc != null) out.add("NothingX packages: ${sc.packages.joinToString().ifBlank { "none" }}; image candidates ${sc.all.size}; auto-picked ${sc.picked?.name ?: "none"}")
        return out
    }
}


