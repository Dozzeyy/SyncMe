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

package org.vahak.syncme.crypto

import org.vahak.syncme.data.WebDavConfigDao
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CredentialMigrationManager @Inject constructor(
    private val secureStorage: SecureStorage,
    private val webDavConfigDao: WebDavConfigDao
) {

    data class MigrationReport(
        val totalConfigs: Int,
        val migratedConfigs: Int,
        val failedConfigs: Int,
        val errors: List<String>
    )

    suspend fun getWebDavPasswordResult(configId: Long): StorageResult<String> {
        val result = secureStorage.getWebDavPasswordResult(configId)
        if (result is StorageResult.Success) {
            return result
        }
        if (result is StorageResult.StorageUnavailable) {
            return result
        }

        val rawConfig = webDavConfigDao.getById(configId) ?: return StorageResult.MissingSecret
        if (rawConfig.password.isNotBlank()) {
            when (val saveRes = secureStorage.saveWebDavPassword(configId, rawConfig.password)) {
                is StorageResult.Success -> {
                    val verifyRes = secureStorage.getWebDavPasswordResult(configId)
                    if (verifyRes is StorageResult.Success && verifyRes.data == rawConfig.password) {
                        try {
                            webDavConfigDao.update(rawConfig.copy(password = ""))
                        } catch (dbEx: Exception) {
    
                            return StorageResult.StorageUnavailable("Secure credential migrated, but legacy database cleanup failed for config $configId")
                        }
                    }
                    return verifyRes
                }
                is StorageResult.StorageUnavailable -> return saveRes
                is StorageResult.MissingSecret -> return saveRes
            }
        }

        return result
    }

    suspend fun migrateAllLegacyWebDavPasswords(): MigrationReport {
        var total = 0
        var migrated = 0
        var failed = 0
        val errors = mutableListOf<String>()

        try {
            val configs = webDavConfigDao.getAllList()
            total = configs.size
            for (config in configs) {
                if (config.password.isNotBlank()) {
                    val result = getWebDavPasswordResult(config.id)
                    if (result is StorageResult.Success) {
                        migrated++
                    } else {
                        failed++
                        val safeMsg = when (result) {
                            is StorageResult.StorageUnavailable -> result.message
                            else -> "Migration failed for config ${config.id}"
                        }
                        errors.add(safeMsg)
                    }
                }
            }
        } catch (e: Exception) {
            errors.add("Migration failed for WebDAV configuration list")
        }

        return MigrationReport(total, migrated, failed, errors)
    }
}
