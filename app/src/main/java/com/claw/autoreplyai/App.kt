package com.claw.autoreplyai

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashGuard()
    }

    /**
     * Records any uncaught exception into the in-app log + files/crash.log, then
     * hands control back to the platform handler (which terminates the process).
     */
    private fun installCrashGuard() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val stamp = SimpleDateFormat("dd MMM HH:mm:ss", Locale.US).format(Date())
                LogStore.add(
                    applicationContext,
                    "‼️ ক্র্যাশ — ${throwable.javaClass.simpleName}: ${throwable.message ?: ""}"
                )
                File(filesDir, "crash.log")
                    .appendText("\n===== $stamp =====\n$sw")
            } catch (_: Throwable) {
                // never let the logger itself take the app down
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
