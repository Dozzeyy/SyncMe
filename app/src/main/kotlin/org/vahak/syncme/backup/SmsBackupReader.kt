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
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

object SmsBackupReader {

    private val SMS_URI: Uri = Uri.parse("content://sms")

    fun generateSmsJson(context: Context): ByteArray {
        val smsArray = JSONArray()
        val resolver = context.contentResolver

        val cursor = resolver.query(
            SMS_URI,
            null, null, null,
            "date DESC"
        )

        cursor?.use { c ->
            val idIdx = c.getColumnIndex("_id")
            val addressIdx = c.getColumnIndex("address")
            val bodyIdx = c.getColumnIndex("body")
            val dateIdx = c.getColumnIndex("date")
            val dateSentIdx = c.getColumnIndex("date_sent")
            val typeIdx = c.getColumnIndex("type")
            val readIdx = c.getColumnIndex("read")
            val threadIdIdx = c.getColumnIndex("thread_id")

            while (c.moveToNext()) {
                val smsObj = JSONObject().apply {
                    put("id", if (idIdx != -1) c.getLong(idIdx) else 0L)
                    put("address", if (addressIdx != -1) c.getString(addressIdx) else "")
                    put("body", if (bodyIdx != -1) c.getString(bodyIdx) else "")
                    put("date", if (dateIdx != -1) c.getLong(dateIdx) else 0L)
                    put("dateSent", if (dateSentIdx != -1) c.getLong(dateSentIdx) else 0L)
                    put("type", if (typeIdx != -1) c.getInt(typeIdx) else 1)
                    put("read", if (readIdx != -1) c.getInt(readIdx) else 1)
                    put("threadId", if (threadIdIdx != -1) c.getLong(threadIdIdx) else 0L)
                }
                smsArray.put(smsObj)
            }
        }

        val rootObj = JSONObject().apply {
            put("backupType", "MESSAGES_SMS")
            put("version", 1)
            put("timestamp", System.currentTimeMillis())
            put("count", smsArray.length())
            put("messages", smsArray)
        }

        return rootObj.toString(2).toByteArray(Charsets.UTF_8)
    }
}
