package com.voicecontrol.app.data

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import com.voicecontrol.app.appendToCrashLog
import com.voicecontrol.app.appendToInferenceErrors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter

class LocalAiClient(private val context: Context) {

    private val appModelPath = context.filesDir.absolutePath + "/model.gguf"

    private var llamaContext: de.kherud.llama.LlamaModel? = null

    fun isModelAvailable(): Boolean {
        val f = File(appModelPath)
        return f.exists() && f.length() > 100_000_000L
    }

    fun getModelStatusMessage(): String {
        val f = File(appModelPath)
        return when {
            f.exists() && f.length() > 100_000_000L ->
                "Model: Ready \u2713 (${f.length() / 1_048_576}MB)"
            f.exists() ->
                "Model: File incomplete (${f.length() / 1_048_576}MB) \u2014 copy again"
            else ->
                "Model: Not copied yet. Tap 'Copy Model' below."
        }
    }

    suspend fun copyModelFromUri(
        uri: Uri,
        onProgress: (Int) -> Unit
    ): String = withContext(Dispatchers.IO) {
        try {
            onProgress(1)

            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext "Could not open selected file."

            val fileSize = try {
                context.contentResolver.openFileDescriptor(uri, "r")?.statSize ?: 0L
            } catch (e: Exception) {
                0L
            }

            val destFile = File(appModelPath)
            var copiedBytes = 0L

            inputStream.buffered(8 * 1024 * 1024).use { input ->
                FileOutputStream(destFile).buffered(8 * 1024 * 1024).use { output ->
                    val buffer = ByteArray(8 * 1024 * 1024)
                    var bytes = input.read(buffer)
                    while (bytes >= 0) {
                        output.write(buffer, 0, bytes)
                        copiedBytes += bytes
                        if (fileSize > 0) {
                            onProgress(((copiedBytes * 100) / fileSize).toInt().coerceIn(0, 99))
                        }
                        bytes = input.read(buffer)
                    }
                }
            }

            onProgress(100)

            if (destFile.length() < 100_000_000L) {
                destFile.delete()
                "File copied but too small (${destFile.length() / 1024}KB) — did you select the right file?"
            } else {
                "Model copied successfully! Size: ${destFile.length() / 1_048_576}MB"
            }

        } catch (e: Exception) {
            "Copy failed: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    suspend fun generateResponse(
        prompt: String,
        history: List<Pair<String, String>>
    ): String = withContext(Dispatchers.IO) {
        if (!isModelAvailable()) {
            return@withContext "Model not ready. Go to Settings \u2192 tap 'Copy Model to App Storage' first."
        }

        try {
            if (llamaContext == null) {
                val memInfo = ActivityManager.MemoryInfo()
                (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memInfo)
                appendToCrashLog(context, "=== Model Load ===")
                appendToCrashLog(context, "availMem: ${memInfo.availMem / 1_048_576L}MB")
                appendToCrashLog(context, "threshold: ${memInfo.threshold / 1_048_576L}MB")
                appendToCrashLog(context, "lowMemory: ${memInfo.lowMemory}")
                appendToCrashLog(context, "SUPPORTED_ABIS: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
                appendToCrashLog(context, "n_ctx: 1024 -> 512 (changed)")
                appendToCrashLog(context, "use_mmap: true (default, unchanged)")

                val params = de.kherud.llama.ModelParameters()
                    .setGpuLayers(0)
                    .setCtxSize(512)
                    .setModel(appModelPath)
                try {
                    llamaContext = de.kherud.llama.LlamaModel(params)
                } catch (t: Throwable) {
                    appendToCrashLog(context, "Model load failed: ${t.javaClass.name}: ${t.message}")
                    val sw = StringWriter()
                    t.printStackTrace(PrintWriter(sw))
                    appendToCrashLog(context, sw.toString())
                    appendToInferenceErrors(context, "--- MODEL LOAD ERROR ---", t)
                    throw t
                }
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

            result.toString().trim()

        } catch (t: Throwable) {
            Log.e("LocalAiClient", "Inference error", t)
            appendToInferenceErrors(context, "--- INFERENCE ERROR ---", t)
            "Error: ${t.javaClass.name} — ${t.message}"
        }
    }

    fun unload() {
        try { llamaContext?.close() } catch (_: Exception) {}
        llamaContext = null
    }
}
