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

package org.vahak.syncme.sync

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.vahak.syncme.backup.CallLogBackupReader
import org.vahak.syncme.backup.ContactsBackupReader
import org.vahak.syncme.backup.SmsBackupReader
import org.vahak.syncme.crypto.CryptoEngine
import org.vahak.syncme.crypto.EncryptedMetadata
import org.vahak.syncme.data.SyncTask
import org.vahak.syncme.data.SyncedFileState
import org.vahak.syncme.data.SyncedFileStateDao
import org.vahak.syncme.data.WebDavConfig
import java.io.File
import java.io.StringReader
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

data class SyncResult(
    val success: Boolean,
    val filesTransferred: Int,
    val filesSkipped: Int = 0,
    val totalBytes: Long,
    val errorMessage: String? = null
)

data class RemoteFileItem(
    val href: String,
    val name: String,
    val isDirectory: Boolean,
    val lastModified: String?,
    val contentLength: Long
)

data class StagedUploadItem(
    val stagedFile: File,
    val remotePath: String,
    val relativePath: String,
    val originalFileName: String,
    val localLastModified: Long,
    val localSize: Long
)

class CrossOriginUrlException(message: String) : SecurityException(message)

@Singleton
class WebDavClient @Inject constructor(
    private val cryptoEngine: CryptoEngine
) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun executeWithSafeRedirects(
        baseUrl: String,
        initialRequest: Request,
        maxRedirects: Int = 3
    ): okhttp3.Response {
        var currentRequest = initialRequest
        var redirectCount = 0

        while (true) {
            val response = client.newCall(currentRequest).execute()
            val isRedirectStatus = response.code in listOf(301, 302, 303, 307, 308)
            if (isRedirectStatus) {
                redirectCount++
                if (redirectCount > maxRedirects) {
                    response.close()
                    throw IllegalArgumentException("Too many HTTP redirects")
                }

                val location = response.header("Location")
                response.close()

                if (location.isNullOrBlank()) {
                    throw IllegalArgumentException("Redirect response missing Location header")
                }

                val resolvedUrl = currentRequest.url.resolve(location)
                    ?: throw IllegalArgumentException("Could not resolve redirect Location: $location")

                
                val targetUrlStr = resolveWebDavUrl(baseUrl, resolvedUrl.toString())
                val validatedTargetUrl = targetUrlStr.toHttpUrlOrNull()
                    ?: throw IllegalArgumentException("Malformed validated redirect URL: $targetUrlStr")

                if (currentRequest.url.isHttps && !validatedTargetUrl.isHttps) {
                    throw CrossOriginUrlException("HTTPS to HTTP downgrade redirect rejected: $validatedTargetUrl")
                }

                currentRequest = currentRequest.newBuilder()
                    .url(validatedTargetUrl)
                    .build()
            } else {
                return response
            }
        }
    }

    fun testConnection(config: WebDavConfig): Result<String> {
        return try {
            val targetUrlStr = resolveWebDavUrl(config.url, "")
            val httpUrl = targetUrlStr.toHttpUrlOrNull() ?: return Result.failure(Exception("Invalid URL format"))

            val request = Request.Builder()
                .url(httpUrl)
                .header("Authorization", Credentials.basic(config.username, config.password))
                .header("User-Agent", "SyncMe-Android")
                .header("Depth", "0")
                .method("PROPFIND", null)
                .build()

            executeWithSafeRedirects(config.url, request).use { response ->
                if (response.isSuccessful || response.code == 207 || response.code == 405) {
                    Result.success("Connection successful!")
                } else {
                    Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun syncDeviceDataBackup(
        context: Context,
        config: WebDavConfig,
        taskId: Long,
        sourceType: String,
        isEncrypted: Boolean = false,
        passphrase: String? = null,
        onProgress: ((String) -> Unit)? = null
    ): SyncResult {
    
        if (isEncrypted && passphrase.isNullOrBlank()) {
            return SyncResult(false, 0, 0, 0L, "Encryption is enabled, but the passphrase is unavailable.")
        }

        val stagingDir = File(context.cacheDir, "device_backup_staging_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val (rawBytes, fileName) = when (sourceType) {
                SyncTask.SOURCE_CONTACTS -> Pair(ContactsBackupReader.generateContactsJson(context), "contacts_backup.json")
                SyncTask.SOURCE_MESSAGES -> Pair(SmsBackupReader.generateSmsJson(context), "sms_messages_backup.json")
                SyncTask.SOURCE_CALL_LOGS -> Pair(CallLogBackupReader.generateCallLogJson(context), "call_logs_backup.json")
                else -> return SyncResult(false, 0, 0, 0L, "Unsupported device backup source type")
            }

            onProgress?.invoke("Generating $fileName...")

            val baseUrl = formatUrl(config.url)
            val (stagedFile, remoteFileName) = if (isEncrypted) {
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
                val tmpFile = File(stagingDir, obfuscatedName).apply { writeBytes(encContainer) }
                Pair(tmpFile, obfuscatedName)
            } else {
                val tmpFile = File(stagingDir, fileName).apply { writeBytes(rawBytes) }
                Pair(tmpFile, fileName)
            }

            onProgress?.invoke("Uploading $fileName to WebDAV...")
            val targetUrl = resolveWebDavUrl(baseUrl, remoteFileName)
            val requestBody = stagedFile.asRequestBody("application/json".toMediaTypeOrNull())

            var lastErrorMsg = ""
            for (attempt in 1..3) {
                try {
                    val request = Request.Builder()
                        .url(targetUrl)
                        .header("Authorization", Credentials.basic(config.username, config.password))
                        .header("User-Agent", "SyncMe-Android")
                        .put(requestBody)
                        .build()

                    executeWithSafeRedirects(baseUrl, request).use { response ->
                        if (response.isSuccessful || response.code == 201 || response.code == 204) {
                            return SyncResult(true, 1, 0, stagedFile.length())
                        } else {
                            lastErrorMsg = "HTTP ${response.code}: ${response.message}"
                            if (response.code == 524 || response.code >= 500) {
                                kotlinx.coroutines.delay(2000L * attempt)
                            }
                        }
                    }
                } catch (_: Exception) {
                    lastErrorMsg = "Network operation failed"
                    kotlinx.coroutines.delay(2000L * attempt)
                }
            }
            return SyncResult(false, 0, 0, 0L, lastErrorMsg)
        } catch (_: Exception) {
            return SyncResult(false, 0, 0, 0L, "Device data backup failed")
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    suspend fun syncSingleFile(
        context: Context,
        config: WebDavConfig,
        taskId: Long,
        fileUriStr: String,
        isEncrypted: Boolean = false,
        passphrase: String? = null,
        syncedFileStateDao: SyncedFileStateDao,
        onProgress: ((String) -> Unit)? = null
    ): SyncResult {
    
        if (isEncrypted && passphrase.isNullOrBlank()) {
            return SyncResult(false, 0, 0, 0L, "Encryption is enabled, but the passphrase is unavailable.")
        }

        val stagingDir = File(context.cacheDir, "enc_staging_single_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val uri = Uri.parse(fileUriStr)
            var targetUri = uri
            var fileName = getFileNameFromUri(context, uri)

            val treeDoc = DocumentFile.fromTreeUri(context, uri)
            val fileDoc = if (treeDoc != null && treeDoc.exists() && treeDoc.isDirectory) {
                val firstFile = treeDoc.listFiles().firstOrNull { it.isFile }
                    ?: return SyncResult(false, 0, 0, 0L, "No files found in selected folder")
                targetUri = firstFile.uri
                fileName = firstFile.name ?: "synced_file_${System.currentTimeMillis()}"
                firstFile
            } else {
                DocumentFile.fromSingleUri(context, uri)
            }

            if (fileName == null) fileName = "synced_file_${System.currentTimeMillis()}"

            val localLastModified = fileDoc?.lastModified() ?: 0L
            val localSize = fileDoc?.length() ?: 0L
            val relativePath = fileName

            
            val cachedState = syncedFileStateDao.getByPath(taskId, relativePath)
            if (cachedState != null && localLastModified > 0L && cachedState.lastModified == localLastModified && cachedState.fileSize == localSize) {
                onProgress?.invoke("File '$fileName' is unmodified. Skipping.")
                return SyncResult(true, 0, 1, 0L)
            }

            
            onProgress?.invoke(if (isEncrypted) "Encrypting $fileName offline..." else "Preparing $fileName...")

            val inputStream = context.contentResolver.openInputStream(targetUri)
                ?: return SyncResult(false, 0, 0, 0L, "Cannot open stream for URI: $targetUri")

            val rawBytes = inputStream.use { it.readBytes() }
            val baseUrl = formatUrl(config.url)

            val (stagedFile, remoteFileName) = if (isEncrypted) {
                val activePassphrase = passphrase!!
                val metadata = EncryptedMetadata(
                    originalName = fileName,
                    relativePath = relativePath,
                    mimeType = fileDoc?.type ?: "application/octet-stream",
                    fileSize = localSize,
                    lastModified = localLastModified
                )
                val encContainer = cryptoEngine.encryptContainer(activePassphrase, metadata, rawBytes)
                val obfuscatedName = "enc_" + UUID.nameUUIDFromBytes(relativePath.toByteArray()).toString() + ".enc"
                val tmpFile = File(stagingDir, obfuscatedName).apply { writeBytes(encContainer) }
                Pair(tmpFile, obfuscatedName)
            } else {
                val tmpFile = File(stagingDir, fileName).apply { writeBytes(rawBytes) }
                Pair(tmpFile, fileName)
            }

            
            onProgress?.invoke("Uploading $fileName to WebDAV...")
            val targetUrl = resolveWebDavUrl(baseUrl, remoteFileName)
            val requestBody = stagedFile.asRequestBody("application/octet-stream".toMediaTypeOrNull())

            var lastErrorMsg = ""
            for (attempt in 1..3) {
                try {
                    val request = Request.Builder()
                        .url(targetUrl)
                        .header("Authorization", Credentials.basic(config.username, config.password))
                        .header("User-Agent", "SyncMe-Android")
                        .put(requestBody)
                        .build()

                    executeWithSafeRedirects(baseUrl, request).use { response ->
                        if (response.isSuccessful || response.code == 201 || response.code == 204) {
                            syncedFileStateDao.insertOrUpdate(
                                SyncedFileState(
                                    taskId = taskId,
                                    relativePath = relativePath,
                                    lastModified = localLastModified,
                                    fileSize = localSize
                                )
                            )
                            return SyncResult(true, 1, 0, stagedFile.length())
                        } else {
                            lastErrorMsg = "HTTP ${response.code}: ${response.message}"
                            if (response.code == 524 || response.code >= 500) {
                                kotlinx.coroutines.delay(2000L * attempt)
                            }
                        }
                    }
                } catch (_: Exception) {
                    lastErrorMsg = "Network operation failed"
                    kotlinx.coroutines.delay(2000L * attempt)
                }
            }
            return SyncResult(false, 0, 0, 0L, lastErrorMsg)
        } catch (_: Exception) {
            return SyncResult(false, 0, 0, 0L, "File upload failed")
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    suspend fun syncFolder(
        context: Context,
        config: WebDavConfig,
        taskId: Long,
        folderUriStr: String,
        isEncrypted: Boolean = false,
        passphrase: String? = null,
        syncedFileStateDao: SyncedFileStateDao,
        onProgress: ((currentFile: Int, totalFiles: Int, fileName: String) -> Unit)? = null
    ): SyncResult {
        
        if (isEncrypted && passphrase.isNullOrBlank()) {
            return SyncResult(false, 0, 0, 0L, "Encryption is enabled, but the passphrase is unavailable.")
        }

        val stagingDir = File(context.cacheDir, "enc_staging_folder_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val treeUri = Uri.parse(folderUriStr)
            val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                ?: return SyncResult(false, 0, 0, 0L, "Invalid folder URI or access permission revoked")

            if (!rootDoc.exists() || !rootDoc.isDirectory) {
                return SyncResult(false, 0, 0, 0L, "Selected path is not a valid directory")
            }

            val allFiles = mutableListOf<Pair<DocumentFile, String>>()
            
            fun scanTree(docDir: DocumentFile, relativePath: String) {
                val children = docDir.listFiles()
                for (child in children) {
                    val childPath = if (relativePath.isEmpty()) child.name ?: "unnamed" else "$relativePath/${child.name ?: "unnamed"}"
                    if (child.isDirectory) {
                        scanTree(child, childPath)
                    } else if (child.isFile) {
                        allFiles.add(Pair(child, childPath))
                    }
                }
            }

            onProgress?.invoke(0, 0, "Scanning local files...")
            scanTree(rootDoc, "")

            val totalFiles = allFiles.size
            if (totalFiles == 0) {
                return SyncResult(true, 0, 0, 0L, "Folder is empty, nothing to sync.")
            }

            val existingStatesMap = syncedFileStateDao.getForTask(taskId).associateBy { it.relativePath }

            
            val sessionSalt = if (isEncrypted) cryptoEngine.generateSalt() else null
            val sessionKey: SecretKey? = if (isEncrypted && sessionSalt != null) {
                onProgress?.invoke(0, totalFiles, "Deriving encryption key offline...")
                cryptoEngine.deriveKey(passphrase!!, sessionSalt)
            } else null

            val stagedItems = mutableListOf<StagedUploadItem>()
            var filesSkipped = 0

        
            for ((index, item) in allFiles.withIndex()) {
                val (childDoc, relativePath) = item
                val currentNum = index + 1
                val fileName = childDoc.name ?: "unnamed"

                val localLastModified = childDoc.lastModified()
                val localSize = childDoc.length()

                val cachedState = existingStatesMap[relativePath]

            
                if (cachedState != null && localLastModified > 0L && cachedState.lastModified == localLastModified && cachedState.fileSize == localSize) {
                    filesSkipped++
                    onProgress?.invoke(currentNum, totalFiles, "$fileName [Skipped - Unmodified]")
                    continue
                }

                onProgress?.invoke(currentNum, totalFiles, if (isEncrypted) "Encrypting offline ($currentNum/$totalFiles): $fileName" else "Preparing ($currentNum/$totalFiles): $fileName")

                val inputStream = context.contentResolver.openInputStream(childDoc.uri)
                if (inputStream != null) {
                    val rawBytes = inputStream.use { it.readBytes() }

                    val (stagedFile, remotePath) = if (isEncrypted && sessionKey != null && sessionSalt != null) {
                        val metadata = EncryptedMetadata(
                            originalName = fileName,
                            relativePath = relativePath,
                            mimeType = childDoc.type ?: "application/octet-stream",
                            fileSize = localSize,
                            lastModified = localLastModified
                        )
                        val encContainer = cryptoEngine.encryptContainerWithKey(sessionKey, sessionSalt, metadata, rawBytes)
                        val obfuscatedName = "enc_" + UUID.nameUUIDFromBytes(relativePath.toByteArray()).toString() + ".enc"
                        val tmpFile = File(stagingDir, "stage_$currentNum.enc").apply { writeBytes(encContainer) }
                        Pair(tmpFile, obfuscatedName)
                    } else {
                        val tmpFile = File(stagingDir, "stage_$currentNum.tmp").apply { writeBytes(rawBytes) }
                        Pair(tmpFile, relativePath)
                    }

                    stagedItems.add(
                        StagedUploadItem(
                            stagedFile = stagedFile,
                            remotePath = remotePath,
                            relativePath = relativePath,
                            originalFileName = fileName,
                            localLastModified = localLastModified,
                            localSize = localSize
                        )
                    )
                }
            }

            if (stagedItems.isEmpty()) {
                return SyncResult(true, 0, filesSkipped, 0L)
            }

            
            val baseUrl = formatUrl(config.url)
            var filesUploaded = 0
            var bytesTotal = 0L
            val errors = mutableListOf<String>()

            val totalStaged = stagedItems.size

            for ((index, stagedItem) in stagedItems.withIndex()) {
                val currentNum = index + 1
                onProgress?.invoke(currentNum, totalStaged, "Uploading ($currentNum/$totalStaged): ${stagedItem.originalFileName}")

            
                if (!isEncrypted) {
                    val parentPath = stagedItem.relativePath.substringBeforeLast('/', "")
                    if (parentPath.isNotEmpty()) {
                        createWebDavDirectory(baseUrl, config, parentPath)
                    }
                }

                val targetUrl = resolveWebDavUrl(baseUrl, stagedItem.remotePath)
                val requestBody = stagedItem.stagedFile.asRequestBody("application/octet-stream".toMediaTypeOrNull())

                var uploadSuccess = false
                var lastErrorMsg = ""

                for (attempt in 1..3) {
                    try {
                        val request = Request.Builder()
                            .url(targetUrl)
                            .header("Authorization", Credentials.basic(config.username, config.password))
                            .header("User-Agent", "SyncMe-Android")
                            .put(requestBody)
                            .build()

                        executeWithSafeRedirects(baseUrl, request).use { response ->
                            if (response.isSuccessful || response.code == 201 || response.code == 204) {
                                uploadSuccess = true
                                filesUploaded++
                                bytesTotal += stagedItem.stagedFile.length()

                                syncedFileStateDao.insertOrUpdate(
                                    SyncedFileState(
                                        taskId = taskId,
                                        relativePath = stagedItem.relativePath,
                                        lastModified = stagedItem.localLastModified,
                                        fileSize = stagedItem.localSize
                                    )
                                )
                            } else {
                                lastErrorMsg = "HTTP ${response.code}"
                                if (response.code == 524 || response.code >= 500) {
                                    kotlinx.coroutines.delay(2000L * attempt)
                                }
                            }
                        }
                        if (uploadSuccess) break
                    } catch (_: Exception) {
                        lastErrorMsg = "Network operation failed"
                        kotlinx.coroutines.delay(2000L * attempt)
                    }
                }

                if (!uploadSuccess) {
                    errors.add("Failed '${stagedItem.originalFileName}': $lastErrorMsg")
                }
            }

            if (errors.isNotEmpty() && filesUploaded == 0 && filesSkipped == 0) {
                return SyncResult(false, 0, 0, 0L, errors.joinToString("; "))
            } else {
                val errorSummary = if (errors.isNotEmpty()) " (Partial errors: ${errors.take(3).joinToString(", ")})" else null
                return SyncResult(true, filesUploaded, filesSkipped, bytesTotal, errorSummary)
            }
        } catch (_: Exception) {
            return SyncResult(false, 0, 0, 0L, "Directory synchronization failed")
        } finally {
        
            stagingDir.deleteRecursively()
        }
    }

    suspend fun downloadRemoteToLocalFolder(
        context: Context,
        config: WebDavConfig,
        taskId: Long,
        folderUriStr: String,
        isEncrypted: Boolean = false,
        passphrase: String? = null,
        syncedFileStateDao: SyncedFileStateDao,
        onProgress: ((currentFile: Int, totalFiles: Int, fileName: String) -> Unit)? = null
    ): SyncResult {

        if (isEncrypted && passphrase.isNullOrBlank()) {
            return SyncResult(false, 0, 0, 0L, "Encryption is enabled, but the passphrase is unavailable.")
        }

        return try {
            val rootDoc = DocumentFile.fromTreeUri(context, Uri.parse(folderUriStr))
                ?: return SyncResult(false, 0, 0, 0L, "Invalid destination folder URI")

            val baseUrl = formatUrl(config.url)
            val remoteItems = listRemoteFiles(baseUrl, config)

            if (remoteItems.isEmpty()) {
                return SyncResult(true, 0, 0, 0L, "Remote WebDAV folder is empty.")
            }

            val existingStatesMap = syncedFileStateDao.getForTask(taskId).associateBy { it.relativePath }

            var filesDownloaded = 0
            var filesSkipped = 0
            var bytesTotal = 0L
            val errors = mutableListOf<String>()

            val totalRemoteFiles = remoteItems.size

            for ((index, remoteItem) in remoteItems.withIndex()) {
                val currentNum = index + 1
                val remoteLastModHeader = remoteItem.lastModified ?: ""

            
                val cachedState = existingStatesMap.values.find { state -> state.remoteLastModified == remoteLastModHeader }
                if (cachedState != null && remoteLastModHeader.isNotEmpty()) {
                    filesSkipped++
                    onProgress?.invoke(currentNum, totalRemoteFiles, "${remoteItem.name} [Skipped - Unmodified]")
                    continue
                }

                onProgress?.invoke(currentNum, totalRemoteFiles, "Downloading ${remoteItem.name}...")

                if (!isSameOrigin(baseUrl, remoteItem.href)) {
                    errors.add("Failed '${remoteItem.name}': Rejected cross-origin URL")
                    continue
                }

                val fileReq = Request.Builder()
                    .url(remoteItem.href)
                    .header("Authorization", Credentials.basic(config.username, config.password))
                    .header("User-Agent", "SyncMe-Android")
                    .get()
                    .build()

                val tempDownloadFile = File(context.cacheDir, "temp_dl_${System.currentTimeMillis()}_$index.tmp")
                try {
                    executeWithSafeRedirects(baseUrl, fileReq).use { response ->
                        if (response.isSuccessful) {
                            val bytesDownloaded = downloadStreamToFile(response, tempDownloadFile)
                            val downloadedBytes = tempDownloadFile.readBytes()

                            if (isEncrypted) {
                                val activePassphrase = passphrase!!
                                onProgress?.invoke(currentNum, totalRemoteFiles, "Decrypting ${remoteItem.name}...")
                                val decryptedResult = cryptoEngine.decryptContainer(activePassphrase, downloadedBytes)
                                val metadata = decryptedResult.metadata

                                val targetDoc = getOrCreateLocalDocument(rootDoc, metadata.relativePath, metadata.mimeType)
                                    ?: throw Exception("Could not create local file '${metadata.originalName}'")

                                context.contentResolver.openOutputStream(targetDoc.uri)?.use { os ->
                                    os.write(decryptedResult.payloadBytes)
                                }

                                filesDownloaded++
                                bytesTotal += bytesDownloaded

                                syncedFileStateDao.insertOrUpdate(
                                    SyncedFileState(
                                        taskId = taskId,
                                        relativePath = metadata.relativePath,
                                        lastModified = metadata.lastModified,
                                        fileSize = metadata.fileSize,
                                        remoteLastModified = remoteLastModHeader
                                    )
                                )
                            } else {
                                val relPath = remoteItem.name
                                val targetDoc = getOrCreateLocalDocument(rootDoc, relPath, "application/octet-stream")
                                    ?: throw Exception("Could not create local file '$relPath'")

                                context.contentResolver.openOutputStream(targetDoc.uri)?.use { os ->
                                    os.write(downloadedBytes)
                                }

                                filesDownloaded++
                                bytesTotal += bytesDownloaded

                                syncedFileStateDao.insertOrUpdate(
                                    SyncedFileState(
                                        taskId = taskId,
                                        relativePath = relPath,
                                        lastModified = targetDoc.lastModified(),
                                        fileSize = targetDoc.length(),
                                        remoteLastModified = remoteLastModHeader
                                    )
                                )
                            }
                        } else {
                            errors.add("Failed '${remoteItem.name}': HTTP ${response.code}")
                        }
                    }
                } catch (_: Exception) {
                    errors.add("Error downloading '${remoteItem.name}'")
                } finally {
                    tempDownloadFile.delete()
                }
            }

            if (errors.isNotEmpty() && filesDownloaded == 0 && filesSkipped == 0) {
                return SyncResult(false, 0, 0, 0L, errors.joinToString("; "))
            } else {
                val errorSummary = if (errors.isNotEmpty()) " (Partial errors: ${errors.take(3).joinToString(", ")})" else null
                return SyncResult(true, filesDownloaded, filesSkipped, bytesTotal, errorSummary)
            }
        } catch (_: Exception) {
            return SyncResult(false, 0, 0, 0L, "Remote download failed")
        }
    }

    private fun listRemoteFiles(baseUrl: String, config: WebDavConfig): List<RemoteFileItem> {
        val result = mutableListOf<RemoteFileItem>()
        try {
            val targetUrlStr = resolveWebDavUrl(baseUrl, "")
            val request = Request.Builder()
                .url(targetUrlStr)
                .header("Authorization", Credentials.basic(config.username, config.password))
                .header("User-Agent", "SyncMe-Android")
                .header("Depth", "1")
                .method("PROPFIND", null)
                .build()

            executeWithSafeRedirects(baseUrl, request).use { response ->
                if (response.isSuccessful || response.code == 207) {
                    val xmlStr = response.body?.string() ?: ""
                    val parsed = parsePropfindXml(xmlStr, baseUrl)
                    result.addAll(parsed)
                }
            }
        } catch (_: Exception) {}
        return result
    }

    fun parsePropfindXml(xml: String, baseUrl: String): List<RemoteFileItem> {
        if (xml.length > 10 * 1024 * 1024) {
            throw IllegalArgumentException("Oversized WebDAV XML response (${xml.length} bytes)")
        }

        val items = mutableListOf<RemoteFileItem>()
        val cleanBase = formatUrl(baseUrl)

        val dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        dbf.isNamespaceAware = true


        try {
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false)
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            dbf.isXIncludeAware = false
            dbf.setExpandEntityReferences(false)
        } catch (e: Exception) {
            throw SecurityException("Mandatory XML security features could not be configured", e)
        }

        try {
            val db = dbf.newDocumentBuilder()
            val doc = db.parse(org.xml.sax.InputSource(StringReader(xml)))

            val responseList = doc.getElementsByTagNameNS("*", "response")
            for (i in 0 until responseList.length) {
                val respNode = responseList.item(i) as? org.w3c.dom.Element ?: continue

            
                val collections = respNode.getElementsByTagNameNS("*", "collection")
                if (collections.length > 0) continue

                val hrefList = respNode.getElementsByTagNameNS("*", "href")
                if (hrefList.length == 0) continue
                val rawHref = hrefList.item(0).textContent?.trim() ?: continue

                val lastModList = respNode.getElementsByTagNameNS("*", "getlastmodified")
                val lastMod = if (lastModList.length > 0) lastModList.item(0).textContent?.trim() else null

                val lenList = respNode.getElementsByTagNameNS("*", "getcontentlength")
                val contentLen = if (lenList.length > 0) lenList.item(0).textContent?.trim()?.toLongOrNull() ?: 0L else 0L

                val fullHref = resolveWebDavUrl(cleanBase, rawHref)
                val fileName = rawHref.trimEnd('/').substringAfterLast('/').ifEmpty { "file_${System.currentTimeMillis()}" }

                items.add(
                    RemoteFileItem(
                        href = fullHref,
                        name = fileName,
                        isDirectory = false,
                        lastModified = lastMod,
                        contentLength = contentLen
                    )
                )
            }
        } catch (e: CrossOriginUrlException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (_: Exception) {
            
        }

        return items
    }

    fun isSameOrigin(url1: String, url2: String): Boolean {
        val u1 = url1.toHttpUrlOrNull() ?: return false
        val u2 = url2.toHttpUrlOrNull() ?: return false
        return u1.scheme.equals(u2.scheme, ignoreCase = true) &&
               u1.host.equals(u2.host, ignoreCase = true) &&
               u1.port == u2.port
    }

    fun resolveFullHref(baseUrl: String, href: String): String {
        return resolveWebDavUrl(baseUrl, href)
    }

    private fun getOrCreateLocalDocument(rootDoc: DocumentFile, relativePath: String, mimeType: String): DocumentFile? {
    
        if (relativePath.contains("..") || relativePath.contains("\u0000") || relativePath.startsWith("/") || relativePath.contains(":")) {
            throw IllegalArgumentException("Unsafe path traversal detected in remote file: $relativePath")
        }
        val parts = relativePath.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty()) return null

        var currentDoc = rootDoc

        for (i in 0 until parts.size - 1) {
            val dirName = parts[i]
            var subDir = currentDoc.findFile(dirName)
            if (subDir == null || !subDir.isDirectory) {
                subDir = currentDoc.createDirectory(dirName)
            }
            if (subDir == null) return null
            currentDoc = subDir
        }

        val fileName = parts.last()
        val existing = currentDoc.findFile(fileName)
        if (existing != null && existing.isFile) {
            return existing
        }
        return currentDoc.createFile(mimeType, fileName)
    }

    private fun createWebDavDirectory(baseUrl: String, config: WebDavConfig, relativePath: String) {
        try {
            val parts = relativePath.split('/').filter { it.isNotEmpty() }
            var currentPath = ""
            for (part in parts) {
                currentPath = if (currentPath.isEmpty()) part else "$currentPath/$part"
                val targetUrl = resolveWebDavUrl(baseUrl, currentPath)
                val request = Request.Builder()
                    .url(targetUrl)
                    .header("Authorization", Credentials.basic(config.username, config.password))
                    .header("User-Agent", "SyncMe-Android")
                    .method("MKCOL", null)
                    .build()

                client.newCall(request).execute().close()
            }
        } catch (_: Exception) {
        
        }
    }

    private fun formatUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        val url = when {
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("http://127.0.0.1", ignoreCase = true) || trimmed.startsWith("http://localhost", ignoreCase = true) -> trimmed
            trimmed.startsWith("http://", ignoreCase = true) -> throw IllegalArgumentException("Cleartext HTTP connections are disabled. WebDAV requires HTTPS.")
            else -> "https://$trimmed"
        }
        return if (url.endsWith("/")) url else "$url/"
    }

    fun downloadStreamToFile(response: okhttp3.Response, tempFile: File, maxBytes: Long = MAX_DOWNLOAD_BYTES): Long {
        val body = response.body ?: throw IllegalArgumentException("Response body is null")
        val contentLength = body.contentLength()
        if (contentLength > maxBytes) {
            throw IllegalArgumentException("Content-Length ($contentLength bytes) exceeds maximum permitted limit ($maxBytes bytes)")
        }

        var totalBytesRead = 0L
        val buffer = ByteArray(64 * 1024)

        try {
            body.byteStream().use { inputStream ->
                tempFile.outputStream().use { outputStream ->
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        totalBytesRead += bytesRead
                        if (totalBytesRead > maxBytes) {
                            throw IllegalArgumentException("Downloaded data ($totalBytesRead bytes) exceeded maximum permitted limit ($maxBytes bytes)")
                        }
                        outputStream.write(buffer, 0, bytesRead)
                    }
                    outputStream.flush()
                }
            }
            return totalBytesRead
        } catch (e: Exception) {
            tempFile.delete() 
            throw e
        }
    }

    companion object {
        const val MAX_DOWNLOAD_BYTES = 500 * 1024 * 1024L 

        fun cleanupStagingDirectories(context: Context) {
            try {
                val cacheDir = context.cacheDir
                cacheDir.listFiles()?.forEach { file ->
                    if (file.isDirectory && (file.name.startsWith("device_backup_staging_") || file.name.startsWith("enc_staging_"))) {
                        file.deleteRecursively()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun resolveWebDavUrl(baseUrl: String, relativePath: String): String {
        val cleanBaseUrl = formatUrl(baseUrl)
        val baseHttpUrl = cleanBaseUrl.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid base WebDAV URL: $baseUrl")

        val rawPath = relativePath.trim()
        if (rawPath.isBlank()) {
            return cleanBaseUrl
        }

        val isAbsoluteUrl = rawPath.startsWith("http://", ignoreCase = true) || rawPath.startsWith("https://", ignoreCase = true)


        val lowerRaw = rawPath.lowercase()
        if (lowerRaw.contains("%2e%2e") || lowerRaw.contains("%2f") || lowerRaw.contains("%5c") || lowerRaw.contains("%00") || lowerRaw.contains("%25")) {
            throw IllegalArgumentException("Encoded path traversal or separator sequence detected in WebDAV URL: $rawPath")
        }

        if (!isAbsoluteUrl && (rawPath.contains("..") || rawPath.contains("\u0000") || rawPath.contains(":") || rawPath.startsWith("\\"))) {
            throw IllegalArgumentException("Unsafe path component detected in WebDAV URL: $rawPath")
        }

        val resolvedUrl = if (isAbsoluteUrl) {
            rawPath.toHttpUrlOrNull() ?: throw IllegalArgumentException("Malformed absolute WebDAV URL: $rawPath")
        } else if (rawPath.startsWith("/")) {
            baseHttpUrl.newBuilder().encodedPath(rawPath).build()
        } else {
            baseHttpUrl.resolve(rawPath) ?: throw IllegalArgumentException("Failed to resolve WebDAV path: $rawPath")
        }

        if (resolvedUrl.username.isNotEmpty() || resolvedUrl.password.isNotEmpty()) {
            throw IllegalArgumentException("Userinfo in WebDAV URL rejected: $resolvedUrl")
        }

        val resolvedStr = resolvedUrl.toString()
        if (!isSameOrigin(cleanBaseUrl, resolvedStr)) {
            throw CrossOriginUrlException("Resolved WebDAV URL cross-origin mismatch: $resolvedStr")
        }

    
        val baseRootPath = if (baseHttpUrl.encodedPath.endsWith("/")) baseHttpUrl.encodedPath else "${baseHttpUrl.encodedPath}/"
        val targetPath = if (resolvedUrl.encodedPath.endsWith("/")) resolvedUrl.encodedPath else "${resolvedUrl.encodedPath}/"

        if (!targetPath.startsWith(baseRootPath)) {
            throw CrossOriginUrlException("Resolved WebDAV URL escapes configured WebDAV root directory: $resolvedStr (Root: $baseRootPath)")
        }

        return resolvedStr
    }

    private fun getFileNameFromUri(context: Context, uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        name = cursor.getString(index)
                    }
                }
            }
        }
        if (name == null) {
            name = uri.path?.let { p ->
                val cut = p.lastIndexOf('/')
                if (cut != -1) p.substring(cut + 1) else p
            }
        }
        return name
    }
}
