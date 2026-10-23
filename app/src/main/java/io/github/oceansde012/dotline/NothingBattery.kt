package io.github.oceansde012.dotline

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

/**
 * Left / right / case battery of Nothing (and CMF) earbuds, asked over the earbuds' own RFCOMM service — the
 * channel the Nothing X app talks on. Only the read-only battery request (0xC007) is ever sent.
 *
 * Frame: 55 60 01 | cmd lo hi | len lo hi | op id | payload | crc16 (Modbus, little endian).
 * Battery reply (cmd 0xE007): count, then (device id, level) pairs; id 2 = left, 3 = right, 4 = case,
 * 6 = both (stereo); bit 7 of the level = charging.
 *
 * Blocking, call off the main thread. Any failure (no service, busy, old firmware) returns null.
 */
object NothingBattery {
    private const val TAG = "NdNothingBat"
    private val SERVICE = UUID.fromString("aeac4a03-dff5-498f-843a-34487cf133eb")
    private const val CMD_BATTERY = 0xC007
    private const val TIMEOUT_MS = 3000L

    class Levels(val left: Int, val right: Int, val case: Int, val leftCharging: Boolean, val rightCharging: Boolean)

    /** true when the device advertises the Nothing service (cached SDP record; no radio traffic) */
    fun supports(d: BluetoothDevice): Boolean = has(d, SERVICE) || has(d, FAST_PAIR)

    @Volatile var lastNote: String = "not run yet"
        private set

    /** Google Fast Pair message stream: the earbuds push "battery updated" (group 3, code 3) after connect */
    private val FAST_PAIR = UUID.fromString("df21fe2c-2515-4fdb-8886-f12c4d67927c")

    fun read(d: BluetoothDevice): Levels? {
        val notes = ArrayList<String>()
        val t0 = System.currentTimeMillis()
        val nx = if (has(d, SERVICE)) talk(d, SERVICE, frame(CMD_BATTERY, 1), ::parse).also { notes.add("nothing $lastNote") } else null
        val fp = if (nx == null && has(d, FAST_PAIR)) talk(d, FAST_PAIR, null, ::parseFastPair).also { notes.add("fastpair $lastNote") } else null
        lastNote = notes.joinToString("; ") + " in ${System.currentTimeMillis() - t0} ms"
        Log.i(TAG, lastNote)
        return nx ?: fp
    }

    private fun has(d: BluetoothDevice, u: UUID): Boolean = try {
        d.uuids.orEmpty().any { it == ParcelUuid(u) }
    } catch (e: Exception) {
        false
    }

    /** Fast Pair message stream: group(1) code(1) length(2, big endian) data */
    private fun parseFastPair(b: ByteArray, n: Int): Levels? {
        var i = 0
        while (i + 4 <= n) {
            val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            if (i + 4 + len > n) return null
            if (b[i] == 3.toByte() && b[i + 1] == 3.toByte() && len >= 3) {
                fun pct(v: Byte) = (v.toInt() and 0x7F).takeIf { it in 0..100 } ?: -1
                fun ch(v: Byte) = v.toInt() and 0x80 != 0
                val p = i + 4
                return Levels(pct(b[p]), pct(b[p + 1]), pct(b[p + 2]), ch(b[p]), ch(b[p + 1]))
            }
            i += 4 + len
        }
        return null
    }

    @Suppress("MissingPermission")
    private fun talk(d: BluetoothDevice, service: UUID, request: ByteArray?, parse: (ByteArray, Int) -> Levels?): Levels? {
        var socket: BluetoothSocket? = null
        val watchdog = Thread {
            try {
                Thread.sleep(TIMEOUT_MS)
                socket?.close()   // unblocks connect() / read()
            } catch (e: Exception) {
                // interrupted: finished in time
            }
        }
        return try {
            socket = d.createRfcommSocketToServiceRecord(service)
            watchdog.start()
            socket.connect()
            if (request != null) {
                socket.outputStream.write(request)
                socket.outputStream.flush()
            }
            val buf = ByteArray(512)
            var n = 0
            var levels: Levels? = null
            while (levels == null && n < buf.size) {
                val r = socket.inputStream.read(buf, n, buf.size - n)
                if (r <= 0) break
                n += r
                levels = parse(buf, n)
            }
            lastNote = if (levels != null) "ok" else "no battery reply ($n bytes)"
            levels
        } catch (e: Exception) {
            lastNote = "failed: ${e.javaClass.simpleName}"
            Log.d(TAG, "read failed", e)
            null
        } finally {
            watchdog.interrupt()
            try { socket?.close() } catch (e: Exception) { /* closed */ }
        }
    }

    private fun frame(cmd: Int, op: Int): ByteArray {
        val h = byteArrayOf(0x55, 0x60, 0x01, (cmd and 0xFF).toByte(), (cmd shr 8).toByte(), 0, 0, op.toByte())
        val crc = crc16(h, h.size)
        return h + byteArrayOf((crc and 0xFF).toByte(), (crc shr 8).toByte())
    }

    /** finds a battery reply among the frames read so far; null until a complete one arrived */
    private fun parse(b: ByteArray, n: Int): Levels? {
        var i = 0
        while (i + 8 <= n) {
            if (b[i] != 0x55.toByte() || b[i + 1] != 0x60.toByte() || b[i + 2] != 0x01.toByte()) { i++; continue }
            val cmd = (b[i + 3].toInt() and 0xFF) or ((b[i + 4].toInt() and 0xFF) shl 8)
            val len = (b[i + 5].toInt() and 0xFF) or ((b[i + 6].toInt() and 0xFF) shl 8)
            if (i + 8 + len > n) return null   // frame not complete yet
            if (cmd == 0xE007 || cmd == 0x4007) {
                val p = i + 8
                val count = b[p].toInt() and 0xFF
                var l = -1; var r = -1; var c = -1; var lc = false; var rc = false
                for (k in 0 until count) {
                    val at = p + 1 + k * 2
                    if (at + 1 >= i + 8 + len) break
                    val v = b[at + 1].toInt() and 0xFF
                    val pct = (v and 0x7F).takeIf { it in 0..100 } ?: -1
                    val ch = v and 0x80 != 0
                    when (b[at].toInt() and 0xFF) {
                        2 -> { l = pct; lc = ch }
                        3 -> { r = pct; rc = ch }
                        4 -> c = pct
                        6 -> { l = pct; r = pct; lc = ch; rc = ch }
                    }
                }
                return Levels(l, r, c, lc, rc)
            }
            i += 8 + len + 2
        }
        return null
    }

    private fun crc16(d: ByteArray, len: Int): Int {
        var crc = 0xFFFF
        for (i in 0 until len) {
            crc = crc xor (d[i].toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
        }
        return crc
    }
}
