package com.claw.autoreplyai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A saved AI provider profile: name + endpoint + key + model.
 *
 * [id] is a stable identity that survives editing, reordering and re-storing. It exists
 * because the API key is held out of this object's JSON (see [toJson]) and stored in
 * [SecurePrefs] under a *name* — and naming that secret by list position made the name
 * a lie the moment the list moved. Deleting the first of three providers shifted the
 * other two down a slot, so their keys had to be rewritten to follow, and any write
 * that failed part-way left a key attached to the wrong profile. Keying on [id]
 * removes the whole class of bug: a profile's key is reachable from the profile
 * itself, not from wherever it happens to sit in a list.
 */
data class AiProvider(
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val id: String = newId()
) {
    /**
     * Serialise this profile for storage in the provider list.
     *
     * The API key is deliberately **not** written here by default. The provider list
     * lives in ordinary `SharedPreferences`, which is readable by anything that can
     * read the app's data directory — while `Prefs.apiKey`, `transcribeApiKey` and
     * `cloudToken` all go through [SecurePrefs]. Leaving the per-provider keys in the
     * clear made the protection inconsistent: the same class of secret was encrypted in
     * one place and plaintext in another. `AiProviderStore` now keeps each key in
     * [SecurePrefs] and re-attaches it on read.
     *
     * [includeSecret] remains for one purpose: writing a snapshot that is explicitly
     * meant to carry credentials. Nothing calls it with `true` any more; backups and
     * storage both exclude the key.
     */
    fun toJson(includeSecret: Boolean = false): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("name", name)
        o.put("baseUrl", baseUrl)
        if (includeSecret) o.put("apiKey", apiKey)
        o.put("model", model)
        return o
    }

    companion object {
        /** A fresh identity. Random, so two devices merging lists cannot collide. */
        fun newId(): String = UUID.randomUUID().toString()

        fun fromJson(o: JSONObject): AiProvider {
            return AiProvider(
                // A profile written before `id` existed gets one now. It is only ever
                // compared against itself, so a value minted at read time is stable
                // enough — the same read mints it once and it is written straight back.
                id = o.optString("id").takeIf { it.isNotBlank() } ?: newId(),
                name = o.optString("name", ""),
                baseUrl = o.optString("baseUrl", ""),
                apiKey = o.optString("apiKey", ""),
                model = o.optString("model", "")
            )
        }

        val DEFAULT = AiProvider(
            name = "xkiro free",
            baseUrl = "https://api.xkiro.com/v1",
            apiKey = "",
            model = "qwen/qwen3.8-max:free"
        )
    }
}

/**
 * Persists provider profiles as a JSON array in SharedPreferences.
 */
object AiProviderStore {

    private const val PREFS = "ai_providers"
    private const val KEY_LIST = "providers"
    private const val KEY_SELECTED = "selected"

    /**
     * Every secure-store name this store owns starts with this. Used to find orphans
     * after a delete without knowing which ids are on disk.
     */
    private const val SECRET_PREFIX = "providerApiKey_"

    /**
     * Hosts we never call, even if a profile for one is already on the device or
     * arrives through a restored backup.
     *
     * `apinex.bond` answered HTTP 200 with an empty body six times in a row on
     * 28 Sep and was the only provider that did this. Its failures were invisible
     * in the log (a blank answer is not an exception, so `askAi` never logged it)
     * and it silently burned a retry on every message. Rather than trust a manual
     * delete on one device, the host is blocked here: every read path filters it,
     * and it is stripped the next time the list is written.
     */
    private val BLOCKED_HOSTS = listOf("apinex.bond")

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * SecurePrefs name for one provider's key, addressed by the profile's stable [id].
     *
     * Previously this was `providerApiKey_<slot>` — the profile's position in the list.
     * Position is not identity: deleting the first of three profiles shifted the others
     * down a slot, so their keys had to be rewritten to follow the move, and every write
     * had to rewrite the entire key set just to stay consistent. A failed write in the
     * middle of that left keys attached to the wrong profiles. Keyed by id, a key never
     * has to move, and [save] touches only the profile that actually changed.
     */
    private fun secretName(id: String) = SECRET_PREFIX + id

    /** The old slot-based name, still read once so existing keys are not orphaned. */
    private fun legacySecretName(index: Int) = "providerApiKey_$index"

    /** True when this profile points at a host we have retired. */
    fun isBlocked(p: AiProvider): Boolean {
        val url = p.baseUrl.lowercase()
        return BLOCKED_HOSTS.any { url.contains(it) }
    }

