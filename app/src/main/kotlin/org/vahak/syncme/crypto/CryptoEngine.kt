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
import javax.crypto.Mac
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
        private val MAGIC_HEADER_V1 = "SYNCME_ENC_V1".toByteArray(Charsets.UTF_8)
        private val MAGIC_HEADER_V2 = "SYNCME_ENC_V2".toByteArray(Charsets.UTF_8)
        private val HKDF_INFO_FILE_KEY = "SYNCME_V2_FILE_KEY".toByteArray(Charsets.UTF_8)

        const val KDF_PBKDF2_HMAC_SHA256 = 1
        const val PBKDF2_ITERATIONS = 650000
        const val MIN_ACCEPTED_ITERATIONS = 100_000
    
        const val MAX_ACCEPTED_ITERATIONS = 1_000_000

        private val HKDF_INFO_NAME_KEY = "SYNCME_V2_NAME_KEY".toByteArray(Charsets.UTF_8)
    
        private val LEGACY_NAME_KEY_SALT: ByteArray =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest("SYNCME_V2_NAME_SALT".toByteArray(Charsets.UTF_8)).copyOf(16)

    
        const val NAME_SALT_MARKER_FILE = "syncme_names.v2"
        private val NAME_SALT_MARKER_MAGIC = "SYNCME_NAMES_V2".toByteArray(Charsets.UTF_8)
        const val NAME_SALT_MARKER_LENGTH = 15 + 16
        private const val KEY_LENGTH_BITS = 256
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val GCM_TAG_LENGTH_BYTES = 16
        private const val FILE_SALT_LENGTH_BYTES = 16
        const val SALT_LENGTH_BYTES = 16
        const val MAX_METADATA_BYTES = 1 * 1024 * 1024 // 1 MB max for metadata JSON
        const val MAX_PAYLOAD_BYTES = 100 * 1024 * 1024 // 100 MB max payload for in-memory container (P2)

        
        private val V2_HEADER_LENGTH = MAGIC_HEADER_V2.size + 1 + 4 + SALT_LENGTH_BYTES + FILE_SALT_LENGTH_BYTES + GCM_IV_LENGTH_BYTES
        private val MAX_V2_CIPHERTEXT_BYTES = 4 + MAX_METADATA_BYTES + MAX_PAYLOAD_BYTES + GCM_TAG_LENGTH_BYTES
    }

    private val secureRandom = SecureRandom()

    fun generateSalt(): ByteArray {
        val salt = ByteArray(SALT_LENGTH_BYTES)
        secureRandom.nextBytes(salt)
        return salt
    }

    
    fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int = PBKDF2_ITERATIONS): SecretKey {
        val chars = passphrase.toCharArray()
        val spec = PBEKeySpec(chars, salt, iterations, KEY_LENGTH_BITS)
        try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val keyBytes = factory.generateSecret(spec).encoded
            return SecretKeySpec(keyBytes, "AES")
        } finally {
            spec.clearPassword() 
            chars.fill('\u0000')
        }
    }

    fun encryptContainerWithKey(
        secretKey: SecretKey,
        salt: ByteArray,
        metadata: EncryptedMetadata,
        rawPayloadBytes: ByteArray,
        iterations: Int = PBKDF2_ITERATIONS
    ): ByteArray {
        require(salt.size == SALT_LENGTH_BYTES) { "Invalid salt length" }
        require(rawPayloadBytes.size <= MAX_PAYLOAD_BYTES) {
            "File is too large to encrypt in memory (${rawPayloadBytes.size} bytes, limit $MAX_PAYLOAD_BYTES)"
        }

        
        val metadataJson = JSONObject().apply {
            put("originalName", metadata.originalName)
            put("relativePath", metadata.relativePath)
            put("mimeType", metadata.mimeType)
            put("fileSize", rawPayloadBytes.size.toLong())
            put("lastModified", metadata.lastModified)
        }.toString().toByteArray(Charsets.UTF_8)
        require(metadataJson.size <= MAX_METADATA_BYTES) { "Metadata too large" }

        val fileSalt = ByteArray(FILE_SALT_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
        val iv = ByteArray(GCM_IV_LENGTH_BYTES).also { secureRandom.nextBytes(it) }

        val headerStream = ByteArrayOutputStream(V2_HEADER_LENGTH)
        DataOutputStream(headerStream).apply {
            write(MAGIC_HEADER_V2)
            writeByte(KDF_PBKDF2_HMAC_SHA256)
            writeInt(iterations)
            write(salt)
            write(fileSalt)
            write(iv)
            flush()
        }
        val header = headerStream.toByteArray()

        val plaintext = ByteArray(4 + metadataJson.size + rawPayloadBytes.size)
        java.nio.ByteBuffer.wrap(plaintext).apply {
            putInt(metadataJson.size)
            put(metadataJson)
            put(rawPayloadBytes)
        }

        val fileKey = deriveFileKey(secretKey, fileSalt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, fileKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        cipher.updateAAD(header)

        val ctLen = plaintext.size + GCM_TAG_LENGTH_BYTES
        val out = ByteArray(header.size + 4 + ctLen)
        System.arraycopy(header, 0, out, 0, header.size)
        java.nio.ByteBuffer.wrap(out).putInt(header.size, ctLen)
        try {
            val written = cipher.doFinal(plaintext, 0, plaintext.size, out, header.size + 4)
            check(written == ctLen) { "Unexpected ciphertext length" }
        } finally {
            plaintext.fill(0)
        }
        return out
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
        containerBytes: ByteArray,
        allowLegacyV1: Boolean = true
    ): DecryptedResult {
        val magic = readMagic(containerBytes)
        return when {
            magic.contentEquals(MAGIC_HEADER_V2) -> decryptV2WithKey(secretKey, containerBytes)
            magic.contentEquals(MAGIC_HEADER_V1) -> {
                if (!allowLegacyV1) throw legacyRejected()
                decryptV1WithKey(secretKey, containerBytes)
            }
            else -> throw invalidHeader()
        }
    }

    fun decryptContainer(
        passphrase: String,
        containerBytes: ByteArray,
        allowLegacyV1: Boolean = true
    ): DecryptedResult {
        val dis = DataInputStream(ByteArrayInputStream(containerBytes))
        val magic = ByteArray(MAGIC_HEADER_V2.size)
        dis.readFully(magic)

        return when {
            magic.contentEquals(MAGIC_HEADER_V2) -> {
                val kdfId = dis.readUnsignedByte()
                if (kdfId != KDF_PBKDF2_HMAC_SHA256) {
                    throw IllegalArgumentException("Unsupported key derivation function ($kdfId)")
                }
                val iterations = dis.readInt()
                requireSafeIterations(iterations)
                val salt = ByteArray(SALT_LENGTH_BYTES)
                dis.readFully(salt)
                decryptV2WithKey(deriveKey(passphrase, salt, iterations), containerBytes)
            }
            magic.contentEquals(MAGIC_HEADER_V1) -> {
                if (!allowLegacyV1) throw legacyRejected()
                val salt = ByteArray(SALT_LENGTH_BYTES)
                dis.readFully(salt)
                decryptV1WithKey(deriveKey(passphrase, salt), containerBytes)
            }
            else -> throw invalidHeader()
        }
    }

    // ---------------------------------------------------------------- V2

    private fun decryptV2WithKey(secretKey: SecretKey, containerBytes: ByteArray): DecryptedResult {
        val bais = ByteArrayInputStream(containerBytes)
        val dis = DataInputStream(bais)

        dis.readFully(ByteArray(MAGIC_HEADER_V2.size))
        val kdfId = dis.readUnsignedByte()
        if (kdfId != KDF_PBKDF2_HMAC_SHA256) {
            throw IllegalArgumentException("Unsupported key derivation function ($kdfId)")
        }
        requireSafeIterations(dis.readInt())
        dis.readFully(ByteArray(SALT_LENGTH_BYTES))
        val fileSalt = ByteArray(FILE_SALT_LENGTH_BYTES)
        dis.readFully(fileSalt)
        val iv = ByteArray(GCM_IV_LENGTH_BYTES)
        dis.readFully(iv)

        val header = containerBytes.copyOfRange(0, V2_HEADER_LENGTH)

        val ciphertextLength = dis.readInt()
        val remaining = bais.available()
        if (ciphertextLength < GCM_TAG_LENGTH_BYTES + 4 ||
            ciphertextLength > MAX_V2_CIPHERTEXT_BYTES ||
            ciphertextLength > remaining
        ) {
            throw IllegalArgumentException("Invalid container ciphertext length ($ciphertextLength bytes, stream remaining: $remaining bytes)")
        }
        val ciphertext = ByteArray(ciphertextLength)
        dis.readFully(ciphertext)

        if (bais.available() > 0) {
            throw IllegalArgumentException("Corrupted container: unexpected trailing bytes (${bais.available()} bytes)")
        }

        val fileKey = deriveFileKey(secretKey, fileSalt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, fileKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        cipher.updateAAD(header)
        val plaintext = cipher.doFinal(ciphertext)

        try {
            val metadataLength = DataInputStream(ByteArrayInputStream(plaintext)).readInt()
            if (metadataLength <= 0 || metadataLength > MAX_METADATA_BYTES || metadataLength > plaintext.size - 4) {
                throw IllegalArgumentException("Invalid container metadata length ($metadataLength bytes)")
            }
            val metadata = parseMetadata(String(plaintext, 4, metadataLength, Charsets.UTF_8))
            val payload = plaintext.copyOfRange(4 + metadataLength, plaintext.size)

            if (metadata.fileSize != payload.size.toLong()) {
                throw IllegalArgumentException("Container metadata does not match payload size")
            }
            return DecryptedResult(metadata, payload)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun deriveFileKey(masterKey: SecretKey, fileSalt: ByteArray): SecretKey =
        hkdfSha256(masterKey.encoded, fileSalt, HKDF_INFO_FILE_KEY)

    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray): SecretKey {
        try {
            val extract = Mac.getInstance("HmacSHA256")
            extract.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
            val prk = extract.doFinal(ikm)

            val expand = Mac.getInstance("HmacSHA256")
            expand.init(SecretKeySpec(prk, "HmacSHA256"))
            expand.update(info)
            expand.update(0x01.toByte())
            val okm = expand.doFinal() 
            prk.fill(0)
            return SecretKeySpec(okm, "AES")
        } finally {
            ikm.fill(0)
        }
    }

    fun deriveNameKey(passphrase: String, nameSalt: ByteArray = LEGACY_NAME_KEY_SALT): SecretKey {
        require(nameSalt.size == SALT_LENGTH_BYTES) { "Invalid name salt length" }
        val base = deriveKey(passphrase, nameSalt, PBKDF2_ITERATIONS)
        return hkdfSha256(base.encoded, nameSalt, HKDF_INFO_NAME_KEY)
    }


    fun encodeNameSaltMarker(salt: ByteArray): ByteArray {
        require(salt.size == SALT_LENGTH_BYTES) { "Invalid name salt length" }
        return NAME_SALT_MARKER_MAGIC + salt
    }

    fun parseNameSaltMarker(bytes: ByteArray): ByteArray? {
        if (bytes.size != NAME_SALT_MARKER_LENGTH) return null
        if (!bytes.copyOfRange(0, NAME_SALT_MARKER_MAGIC.size).contentEquals(NAME_SALT_MARKER_MAGIC)) return null
        return bytes.copyOfRange(NAME_SALT_MARKER_MAGIC.size, bytes.size)
    }

    
    fun encryptedName(nameKey: SecretKey, relativePath: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(nameKey.encoded, "HmacSHA256"))
        val digest = mac.doFinal(relativePath.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(32)
        for (i in 0 until 16) hex.append(String.format("%02x", digest[i]))
        return EncryptedNames.PREFIX + hex + EncryptedNames.SUFFIX
    }

    inner class DecryptSession(
        private val passphrase: String,
        private val maxAuthFailures: Int = 5,
        private val allowLegacyV1: Boolean = false
    ) {
        private val keys = HashMap<String, SecretKey>()
        private var failures = 0

        fun decrypt(containerBytes: ByteArray): DecryptedResult {
            if (failures >= maxAuthFailures) {
                throw IllegalStateException("Too many files failed authentication; wrong passphrase or tampered server data")
            }
            val dis = DataInputStream(ByteArrayInputStream(containerBytes))
            val magic = readMagic(containerBytes)
            dis.readFully(ByteArray(MAGIC_HEADER_V2.size))
            val (cacheKey, iterations, salt) = when {
                magic.contentEquals(MAGIC_HEADER_V2) -> {
                    validateV2Structure(containerBytes)
                    val kdfId = dis.readUnsignedByte()
                    if (kdfId != KDF_PBKDF2_HMAC_SHA256) throw IllegalArgumentException("Unsupported key derivation function ($kdfId)")
                    val it = dis.readInt()
                    requireSafeIterations(it)
                    val s = ByteArray(SALT_LENGTH_BYTES).also { b -> dis.readFully(b) }
                    Triple("v2:$it:${s.joinToString("") { b -> "%02x".format(b) }}", it, s)
                }
                magic.contentEquals(MAGIC_HEADER_V1) -> {
                    if (!allowLegacyV1) throw legacyRejected()
                    val s = ByteArray(SALT_LENGTH_BYTES).also { b -> dis.readFully(b) }
                    Triple("v1:${s.joinToString("") { b -> "%02x".format(b) }}", PBKDF2_ITERATIONS, s)
                }
                else -> throw invalidHeader()
            }
            try {
                val key = keys.getOrPut(cacheKey) { deriveKey(passphrase, salt, iterations) }
                return decryptContainerWithKey(key, containerBytes, allowLegacyV1)
            } catch (e: Exception) {
                failures++
                throw e
            }
        }
    }

    private fun validateV2Structure(containerBytes: ByteArray) {
        if (containerBytes.size < V2_HEADER_LENGTH + 4 + GCM_TAG_LENGTH_BYTES + 4) {
            throw IllegalArgumentException("Container too short")
        }
        val ctLen = java.nio.ByteBuffer.wrap(containerBytes).getInt(V2_HEADER_LENGTH)
        if (ctLen < GCM_TAG_LENGTH_BYTES + 4 ||
            ctLen > MAX_V2_CIPHERTEXT_BYTES ||
            ctLen != containerBytes.size - V2_HEADER_LENGTH - 4
        ) {
            throw IllegalArgumentException("Invalid container ciphertext length ($ctLen bytes)")
        }
    }

    private fun legacyRejected() =
        IllegalArgumentException("Legacy V1 container rejected: it has no integrity binding between metadata and payload.")

    private fun requireSafeIterations(iterations: Int) {
        if (iterations < MIN_ACCEPTED_ITERATIONS || iterations > MAX_ACCEPTED_ITERATIONS) {
            throw IllegalArgumentException("Unsupported or unsafe key derivation parameters")
        }
    }

    // ---------------------------------------------------------------- V1 (legacy, read-only)

    private fun decryptV1WithKey(secretKey: SecretKey, containerBytes: ByteArray): DecryptedResult {
        val bais = ByteArrayInputStream(containerBytes)
        val dis = DataInputStream(bais)

        val header = ByteArray(MAGIC_HEADER_V1.size)
        dis.readFully(header)
        if (!header.contentEquals(MAGIC_HEADER_V1)) {
            throw invalidHeader()
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
        val metadata = parseMetadata(String(metadataCipher.doFinal(encryptedMetadata), Charsets.UTF_8))

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

    // ---------------------------------------------------------------- helpers

    private fun parseMetadata(json: String): EncryptedMetadata {
        val jsonObj = JSONObject(json)
        return EncryptedMetadata(
            originalName = jsonObj.getString("originalName"),
            relativePath = jsonObj.getString("relativePath"),
            mimeType = jsonObj.optString("mimeType", "application/octet-stream"),
            fileSize = jsonObj.getLong("fileSize"),
            lastModified = jsonObj.getLong("lastModified")
        )
    }

    private fun readMagic(containerBytes: ByteArray): ByteArray {
        if (containerBytes.size < MAGIC_HEADER_V2.size) throw invalidHeader()
        return containerBytes.copyOfRange(0, MAGIC_HEADER_V2.size)
    }

    private fun invalidHeader() =
        IllegalArgumentException("Invalid container header. File may be corrupted or not encrypted by SyncMe.")
}
