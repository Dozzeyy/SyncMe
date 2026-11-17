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

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import org.vahak.syncme.data.SyncTask
import java.util.concurrent.TimeUnit

object SyncScheduler {

    fun scheduleTaskWork(context: Context, task: SyncTask) {
        try {
            val workManager = WorkManager.getInstance(context)
            val workName = "sync_task_${task.id}"

            if (!task.isEnabled) {
                workManager.cancelUniqueWork(workName)
                return
            }

            val intervalMinutes = calculateIntervalMinutes(task)
            val inputData = Data.Builder()
                .putLong("task_id", task.id)
                .build()

            val periodicRequest = PeriodicWorkRequestBuilder<SyncWorker>(intervalMinutes, TimeUnit.MINUTES)
                .setInputData(inputData)
                .build()

            workManager.enqueueUniquePeriodicWork(
                workName,
                ExistingPeriodicWorkPolicy.UPDATE,
                periodicRequest
            )
        } catch (_: Exception) {
        
        }
    }

    fun triggerImmediateSync(context: Context, taskId: Long) {
        try {
            val workManager = WorkManager.getInstance(context)
            val workName = "immediate_sync_task_$taskId"

            val inputData = Data.Builder()
                .putLong("task_id", taskId)
                .build()

            val oneTimeRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(inputData)
                .build()

            workManager.enqueueUniqueWork(
                workName,
                ExistingWorkPolicy.REPLACE,
                oneTimeRequest
            )
        } catch (_: Exception) {}
    }

    fun cancelTaskWork(context: Context, taskId: Long) {
        try {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork("sync_task_$taskId")
        } catch (_: Exception) {}
    }

    private fun calculateIntervalMinutes(task: SyncTask): Long {
        return when (task.frequencyType) {
            SyncTask.FREQ_DAILY -> 24 * 60L
            SyncTask.FREQ_WEEKLY -> 7 * 24 * 60L
            SyncTask.FREQ_MONTHLY -> 30 * 24 * 60L
            SyncTask.FREQ_QUARTERLY -> 90 * 24 * 60L
            SyncTask.FREQ_CUSTOM -> {
                val value = task.customValue.coerceAtLeast(1)
                val base = when (task.customUnit) {
                    SyncTask.UNIT_DAYS -> 24 * 60L
                    SyncTask.UNIT_WEEKS -> 7 * 24 * 60L
                    SyncTask.UNIT_MONTHS -> 30 * 24 * 60L
                    SyncTask.UNIT_YEARS -> 365 * 24 * 60L
                    else -> 24 * 60L
                }
                (value * base).coerceAtLeast(15L)
            }
            else -> 24 * 60L
        }
    }
}