    fun all(ctx: Context): List<AiProvider> {
        val raw = rawList(ctx)
        return raw.filterNot { isBlocked(it) }
    }

    /**
     * Every stored profile, blocked ones included, with its key attached.
     *
     * A profile's key lives in [SecurePrefs] under its own [AiProvider.id]. Two older
     * layouts are read once and folded in:
     *
     *  - a plaintext `apiKey` inline in the list, written by builds before v1.52.3;
     *  - a key under `providerApiKey_<index>`, written by v1.52.3/v1.52.4 before
     *    profiles had an id.
     *
     * Migration is per-profile and each profile is scrubbed only after its **own** write
     * is confirmed. `SecurePrefs.put` returns false when the Keystore is unavailable, and
     * that can happen for one write and not the next, so an aggregate "did anything
     * succeed?" check would delete the key of a profile that was never migrated. Every
     * read falls back to whatever source still holds the key, so a failed migration reads
     * correctly for this boot and is retried on the next.
     */
    private fun rawList(ctx: Context): List<AiProvider> {
        val raw = sp(ctx).getString(KEY_LIST, "[]") ?: "[]"
        val arr = try {
            JSONArray(raw)
        } catch (e: Exception) {
            return emptyList()
        }

        // 1. Read every profile and collect any legacy plaintext keys in the same pass.
        //
        //    A profile written before `id` existed has none, and [AiProvider.fromJson]
        //    mints one. That minted id MUST be written back before anything is stored
        //    under it, or the identity changes on the next read and the key becomes
        //    unreachable — the profile keeps a different id each boot while its secret
        //    sits under the previous one, and the orphan sweep in [save] then deletes
        //    what looks like a dead entry but is in fact the only copy of the key.
        val profiles = ArrayList<AiProvider>(arr.length())
        val legacyInline = ArrayList<String>(arr.length())
        var mintedIds = 0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val p = AiProvider.fromJson(o)
            profiles.add(p)
            legacyInline.add(o.optString("apiKey", ""))
            if (o.optString("id").isBlank()) {
                arr.getJSONObject(i).put("id", p.id)
                mintedIds++
            }
        }
        // Persist the minted ids as a group, immediately, so identity is durable from
        // this read onward.
        if (mintedIds > 0) {
            try {
                sp(ctx).edit().putString(KEY_LIST, arr.toString()).apply()
                LogStore.add(ctx, "প্রোভাইডার পরিচয় বসানো হলো ($mintedIds টা)")
            } catch (e: Exception) {
                // If this fails we must not migrate under the ephemeral id, or step 2
                // would write a key into a name that is about to disappear.
                LogStore.add(ctx, "⚠️ প্রোভাইডার পরিচয় সংরক্ষণ ব্যর্থ — মাইগ্রেশন পিছিয়ে দেওয়া হলো")
                return profiles.mapIndexed { i, p -> p.copy(apiKey = legacyInline[i]) }
            }
        }

        // 2. Resolve each profile's key, preferring the id-keyed slot and falling back
        //    to the slot-numbered one, then to the inline plaintext.
        val resolved = ArrayList<String>(profiles.size)
        var migrated = 0
        for ((i, p) in profiles.withIndex()) {
            val byId = SecurePrefs.get(ctx, secretName(p.id))
            val fromSlot = if (byId == null) SecurePrefs.get(ctx, legacySecretName(i)) else null
            val key = byId ?: fromSlot ?: legacyInline[i]
            resolved.add(key)

            // Adopt the legacy slot name onto the stable id, but only if this profile
            // does not already have an id-keyed secret and actually has a key to move.
            if (byId == null && key.isNotBlank()) {
                if (SecurePrefs.put(ctx, secretName(p.id), key)) {
                    migrated++
                    if (fromSlot != null) SecurePrefs.remove(ctx, legacySecretName(i))
                }
            }
        }

        // 3. Scrub inline plaintext, but only for profiles whose key is now readable
        //    from secure storage — otherwise the scrub destroys the only copy.
        val scrub = ArrayList<Int>()
        for ((i, p) in profiles.withIndex()) {
            if (legacyInline[i].isBlank()) continue
            if (SecurePrefs.get(ctx, secretName(p.id)) != null) scrub.add(i)
        }
        if (scrub.isNotEmpty()) scrubSecrets(ctx, scrub)
        if (migrated > 0 || scrub.isNotEmpty()) {
            LogStore.add(
                ctx,
                "প্রোভাইডার key সুরক্ষিত স্টোরেজে সরানো হলো " +
                        "(id-তে $migrated টা, প্লেইনটেক্সট মুছে ${scrub.size} টা)"
            )
        }

