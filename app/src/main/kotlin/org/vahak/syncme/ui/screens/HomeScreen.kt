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


package org.vahak.syncme.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.vahak.syncme.data.SyncTask
import org.vahak.syncme.data.WebDavConfig
import org.vahak.syncme.ui.MainViewModel
import org.vahak.syncme.ui.SyncProgress
import org.vahak.syncme.ui.dialogs.TaskDialog

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onNavigateToWebDav: () -> Unit
) {
    val tasks by viewModel.tasks.collectAsState()
    val webDavConfigs by viewModel.webDavConfigs.collectAsState()
    val activeSyncs by viewModel.activeSyncs.collectAsState()

    var showTaskDialog by remember { mutableStateOf(false) }
    var taskToEdit by remember { mutableStateOf<SyncTask?>(null) }
    var initialPassphrase by remember { mutableStateOf<String?>(null) }
    var taskToDelete by remember { mutableStateOf<SyncTask?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        if (tasks.isEmpty()) {
            EmptyTaskState(
                webDavConfigs = webDavConfigs,
                onAddTask = {
                    taskToEdit = null
                    initialPassphrase = null
                    showTaskDialog = true
                },
                onNavigateToWebDav = onNavigateToWebDav
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(tasks, key = { it.id }) { task ->
                    val config = webDavConfigs.find { it.id == task.webDavConfigId }
                    val activeProgress = activeSyncs[task.id]

                    TaskCard(
                        task = task,
                        webDavConfigName = config?.name ?: if (task.destinationMode == SyncTask.DESTINATION_LOCAL) "Local Folder" else "Unknown Server",
                        activeProgress = activeProgress,
                        onSyncNow = { viewModel.triggerSyncNow(task) },
                        onEdit = {
                            taskToEdit = task
                            val passphraseRes = viewModel.getTaskPassphraseResult(task.id)
                            initialPassphrase = if (passphraseRes is org.vahak.syncme.crypto.StorageResult.Success) passphraseRes.data else ""
                            showTaskDialog = true
                        },
                        onDelete = {
                            taskToDelete = task
                        }
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = {
                taskToEdit = null
                initialPassphrase = null
                showTaskDialog = true
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
        ) {
            Icon(imageVector = Icons.Default.Add, contentDescription = "Add Sync Task")
        }
    }

    if (showTaskDialog) {
        TaskDialog(
            taskToEdit = taskToEdit,
            initialPassphrase = initialPassphrase,
            webDavConfigs = webDavConfigs,
            onDismiss = { showTaskDialog = false },
            onSave = { task, passphrase ->
                viewModel.saveTask(task, passphrase)
                showTaskDialog = false
            }
        )
    }

    taskToDelete?.let { task ->
        AlertDialog(
            onDismissRequest = { taskToDelete = null },
            title = { Text("Delete Sync Task") },
            text = { Text("Are you sure you want to delete '${task.name}'? Scheduled background syncs for this task will be stopped.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteTask(task)
                        taskToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { taskToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun TaskCard(
    task: SyncTask,
    webDavConfigName: String,
    activeProgress: SyncProgress?,
    onSyncNow: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var showErrorDetails by remember { mutableStateOf(false) }
    val isSyncing = (activeProgress?.isSyncing == true) || (task.lastSyncStatus == SyncTask.STATUS_IN_PROGRESS)

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            
            Text(
                text = task.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(6.dp))

            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
            
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val (sourceIcon, sourceLabel) = when (task.sourceType) {
                            SyncTask.SOURCE_CONTACTS -> Pair(Icons.Default.Person, "Contacts")
                            SyncTask.SOURCE_MESSAGES -> Pair(Icons.AutoMirrored.Filled.Message, "Messages")
                            SyncTask.SOURCE_CALL_LOGS -> Pair(Icons.Default.Call, "Call Logs")
                            else -> Pair(if (task.isSingleFile) Icons.AutoMirrored.Filled.InsertDriveFile else Icons.Default.Folder, if (task.isSingleFile) "Single File" else "Folder")
                        }

                        Icon(
                            imageVector = sourceIcon,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = sourceLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }

                
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val (destIcon, destLabel) = if (task.destinationMode == SyncTask.DESTINATION_LOCAL) {
                            Pair(Icons.Default.Save, "Local Folder")
                        } else if (task.syncDirection == SyncTask.DIRECTION_REMOTE_TO_LOCAL) {
                            Pair(Icons.Default.CloudDownload, "Remote -> Local")
                        } else {
                            Pair(Icons.Default.CloudUpload, "Cloud WebDAV")
                        }

                        Icon(
                            imageVector = destIcon,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = destLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (task.isEncrypted) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "E2EE",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (task.destinationMode == SyncTask.DESTINATION_LOCAL) "Export Target: Local Device Folder" else "Target Server: $webDavConfigName",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Frequency: ${task.getFrequencyDescription()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

        
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Last Sync: ",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = task.lastSyncTime ?: "Never",
                    style = MaterialTheme.typography.bodySmall
                )

                Spacer(modifier = Modifier.weight(1f))

                StatusChip(status = if (isSyncing) SyncTask.STATUS_IN_PROGRESS else (task.lastSyncStatus ?: SyncTask.STATUS_NEVER))
            }

            
            if (isSyncing) {
                Spacer(modifier = Modifier.height(12.dp))
                Column(modifier = Modifier.fillMaxWidth()) {
                    val progressRatio = if (activeProgress != null && activeProgress.totalFiles > 0) {
                        activeProgress.currentFileIndex.toFloat() / activeProgress.totalFiles.toFloat()
                    } else null

                    if (progressRatio != null) {
                        LinearProgressIndicator(
                            progress = { progressRatio },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    val progressMsg = activeProgress?.message?.ifEmpty { "Synchronizing data..." } ?: "Synchronizing data..."
                    Text(
                        text = progressMsg,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            
            if (!isSyncing && task.lastSyncStatus == SyncTask.STATUS_FAILED && !task.lastSyncError.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = { showErrorDetails = !showErrorDetails },
                    modifier = Modifier.padding(0.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (showErrorDetails) "Hide Error Log" else "View Detailed Error Log",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                AnimatedVisibility(visible = showErrorDetails) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    ) {
                        Text(
                            text = task.lastSyncError ?: "Unknown error",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

        
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onSyncNow,
                    enabled = !isSyncing,
                    contentPadding = ButtonDefaults.ContentPadding
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isSyncing) "Syncing..." else "Sync Now")
                }

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(onClick = onEdit, enabled = !isSyncing) {
                    Icon(imageVector = Icons.Default.Edit, contentDescription = "Edit Task")
                }

                IconButton(onClick = onDelete, enabled = !isSyncing) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete Task",
                        tint = if (isSyncing) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
fun StatusChip(status: String) {
    val (bgColor, textColor, label) = when (status) {
        SyncTask.STATUS_SUCCESS -> Triple(Color(0xFFE8F5E9), Color(0xFF2E7D32), "Success")
        SyncTask.STATUS_FAILED -> Triple(Color(0xFFFFEBEE), Color(0xFFC62828), "Failed")
        SyncTask.STATUS_IN_PROGRESS -> Triple(Color(0xFFE3F2FD), Color(0xFF1565C0), "Syncing...")
        else -> Triple(Color(0xFFF5F5F5), Color(0xFF616161), "Never")
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = bgColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (status == SyncTask.STATUS_IN_PROGRESS) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 2.dp,
                    color = textColor
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = textColor,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun EmptyTaskState(
    webDavConfigs: List<WebDavConfig>,
    onAddTask: () -> Unit,
    onNavigateToWebDav: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.CloudSync,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No Sync Tasks Yet",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Create a task to start syncing local files or folders automatically to your WebDAV cloud.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))

        if (webDavConfigs.isEmpty()) {
            Button(onClick = onNavigateToWebDav) {
                Text("Configure WebDAV URL First")
            }
        } else {
            Button(onClick = onAddTask) {
                Icon(imageVector = Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Create First Task")
            }
        }
    }
}
