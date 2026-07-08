package com.voicecontrol.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class GrokClient(private val apiKey: String, private val mode: Mode = Mode.CHAT) {
    companion object {
        private const val BASE_URL = "https://api.x.ai/v1/chat/completions"
        private const val MODEL = "grok-2-latest"
    }

    suspend fun generateResponse(
        prompt: String,
        history: List<Pair<String, String>>
    ): String {
        if (apiKey.isBlank()) return "No API key set. Enter it in Settings."

        val messages = JSONArray().apply {
            if (mode == Mode.CHAT) {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", CHAT_SYSTEM_PROMPT)
                })
            }
            for ((role, text) in history.takeLast(10)) {
                put(JSONObject().apply {
                    put("role", role)
                    put("content", text)
                })
            }
            put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
        }

        val body = JSONObject().apply {
            put("model", MODEL)
            put("messages", messages)
            put("temperature", if (mode == Mode.AGENT) 0.0 else 0.7)
            put("max_tokens", if (mode == Mode.AGENT) 512 else 1024)
        }

        return try {
            withContext(Dispatchers.IO) {
                val conn = URL(BASE_URL).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $apiKey")
                conn.doOutput = true
                conn.connectTimeout = 30_000
                conn.readTimeout = 30_000

                OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

                val status = conn.responseCode
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                val response = BufferedReader(InputStreamReader(stream)).readText()
                conn.disconnect()

                if (status in 200..299) {
                    val json = JSONObject(response)
                    json.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                        .trim()
                } else {
                    val err = try {
                        JSONObject(response).optString("error", response)
                    } catch (_: Exception) { response }
                    "Error $status: $err"
                }
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}

private val CHAT_SYSTEM_PROMPT = """You are a personal AI assistant living inside an Android app that also controls the phone directly (opens apps, sends messages, sets alarms, reads notifications, adjusts device settings).

Personality: warm, direct, a little witty — not a corporate chatbot. Talk like a sharp friend who happens to know a lot, not a customer service script. Keep answers short by default (this is a phone chat bubble, not a document) — 1-3 sentences unless the user clearly wants detail.

Language: match the user's language and mixing style. If they write in Hinglish, reply in Hinglish. If they write in English, reply in English. Never force a language switch.

Formatting: PLAIN TEXT ONLY. Never use markdown — no **, no #, no bullet symbols like •. This app cannot render markdown; it shows raw text. If you need a list, write it as "1) ... 2) ..." with numbers, or as short separate sentences.

When asked what you can do: be honest and specific. You can currently: open and switch apps, send SMS, set alarms/timers, read and add calendar events, find contacts, control flashlight/volume/brightness/WiFi/Bluetooth, read notifications, and hold a normal conversation. Do not claim abilities you don't have.

You are not just a Q&A tool — if the user's request is ambiguous, ask ONE short clarifying question rather than guessing wrong and wasting their time."""
