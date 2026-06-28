package com.voicecontrol.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class LocalAiClient(context: Context) {

    private val modelPath = "/storage/emulated/0/Download/gemma-2-2b-it-lQ4_XS.gguf"
    private var model: LLamaAndroid? = null

    fun isModelAvailable(): Boolean = File(modelPath).exists()

    suspend fun generateResponse(prompt: String, history: List<Pair<String, String>>): String {
        return withContext(Dispatchers.IO) {
            try {
                if (model == null) {
                    model = LLamaAndroid()
                    model?.loadModel(modelPath, nThreads = 4, nCtx = 1024)
                }

                val sb = StringBuilder()
                val recentHistory = history.takeLast(6)
                for ((role, text) in recentHistory) {
                    sb.append("<start_of_turn>")
                    sb.append(if (role == "user") "user" else "model")
                    sb.appendLine(text)
                    sb.append("<end_of_turn>")
                }
                sb.append("<start_of_turn>user").appendLine(prompt).append("<end_of_turn>")
                sb.append("<start_of_turn>model")

                model?.generate(
                    sb.toString(),
                    nPredict = 256,
                    nBatch = 512,
                    topK = 40,
                    topP = 0.95f,
                    temp = 0.7f,
                    repeatPenalty = 1.1f
                )?.trim() ?: "No response"
            } catch (e: Exception) {
                "Model error: ${e.message}"
            }
        }
    }

    fun unload() {
        model?.unloadModel()
        model = null
    }
}
