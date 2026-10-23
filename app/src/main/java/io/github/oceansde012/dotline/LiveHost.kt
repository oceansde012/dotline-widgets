package io.github.oceansde012.dotline

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

/**
 * Runtime receivers of the clock / power / weather / buds widgets (none of these broadcasts can be declared in
 * the manifest). Started from [MediaTracker.ensureStarted], i.e. by the long-lived listener service, the
 * providers' onUpdate and the activity; when the process dies, [Ticker]'s alarm and the 15 min [WeatherJob]
 * catch up.
 */
object LiveHost {
    /** system broadcasts, sent by the framework itself */
    private val SYSTEM = listOf(
        Intent.ACTION_USER_PRESENT,
        Intent.ACTION_BATTERY_CHANGED,
        Intent.ACTION_POWER_CONNECTED,
        Intent.ACTION_POWER_DISCONNECTED,
        Intent.ACTION_TIME_TICK,
        Intent.ACTION_TIME_CHANGED,
        Intent.ACTION_TIMEZONE_CHANGED,
    )

    /** sent by the Bluetooth app (another uid), so the receiver has to be exported */
    private val BLUETOOTH = listOf(
        BluetoothDevice.ACTION_ACL_CONNECTED,
        BluetoothDevice.ACTION_ACL_DISCONNECTED,
        BluetoothAdapter.ACTION_STATE_CHANGED,
        BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
        BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED,
        BudsRepo.ACTION_BATTERY_LEVEL_CHANGED,
    )

    private const val USER_PRESENT_MIN_AGE_MS = 10 * 60 * 1000L
    private var registered = false

    @Synchronized
    fun ensure(ctx: Context) {
        if (registered) return
        val a = ctx.applicationContext
        try {
            val sys = IntentFilter().apply { SYSTEM.forEach { addAction(it) } }
            val bt = IntentFilter().apply { BLUETOOTH.forEach { addAction(it) } }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                a.registerReceiver(systemReceiver, sys, Context.RECEIVER_NOT_EXPORTED)
                a.registerReceiver(btReceiver, bt, Context.RECEIVER_EXPORTED)
            } else {
                a.registerReceiver(systemReceiver, sys)
                a.registerReceiver(btReceiver, bt)
            }
            registered = true
        } catch (e: Exception) {
            android.util.Log.w("NdLive", "register failed", e)
        }
    }

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_USER_PRESENT -> {
                    if (WeatherJob.hasWidgets(context, WeatherWidgetProvider::class.java)) {
                        WeatherRepo.refreshAsync(context, USER_PRESENT_MIN_AGE_MS)
                    }
                    BudsRepo.refreshAsync(context)
                    PowerWidgetProvider.refresh(context)
                    Ticker.tick(context)
                }
                // minute boundary (also covers the date rollover) and clock / zone changes
                Intent.ACTION_TIME_TICK, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> Ticker.tick(context)
                // sticky: delivered once on registration, then on every charge-state tick
                Intent.ACTION_BATTERY_CHANGED -> PowerWidgetProvider.onBattery(context, intent, force = false)
                Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED -> PowerWidgetProvider.refresh(context)
            }
        }
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BudsRepo.ACTION_BATTERY_LEVEL_CHANGED) BudsRepo.onBatteryBroadcast(intent)
            BudsRepo.refreshAsync(context)
        }
    }
}
