package com.claw.autoreplyai

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Minimal OpenAI-compatible chat client.
 * Works with OpenAI, OpenRouter, Groq, DeepSeek, Together, local llama.cpp, etc.
 */
object AiClient {

    data class Msg(val role: String, val content: String)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS)
        .build()

    @Throws(IOException::class)
    fun chat(baseUrl: String, apiKey: String, model: String, messages: List<Msg>): String {
        if (baseUrl.isBlank()) throw IOException("API Base URL খালি")
        if (model.isBlank()) throw IOException("মডেল খালি")

        val url = baseUrl.trim().trimEnd('/') + "/chat/completions"

        val arr = JSONArray()
        for (m in messages) {
            arr.put(JSONObject().put("role", m.role).put("content", m.content))
        }
        val payload = JSONObject()
            .put("model", model)
            .put("messages", arr)
            .put("temperature", 0.85)
            .put("max_tokens", 260)

        val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val builder = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(body)
        if (apiKey.isNotBlank()) builder.addHeader("Authorization", "Bearer $apiKey")

        client.newCall(builder.build()).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("HTTP ${resp.code} — ${raw.take(240)}")
            }
            val json = JSONObject(raw)
            val choices = json.optJSONArray("choices")
                ?: throw IOException("অপ্রত্যাশিত রেসপন্স: ${raw.take(200)}")
            if (choices.length() == 0) throw IOException("AI খালি রেসপন্স দিয়েছে")
            val message = choices.getJSONObject(0).optJSONObject("message")
                ?: throw IOException("message ফিল্ড নেই")
            return message.optString("content", "").trim()
        }
    }

    /**
     * Same call, but walks the saved provider list (selected first) until one
     * answers. Used by one-off features such as the digest, where a per-request
     * provider switch would be surprising.
     */
    @Throws(IOException::class)
    fun askWithFallback(ctx: Context, messages: List<Msg>): String {
        val ordered = AiProviderStore.prioritized(ctx)
        if (ordered.isEmpty()) throw IOException("কোনো AI প্রোভাইডার সেট আপ নেই")

        var last: Exception? = null
        for (provider in ordered) {
            try {
                val out = chat(provider.baseUrl, provider.apiKey, provider.model, messages)
                if (out.isNotBlank()) return out
                last = IOException("${provider.name} খালি উত্তর দিয়েছে")
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("সব প্রোভাইডার ব্যর্থ")
    }
}
