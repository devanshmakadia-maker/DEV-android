package com.devassistant.app.ai

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper around the Anthropic Messages API (api.anthropic.com/v1/messages) with tool use.
 * The user supplies their own API key in Settings; it is stored locally via SettingsStore and
 * sent only in the anthropic-api-key header of requests this app makes directly to Anthropic.
 */
class AnthropicClient(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Sends the running conversation (system prompt + turns so far, including prior tool
     * results) and returns the raw JSON response body for the caller to interpret.
     */
    fun sendMessage(systemPrompt: String, messages: JSONArray, tools: JSONArray): JSONObject {
        val body = JSONObject().apply {
            put("model", "claude-sonnet-4-6")
            put("max_tokens", 1024)
            put("system", systemPrompt)
            put("messages", messages)
            put("tools", tools)
        }

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string() ?: "{}"
            if (!response.isSuccessful) {
                throw RuntimeException("Anthropic API error ${response.code}: $text")
            }
            return JSONObject(text)
        }
    }
}
