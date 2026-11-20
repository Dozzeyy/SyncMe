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
import org.vahak.syncme.crypto.CryptoEngine
import org.vahak.syncme.crypto.EncryptedNames
import org.vahak.syncme.crypto.EncryptedMetadata
import org.vahak.syncme.data.SyncTask
import org.vahak.syncme.data.SyncedFileState
import org.vahak.syncme.data.SyncedFileStateDao
import org.vahak.syncme.data.WebDavConfig
import java.io.File
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

data class SyncResult(
    val success: Boolean,
    val filesTransferred: Int,
    val filesSkipped: Int,
    val totalBytes: Long,
    val errorMessage: String? = null
)

private data class StagedUploadItem(

    val stagedFile: File?,
    val plainUri: Uri?,
    val uploadSize: Long,
    val remotePath: String,
    val relativePath: String,
    val originalFileName: String,
    val localLastModified: Long,
    val localSize: Long
)

data class RemoteFileItem(
    val href: String,
    val name: String,
    val isDirectory: Boolean,
    val lastModified: String?,
    val contentLength: Long
)

class CrossOriginUrlException(message: String) : Exception(message)

@Singleton
class WebDavClient @Inject constructor(
    private val cryptoEngine: CryptoEngine
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.MINUTES) 
        .followRedirects(false)
        .followSslRedirects(false)
    
        .connectionSpecs(listOf(okhttp3.ConnectionSpec.MODERN_TLS))
        .build()

    private fun clientFor(baseUrl: String, pin: String?): OkHttpClient {
        val pins = CertPins.parse(pin)
        if (pins.isEmpty()) return client
        require(CertPins.isValid(pin)) { "Invalid certificate pin format" }
        val host = formatUrl(baseUrl).toHttpUrlOrNull()?.host
            ?: throw IllegalArgumentException("Invalid base WebDAV URL")
        val pinner = okhttp3.CertificatePinner.Builder().apply { pins.forEach { add(host, it) } }.build()
        return client.newBuilder().certificatePinner(pinner).build()
    }

    fun executeWithSafeRedirects(
        baseUrl: String,
        initialRequest: Request,
        maxRedirects: Int = 5,
        pin: String? = null
    ): okhttp3.Response {
        var currentRequest = initialRequest
        var redirectCount = 0
        val http = clientFor(baseUrl, pin)

        while (true) {
            val response = http.newCall(currentRequest).execute()
            if (!response.isRedirect) return response

            redirectCount++
            if (redirectCount > maxRedirects) {
                response.close()
                throw IllegalArgumentException("Too many HTTP redirects (exceeded maximum limit of $maxRedirects)")
            }

            val locationHeader = response.header("Location")
            response.close()

            if (locationHeader.isNullOrBlank()) {
                throw IllegalArgumentException("Redirect response missing Location header")
            }

            val targetUrlStr = resolveWebDavUrl(baseUrl, locationHeader)
            currentRequest = currentRequest.newBuilder()
                .url(targetUrlStr)
                .build()
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

            executeWithSafeRedirects(config.url, request, pin = config.certificatePin).use { response ->
                
                when (response.code) {
                    207 -> Result.success("Connection successful!")
                    401, 403 -> Result.failure(Exception("Login rejected (HTTP ${response.code}). Check the user name and password."))
                    404 -> Result.failure(Exception("Folder not found (HTTP 404). Check the server URL."))
                    405, 501 -> Result.failure(Exception("The server does not support WebDAV at this URL (HTTP ${response.code})."))
                    else -> Result.failure(Exception("Unexpected answer from the server (HTTP ${response.code})."))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun deleteRemotePath(baseUrl: String, config: WebDavConfig, remotePath: String): Boolean {
        return try {
            val targetUrl = resolveListingHref(baseUrl, remotePath)
            val request = Request.Builder()
                .url(targetUrl)
                .header("Authorization", Credentials.basic(config.username, config.password))
                .header("User-Agent", "SyncMe-Android")
                .delete()
                .build()

            executeWithSafeRedirects(baseUrl, request, pin = config.certificatePin).use { response ->
                response.isSuccessful || response.code == 204 || response.code == 404
            }
        } catch (_: Exception) {
            false
        }
    }

    private class NameKeyInfo(val key: SecretKey, val isNewSalt: Boolean)

    private fun resolveNameKey(baseUrl: String, config: WebDavConfig, passphrase: String): NameKeyInfo {
        val markerUrl = resolveWebDavUrl(baseUrl, CryptoEngine.NAME_SALT_MARKER_FILE)

        fun fetch(): ByteArray? {
            val request = Request.Builder()
                .url(markerUrl)
                .header("Authorization", Credentials.basic(config.username, config.password))
                .header("User-Agent", "SyncMe-Android")
                .get()
                .build()
            return executeWithSafeRedirects(baseUrl, request, pin = config.certificatePin).use { response ->
                when {
                    response.code == 404 -> null
                    response.isSuccessful -> {
                        val body = response.body ?: throw IllegalStateException("Empty name salt marker")
                        readBounded(body.byteStream(), 256)
                    }
                    else -> throw IllegalStateException("Could not read the name salt marker (HTTP ${response.code})")
                }
            }
        }

        fun fromMarker(bytes: ByteArray, isNew: Boolean): NameKeyInfo {
            val salt = cryptoEngine.parseNameSaltMarker(bytes)
                ?: throw IllegalStateException("The name salt marker on the server is invalid")
            return NameKeyInfo(cryptoEngine.deriveNameKey(passphrase, salt), isNew)
        }

        fetch()?.let { return fromMarker(it, false) }

        val markerBytes = cryptoEngine.encodeNameSaltMarker(cryptoEngine.generateSalt())
        val put = Request.Builder()
            .url(markerUrl)
            .header("Authorization", Credentials.basic(config.username, config.password))
            .header("User-Agent", "SyncMe-Android")
            .header("If-None-Match", "*")
            .put(markerBytes.toRequestBody("application/octet-stream".toMediaTypeOrNull()))
            .build()
        val code = executeWithSafeRedirects(baseUrl, put, pin = config.certificatePin).use { it.code }
        return when {
            code in 200..299 -> fromMarker(markerBytes, true)
            code == 412 -> fromMarker(fetch() ?: throw IllegalStateException("Name salt marker vanished"), false)
            else -> throw IllegalStateException("Could not create the name salt marker (HTTP $code)")
        }
    }

    private fun remoteContentMatches(
        baseUrl: String,
        config: WebDavConfig,
        url: String,
        expected: ByteArray,
        passphrase: String?
    ): Boolean {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("Authorization", Credentials.basic(config.username, config.password))
                .header("User-Agent", "SyncMe-Android")
                .get()
                .build()
            executeWithSafeRedirects(baseUrl, request, pin = config.certificatePin).use { response ->
                if (!response.isSuccessful) return false
                val body = response.body ?: return false
                val limit = MAX_TRANSFER_BYTES + 64L * 1024
                if (body.contentLength() > limit) return false
                val remote = readBounded(body.byteStream(), limit)
                val plain = if (passphrase != null) cryptoEngine.DecryptSession(passphrase).decrypt(remote).payloadBytes else remote
                java.security.MessageDigest.isEqual(plain, expected)
            }
        } catch (_: Exception) {
            false
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

        try {
            val (rawBytes, fileName) = when (sourceType) {
                SyncTask.SOURCE_CONTACTS -> Pair(org.vahak.syncme.backup.ContactsBackupReader.generateContactsVcf(context), "contacts_backup.vcf")
                SyncTask.SOURCE_MESSAGES -> Pair(org.vahak.syncme.backup.SmsBackupReader.generateSmsJson(context), "sms_messages_backup.json")
                SyncTask.SOURCE_CALL_LOGS -> Pair(org.vahak.syncme.backup.CallLogBackupReader.generateCallLogJson(context), "call_logs_backup.json")
                else -> return SyncResult(false, 0, 0, 0L, "Unsupported device backup source type")
            }

            onProgress?.invoke("Generating $fileName...")
            val baseUrl = formatUrl(config.url)

            
            val remoteFileName = if (isEncrypted) {
                cryptoEngine.encryptedName(resolveNameKey(baseUrl, config, passphrase!!).key, fileName)
            } else {
                fileName
            }

            if (remoteContentMatches(baseUrl, config, resolveWebDavUrl(baseUrl, remoteFileName), rawBytes, if (isEncrypted) passphrase else null)) {
                onProgress?.invoke("$fileName is unchanged; nothing to upload.")
                return SyncResult(true, 0, 1, 0L)
            }

            val uploadBytes = if (isEncrypted) {
                onProgress?.invoke("Encrypting $fileName offline...")
                val metadata = EncryptedMetadata(
                    originalName = fileName,
                    relativePath = fileName,
                    mimeType = if (fileName.endsWith(".vcf")) "text/vcard" else "application/json",
                    fileSize = rawBytes.size.toLong(),
                    lastModified = System.currentTimeMillis()
                )
                cryptoEngine.encryptContainer(passphrase!!, metadata, rawBytes)
            } else {
                rawBytes
            }

            onProgress?.invoke("Uploading $fileName to WebDAV...")
            val targetUrl = resolveWebDavUrl(baseUrl, remoteFileName)
            val requestBody = uploadBytes.toRequestBody("application/octet-stream".toMediaTypeOrNull())

            var lastErrorMsg = ""
            for (attempt in 1..3) {
                try {
                    val request = Request.Builder()
                        .url(targetUrl)
                        .header("Authorization", Credentials.basic(config.username, config.password))
                        .header("User-Agent", "SyncMe-Android")
                        .put(requestBody)
                        .build()

                    executeWithSafeRedirects(baseUrl, request, pin = config.certificatePin).use { response ->
                        if (response.isSuccessful || response.code == 201 || response.code == 204) {
                            return SyncResult(true, 1, 0, uploadBytes.size.toLong())
                        } else {
                            lastErrorMsg = "HTTP ${response.code}"
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
        onProgress: ((String) -> Unit)? = null,
        task: SyncTask? = null
    ): SyncResult {
        if (isEncrypted && passphrase.isNullOrBlank()) {
            return SyncResult(false, 0, 0, 0L, "Encryption is enabled, but the passphrase is unavailable.")
        }

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

            if (task != null && task.shouldExcludeFile(fileName, localSize)) {
                onProgress?.invoke("File '$fileName' is excluded by filter criteria.")
                return SyncResult(true, 0, 1, 0L)
            }

            val baseUrl = formatUrl(config.url)
            val nameKeyInfo = if (isEncrypted) resolveNameKey(baseUrl, config, passphrase!!) else null

            val cachedState = syncedFileStateDao.getByPath(taskId, relativePath)
            
            if (nameKeyInfo?.isNewSalt != true && cachedState != null && localLastModified > 0L && cachedState.lastModified == localLastModified && cachedState.fileSize == localSize) {
                onProgress?.invoke("File '$fileName' is unmodified. Skipping.")
                return SyncResult(true, 0, 1, 0L)
            }

            onProgress?.invoke(if (isEncrypted) "Encrypting $fileName offline..." else "Preparing $fileName...")

            val inputStream = context.contentResolver.openInputStream(targetUri)
                ?: return SyncResult(false, 0, 0, 0L, "Cannot open stream for URI: $targetUri")

            if (localSize > MAX_TRANSFER_BYTES) {
                inputStream.close()
                return SyncResult(false, 0, 0, 0L, "File is larger than the ${MAX_TRANSFER_BYTES / (1024 * 1024)} MB limit")
            }
            val rawBytes = inputStream.use { readBounded(it, MAX_TRANSFER_BYTES, localSize) }

            val (uploadBytes, remoteFileName) = if (isEncrypted) {
                val activePassphrase = passphrase!!
                val metadata = EncryptedMetadata(
                    originalName = fileName,
                    relativePath = relativePath,
                    mimeType = fileDoc?.type ?: "application/octet-stream",
                    fileSize = localSize,
                    lastModified = localLastModified
                )
                val encContainer = cryptoEngine.encryptContainer(activePassphrase, metadata, rawBytes)
                val obfuscatedName = cryptoEngine.encryptedName(nameKeyInfo!!.key, relativePath)
                Pair(encContainer, obfuscatedName)
            } else {
                Pair(rawBytes, fileName)
            }

            onProgress?.invoke("Uploading $fileName to WebDAV...")
            val targetUrl = resolveWebDavUrl(baseUrl, remoteFileName)
            val requestBody = uploadBytes.toRequestBody("application/octet-stream".toMediaTypeOrNull())

            var lastErrorMsg = ""
            for (attempt in 1..3) {
                try {
                    val request = Request.Builder()
                        .url(targetUrl)
                        .header("Authorization", Credentials.basic(config.username, config.password))
                        .header("User-Agent", "SyncMe-Android")
                        .put(requestBody)
                        .build()

                    executeWithSafeRedirects(baseUrl, request, pin = config.certificatePin).use { response ->
                        if (response.isSuccessful || response.code == 201 || response.code == 204) {
                            syncedFileStateDao.insertOrUpdate(
                                SyncedFileState(
                                    taskId = taskId,
                                    relativePath = relativePath,
                                    lastModified = localLastModified,
                                    fileSize = localSize,
                                    acceptedSourceModified = maxOf(cachedState?.acceptedSourceModified ?: 0L, localLastModified)
                                )
                            )
                            return SyncResult(true, 1, 0, uploadBytes.size.toLong())
                        } else {
                            lastErrorMsg = "HTTP ${response.code}"
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
        onProgress: ((currentFile: Int, totalFiles: Int, fileName: String) -> Unit)? = null,
        task: SyncTask? = null
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
            val baseUrl = formatUrl(config.url)
            val allStates = syncedFileStateDao.getForTask(taskId).associateBy { it.relativePath }

            val nameKeyInfo: NameKeyInfo? = if (isEncrypted) {
                onProgress?.invoke(0, totalFiles, "Deriving file-name key offline...")
                resolveNameKey(baseUrl, config, passphrase!!)
            } else null
            
            val existingStatesMap = if (nameKeyInfo?.isNewSalt == true) emptyMap() else allStates

            val sessionSalt = if (isEncrypted) cryptoEngine.generateSalt() else null
            val sessionKey: SecretKey? = if (isEncrypted && sessionSalt != null) {
                onProgress?.invoke(0, totalFiles, "Deriving encryption key offline...")
                cryptoEngine.deriveKey(passphrase!!, sessionSalt)
            } else null
            val nameKey: SecretKey? = nameKeyInfo?.key

            val stagedItems = mutableListOf<StagedUploadItem>()
            val errors = mutableListOf<String>()
            var filesSkipped = 0
            var skippedUnmodified = 0
            var skippedFiltered = 0

            for ((index, item) in allFiles.withIndex()) {
                val (childDoc, relativePath) = item
                val currentNum = index + 1
                val fileName = childDoc.name ?: "unnamed"

                val localLastModified = childDoc.lastModified()
                val localSize = childDoc.length()

                if (task != null && task.shouldExcludeFile(fileName, localSize)) {
                    filesSkipped++
                    skippedFiltered++
                    onProgress?.invoke(currentNum, totalFiles, "$fileName [Skipped - Filtered]")
                    continue
                }

                val cachedState = existingStatesMap[relativePath]

                if (cachedState != null && localLastModified > 0L && cachedState.lastModified == localLastModified && cachedState.fileSize == localSize) {
                    filesSkipped++
                    skippedUnmodified++
                    onProgress?.invoke(currentNum, totalFiles, "$fileName [Skipped - Unmodified]")
                    continue
                }

                onProgress?.invoke(currentNum, totalFiles, if (isEncrypted) "Encrypting offline ($currentNum/$totalFiles): $fileName" else "Preparing ($currentNum/$totalFiles): $fileName")

                
                if (localSize > MAX_TRANSFER_BYTES) {
                    errors.add("A file was skipped: larger than the ${MAX_TRANSFER_BYTES / (1024 * 1024)} MB limit")
                    continue
                }
                try {
                    if (isEncrypted && sessionKey != null && sessionSalt != null) {
                        val inputStream = context.contentResolver.openInputStream(childDoc.uri)
                        if (inputStream != null) {
                            val rawBytes = inputStream.use { readBounded(it, MAX_TRANSFER_BYTES, localSize) }
                            val metadata = EncryptedMetadata(
                                originalName = fileName,
                                relativePath = relativePath,
                                mimeType = childDoc.type ?: "application/octet-stream",
                                fileSize = localSize,
                                lastModified = localLastModified
                            )
                            val encContainer = cryptoEngine.encryptContainerWithKey(sessionKey, sessionSalt, metadata, rawBytes)
                            val obfuscatedName = cryptoEngine.encryptedName(nameKey!!, relativePath)
                            val tmpFile = File(stagingDir, "stage_$currentNum.enc").apply { writeBytes(encContainer) }
                            stagedItems.add(
                                StagedUploadItem(
                                    stagedFile = tmpFile,
                                    plainUri = null,
                                    uploadSize = tmpFile.length(),
                                    remotePath = obfuscatedName,
                                    relativePath = relativePath,
                                    originalFileName = fileName,
                                    localLastModified = localLastModified,
                                    localSize = localSize
                                )
                            )
                        }
                    } else {
                        
                        stagedItems.add(
                            StagedUploadItem(
                                stagedFile = null,
                                plainUri = childDoc.uri,
                                uploadSize = localSize,
                                remotePath = relativePath,
                                relativePath = relativePath,
                                originalFileName = fileName,
                                localLastModified = localLastModified,
                                localSize = localSize
                            )
                        )
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: OutOfMemoryError) {
                    errors.add("A file was skipped: not enough memory to process it")
                } catch (_: Exception) {
                    errors.add("A file was skipped: it could not be read or encrypted")
                }
            }

            var filesUploaded = 0
            var bytesTotal = 0L

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
                val requestBody = if (stagedItem.stagedFile != null) {
                    stagedItem.stagedFile.asRequestBody("application/octet-stream".toMediaTypeOrNull())
                } else {
                    UriRequestBody(context, stagedItem.plainUri!!, stagedItem.uploadSize, "application/octet-stream".toMediaTypeOrNull())
                }

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

                        executeWithSafeRedirects(baseUrl, request, pin = config.certificatePin).use { response ->
                            if (response.isSuccessful || response.code == 201 || response.code == 204) {
                                uploadSuccess = true
                                filesUploaded++
                                bytesTotal += stagedItem.uploadSize

                                syncedFileStateDao.insertOrUpdate(
                                    SyncedFileState(
                                        taskId = taskId,
                                        relativePath = stagedItem.relativePath,
                                        lastModified = stagedItem.localLastModified,
                                        fileSize = stagedItem.localSize,
                                        acceptedSourceModified = maxOf(
                                            allStates[stagedItem.relativePath]?.acceptedSourceModified ?: 0L,
                                            stagedItem.localLastModified
                                        )
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
                    errors.add("A file failed to upload ($lastErrorMsg)")
                }
            }

            
            var cleanupNote: String? = null
            if (task?.isMirrorSync == true) {
                onProgress?.invoke(totalStaged, totalStaged, "Checking for orphaned remote files...")
                val remoteItems = listRemoteFilesOrNull(baseUrl, config)
                if (remoteItems == null) {
                    cleanupNote = "Mirror cleanup skipped: the remote folder could not be listed."
                } else {
                    val localRelativePaths = allFiles.map { it.second }.toSet()
                    val expectedNames: Set<String> = if (isEncrypted) {
                        localRelativePaths.map { cryptoEngine.encryptedName(nameKey!!, it) }.toSet()
                    } else {
                        localRelativePaths
                    }
                    val orphans = remoteItems.filter { item ->
                        item.name !in expectedNames && (!isEncrypted || EncryptedNames.isContainerName(item.name))
                    }
                    val skipReason = MirrorSafety.evaluate(
                        sourceCount = allFiles.size,
                        destinationCount = remoteItems.size,
                        deleteCount = orphans.size,
                        hadErrors = errors.isNotEmpty()
                    )
                    if (skipReason != null) {
                        cleanupNote = skipReason
                    } else {
                        for (orphan in orphans) {
                            onProgress?.invoke(totalStaged, totalStaged, "Mirror sync: Deleting remote file ${orphan.name}")
                            deleteRemotePath(baseUrl, config, orphan.href)
                        }
                    }
                }
            }

            if (errors.isNotEmpty() && filesUploaded == 0 && filesSkipped == 0) {
                return SyncResult(false, 0, 0, 0L, errors.joinToString("; "))
            } else {
                val errorSummary = listOfNotNull(
                    if (filesUploaded == 0 && allFiles.isNotEmpty()) "No file was uploaded: $skippedUnmodified unchanged since last sync, $skippedFiltered excluded by filters, ${filesSkipped - skippedUnmodified - skippedFiltered} already up to date or kept remote." else null,
                    if (errors.isNotEmpty()) "(Partial errors: ${errors.take(3).joinToString(", ")})" else null,
                    cleanupNote
                ).joinToString(" ").ifEmpty { null }
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
        onProgress: ((currentFile: Int, totalFiles: Int, fileName: String) -> Unit)? = null,
        task: SyncTask? = null,
        
        allowLegacyV1: Boolean = false
    ): SyncResult {
        if (isEncrypted && passphrase.isNullOrBlank()) {
            return SyncResult(false, 0, 0, 0L, "Encryption is enabled, but the passphrase is unavailable.")
        }

        return try {
            val rootDoc = DocumentFile.fromTreeUri(context, Uri.parse(folderUriStr))
                ?: return SyncResult(false, 0, 0, 0L, "Invalid destination folder URI")

            val baseUrl = formatUrl(config.url)
            var listFailure: String? = null
            val remoteItems = listRemoteFilesOrNull(baseUrl, config) { listFailure = it }
                ?: return SyncResult(
                    false, 0, 0, 0L,
                    "Could not list the remote WebDAV folder" + (listFailure?.let { " ($it)." } ?: ".")
                )
            val decryptSession = if (isEncrypted) cryptoEngine.DecryptSession(passphrase!!, allowLegacyV1 = allowLegacyV1) else null
            var aborted = false

            if (remoteItems.none { it.name != CryptoEngine.NAME_SALT_MARKER_FILE }) {
                return SyncResult(true, 0, 0, 0L, "Remote WebDAV folder has no files to download (the server listed ${remoteItems.size} item(s), none of them backup content).")
            }

            val strategy = task?.conflictStrategy ?: SyncTask.STRATEGY_KEEP_BOTH
            val existingStatesMap = syncedFileStateDao.getForTask(taskId).associateBy { it.relativePath }

            var filesDownloaded = 0
            var filesSkipped = 0
            var conflictsKept = 0
            var bytesTotal = 0L
            var skippedUnmodified = 0
            var skippedFiltered = 0
            val errors = mutableListOf<String>()

            val totalRemoteFiles = remoteItems.size
            val downloadedRelativePaths = mutableSetOf<String>()
            
            var cleanupSafe = true

            for ((index, remoteItem) in remoteItems.withIndex()) {
                val currentNum = index + 1
                val remoteLastModHeader = remoteItem.lastModified ?: ""

            
                if (remoteItem.name == CryptoEngine.NAME_SALT_MARKER_FILE) continue

                if (task != null && task.shouldExcludeFile(remoteItem.name, remoteItem.contentLength)) {
                    filesSkipped++
                    skippedFiltered++
                    if (isEncrypted) cleanupSafe = false else downloadedRelativePaths.add(remoteItem.name)
                    onProgress?.invoke(currentNum, totalRemoteFiles, "${remoteItem.name} [Skipped - Filtered]")
                    continue
                }

            
                val cachedState = if (remoteLastModHeader.isNotEmpty()) {
                    existingStatesMap.values
                        .filter { state -> state.remoteLastModified == remoteLastModHeader }
                        .singleOrNull()
                        ?.takeIf { state ->
                            try { findLocalDocument(rootDoc, state.relativePath) != null } catch (_: Exception) { false }
                        }
                } else null
                if (cachedState != null) {
                    filesSkipped++
                    skippedUnmodified++
                    downloadedRelativePaths.add(cachedState.relativePath)
                    onProgress?.invoke(currentNum, totalRemoteFiles, "${remoteItem.name} [Skipped - Unmodified]")
                    continue
                }

                onProgress?.invoke(currentNum, totalRemoteFiles, "Downloading ${remoteItem.name}...")

                if (!isSameOrigin(baseUrl, remoteItem.href)) {
                    errors.add("A file was skipped: rejected cross-origin URL")
                    continue
                }

                val fileReq = Request.Builder()
                    .url(remoteItem.href)
                    .header("Authorization", Credentials.basic(config.username, config.password))
                    .header("User-Agent", "SyncMe-Android")
                    .get()
                    .build()

                var acceptedFromRemote = 0L
                val tempDownloadFile = File(context.cacheDir, "temp_dl_${System.currentTimeMillis()}_$index.tmp")
                try {
                    executeWithSafeRedirects(baseUrl, fileReq, pin = config.certificatePin).use { response ->
                        if (response.isSuccessful) {
                            val bytesDownloaded = downloadStreamToFile(response, tempDownloadFile)
                            val downloadedBytes = tempDownloadFile.readBytes()

                            val relPath: String
                            val mimeType: String
                            val incomingBytes: ByteArray

                            if (isEncrypted) {
                                onProgress?.invoke(currentNum, totalRemoteFiles, "Decrypting ${remoteItem.name}...")
                                val decryptedResult = decryptSession!!.decrypt(downloadedBytes)
                                val metadata = decryptedResult.metadata

                                if (task != null && task.shouldExcludeFile(metadata.originalName, metadata.fileSize)) {
                                    filesSkipped++
                                    downloadedRelativePaths.add(metadata.relativePath)
                                    return@use
                                }

                                
                                val accepted = existingStatesMap[metadata.relativePath]?.acceptedSourceModified ?: 0L
                                if (metadata.lastModified < accepted) {
                                    errors.add("A file was rejected: it is older than the version already synced (possible rollback)")
                                    downloadedRelativePaths.add(metadata.relativePath)
                                    return@use
                                }
                                acceptedFromRemote = metadata.lastModified

                                relPath = metadata.relativePath
                                mimeType = metadata.mimeType
                                incomingBytes = decryptedResult.payloadBytes
                            } else {
                                relPath = remoteItem.name
                                mimeType = "application/octet-stream"
                                incomingBytes = downloadedBytes
                            }

                        
                            val placed = placeIncomingFile(
                                context, rootDoc, relPath, mimeType, incomingBytes, existingStatesMap[relPath], strategy
                            )
                            downloadedRelativePaths.add(relPath)

                            when (placed.outcome) {
                                PlaceOutcome.WRITTEN -> {
                                    filesDownloaded++
                                    bytesTotal += bytesDownloaded
                                }
                                PlaceOutcome.UNCHANGED -> filesSkipped++
                                PlaceOutcome.KEPT_LOCAL -> {
                                    filesSkipped++
                                    conflictsKept++
                                }
                                PlaceOutcome.KEPT_BOTH -> {
                                    filesDownloaded++
                                    bytesTotal += bytesDownloaded
                                    conflictsKept++
                                }
                            }

                            syncedFileStateDao.insertOrUpdate(
                                SyncedFileState(
                                    taskId = taskId,
                                    relativePath = relPath,
                                    lastModified = placed.stateLastModified,
                                    fileSize = placed.stateFileSize,
                                    remoteLastModified = remoteLastModHeader,
                                    acceptedSourceModified = maxOf(existingStatesMap[relPath]?.acceptedSourceModified ?: 0L, acceptedFromRemote)
                                )
                            )
                        } else {
                            errors.add("A download failed (HTTP ${response.code})")
                        }
                    }
                } catch (e: IllegalStateException) {
                    
                    errors.add("Stopped: too many files failed authentication (wrong passphrase or tampered server data)")
                    aborted = true
                } catch (_: Exception) {
                    errors.add("Error downloading a file")
                } finally {
                    
                    if (isEncrypted) tempDownloadFile.delete() else StagingFiles.secureDelete(tempDownloadFile)
                }
                if (aborted) break
            }

           
            var cleanupNote: String? = null
            if (task?.isMirrorSync == true) {
                if (!cleanupSafe || aborted || errors.isNotEmpty()) {
                    cleanupNote = "Mirror cleanup skipped: some remote files could not be checked or downloaded."
                } else {
                    onProgress?.invoke(totalRemoteFiles, totalRemoteFiles, "Checking for orphaned local files...")
                    val localFiles = rootDoc.listFiles().filter { it.isFile }
                    val orphans = localFiles.filter { child ->
                        val name = child.name ?: ""
                        name.isNotEmpty() &&
                            !CONFLICT_COPY_REGEX.matches(name) &&
                            !downloadedRelativePaths.contains(name) &&
                            !downloadedRelativePaths.any { it.endsWith("/$name") }
                    }
                    val skipReason = MirrorSafety.evaluate(
                        sourceCount = totalRemoteFiles,
                        destinationCount = localFiles.size,
                        deleteCount = orphans.size,
                        hadErrors = false
                    )
                    if (skipReason != null) {
                        cleanupNote = skipReason
                    } else {
                        for (child in orphans) {
                            onProgress?.invoke(totalRemoteFiles, totalRemoteFiles, "Mirror sync: Deleting local orphaned file ${child.name}")
                            try {
                                child.delete()
                            } catch (_: Exception) {}
                        }
                    }
                }
            }

            if (errors.isNotEmpty() && filesDownloaded == 0 && filesSkipped == 0) {
                return SyncResult(false, 0, 0, 0L, errors.joinToString("; "))
            } else {
                val errorSummary = listOfNotNull(
                    if (errors.isNotEmpty()) "(Partial errors: ${errors.take(3).joinToString(", ")})" else null,
                    if (conflictsKept > 0) "$conflictsKept local conflict(s) kept (see conflict strategy)." else null,
                    cleanupNote
                ).joinToString(" ").ifEmpty { null }
                return SyncResult(true, filesDownloaded, filesSkipped, bytesTotal, errorSummary)
            }
        } catch (_: Exception) {
            return SyncResult(false, 0, 0, 0L, "Remote download failed")
        }
    }

    suspend fun bidirectionalSyncWebDav(
        context: Context,
        config: WebDavConfig,
        task: SyncTask,
        passphrase: String? = null,
        syncedFileStateDao: SyncedFileStateDao,
        onProgress: ((currentFile: Int, totalFiles: Int, fileName: String) -> Unit)? = null
    ): SyncResult {
        if (task.isEncrypted && passphrase.isNullOrBlank()) {
            return SyncResult(false, 0, 0, 0L, "Encryption is enabled, but the passphrase is unavailable.")
        }

        
        val safeTask = task.copy(isMirrorSync = false)

        return try {
            val uploadRes = syncFolder(
                context = context,
                config = config,
                taskId = task.id,
                folderUriStr = task.localUri,
                isEncrypted = task.isEncrypted,
                passphrase = passphrase,
                syncedFileStateDao = syncedFileStateDao,
                onProgress = onProgress,
                task = safeTask
            )

            val downloadRes = downloadRemoteToLocalFolder(
                context = context,
                config = config,
                taskId = task.id,
                folderUriStr = task.localUri,
                isEncrypted = task.isEncrypted,
                passphrase = passphrase,
                syncedFileStateDao = syncedFileStateDao,
                onProgress = onProgress,
                task = safeTask
            )

            SyncResult(
                success = uploadRes.success && downloadRes.success,
                filesTransferred = uploadRes.filesTransferred + downloadRes.filesTransferred,
                filesSkipped = uploadRes.filesSkipped + downloadRes.filesSkipped,
                totalBytes = uploadRes.totalBytes + downloadRes.totalBytes,
                errorMessage = listOfNotNull(uploadRes.errorMessage, downloadRes.errorMessage).joinToString("; ").ifEmpty { null }
            )
        } catch (e: Exception) {
            SyncResult(false, 0, 0, 0L, "Bidirectional sync failed.")
        }
    }

    private fun listRemoteFilesOrNull(
        baseUrl: String,
        config: WebDavConfig,
        onFailure: (String) -> Unit = {}
    ): List<RemoteFileItem>? {
        return try {
            val targetUrlStr = resolveWebDavUrl(baseUrl, "")
            val request = Request.Builder()
                .url(targetUrlStr)
                .header("Authorization", Credentials.basic(config.username, config.password))
                .header("User-Agent", "SyncMe-Android")
                .header("Depth", "1")
                .method("PROPFIND", null)
                .build()

            executeWithSafeRedirects(baseUrl, request, pin = config.certificatePin).use { response ->
                if (response.isSuccessful || response.code == 207) {
                    val body = response.body
                    if (body == null) {
                        onFailure("empty reply from server"); return@use null
                    }
                    if (body.contentLength() > MAX_PROPFIND_BYTES) {
                        onFailure("folder listing is too large"); return@use null
                    }
                    
                    val xmlStr = String(readBounded(body.byteStream(), MAX_PROPFIND_BYTES), Charsets.UTF_8)
                    parsePropfindXml(xmlStr, baseUrl)
                } else {
                    onFailure("server answered HTTP ${response.code}")
                    null
                }
            }
        } catch (e: CrossOriginUrlException) {
            onFailure("a listed item points outside the configured WebDAV folder")
            null
        } catch (e: Exception) {
            onFailure("${e.javaClass.simpleName}: ${(e.message ?: "").take(120)}")
            null
        }
    }

    fun parsePropfindXml(xml: String, baseUrl: String): List<RemoteFileItem> {
        if (xml.length > MAX_PROPFIND_BYTES) {
            throw IllegalArgumentException("Oversized WebDAV XML response (${xml.length} bytes)")
        }

        val items = mutableListOf<RemoteFileItem>()
        val cleanBase = formatUrl(baseUrl)

        if (DOCTYPE_OR_ENTITY_REGEX.containsMatchIn(xml)) {
            throw IllegalArgumentException("DOCTYPE/ENTITY declarations are not allowed in WebDAV XML")
        }

        val dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance()
        dbf.isNamespaceAware = true

        fun tryHarden(block: () -> Unit) {
            try { block() } catch (_: Exception) { /* feature unsupported on this parser; the DOCTYPE guard above still holds */ }
        }
        tryHarden { dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        tryHarden { dbf.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        tryHarden { dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        tryHarden { dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        tryHarden { dbf.isXIncludeAware = false }
        tryHarden { dbf.setExpandEntityReferences(false) }

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

                
                val fullHref = try {
                    resolveListingHref(cleanBase, rawHref)
                } catch (e: CrossOriginUrlException) {
                    throw e
                } catch (_: IllegalArgumentException) {
                    continue
                }
                val fileName = decodePathSegment(fullHref.trimEnd('/').substringAfterLast('/'))
                if (fileName.isEmpty()) continue
                if (items.size >= MAX_LISTING_ITEMS) {
                    throw IllegalArgumentException("Remote folder lists more than $MAX_LISTING_ITEMS files")
                }

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
            
            throw IllegalArgumentException("Malformed WebDAV XML reply")
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

    fun resolveListingHref(baseUrl: String, href: String): String {
        val cleanBase = formatUrl(baseUrl)
        val baseHttpUrl = cleanBase.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid base WebDAV URL")
        val raw = href.trim()
        if (raw.isEmpty()) return cleanBase
        
        if (DOT_SEGMENT_REGEX.containsMatchIn(raw)) {
            throw IllegalArgumentException("Dot segment in WebDAV href")
        }
        val resolved = (if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) raw.toHttpUrlOrNull()
            else baseHttpUrl.resolve(raw))
            ?: throw IllegalArgumentException("Malformed WebDAV href")
        if (resolved.username.isNotEmpty() || resolved.password.isNotEmpty()) {
            throw IllegalArgumentException("Userinfo in WebDAV URL rejected")
        }
        val resolvedStr = resolved.toString()
        if (!isSameOrigin(cleanBase, resolvedStr)) {
            throw CrossOriginUrlException("Resolved WebDAV URL cross-origin mismatch: $resolvedStr")
        }

        val baseSegs = baseHttpUrl.pathSegments.filter { it.isNotEmpty() }
        val targetSegs = resolved.pathSegments.filter { it.isNotEmpty() }
        if (targetSegs.size < baseSegs.size || targetSegs.subList(0, baseSegs.size) != baseSegs) {
            throw CrossOriginUrlException("Resolved WebDAV URL escapes configured WebDAV root directory: $resolvedStr")
        }
        for (segment in resolved.pathSegments) {
            if (segment == "." || segment == ".." || segment.contains('/') || segment.contains('\\') || segment.contains('\u0000')) {
                throw IllegalArgumentException("Unsafe path segment in WebDAV href")
            }
        }
        return resolvedStr
    }

    private fun safePathParts(relativePath: String): List<String> {
        if (relativePath.contains("..") || relativePath.contains("\u0000") || relativePath.startsWith("/") || relativePath.contains(":")) {
            throw IllegalArgumentException("Unsafe path traversal detected in remote file: $relativePath")
        }
        return relativePath.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
    }

    private fun decodePathSegment(segment: String): String =
        try {
            java.net.URLDecoder.decode(segment.replace("+", "%2B"), "UTF-8")
        } catch (_: IllegalArgumentException) {
            segment
        }

    private fun findLocalDocument(rootDoc: DocumentFile, relativePath: String): DocumentFile? {
        val parts = safePathParts(relativePath)
        if (parts.isEmpty()) return null
        var current: DocumentFile = rootDoc
        for (i in 0 until parts.size - 1) {
            current = current.findFile(parts[i])?.takeIf { it.isDirectory } ?: return null
        }
        return current.findFile(parts.last())?.takeIf { it.isFile }
    }

    private enum class PlaceOutcome { WRITTEN, UNCHANGED, KEPT_LOCAL, KEPT_BOTH }

    
    private data class PlacedFile(val outcome: PlaceOutcome, val stateLastModified: Long, val stateFileSize: Long)

    private fun placeIncomingFile(
        context: Context,
        rootDoc: DocumentFile,
        relativePath: String,
        mimeType: String,
        bytes: ByteArray,
        knownState: SyncedFileState?,
        strategy: String
    ): PlacedFile {
        val existing = findLocalDocument(rootDoc, relativePath)
        if (existing == null) {
            val created = getOrCreateLocalDocument(rootDoc, relativePath, mimeType)
                ?: throw Exception("Could not create local file")
            writeLocalBytes(context, created, bytes)
            return PlacedFile(PlaceOutcome.WRITTEN, created.lastModified(), created.length())
        }

        val locallyModified = knownState == null ||
            existing.lastModified() != knownState.lastModified ||
            existing.length() != knownState.fileSize

        if (!locallyModified) {
            writeLocalBytes(context, existing, bytes)
            return PlacedFile(PlaceOutcome.WRITTEN, existing.lastModified(), existing.length())
        }

        if (hasSameContent(context, existing, bytes)) {
            return PlacedFile(PlaceOutcome.UNCHANGED, existing.lastModified(), existing.length())
        }

        return when (strategy) {
            SyncTask.STRATEGY_REMOTE_WINS -> {
                writeLocalBytes(context, existing, bytes)
                PlacedFile(PlaceOutcome.WRITTEN, existing.lastModified(), existing.length())
            }
            SyncTask.STRATEGY_LOCAL_WINS -> PlacedFile(PlaceOutcome.KEPT_LOCAL, -1L, -1L)
            else -> {
                val copy = getOrCreateLocalDocument(rootDoc, conflictCopyPath(relativePath), mimeType)
                    ?: throw Exception("Could not create conflict copy")
                writeLocalBytes(context, copy, bytes)
                PlacedFile(PlaceOutcome.KEPT_BOTH, -1L, -1L)
            }
        }
    }

    private fun writeLocalBytes(context: Context, doc: DocumentFile, bytes: ByteArray) {
        val os = context.contentResolver.openOutputStream(doc.uri, "wt")
            ?: throw Exception("Cannot open local output stream")
        os.use { it.write(bytes) }
    }

    private fun hasSameContent(context: Context, doc: DocumentFile, bytes: ByteArray): Boolean {
        if (doc.length() != bytes.size.toLong()) return false
        val input = context.contentResolver.openInputStream(doc.uri) ?: return false
        return input.use { stream ->
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var read: Int
            while (stream.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
            java.security.MessageDigest.isEqual(
                digest.digest(),
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            )
        }
    }

    private fun conflictCopyPath(relativePath: String): String {
        val dir = relativePath.substringBeforeLast('/', "")
        val name = relativePath.substringAfterLast('/')
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) name.substringAfterLast('.') else ""
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val newName = if (ext.isNotEmpty()) "${base}_conflict_$stamp.$ext" else "${base}_conflict_$stamp"
        return if (dir.isEmpty()) newName else "$dir/$newName"
    }

    private fun getOrCreateLocalDocument(rootDoc: DocumentFile, relativePath: String, mimeType: String): DocumentFile? {
        val parts = safePathParts(relativePath)
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

                clientFor(baseUrl, config.certificatePin).newCall(request).execute().close()
            }
        } catch (_: Exception) {}
    }

    private fun formatUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        val url = when {
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
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
            StagingFiles.secureDelete(tempFile)
            throw e
        }
    }

    companion object {
        
        const val MAX_TRANSFER_BYTES = 100 * 1024 * 1024L
        const val MAX_DOWNLOAD_BYTES = MAX_TRANSFER_BYTES
        const val MAX_PROPFIND_BYTES = 10 * 1024 * 1024L
        const val MAX_LISTING_ITEMS = 10_000

        private val DOCTYPE_OR_ENTITY_REGEX = Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE)

        private val DOT_SEGMENT_REGEX = Regex("(?i)(^|/)(\\.|%2e){1,2}(?=/|\\?|#|$)")

        
        val CERT_PIN_REGEX = CertPins.PIN_REGEX

        fun readBounded(input: java.io.InputStream, limit: Long, sizeHint: Long = -1L): ByteArray {
            var buf = ByteArray(if (sizeHint in 1..limit) sizeHint.toInt() else minOf(limit, 64L * 1024).toInt())
            var total = 0
            while (true) {
                if (total == buf.size) {
                    
                    val probe = input.read()
                    if (probe == -1) break
                    if (total.toLong() >= limit) throw IllegalArgumentException("Data exceeds the maximum permitted size ($limit bytes)")
                    buf = buf.copyOf(minOf(limit, buf.size.toLong() * 2).toInt())
                    buf[total++] = probe.toByte()
                    continue
                }
                val n = input.read(buf, total, buf.size - total)
                if (n == -1) break
                total += n
            }
            return if (buf.size == total) buf else buf.copyOf(total)
        }

        
        val CONFLICT_COPY_REGEX = Regex(""".*_conflict_\d{8}_\d{6}(\..*)?""")

        fun cleanupStagingDirectories(context: Context) {
            StagingFiles.cleanupStale(context.cacheDir)
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
