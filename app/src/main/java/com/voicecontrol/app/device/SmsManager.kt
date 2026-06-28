package com.voicecontrol.app.device

import android.content.Context
import android.provider.ContactsContract
import android.provider.Telephony
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SmsManager {

    suspend fun sendSms(context: Context, contactName: String, message: String): String = withContext(Dispatchers.IO) {
        try {
            val number = lookupPhoneNumber(context, contactName)
                ?: return@withContext "Contact '$contactName' not found."
            android.telephony.SmsManager.getDefault().sendTextMessage(number, null, message, null, null)
            "SMS sent to $contactName."
        } catch (e: SecurityException) {
            "SMS permission not granted. Grant SMS access in Settings > Apps > AI Assistant > Permissions."
        } catch (e: Exception) {
            "Failed to send SMS: ${e.message}"
        }
    }

    suspend fun readRecentSms(context: Context, limit: Int = 5): String = withContext(Dispatchers.IO) {
        try {
            val cursor = context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI, null, null, null,
                "${Telephony.Sms.Inbox.DEFAULT_SORT_ORDER} DESC LIMIT $limit"
            )
            cursor?.use {
                val msgs = mutableListOf<String>()
                while (it.moveToNext()) {
                    val addr = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.Inbox.ADDRESS))
                    val body = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.Inbox.BODY))
                    msgs.add("• $addr: $body")
                }
                if (msgs.isEmpty()) "No messages found." else "Recent messages:\n${msgs.joinToString("\n")}"
            } ?: "No messages found."
        } catch (e: SecurityException) {
            "SMS permission not granted. Grant SMS access in Settings > Apps > AI Assistant > Permissions."
        } catch (e: Exception) {
            "Failed to read messages: ${e.message}"
        }
    }

    private fun lookupPhoneNumber(context: Context, name: String): String? {
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"), null
        )
        cursor?.use {
            if (it.moveToFirst())
                return it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
        }
        return null
    }
}
