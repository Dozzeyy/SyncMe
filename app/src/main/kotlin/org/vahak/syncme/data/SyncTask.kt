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

package org.vahak.syncme.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sync_tasks",
    foreignKeys = [
        ForeignKey(
            entity = WebDavConfig::class,
            parentColumns = ["id"],
            childColumns = ["webDavConfigId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["webDavConfigId"])]
)
data class SyncTask(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val webDavConfigId: Long,
    val localUri: String,
    val isSingleFile: Boolean = false,
    val isEncrypted: Boolean = false,
    val syncDirection: String = DIRECTION_LOCAL_TO_REMOTE,
    val sourceType: String = SOURCE_FILES,
    val destinationMode: String = DESTINATION_REMOTE,
    val frequencyType: String = FREQ_DAILY,
    val customValue: Int = 1,
    val customUnit: String = UNIT_DAYS,
    val lastSyncTime: String? = null,
    val lastSyncStatus: String? = STATUS_NEVER,
    val lastSyncError: String? = null,
    val isEnabled: Boolean = true
) {
    companion object {
        const val DIRECTION_LOCAL_TO_REMOTE = "LOCAL_TO_REMOTE"
        const val DIRECTION_REMOTE_TO_LOCAL = "REMOTE_TO_LOCAL"

        const val SOURCE_FILES = "FILES"
        const val SOURCE_CONTACTS = "CONTACTS"
        const val SOURCE_MESSAGES = "MESSAGES"
        const val SOURCE_CALL_LOGS = "CALL_LOGS"

        const val DESTINATION_REMOTE = "REMOTE"
        const val DESTINATION_LOCAL = "LOCAL"

        const val FREQ_DAILY = "DAILY"
        const val FREQ_WEEKLY = "WEEKLY"
        const val FREQ_MONTHLY = "MONTHLY"
        const val FREQ_QUARTERLY = "QUARTERLY"
        const val FREQ_CUSTOM = "CUSTOM"

        const val UNIT_DAYS = "DAYS"
        const val UNIT_WEEKS = "WEEKS"
        const val UNIT_MONTHS = "MONTHS"
        const val UNIT_YEARS = "YEARS"

        const val STATUS_NEVER = "NEVER"
        const val STATUS_SUCCESS = "SUCCESS"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_IN_PROGRESS = "IN_PROGRESS"
    }

    fun getFrequencyDescription(): String {
        return when (frequencyType) {
            FREQ_DAILY -> "Daily"
            FREQ_WEEKLY -> "Weekly"
            FREQ_MONTHLY -> "Monthly"
            FREQ_QUARTERLY -> "Quarterly"
            FREQ_CUSTOM -> {
                val unitLabel = when (customUnit) {
                    UNIT_DAYS -> if (customValue == 1) "Day" else "Days"
                    UNIT_WEEKS -> if (customValue == 1) "Week" else "Weeks"
                    UNIT_MONTHS -> if (customValue == 1) "Month" else "Months"
                    UNIT_YEARS -> if (customValue == 1) "Year" else "Years"
                    else -> customUnit.lowercase()
                }
                "Every $customValue $unitLabel"
            }
            else -> frequencyType
        }
    }
}
