package com.voicecontrol.app

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun appendToCrashLog(context: Context, entry: String) {
    try {
        FileWriter(File(context.filesDir, "crash_log.txt"), true).use { w ->
            w.appendLine("[${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}] $entry")
        }
    } catch (_: Exception) {}
}

internal fun appendToInferenceErrors(context: Context, header: String, t: Throwable) {
    try {
        val sw = StringWriter()
        sw.appendLine(header)
        t.printStackTrace(PrintWriter(sw))
        FileWriter(File(context.filesDir, "inference_errors.txt"), true).use { w ->
            w.appendLine("[${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}] $header")
            w.appendLine(sw.toString())
        }
    } catch (_: Exception) {}
}

class VoiceControlApp : Application() {

    override fun onCreate() {
        super.onCreate()
        registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                val label = when (level) {
                    TRIM_MEMORY_COMPLETE -> "COMPLETE"
                    TRIM_MEMORY_MODERATE -> "MODERATE"
                    TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
                    TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN"
                    TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
                    TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
                    TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE"
                    else -> "UNKNOWN($level)"
                }
                appendToCrashLog(this@VoiceControlApp, "TRIM_MEMORY: $label")
            }

            override fun onConfigurationChanged(c: Configuration) {}

            override fun onLowMemory() {
                appendToCrashLog(this@VoiceControlApp, "LOW_MEMORY")
            }
        })
    }
}
