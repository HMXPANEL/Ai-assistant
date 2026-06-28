package com.voicecontrol.app.device

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object CalendarHelper {

    suspend fun getTodayEvents(context: Context): String = withContext(Dispatchers.IO) {
        getEvents(context, 0)
    }

    suspend fun getUpcomingEvents(context: Context, days: Int = 7): String = withContext(Dispatchers.IO) {
        getEvents(context, days)
    }

    private fun getEvents(context: Context, days: Int): String {
        try {
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val startOfDay = cal.timeInMillis

            val endOfRange = if (days > 0) {
                cal.add(Calendar.DAY_OF_YEAR, days)
                cal.timeInMillis
            } else {
                cal.set(Calendar.HOUR_OF_DAY, 23)
                cal.set(Calendar.MINUTE, 59)
                cal.set(Calendar.SECOND, 59)
                cal.timeInMillis
            }

            val cursor = context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(
                    CalendarContract.Events.TITLE,
                    CalendarContract.Events.DTSTART
                ),
                "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?",
                arrayOf(startOfDay.toString(), endOfRange.toString()),
                "${CalendarContract.Events.DTSTART} ASC"
            )

            return cursor?.use {
                val events = mutableListOf<String>()
                val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
                while (it.moveToNext()) {
                    val title = it.getString(it.getColumnIndexOrThrow(CalendarContract.Events.TITLE)) ?: "Untitled"
                    val start = it.getLong(it.getColumnIndexOrThrow(CalendarContract.Events.DTSTART))
                    events.add("• ${fmt.format(Date(start))} — $title")
                }
                if (events.isEmpty()) {
                    if (days > 0) "No events in the next $days days." else "No events today."
                } else {
                    val header = if (days > 0) "Events (next $days days):\n" else "Today's events:\n"
                    header + events.joinToString("\n")
                }
            } ?: "No events found."
        } catch (e: SecurityException) {
            "Calendar permission not granted. Grant calendar access in Settings."
        } catch (e: Exception) {
            "Error reading calendar: ${e.message}"
        }
    }

    fun addEvent(context: Context, title: String, startHour: Int, startMinute: Int, durationMinutes: Int = 60): String {
        try {
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, startHour)
            cal.set(Calendar.MINUTE, startMinute)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)

            val endCal = cal.clone() as Calendar
            endCal.add(Calendar.MINUTE, durationMinutes)

            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, title)
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, cal.timeInMillis)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endCal.timeInMillis)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Opening calendar to add event: $title"
        } catch (e: Exception) {
            "Couldn't open calendar: ${e.message}"
        }
    }
}
