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
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface WebDavConfigDao {
    @Query("SELECT * FROM webdav_configs ORDER BY name ASC")
    fun getAll(): Flow<List<WebDavConfig>>

    @Query("SELECT * FROM webdav_configs")
    suspend fun getAllList(): List<WebDavConfig>

    @Query("SELECT * FROM webdav_configs WHERE id = :id")
    suspend fun getById(id: Long): WebDavConfig?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(config: WebDavConfig): Long

    @Update
    suspend fun update(config: WebDavConfig)

    @Delete
    suspend fun delete(config: WebDavConfig)

    @Query("DELETE FROM webdav_configs WHERE id = :id")
    suspend fun deleteById(id: Long)
}
