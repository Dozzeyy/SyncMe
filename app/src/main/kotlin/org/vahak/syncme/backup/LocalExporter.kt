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
import org.vahak.syncme.crypto.EncryptedNames
import org.vahak.syncme.crypto.EncryptedMetadata
import org.vahak.syncme.data.SyncTask
import org.vahak.syncme.sync.MirrorSafety
import org.vahak.syncme.sync.SyncResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.vahak.syncme.sync.WebDavClient
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalExporter @Inject constructor(
    private val cryptoEngine: CryptoEngine
) {

    private var cachedNameKey: Pair<String, SecretKey>? = null

    private fun encryptedNameFor(nameKey: SecretKey, relativePath: String): String =
        cryptoEngine.encryptedName(nameKey, relativePath)

    private fun resolveNameKey(context: Context, destRootDoc: DocumentFile, passphrase: String): SecretKey {
        val existing = destRootDoc.findFile(CryptoEngine.NAME_SALT_MARKER_FILE)?.takeIf { it.isFile }
        val salt: ByteArray = if (existing != null) {
            val bytes = context.contentResolver.openInputStream(existing.uri)?.use { WebDavClient.readBounded(it, 256) }
                ?: throw IllegalStateException("Cannot read the name salt marker")
            cryptoEngine.parseNameSaltMarker(bytes)
                ?: throw IllegalStateException("The name salt marker in the destination is invalid")
        } else {
            val fresh = cryptoEngine.generateSalt()
            val doc = destRootDoc.createFile("application/octet-stream", CryptoEngine.NAME_SALT_MARKER_FILE)
                ?: throw IllegalStateException("Cannot create the name salt marker")
            writeFully(context, doc, cryptoEngine.encodeNameSaltMarker(fresh))
            fresh
        }
        val id = java.security.MessageDigest.getInstance("SHA-256")
            .digest(passphrase.toByteArray(Charsets.UTF_8) + salt)
            .joinToString("") { "%02x".format(it) }
        return cachedNameKey?.takeIf { it.first == id }?.second
            ?: cryptoEngine.deriveNameKey(passphrase, salt).also { cachedNameKey = id to it }
    }

   
   
    private fun writeFully(context: Context, doc: DocumentFile, bytes: ByteArray) {
        context.contentResolver.openOutputStream(doc.uri, "wt")?.use { it.write(bytes) }
            ?: throw java.io.IOException("Cannot open output stream")
        verifyLength(doc, bytes.size.toLong())
    }

    private fun verifyLength(doc: DocumentFile, expected: Long) {
        val actual = doc.length()
        if (actual != expected) throw java.io.IOException("Written size mismatch ($actual of $expected bytes)")
    }

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
            val destUriStr = task.destinationLocalUri.ifBlank { task.localUri }
            val rootDoc = DocumentFile.fromTreeUri(context, Uri.parse(destUriStr))
                ?: return SyncResult(false, 0, 0, 0L, "Invalid destination folder URI")

            if (!rootDoc.exists() || !rootDoc.isDirectory) {
                return SyncResult(false, 0, 0, 0L, "Destination folder does not exist or is not a directory")
            }

            val nameKey: SecretKey? = if (task.isEncrypted) resolveNameKey(context, rootDoc, passphrase!!) else null

            if (task.sourceType == SyncTask.SOURCE_FILES) {
                return copyLocalFilesToLocalFolder(context, task, rootDoc, passphrase, nameKey, onProgress)
            }

            val (rawBytes, fileName) = when (task.sourceType) {
                SyncTask.SOURCE_CONTACTS -> Pair(ContactsBackupReader.generateContactsVcf(context), "contacts_backup.vcf")
                SyncTask.SOURCE_MESSAGES -> Pair(SmsBackupReader.generateSmsJson(context), "sms_messages_backup.json")
                SyncTask.SOURCE_CALL_LOGS -> Pair(CallLogBackupReader.generateCallLogJson(context), "call_logs_backup.json")
                else -> return SyncResult(false, 0, 0, 0L, "Unsupported device backup source type")
            }

            if (task.shouldExcludeFile(fileName, rawBytes.size.toLong())) {
                return SyncResult(true, 0, 1, 0L, "File excluded by filter criteria.")
            }

            onProgress?.invoke("Generating $fileName...")

            val targetFileName = if (task.isEncrypted) encryptedNameFor(nameKey!!, fileName) else fileName
            val existingFile = rootDoc.findFile(targetFileName)

        
            if (existingFile != null && existingContentMatches(context, existingFile, rawBytes, if (task.isEncrypted) passphrase else null)) {
                onProgress?.invoke("$fileName is unchanged; nothing to write.")
                return SyncResult(true, 0, 1, 0L)
            }

            val (writeBytes, mimeType) = if (task.isEncrypted) {
                val activePassphrase = passphrase!!
                onProgress?.invoke("Encrypting $fileName with AES-256-GCM...")
                val metadata = EncryptedMetadata(
                    originalName = fileName,
                    relativePath = fileName,
                    mimeType = if (fileName.endsWith(".vcf")) "text/vcard" else "application/json",
                    fileSize = rawBytes.size.toLong(),
                    lastModified = System.currentTimeMillis()
                )
                Pair(cryptoEngine.encryptContainer(activePassphrase, metadata, rawBytes), "application/octet-stream")
            } else {
                Pair(rawBytes, if (fileName.endsWith(".vcf")) "text/vcard" else "application/json")
            }

            onProgress?.invoke("Writing $targetFileName to local folder...")

            val targetDoc = existingFile ?: createExact(rootDoc, targetFileName)
                ?: return SyncResult(false, 0, 0, 0L, "Could not create target file '$targetFileName' in local folder")

            try {
                writeFully(context, targetDoc, writeBytes)
            } catch (_: java.io.IOException) {
                return SyncResult(false, 0, 0, 0L, "Cannot write '$targetFileName' completely")
            }

            SyncResult(true, 1, 0, writeBytes.size.toLong())
        } catch (_: Exception) {
            SyncResult(false, 0, 0, 0L, "Local export failed")
        }
    }

    private fun existingContentMatches(context: Context, doc: DocumentFile, expected: ByteArray, passphrase: String?): Boolean {
        return try {
            val limit = WebDavClient.MAX_TRANSFER_BYTES + 64L * 1024
            if (doc.length() > limit) return false
            val stored = context.contentResolver.openInputStream(doc.uri)?.use { WebDavClient.readBounded(it, limit) } ?: return false
            val plain = if (passphrase != null) cryptoEngine.DecryptSession(passphrase).decrypt(stored).payloadBytes else stored
            java.security.MessageDigest.isEqual(plain, expected)
        } catch (_: Exception) {
            false
        }
    }

    private fun copyLocalFilesToLocalFolder(
        context: Context,
        task: SyncTask,
        destRootDoc: DocumentFile,
        passphrase: String? = null,
        nameKey: SecretKey? = null,
        onProgress: ((String) -> Unit)? = null
    ): SyncResult {
        try {
            val sourceUri = Uri.parse(task.localUri)
            try {
                context.contentResolver.takePersistableUriPermission(
                    sourceUri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}

            val sourceTreeDoc = DocumentFile.fromTreeUri(context, sourceUri)
                ?: DocumentFile.fromSingleUri(context, sourceUri)
                ?: return SyncResult(false, 0, 0, 0L, "Invalid source folder or file URI")

            if (task.isSingleFile || sourceTreeDoc.isFile) {
                val originalName = sourceTreeDoc.name ?: "file"
                val localSize = sourceTreeDoc.length()
                if (task.shouldExcludeFile(originalName, localSize)) {
                    return SyncResult(true, 0, 1, 0L, "Source file excluded by filter criteria.")
                }
                return copySingleFile(context, task, sourceTreeDoc, destRootDoc, passphrase, nameKey, onProgress)
            }

            if (!sourceTreeDoc.exists() || !sourceTreeDoc.isDirectory) {
                return SyncResult(false, 0, 0, 0L, "Source folder does not exist or is not a directory")
            }

            var filesTransferred = 0
            var filesSkipped = 0
            var totalBytesTransferred = 0L

            val sourceFilesMap = mutableMapOf<String, DocumentFile>()
            fun scanSource(dir: DocumentFile, relPath: String) {
                for (child in dir.listFiles()) {
                    val childPath = if (relPath.isEmpty()) child.name ?: "" else "$relPath/${child.name ?: ""}"
                    if (child.isDirectory) {
                        scanSource(child, childPath)
                    } else if (child.isFile && childPath != CryptoEngine.NAME_SALT_MARKER_FILE) {
                        sourceFilesMap[childPath] = child
                    }
                }
            }
            scanSource(sourceTreeDoc, "")

            val destFilesMap = mutableMapOf<String, DocumentFile>()
            fun scanDest(dir: DocumentFile, relPath: String) {
                for (child in dir.listFiles()) {
                    val childPath = if (relPath.isEmpty()) child.name ?: "" else "$relPath/${child.name ?: ""}"
                    if (child.isDirectory) {
                        scanDest(child, childPath)
                    } else if (child.isFile && childPath != CryptoEngine.NAME_SALT_MARKER_FILE) {
                        destFilesMap[childPath] = child
                    }
                }
            }
            scanDest(destRootDoc, "")

            var mirrorNote: String? = null
            var failedFiles = 0 

            if (task.syncDirection == SyncTask.DIRECTION_BIDIRECTIONAL) {
                val allPaths = (sourceFilesMap.keys + destFilesMap.keys).toSet()

                for (path in allPaths) {
                    try {
                    val srcFile = sourceFilesMap[path]
                    val dstFile = destFilesMap[path]
                    val fileName = path.substringAfterLast('/')

                    if (srcFile != null && task.shouldExcludeFile(fileName, srcFile.length())) {
                        filesSkipped++
                        continue
                    }

                    if (srcFile != null && dstFile == null) {
                        val bytes = copyFileDoc(context, task, srcFile, destRootDoc, path, passphrase, nameKey, onProgress)
                        filesTransferred++
                        totalBytesTransferred += bytes
                    } else if (srcFile == null && dstFile != null) {
                        val bytes = copyFileDoc(context, task, dstFile, sourceTreeDoc, path, passphrase, nameKey, onProgress)
                        filesTransferred++
                        totalBytesTransferred += bytes
                    } else if (srcFile != null && dstFile != null) {
                        val srcTime = srcFile.lastModified()
                        val dstTime = dstFile.lastModified()

                        if (srcTime > dstTime + 1000L) {
                            val bytes = copyFileDoc(context, task, srcFile, destRootDoc, path, passphrase, nameKey, onProgress)
                            filesTransferred++
                            totalBytesTransferred += bytes
                        } else if (dstTime > srcTime + 1000L) {
                            val bytes = copyFileDoc(context, task, dstFile, sourceTreeDoc, path, passphrase, nameKey, onProgress)
                            filesTransferred++
                            totalBytesTransferred += bytes
                        } else {
                            when (task.conflictStrategy) {
                                SyncTask.STRATEGY_LOCAL_WINS -> {
                                    val bytes = copyFileDoc(context, task, srcFile, destRootDoc, path, passphrase, nameKey, onProgress)
                                    filesTransferred++
                                    totalBytesTransferred += bytes
                                }
                                SyncTask.STRATEGY_REMOTE_WINS -> {
                                    val bytes = copyFileDoc(context, task, dstFile, sourceTreeDoc, path, passphrase, nameKey, onProgress)
                                    filesTransferred++
                                    totalBytesTransferred += bytes
                                }
                                else -> {
                                    val timeSuffix = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                                    val ext = fileName.substringAfterLast('.', "")
                                    val base = fileName.substringBeforeLast('.')
                                    val newDestName = if (ext.isNotEmpty()) "${base}_dest_${timeSuffix}.${ext}" else "${base}_dest_${timeSuffix}"
                                    val newDestPath = path.substringBeforeLast('/', "").let { if (it.isEmpty()) newDestName else "$it/$newDestName" }

                                    copyFileDoc(context, task, dstFile, destRootDoc, newDestPath, passphrase, nameKey, onProgress)
                                    val bytes = copyFileDoc(context, task, srcFile, destRootDoc, path, passphrase, nameKey, onProgress)
                                    filesTransferred++
                                    totalBytesTransferred += bytes
                                }
                            }
                        }
                    }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: OutOfMemoryError) {
                        failedFiles++
                    } catch (_: Exception) {
                        failedFiles++
                    }
                }
            } else {
                val encrypted = task.isEncrypted
                for ((relPath, srcFile) in sourceFilesMap) {
                    val fileName = srcFile.name ?: "file"
                    if (task.shouldExcludeFile(fileName, srcFile.length())) {
                        filesSkipped++
                        onProgress?.invoke("Skipping excluded file $fileName")
                        continue
                    }

                
                    val dstFile = destFilesMap[if (encrypted) encryptedNameFor(nameKey!!, relPath) else relPath]
                    val needsCopy = if (encrypted) {
                        dstFile == null || srcFile.lastModified() == 0L || srcFile.lastModified() > dstFile.lastModified()
                    } else {
                        dstFile == null || srcFile.lastModified() > dstFile.lastModified() + 1000L || srcFile.length() != dstFile.length()
                    }
                    if (needsCopy) {
                        try {
                            val bytes = copyFileDoc(context, task, srcFile, destRootDoc, relPath, passphrase, nameKey, onProgress)
                            filesTransferred++
                            totalBytesTransferred += bytes
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: OutOfMemoryError) {
                            failedFiles++
                        } catch (_: Exception) {
                            failedFiles++
                        }
                    } else {
                        filesSkipped++
                    }
                }

                if (task.isMirrorSync) {
                    val expectedDestKeys: Set<String> = if (encrypted) {
                        sourceFilesMap.keys.map { encryptedNameFor(nameKey!!, it) }.toSet()
                    } else {
                        sourceFilesMap.keys
                    }
                    val orphans = destFilesMap.filter { (key, _) ->
                        key !in expectedDestKeys && (!encrypted || EncryptedNames.isContainerName(key))
                    }
                    val skipReason = MirrorSafety.evaluate(
                        sourceCount = sourceFilesMap.size,
                        destinationCount = destFilesMap.size,
                        deleteCount = orphans.size,
                        hadErrors = failedFiles > 0
                    )
                    if (skipReason != null) {
                        mirrorNote = skipReason
                    } else {
                        for ((relPath, dstFile) in orphans) {
                            onProgress?.invoke("Mirror sync: Deleting orphaned destination file $relPath")
                            try {
                                dstFile.delete()
                            } catch (_: Exception) {}
                        }
                    }
                }
            }

            val failureNote = if (failedFiles > 0) "$failedFiles file(s) could not be copied (too large or unreadable)." else null
            val note = listOfNotNull(failureNote, mirrorNote).joinToString(" ").ifEmpty { null }
            return if (failedFiles > 0 && filesTransferred == 0 && filesSkipped == 0) {
                SyncResult(false, 0, 0, 0L, note)
            } else {
                SyncResult(true, filesTransferred, filesSkipped, totalBytesTransferred, note)
            }
        } catch (e: Exception) {
            val safe = if (e is IllegalStateException && e.message?.startsWith("Encryption is enabled") == true) e.message else null
            return SyncResult(false, 0, 0, 0L, safe ?: "Local file copy failed.")
        }
    }

    private fun copySingleFile(
        context: Context,
        task: SyncTask,
        sourceDoc: DocumentFile,
        destRootDoc: DocumentFile,
        passphrase: String? = null,
        nameKey: SecretKey? = null,
        onProgress: ((String) -> Unit)? = null
    ): SyncResult {
        val originalName = sourceDoc.name ?: "file"
        val mimeType = sourceDoc.type ?: "application/octet-stream"
        onProgress?.invoke("Copying $originalName...")

        val inputStream = context.contentResolver.openInputStream(sourceDoc.uri)
            ?: return SyncResult(false, 0, 0, 0L, "Cannot open source file stream")

        val (transferredBytes, _) = if (task.isEncrypted) {
            val rawBytes = inputStream.use { WebDavClient.readBounded(it, WebDavClient.MAX_TRANSFER_BYTES, sourceDoc.length()) }
            onProgress?.invoke("Encrypting $originalName with AES-256-GCM...")
            val metadata = EncryptedMetadata(
                originalName = originalName,
                relativePath = originalName,
                mimeType = mimeType,
                fileSize = sourceDoc.length(),
                lastModified = sourceDoc.lastModified()
            )
            val encContainer = cryptoEngine.encryptContainer(passphrase!!, metadata, rawBytes)
            val obfuscatedName = encryptedNameFor(nameKey!!, originalName)

            val existingFile = destRootDoc.findFile(obfuscatedName)
            val targetDoc = existingFile ?: destRootDoc.createFile("application/octet-stream", obfuscatedName)
                ?: return SyncResult(false, 0, 0, 0L, "Could not create target file '$obfuscatedName'")

            try {
                writeFully(context, targetDoc, encContainer)
            } catch (_: java.io.IOException) {
                return SyncResult(false, 0, 0, 0L, "Cannot write the destination file completely")
            }

            Pair(encContainer.size.toLong(), obfuscatedName)
        } else {
            val existingFile = destRootDoc.findFile(originalName)
            val targetDoc = existingFile ?: createExact(destRootDoc, originalName)
                ?: return SyncResult(false, 0, 0, 0L, "Could not create target file '$originalName'")

            var copiedBytes = 0L
            context.contentResolver.openOutputStream(targetDoc.uri, "wt")?.use { os ->
                inputStream.use { isStream ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (isStream.read(buffer).also { bytesRead = it } != -1) {
                        os.write(buffer, 0, bytesRead)
                        copiedBytes += bytesRead
                    }
                }
            } ?: return SyncResult(false, 0, 0, 0L, "Cannot open destination file stream")
            try {
                verifyLength(targetDoc, copiedBytes)
            } catch (_: java.io.IOException) {
                return SyncResult(false, 0, 0, 0L, "Cannot write the destination file completely")
            }

            Pair(copiedBytes, originalName)
        }

        return SyncResult(true, 1, 0, transferredBytes)
    }

    private fun copyFileDoc(
        context: Context,
        task: SyncTask,
        sourceDoc: DocumentFile,
        targetRootDoc: DocumentFile,
        relativePath: String,
        passphrase: String? = null,
        nameKey: SecretKey? = null,
        onProgress: ((String) -> Unit)? = null
    ): Long {
        val originalName = sourceDoc.name ?: relativePath.substringAfterLast('/')
        val mimeType = sourceDoc.type ?: "application/octet-stream"
        onProgress?.invoke("Copying $relativePath...")

        val inputStream = context.contentResolver.openInputStream(sourceDoc.uri) ?: return 0L

        if (task.isEncrypted && passphrase.isNullOrBlank()) {
            inputStream.close()
            throw IllegalStateException("Encryption is enabled, but the passphrase is unavailable.")
        }

        return if (task.isEncrypted && passphrase != null) {
            if (sourceDoc.length() > WebDavClient.MAX_TRANSFER_BYTES) {
                inputStream.close()
                throw IllegalArgumentException("File is larger than the transfer limit")
            }
            val rawBytes = inputStream.use { WebDavClient.readBounded(it, WebDavClient.MAX_TRANSFER_BYTES, sourceDoc.length()) }
            val metadata = EncryptedMetadata(
                originalName = originalName,
                relativePath = relativePath,
                mimeType = mimeType,
                fileSize = sourceDoc.length(),
                lastModified = sourceDoc.lastModified()
            )
            val encContainer = cryptoEngine.encryptContainer(passphrase, metadata, rawBytes)
            val obfuscatedName = encryptedNameFor(nameKey!!, relativePath)
            val targetDoc = getOrCreateLocalDocument(targetRootDoc, obfuscatedName, "application/octet-stream") ?: return 0L
            writeFully(context, targetDoc, encContainer)
            encContainer.size.toLong()
        } else {
            val targetDoc = getOrCreateLocalDocument(targetRootDoc, relativePath, mimeType) ?: return 0L
            var copiedBytes = 0L
            context.contentResolver.openOutputStream(targetDoc.uri, "wt")?.use { os ->
                inputStream.use { isStream ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (isStream.read(buffer).also { bytesRead = it } != -1) {
                        os.write(buffer, 0, bytesRead)
                        copiedBytes += bytesRead
                    }
                }
            } ?: throw java.io.IOException("Cannot open output stream")
            verifyLength(targetDoc, copiedBytes)
            copiedBytes
        }
    }

    private fun getOrCreateLocalDocument(rootDoc: DocumentFile, relativePath: String, mimeType: String): DocumentFile? {
        val parts = relativePath.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty()) return null

        var currentDoc = rootDoc
        for (i in 0 until parts.size - 1) {
            val dirName = parts[i]
            var subDir = currentDoc.findFile(dirName)
            if (subDir == null || !subDir.isDirectory) {
                subDir = currentDoc.createDirectory(dirName)
            }
            currentDoc = subDir ?: return null
        }

        val fileName = parts.last()
        val existingFile = currentDoc.findFile(fileName)
        if (existingFile != null) return existingFile

        return createExact(currentDoc, fileName)
            ?: throw java.io.IOException("Cannot create '$fileName' with its exact name in the destination")
    }


    private fun createExact(parent: DocumentFile, name: String): DocumentFile? {
        val created = parent.createFile("application/octet-stream", name) ?: return null
        if (created.name == name) return created
        try {
            if (created.renameTo(name) && created.name == name) return created
        } catch (_: Exception) {
        }
        try { created.delete() } catch (_: Exception) {}
        return null
    }
}
