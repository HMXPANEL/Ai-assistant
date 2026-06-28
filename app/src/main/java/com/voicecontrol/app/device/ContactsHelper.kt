package com.voicecontrol.app.device

import android.content.Context
import android.provider.ContactsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ContactsHelper {

    suspend fun findContact(context: Context, name: String): String = withContext(Dispatchers.IO) {
        try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"), null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val displayName = it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME))
                    val number = it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
                    return@withContext "Contact found: $displayName — $number"
                }
            }
            "No contact found for '$name'."
        } catch (e: SecurityException) {
            "Contacts permission not granted. Grant contacts access in Settings."
        } catch (e: Exception) {
            "Error looking up contact: ${e.message}"
        }
    }

    suspend fun listRecentContacts(context: Context, limit: Int = 10): String = withContext(Dispatchers.IO) {
        try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null,
                "${ContactsContract.CommonDataKinds.Phone.TIMES_CONTACTED} DESC LIMIT $limit"
            )
            cursor?.use {
                val contacts = mutableListOf<String>()
                while (it.moveToNext()) {
                    val name = it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME))
                    val number = it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
                    contacts.add("• $name — $number")
                }
                if (contacts.isEmpty()) "No contacts found." else "Recent contacts:\n${contacts.joinToString("\n")}"
            } ?: "No contacts found."
        } catch (e: SecurityException) {
            "Contacts permission not granted. Grant contacts access in Settings."
        } catch (e: Exception) {
            "Error listing contacts: ${e.message}"
        }
    }
}
