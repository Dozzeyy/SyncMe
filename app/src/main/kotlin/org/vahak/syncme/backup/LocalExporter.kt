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

package org.vahak.syncme.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.vahak.syncme.crypto.CryptoEngine
import org.vahak.syncme.crypto.EncryptedMetadata
import org.vahak.syncme.data.SyncTask
import org.vahak.syncme.sync.SyncResult
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalExporter @Inject constructor(
    private val cryptoEngine: CryptoEngine
) {

    fun exportToLocalFolder(
        context: Context,
        task: SyncTask,
        passphrase: String? = null,
        onProgress: ((String) -> Unit)? = null
    ): SyncResult {
        if (task.isEncrypted && passphrase.isNullOrBlank()) {
            return SyncResult(
                success = false,
                filesTransferred = 0,
                filesSkipped = 0,
                totalBytes = 0L,
                errorMessage = "Encryption is enabled, but the passphrase is unavailable."
            )
        }

        return try {
            val rootDoc = DocumentFile.fromTreeUri(context, Uri.parse(task.localUri))
                ?: return SyncResult(false, 0, 0, 0L, "Invalid destination folder URI")

            if (!rootDoc.exists() || !rootDoc.isDirectory) {
                return SyncResult(false, 0, 0, 0L, "Destination folder does not exist or is not a directory")
            }

            val (rawBytes, fileName) = when (task.sourceType) {
                SyncTask.SOURCE_CONTACTS -> Pair(ContactsBackupReader.generateContactsJson(context), "contacts_backup.json")
                SyncTask.SOURCE_MESSAGES -> Pair(SmsBackupReader.generateSmsJson(context), "sms_messages_backup.json")
                SyncTask.SOURCE_CALL_LOGS -> Pair(CallLogBackupReader.generateCallLogJson(context), "call_logs_backup.json")
                else -> return SyncResult(false, 0, 0, 0L, "Unsupported device backup source type")
            }

            onProgress?.invoke("Generating $fileName...")

            val (writeBytes, targetFileName, mimeType) = if (task.isEncrypted) {
                val activePassphrase = passphrase!!
                onProgress?.invoke("Encrypting $fileName with AES-256-GCM...")
                val metadata = EncryptedMetadata(
                    originalName = fileName,
                    relativePath = fileName,
                    mimeType = "application/json",
                    fileSize = rawBytes.size.toLong(),
                    lastModified = System.currentTimeMillis()
                )
                val encContainer = cryptoEngine.encryptContainer(activePassphrase, metadata, rawBytes)
                val obfuscatedName = "enc_" + UUID.nameUUIDFromBytes(fileName.toByteArray()).toString() + ".enc"
                Triple(encContainer, obfuscatedName, "application/octet-stream")
            } else {
                Triple(rawBytes, fileName, "application/json")
            }

            onProgress?.invoke("Writing $targetFileName to local folder...")

            val existingFile = rootDoc.findFile(targetFileName)
            val targetDoc = existingFile ?: rootDoc.createFile(mimeType, targetFileName)
                ?: return SyncResult(false, 0, 0, 0L, "Could not create target file '$targetFileName' in local folder")

            context.contentResolver.openOutputStream(targetDoc.uri)?.use { os ->
                os.write(writeBytes)
            } ?: return SyncResult(false, 0, 0, 0L, "Cannot open output stream for '$targetFileName'")

            SyncResult(true, 1, 0, writeBytes.size.toLong())
        } catch (_: Exception) {
            SyncResult(false, 0, 0, 0L, "Local export failed")
        }
    }
}
