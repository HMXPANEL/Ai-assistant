package com.voicecontrol.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ConversationMemory(context: Context) {

    private val file = File(context.filesDir, "conversation_history.json")

    fun saveMessage(role: String, text: String) {
        val entries = loadEntries()
        val entry = JSONObject().apply {
            put("role", role)
            put("text", text)
            put("timestamp", System.currentTimeMillis())
        }
        entries.put(entry)
        file.writeText(entries.toString())
    }

    fun getHistory(): List<Pair<String, String>> {
        val entries = loadEntries()
        val total = entries.length()
        val start = maxOf(0, total - 20)
        return (start until total).map { i ->
            val obj = entries.getJSONObject(i)
            Pair(obj.getString("role"), obj.getString("text"))
        }
    }

    fun clearHistory() {
        file.delete()
    }

    private fun loadEntries(): JSONArray {
        return try {
            if (file.exists()) JSONArray(file.readText()) else JSONArray()
        } catch (_: Exception) {
            JSONArray()
        }
    }
}
