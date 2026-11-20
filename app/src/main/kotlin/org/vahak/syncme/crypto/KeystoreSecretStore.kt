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
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeystoreSecretStore(
    private val prefs: SharedPreferences,
    private val key: SecretKey
) {
    fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        val blob = Base64.decode(stored, Base64.NO_WRAP)
        if (blob.size <= IV_BYTES) throw SecureStorageException("Corrupt secret entry")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES), Charsets.UTF_8)
    }

    fun put(name: String, value: String): Boolean {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key) 
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val blob = iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return prefs.edit().putString(name, Base64.encodeToString(blob, Base64.NO_WRAP)).commit()
    }

    fun remove(name: String): Boolean = prefs.edit().remove(name).commit()

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        const val PREFS_NAME = "syncme_secrets_v2"
        private const val KEY_ALIAS = "syncme_secret_key_v2"


        fun open(context: Context): KeystoreSecretStore? = try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            KeystoreSecretStore(prefs, loadOrCreateKey())
        } catch (_: Exception) {
            null
        }

        private fun loadOrCreateKey(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            return try {
                generate(strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            } catch (_: Exception) {
                generate(strongBox = false) 
            }
        }

        private fun generate(strongBox: Boolean): SecretKey {
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .apply { if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true) }
                .build()
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(spec)
            return gen.generateKey()
        }
    }
}
