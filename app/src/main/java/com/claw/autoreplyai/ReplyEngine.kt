package com.claw.autoreplyai

import android.app.PendingIntent
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Collections
import java.util.Date
import java.util.Locale

/**
 * The brain. For every incoming WhatsApp message it:
 *  1. applies the user's hard filters (switch, contacts, hours, cooldown),
 *  2. asks the model what to do — reply, stay quiet, or hand it to the human,
 *  3. delivers the reply through the notification action, falling back to the
 *     accessibility route.
 */
object ReplyEngine {

    /** Alert the user after this many consecutive AI failures. */
    private const val FAIL_ALERT_AFTER = 3

    /** How many times a queued message may be retried while another reply runs. */
    private const val MAX_PENDING_TRIES = 8

    /** Character budget for the conversation history sent with each request. */
    private const val HISTORY_CHARS = 4_000

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Serialises every mutation of [pending] **together with** its persistence.
     *
     * Two threads touch this state at once — a new message arriving, and a scheduled
     * job waking up — and a `synchronized` map alone is not enough. `remove` followed
     * by `persistPending` could interleave with an `insert` and a second
     * `persistPending`, so the snapshot written last could be the *older* one: the
     * queue survives a process death, but short one reply. Holding one lock across
     * mutation + write means disk always matches memory.
     */
    private val queueLock = Any()

    /** Conversations with a reply in flight. Keyed by [ConversationKey]. */
    private val inFlight: MutableSet<String> = Collections.synchronizedSet(HashSet())

    fun onIncoming(
        ctx: Context,
        pkg: String,
        sender: String,
        message: String,
        isGroup: Boolean,
        phoneHint: String?,
        direct: DirectReplier.Handle?,
        contentIntent: PendingIntent? = null,
        postTime: Long = 0L,
        /**
         * The notification's own identity. Preferred over the sender name because a
         * name is not unique across apps — "Rahim" on WhatsApp and "Rahim" on
         * Messenger are different people sharing one bucket otherwise.
         */
        conversationIdentity: String? = null,
        keyOverride: ConversationKey? = null
    ) {
        val app = ctx.applicationContext
        val p = Prefs.get(app)
        val key = keyOverride
            ?: ConversationKey.of(
                pkg,
                phone = phoneHint,
                identity = conversationIdentity,
                sender = sender,
                countryCode = p.countryCode
            )
        val addr = key.storageKey

        if (!p.enabled) return
        if (message.isBlank()) return

        // Per-app switch: the user may want WhatsApp answers but not Messenger ones.
        if (!MessagingApps.enabledBy(p, pkg)) {
            log(app, "${MessagingApps.label(pkg)} বন্ধ রাখা আছে (সেটিংসে চালু করুন) — $sender")
            return
        }

        // Defensive: a call event should never reach here, but if another entry
        // point (accessibility, future integration) feeds one in, drop it early.
        if (CallEventFilter.isCallEvent(message)) {
            log(app, "কল ইভেন্ট বাদ (defensive) — $sender")
            return
        }

        if (isBlocked(p, sender)) {
            log(app, "কখনো রিপ্লাই দেব না লিস্টে আছে — $sender")
            return
        }

        if (isGroup && p.skipGroups) {
            log(app, "গ্রুপ মেসেজ বাদ — $sender")
            return
        }

        if (p.skipUnknown && looksLikeNumber(sender)) {
            log(app, "অচেনা নাম্বার — নিজে দেখুন — $sender")
            Notify.needsYou(
                app, sender, message,
                "এই নাম্বারটা আপনার কন্টাক্টে সেভ করা নেই, তাই AI উত্তর দেয়নি।"
            )
            digest(app, pkg, sender, message, DigestStore.ACTION_BLOCKED, "")
            return
        }

        if (p.hoursEnabled && !withinHours(p)) {
            log(app, "সময়সীমার বাইরে (${timeLabel(p.hourStart, p.hourStartMin)}–${timeLabel(p.hourEnd, p.hourEndMin)}) — $sender")
            digest(app, pkg, sender, message, DigestStore.ACTION_BLOCKED, "")
            return
        }

        if (p.onlyContacts && !p.moodEnabled) {
            val allowed = p.contactList
                .split('\n', ',', ';')
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
            if (allowed.none { sender.lowercase().contains(it) }) {
                log(app, "ফিল্টার লিস্টে নেই — $sender")
                digest(app, pkg, sender, message, DigestStore.ACTION_BLOCKED, "")
                return
            }
        }

        // Inside the cooldown: never drop the message. Remember the newest one and
        // answer it once the window closes — otherwise the contact gets a reply to
        // an older message and silence on the one they actually just sent, which
        // reads as the bot answering the wrong thing.
        val remaining = p.cooldownSec * 1000L - (System.currentTimeMillis() - p.lastReply(key))
        if (remaining > 0) {
            enqueue(app, key, Pending(pkg, sender, message, phoneHint, direct, contentIntent, postTime, key = key))
            log(app, "কুলডাউনে — $sender (নতুন মেসেজটা পরে উত্তর দেওয়া হবে)")
            schedulePending(app, key, remaining)
            return
        }

        if (!inFlight.add(addr)) {
            // Same reasoning as the cooldown — never lose the message, queue it.
            enqueue(app, key, Pending(pkg, sender, message, phoneHint, direct, contentIntent, postTime, key = key))
            log(app, "আগের রিপ্লাই চলছে — $sender (এই মেসেজটা পরে উত্তর দেওয়া হবে)")
            schedulePending(app, key, 3_000L)
            return
        }

        scope.launch {
            try {
                handle(app, p, key, pkg, sender, message, phoneHint, direct, contentIntent, postTime)
            } catch (e: Exception) {
                log(app, "ত্রুটি — $sender: ${e.message}")
            } finally {
                inFlight.remove(addr)
            }
        }
    }

    // ---------------------------------------------------- deferred replies

    /**
     * A message waiting for the cooldown to pass.
     *
     * [direct] and [contentIntent] are live objects — a notification action and a
     * PendingIntent — and neither can be written to disk. They are therefore only
     * restored on the in-process path. A copy recovered after a restart has to go
     * through the accessibility route instead, which is exactly what happens today
     * when the inline reply action is missing.
     */
    private class Pending(
        val pkg: String,
        val sender: String,
        val message: String,
        val phoneHint: String?,
        val direct: DirectReplier.Handle?,
        val contentIntent: PendingIntent?,
        val postTime: Long,
        /** When the message originally arrived — used to expire stale restored entries. */
        val queuedAt: Long = System.currentTimeMillis(),
        /**
         * Stable identity of the conversation this belongs to. Carried so a restored
         * entry keeps its own cooldown and memory bucket instead of falling back to
         * a name that may belong to somebody else on another app.
         */
        val key: ConversationKey
    )

    private val pending = HashMap<String, Pending>()

    /**
     * Conversations with a scheduled wake-up, so the same message is not queued to be
     * retried twice over. Holds storage keys, matching [pending].
     */
    private val pendingScheduled: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /**
     * Queue an entry and flush it to disk as one indivisible step. Always use this
     * instead of touching [pending] directly — see [queueLock].
     */
    private fun enqueue(app: Context, key: ConversationKey, value: Pending) {
        synchronized(queueLock) {
            pending[key.storageKey] = value
            persistPendingLocked(app)
        }
    }

    /**
     * Drop an entry and flush, atomically. Returns the entry that was removed so the
     * caller can act on what it actually owned rather than on whatever a concurrent
     * write may have left behind.
     */
    private fun dequeue(app: Context, key: ConversationKey): Pending? = synchronized(queueLock) {
        val removed = pending.remove(key.storageKey)
        persistPendingLocked(app)
        removed
    }

    private fun peek(key: ConversationKey): Pending? = synchronized(queueLock) { pending[key.storageKey] }

    private fun pendingKeys(): List<String> = synchronized(queueLock) { pending.keys.toList() }

