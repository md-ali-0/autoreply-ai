package com.claw.autoreplyai

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.PendingIntent
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
 * Drives the real chat app UI when the notification has no usable inline-reply
 * action:
 *  1. opens the conversation — either through a `wa.me` deep link (WhatsApp, the
 *     reply text comes pre-filled) or through the notification's own content
 *     intent (Messenger, where no phone-number link exists),
 *  2. waits for the chat window, then types the text (if needed) and taps Send.
 *
 * This is the only way to send on behalf of the user without root or the official
 * WhatsApp Business Cloud API.
 */
class SendAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    /**
     * The single job in progress, or null when idle.
     *
     * This used to be six separate service-level fields (`targetPkg`, `pendingText`,
     * `textPrefilled`, `pendingResult`, `attempts`, `deadline`). Because they were
     * per-service rather than per-job, a second send arriving while the first was still
     * looking for the Send button overwrote all six at once — so the first job would
     * then tap Send on the second conversation's screen and report its own success. The
     * user sees a reply delivered to the wrong person, which is the worst failure this
     * app can produce.
     *
     * Bundling them into one object makes that impossible to express: there is only ever
     * one job, and a second request waits its turn in [queue].
     */
    private class AccessibilityJob(
        val pkg: String,
        val target: Any,
        val text: String,
        val prefilled: Boolean,
        val onResult: (Boolean) -> Unit,
        val what: String
    ) {
        var attempts = 0
        var textPrefilled = false
        var deadline = 0L
    }

    private var job: AccessibilityJob? = null

    /**
     * Sends waiting for the current job to end. Accessibility is a single global
     * resource — there is one screen, one focused window, one Send button — so
     * serialising here is not a limitation to work around, it is the honest model.
     */
    private val queue = ArrayDeque<AccessibilityJob>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Accessibility services are the easiest thing in Android to crash by accident:
        // recycled nodes, a disappearing window, a package that unloads mid-walk. An
        // exception here kills the service, and a dead service means auto-reply stops
        // with no visible symptom.
        try {
            val j = job ?: return
            if (j.deadline == 0L) return
            if (System.currentTimeMillis() > j.deadline) {
                finish(false)
                return
            }
            val pkg = event?.packageName?.toString() ?: return
            if (pkg != j.pkg) return
            tick(j)
        } catch (e: Throwable) {
            LogStore.add(
                applicationContext,
                "‼️ অ্যাক্সেসিবিলিটি ইভেন্টে ত্রুটি — ${e.javaClass.simpleName}: ${e.message ?: ""}"
            )
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        // A queued job will never run once the service is gone. Fail them now so the
        // callers stop waiting and the reply is reported as undelivered instead of
        // hanging until the caller's own timeout.
        val stranded = queue.toList()
        queue.clear()
        job = null
        for (s in stranded) {
            try {
                s.onResult(false)
            } catch (_: Throwable) {
            }
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ send

    /**
     * WhatsApp route: a wa.me link both opens the chat and pre-fills the text, so
     * the only thing left to do is tap Send.
     */
    private fun dispatch(pkg: String, number: String, text: String, onResult: (Boolean) -> Unit): Boolean {
        val encoded = try {
            URLEncoder.encode(text, "UTF-8")
        } catch (e: Exception) {
            text
        }
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://wa.me/$number?text=$encoded")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            setPackage(pkg)
        }
        return launch(pkg, intent, text, prefilled = true, onResult = onResult, what = "WhatsApp চ্যাট")
    }

    /**
     * Messenger route: there is no phone-number link, so reopen the conversation
     * through the notification's content intent and type the reply in.
     */
    private fun dispatchOpen(
        pkg: String,
        open: PendingIntent,
        text: String,
        onResult: (Boolean) -> Unit
    ): Boolean {
        return launch(pkg, open, text, prefilled = false, onResult = onResult, what = "চ্যাট")
    }

    private fun launch(
        pkg: String,
        target: Any,
        text: String,
        prefilled: Boolean,
        onResult: (Boolean) -> Unit,
        what: String
    ): Boolean {
        // Busy: park it. Two jobs must never be in flight together, because they would
        // share one screen — see [AccessibilityJob].
        val current = job
        if (current != null) {
            if (queue.size >= MAX_QUEUE) {
                LogStore.add(
                    applicationContext,
                    "সেন্ড কিউ ভরে গেছে (${queue.size}) — নতুন রিকোয়েস্ট বাদ"
                )
                onResult(false)
                return false
            }
            queue.addLast(AccessibilityJob(pkg, target, text, prefilled, onResult, what))
            LogStore.add(
                applicationContext,
                "সেন্ড কিউতে অপেক্ষা করছে (${queue.size}) — ${MessagingApps.label(pkg)} $what"
            )
            return true
        }
        return start(AccessibilityJob(pkg, target, text, prefilled, onResult, what))
    }

    /** Begin a job: remember it, open the conversation, and start looking for Send. */
    private fun start(j: AccessibilityJob): Boolean {
        job = j
        j.attempts = 0
        j.textPrefilled = j.prefilled
        j.deadline = System.currentTimeMillis() + TIMEOUT_MS

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

        return try {
            when (val target = j.target) {
                is Intent -> startActivity(target)
                is PendingIntent -> target.send()
                else -> return false
            }
            LogStore.add(applicationContext, "${MessagingApps.label(j.pkg)} ${j.what} খোলা হচ্ছে")
            scheduleTick()
            true
        } catch (e: Exception) {
            LogStore.add(applicationContext, "চ্যাট খোলা যায়নি: ${e.message}")
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
            // This runs on the main looper: an uncaught throw here is an app crash,
            // not a caught error, so the guard matters more than it does elsewhere.
            try {
                val j = job ?: return@postDelayed
                if (j.deadline == 0L) return@postDelayed
                if (System.currentTimeMillis() > j.deadline) {
                    finish(false)
                    return@postDelayed
                }
                tick(j)
                scheduleTick()
            } catch (e: Throwable) {
                LogStore.add(
                    applicationContext,
                    "‼️ টিক ব্যর্থ — ${e.javaClass.simpleName}: ${e.message ?: ""}"
                )
                finish(false)
            }
        }, TICK_MS)
    }

    private fun tick(j: AccessibilityJob) {
        j.attempts++
        // Every node call below can throw IllegalStateException once Android has
        // recycled the window we are walking. Failing this tick is fine — the next
        // one re-reads the tree — so swallow it rather than losing the service.
        val root = try {
            rootInActiveWindow ?: return
        } catch (e: Throwable) {
            return
        }
        val pkgNow = try {
            root.packageName?.toString()
        } catch (e: Throwable) {
            return
        }
        if (pkgNow != j.pkg) return

        // 1. Tap Send if the text is already there (deep link) or once we typed it.
        if (clickSend(j, root)) return

        // 2. Type the text into the composer, then send on a later tick.
        if (j.attempts >= 2) {
            val entry = findComposer(root)
            if (entry != null) {
                val args = Bundle()
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, j.text)
                if (entry.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                    j.textPrefilled = true
                }
            }
        }
    }

    private fun clickSend(j: AccessibilityJob, root: AccessibilityNodeInfo): Boolean {
        // Never tap Send before the composer actually holds our text — otherwise
        // the tap lands on an empty composer and reports a false success.
        if (!j.textPrefilled) return false

        val node = findById(root, SEND_IDS) ?: findByDescription(root)
        if (node == null) return false
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (ok) {
            LogStore.add(applicationContext, "✓ Send বাটনে ট্যাপ হয়েছে")
            finish(true)
        }
        return ok
    }

    /**
     * End the current job, hand the result back exactly once, then start the next
     * queued one. Every exit path — success, timeout, launch failure, service
     * teardown — funnels through here, which is what stops a job from being both
     * finished and started again.
     */
    private fun finish(success: Boolean) {
        val done = job
        job = null
        if (done != null) {
            if (!success) LogStore.add(applicationContext, "✗ সময় শেষ — Send বাটন পাওয়া যায়নি")
            try {
                done.onResult(success)
            } catch (e: Throwable) {
                LogStore.add(
                    applicationContext,
                    "‼️ সেন্ড ফলাফল জানাতে ত্রুটি — ${e.javaClass.simpleName}: ${e.message ?: ""}"
                )
            }
        }
        // Deliberately after the callback: the caller may log or queue more work, and
        // starting the next job first would put two in flight at once.
        val next = queue.removeFirstOrNull()
        if (next != null) start(next)
    }

    // ------------------------------------------------------------- tree utils

    private fun findById(node: AccessibilityNodeInfo, suffixes: List<String>): AccessibilityNodeInfo? {
        val id = node.viewIdResourceName
        if (id != null && node.isClickable && suffixes.any { id.endsWith(":id/$it") }) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = findById(child, suffixes)
            if (hit != null) return hit
        }
        return null
    }

    private fun findByDescription(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString()?.lowercase()
        if (!desc.isNullOrBlank() && node.isClickable && SEND_WORDS.any { desc.contains(it) }) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = findByDescription(child)
            if (hit != null) return hit
        }
        return null
    }

    /**
     * The message box. Known ids first (WhatsApp: `entry`; Messenger:
     * `row_input_text`), then any editable field that is not a search box.
     */
    private fun findComposer(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        findById(node, COMPOSER_IDS)?.let { return it }
        return firstEditable(node)
    }

    private fun firstEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val cls = node.className?.toString().orEmpty()
        val hint = node.hintText?.toString()?.lowercase().orEmpty()
        val looksLikeSearch = cls.contains("SearchView") ||
                hint.contains("search") || hint.contains("খুঁজ")
        if (node.isEditable && !looksLikeSearch && cls.contains("EditText")) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = firstEditable(child)
            if (hit != null) return hit
        }
        return null
    }

    companion object {
        private const val TIMEOUT_MS = 25_000L
        private const val TICK_MS = 700L
        private const val WAKE_MS = 20_000L

        /** How many sends may wait behind the active one before new ones are refused. */
        private const val MAX_QUEUE = 8

        private val SEND_IDS = listOf("send", "btn_send", "send_button", "button_send", "sendbutton")
        private val COMPOSER_IDS = listOf(
            "entry", "input", "row_input_text", "message_input", "edit_text", "composer", "message"
        )
        private val SEND_WORDS = listOf("send", "পাঠান", "পাঠাও", "送出", "enviar")

        @Volatile
        private var instance: SendAccessibilityService? = null

        fun isRunning(): Boolean = instance != null

        /**
         * WhatsApp: open a wa.me link (text pre-filled) and tap Send.
         * Fire-and-forget from the caller's perspective; [onResult] is invoked on
         * the main thread with true only after the Send button was actually tapped.
         */
        fun send(ctx: Context, pkg: String, number: String, text: String, onResult: (Boolean) -> Unit) {
            val svc = instance
            if (svc == null) {
                LogStore.add(ctx, "অ্যাক্সেসিবিলিটি সার্ভিস চালু নেই")
                onResult(false)
                return
            }
            svc.handler.post { svc.dispatch(pkg, number, text, onResult) }
        }

        /**
         * Messenger: reopen the conversation through the notification's content
         * intent, type the reply, then tap Send.
         */
        fun sendViaIntent(
            ctx: Context,
            pkg: String,
            open: PendingIntent,
            text: String,
            onResult: (Boolean) -> Unit
        ) {
            val svc = instance
            if (svc == null) {
                LogStore.add(ctx, "অ্যাক্সেসিবিলিটি সার্ভিস চালু নেই")
                onResult(false)
                return
            }
            svc.handler.post { svc.dispatchOpen(pkg, open, text, onResult) }
        }
    }
}
