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

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [WebDavConfig::class, SyncTask::class, SyncLog::class, SyncedFileState::class],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun webDavConfigDao(): WebDavConfigDao
    abstract fun syncTaskDao(): SyncTaskDao
    abstract fun syncLogDao(): SyncLogDao
    abstract fun syncedFileStateDao(): SyncedFileStateDao
}
