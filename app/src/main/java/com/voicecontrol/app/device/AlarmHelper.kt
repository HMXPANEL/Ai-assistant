package com.voicecontrol.app.device

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock

object AlarmHelper {

    fun setAlarm(context: Context, hour: Int, minute: Int, label: String = "Assistant Alarm"): String {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Alarm set for ${"%02d".format(hour)}:${"%02d".format(minute)}."
        } catch (e: Exception) {
            "Sorry, couldn't set the alarm: ${e.message}"
        }
    }

    fun setTimer(context: Context, seconds: Int): String {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        val mins = seconds / 60
        val secs = seconds % 60
        return if (mins > 0) "Timer set for $mins minutes $secs seconds." else "Timer set for $secs seconds."
    }

    fun parseTimeFromText(input: String): Pair<Int, Int>? {
        return try {
            val lower = input.lowercase().trim()

            // Remove trigger words to isolate the time part
            val cleaned = lower
                .replace("set alarm at", "")
                .replace("set alarm for", "")
                .replace("alarm at", "")
                .replace("alarm for", "")
                .replace("wake me at", "")
                .replace("wake me up at", "")
                .trim()

            val isAm = cleaned.contains("am")
            val isPm = cleaned.contains("pm")

            // Strip am/pm and whitespace
            val timeOnly = cleaned.replace("am", "").replace("pm", "").trim()

            val hour: Int
            val minute: Int

            if (timeOnly.contains(":")) {
                // Format: "7:30" or "07:30"
                val parts = timeOnly.split(":")
                hour = parts[0].trim().toInt()
                minute = parts[1].trim().toInt()
            } else if (timeOnly.contains(" ")) {
                // Format: "7 30"
                val parts = timeOnly.trim().split("\\s+".toRegex())
                hour = parts[0].trim().toInt()
                minute = parts[1].trim().toInt()
            } else {
                // Format: "7" or "730"
                val num = timeOnly.trim().toInt()
                if (num > 99) {
                    // e.g. 730 means 7:30
                    hour = num / 100
                    minute = num % 100
                } else {
                    hour = num
                    minute = 0
                }
            }

            // Convert to 24h
            val hour24 = when {
                isPm && hour != 12 -> hour + 12
                isAm && hour == 12 -> 0
                else -> hour
            }

            // Validate
            if (hour24 < 0 || hour24 > 23 || minute < 0 || minute > 59) null
            else Pair(hour24, minute)

        } catch (e: Exception) {
            null // Return null for ANY parse error — never crash
        }
    }
}
