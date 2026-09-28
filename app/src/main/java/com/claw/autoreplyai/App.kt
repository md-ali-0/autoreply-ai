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

        // Bring back any reply that was queued when the process died. Android kills a
        // backgrounded app freely, and without this a message waiting out its cooldown
        // disappears for good.
        //
        // Deliberately isolated: the guard above is installed first, but if anything in
        // it throws we would reach here with the app half-initialised, and the queue
        // would silently stay empty — the exact failure this call exists to prevent.
        try {
            ReplyEngine.restorePending(this)
        } catch (e: Throwable) {
            LogStore.add(
                this,
                "পেন্ডিং কিউ ফেরানো যায়নি (বুট): ${e.javaClass.simpleName} — ${e.message ?: ""}"
            )
        }

        // One-time: move any API keys still sitting in plaintext preferences into the
        // Keystore-backed store. Also runs on every later boot, where it is a no-op.
        try {
            Prefs.get(this).migrateSecretsToKeystore()
        } catch (e: Throwable) {
            LogStore.add(
                this,
                "সিক্রেট সরানো যায়নি: ${e.javaClass.simpleName} — ${e.message ?: ""}"
            )
        }
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
