package com.voicecontrol.app.data

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.generationConfig
import com.voicecontrol.app.BuildConfig

class GeminiClient {
    private val model = GenerativeModel(
        modelName = "gemini-2.5-flash",
        apiKey = BuildConfig.GEMINI_API_KEY,
        generationConfig = generationConfig {
            temperature = 0.7f
            maxOutputTokens = 512
        }
    )

    suspend fun generateResponse(
        prompt: String,
        history: List<Pair<String, String>>
    ): String {
        val context = buildString {
            for ((role, text) in history.takeLast(10)) {
                appendLine("$role: $text")
            }
            appendLine("user: $prompt")
        }
        return try {
            val response = model.generateContent(context)
            response.text?.trim() ?: "No response generated."
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}
