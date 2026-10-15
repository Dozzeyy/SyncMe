package org.vahak.syncme.sync

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.vahak.syncme.SyncMeApplication
import org.vahak.syncme.backup.LocalExporter
import org.vahak.syncme.crypto.CredentialMigrationManager
import org.vahak.syncme.crypto.SecureStorage
import org.vahak.syncme.crypto.StorageResult
import org.vahak.syncme.data.AppDatabase
import org.vahak.syncme.data.SyncLog
import org.vahak.syncme.data.SyncTask
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val database: AppDatabase,
    private val webDavClient: WebDavClient,
    private val localExporter: LocalExporter,
    private val secureStorage: SecureStorage,
    private val credentialMigrationManager: CredentialMigrationManager
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getLong("task_id", -1L)
        if (taskId == -1L) return Result.failure()

        val taskDao = database.syncTaskDao()
        val configDao = database.webDavConfigDao()
        val logDao = database.syncLogDao()
        val syncedFileStateDao = database.syncedFileStateDao()

        val task = taskDao.getById(taskId) ?: return Result.failure()
        val nowStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

        val passphraseRes = if (task.isEncrypted) secureStorage.getTaskPassphraseResult(task.id) else null
        val passphrase = when (passphraseRes) {
            null -> null
            is StorageResult.Success -> passphraseRes.data
            is StorageResult.MissingSecret -> {
                val errorMsg = "Encryption is enabled, but passphrase is missing."
                taskDao.update(task.copy(lastSyncStatus = SyncTask.STATUS_FAILED, lastSyncError = errorMsg))
                logDao.insert(SyncLog(taskId = task.id, timestamp = nowStr, status = SyncTask.STATUS_FAILED, message = errorMsg))
                sendNotification("SyncMe: ${task.name} Failed", errorMsg, isError = true)
                return Result.failure()
            }
            is StorageResult.StorageUnavailable -> {
                val errorMsg = "Encryption is enabled, but Android KeyStore is unavailable on this device."
                taskDao.update(task.copy(lastSyncStatus = SyncTask.STATUS_FAILED, lastSyncError = errorMsg))
                logDao.insert(SyncLog(taskId = task.id, timestamp = nowStr, status = SyncTask.STATUS_FAILED, message = errorMsg))
                sendNotification("SyncMe: ${task.name} Failed", errorMsg, isError = true)
                return Result.failure()
            }
        }

        taskDao.update(task.copy(lastSyncStatus = SyncTask.STATUS_IN_PROGRESS))

        try {
            val uri = Uri.parse(task.localUri)
            applicationContext.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {}

        val syncResult = if (task.destinationMode == SyncTask.DESTINATION_LOCAL) {
            localExporter.exportToLocalFolder(
                context = applicationContext,
                task = task,
                passphrase = passphrase
            )
        } else {
            val rawConfig = configDao.getById(task.webDavConfigId)
                ?: return Result.failure()
        
            val securePasswordRes = credentialMigrationManager.getWebDavPasswordResult(rawConfig.id)
            if (securePasswordRes !is StorageResult.Success) {
                val errorMsg = "WebDAV password missing or secure storage error. Please re-enter your password in WebDAV Settings."
                taskDao.update(task.copy(lastSyncStatus = SyncTask.STATUS_FAILED, lastSyncError = errorMsg))
                logDao.insert(SyncLog(taskId = task.id, timestamp = nowStr, status = SyncTask.STATUS_FAILED, message = errorMsg))
                sendNotification("SyncMe: ${task.name} Failed", errorMsg, isError = true)
                return Result.failure()
            }
            val config = rawConfig.copy(password = securePasswordRes.data)

            when {
                task.sourceType == SyncTask.SOURCE_CONTACTS ||
                task.sourceType == SyncTask.SOURCE_MESSAGES ||
                task.sourceType == SyncTask.SOURCE_CALL_LOGS -> {
                    webDavClient.syncDeviceDataBackup(
                        context = applicationContext,
                        config = config,
                        taskId = task.id,
                        sourceType = task.sourceType,
                        isEncrypted = task.isEncrypted,
                        passphrase = passphrase
                    )
                }
                task.syncDirection == SyncTask.DIRECTION_REMOTE_TO_LOCAL -> {
                    webDavClient.downloadRemoteToLocalFolder(
                        context = applicationContext,
                        config = config,
                        taskId = task.id,
                        folderUriStr = task.localUri,
                        isEncrypted = task.isEncrypted,
                        passphrase = passphrase,
                        syncedFileStateDao = syncedFileStateDao
                    )
                }
                task.isSingleFile -> {
                    webDavClient.syncSingleFile(
                        context = applicationContext,
                        config = config,
                        taskId = task.id,
                        fileUriStr = task.localUri,
                        isEncrypted = task.isEncrypted,
                        passphrase = passphrase,
                        syncedFileStateDao = syncedFileStateDao
                    )
                }
                else -> {
                    webDavClient.syncFolder(
                        context = applicationContext,
                        config = config,
                        taskId = task.id,
                        folderUriStr = task.localUri,
                        isEncrypted = task.isEncrypted,
                        passphrase = passphrase,
                        syncedFileStateDao = syncedFileStateDao
                    )
                }
            }
        }

        return if (syncResult.success) {
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

            val updatedTask = task.copy(
                lastSyncTime = nowStr,
                lastSyncStatus = SyncTask.STATUS_SUCCESS,
                lastSyncError = sanitizeError(syncResult.errorMessage)
            )
            taskDao.update(updatedTask)
            logDao.insert(
                SyncLog(
                    taskId = task.id,
                    timestamp = nowStr,
                    status = SyncTask.STATUS_SUCCESS,
                    message = successMsg,
                    bytesTransferred = syncResult.totalBytes
                )
            )

            sendNotification(
                title = "SyncMe: ${task.name} Successful",
                message = successMsg,
                isError = false
            )
            Result.success()
        } else {
            val errorMsg = sanitizeError(syncResult.errorMessage)
            val updatedTask = task.copy(
                lastSyncTime = nowStr,
                lastSyncStatus = SyncTask.STATUS_FAILED,
                lastSyncError = errorMsg
            )
            taskDao.update(updatedTask)
            logDao.insert(
                SyncLog(
                    taskId = task.id,
                    timestamp = nowStr,
                    status = SyncTask.STATUS_FAILED,
                    message = errorMsg
                )
            )

            sendNotification(
                title = "SyncMe: ${task.name} Failed",
                message = errorMsg,
                isError = true
            )
            Result.failure()
        }
    }

    private fun sanitizeError(raw: String?): String {
        if (raw.isNullOrBlank()) return "Sync operation failed."
        return raw.replace(Regex("http[s]?://[^@]+@"), "https://***@")
            .replace(Regex("(password|passphrase|token|auth|key)=[^&\\s]+", RegexOption.IGNORE_CASE), "$1=***")
            .take(250)
    }

    private fun sendNotification(title: String, message: String, isError: Boolean) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(applicationContext, SyncMeApplication.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(if (isError) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        manager.notify((System.currentTimeMillis() % 10000).toInt(), notification)
    }
}
