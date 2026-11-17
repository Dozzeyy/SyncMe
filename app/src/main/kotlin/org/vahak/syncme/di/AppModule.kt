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

 
package org.vahak.syncme.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.vahak.syncme.data.AppDatabase
import org.vahak.syncme.data.SyncLogDao
import org.vahak.syncme.data.SyncTaskDao
import org.vahak.syncme.data.SyncedFileStateDao
import org.vahak.syncme.data.WebDavConfigDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "syncme_database"
        ).fallbackToDestructiveMigration(true).build()
    }

    @Provides
    fun provideWebDavConfigDao(database: AppDatabase): WebDavConfigDao {
        return database.webDavConfigDao()
    }

    @Provides
    fun provideSyncTaskDao(database: AppDatabase): SyncTaskDao {
        return database.syncTaskDao()
    }

    @Provides
    fun provideSyncLogDao(database: AppDatabase): SyncLogDao {
        return database.syncLogDao()
    }

    @Provides
    fun provideSyncedFileStateDao(database: AppDatabase): SyncedFileStateDao {
        return database.syncedFileStateDao()
    }
}
