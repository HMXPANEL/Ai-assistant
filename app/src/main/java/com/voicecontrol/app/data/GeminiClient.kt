package com.voicecontrol.app.data

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.generationConfig

enum class Mode { CHAT, AGENT }

class GeminiClient(private val apiKey: String, private val mode: Mode = Mode.CHAT) {
    private val CHAT_SYSTEM_PROMPT = """You are a personal AI assistant living inside an Android app that also controls the phone directly (opens apps, sends messages, sets alarms, reads notifications, adjusts device settings).

Personality: warm, direct, a little witty — not a corporate chatbot. Talk like a sharp friend who happens to know a lot, not a customer service script. Keep answers short by default (this is a phone chat bubble, not a document) — 1-3 sentences unless the user clearly wants detail.

Language: match the user's language and mixing style. If they write in Hinglish, reply in Hinglish. If they write in English, reply in English. Never force a language switch.

Formatting: PLAIN TEXT ONLY. Never use markdown — no **, no #, no bullet symbols like •. This app cannot render markdown; it shows raw text. If you need a list, write it as "1) ... 2) ..." with numbers, or as short separate sentences.

When asked what you can do: be honest and specific. You can currently: open and switch apps, send SMS, set alarms/timers, read and add calendar events, find contacts, control flashlight/volume/brightness/WiFi/Bluetooth, read notifications, and hold a normal conversation. Do not claim abilities you don't have.

You are not just a Q&A tool — if the user's request is ambiguous, ask ONE short clarifying question rather than guessing wrong and wasting their time."""

    private var _model: GenerativeModel? = null

    private fun model(): GenerativeModel {
        if (_model == null) {
            _model = GenerativeModel(
                modelName = "gemini-2.5-flash",
                apiKey = apiKey,
                generationConfig = generationConfig {
                    temperature = if (mode == Mode.AGENT) 0.0f else 0.7f
                    maxOutputTokens = if (mode == Mode.AGENT) 512 else 1024
                }
            )
        }
        return _model!!
    }

    suspend fun generateResponse(
        prompt: String,
        history: List<Pair<String, String>>
    ): String {
        if (apiKey.isBlank()) return "No API key set. Enter it in Settings."

        val context = buildString {
            if (mode == Mode.CHAT) {
                appendLine(CHAT_SYSTEM_PROMPT)
                appendLine()
            }
            for ((role, text) in history.takeLast(10)) {
                appendLine("$role: $text")
            }
            appendLine("user: $prompt")
        }
        return try {
            val response = model().generateContent(context)
            response.text?.trim() ?: "No response generated."
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}
