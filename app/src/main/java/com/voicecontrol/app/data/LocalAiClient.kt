package com.voicecontrol.app.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class LocalAiClient(private val context: Context) {

    private val modelPath = "/storage/emulated/0/Download/gemma-2-2b-it-lQ4_XS.gguf"
    private var isLoaded = false
    private var llamaContext: de.kherud.llama.LlamaModel? = null

    fun isModelAvailable(): Boolean = File(modelPath).exists()

    suspend fun generateResponse(
        prompt: String,
        history: List<Pair<String, String>>
    ): String = withContext(Dispatchers.IO) {
        try {
            if (!isLoaded || llamaContext == null) {
                val params = de.kherud.llama.ModelParameters()
                    .setGpuLayers(0)
                    .setCtxSize(1024)
                    .setModel(modelPath)
                llamaContext = de.kherud.llama.LlamaModel(params)
                isLoaded = true
            }

            val sb = StringBuilder()
            val recentHistory = history.takeLast(6)
            for ((role, text) in recentHistory) {
                if (role == "user") {
                    sb.append("<start_of_turn>user\n").append(text).append("<end_of_turn>\n")
                } else {
                    sb.append("<start_of_turn>model\n").append(text).append("<end_of_turn>\n")
                }
            }
            sb.append("<start_of_turn>user\n").append(prompt).append("<end_of_turn>\n")
            sb.append("<start_of_turn>model\n")

            val inferParams = de.kherud.llama.InferenceParameters(sb.toString())
                .setNPredict(256)
                .setTemperature(0.7f)
                .setStopStrings("<end_of_turn>")

            val result = StringBuilder()
            llamaContext?.generate(inferParams)?.forEach { token ->
                result.append(token.text)
            }

            result.toString().trim().ifEmpty { "I couldn't generate a response." }

        } catch (e: Exception) {
            Log.e("LocalAiClient", "Inference error", e)
            "Model error: ${e.message}"
        }
    }

    fun unload() {
        try {
            llamaContext?.close()
        } catch (_: Exception) {}
        llamaContext = null
        isLoaded = false
    }
}
