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
import android.provider.CallLog
import org.json.JSONArray
import org.json.JSONObject

object CallLogBackupReader {

    fun generateCallLogJson(context: Context): ByteArray {
        val callLogsArray = JSONArray()
        val resolver = context.contentResolver

        val cursor = resolver.query(
            CallLog.Calls.CONTENT_URI,
            null, null, null,
            "${CallLog.Calls.DATE} DESC"
        )

        cursor?.use { c ->
            val idIdx = c.getColumnIndex(CallLog.Calls._ID)
            val numberIdx = c.getColumnIndex(CallLog.Calls.NUMBER)
            val nameIdx = c.getColumnIndex(CallLog.Calls.CACHED_NAME)
            val dateIdx = c.getColumnIndex(CallLog.Calls.DATE)
            val durationIdx = c.getColumnIndex(CallLog.Calls.DURATION)
            val typeIdx = c.getColumnIndex(CallLog.Calls.TYPE)

            while (c.moveToNext()) {
                val callObj = JSONObject().apply {
                    put("id", if (idIdx != -1) c.getLong(idIdx) else 0L)
                    put("number", if (numberIdx != -1) c.getString(numberIdx) else "")
                    put("cachedName", if (nameIdx != -1) c.getString(nameIdx) else "")
                    put("date", if (dateIdx != -1) c.getLong(dateIdx) else 0L)
                    put("durationSeconds", if (durationIdx != -1) c.getLong(durationIdx) else 0L)
                    put("type", if (typeIdx != -1) c.getInt(typeIdx) else 1)
                }
                callLogsArray.put(callObj)
            }
        }

        val rootObj = JSONObject().apply {
            put("backupType", "CALL_LOGS")
            put("version", 1)
            put("timestamp", System.currentTimeMillis())
            put("count", callLogsArray.length())
            put("callLogs", callLogsArray)
        }

        return rootObj.toString(2).toByteArray(Charsets.UTF_8)
    }
}
