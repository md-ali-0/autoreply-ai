package com.claw.autoreplyai

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.net.URLEncoder

/**
 * Drives the real WhatsApp UI:
 *  1. opens the chat via a wa.me deep link (reply text pre-filled)
 *  2. waits for the chat window, taps the Send button
 *
 * This is the only way to send on behalf of the user without root or the official
 * WhatsApp Business Cloud API.
 */
class SendAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var deadline = 0L
    private var targetPkg = WA
    private var pendingText = ""
    private var pendingResult: ((Boolean) -> Unit)? = null
    private var attempts = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (deadline == 0L) return
        if (System.currentTimeMillis() > deadline) {
            finish(false)
            return
        }
        val pkg = event?.packageName?.toString() ?: return
        if (pkg != WA && pkg != WAB) return
        tick()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ send

    private fun dispatch(pkg: String, waNumber: String, text: String, onResult: (Boolean) -> Unit): Boolean {
        targetPkg = if (pkg.contains("w4b")) WAB else WA
        pendingText = text
        pendingResult = onResult
        attempts = 0
        deadline = System.currentTimeMillis() + TIMEOUT_MS

        // A locked screen is the one case this route cannot handle — say so plainly
        // instead of timing out with a vague error.
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (km.isKeyguardLocked) {
            LogStore.add(
                applicationContext,
                if (km.isDeviceSecure) "ফোন লক করা (PIN/ফিঙ্গারপ্রিন্ট) — স্ক্রিন থেকে রিপ্লাই সম্ভব নয়"
                else "লক স্ক্রিনে আছে — চেষ্টা করছি"
            )
        }

        wakeScreen()

        val encoded = try {
            URLEncoder.encode(text, "UTF-8")
        } catch (e: Exception) {
            text
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$waNumber?text=$encoded")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            setPackage(targetPkg)
        }

        return try {
            startActivity(intent)
            LogStore.add(applicationContext, "WhatsApp চ্যাট খোলা হচ্ছে → $waNumber")
            scheduleTick()
            true
        } catch (e: Exception) {
            LogStore.add(applicationContext, "WhatsApp খোলা যায়নি: ${e.message}")
            finish(false)
            false
        }
    }

    /**
     * Launching an activity no longer turns the panel on by itself, so nudge the
     * screen awake first. The lock is released by itself after [WAKE_MS].
     */
    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isInteractive) return
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "AutoReplyAI:send"
            )
            wl.acquire(WAKE_MS)
        } catch (e: Exception) {
            LogStore.add(applicationContext, "স্ক্রিন জাগানো যায়নি: ${e.message}")
        }
    }

    private fun scheduleTick() {
        handler.postDelayed({
            if (deadline == 0L) return@postDelayed
            if (System.currentTimeMillis() > deadline) {
                finish(false)
                return@postDelayed
            }
            tick()
            scheduleTick()
        }, TICK_MS)
    }

    private fun tick() {
        attempts++
        val root = rootInActiveWindow ?: return
        val pkgNow = root.packageName?.toString()
        if (pkgNow != WA && pkgNow != WAB) return

        // 1. try to tap send (text already pre-filled by the deep link)
        if (clickSend(root)) return

        // 2. fallback: type the text into the composer, then send on a later tick
        if (attempts >= 3) {
            val entry = findById(root, "entry") ?: findById(root, "input")
            if (entry != null) {
                val args = Bundle()
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pendingText)
                entry.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
        }
    }

    private fun clickSend(root: AccessibilityNodeInfo): Boolean {
        val node = findById(root, "send") ?: findByDescription(root)
        if (node == null) return false
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (ok) {
            LogStore.add(applicationContext, "✓ Send বাটনে ট্যাপ হয়েছে")
            finish(true)
        }
        return ok
    }

    private fun finish(success: Boolean) {
        deadline = 0L
        val cb = pendingResult
        pendingResult = null
        if (!success) LogStore.add(applicationContext, "✗ সময় শেষ — Send বাটন পাওয়া যায়নি")
        cb?.invoke(success)
    }

    // ------------------------------------------------------------- tree utils

    private fun findById(node: AccessibilityNodeInfo, suffix: String): AccessibilityNodeInfo? {
        val id = node.viewIdResourceName
        if (id != null && id.endsWith(":id/$suffix") && node.isClickable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = findById(child, suffix)
            if (hit != null) return hit
        }
        return null
    }

    private fun findByDescription(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString()?.lowercase()
        if (!desc.isNullOrBlank() && node.isClickable &&
            (desc.contains("send") || desc.contains("পাঠান") || desc.contains("পাঠাও"))
        ) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = findByDescription(child)
            if (hit != null) return hit
        }
        return null
    }

    companion object {
        private const val WA = "com.whatsapp"
        private const val WAB = "com.whatsapp.w4b"
        private const val TIMEOUT_MS = 25_000L
        private const val TICK_MS = 700L
        private const val WAKE_MS = 20_000L

        @Volatile
        private var instance: SendAccessibilityService? = null

        fun isRunning(): Boolean = instance != null

        /**
         * Fire-and-forget from the caller's perspective; [onResult] is invoked on the
         * main thread with true only after the Send button was actually tapped.
         */
        fun send(ctx: Context, pkg: String, waNumber: String, text: String, onResult: (Boolean) -> Unit) {
            val svc = instance
            if (svc == null) {
                LogStore.add(ctx, "অ্যাক্সেসিবিলিটি সার্ভিস চালু নেই")
                onResult(false)
                return
            }
            svc.handler.post { svc.dispatch(pkg, waNumber, text, onResult) }
        }
    }
}
