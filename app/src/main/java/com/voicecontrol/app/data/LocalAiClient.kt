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
    private val possibleFileNames = listOf(
        "gemma-2-2b-it-lQ4_XS.gguf",
        "gemma-2-2b-it-Q4_K_M.gguf",
        "model.gguf",
        "gemma-2b.gguf",
        "gemma.gguf"
    )

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

        for (fileName in possibleFileNames) {
            val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ?"
            val cursor = context.contentResolver.query(
                collection, projection, selection, arrayOf(fileName), null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val size = it.getLong(
                        it.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                    )
                    if (size > 100_000_000L) {
                        val id = it.getLong(
                            it.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                        )
                        return Uri.withAppendedPath(collection, id.toString())
                    }
                }
            }
        }

        // Fallback: scan all files in Downloads for any .gguf or .bin > 100MB
        val allFilesSelection = "${MediaStore.Downloads.SIZE} > ?"
        val allFilesCursor = context.contentResolver.query(
            collection, projection, allFilesSelection,
            arrayOf("100000000"),
            "${MediaStore.Downloads.SIZE} DESC"
        )
        allFilesCursor?.use {
            while (it.moveToNext()) {
                val name = it.getString(
                    it.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                )
                if (name.endsWith(".gguf", ignoreCase = true) ||
                    name.endsWith(".bin", ignoreCase = true)) {
                    val id = it.getLong(
                        it.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                    )
                    Log.d("LocalAiClient", "Found model file: $name")
                    return Uri.withAppendedPath(collection, id.toString())
                }
            }
        }

        return null
    }

    suspend fun copyModelToAppStorage(
        onProgress: (Int) -> Unit
    ): String = withContext(Dispatchers.IO) {
        try {
            onProgress(1)

            val uri = findModelUriInDownloads()

            if (uri == null) {
                val debugInfo = StringBuilder("No matching file found.\n\n")
                debugInfo.append("Files found in Downloads via MediaStore:\n")

                try {
                    val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL)
                    val projection = arrayOf(
                        MediaStore.Downloads.DISPLAY_NAME,
                        MediaStore.Downloads.SIZE
                    )
                    val cursor = context.contentResolver.query(
                        collection, projection, null, null, null
                    )
                    cursor?.use {
                        var count = 0
                        while (it.moveToNext() && count < 20) {
                            val name = it.getString(
                                it.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                            )
                            val size = it.getLong(
                                it.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                            )
                            debugInfo.append("- $name (${size / 1_048_576}MB)\n")
                            count++
                        }
                        if (count == 0) {
                            debugInfo.append("(MediaStore query returned ZERO files — permission or indexing issue)\n")
                        }
                    }
                } catch (e: Exception) {
                    debugInfo.append("MediaStore query itself failed: ${e.message}\n")
                }

                return@withContext debugInfo.toString()
            }

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

        } catch (e: Exception) {
            Log.e("LocalAiClient", "Copy error", e)
            "Copy failed with exception: ${e.javaClass.simpleName}: ${e.message}\nStack: ${e.stackTrace.take(3).joinToString()}"
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
