package io.github.oceansde012.dotline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import android.util.LruCache
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Loads images given as a URI string (media art URIs: content:// or https://) off the main thread, with a
 * small in-memory cache keyed by URI. Used for Spotify-style sessions that expose only an art URI.
 */
object ArtLoader {
    private const val TAG = "NdArt"
    private const val MAX_SIDE = 640
    private const val RETRY_MS = 60_000L
    private const val DISK_LIMIT = 40
    private val io = Executors.newFixedThreadPool(2)
    private val cache = LruCache<String, Bitmap>(10)
    private val failed = HashMap<String, Long>()   // uri -> failure time, retried after RETRY_MS
    private val pending = HashSet<String>()
    /** last failure text per scheme, for the diagnostics card */
    @Volatile var lastError: String = ""

    @Synchronized
    fun cached(uri: String): Bitmap? = cache.get(uri)

    /** starts a load unless cached / running / already failed; [onLoaded] runs on the loader thread */
    @Synchronized
    fun request(ctx: Context, uri: String, onLoaded: () -> Unit) {
        if (cache.get(uri) != null || uri in pending) return
        val failedAt = failed[uri]
        if (failedAt != null && android.os.SystemClock.elapsedRealtime() - failedAt < RETRY_MS) return
        failed.remove(uri)
        pending.add(uri)
        val a = ctx.applicationContext
        io.execute {
            val bmp = try {
                decode(a, uri)
            } catch (e: Exception) {
                lastError = "${Uri.parse(uri).scheme}: ${e.javaClass.simpleName} ${e.message ?: ""}".take(120)
                Log.w(TAG, "art load failed (${Uri.parse(uri).scheme})", e)
                null
            }
            synchronized(this) {
                pending.remove(uri)
                if (bmp != null) cache.put(uri, bmp) else failed[uri] = android.os.SystemClock.elapsedRealtime()
            }
            if (bmp != null) onLoaded()
        }
    }

    /** blocking: also used by the earbuds image chain */
    fun decode(ctx: Context, uri: String): Bitmap? {
        val u = Uri.parse(uri)
        fun open(): java.io.InputStream? = when (u.scheme) {
            "content", "file", "android.resource" -> ctx.contentResolver.openInputStream(u)
            "https" -> {
                val c = URL(uri).openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
                c.inputStream
            }
            else -> throw java.io.IOException("unsupported scheme ${u.scheme}")
        }
        val bytes = open()?.use { it.readBytes() } ?: return null
        return decodeBytes(bytes)
    }

    fun decodeBytes(bytes: ByteArray): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var s = 1
        while (o.outWidth / (s * 2) >= MAX_SIDE && o.outHeight / (s * 2) >= MAX_SIDE) s *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = s }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2)
    }
}

