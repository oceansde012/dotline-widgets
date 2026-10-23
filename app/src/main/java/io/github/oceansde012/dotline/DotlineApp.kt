package io.github.oceansde012.dotline

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Global crash handler: writes the last uncaught exception to filesDir/crash.txt (no personal data),
 * then hands it to the previous handler so the system still kills the process normally.
 * MainActivity shows the file on the next start.
 */
class DotlineApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrash(this, thread, throwable)
            } catch (_: Throwable) {
                // the handler itself must never fail
            }
            if (previous != null) previous.uncaughtException(thread, throwable)
        }
    }

    companion object {
        private const val FILE = "crash.txt"

        private fun writeCrash(ctx: Context, thread: Thread, t: Throwable) {
            val iso = try {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())
            } catch (_: Throwable) {
                Date().toString()
            }
            var version = "?"
            try {
                val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                version = "${pi.versionName} (${pi.longVersionCode})"
            } catch (_: Throwable) {
            }
            val sb = StringBuilder()
            sb.append("time: ").append(iso).append('\n')
            sb.append("version: ").append(version).append('\n')
            sb.append("sdk: ").append(Build.VERSION.SDK_INT).append('\n')
            sb.append("model: ").append(Build.MODEL).append('\n')
            sb.append("thread: ").append(thread.name).append('\n')
            sb.append('\n').append(Log.getStackTraceString(t))
            File(ctx.filesDir, FILE).writeText(sb.toString())
        }

        /** the saved crash report, or null if there is none */
        fun readCrash(ctx: Context): String? = try {
            val f = File(ctx.filesDir, FILE)
            if (f.isFile) f.readText().ifBlank { null } else null
        } catch (_: Throwable) {
            null
        }

        fun clearCrash(ctx: Context) {
            try {
                File(ctx.filesDir, FILE).delete()
            } catch (_: Throwable) {
            }
        }
    }
}