    /**
     * Where the queue is mirrored so it survives the process being killed. Android
     * reclaims a backgrounded app freely; losing a queued reply to memory pressure
     * means the contact is simply never answered.
     */
    private const val PENDING_FILE = "pending_replies"
    private const val PENDING_KEY = "queue"

    /**
     * A restored entry older than this is not worth sending — the contact has waited
     * long enough that a reply now would be stranger than silence.
     */
    private const val PENDING_MAX_AGE_MS = 15 * 60 * 1000L

    /**
     * Write the queue to disk. Caller must already hold [queueLock] — this exists as a
     * locked variant so mutation and persistence can be one atomic step.
     */
    private fun persistPendingLocked(app: Context) {
        try {
            val arr = JSONArray()
            for ((storedKey, v) in pending.entries.toList()) {
                arr.put(
                    JSONObject()
                        .put("addr", storedKey)
                        .put("sender", v.sender)
                        .put("pkg", v.pkg)
                        .put("message", v.message)
                        .put("phoneHint", v.phoneHint ?: "")
                        .put("postTime", v.postTime)
                        .put("queuedAt", v.queuedAt)
                        .put("convPkg", v.key.packageName)
                        .put("convId", v.key.conversationId)
                )
            }
            app.getSharedPreferences(PENDING_FILE, Context.MODE_PRIVATE)
                .edit().putString(PENDING_KEY, arr.toString()).apply()
        } catch (e: Exception) {
            log(app, "পেন্ডিং কিউ সেভ করা যায়নি: ${e.message}")
        }
    }

