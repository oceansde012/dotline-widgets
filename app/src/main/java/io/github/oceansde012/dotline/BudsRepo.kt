package io.github.oceansde012.dotline

import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/** What the buds widget shows. Battery values are percent, -1 = unknown. */
class Buds(
    val permission: Boolean,
    val connected: Boolean,
    val name: String,
    val left: Int,
    val right: Int,
    val case: Int,
    /** single level (HFP / getBatteryLevel) when the earbuds do not report L / R */
    val single: Int,
) {
    val hasSides get() = left >= 0 || right >= 0
    val lowest: Int get() = listOf(left, right, single).filter { it >= 0 }.minOrNull() ?: -1
    val key get() = listOf(permission, connected, name, left, right, case, single)

    companion object {
        fun noPermission() = Buds(false, false, "", -1, -1, -1, -1)
        fun disconnected() = Buds(true, false, "", -1, -1, -1, -1)
    }
}

/**
 * Connected audio device + earbud battery (DESIGN_SPEC.md 2.6). Battery order: untethered metadata
 * (left / right / case) via reflection, then BluetoothDevice.getBatteryLevel() via reflection, else
 * connected-only. Both are hidden / system APIs: every call is guarded and a failure just means "unknown".
 * The last result is kept in memory and in SharedPreferences so a freshly started process can render at once.
 */
object BudsRepo {
    private const val TAG = "NdBuds"
    private const val PREFS = "nd_buds"
    private const val K_LAST = "last"

    /** BluetoothDevice.ACTION_BATTERY_LEVEL_CHANGED is hidden API (a protected broadcast, stable string) */
    const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"

    // BluetoothDevice.METADATA_UNTETHERED_*_BATTERY (AOSP: MANUFACTURER_NAME = 0 ... LEFT_ICON 7, RIGHT_ICON 8,
    // CASE_ICON 9, then the batteries)
    private const val META_LEFT_BATTERY = 10
    private const val META_RIGHT_BATTERY = 11
    private const val META_CASE_BATTERY = 12

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var last: Buds? = null
    private var refreshing = false

    fun hasPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** last known state for an immediate render; never blocks */
    fun current(ctx: Context): Buds {
        if (!hasPermission(ctx)) return Buds.noPermission()
        last?.let { return it }
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K_LAST, null)
        val parsed = s?.split('|')?.takeIf { it.size == 5 }?.let {
            try {
                Buds(true, true, it[0], it[1].toInt(), it[2].toInt(), it[3].toInt(), it[4].toInt())
            } catch (e: Exception) {
                null
            }
        }
        // prefs hold only a connected state; "no entry" renders as disconnected until the first refresh
        return parsed ?: Buds.disconnected()
    }

    private fun store(ctx: Context, b: Buds) {
        last = b
        val v = if (b.connected) "${b.name.replace('|', ' ')}|${b.left}|${b.right}|${b.case}|${b.single}" else null
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (v == null) remove(K_LAST) else putString(K_LAST, v)
        }.apply()
    }

    /**
     * Asks the A2DP and HEADSET profile proxies for connected devices, reads their batteries and
     * re-renders the widgets. Async (the proxies connect asynchronously); cheap when no widget is placed.
     */
    fun refreshAsync(ctx: Context) {
        val a = ctx.applicationContext
        if (!WeatherJob.hasWidgets(a, BudsWidgetProvider::class.java)) return
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
            publish(a, Buds.disconnected())
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
            refreshing = false
            val b = try {
                read(a, devices.values.toList())
            } catch (e: Exception) {
                Log.w(TAG, "read failed", e)
                Buds.disconnected()
            }
            publish(a, b)
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

    private fun publish(a: Context, b: Buds) {
        refreshing = false
        store(a, b)
        BudsWidgetProvider.updateAll(a)
    }

    /** picks the device that reports batteries (earbuds) over e.g. a car kit, and reads them */
    private fun read(a: Context, devices: List<BluetoothDevice>): Buds {
        if (devices.isEmpty()) return Buds.disconnected()
        var best: Buds? = null
        for (d in devices) {
            val name = try {
                d.alias ?: d.name ?: ""
            } catch (e: SecurityException) {
                ""
            }
            val l = metaBattery(d, META_LEFT_BATTERY)
            val r = metaBattery(d, META_RIGHT_BATTERY)
            val c = metaBattery(d, META_CASE_BATTERY)
            val single = if (l < 0 && r < 0) deviceBattery(d) else -1
            val b = Buds(true, true, name, l, r, c, single)
            if (best == null || score(b) > score(best)) best = b
        }
        return best ?: Buds.disconnected()
    }

    private fun score(b: Buds) = (if (b.hasSides) 2 else 0) + (if (b.single >= 0) 1 else 0)

    /** BluetoothDevice.getMetadata(int): byte[] holding the percent as ASCII text; -1 when unavailable */
    private fun metaBattery(d: BluetoothDevice, key: Int): Int = try {
        val m = BluetoothDevice::class.java.getMethod("getMetadata", Int::class.javaPrimitiveType)
        val bytes = m.invoke(d, key) as? ByteArray
        val v = bytes?.let { String(it, Charsets.UTF_8).trim().toIntOrNull() } ?: -1
        if (v in 0..100) v else -1
    } catch (t: Throwable) {
        -1
    }

    /** BluetoothDevice.getBatteryLevel(): hidden; BATTERY_LEVEL_UNKNOWN = -1 */
    private fun deviceBattery(d: BluetoothDevice): Int = try {
        val m = BluetoothDevice::class.java.getMethod("getBatteryLevel")
        val v = m.invoke(d) as? Int ?: -1
        if (v in 0..100) v else -1
    } catch (t: Throwable) {
        -1
    }
}
