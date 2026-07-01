package com.voicecontrol.app.data

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.generationConfig

enum class Mode { CHAT, AGENT }

class GeminiClient(private val apiKey: String, private val mode: Mode = Mode.CHAT) {
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
