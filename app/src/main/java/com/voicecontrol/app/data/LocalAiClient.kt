package com.voicecontrol.app.data

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class LocalAiClient(context: Context) {

    private val modelPath = context.filesDir.absolutePath + "/gemma-2b-it-cpu-int4.bin"
    private var inference: LlmInference? = null

    fun isModelAvailable(): Boolean = File(modelPath).exists()

    suspend fun generateResponse(prompt: String, history: List<Pair<String, String>>): String {
        return withContext(Dispatchers.IO) {
            try {
                if (inference == null) {
                    val options = LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(modelPath)
                        .build()
                    inference = LlmInference.createFromOptions(options)
                }

                val sb = StringBuilder()
                val recentHistory = history.takeLast(10)
                for ((role, text) in recentHistory) {
                    sb.append(if (role == "user") "User: " else "Assistant: ")
                    sb.appendLine(text)
                }
                sb.append("User: ").appendLine(prompt)
                sb.append("Assistant: ")

                inference?.generateResponse(sb.toString()) ?: "No response"
            } catch (e: Exception) {
                "Model error: ${e.message}"
            }
        }
    }
}
