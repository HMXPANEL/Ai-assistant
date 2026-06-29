package com.voicecontrol.app.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class LocalAiClient(private val context: Context) {

    private val appModelPath = context.filesDir.absolutePath + "/model.gguf"
    private val modelFileName = "gemma-2-2b-it-lQ4_XS.gguf"

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

    private fun findModelUriInDownloads(): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null

        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.SIZE
        )
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(modelFileName)

        val cursor = context.contentResolver.query(
            collection, projection, selection, selectionArgs, null
        )

        cursor?.use {
            if (it.moveToFirst()) {
                val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                return Uri.withAppendedPath(collection, id.toString())
            }
        }
        return null
    }

    suspend fun copyModelToAppStorage(
        onProgress: (Int) -> Unit
    ): String = withContext(Dispatchers.IO) {
        try {
            val uri = findModelUriInDownloads()

            if (uri != null) {
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: return@withContext "Could not open model file from Downloads."

                val fileSize = context.contentResolver.openFileDescriptor(uri, "r")
                    ?.statSize ?: 0L

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
                                val progress = ((copiedBytes * 100) / fileSize).toInt()
                                onProgress(progress.coerceIn(0, 99))
                            }
                            bytes = input.read(buffer)
                        }
                    }
                }

                onProgress(100)
                val sizeMB = destFile.length() / 1_048_576
                "Model copied successfully! Size: ${sizeMB}MB. You can now enable On-Device AI."

            } else {
                "Model file '$modelFileName' not found in Downloads folder.\n" +
                "Make sure the file is in your Downloads folder and try again.\n" +
                "File must be named exactly: $modelFileName"
            }

        } catch (e: Exception) {
            Log.e("LocalAiClient", "Copy error", e)
            "Copy failed: ${e.message}"
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
                val params = de.kherud.llama.ModelParameters()
                    .setGpuLayers(0)
                    .setCtxSize(1024)
                    .setModel(appModelPath)
                llamaContext = de.kherud.llama.LlamaModel(params)
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
        try { llamaContext?.close() } catch (_: Exception) {}
        llamaContext = null
    }
}
