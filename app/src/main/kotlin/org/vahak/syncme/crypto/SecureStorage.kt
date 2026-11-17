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
import android.content.SharedPreferences
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
    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val encryptedPrefs: SharedPreferences? by lazy {
        try {
            EncryptedSharedPreferences.create(
                context,
                "syncme_secure_passphrases",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
    
            null
        }
    }

    fun isStorageAvailable(): Boolean {
        return encryptedPrefs != null
    }

    private fun getPrefsOrThrow(): SharedPreferences {
        return encryptedPrefs ?: throw SecureStorageException("Android Keystore / SecureStorage is unavailable.")
    }

    fun saveTaskPassphrase(taskId: Long, passphrase: String): StorageResult<Unit> {
        return try {
            val success = getPrefsOrThrow().edit().putString("task_passphrase_$taskId", passphrase).commit()
            if (success) StorageResult.Success(Unit) else StorageResult.StorageUnavailable("Failed to commit task passphrase to secure storage.")
        } catch (_: Exception) {
            StorageResult.StorageUnavailable("Secure storage operation failed.")
        }
    }

    fun getTaskPassphraseResult(taskId: Long): StorageResult<String> {
        val prefs = encryptedPrefs ?: return StorageResult.StorageUnavailable("Secure storage is unavailable on this device.")
        val secret = try {
            prefs.getString("task_passphrase_$taskId", null)
        } catch (_: Exception) {
            return StorageResult.StorageUnavailable("Secure storage read operation failed.")
        }
        return if (!secret.isNullOrBlank()) StorageResult.Success(secret) else StorageResult.MissingSecret
    }

    fun deleteTaskPassphrase(taskId: Long): StorageResult<Unit> {
        return try {
            val prefs = encryptedPrefs ?: return StorageResult.StorageUnavailable("Secure storage is unavailable on this device.")
            val success = prefs.edit().remove("task_passphrase_$taskId").commit()
            if (success) StorageResult.Success(Unit) else StorageResult.StorageUnavailable("Failed to commit task passphrase deletion.")
        } catch (_: Exception) {
            StorageResult.StorageUnavailable("Secure storage operation failed.")
        }
    }

    fun saveWebDavPassword(configId: Long, password: String): StorageResult<Unit> {
        return try {
            val success = getPrefsOrThrow().edit().putString("webdav_password_$configId", password).commit()
            if (success) StorageResult.Success(Unit) else StorageResult.StorageUnavailable("Failed to commit WebDAV password to secure storage.")
        } catch (_: Exception) {
            StorageResult.StorageUnavailable("Secure storage operation failed.")
        }
    }

    fun getWebDavPasswordResult(configId: Long): StorageResult<String> {
        val prefs = encryptedPrefs ?: return StorageResult.StorageUnavailable("Secure storage is unavailable on this device.")
        val secret = try {
            prefs.getString("webdav_password_$configId", null)
        } catch (_: Exception) {
            return StorageResult.StorageUnavailable("Secure storage read operation failed.")
        }
        return if (!secret.isNullOrBlank()) StorageResult.Success(secret) else StorageResult.MissingSecret
    }

    fun deleteWebDavPassword(configId: Long): StorageResult<Unit> {
        return try {
            val prefs = encryptedPrefs ?: return StorageResult.StorageUnavailable("Secure storage is unavailable on this device.")
            val success = prefs.edit().remove("webdav_password_$configId").commit()
            if (success) StorageResult.Success(Unit) else StorageResult.StorageUnavailable("Failed to commit WebDAV password deletion.")
        } catch (_: Exception) {
            StorageResult.StorageUnavailable("Secure storage operation failed.")
        }
    }
}
