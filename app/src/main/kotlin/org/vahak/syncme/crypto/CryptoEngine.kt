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

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

data class EncryptedMetadata(
    val originalName: String,
    val relativePath: String,
    val mimeType: String,
    val fileSize: Long,
    val lastModified: Long
)

data class DecryptedResult(
    val metadata: EncryptedMetadata,
    val payloadBytes: ByteArray
)

@Singleton
class CryptoEngine @Inject constructor() {

    companion object {
        private val MAGIC_HEADER = "SYNCME_ENC_V1".toByteArray(Charsets.UTF_8)
        private const val PBKDF2_ITERATIONS = 650000
        private const val KEY_LENGTH_BITS = 256
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128
        const val SALT_LENGTH_BYTES = 16
        const val MAX_METADATA_BYTES = 1 * 1024 * 1024 
        const val MAX_PAYLOAD_BYTES = 100 * 1024 * 1024 
    }

    private val secureRandom = SecureRandom()

    fun generateSalt(): ByteArray {
        val salt = ByteArray(SALT_LENGTH_BYTES)
        secureRandom.nextBytes(salt)
        return salt
    }

    fun deriveKey(passphrase: String, salt: ByteArray): SecretKey {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val keyBytes = factory.generateSecret(spec).encoded
            return SecretKeySpec(keyBytes, "AES")
        } finally {
            spec.clearPassword() 
        }
    }

    fun encryptContainerWithKey(
        secretKey: SecretKey,
        salt: ByteArray,
        metadata: EncryptedMetadata,
        rawPayloadBytes: ByteArray
    ): ByteArray {
    
        val metadataJson = JSONObject().apply {
            put("originalName", metadata.originalName)
            put("relativePath", metadata.relativePath)
            put("mimeType", metadata.mimeType)
            put("fileSize", metadata.fileSize)
            put("lastModified", metadata.lastModified)
        }.toString().toByteArray(Charsets.UTF_8)

        val metadataIv = ByteArray(GCM_IV_LENGTH_BYTES)
        secureRandom.nextBytes(metadataIv)

        val metadataCipher = Cipher.getInstance("AES/GCM/NoPadding")
        metadataCipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, metadataIv))
        val encryptedMetadata = metadataCipher.doFinal(metadataJson)

        val payloadIv = ByteArray(GCM_IV_LENGTH_BYTES)
        secureRandom.nextBytes(payloadIv)

        val payloadCipher = Cipher.getInstance("AES/GCM/NoPadding")
        payloadCipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, payloadIv))
        val encryptedPayload = payloadCipher.doFinal(rawPayloadBytes)

        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        dos.write(MAGIC_HEADER)
        dos.write(salt)
        dos.write(metadataIv)
        dos.writeInt(encryptedMetadata.size)
        dos.write(encryptedMetadata)
        dos.write(payloadIv)
        dos.writeInt(encryptedPayload.size)
        dos.write(encryptedPayload)
        dos.flush()

        return baos.toByteArray()
    }

    fun encryptContainer(
        passphrase: String,
        metadata: EncryptedMetadata,
        rawPayloadBytes: ByteArray
    ): ByteArray {
        val salt = generateSalt()
        val secretKey = deriveKey(passphrase, salt)
        return encryptContainerWithKey(secretKey, salt, metadata, rawPayloadBytes)
    }

    fun decryptContainerWithKey(
        secretKey: SecretKey,
        containerBytes: ByteArray
    ): DecryptedResult {
        val bais = ByteArrayInputStream(containerBytes)
        val dis = DataInputStream(bais)

        val header = ByteArray(MAGIC_HEADER.size)
        dis.readFully(header)
        if (!header.contentEquals(MAGIC_HEADER)) {
            throw IllegalArgumentException("Invalid container header. File may be corrupted or not encrypted by SyncMe.")
        }

        val salt = ByteArray(SALT_LENGTH_BYTES)
        dis.readFully(salt)

    
        val metadataIv = ByteArray(GCM_IV_LENGTH_BYTES)
        dis.readFully(metadataIv)

        val metadataLen = dis.readInt()
        val remainingAfterMetaIv = bais.available()
        if (metadataLen <= 0 || metadataLen > MAX_METADATA_BYTES || metadataLen > remainingAfterMetaIv) {
            throw IllegalArgumentException("Invalid container metadata length ($metadataLen bytes, stream remaining: $remainingAfterMetaIv bytes)")
        }
        val encryptedMetadata = ByteArray(metadataLen)
        dis.readFully(encryptedMetadata)

        val metadataCipher = Cipher.getInstance("AES/GCM/NoPadding")
        metadataCipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, metadataIv))
        val decryptedMetadataJson = String(metadataCipher.doFinal(encryptedMetadata), Charsets.UTF_8)

        val jsonObj = JSONObject(decryptedMetadataJson)
        val metadata = EncryptedMetadata(
            originalName = jsonObj.getString("originalName"),
            relativePath = jsonObj.getString("relativePath"),
            mimeType = jsonObj.optString("mimeType", "application/octet-stream"),
            fileSize = jsonObj.getLong("fileSize"),
            lastModified = jsonObj.getLong("lastModified")
        )

        val payloadIv = ByteArray(GCM_IV_LENGTH_BYTES)
        dis.readFully(payloadIv)

        val payloadLen = dis.readInt()
        val remainingAfterPayloadIv = bais.available()
        if (payloadLen <= 0 || payloadLen > MAX_PAYLOAD_BYTES || payloadLen > remainingAfterPayloadIv) {
            throw IllegalArgumentException("Invalid container payload length ($payloadLen bytes, stream remaining: $remainingAfterPayloadIv bytes)")
        }
        val encryptedPayload = ByteArray(payloadLen)
        dis.readFully(encryptedPayload)

        if (bais.available() > 0) {
            throw IllegalArgumentException("Corrupted container: unexpected trailing bytes (${bais.available()} bytes)")
        }

        val payloadCipher = Cipher.getInstance("AES/GCM/NoPadding")
        payloadCipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, payloadIv))
        val payloadBytes = payloadCipher.doFinal(encryptedPayload)

        return DecryptedResult(metadata, payloadBytes)
    }

    fun decryptContainer(
        passphrase: String,
        containerBytes: ByteArray
    ): DecryptedResult {
        val bais = ByteArrayInputStream(containerBytes)
        val dis = DataInputStream(bais)

        val header = ByteArray(MAGIC_HEADER.size)
        dis.readFully(header)
        if (!header.contentEquals(MAGIC_HEADER)) {
            throw IllegalArgumentException("Invalid container header. File may be corrupted or not encrypted by SyncMe.")
        }

        val salt = ByteArray(SALT_LENGTH_BYTES)
        dis.readFully(salt)

        val secretKey = deriveKey(passphrase, salt)
        return decryptContainerWithKey(secretKey, containerBytes)
    }
}
