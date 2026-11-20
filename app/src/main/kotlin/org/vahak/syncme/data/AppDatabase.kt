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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [WebDavConfig::class, SyncTask::class, SyncLog::class, SyncedFileState::class],
    version = 11,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun webDavConfigDao(): WebDavConfigDao
    abstract fun syncTaskDao(): SyncTaskDao
    abstract fun syncLogDao(): SyncLogDao
    abstract fun syncedFileStateDao(): SyncedFileStateDao

    companion object {
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sync_tasks` ADD COLUMN `destinationLocalUri` TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sync_tasks_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `webDavConfigId` INTEGER,
                        `localUri` TEXT NOT NULL,
                        `destinationLocalUri` TEXT NOT NULL DEFAULT '',
                        `isSingleFile` INTEGER NOT NULL,
                        `isEncrypted` INTEGER NOT NULL,
                        `syncDirection` TEXT NOT NULL,
                        `sourceType` TEXT NOT NULL,
                        `destinationMode` TEXT NOT NULL,
                        `frequencyType` TEXT NOT NULL,
                        `customValue` INTEGER NOT NULL,
                        `customUnit` TEXT NOT NULL,
                        `lastSyncTime` TEXT,
                        `lastSyncStatus` TEXT,
                        `lastSyncError` TEXT,
                        `isEnabled` INTEGER NOT NULL,
                        FOREIGN KEY(`webDavConfigId`) REFERENCES `webdav_configs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    INSERT INTO `sync_tasks_new` (
                        `id`, `name`, `webDavConfigId`, `localUri`, `destinationLocalUri`,
                        `isSingleFile`, `isEncrypted`, `syncDirection`, `sourceType`,
                        `destinationMode`, `frequencyType`, `customValue`, `customUnit`,
                        `lastSyncTime`, `lastSyncStatus`, `lastSyncError`, `isEnabled`
                    )
                    SELECT
                        `id`, `name`, CASE WHEN `webDavConfigId` = -1 THEN NULL ELSE `webDavConfigId` END, `localUri`, `destinationLocalUri`,
                        `isSingleFile`, `isEncrypted`, `syncDirection`, `sourceType`,
                        `destinationMode`, `frequencyType`, `customValue`, `customUnit`,
                        `lastSyncTime`, `lastSyncStatus`, `lastSyncError`, `isEnabled`
                    FROM `sync_tasks`
                    """.trimIndent()
                )

                db.execSQL("DROP TABLE `sync_tasks`")
                db.execSQL("ALTER TABLE `sync_tasks_new` RENAME TO `sync_tasks`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sync_tasks_webDavConfigId` ON `sync_tasks` (`webDavConfigId`)")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sync_tasks` ADD COLUMN `isMirrorSync` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `sync_tasks` ADD COLUMN `conflictStrategy` TEXT NOT NULL DEFAULT 'KEEP_BOTH'")
                db.execSQL("ALTER TABLE `sync_tasks` ADD COLUMN `filterCriteria` TEXT NOT NULL DEFAULT 'NONE'")
                db.execSQL("ALTER TABLE `sync_tasks` ADD COLUMN `excludedExtensions` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `sync_tasks` ADD COLUMN `maxFileSizeMb` INTEGER NOT NULL DEFAULT 0")
            }
        }

    
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE `sync_tasks` SET `isMirrorSync` = 0")
            }
        }


        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `webdav_configs` ADD COLUMN `certificatePin` TEXT NOT NULL DEFAULT ''")
            }
        }


        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `synced_files` ADD COLUMN `acceptedSourceModified` INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