        return profiles.mapIndexed { i, p -> p.copy(apiKey = resolved[i]) }
    }

    /**
     * Drop the `apiKey` field from the named slots only.
     *
     * [slots] is the set of indices whose key is now confirmed present in [SecurePrefs].
     * Every other slot keeps its value — see the migration comment in [rawList] for why
     * a blanket scrub is unsafe.
     */
    private fun scrubSecrets(ctx: Context, slots: List<Int>) {
        if (slots.isEmpty()) return
        try {
            val raw = sp(ctx).getString(KEY_LIST, "[]") ?: "[]"
            val arr = JSONArray(raw)
            for (i in slots) {
                if (i in 0 until arr.length()) arr.getJSONObject(i).remove("apiKey")
            }
            sp(ctx).edit().putString(KEY_LIST, arr.toString()).apply()
        } catch (e: Exception) {
            // Best effort. A failure leaves the list readable and the key still in the
            // secure store, so the worst case is a redundant plaintext copy that the
            // next read will try to scrub again.
        }
    }

    /**
     * Persist the whole list and each profile's key. Returns the number of keys that
     * could **not** be written to secure storage, so the caller can say so.
     *
     * The list goes to the plain preference (never carrying keys — see
     * [AiProvider.toJson]) and each key goes to [SecurePrefs] under its profile's own
     * id. Because the name is derived from the profile and not from a list position, a
     * reorder or a delete cannot leave one profile's key attached to another — there is
     * nothing to renumber, and a profile that did not change is not rewritten at all.
     *
     * A failed write is reported rather than swallowed. Losing a key here is the user's
     * own edit not persisting, which is recoverable — but only if they are told.
     */
    fun save(ctx: Context, list: List<AiProvider>): Int {
        // A blocked host can never be written back, whatever the caller passes.
        val clean = list.filterNot { isBlocked(it) }

        val arr = JSONArray()
        clean.forEach { arr.put(it.toJson(includeSecret = false)) }
        sp(ctx).edit().putString(KEY_LIST, arr.toString()).apply()

        var failed = 0
        for (p in clean) {
            if (p.apiKey.isBlank()) {
                SecurePrefs.remove(ctx, secretName(p.id))
            } else if (!SecurePrefs.put(ctx, secretName(p.id), p.apiKey)) {
                failed++
                LogStore.add(ctx, "⚠️ প্রোভাইডার key সংরক্ষণ করা করা যায়নি — ${p.name.ifBlank { p.id }}")
            }
        }

        // Drop keys belonging to profiles that are no longer in the list. Enumerated
        // from the names actually present rather than from a slot range, since the ids
        // are arbitrary strings and there is no "beyond the end" to walk to.
        val live = clean.map { secretName(it.id) }.toSet()
        for (name in SecurePrefs.namesWithPrefix(ctx, SECRET_PREFIX)) {
            if (name !in live) SecurePrefs.remove(ctx, name)
        }
        return failed
    }

    private fun rawListStoredIndex(ctx: Context): Int =
        sp(ctx).getInt(KEY_SELECTED, 0).coerceAtLeast(0)

    /**
     * Drops retired hosts from storage. Called from the store's own write path, so a
     * profile that was already on the device disappears the first time any settings
     * screen saves — no manual delete, and nothing to forget on a new handset.
     */
    fun purgeBlocked(ctx: Context) {
        val raw = rawList(ctx)
        val kept = raw.filterNot { isBlocked(it) }
        if (kept.size == raw.size) return

        val stored = rawListStoredIndex(ctx)
        val removedBefore = raw.take(stored).count { isBlocked(it) }
        save(ctx, kept)
        if (kept.isNotEmpty()) {
            setSelected(ctx, (stored - removedBefore).coerceIn(0, kept.lastIndex))
        } else {
            setSelected(ctx, 0)
        }
    }

    fun selectedIndex(ctx: Context): Int {
        // The stored index counts *raw* entries, including any blocked ones. `all()`
        // has already dropped those, so the index has to be shifted by however many
        // were filtered out ahead of the selection — otherwise a blocked provider at
        // slot 0 leaves the selection pointing one entry too far down the list.
        val raw = rawList(ctx)
        val cleanCount = raw.count { !isBlocked(it) }
        if (cleanCount == 0) return 0
        val stored = rawListStoredIndex(ctx)
        val removedBefore = raw.take(stored).count { isBlocked(it) }
        return (stored - removedBefore).coerceIn(0, cleanCount - 1)
    }

    fun setSelected(ctx: Context, index: Int) {
        sp(ctx).edit().putInt(KEY_SELECTED, index.coerceAtLeast(0)).apply()
    }

    /**
     * Returns providers ordered by priority: the selected one first,
     * then the rest. Used for auto-fallback when the primary fails.
     */
    fun prioritized(ctx: Context): List<AiProvider> {
        val list = all(ctx)
        if (list.isEmpty()) return emptyList()
        val sel = selectedIndex(ctx).coerceIn(0, list.lastIndex)
        val ordered = mutableListOf<AiProvider>()
        ordered.add(list[sel])
        for (i in list.indices) {
            if (i != sel) ordered.add(list[i])
        }
        return ordered
    }

    /** Migrate from legacy single-provider prefs if providers list is empty. */
    fun migrateIfNeeded(ctx: Context, p: Prefs) {
        if (all(ctx).isNotEmpty()) return
        val list = mutableListOf(
            AiProvider(
                name = "xkiro",
                baseUrl = p.baseUrl.ifBlank { "https://api.xkiro.com/v1" },
                apiKey = p.apiKey,
                model = p.model.ifBlank { "qwen/qwen3.8-max:free" }
            )
        )
        save(ctx, list)
        setSelected(ctx, 0)
    }

    /**
     * Report, in the log, what each profile resolved to and which secure-store names
     * exist — without ever writing a key.
     *
     * Exists because a provider key that fails to resolve is invisible from the outside:
     * the request simply goes out with no `Authorization` header and comes back 401, and
     * on a non-debuggable build the preferences file cannot be read to tell "the key was
     * lost" apart from "the key is there but under the wrong name". This says which.
     *
     * Keys are reduced to a length and a 4-character fingerprint, which is enough to
     * confirm *which* stored value a profile picked up and useless to anyone reading the
     * log over someone's shoulder.
     */
    fun describeForTest(ctx: Context) {
        val ids = SecurePrefs.namesWithPrefix(ctx, SECRET_PREFIX)
        LogStore.add(ctx, "প্রোভাইডার ডায়াগনোসিস: সিক্রেট নাম ${ids.size} টা — " + ids.joinToString(", "))
        val raw = rawList(ctx)
        LogStore.add(ctx, "প্রোভাইডার ডায়াগনোসিস: প্রোফাইল ${raw.size} টা")
        for ((i, p) in raw.withIndex()) {
            LogStore.add(
                ctx,
                "  #${i + 1} ${p.name} · ${p.baseUrl} · id=${p.id.take(8)} · " +
                        "key=${fingerprint(p.apiKey)} · selected=${selectedIndex(ctx) == i}"
            )
        }
        LogStore.add(
            ctx,
            "প্রোভাইডার ডায়াগনোসিস: ব্লকড ${raw.count { isBlocked(it) }} টা বাদ যাবে"
        )
    }

    /** `(length, first4)` — identifies a key without disclosing it. */
    private fun fingerprint(key: String): String =
        if (key.isBlank()) "নেই (খালি)" else "${key.length} অক্ষর, ${key.take(4)}…"

    /**
     * Reproduce the exact upgrade condition that lost keys on 28 Sep, so the fix can be
     * shown to hold on the real device rather than only in a mirror.
     *
     * Writes a profile with a known key, then strips the `id` back out of the stored
     * JSON — which is precisely the state a v1.52.4 install is in: a profile with a
     * legacy slot-numbered secret and no identity. The next read has to mint an id, and
     * the assertion is that the key survives the reads, a [save], and a restart.
     *
     * Only ever called from the headless `providertest` extra.
     */
    fun seedLegacyForTest(ctx: Context, key: String) {
        // One profile, no id in the JSON, key only in the old slot-numbered name.
        val arr = JSONArray()
        arr.put(
            JSONObject()
                .put("name", "SeedTest")
                .put("baseUrl", "https://seed.test/v1")
                .put("model", "seed-model")
        )
        sp(ctx).edit().putString(KEY_LIST, arr.toString()).apply()
        for (name in SecurePrefs.namesWithPrefix(ctx, SECRET_PREFIX)) {
            SecurePrefs.remove(ctx, name)
        }
        SecurePrefs.put(ctx, legacySecretName(0), key)
        LogStore.add(ctx, "সিড: পুরোনো slot-নামে key বসানো হলো, প্রোফাইলে id নেই")
    }
}
