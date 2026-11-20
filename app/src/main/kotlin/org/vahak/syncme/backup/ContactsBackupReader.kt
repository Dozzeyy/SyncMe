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

    fun generateContactsVcf(context: Context): ByteArray {
        val D = ContactsContract.Data.CONTENT_URI
        val mimeTypes = arrayOf(
            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE
        )
        val projection = arrayOf(
            ContactsContract.Data.CONTACT_ID, ContactsContract.Data.MIMETYPE,
            ContactsContract.Data.DATA1, ContactsContract.Data.DATA2, ContactsContract.Data.DATA3,
            ContactsContract.Data.DATA4, ContactsContract.Data.DATA5, ContactsContract.Data.DATA6,
            ContactsContract.Data.DATA7, ContactsContract.Data.DATA8, ContactsContract.Data.DATA9,
            ContactsContract.Data.DATA10
        )
        val selection = "${ContactsContract.Data.MIMETYPE} IN (${mimeTypes.joinToString(",") { "?" }})"

        val contacts = ArrayList<VCardContact>()
        var current = Builder()
        var currentId = Long.MIN_VALUE

        context.contentResolver.query(D, projection, selection, mimeTypes, "${ContactsContract.Data.CONTACT_ID} ASC, ${ContactsContract.Data._ID} ASC")?.use { c ->
            fun str(i: Int) = if (c.isNull(i)) "" else c.getString(i).orEmpty()
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (id != currentId) {
                    if (currentId != Long.MIN_VALUE) contacts.add(current.build())
                    current = Builder()
                    currentId = id
                }
                val d1 = str(2); val d2 = str(3); val d3 = str(4); val d4 = str(5); val d5 = str(6)
                val d6 = str(7); val d7 = str(8); val d8 = str(9); val d9 = str(10); val d10 = str(11)
                when (c.getString(1)) {
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> if (current.displayName.isEmpty()) {
                        current.displayName = d1; current.given = d2; current.family = d3
                        current.prefix = d4; current.middle = d5; current.suffix = d6
                    }
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE ->
                        current.phones.add(VCardContact.Typed(phoneType(d2.toIntOrNull() ?: 0), d1))
                    ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE ->
                        current.emails.add(VCardContact.Typed(emailType(d2.toIntOrNull() ?: 0), d1))
                    ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> {
                        if (current.org.isEmpty()) current.org = d1
                        if (current.title.isEmpty()) current.title = d4
                    }
                    ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE ->
                        current.addresses.add(
                            VCardContact.Address(
                                type = when (d2.toIntOrNull()) { 1 -> "HOME"; 2 -> "WORK"; else -> "OTHER" },
                                poBox = d5, street = d4, city = d7, region = d8, postalCode = d9, country = d10
                            )
                        )
                    ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE -> current.urls.add(d1)
                    ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE ->
                        if (d2.toIntOrNull() == ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY && current.birthday.isEmpty()) current.birthday = d1
                    ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE ->
                        current.note = if (current.note.isEmpty()) d1 else current.note + "\n" + d1
                }
            }
            if (currentId != Long.MIN_VALUE) contacts.add(current.build())
        }

        contacts.sortWith(compareBy({ it.displayName.lowercase() }, { it.displayName }))
        return VCardWriter.write(contacts)
    }

    private fun phoneType(t: Int) = when (t) {
        ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "HOME,VOICE"
        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "CELL"
        ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "WORK,VOICE"
        ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK -> "WORK,FAX"
        ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME -> "HOME,FAX"
        ContactsContract.CommonDataKinds.Phone.TYPE_PAGER -> "PAGER"
        ContactsContract.CommonDataKinds.Phone.TYPE_WORK_MOBILE -> "WORK,CELL"
        else -> "VOICE"
    }

    private fun emailType(t: Int) = when (t) {
        ContactsContract.CommonDataKinds.Email.TYPE_HOME -> "INTERNET,HOME"
        ContactsContract.CommonDataKinds.Email.TYPE_WORK -> "INTERNET,WORK"
        else -> "INTERNET"
    }

    private class Builder {
        var displayName = ""; var given = ""; var family = ""; var middle = ""; var prefix = ""; var suffix = ""
        var org = ""; var title = ""; var birthday = ""; var note = ""
        val phones = ArrayList<VCardContact.Typed>()
        val emails = ArrayList<VCardContact.Typed>()
        val addresses = ArrayList<VCardContact.Address>()
        val urls = ArrayList<String>()
        fun build() = VCardContact(
            displayName = displayName, familyName = family, givenName = given, middleName = middle,
            prefix = prefix, suffix = suffix, phones = phones, emails = emails, organization = org,
            title = title, addresses = addresses, urls = urls, birthday = birthday, note = note
        )
    }
}
