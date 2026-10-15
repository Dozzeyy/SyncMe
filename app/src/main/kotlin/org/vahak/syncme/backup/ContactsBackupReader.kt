/*
 * SyncMe
 * Copyright (C) 2026 Bhuvan (app.upstream242@passmail.com)
 * SPDX-License-Identifier: GPL-3.0-or-later

 This program is free software; you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation; either version 2 of the License, or
 (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.
 */

package org.vahak.syncme.backup

import android.content.Context
import android.provider.ContactsContract
import org.json.JSONArray
import org.json.JSONObject

object ContactsBackupReader {

    fun generateContactsJson(context: Context): ByteArray {
        val contactsArray = JSONArray()
        val resolver = context.contentResolver

        val cursor = resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            null, null, null,
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC"
        )

        cursor?.use { c ->
            val idIndex = c.getColumnIndex(ContactsContract.Contacts._ID)
            val nameIndex = c.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            val hasPhoneIndex = c.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER)

            while (c.moveToNext()) {
                val contactId = if (idIndex != -1) c.getString(idIndex) else ""
                val displayName = if (nameIndex != -1) c.getString(nameIndex) else "Unknown"
                val hasPhone = if (hasPhoneIndex != -1) c.getInt(hasPhoneIndex) else 0

                val contactObj = JSONObject()
                contactObj.put("id", contactId)
                contactObj.put("displayName", displayName)

                val phoneNumbers = JSONArray()
                if (hasPhone > 0) {
                    val phoneCursor = resolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        null,
                        "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                        arrayOf(contactId),
                        null
                    )
                    phoneCursor?.use { pc ->
                        val numIdx = pc.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                        val typeIdx = pc.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
                        while (pc.moveToNext()) {
                            val number = if (numIdx != -1) pc.getString(numIdx) else ""
                            val type = if (typeIdx != -1) pc.getInt(typeIdx) else 0
                            val phoneObj = JSONObject().apply {
                                put("number", number)
                                put("type", type)
                            }
                            phoneNumbers.put(phoneObj)
                        }
                    }
                }
                contactObj.put("phoneNumbers", phoneNumbers)

                val emails = JSONArray()
                val emailCursor = resolver.query(
                    ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                    null,
                    "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
                    arrayOf(contactId),
                    null
                )
                emailCursor?.use { ec ->
                    val emailIdx = ec.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
                    val typeIdx = ec.getColumnIndex(ContactsContract.CommonDataKinds.Email.TYPE)
                    while (ec.moveToNext()) {
                        val email = if (emailIdx != -1) ec.getString(emailIdx) else ""
                        val type = if (typeIdx != -1) ec.getInt(typeIdx) else 0
                        val emailObj = JSONObject().apply {
                            put("address", email)
                            put("type", type)
                        }
                        emails.put(emailObj)
                    }
                }
                contactObj.put("emails", emails)

                contactsArray.put(contactObj)
            }
        }

        val rootObj = JSONObject()
        rootObj.put("backupType", "CONTACTS")
        rootObj.put("version", 1)
        rootObj.put("timestamp", System.currentTimeMillis())
        rootObj.put("count", contactsArray.length())
        rootObj.put("contacts", contactsArray)

        return rootObj.toString(2).toByteArray(Charsets.UTF_8)
    }
}