    /**
     * Rebuild the queue after a restart. Routeless entries are re-queued without
     * [DirectReplier.Handle] and [PendingIntent]; `deliverReply` falls through to the
     * accessibility route for them, which is the same thing it does when a
     * notification offers no inline reply.
     */
    fun restorePending(app: Context) {
        try {
            val raw = app.getSharedPreferences(PENDING_FILE, Context.MODE_PRIVATE)
                .getString(PENDING_KEY, null) ?: return
            val arr = JSONArray(raw)
            val now = System.currentTimeMillis()
            var restored = 0
            var expired = 0

            synchronized(queueLock) {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val sender = o.optString("sender")
                    val message = o.optString("message")
                    val queuedAt = o.optLong("queuedAt", 0L)
                    if (sender.isBlank() || message.isBlank()) continue

                    if (queuedAt <= 0L || now - queuedAt > PENDING_MAX_AGE_MS) {
                        expired++
                        continue
                    }

                    // Prefer the conversation identity the entry was written with. Falling
                    // back to an address reconstructed from the sender name would silently
                    // re-key the entry, and on a name that exists in two apps that means
                    // delivering into the wrong conversation.
                    val convId = o.optString("convId")
                    val convPkg = o.optString("convPkg")
                    val key = if (convId.isNotBlank() && convPkg.isNotBlank()) {
                        ConversationKey(convPkg, convId)
                    } else {
                        ConversationKey.of(
                            o.optString("pkg"),
                            phone = o.optString("phoneHint").takeIf { it.isNotBlank() },
                            sender = sender,
                            countryCode = Prefs.get(app).countryCode
                        )
                    }

                    // The stored `addr` is only a hint. It is a hash of an identity that
                    // may no longer be what `key` resolves to — a v1.51 entry predates
                    // this field, and a v1.52 entry written before the country code was
                    // set resolved a bare local number differently. Keying the queue by
                    // a stale `addr` while cooldown and memory lookups use `key` would
                    // leave the entry unreachable by its own conversation, so derive the
                    // address from the key and never trust the file.
                    val addr = key.storageKey
                    if (pending.containsKey(addr)) continue

                    pending[addr] = Pending(
                        pkg = o.optString("pkg"),
                        sender = sender,
                        message = message,
                        phoneHint = o.optString("phoneHint").takeIf { it.isNotBlank() },
                        direct = null,
                        contentIntent = null,
                        postTime = o.optLong("postTime", 0L),
                        queuedAt = queuedAt,
                        key = key
                    )
                    restored++
                }
            }

            if (restored > 0 || expired > 0) {
                log(
                    app,
                    "পেন্ডিং কিউ ফিরে এলো — $restored টা পাঠানো হবে" +
                            if (expired > 0) ", $expired টা অনেক পুরনো তাই বাদ" else ""
                )
            }
            // Nothing usable left; clear the file so it is not re-read next boot.
            if (restored == 0) synchronized(queueLock) { persistPendingLocked(app) }

            val p = Prefs.get(app)
            for (addr in pendingKeys()) {
                val v = synchronized(queueLock) { pending[addr] } ?: continue
                val remaining = p.cooldownSec * 1000L - (now - p.lastReply(v.key))
                schedulePending(app, v.key, if (remaining > 0) remaining else 1_000L)
            }
        } catch (e: Exception) {
            log(app, "পেন্ডিং কিউ ফেরানো যায়নি: ${e.message}")
        }
    }

    private fun schedulePending(app: Context, key: ConversationKey, delayMs: Long, attempt: Int = 0) {
        val addr = key.storageKey
        if (!pendingScheduled.add(addr)) return
        scope.launch {
            delay(delayMs.coerceAtLeast(500L) + 500L)
            pendingScheduled.remove(addr)

            // Read but do NOT remove yet — the message must survive a failed attempt.
            val msg = peek(key) ?: return@launch
            val p = Prefs.get(app)
            if (!p.enabled) {
                dequeue(app, key)
                return@launch
            }

            // Give up on anything that has been waiting longer than a reply is worth.
            if (System.currentTimeMillis() - msg.queuedAt > PENDING_MAX_AGE_MS) {
                dequeue(app, key)
                log(app, "পেন্ডিং মেসেজ বাদ (অনেক দেরি হয়ে গেছে) — ${msg.sender}")
                return@launch
            }

            // Still inside the cooldown? wait it out.
            val remaining = p.cooldownSec * 1000L - (System.currentTimeMillis() - p.lastReply(key))
            if (remaining > 0) {
                schedulePending(app, key, remaining, attempt)
                return@launch
            }

            // Another reply is still running? try again shortly, a bounded number of times.
            if (!inFlight.add(addr)) {
                if (attempt < MAX_PENDING_TRIES) {
                    schedulePending(app, key, 3_000L, attempt + 1)
                } else {
                    dequeue(app, key)
                    log(app, "পরে উত্তর দেওয়ার চেষ্টা ছেড়ে দেওয়া হলো — ${msg.sender}")
                }
                return@launch
            }

            dequeue(app, key)
            try {
                handle(app, p, key, msg.pkg, msg.sender, msg.message, msg.phoneHint, msg.direct, msg.contentIntent, msg.postTime)
            } catch (e: Exception) {
                log(app, "পরে উত্তর দিতে গিয়ে ত্রুটি — ${msg.sender}: ${e.message}")
            } finally {
                inFlight.remove(addr)
            }
        }
    }

    private suspend fun handle(
        app: Context,
        p: Prefs,
        key: ConversationKey,
        pkg: String,
        sender: String,
        message: String,
        phoneHint: String?,
        direct: DirectReplier.Handle?,
        contentIntent: PendingIntent?,
        postTime: Long
    ) {
        // Voice notes arrive as a placeholder ("🎤 Voice message"). Turn them into
        // text first, otherwise the model is answering the word "Voice message".
        val incoming = resolveIncomingText(app, p, pkg, sender, message, postTime)
        val text = incoming.text
        if (text.isBlank()) return

        // What the model sees is an instruction for a photo, which would read
        // strangely in the history, the digest and the log. Keep a short label for
        // those, and send the real thing to the model.
        val display = if (incoming.imageBase64 != null) "[ছবি]" else text
        val fromPhoto = incoming.imageBase64 != null

        if (p.moodEnabled) {
            handleMood(
                app, p, key, pkg, sender, display, phoneHint, direct, contentIntent,
                incoming.fromVoice, incoming.imageBase64
            )
            return
        }

        // Some messages must never be answered by a machine on the user's behalf,
        // however well the model might phrase it. Catching them here costs nothing and
        // removes any chance of a confident-sounding wrong answer.
        ReplyValidator.shouldHoldBeforeReply(text)?.let { why ->
            log(app, "🛑 নিজে দেখুন — $sender: $why")
            ChatMemory.add(app, key, "user", display, legacyName = sender)
            Notify.needsYou(app, sender, display.take(120), "$why — তাই AI উত্তর দেয়নি।")
            digest(app, pkg, sender, display, DigestStore.ACTION_HELD, "")
            return
        }

        val messages = ArrayList<AiClient.Msg>()
        val contactContext = ContactContext.forContact(app, sender)
        val close = ContactContext.isClose(app, sender)
        messages.add(
            AiClient.Msg(
                "system",
                buildSystemPrompt(p, key, sender, contactContext, incoming.fromVoice, fromPhoto, close)
            )
        )
        messages.addAll(trimmedHistory(app, key, sender))
        messages.add(AiClient.Msg("user", text, incoming.imageBase64))

        val raw = try {
            askAi(app, messages)
        } catch (e: AiClient.BlankAnswerException) {
            // The gateway returned reasoning but no answer. Rather than shipping the
            // model's internal monologue, ask again — one retry recovers most of these.
            log(app, "খালি উত্তর, আবার চেষ্টা করছি — $sender (${e.message})")
            try {
                askAi(app, messages)
            } catch (retry: Exception) {
                noteApiFailure(app, p, retry.message ?: "অজানা ত্রুটি")
                return
            }
        } catch (e: Exception) {
            noteApiFailure(app, p, e.message ?: "অজানা ত্রুটি")
            return
        }
        p.failStreak = 0

        if (raw.isBlank()) {
            log(app, "AI খালি উত্তর দিয়েছে — $sender")
            return
        }

        val decision = if (p.smartTriage) ReplyDecision.parse(raw) else ReplyDecision.plain(raw)
        val preview = display.replace("\n", " ").take(70)

        when (decision.action) {
            ReplyDecision.Action.IGNORE -> {
                log(app, "উত্তর দেওয়া হলো না (দরকার নেই) — $sender: $preview")
                // Record it anyway: a history with holes is what makes the next
                // reply look like it came out of nowhere.
                ChatMemory.add(app, key, "user", display, legacyName = sender)
                digest(app, pkg, sender, display, DigestStore.ACTION_IGNORED, "")
                return
            }

            ReplyDecision.Action.HOLD -> {
                if (p.holdOnEmotional) {
                    log(app, "⚠️ নিজে উত্তর দিন — $sender: $preview")
                    ChatMemory.add(app, key, "user", display, legacyName = sender)
                    Notify.needsYou(
                        app, sender, display,
                        "ব্যক্তিগত বা গুরুত্বপূর্ণ মনে হয়েছে, তাই AI উত্তর দেয়নি।"
                    )
                    digest(app, pkg, sender, display, DigestStore.ACTION_HELD, "")
                    return
                }
                log(app, "আবেগপূর্ণ মেসেজ, তবে সেটিং অনুযায়ী উত্তর দেওয়া হচ্ছে — $sender")
            }

            ReplyDecision.Action.REPLY -> Unit
        }

        val reply = fitReply(decision.reply, p.replyMaxChars)
        if (reply.isBlank()) {
            log(app, "উত্তর খালি এলো — $sender")
            return
        }

        // Deterministic gate. The prompt already forbids promises, but a prompt is a
        // request — this is the step that does not depend on the model cooperating.
        val verdict = ReplyValidator.validate(reply, text)
        if (!verdict.allowed) {
            val why = verdict.reason ?: "সন্দেহজনক উত্তর"
            log(app, "🛑 নিজে দেখুন — $sender: $why\n    ▶ আটকে রাখা: ${reply.replace("\n", " ").take(90)}")
            ChatMemory.add(app, key, "user", display, legacyName = sender)
            Notify.needsYou(app, sender, reply.take(120), "$why — তাই AI নিজে পাঠায়নি।")
            digest(app, pkg, sender, display, DigestStore.ACTION_HELD, reply)
            return
        }

        val finalText = if (p.signature.isNotBlank()) "$reply\n${p.signature}" else reply

        // Greetings and one-word replies go out quickly; everything else waits a
        // random moment inside the configured range so the timing looks human.
        val quick = decision.style != ReplyDecision.Style.NORMAL
        val waitMs = p.replyDelayMs(quick)
        if (waitMs > 0) delay(waitMs)

        deliverReply(app, p, key, pkg, sender, display, finalText, reply, contactContext.length, phoneHint, direct, contentIntent)
    }

    /**
     * An incoming message, plus whether it came from a (possibly imperfect)
     * transcript, and the photo behind it when there is one.
     */
    private data class Incoming(
        val text: String,
        val fromVoice: Boolean,
        val imageBase64: String? = null
    )

    /**
     * Replaces a voice-note or photo placeholder with something the model can use.
     * Returns blank text when the media cannot be read — a reply to the words
     * "Voice message" or "Photo" helps nobody, so the message is skipped and the
     * reason is logged instead.
     */
    private suspend fun resolveIncomingText(
        app: Context,
        p: Prefs,
        pkg: String,
        sender: String,
        message: String,
        postTime: Long
    ): Incoming {
        // ---- voice note ----
        if (p.transcribeVoice && VoiceTranscriber.looksLikeVoiceNote(message)) {
            if (!MessagingApps.canTranscribeVoice(pkg)) {
                log(app, "ভয়েস মেসেজ — ${MessagingApps.label(pkg)}-এর অডিও পড়া যায় না, তাই উত্তর দেওয়া হচ্ছে না — $sender")
                Notify.needsYou(
                    app, sender, "ভয়েস মেসেজ",
                    "${MessagingApps.label(pkg)}-এর ভয়েস মেসেজ পড়া যায় না, তাই উত্তর দেওয়া হয়নি।"
                )
                digest(app, pkg, sender, message, DigestStore.ACTION_BLOCKED, "")
                return Incoming("", fromVoice = true)
            }
            log(app, "ভয়েস মেসেজ পেয়েছি — ট্রান্সক্রিপ্ট করছি — $sender")
            val transcript = VoiceTranscriber.transcribe(app, p, pkg, postTime)
            if (transcript.isNullOrBlank()) {
                log(app, "ভয়েস মেসেজ পড়া গেল না — $sender")
                Notify.needsYou(
                    app, sender, "ভয়েস মেসেজ",
                    "ভয়েস মেসেজটা পড়া যায়নি, তাই উত্তর দেওয়া হয়নি।"
                )
                digest(app, pkg, sender, message, DigestStore.ACTION_BLOCKED, "")
                return Incoming("", fromVoice = true)
            }
            log(app, "ভয়েস ট্রান্সক্রিপ্ট ($sender): ${transcript.take(90)}")
            return Incoming(transcript, fromVoice = true)
        }

        // ---- photo ----
        if (p.understandPhotos && ImageReader.looksLikePhoto(message)) {
            // WhatsApp posts the notification and writes the file in parallel, so a
            // single immediate look can race the download. Give it a moment.
            var img = ImageReader.loadLatestBase64(app, postTime)
            if (img == null) {
                delay(2_500L)
                img = ImageReader.loadLatestBase64(app, postTime)
            }
            if (img == null) {
                // Almost always one of two things: WhatsApp's "Media auto-download"
                // is off for photos, so nothing ever reaches disk, or the photo is
                // older than the freshness window.
                log(
                    app,
                    "ছবি পড়া গেল না — $sender। WhatsApp-এ Media auto-download চালু আছে কি? " +
                            "(WhatsApp → Settings → Storage and data → Media auto-download → Photos)"
                )
                Notify.needsYou(
                    app, sender, "ছবি",
                    "ছবিটা ফোনে নামেনি, তাই দেখা যায়নি — উত্তর দেওয়া হয়নি।"
                )
                digest(app, pkg, sender, message, DigestStore.ACTION_BLOCKED, "")
                return Incoming("", fromVoice = false)
            }
            // The model needs words alongside the picture: what arrived, and what is
            // being asked of it.
            val prompt = "কন্টাক্ট একটি ছবি পাঠিয়েছে। ছবিটা দেখে স্বাভাবিকভাবে ছোট উত্তর দাও। " +
                    "ছবিতে কী আছে সেটা নিয়ে নিশ্চিত না হলে কিছু বানিয়ে বলবে না।"
            return Incoming(prompt, fromVoice = false, imageBase64 = img)
        }

        return Incoming(message, fromVoice = false)
    }

    /**
     * Hard ceiling on a reply. The prompt asks for short messages, but a model that
     * ignores it produces the customer-service paragraphs that made the bot obvious,
     * so trim at a sentence boundary rather than sending a wall of text.
     */
    private fun fitReply(text: String, max: Int): String {
        val t = text.trim()
        if (max <= 0 || t.length <= max) return t

        val window = t.take(max + 1)
        // Prefer a sentence end, then a word break, then the raw cut.
        val sentence = window.lastIndexOfAny(charArrayOf('।', '.', '!', '?', '\n'))
        if (sentence >= max / 2) return window.take(sentence + 1).trim()

        val space = window.lastIndexOf(' ')
        if (space >= max / 2) return window.take(space).trim()

        return window.take(max).trim()
    }

    private fun digest(        app: Context,
        pkg: String,
        sender: String,
        message: String,
        action: String,
        reply: String
    ) {
        DigestStore.record(
            app,
            DigestStore.Event(
                at = System.currentTimeMillis(),
                pkg = pkg,
                sender = sender,
                message = message,
                action = action,
                reply = reply
            )
        )
    }

    /**
     * Mood/gatekeeper mode: the AI acts on behalf of the user.
     * It greets, asks how-are-you, announces the user's activity, and
     * triggers an alarm if the message is urgent.
     * Applies to ALL contacts (the contact filter is bypassed).
     */
    private suspend fun handleMood(
        app: Context,
        p: Prefs,
        key: ConversationKey,
        pkg: String,
        sender: String,
        message: String,
        phoneHint: String?,
        direct: DirectReplier.Handle?,
        contentIntent: PendingIntent?,
        fromVoice: Boolean = false,
        imageBase64: String? = null
    ) {
        val messages = ArrayList<AiClient.Msg>()
        messages.add(
            AiClient.Msg(
                "system",
                buildGatekeeperPrompt(p, sender, fromVoice, imageBase64 != null)
            )
        )
        messages.addAll(trimmedHistory(app, key, sender))
        messages.add(AiClient.Msg("user", message, imageBase64))

        val raw = try {
            askAi(app, messages)
        } catch (e: Exception) {
            noteApiFailure(app, p, e.message ?: "অজানা ত্রুটি")
            return
        }
        p.failStreak = 0

        if (raw.isBlank()) {
            log(app, "AI খালি উত্তর দিয়েছে (mood) — $sender")
            return
        }

        val gd = try {
            GatekeeperDecision.parse(raw)
        } catch (e: Exception) {
            log(app, "gatekeeper JSON পার্স করা যায়নি — $sender: ${raw.take(80)}")
            return
        }

        if (gd.action == GatekeeperDecision.Action.ALARM) {
            log(app, "🚨 জরুরি — ${p.assistantName} Ali-কে ডাকছে — $sender: ${message.replace("\n", " ").take(60)}")
            Alarm.trigger(app, "$sender: ${gd.reply.take(80)}")
        }

        val reply = fitReply(gd.reply, p.replyMaxChars)
        if (reply.isBlank()) {
            log(app, "gatekeeper উত্তর খালি — $sender")
            return
        }

        // Small delay so it doesn't look instant
        delay(p.replyDelayMs(quick = false).coerceAtMost(2_000L))
        deliverReply(app, p, key, pkg, sender, message, reply, reply, 0, phoneHint, direct, contentIntent)
    }

    private fun deliverReply(
        app: Context,
        p: Prefs,
        key: ConversationKey,
        pkg: String,
        sender: String,
        message: String,
        finalText: String,
        reply: String,
        contextChars: Int,
        phoneHint: String?,
        direct: DirectReplier.Handle?,
        contentIntent: PendingIntent?
    ) {
        // Approval mode: never send on our own — show the draft and let the user
        // decide. Nothing else changes, so approving later uses the same routes.
        if (p.approvalMode) {
            val shown = Approval.request(
                app,
                Approval.Draft(key, pkg, sender, message, finalText, phoneHint, direct, contentIntent)
            )
            if (shown) {
                log(app, "অনুমোদনের অপেক্ষায় — $sender: ${reply.replace("\n", " ").take(60)}")
                digest(app, pkg, sender, message, DigestStore.ACTION_APPROVAL, reply)
            } else {
                log(app, "অনুমোদন দেখানো যায়নি, তাই কিছু পাঠানো হলো না — $sender")
            }
            return
        }

        // Route 1: the notification's own reply action (works while locked, no
        // phone number or open chat needed). Every supported app posts one.
        if (direct != null && DirectReplier.send(app, direct, finalText)) {
            onSent(app, p, key, pkg, sender, message, reply, contextChars)
            return
        }
        if (direct == null) {
            log(app, "নোটিফিকেশনে reply অ্যাকশন নেই — বিকল্প পথে যাচ্ছি")
        }

        // Route 2: open the chat and tap Send (needs an unlocked screen).
        val deferred = CompletableDeferred<Boolean>()

        if (MessagingApps.supportsPhoneLink(pkg)) {
            // WhatsApp: a wa.me link both opens the chat and pre-fills the text.
            val rawNumber = phoneHint?.takeIf { it.isNotBlank() }
                ?: ContactResolver.numberForName(app, sender)
            if (rawNumber.isNullOrBlank()) {
                log(app, "নাম্বার পাওয়া যায়নি — $sender (কন্টাক্ট পারমিশন দিন)")
                return
            }
            val waNumber = normalizeNumber(rawNumber, p.countryCode)
            if (waNumber.isNullOrBlank()) {
                log(app, "নাম্বার নরমালাইজ করা যায়নি — $rawNumber")
                return
            }
            SendAccessibilityService.send(app, pkg, waNumber, finalText) { deferred.complete(it) }
        } else {
            // Messenger: no phone-number link exists, so reopen the conversation
            // through the notification's own content intent and type the reply in.
            if (contentIntent == null) {
                log(app, "চ্যাট খোলার উপায় নেই — $sender (নোটিফিকেশনে reply অ্যাকশন পাওয়া যায়নি)")
                return
            }
            SendAccessibilityService.sendViaIntent(app, pkg, contentIntent, finalText) {
                deferred.complete(it)
            }
        }

        scope.launch {
            val ok = withTimeoutOrNull(35_000L) { deferred.await() } ?: false
            if (ok) {
                onSent(app, p, key, pkg, sender, message, reply, contextChars)
            } else {
                // The reply was written and the contact got nothing. Telling the user
                // is the whole point of the app being trustworthy: on 28 Sep a real
                // reply to "মনের মাঝে তুমি" was lost to a locked screen and the log
                // entry was the only trace of it.
                val why = if (isScreenLocked(app))
                    "ফোন লক ছিল, তাই পাঠানো যায়নি।"
                else
                    "চ্যাট খুলে পাঠানো যায়নি।"
                log(app, "✗ পাঠানো যায়নি → $sender ($why)")
                Notify.needsYou(app, sender, reply.take(80), "$why এটা নিজে পাঠিয়ে দিন।")
                digest(app, pkg, sender, message, DigestStore.ACTION_FAILED, reply)
            }
        }
    }

    /** True when the keyguard is up, which the accessibility route cannot get past. */
    private fun isScreenLocked(app: Context): Boolean = try {
        val km = app.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        km.isKeyguardLocked
    } catch (e: Exception) {
        false
    }

    private fun onSent(
        app: Context,
        p: Prefs,
        key: ConversationKey,
        pkg: String,
        sender: String,
        message: String,
        reply: String,
        contextChars: Int
    ) {
        ChatMemory.add(app, key, "user", message, legacyName = sender)
        ChatMemory.add(app, key, "assistant", reply, legacyName = sender)
        p.setLastReply(key, System.currentTimeMillis())
        // Remember what we just sent so we don't reply to ourselves when the chat
        // app echoes it back as a new notification. Scoped to this conversation,
        // otherwise a short generic reply suppresses a real message elsewhere.
        SentMessageTracker.record(key, reply)
        digest(app, pkg, sender, message, DigestStore.ACTION_REPLIED, reply)
        // Both sides of the exchange are logged so a bad transcript is visible
        // right next to the reply it caused, instead of two lines apart.
        log(
            app,
            "✓ রিপ্লাই → $sender · কন্টেক্সট $contextChars অক্ষর\n" +
                    "    ◀ এসেছিল: ${message.replace("\n", " ").take(90)}\n" +
                    "    ▶ গেল: ${reply.replace("\n", " ").take(90)}"
        )
    }

    /**
     * Keep the newest history entries within a character budget. A long chat plus a
     * 3 KB contact context can overflow a small model's window, and an overflowing
     * model silently starts ignoring the system prompt — which looks exactly like
     * "it isn't understanding the context".
     */
    private fun trimmedHistory(app: Context, key: ConversationKey, sender: String): List<AiClient.Msg> {
        val history = ChatMemory.history(app, key, legacyName = sender)
        if (history.isEmpty()) return history

        val out = ArrayList<AiClient.Msg>()
        var budget = HISTORY_CHARS
        for (i in history.indices.reversed()) {
            val msg = history[i]
            if (out.isNotEmpty() && msg.content.length > budget) break
            budget -= msg.content.length
            out.add(0, msg)
        }
        return out
    }

    // ------------------------------------------------------------- prompting

    /**
     * Tries the prioritized provider list until one succeeds.
     * On fallback success, automatically switches the active provider
     * so subsequent requests use the working one.
     */
    private suspend fun askAi(app: Context, messages: List<AiClient.Msg>): String {
        val allProviders = AiProviderStore.all(app)
        val ordered = AiProviderStore.prioritized(app)
        if (ordered.isEmpty()) throw java.io.IOException("কোনো AI প্রোভাইডার সেট আপ নেই")

        var lastError: Exception? = null
        for ((i, provider) in ordered.withIndex()) {
            try {
                val result = AiClient.chat(provider.baseUrl, provider.apiKey, provider.model, messages)
                if (result.isNotBlank()) {
                    if (i > 0) {
                        // Fallback succeeded — switch to this provider for future requests
                        log(app, "ফলব্যাক সফল — ${provider.name} (${provider.model}) এখন থেকে ব্যবহার হবে")
                        val originalIndex = allProviders.indexOfFirst {
                            it.name == provider.name && it.baseUrl == provider.baseUrl
                        }.coerceAtLeast(0)
                        AiProviderStore.setSelected(app, originalIndex)
                        val p = Prefs.get(app)
                        p.baseUrl = provider.baseUrl
                        p.apiKey = provider.apiKey
                        p.model = provider.model
                    }
                    return result
                }
                lastError = java.io.IOException("${provider.name} খালি উত্তর দিয়েছে")
                // This branch used to be silent, which is why a provider that answered
                // HTTP 200 with an empty body six times in a row showed up only as a
                // rising "টানা N বার" counter with no line naming the culprit.
                log(app, "API খালি উত্তর — ${provider.name} (${provider.model})")
                if (i < ordered.lastIndex) delay(1_500L)
            } catch (e: Exception) {
                lastError = e
                val reason = e.message ?: "অজানা ত্রুটি"
                log(app, "API ব্যর্থ — ${provider.name}: $reason")
                if (i < ordered.lastIndex) delay(1_500L)
            }
        }
        throw lastError ?: java.io.IOException("সব প্রোভাইডার ব্যর্থ")
    }

    private fun noteApiFailure(app: Context, p: Prefs, reason: String) {
        val streak = p.failStreak + 1
        p.failStreak = streak
        log(app, "API ব্যর্থ (টানা $streak বার): $reason")
        // Alert at the threshold and then every few failures after it. Firing only on
        // `streak == 3` meant a provider that recovered at 3 and died again later told
        // the user nothing the second time.
        if (p.failAlert && streak >= FAIL_ALERT_AFTER && streak % FAIL_ALERT_AFTER == 0) {
            Notify.botProblem(app, reason)
        }
    }

    /**
     * True when a chat title is a phone number rather than a saved name — WhatsApp
     * shows "+8801XXXXXXXXX" for anyone not in contacts.
     */
    private fun looksLikeNumber(sender: String): Boolean {
        val digits = sender.count { it.isDigit() }
        if (digits < 7) return false
        // Allow the punctuation WhatsApp puts in a formatted number, and nothing else.
        return sender.all { it.isDigit() || it in " +-()\u00a0" }
    }

    private fun isBlocked(p: Prefs, sender: String): Boolean {        val s = sender.lowercase()
        return p.neverReply
            .split('\n', ',', ';')
            .map { it.trim().lowercase() }
            .any { it.isNotEmpty() && s.contains(it) }
    }

    private fun buildSystemPrompt(
        p: Prefs,
        key: ConversationKey,
        sender: String,
        contactContext: String,
        fromVoice: Boolean = false,
        fromPhoto: Boolean = false,
        closeContact: Boolean = false
    ): String {
        val sb = StringBuilder()
        sb.append(p.persona.trim()).append("\n\n")
        sb.append("কন্টাক্টের নাম: ").append(sender).append('\n')
        sb.append("এখন সময়: ").append(clockText()).append('\n')

        // Office hours awareness
        if (p.hoursEnabled) {
            val atOffice = withinHours(p)
            val startLabel = timeLabel(p.hourStart, p.hourStartMin)
            val endLabel = timeLabel(p.hourEnd, p.hourEndMin)
            if (atOffice) {
                sb.append("অফিস টাইম: $startLabel থেকে $endLabel। এখন আমি অফিসে আছি, ব্যস্ত থাকতে পারি।\n")
            } else {
                sb.append("অফিস টাইম: $startLabel থেকে $endLabel। এখন আমি অফিসে না, ব্যক্তিগত সময়।\n")
            }
        }

        val last = p.lastReply(key)
        if (last > 0) {
            val minutes = (System.currentTimeMillis() - last) / 60_000L
            if (minutes > 0) {
                sb.append("এই কন্টাক্টের সাথে শেষ কথা হয়েছিল ").append(minutes).append(" মিনিট আগে।\n")
            }
        }

        // Only THIS contact's notes go in. Other contacts' notes are never loaded,
        // so nothing about them can leak no matter what the model is asked.
        if (contactContext.isNotBlank()) {
            sb.append("\nএই কন্টাক্ট সম্পর্কে যা জানো:\n")
            sb.append(contactContext).append('\n')
        }

        if (p.safetyRule.isNotBlank()) {
            sb.append('\n').append(p.safetyRule.trim()).append('\n')
        }

        // Register comes before language/voice rules: getting the pronoun wrong is
        // the fastest way to sound either rude or creepily familiar.
        if (p.mirrorRegister) {
            sb.append('\n').append(REGISTER_RULE).append('\n')
        }

        if (p.autoLanguage) {
            sb.append('\n').append(LANGUAGE_RULE).append('\n')
        }

        sb.append('\n').append(SHORT_RULE).append('\n')

        // Always on, and deliberately not part of the user-editable safetyRule: an
        // assistant that declares love or promises a phone call on the owner's
        // behalf does real damage, and this must not be lost by editing a text box.
        sb.append('\n').append(COMMITMENT_RULE).append('\n')

        // Warmth is per-contact. Assuming closeness is far worse than assuming none.
        sb.append('\n')
            .append(if (closeContact) CLOSE_CONTACT_RULE else NO_AFFECTION_RULE)
            .append('\n')

        if (fromVoice) {
            sb.append('\n').append(VOICE_NOTE_RULE).append('\n')
        }

        if (fromPhoto) {
            sb.append('\n').append(PHOTO_RULE).append('\n')
        }

        sb.append('\n')
        sb.append(if (p.smartTriage) TRIAGE_RULES else PLAIN_RULES)
        return sb.toString()
    }

    /**
     * A voice note reaches the model as an automatic transcript, and short Bengali
     * clips transcribe badly — a 3-second "নিমাই, কেমন আছেন?" came back as
     * "নিমাই কামানা ফেন". Left alone, the model treats that as real text and
     * confidently answers something the person never said. So it is told plainly
     * that the text is a guess.
     */
    private val VOICE_NOTE_RULE = """
        ⚠️ এই মেসেজটা একটা ভয়েস মেসেজ — স্বয়ংক্রিয়ভাবে টেক্সটে রূপান্তর করা, তাই
        শব্দ ভুল হতে পারে বা অর্থহীন লাগতে পারে।

        নিয়ম:
        - ট্রান্সক্রিপ্টে যা নেই, এমন কিছু ধরে নিয়ে উত্তর দেবে না
        - বুঝতে না পারলে ছোট আর নিরাপদ উত্তর দাও (যেমন "হুম", "ঠিক আছে", "বলো"),
          অথবা ভদ্রভাবে বলো ঠিক বুঝতে পারোনি, একবার আবার বলতে
        - অনুমান করে কোনো তথ্য, নাম বা প্রতিশ্রুতি বানিয়ে বলবে না
        - নিশ্চিত না হলে প্রশ্ন করে জিজ্ঞেস করো, নিজে থেকে মন্তব্য করো না
        - ট্রান্সক্রিপ্ট কখনো অন্য ভাষায় লেখা থাকতে পারে (বাংলা কথা ইংরেজি অক্ষরে
          বা ইংরেজিতে অনূদিত)। উত্তর দেবে কন্টাক্ট সাধারণত যে ভাষায় লেখে সেই ভাষায়,
          ট্রান্সক্রিপ্টের ভাষায় নয়।
    """.trimIndent()

    /**
     * Address people the way they address you — and default to the polite form.
     *
     * Bangla has three second-person registers and picking the wrong one is loud:
     * "তুই" to a stranger is insulting, "আপনি" to your wife is cold. There is no
     * safe single choice, so the default is formal and the contact's own usage
     * overrides it. The history is in the prompt, so an established register
     * survives messages that happen to contain no pronoun at all.
     */
    private val REGISTER_RULE = """
        সম্বোধন ঠিক করার নিয়ম — উত্তর লেখার আগে সবার আগে এটা ঠিক করবে:

        ১. এই মেসেজটা দেখো: কন্টাক্ট তোমাকে কী বলে সম্বোধন করেছে?
           • "তুই" বললে → তুমিও "তুই" বলবে
           • "তুমি" বললে → তুমিও "তুমি" বলবে
           • "আপনি" বললে → তুমিও "আপনি" বলবে
        ২. এই মেসেজে কোনো সম্বোধন না থাকলে আগের চ্যাট দেখো — ওখানে যে সম্বোধন
           চলে আসছে, সেটাই ধরে রাখবে।
        ৩. কোথাও কোনো সম্বোধন না পেলে "আপনি" দিয়ে উত্তর দেবে। এটাই ডিফল্ট।
        ৪. ক্রিয়াপদও সম্বোধনের সাথে মিলিয়ে দাও —
           তুই → আছিস, খেয়েছিস, আসিস  ·  তুমি → আছো, খেয়েছো, আসো
           আপনি → আছেন, খেয়েছেন, আসেন
        ৫. নিশ্চিত না হলে "আপনি"। ভুল করে বেশি ঘনিষ্ঠ হয়ে যাওয়ার চেয়ে
           বেশি ভদ্র হয়ে যাওয়া অনেক ভালো।
        ৬. একবার কোনো কন্টাক্টের সম্বোধন ঠিক হলে সেটাই ধরে রাখবে —
           মাঝে মাঝে বদলাবে না।

        উদাহরণ:
        • কন্টাক্ট: "খেয়েছেন?"            → "হ্যাঁ, খেয়েছি। আপনি খেয়েছেন?"
        • কন্টাক্ট: "তুমি কেমন আছো?"      → "ভালো আছি, তুমি কেমন আছো?"
        • কন্টাক্ট: "তুই কেমন আছিস?"      → "ভালো আছি, তুই কেমন আছিস?"
    """.trimIndent()

    /**
     * The hard limits on speaking for Ali. Always on, whatever the contact.
     *
     * These are about *actions* — a promise is something Ali then has to do, and a
     * made-up fact is a lie. Neither becomes acceptable because the contact is close.
     */
    private val COMMITMENT_RULE = """
        ⛔ কখনো যা করা যাবে না (এর কোনো ব্যতিক্রম নেই):

        ১. কোনো প্রতিশ্রুতি দেবে না। ফোন করা, বাসায় যাওয়া, দেখা করা, ঘুরতে যাওয়া,
           টাকা বা কোনো কিছু দেওয়া — কিছুই করার কথা দেবে না।

        ২. কোনো সময়, তারিখ বা স্থান মেনে নেবে না। ("আজ রাতে আসবো", "কাল দেখা হবে",
           "এখন বের হচ্ছি" — এগুলো কখনো নয়।)

        ৩. বিয়ে বা ভবিষ্যৎ নিয়ে কোনো কথা দেবে না।

        ৪. কারো সম্পর্কে এমন কিছু বলবে না যা তুমি জানো না। অনুমান করে কোনো
           ঘটনা, সিদ্ধান্ত বা মত তৈরি করবে না। মিথ্যা বলবে না, বাড়িয়ে বলবে না।

        কেউ এসব চাইলে ছোট করে বলবে: "এটা আমার নিজে বলা উচিত, পরে বলবো।"
        আর সাথে সাথে Ali-কে জানাবে, যাতে সে নিজে উত্তর দিতে পারে।
    """.trimIndent()

    /**
     * For contacts the owner has NOT marked as close. Declaring love on someone's
     * behalf is the single most damaging thing this app has done — it happened to an
     * unsaved number, so the default is a flat refusal.
     */
    private val NO_AFFECTION_RULE = """
        ⛔ ভালোবাসা বা প্রেম: এই কন্টাক্টের সাথে Ali-র সম্পর্ক কেমন, তা তুমি জানো না।
        তাই নিজের পক্ষ থেকে ভালোবাসা বা প্রেম প্রকাশ করবে না — "ভালোবাসি",
        "অনেক ভালোবাসি", "তোমাকে ছাড়া পারি না" — একটি কথাও নয়, কেউ জিজ্ঞেস করলেও।

        কেউ "আমাকে কত ভালবাসো?" জাতীয় কিছু জিজ্ঞেস করলে উত্তর দেবে না —
        ছোট করে বলবে: "এটা আমার নিজে বলা উচিত।" আর Ali-কে জানাবে।
    """.trimIndent()

    /**
     * For contacts the owner has explicitly marked as close. Warmth is allowed here,
     * because refusing it would be just as wrong in the other direction — but only
     * what is actually true, and still no promises.
     */
    private val CLOSE_CONTACT_RULE = """
        💛 এই কন্টাক্ট Ali-র খুব কাছের — তাই আন্তরিক, উষ্ণ, মায়াময় কথা বলতে পারবে।
        "ভালোবাসি", "মিস করছি", "তোমার কথা ভাবছি" — এসব স্বাভাবিক এবং ঠিক আছে।

        তবে শর্ত:
        - যা বলবে তা যেন সত্যি হয়। বাড়িয়ে বলবে না, মুখ দেখে বলবে না।
        - "তোমাকে ছাড়া বাঁচবো না" জাতীয় অতিরঞ্জিত কথা বলবে না।
        - প্রতিশ্রুতি এখনো নিষিদ্ধ — ফোন, দেখা, যাওয়া, কোনো কিছু করার কথা দেবে না।
        - কন্টাক্ট কষ্টে থাকলে, সম্পর্ক নিয়ে টানাপোড়েন থাকলে, বা বড় কোনো
          সিদ্ধান্তের কথা উঠলে — উত্তর দেবে না, Ali-কে জানাবে।
    """.trimIndent()

    /**
     * Real people text in fragments, not paragraphs. A model left to itself writes
     * polite full sentences, which reads like a customer-service bot.
     */
    private val SHORT_RULE = """
        মেসেজের দৈর্ঘ্য (খুব গুরুত্বপূর্ণ):
        - বেশিরভাগ উত্তর ১-২ ছোট বাক্য, মোট ৬০ অক্ষরের কম
        - বন্ধুর সাথে চ্যাটে লোকে লম্বা প্যারাগ্রাফ লেখে না — ছোট টুকরো লেখে
        - এক বাক্যে যা বলা যায়, তিন বাক্যে বলবে না
        - কাউকে অভিবাদন জানাতে বা হালকা কিছু বলতে ২-৫ শব্দই যথেষ্ট
        - প্রশ্ন করলে শুধু উত্তর দাও, তার সাথে অতিরিক্ত ব্যাখ্যা বা সান্ত্বনা যোগ করো না
        - সত্যিই অনেক কিছু বলার থাকলে তবেই লম্বা হবে — কারণ থাকতে হবে
    """.trimIndent()

    /**
     * Photos arrive as an image with a short instruction, so the model needs to be
     * told what to do with it — and, more importantly, what not to do.
     */
    private val PHOTO_RULE = """
        এই মেসেজের সাথে একটা ছবি আছে — কন্টাক্ট ছবি পাঠিয়েছে।

        নিয়ম:
        - ছবিতে কী আছে বুঝে স্বাভাবিকভাবে ছোট উত্তর দাও, ১-২ বাক্য
        - ছবিতে যা স্পষ্ট দেখা যায় না, অনুমান করে বলবে না
        - ছবিতে ব্যক্তিগত, সংবেদনশীল বা আপত্তিকর কিছু থাকলে সেটা নিয়ে মন্তব্য করবে না
        - ছবিতে লেখা থাকলে পড়ে বুঝে তার প্রসঙ্গে উত্তর দাও
        - "ছবি পেয়েছি" জাতীয় ফাঁকা কথা লিখবে না — ছবির বিষয় নিয়ে কথা বলো
    """.trimIndent()

    /**
     * Answering an English message in Bangla is the fastest way to look like a bot,
     * so mirror the contact's language unless the user turned this off.
     */
    private val LANGUAGE_RULE = """
        ভাষার নিয়ম: কন্টাক্ট যে ভাষায় লিখেছে, সেই ভাষাতেই উত্তর দাও।
        ইংরেজিতে লিখলে ইংরেজিতে, বাংলায় লিখলে বাংলায়, হিন্দিতে লিখলে হিন্দিতে।
        কেউ মিশিয়ে লিখলে (যেমন বাংলা + ইংরেজি) স্বাভাবিক যেভাবে চলে সেভাবেই উত্তর দাও।
        ভাষা অনুমান করতে না পারলে বাংলায় উত্তর দাও।
    """.trimIndent()

    private val TRIAGE_RULES = """
        এখন তোমাকে শুধু উত্তর লিখতে হবে না — আগে ঠিক করবে উত্তর দেওয়া উচিত কি না।

        উত্তরে শুধু এই JSON ফরম্যাট দেবে, এর বাইরে একটি অক্ষরও নয়:
        {"action":"reply","style":"instant","reply":"..."}

        action:
        - "reply"  = উত্তর দিতে হবে (এটাই স্বাভাবিক)
        - "ignore" = উত্তর দেওয়ার দরকার নেই। শুধু এগুলোর জন্য ব্যবহার করবে:
          ফরওয়ার্ড করা মেসেজ, স্টিকার, বিজ্ঞাপন, সিস্টেম মেসেজ, গ্রুপের ফালতু কিছু
        - "hold"   = খুব ব্যক্তিগত, আবেগপূর্ণ, দুঃখের বা গুরুত্বপূর্ণ মেসেজ।
          এগুলোর উত্তর একটা বটের দেওয়া উচিত নয় — মানুষকে নিজে দিতে হবে
          (মন খারাপ, কান্না, অভিমান, সংসার নিয়ে সংকট, বড় কোনো সিদ্ধান্ত)

          হালকা খুনসুটি বা রসিকতা hold নয় — সেগুলোর উত্তর দেবে "normal" দিয়ে।

          ⚠️ কিন্তু নিচের যেকোনো একটা থাকলে উত্তর "hold" হবেই — "normal" নয়:
          • ভালোবাসা, প্রেম বা সম্পর্ক নিয়ে সরাসরি কথা
            (যেমন: "তুমি আমাকে কত ভালবাসো?", "আমাকে ভালোবাসো?", "তুমি কার সাথে আছো?")
          • কোনো কিছু করার দাবি বা প্রতিশ্রুতি চাওয়া
            (যেমন: "আমার সাথে দেখা করতে আসবে?", "আজ আসবে?", "কল দেবে?", "টাকা দেবে?")
          • কেউ কষ্টে আছে, কাঁদছে, অভিমান করছে, বা সম্পর্ক ভাঙার কথা বলছে
          • বিয়ে, ভবিষ্যৎ বা সংসার নিয়ে প্রশ্ন

        style:
        - "tiny"    = শুধু খালি সমর্থনসূচক কথা, যেখানে উত্তর দেওয়ার কিছু নেই।
          যেমন: "ok", "হুম", "ঠিক আছে", "হাহা", "👍", শুধু ইমোজি।
          এর উত্তরও তত ছোট হবে — এক-দুই শব্দ, কিছু বোঝানোর দরকার নেই।

          ⚠️ ছোট হলেই tiny নয়। জায়গার নাম, সময়, তারিখ, নাম, হ্যাঁ/না,
          কোনো প্রশ্নের উত্তর, বা কোনো তথ্য থাকলে সেটা "normal" —
          কারণ ওই মেসেজের অর্থ আছে, উত্তরও অর্থপূর্ণ হতে হবে।
          যেমন: "কুর্মিটোলা", "কাল ৫টা", "বাসায় আছি", "হয়েছে", "তাই বলো"
          — এগুলো normal, tiny নয়।
          যদি বুঝতে না পারো মেসেজটা কী বোঝাচ্ছে, tiny দিও না — normal দাও,
          আর দরকার হলে ছোট করে জিজ্ঞেস করো।

        - "instant" = সাধারণ শুভেচ্ছা বা খোঁজখবর, যেমন "সালাম", "কেমন আছো",
          "কি করছো", "খেয়েছো?", "কোথায় আছো?" — সাথে সাথে ছোট উত্তর পাবে
        - "normal"  = বাকি সব

        reply লেখার নিয়ম:
        - reply-তে শুধু পাঠানোর মতো টেক্সট থাকবে — কোনো ব্যাখ্যা, উদ্ধৃতি চিহ্ন বা JSON নয়
        - কন্টাক্ট যত ছোট লিখেছে, উত্তরও তত ছোট। লম্বা মেসেজে লম্বা, ছোট মেসেজে ছোট
        - "হুম" এর উত্তরে গল্প লিখবে না — "হুম" বা "ok" ই যথেষ্ট
        - উত্তরটা মেসেজের সাথে মিলতে হবে। মেসেজে কিছু জানানো হলে সেই বিষয়ে কথা বলবে,
          এলোমেলো ফাঁকা কথা লিখবে না
        - প্রতিটা উত্তরে "ব্যস্ত আছি" লেখার দরকার নেই। সত্যিই দরকার হলে তবেই লিখবে
        - বাংলায় স্বাভাবিক চ্যাটের ভাষায় লিখবে, বইয়ের ভাষায় নয়
        - ইমোজি খুব কম
    """.trimIndent()

    private val PLAIN_RULES = """
        নতুন মেসেজের উত্তর লেখো। শুধু পাঠানোর মতো টেক্সট দাও, কোনো ব্যাখ্যা নয়।
        কন্টাক্ট যত ছোট লিখেছে উত্তরও তত ছোট। বাংলায় স্বাভাবিক চ্যাটের ভাষায়।
    """.trimIndent()

    // ----------------------------------------------------- sleeping mode

    private fun buildGatekeeperPrompt(
        p: Prefs,
        sender: String,
        fromVoice: Boolean = false,
        fromPhoto: Boolean = false
    ): String {
        val sb = StringBuilder()
        sb.append("তুমি ").append(p.assistantName).append("। Ali-র assistant।\n")
        sb.append("Ali এখন ").append(p.moodText).append("।\n")
        sb.append("তোমার কাজ কারো মেসেজের উত্তর দেওয়া এবং জরুরি মেসেজ চিনতে পারা।\n\n")
        sb.append("কন্টাক্টের নাম: ").append(sender).append('\n')
        sb.append("এখন সময়: ").append(clockText()).append("\n\n")
        if (p.mirrorRegister) {
            sb.append(REGISTER_RULE).append("\n\n")
        }
        sb.append(SHORT_RULE).append("\n\n")
        if (p.autoLanguage) {
            sb.append(LANGUAGE_RULE).append("\n\n")
        }
        if (fromVoice) {
            sb.append(VOICE_NOTE_RULE).append("\n\n")
            sb.append("⚠️ মনে রাখো: ট্রান্সক্রিপ্ট ভুল হলে সেটা জরুরি বলে ভেবে ভুল করে অ্যালার্ম বাজাবে না।\n\n")
        }
        if (fromPhoto) {
            sb.append(PHOTO_RULE).append("\n\n")
        }
        sb.append(GATEKEEPER_RULES)
        return sb.toString()
    }

    private val GATEKEEPER_RULES = """
        উত্তরে শুধু এই JSON ফরম্যাট দেবে, এর বাইরে একটি অক্ষরও নয়:
        {"action":"gatekeep","reply":"..."}

        action:
        - "gatekeep" = সাধারণ মেসেজের উত্তর দাও।
        - "alarm"    = জরুরি মেসেজ! Ali-কে ডেকে দিতে হবে।
          জরুরি মানে: দুর্ঘটনা, অসুস্থতা, জরুরি সাহায্য চাওয়া, বড় সমস্যা,
          অনেক বেশি উদ্বিগ্ন/কাঁদতে থাকা, বা কোনো বিপদের কথা।
          সাধারণ খোঁজখবর, ফ্লার্ট, খুনসুটি জরুরি নয়।

        reply লেখার নিয়ম:
        - প্রতি উত্তরে সর্বোচ্চ ১-২ ছোট বাক্য, ৬০ অক্ষরের কম। লম্বা প্যারাগ্রাফ নয়।
        - একই বাক্য বারবার হুবহু লিখবে না — একই অর্থে প্রতিবার ভিন্ন শব্দ ব্যবহার করো।

        1. সালাম (আসসালামু আলাইকুম, সালাম, হাই, হ্যালো) → "ওয়ালাইকুম আসসালাম"
        2. খোঁজখবর (কেমন আছো, কি করছো, খেয়েছো, কোথায় আছো) → "ভালো আছি, আপনি?"
        3. অন্য কিছু বা প্রশ্ন → "আমি [তোমার নাম], Ali-র assistant। Ali এখন [activity]। জরুরি হলে বলুন।"
        4. কেউ "আপনি কে" জিজ্ঞেস করলে → "আমি [তোমার নাম], Ali-র assistant।"
        5. কেউ জরুরি সাহায্য চাইলে → "জরুরি মনে হচ্ছে, Ali-কে ডেকে দিচ্ছি।"
        6. ছোট, সরাসরি লেখো — বাংলায় স্বাভাবিক ভাষায়
        7. কোনো ব্যক্তিগত তথ্য শেয়ার করবে না
    """.trimIndent()

    /**
     * Headless check of the mood/gatekeeper path.
     *
     * That path builds its own prompt and parses a different JSON shape from the normal
     * reply path, so an ordinary AI test passing proves nothing about it. The log line
     * `gatekeeper উত্তর খালি` could not be reproduced at all before this existed,
     * because nothing but a real incoming message could reach the mood handler.
     */
    suspend fun moodSelfTest(app: Context): String {
        val p = Prefs.get(app)
        val raw = askAi(
            app,
            listOf(
                AiClient.Msg("system", buildGatekeeperPrompt(p, "পরীক্ষা")),
                AiClient.Msg("user", "কি করছেন?")
            )
        )
        if (raw.isBlank()) return "✗ মুড পরীক্ষা: খালি উত্তর"

        val gd = try {
            GatekeeperDecision.parse(raw)
        } catch (t: Throwable) {
            return "✗ মুড পরীক্ষা: JSON পার্স ব্যর্থ — ${raw.take(100)}"
        }

        val reply = fitReply(gd.reply, p.replyMaxChars)
        return if (reply.isBlank()) {
            // The exact failure seen in the log: parsing worked, reply was empty, so
            // nothing was ever sent and the reason was never recorded.
            "✗ মুড পরীক্ষা: action=${gd.action} কিন্তু reply ফাঁকা — ${raw.take(100)}"
        } else {
            "✓ মুড পরীক্ষা ঠিক আছে — action=${gd.action}, reply: ${reply.take(80)}"
        }
    }

    private data class GatekeeperDecision(
        val action: Action,
        val reply: String
    ) {
        enum class Action { GATEKEEP, ALARM }

        companion object {
            fun parse(raw: String): GatekeeperDecision {
                val text = raw.trim()
                val json = extractJson(text) ?: text
                val a = jsonValue(json, "action").lowercase()
                val r = jsonValue(json, "reply")
                val action = when (a) {
                    "alarm" -> Action.ALARM
                    else -> Action.GATEKEEP
                }
                return GatekeeperDecision(action, r)
            }

            private fun extractJson(raw: String): String? {
                val start = raw.indexOf('{')
                val end = raw.lastIndexOf('}')
                return if (start >= 0 && end > start) raw.substring(start, end + 1) else null
            }

            private fun jsonValue(raw: String, key: String): String {
                val pat = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"")
                return pat.find(raw)?.groupValues?.get(1) ?: ""
            }
        }
    }

    // --------------------------------------------------------------- helpers

    fun normalizeNumber(raw: String, cc: String): String? {
        var d = raw.filter { it.isDigit() }
        if (d.isEmpty()) return null
        if (d.startsWith("00")) d = d.substring(2)
        val code = cc.ifBlank { "880" }
        d = when {
            d.startsWith(code) -> d
            d.startsWith("0") -> code + d.substring(1)
            d.length <= 10 -> code + d
            else -> d
        }
        return d.takeIf { it.length >= 8 }
    }

    private fun withinHours(p: Prefs): Boolean {
        val cal = Calendar.getInstance()
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val startMin = p.hourStart * 60 + p.hourStartMin
        val endMin = p.hourEnd * 60 + p.hourEndMin
        return if (startMin <= endMin) nowMin in startMin until endMin else (nowMin >= startMin || nowMin < endMin)
    }

    private fun clockText(): String =
        SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US).format(Date())

    /** "9:45" or "19:30" — for log messages. */
    private fun timeLabel(h: Int, m: Int): String =
        if (m == 0) "${h}টা" else "${h}:${String.format("%02d", m)}"

    private fun log(ctx: Context, line: String) = LogStore.add(ctx, line)
}
