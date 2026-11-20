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

package org.vahak.syncme.sync

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import org.vahak.syncme.SyncMeApplication

object SyncNotifier {
    fun notify(context: Context, taskId: Long, title: String, message: String, isError: Boolean) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val publicVersion = NotificationCompat.Builder(context, SyncMeApplication.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(if (isError) "SyncMe: a sync needs attention" else "SyncMe: sync complete")
            .build()

        val notification = NotificationCompat.Builder(context, SyncMeApplication.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(if (isError) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setAutoCancel(true)
            .build()

        manager.notify(NOTIFICATION_ID_BASE + (taskId % 100_000L).toInt(), notification)
    }

    private const val NOTIFICATION_ID_BASE = 10_000
}
