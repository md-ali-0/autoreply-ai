package com.claw.autoreplyai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/**
 * Resolves a WhatsApp chat title (usually the saved contact name) into a phone number,
 * so we can build a wa.me deep link.
 */
object ContactResolver {

    fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED

    fun numberForName(ctx: Context, name: String): String? {
        if (!hasPermission(ctx) || name.isBlank()) return null

        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val proj = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER)

        // 1. exact display name
        query(ctx, uri, proj, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ?", arrayOf(name))
            ?.let { return it }

        // 2. exact nickname / structured name fallback
        query(
            ctx, uri, proj,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%")
        )?.let { return it }

        return null
    }

    private fun query(
        ctx: Context,
        uri: android.net.Uri,
        proj: Array<String>,
        selection: String,
        args: Array<String>
    ): String? {
        return try {
            ctx.contentResolver.query(uri, proj, selection, args, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
