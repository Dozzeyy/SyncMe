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


package org.vahak.syncme.ui

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vahak.syncme.SyncMeApplication
import org.vahak.syncme.backup.LocalExporter
import org.vahak.syncme.crypto.CredentialMigrationManager
import org.vahak.syncme.crypto.SecureStorage
import org.vahak.syncme.crypto.StorageResult
import org.vahak.syncme.data.AppThemeMode
import org.vahak.syncme.data.SyncLog
import org.vahak.syncme.data.SyncLogDao
import org.vahak.syncme.data.SyncTask
import org.vahak.syncme.data.SyncTaskDao
import org.vahak.syncme.data.SyncedFileStateDao
import org.vahak.syncme.data.SecurityPreferences
import org.vahak.syncme.data.ThemePreferences
import org.vahak.syncme.data.WebDavConfig
import org.vahak.syncme.data.WebDavConfigDao
import org.vahak.syncme.sync.SyncRunLock
import org.vahak.syncme.sync.SyncScheduler
import org.vahak.syncme.sync.WebDavClient
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class SyncProgress(
    val isSyncing: Boolean = false,
    val currentFileIndex: Int = 0,
    val totalFiles: Int = 0,
    val currentFileName: String = "",
    val message: String = ""
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val themePreferences: ThemePreferences,
    private val securityPreferences: SecurityPreferences,
    private val webDavConfigDao: WebDavConfigDao,
    private val syncTaskDao: SyncTaskDao,
    private val syncLogDao: SyncLogDao,
    private val syncedFileStateDao: SyncedFileStateDao,
    private val secureStorage: SecureStorage,
    private val credentialMigrationManager: CredentialMigrationManager,
    private val webDavClient: WebDavClient,
    private val localExporter: LocalExporter,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    init {
        viewModelScope.launch(Dispatchers.IO) {
            credentialMigrationManager.migrateAllLegacyWebDavPasswords()
        }
    }

    val themeMode: StateFlow<AppThemeMode> = themePreferences.themeMode

    val screenProtection: StateFlow<Boolean> = securityPreferences.screenProtection

    fun setScreenProtection(enabled: Boolean) {
        securityPreferences.setScreenProtection(enabled)
    }

    suspend fun getWebDavPasswordResultOrMigrate(rawConfig: WebDavConfig): StorageResult<String> {
        return credentialMigrationManager.getWebDavPasswordResult(rawConfig.id)
    }

    val tasks: StateFlow<List<SyncTask>> = syncTaskDao.getAll().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val webDavConfigs: StateFlow<List<WebDavConfig>> = webDavConfigDao.getAll().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private val _activeSyncs = MutableStateFlow<Map<Long, SyncProgress>>(emptyMap())
    val activeSyncs: StateFlow<Map<Long, SyncProgress>> = _activeSyncs.asStateFlow()

    private val activeSyncJobs = ConcurrentHashMap<Long, Job>()

    private val _uiError = MutableStateFlow<String?>(null)
    val uiError: StateFlow<String?> = _uiError.asStateFlow()

    fun clearUiError() {
        _uiError.value = null
    }

    fun setThemeMode(mode: AppThemeMode) {
        themePreferences.setThemeMode(mode)
    }

    fun saveTask(task: SyncTask, passphrase: String? = null, onResult: ((Boolean, String?) -> Unit)? = null) {
        viewModelScope.launch {
            try {
                val isNewTask = (task.id == 0L)
                val originalTask = if (!isNewTask) {
                    syncTaskDao.getById(task.id)
                } else null

                val id = if (isNewTask) {
                    syncTaskDao.insert(task)
                } else {
                    syncTaskDao.update(task)
                    task.id
                }

                if (task.isEncrypted) {
                    if (!passphrase.isNullOrEmpty()) {
                        val saveRes = secureStorage.saveTaskPassphrase(id, passphrase)
                        if (saveRes !is StorageResult.Success) {
                            if (isNewTask) {
                                syncTaskDao.deleteById(id)
                            } else if (originalTask != null) {
                                syncTaskDao.update(originalTask)
                            }
                            val errorMsg = "KeyStore failure: Passphrase could not be stored. Task not scheduled."
                            _uiError.value = errorMsg
                            onResult?.invoke(false, errorMsg)
                            return@launch
                        }

                        val verifyRes = secureStorage.getTaskPassphraseResult(id)
                        if (verifyRes !is StorageResult.Success || verifyRes.data != passphrase) {
                            if (isNewTask) {
                                syncTaskDao.deleteById(id)
                            } else if (originalTask != null) {
                                syncTaskDao.update(originalTask)
                            }
                            val errorMsg = "Passphrase verification failed after storage. Task not scheduled."
                            _uiError.value = errorMsg
                            onResult?.invoke(false, errorMsg)
                            return@launch
                        }
                    } else {
                        
                        val verifyRes = secureStorage.getTaskPassphraseResult(id)
                        if (verifyRes !is StorageResult.Success) {
                            if (isNewTask) {
                                syncTaskDao.deleteById(id)
                            } else if (originalTask != null) {
                                syncTaskDao.update(originalTask)
                            }
                            val errorMsg = "Passphrase missing or KeyStore unavailable. Task not scheduled."
                            _uiError.value = errorMsg
                            onResult?.invoke(false, errorMsg)
                            return@launch
                        }
                    }
                } else {
                    
                    secureStorage.deleteTaskPassphrase(id)
                }

                val savedTask = task.copy(id = id)
                SyncScheduler.scheduleTaskWork(context, savedTask)
                onResult?.invoke(true, null)
            } catch (_: Exception) {
                val errorMsg = "Secure storage error."
                _uiError.value = errorMsg
                onResult?.invoke(false, errorMsg)
            }
        }
    }

    fun getTaskPassphraseResult(taskId: Long): StorageResult<String> {
        return secureStorage.getTaskPassphraseResult(taskId)
    }

    fun deleteTask(task: SyncTask) {
        viewModelScope.launch {
            activeSyncJobs[task.id]?.cancel()
            activeSyncJobs.remove(task.id)
            SyncScheduler.cancelTaskWork(context, task.id)
            secureStorage.deleteTaskPassphrase(task.id)
            syncedFileStateDao.deleteForTask(task.id)
            syncTaskDao.delete(task)
        }
    }

    fun stopSync(task: SyncTask) {
        viewModelScope.launch {
            activeSyncJobs[task.id]?.cancel()
            activeSyncJobs.remove(task.id)
            SyncScheduler.cancelTaskWork(context, task.id)
            _activeSyncs.value = _activeSyncs.value - task.id

            val nowStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            val errorMsg = "Sync stopped by user"
            syncTaskDao.update(task.copy(lastSyncStatus = SyncTask.STATUS_FAILED, lastSyncError = errorMsg))
            syncLogDao.insert(
                SyncLog(
                    taskId = task.id,
                    timestamp = nowStr,
                    status = SyncTask.STATUS_FAILED,
                    message = errorMsg
                )
            )
        }
    }

    fun triggerSyncNow(task: SyncTask) {
        activeSyncJobs[task.id]?.cancel()
        val job = viewModelScope.launch(Dispatchers.IO) {
            if (SyncRunLock.isRunning(task.id)) {
                _activeSyncs.value = _activeSyncs.value + (task.id to SyncProgress(
                    isSyncing = true,
                    message = "Waiting for another run of this task to finish..."
                ))
            }
            
            SyncRunLock.runExclusive(task.id) {
            try {
                val passphraseRes = if (task.isEncrypted) secureStorage.getTaskPassphraseResult(task.id) else null
                val passphrase = when (passphraseRes) {
                    null -> null
                    is StorageResult.Success -> passphraseRes.data
                    is StorageResult.MissingSecret, is StorageResult.StorageUnavailable -> {
                        val errorMsg = "Encryption is enabled, but the passphrase is unavailable."
                        val nowStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                        syncTaskDao.update(task.copy(lastSyncStatus = SyncTask.STATUS_FAILED, lastSyncError = errorMsg))
                        syncLogDao.insert(SyncLog(taskId = task.id, timestamp = nowStr, status = SyncTask.STATUS_FAILED, message = errorMsg))
                        sendNotification(task.id, "SyncMe: ${task.name} Failed", errorMsg, isError = true)
                        return@launch
                    }
                }

            _activeSyncs.value = _activeSyncs.value + (task.id to SyncProgress(
                isSyncing = true,
                message = "Starting..."
            ))

            syncTaskDao.update(task.copy(lastSyncStatus = SyncTask.STATUS_IN_PROGRESS))

            try {
                val uri = Uri.parse(task.localUri)
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}

            val syncResult = if (task.destinationMode == SyncTask.DESTINATION_LOCAL) {
                localExporter.exportToLocalFolder(
                    context = context,
                    task = task,
                    passphrase = passphrase,
                    onProgress = { msg ->
                        _activeSyncs.value = _activeSyncs.value + (task.id to SyncProgress(
                            isSyncing = true,
                            currentFileIndex = 1,
                            totalFiles = 1,
                            currentFileName = msg,
                            message = msg
                        ))
                    }
                )
            } else {
                val rawConfig = task.webDavConfigId?.let { webDavConfigDao.getById(it) }
                if (rawConfig == null) {
                    val errorMsg = "WebDAV configuration not found"
                    syncTaskDao.update(task.copy(lastSyncStatus = SyncTask.STATUS_FAILED, lastSyncError = errorMsg))
                    _activeSyncs.value = _activeSyncs.value - task.id
                    return@launch
                }
                val securePasswordRes = getWebDavPasswordResultOrMigrate(rawConfig)
                val securePassword = when (securePasswordRes) {
                    is StorageResult.Success -> securePasswordRes.data
                    is StorageResult.MissingSecret -> {
                        val errorMsg = "WebDAV password missing. Please re-enter password in WebDAV Settings."
                        syncTaskDao.update(task.copy(lastSyncStatus = SyncTask.STATUS_FAILED, lastSyncError = errorMsg))
                        _activeSyncs.value = _activeSyncs.value - task.id
                        sendNotification(task.id, "SyncMe: ${task.name} Failed", errorMsg, isError = true)
                        return@launch
                    }
                    is StorageResult.StorageUnavailable -> {
                        val errorMsg = "KeyStore is unavailable on this device."
                        syncTaskDao.update(task.copy(lastSyncStatus = SyncTask.STATUS_FAILED, lastSyncError = errorMsg))
                        _activeSyncs.value = _activeSyncs.value - task.id
                        sendNotification(task.id, "SyncMe: ${task.name} Failed", errorMsg, isError = true)
                        return@launch
                    }
                }
                val config = rawConfig.copy(password = securePassword)

                when {
                    task.syncDirection == SyncTask.DIRECTION_REMOTE_TO_LOCAL -> {
                        webDavClient.downloadRemoteToLocalFolder(
                            context = context,
                            config = config,
                            taskId = task.id,
                            folderUriStr = task.localUri,
                            isEncrypted = task.isEncrypted,
                            passphrase = passphrase,
                            syncedFileStateDao = syncedFileStateDao,
                            onProgress = { current, total, fileName ->
                                _activeSyncs.value = _activeSyncs.value + (task.id to SyncProgress(
                                    isSyncing = true,
                                    currentFileIndex = current,
                                    totalFiles = total,
                                    currentFileName = fileName,
                                    message = "Downloading ($current/$total): $fileName"
                                ))
                            }
                        )
                    }
                    task.sourceType == SyncTask.SOURCE_CONTACTS ||
                    task.sourceType == SyncTask.SOURCE_MESSAGES ||
                    task.sourceType == SyncTask.SOURCE_CALL_LOGS -> {
                        webDavClient.syncDeviceDataBackup(
                            context = context,
                            config = config,
                            taskId = task.id,
                            sourceType = task.sourceType,
                            isEncrypted = task.isEncrypted,
                            passphrase = passphrase,
                            onProgress = { msg ->
                                _activeSyncs.value = _activeSyncs.value + (task.id to SyncProgress(
                                    isSyncing = true,
                                    currentFileIndex = 1,
                                    totalFiles = 1,
                                    currentFileName = msg,
                                    message = msg
                                ))
                            }
                        )
                    }
                    task.isSingleFile -> {
                        webDavClient.syncSingleFile(
                            context = context,
                            config = config,
                            taskId = task.id,
                            fileUriStr = task.localUri,
                            isEncrypted = task.isEncrypted,
                            passphrase = passphrase,
                            syncedFileStateDao = syncedFileStateDao,
                            onProgress = { msg ->
                                _activeSyncs.value = _activeSyncs.value + (task.id to SyncProgress(
                                    isSyncing = true,
                                    currentFileIndex = 1,
                                    totalFiles = 1,
                                    currentFileName = msg,
                                    message = msg
                                ))
                            }
                        )
                    }
                    task.syncDirection == SyncTask.DIRECTION_BIDIRECTIONAL -> {
                        webDavClient.bidirectionalSyncWebDav(
                            context = context,
                            config = config,
                            task = task,
                            passphrase = passphrase,
                            syncedFileStateDao = syncedFileStateDao,
                            onProgress = { current, total, fileName ->
                                _activeSyncs.value = _activeSyncs.value + (task.id to SyncProgress(
                                    isSyncing = true,
                                    currentFileIndex = current,
                                    totalFiles = total,
                                    currentFileName = fileName,
                                    message = "Syncing ($current/$total): $fileName"
                                ))
                            }
                        )
                    }
                    else -> {
                        webDavClient.syncFolder(
                            context = context,
                            config = config,
                            taskId = task.id,
                            folderUriStr = task.localUri,
                            isEncrypted = task.isEncrypted,
                            passphrase = passphrase,
                            syncedFileStateDao = syncedFileStateDao,
                            onProgress = { current, total, fileName ->
                                _activeSyncs.value = _activeSyncs.value + (task.id to SyncProgress(
                                    isSyncing = true,
                                    currentFileIndex = current,
                                    totalFiles = total,
                                    currentFileName = fileName,
                                    message = "Checking ($current/$total): $fileName"
                                ))
                            }
                        )
                    }
                }
            }

            val nowStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

            if (syncResult.success) {
                val sizeKb = String.format("%.2f", syncResult.totalBytes / 1024.0)
                val actionLabel = when {
                    task.destinationMode == SyncTask.DESTINATION_LOCAL -> "Exported"
                    task.syncDirection == SyncTask.DIRECTION_REMOTE_TO_LOCAL -> "Downloaded"
                    else -> "Uploaded"
                }
                val successMsg = if (syncResult.filesSkipped > 0) {
                    "$actionLabel ${syncResult.filesTransferred} file(s), ${syncResult.filesSkipped} unmodified skipped (${sizeKb} KB)"
                } else {
                    "$actionLabel ${syncResult.filesTransferred} file(s) (${sizeKb} KB)"
                }

                syncTaskDao.update(
                    task.copy(
                        lastSyncTime = nowStr,
                        lastSyncStatus = SyncTask.STATUS_SUCCESS,
                        lastSyncError = syncResult.errorMessage?.takeIf { it.isNotBlank() }?.let { sanitizeError(it) }
                    )
                )
                syncLogDao.insert(
                    SyncLog(
                        taskId = task.id,
                        timestamp = nowStr,
                        status = SyncTask.STATUS_SUCCESS,
                        message = successMsg,
                        bytesTransferred = syncResult.totalBytes
                    )
                )
                sendNotification(task.id, "SyncMe: ${task.name} Successful", successMsg, isError = false)
            } else {
                val errorMsg = sanitizeError(syncResult.errorMessage ?: "Sync failed")
                syncTaskDao.update(
                    task.copy(
                        lastSyncTime = nowStr,
                        lastSyncStatus = SyncTask.STATUS_FAILED,
                        lastSyncError = errorMsg
                    )
                )
                syncLogDao.insert(
                    SyncLog(
                        taskId = task.id,
                        timestamp = nowStr,
                        status = SyncTask.STATUS_FAILED,
                        message = errorMsg
                    )
                )
                sendNotification(task.id, "SyncMe: ${task.name} Failed", errorMsg, isError = true)
            }
        } finally {
            activeSyncJobs.remove(task.id)
            _activeSyncs.value = _activeSyncs.value - task.id
        }
            }
    }
    activeSyncJobs[task.id] = job
}

    private fun sanitizeError(raw: String?): String = org.vahak.syncme.sync.ErrorSanitizer.sanitize(raw)

    private fun sendNotification(taskId: Long, title: String, message: String, isError: Boolean) {
        org.vahak.syncme.sync.SyncNotifier.notify(context, taskId, title, message, isError)
    }

    fun getLogsForTask(taskId: Long): Flow<List<SyncLog>> {
        return syncLogDao.getLogsForTask(taskId)
    }

    fun getWebDavPasswordResult(configId: Long): StorageResult<String> {
        return secureStorage.getWebDavPasswordResult(configId)
    }

    fun saveWebDavConfig(
        config: WebDavConfig,
        lifecycleAction: org.vahak.syncme.data.PasswordLifecycleAction = org.vahak.syncme.data.PasswordLifecycleAction.CHANGE_PASSWORD,
        onResult: ((Boolean, String?) -> Unit)? = null
    ) {
        viewModelScope.launch {
            if (!secureStorage.isStorageAvailable()) {
                val errorMsg = "Android KeyStore / SecureStorage is unavailable on this device."
                _uiError.value = errorMsg
                onResult?.invoke(false, errorMsg)
                return@launch
            }

            try {
                if (config.id == 0L) {
                    val tempConfig = config.copy(password = "")
                    val newId = webDavConfigDao.insert(tempConfig)

                    if (lifecycleAction == org.vahak.syncme.data.PasswordLifecycleAction.CHANGE_PASSWORD && config.password.isNotBlank()) {
                        val saveRes = secureStorage.saveWebDavPassword(newId, config.password)
                        if (saveRes !is StorageResult.Success) {
                            webDavConfigDao.deleteById(newId)
                            val errorMsg = "KeyStore failure: Password could not be stored. Config reverted."
                            _uiError.value = errorMsg
                            onResult?.invoke(false, errorMsg)
                            return@launch
                        }

                        val verifyRes = secureStorage.getWebDavPasswordResult(newId)
                        if (verifyRes !is StorageResult.Success || verifyRes.data != config.password) {
                            webDavConfigDao.deleteById(newId)
                            secureStorage.deleteWebDavPassword(newId)
                            val errorMsg = "KeyStore verification failed. Config reverted."
                            _uiError.value = errorMsg
                            onResult?.invoke(false, errorMsg)
                            return@launch
                        }
                    }
                } else {
                    when (lifecycleAction) {
                        org.vahak.syncme.data.PasswordLifecycleAction.KEEP_EXISTING_PASSWORD -> {
                            val configToSave = config.copy(password = "")
                            webDavConfigDao.update(configToSave)
                        }

                        org.vahak.syncme.data.PasswordLifecycleAction.CLEAR_PASSWORD -> {
                            val oldSecretRes = secureStorage.getWebDavPasswordResult(config.id)
                            when (oldSecretRes) {
                                is StorageResult.StorageUnavailable -> {
                                    val errorMsg = "KeyStore failure: Cannot clear password because storage is unavailable."
                                    _uiError.value = errorMsg
                                    onResult?.invoke(false, errorMsg)
                                    return@launch
                                }
                                is StorageResult.MissingSecret -> {
                                    val configToSave = config.copy(password = "")
                                    webDavConfigDao.update(configToSave)
                                }
                                is StorageResult.Success -> {
                                    val oldSecret = oldSecretRes.data
                                    val deleteRes = secureStorage.deleteWebDavPassword(config.id)
                                    if (deleteRes !is StorageResult.Success) {
                                        val errorMsg = "KeyStore failure: Could not delete credential from secure storage."
                                        _uiError.value = errorMsg
                                        onResult?.invoke(false, errorMsg)
                                        return@launch
                                    }

                                    val configToSave = config.copy(password = "")
                                    try {
                                        webDavConfigDao.update(configToSave)
                                    } catch (dbEx: Exception) {
                                        val restoreRes = secureStorage.saveWebDavPassword(config.id, oldSecret)
                                        val verifyRestore = secureStorage.getWebDavPasswordResult(config.id)

                                        val errorMsg = if (restoreRes is StorageResult.Success && verifyRestore is StorageResult.Success && verifyRestore.data == oldSecret) {
                                            "Database update failed. Clear action was safely rolled back."
                                        } else {
                                            "CRITICAL: Security state inconsistency! Clear action DB update failed and credential restoration failed."
                                        }
                                        _uiError.value = errorMsg
                                        onResult?.invoke(false, errorMsg)
                                        return@launch
                                    }
                                }
                            }
                        }

                        org.vahak.syncme.data.PasswordLifecycleAction.CHANGE_PASSWORD -> {
                            if (config.password.isBlank()) {
                                val errorMsg = "Password cannot be empty when requesting CHANGE_PASSWORD."
                                _uiError.value = errorMsg
                                onResult?.invoke(false, errorMsg)
                                return@launch
                            }

                            val oldSecretRes = secureStorage.getWebDavPasswordResult(config.id)
                            if (oldSecretRes is StorageResult.StorageUnavailable) {
                                val errorMsg = "KeyStore failure: Storage is unavailable on this device."
                                _uiError.value = errorMsg
                                onResult?.invoke(false, errorMsg)
                                return@launch
                            }

                            suspend fun rollbackToOriginalState(): Boolean {
                                return when (oldSecretRes) {
                                    is StorageResult.Success -> {
                                        val oldPassword = oldSecretRes.data
                                        val restoreRes = secureStorage.saveWebDavPassword(config.id, oldPassword)
                                        val verifyRestore = secureStorage.getWebDavPasswordResult(config.id)
                                        restoreRes is StorageResult.Success && verifyRestore is StorageResult.Success && verifyRestore.data == oldPassword
                                    }
                                    is StorageResult.MissingSecret -> {
                                        val deleteRes = secureStorage.deleteWebDavPassword(config.id)
                                        val verifyDelete = secureStorage.getWebDavPasswordResult(config.id)
                                        deleteRes is StorageResult.Success && verifyDelete is StorageResult.MissingSecret
                                    }
                                    is StorageResult.StorageUnavailable -> false
                                }
                            }

                            val saveRes = secureStorage.saveWebDavPassword(config.id, config.password)
                            if (saveRes !is StorageResult.Success) {
                                val errorMsg = "KeyStore failure: Password update failed."
                                _uiError.value = errorMsg
                                onResult?.invoke(false, errorMsg)
                                return@launch
                            }

                            val verifyRes = secureStorage.getWebDavPasswordResult(config.id)
                            if (verifyRes !is StorageResult.Success || verifyRes.data != config.password) {
                                val rollbackOk = rollbackToOriginalState()
                                val errorMsg = if (rollbackOk) {
                                    "KeyStore verification failed. Password update reverted."
                                } else {
                                    "CRITICAL: KeyStore verification failed and rollback verification failed."
                                }
                                _uiError.value = errorMsg
                                onResult?.invoke(false, errorMsg)
                                return@launch
                            }

                            val configToSave = config.copy(password = "")
                            try {
                                webDavConfigDao.update(configToSave)
                            } catch (dbEx: Exception) {
                                val rollbackOk = rollbackToOriginalState()
                                val errorMsg = if (rollbackOk) {
                                    "Database update failed. Password change was safely rolled back."
                                } else {
                                    "CRITICAL: Security state inconsistency! Database update failed and credential rollback failed."
                                }
                                _uiError.value = errorMsg
                                onResult?.invoke(false, errorMsg)
                                return@launch
                            }
                        }
                    }
                }
                onResult?.invoke(true, null)
            } catch (_: Exception) {
                val errorMsg = "Failed to save WebDAV configuration."
                _uiError.value = errorMsg
                onResult?.invoke(false, errorMsg)
            }
        }
    }

    fun deleteWebDavConfig(config: WebDavConfig) {
        viewModelScope.launch {
            val associatedTasks = syncTaskDao.getByWebDavConfigId(config.id)
            for (t in associatedTasks) {
                SyncScheduler.cancelTaskWork(context, t.id)
                secureStorage.deleteTaskPassphrase(t.id)
                syncedFileStateDao.deleteForTask(t.id)
            }
            secureStorage.deleteWebDavPassword(config.id)
            webDavConfigDao.delete(config)
        }
    }

    fun testWebDavConnection(config: WebDavConfig, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val securePasswordRes = getWebDavPasswordResultOrMigrate(config)
            val securePassword = when (securePasswordRes) {
                is StorageResult.Success -> securePasswordRes.data
                is StorageResult.MissingSecret -> {
                    onResult(false, "WebDAV password missing. Please re-enter your password in WebDAV Settings.")
                    return@launch
                }
                is StorageResult.StorageUnavailable -> {
                    onResult(false, "KeyStore unavailable: Secure storage is unavailable on this device.")
                    return@launch
                }
            }
            val activeConfig = config.copy(password = securePassword)
            val result = withContext(Dispatchers.IO) {
                webDavClient.testConnection(activeConfig)
            }
            result.fold(
                onSuccess = { msg -> onResult(true, msg) },
                onFailure = { _ -> onResult(false, "Connection failed.") }
            )
        }
    }

    fun exportConfig(directoryUri: Uri, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    directoryUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}

            val destFolderDoc = DocumentFile.fromTreeUri(context, directoryUri)
            if (destFolderDoc == null || !destFolderDoc.exists() || !destFolderDoc.isDirectory) {
                withContext(Dispatchers.Main) {
                    onResult(false, "Selected directory is invalid or not accessible.")
                }
                return@launch
            }

            try {
                val allWebDav = webDavConfigDao.getAllList()
                val allTasks = syncTaskDao.getAllList()

                val webDavArray = JSONArray()
                for (config in allWebDav) {
                    val passwordRes = getWebDavPasswordResultOrMigrate(config)
                    val pwd = if (passwordRes is StorageResult.Success) passwordRes.data else config.password
                    val configObj = JSONObject().apply {
                        put("id", config.id)
                        put("name", config.name)
                        put("url", config.url)
                        put("username", config.username)
                        put("password", pwd)
                        put("certificatePin", config.certificatePin)
                    }
                    webDavArray.put(configObj)
                }

                val tasksArray = JSONArray()
                for (task in allTasks) {
                    val passphraseRes = if (task.isEncrypted) getTaskPassphraseResult(task.id) else null
                    val passphrase = if (passphraseRes is StorageResult.Success) passphraseRes.data else null

                    val taskObj = JSONObject().apply {
                        put("id", task.id)
                        put("name", task.name)
                        put("webDavConfigId", task.webDavConfigId ?: JSONObject.NULL)
                        put("localUri", task.localUri)
                        put("destinationLocalUri", task.destinationLocalUri)
                        put("isSingleFile", task.isSingleFile)
                        put("isEncrypted", task.isEncrypted)
                        put("syncDirection", task.syncDirection)
                        put("sourceType", task.sourceType)
                        put("destinationMode", task.destinationMode)
                        put("isMirrorSync", task.isMirrorSync)
                        put("conflictStrategy", task.conflictStrategy)
                        put("filterCriteria", task.filterCriteria)
                        put("excludedExtensions", task.excludedExtensions)
                        put("maxFileSizeMb", task.maxFileSizeMb)
                        put("frequencyType", task.frequencyType)
                        put("customValue", task.customValue)
                        put("customUnit", task.customUnit)
                        put("lastSyncTime", task.lastSyncTime ?: JSONObject.NULL)
                        put("lastSyncStatus", task.lastSyncStatus ?: JSONObject.NULL)
                        put("lastSyncError", task.lastSyncError ?: JSONObject.NULL)
                        put("isEnabled", task.isEnabled)
                        if (passphrase != null) {
                            put("passphrase", passphrase)
                        }
                    }
                    tasksArray.put(taskObj)
                }

                val rootJson = JSONObject().apply {
                    put("version", 1)
                    put("webDavConfigs", webDavArray)
                    put("tasks", tasksArray)
                }

                val fileName = "syncme_config_${System.currentTimeMillis()}.json"
                val existing = destFolderDoc.findFile(fileName)
                val jsonFileDoc = existing ?: destFolderDoc.createFile("application/json", fileName)
                    ?: throw Exception("Could not create JSON file in target directory")

                context.contentResolver.openOutputStream(jsonFileDoc.uri)?.use { os ->
                    os.write(rootJson.toString(2).toByteArray(Charsets.UTF_8))
                } ?: throw Exception("Could not write to JSON file output stream")

                withContext(Dispatchers.Main) {
                    onResult(true, "Config exported successfully as $fileName")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(false, "Export failed. Check that the destination folder is writable.")
                }
            }
        }
    }

    fun importConfig(fileUri: Uri, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val jsonText = context.contentResolver.openInputStream(fileUri)?.use { isStream ->
                    isStream.bufferedReader().use { it.readText() }
                } ?: throw Exception("Unable to open input file")

                val rootJson = JSONObject(jsonText)
                val webDavArray = rootJson.optJSONArray("webDavConfigs") ?: JSONArray()
                val tasksArray = rootJson.optJSONArray("tasks") ?: JSONArray()

                val oldToNewWebDavIdMap = mutableMapOf<Long, Long>()
                val existingWebDavs = webDavConfigDao.getAllList()

                for (i in 0 until webDavArray.length()) {
                    val obj = webDavArray.getJSONObject(i)
                    val oldId = obj.optLong("id", -1L)
                    val name = obj.optString("name", "Imported WebDAV")
                    val url = obj.optString("url", "")
                    val username = obj.optString("username", "")
                    val password = obj.optString("password", "")
                    
                    val certificatePin = org.vahak.syncme.sync.CertPins.normalize(
                        obj.optString("certificatePin", "").takeIf { org.vahak.syncme.sync.CertPins.isValid(it) }
                    )

                    val existing = existingWebDavs.find { it.url == url && it.username == username }
                    val targetId = if (existing != null) {
                        if (password.isNotBlank()) {
                            secureStorage.saveWebDavPassword(existing.id, password)
                        }
                        existing.id
                    } else {
                        val newConfig = WebDavConfig(
                            name = name,
                            url = url,
                            username = username,
                            password = "",
                            certificatePin = certificatePin
                        )
                        val insertedId = webDavConfigDao.insert(newConfig)
                        if (password.isNotBlank()) {
                            secureStorage.saveWebDavPassword(insertedId, password)
                        }
                        insertedId
                    }
                    if (oldId != -1L) {
                        oldToNewWebDavIdMap[oldId] = targetId
                    }
                }

                var importedTasksCount = 0
                for (i in 0 until tasksArray.length()) {
                    val obj = tasksArray.getJSONObject(i)
                    val name = obj.optString("name", "Imported Task")
                    val oldWebDavId = if (obj.has("webDavConfigId") && !obj.isNull("webDavConfigId")) obj.optLong("webDavConfigId", -1L) else -1L
                    val destinationMode = obj.optString("destinationMode", SyncTask.DESTINATION_REMOTE)
                    val targetWebDavId = if (destinationMode == SyncTask.DESTINATION_LOCAL || oldWebDavId == -1L) null else (oldToNewWebDavIdMap[oldWebDavId] ?: oldWebDavId)

                    val localUri = obj.optString("localUri", "")
                    val destinationLocalUri = obj.optString("destinationLocalUri", "")
                    val isSingleFile = obj.optBoolean("isSingleFile", false)
                    val isEncrypted = obj.optBoolean("isEncrypted", false)
                    val syncDirection = obj.optString("syncDirection", SyncTask.DIRECTION_LOCAL_TO_REMOTE)
                    val sourceType = obj.optString("sourceType", SyncTask.SOURCE_FILES)
                    val isMirrorSync = obj.optBoolean("isMirrorSync", false)
                    val conflictStrategy = obj.optString("conflictStrategy", SyncTask.STRATEGY_KEEP_BOTH)
                    val filterCriteria = obj.optString("filterCriteria", SyncTask.FILTER_NONE)
                    val excludedExtensions = obj.optString("excludedExtensions", "")
                    val maxFileSizeMb = obj.optLong("maxFileSizeMb", 0L)
                    val frequencyType = obj.optString("frequencyType", SyncTask.FREQ_DAILY)
                    val customValue = obj.optInt("customValue", 1)
                    val customUnit = obj.optString("customUnit", SyncTask.UNIT_DAYS)
                    val isEnabled = obj.optBoolean("isEnabled", true)
                    val passphrase = if (obj.has("passphrase") && !obj.isNull("passphrase")) obj.optString("passphrase") else null

                    val task = SyncTask(
                        name = name,
                        webDavConfigId = targetWebDavId,
                        localUri = localUri,
                        destinationLocalUri = destinationLocalUri,
                        isSingleFile = isSingleFile,
                        isEncrypted = isEncrypted,
                        syncDirection = syncDirection,
                        sourceType = sourceType,
                        destinationMode = destinationMode,
                        isMirrorSync = isMirrorSync,
                        conflictStrategy = conflictStrategy,
                        filterCriteria = filterCriteria,
                        excludedExtensions = excludedExtensions,
                        maxFileSizeMb = maxFileSizeMb,
                        frequencyType = frequencyType,
                        customValue = customValue,
                        customUnit = customUnit,
                        lastSyncTime = null,
                        lastSyncStatus = SyncTask.STATUS_NEVER,
                        lastSyncError = null,
                        isEnabled = isEnabled
                    )

                    val newTaskId = syncTaskDao.insert(task)
                    if (isEncrypted && !passphrase.isNullOrBlank()) {
                        secureStorage.saveTaskPassphrase(newTaskId, passphrase)
                    }
                    val finalTask = task.copy(id = newTaskId)
                    SyncScheduler.scheduleTaskWork(context, finalTask)
                    importedTasksCount++
                }

                withContext(Dispatchers.Main) {
                    onResult(true, "Successfully imported ${webDavArray.length()} WebDAV config(s) and $importedTasksCount task(s).")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(false, "Import failed. Check that the file is a valid SyncMe export.")
                }
            }
        }
    }
}
