package com.voicecontrol.app.device

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock

object AlarmHelper {

    fun setAlarm(context: Context, hour: Int, minute: Int, label: String = "Assistant Alarm"): String {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return "Alarm set for ${"%02d".format(hour)}:${"%02d".format(minute)}."
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
        val cleaned = input.lowercase()
            .replace(Regex("set alarm|wake me up|wake me|alarm at|alarm for|for|at"), "")
            .replace("a.m.", "am").replace("p.m.", "pm")
            .trim()

        val r1 = Regex("""(\d{1,2})\s*[:.]?\s*(\d{2})\s*(am|pm)?""")
        r1.find(cleaned)?.let { m ->
            var h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            val ap = m.groupValues[3]
            if (ap == "pm" && h < 12) h += 12
            if (ap == "am" && h == 12) h = 0
            if (h in 0..23 && min in 0..59) return Pair(h, min)
        }

        val r2 = Regex("""(\d{1,2})\s*(am|pm)""")
        r2.find(cleaned)?.let { m ->
            var h = m.groupValues[1].toInt()
            if (m.groupValues[2] == "pm" && h < 12) h += 12
            if (m.groupValues[2] == "am" && h == 12) h = 0
            if (h in 0..23) return Pair(h, 0)
        }

        return null
    }
}
