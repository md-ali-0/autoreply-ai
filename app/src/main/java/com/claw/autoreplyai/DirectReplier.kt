package com.claw.autoreplyai

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * Replies straight through the inline-reply action that WhatsApp puts on its own
 * notification ("Reply" / "উত্তর দিন").
 *
 * Why this is the preferred path:
 *  - the screen never has to wake up,
 *  - WhatsApp is never brought to the foreground,
 *  - it therefore works while the phone is **locked**, which the
 *    accessibility + wa.me route fundamentally cannot do.
 *
 * It only needs the notification-listener grant, which the app already has.
 */
object DirectReplier {

    /** The inline-reply action of a notification, captured while it is still live. */
    class Handle(
        val action: Notification.Action,
        val inputs: Array<RemoteInput>
    )

    /** Returns a [Handle] if this notification offers a free-form reply action. */
    fun findReplyAction(n: Notification?): Handle? {
        val actions = n?.actions ?: return null
        for (action in actions) {
            val inputs = action.remoteInputs ?: continue
            if (inputs.any { it.allowFreeFormInput }) return Handle(action, inputs)
        }
        return null
    }

    fun send(ctx: Context, handle: Handle, text: String): Boolean {
        return try {
            val results = Bundle()
            for (input in handle.inputs) {
                if (input.allowFreeFormInput) results.putCharSequence(input.resultKey, text)
            }
            if (results.isEmpty) {
                LogStore.add(ctx, "ডাইরেক্ট রিপ্লাই: resultKey পাওয়া যায়নি")
                return false
            }

            val intent = Intent()
            RemoteInput.addResultsToIntent(handle.inputs, intent, results)
            handle.action.actionIntent.send(ctx, 0, intent)

            LogStore.add(ctx, "✓ নোটিফিকেশন থেকেই সরাসরি রিপ্লাই পাঠানো হলো")
            true
        } catch (e: Exception) {
            LogStore.add(ctx, "ডাইরেক্ট রিপ্লাই ব্যর্থ: ${e.message}")
            false
        }
    }
}
