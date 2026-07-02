package fr.retrospare.blazeplayer.debug

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Journal ultra simple des crashs/erreurs critiques dans le cache applicatif.
 * Objectif debug terrain : ne plus perdre les plantages silencieux quand Logcat n'est pas branché.
 */
object CrashReporter {
    private const val TAG = "BlazeCrash"
    private const val FILE_NAME = "blaze_crash_log.txt"
    private const val MAX_BYTES = 512 * 1024

    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            log(appContext, "UNCAUGHT on ${thread.name}", throwable)
            previous?.uncaughtException(thread, throwable)
        }
        Log.i(TAG, "CrashReporter installed")
    }

    fun log(context: Context, message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
        try {
            val file = File(context.applicationContext.cacheDir, FILE_NAME)
            if (file.exists() && file.length() > MAX_BYTES) file.delete()
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val header = "[$timestamp] SDK=${Build.VERSION.SDK_INT} $message\n"
            file.appendText(header)
            if (throwable != null) file.appendText(Log.getStackTraceString(throwable))
        } catch (ignored: Exception) {
            Log.w(TAG, "Unable to write crash log", ignored)
        }
    }
}
