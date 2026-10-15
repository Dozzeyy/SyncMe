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

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SyncedFileStateDao {
    @Query("SELECT * FROM synced_files WHERE taskId = :taskId")
    suspend fun getForTask(taskId: Long): List<SyncedFileState>

    @Query("SELECT * FROM synced_files WHERE taskId = :taskId AND relativePath = :relativePath LIMIT 1")
    suspend fun getByPath(taskId: Long, relativePath: String): SyncedFileState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(state: SyncedFileState): Long

    @Query("DELETE FROM synced_files WHERE taskId = :taskId")
    suspend fun deleteForTask(taskId: Long)
}
