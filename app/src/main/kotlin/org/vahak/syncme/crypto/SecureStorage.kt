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

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

class SecureStorageException(message: String, cause: Throwable? = null) : Exception(message, cause)

sealed class StorageResult<out T> {
    data class Success<T>(val data: T) : StorageResult<T>()
    object MissingSecret : StorageResult<Nothing>()
    data class StorageUnavailable(val message: String) : StorageResult<Nothing>()
}

@Singleton
class SecureStorage @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    @Volatile
    private var openedStore: KeystoreSecretStore? = null

    private val store: KeystoreSecretStore?
        get() = openedStore ?: synchronized(this) {
            openedStore ?: KeystoreSecretStore.open(context)?.also {
                migrateLegacyEntries(it)
                openedStore = it
            }
        }

    private fun migrateLegacyEntries(target: KeystoreSecretStore) {
        try {
            val legacyFile = java.io.File(context.applicationInfo.dataDir, "shared_prefs/$LEGACY_PREFS_NAME.xml")
            if (!legacyFile.exists()) return
            val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val legacy = EncryptedSharedPreferences.create(
                context, LEGACY_PREFS_NAME, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            for ((name, value) in legacy.all) {
                val secret = value as? String ?: continue
                if (!target.put(name, secret) || target.get(name) != secret) return
            }
            context.deleteSharedPreferences(LEGACY_PREFS_NAME)
        } catch (_: Exception) {
        
        }
    }

    fun isStorageAvailable(): Boolean {
        return store != null
    }

    private fun getStoreOrThrow(): KeystoreSecretStore {
        return store ?: throw SecureStorageException("Android Keystore / SecureStorage is unavailable.")
    }

    private fun save(name: String, value: String, failMsg: String): StorageResult<Unit> {
        return try {
            if (getStoreOrThrow().put(name, value)) StorageResult.Success(Unit) else StorageResult.StorageUnavailable(failMsg)
        } catch (_: Exception) {
            StorageResult.StorageUnavailable("Secure storage operation failed.")
        }
    }

    private fun read(name: String): StorageResult<String> {
        val s = store ?: return StorageResult.StorageUnavailable("Secure storage is unavailable on this device.")
        val secret = try {
            s.get(name)
        } catch (_: Exception) {
            return StorageResult.StorageUnavailable("Secure storage read operation failed.")
        }
        return if (!secret.isNullOrBlank()) StorageResult.Success(secret) else StorageResult.MissingSecret
    }

    private fun delete(name: String, failMsg: String): StorageResult<Unit> {
        return try {
            val s = store ?: return StorageResult.StorageUnavailable("Secure storage is unavailable on this device.")
            if (s.remove(name)) StorageResult.Success(Unit) else StorageResult.StorageUnavailable(failMsg)
        } catch (_: Exception) {
            StorageResult.StorageUnavailable("Secure storage operation failed.")
        }
    }

    fun saveTaskPassphrase(taskId: Long, passphrase: String): StorageResult<Unit> =
        save("task_passphrase_$taskId", passphrase, "Failed to commit task passphrase to secure storage.")

    fun getTaskPassphraseResult(taskId: Long): StorageResult<String> = read("task_passphrase_$taskId")

    fun deleteTaskPassphrase(taskId: Long): StorageResult<Unit> =
        delete("task_passphrase_$taskId", "Failed to commit task passphrase deletion.")

    fun saveWebDavPassword(configId: Long, password: String): StorageResult<Unit> =
        save("webdav_password_$configId", password, "Failed to commit WebDAV password to secure storage.")

    fun getWebDavPasswordResult(configId: Long): StorageResult<String> = read("webdav_password_$configId")

    fun deleteWebDavPassword(configId: Long): StorageResult<Unit> =
        delete("webdav_password_$configId", "Failed to commit WebDAV password deletion.")

    private companion object {
        const val LEGACY_PREFS_NAME = "syncme_secure_passphrases"
    }
}
