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

package org.vahak.syncme.ui.dialogs

import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.vahak.syncme.crypto.PassphraseGenerator
import org.vahak.syncme.crypto.PassphrasePolicy
import org.vahak.syncme.data.SyncTask
import org.vahak.syncme.data.WebDavConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDialog(
    taskToEdit: SyncTask?,
    initialPassphrase: String? = null,
    webDavConfigs: List<WebDavConfig>,
    onDismiss: () -> Unit,
    onSave: (SyncTask, passphrase: String?) -> Unit
) {
    val context = LocalContext.current

    var name by remember { mutableStateOf(taskToEdit?.name ?: "") }
    var selectedConfigId by remember { mutableStateOf(taskToEdit?.webDavConfigId ?: webDavConfigs.firstOrNull()?.id ?: -1L) }
    var sourceType by remember { mutableStateOf(taskToEdit?.sourceType ?: SyncTask.SOURCE_FILES) }

    val initialSyncMode = when {
        taskToEdit?.syncDirection == SyncTask.DIRECTION_BIDIRECTIONAL && taskToEdit.destinationMode == SyncTask.DESTINATION_LOCAL -> "BIDIRECTIONAL_LOCAL_LOCAL"
        taskToEdit?.syncDirection == SyncTask.DIRECTION_BIDIRECTIONAL -> "BIDIRECTIONAL_LOCAL_REMOTE"
        taskToEdit?.destinationMode == SyncTask.DESTINATION_LOCAL -> "MIRROR_LOCAL_TO_LOCAL"
        taskToEdit?.syncDirection == SyncTask.DIRECTION_REMOTE_TO_LOCAL -> "MIRROR_REMOTE_TO_LOCAL"
        else -> "MIRROR_LOCAL_TO_REMOTE"
    }
    var syncModeKey by remember { mutableStateOf(initialSyncMode) }

    var conflictStrategy by remember { mutableStateOf(taskToEdit?.conflictStrategy ?: SyncTask.STRATEGY_KEEP_BOTH) }

    var filterCriteria by remember { mutableStateOf(taskToEdit?.filterCriteria ?: SyncTask.FILTER_NONE) }
    var excludedExtensions by remember { mutableStateOf(taskToEdit?.excludedExtensions ?: "") }
    var maxFileSizeMbStr by remember { mutableStateOf(taskToEdit?.maxFileSizeMb?.let { if (it > 0) it.toString() else "" } ?: "") }

    var localUriStr by remember { mutableStateOf(taskToEdit?.localUri ?: "") }
    var destinationLocalUriStr by remember { mutableStateOf(taskToEdit?.destinationLocalUri ?: "") }
    var isSingleFile by remember { mutableStateOf(taskToEdit?.isSingleFile ?: false) }

    var isEncrypted by remember { mutableStateOf(taskToEdit?.isEncrypted ?: false) }
    var passphrase by remember { mutableStateOf(initialPassphrase ?: "") }
    var passphraseVisible by remember { mutableStateOf(false) }
    var passphraseConfirm by remember { mutableStateOf("") }


    DisposableEffect(Unit) { onDispose { passphrase = ""; passphraseConfirm = "" } }
    var mirrorDeletes by remember { mutableStateOf(taskToEdit?.isMirrorSync ?: false) }
    var showPlaintextConfirm by remember { mutableStateOf(false) }
    var plaintextConfirmText by remember { mutableStateOf("") }


    val passphraseChanged = isEncrypted && passphrase != (initialPassphrase ?: "")
    val passphrasePolicyError = if (passphraseChanged) PassphrasePolicy.validate(passphrase) else null
    val passphraseConfirmMismatch = passphraseChanged && passphrase != passphraseConfirm

    var frequencyType by remember { mutableStateOf(taskToEdit?.frequencyType ?: SyncTask.FREQ_DAILY) }
    var customValue by remember { mutableStateOf(taskToEdit?.customValue?.toString() ?: "1") }
    var customUnit by remember { mutableStateOf(taskToEdit?.customUnit ?: SyncTask.UNIT_DAYS) }

    var webDavDropdownExpanded by remember { mutableStateOf(false) }
    var sourceDropdownExpanded by remember { mutableStateOf(false) }
    var syncModeDropdownExpanded by remember { mutableStateOf(false) }
    var conflictDropdownExpanded by remember { mutableStateOf(false) }
    var filterDropdownExpanded by remember { mutableStateOf(false) }
    var freqDropdownExpanded by remember { mutableStateOf(false) }
    var unitDropdownExpanded by remember { mutableStateOf(false) }

    var requiredPermission by remember { mutableStateOf<String?>(null) }
    var permissionGranted by remember { mutableStateOf(true) }

    fun checkPermissionForSource(type: String) {
        val perm = when (type) {
            SyncTask.SOURCE_CONTACTS -> android.Manifest.permission.READ_CONTACTS
            SyncTask.SOURCE_MESSAGES -> android.Manifest.permission.READ_SMS
            SyncTask.SOURCE_CALL_LOGS -> android.Manifest.permission.READ_CALL_LOG
            else -> null
        }
        requiredPermission = perm
        permissionGranted = if (perm != null) {
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        permissionGranted = isGranted
    }


    val singleFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}
            localUriStr = it.toString()
            isSingleFile = true
        }
    }

    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}
            localUriStr = it.toString()
        }
    }

    val destFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}
            destinationLocalUriStr = it.toString()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (taskToEdit == null) "Create Sync Task" else "Edit Sync Task")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Task Name") },
                    placeholder = { Text("e.g. Backup Documents") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                
                ExposedDropdownMenuBox(
                    expanded = sourceDropdownExpanded,
                    onExpandedChange = { sourceDropdownExpanded = !sourceDropdownExpanded }
                ) {
                    val sourceLabel = when (sourceType) {
                        SyncTask.SOURCE_CONTACTS -> "Contacts"
                        SyncTask.SOURCE_MESSAGES -> "Messages (SMS)"
                        SyncTask.SOURCE_CALL_LOGS -> "Call Logs"
                        else -> "Files & Folders"
                    }

                    OutlinedTextField(
                        value = sourceLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Backup Source") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sourceDropdownExpanded) },
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth()
                    )

                    ExposedDropdownMenu(
                        expanded = sourceDropdownExpanded,
                        onDismissRequest = { sourceDropdownExpanded = false }
                    ) {
                        listOf(
                            SyncTask.SOURCE_FILES to "Files & Folders",
                            SyncTask.SOURCE_CONTACTS to "Contacts",
                            SyncTask.SOURCE_MESSAGES to "Messages (SMS)",
                            SyncTask.SOURCE_CALL_LOGS to "Call Logs"
                        ).forEach { (type, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    sourceType = type
                                    sourceDropdownExpanded = false
                                    
                                    if (type != SyncTask.SOURCE_FILES && taskToEdit == null) {
                                        isEncrypted = true
                                    }
                                    if (type != SyncTask.SOURCE_FILES) {
                                        if (syncModeKey != "MIRROR_REMOTE_TO_LOCAL") {
                                            localUriStr = "syncme://source/${type.lowercase()}"
                                        }
                                    } else if (localUriStr.startsWith("syncme://")) {
                                        localUriStr = ""
                                    }
                                    checkPermissionForSource(type)
                                }
                            )
                        }
                    }
                }

                
                if (sourceType != SyncTask.SOURCE_FILES) {
                    androidx.compose.material3.Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Privacy Notice: Backups may contain sensitive personal data. They are written to the destination you configure, such as a local folder or WebDAV server. Review your destination provider's security and retention practices. When encryption is enabled, verify that encryption completes successfully before relying on the backup's confidentiality.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }

        
                if (sourceType != SyncTask.SOURCE_FILES && !permissionGranted && requiredPermission != null) {
                    OutlinedButton(
                        onClick = { requiredPermission?.let { permissionLauncher.launch(it) } },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(imageVector = Icons.Default.Security, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Grant ${sourceType.lowercase().replace('_', ' ')} permission")
                    }
                }

            
                ExposedDropdownMenuBox(
                    expanded = syncModeDropdownExpanded,
                    onExpandedChange = { syncModeDropdownExpanded = !syncModeDropdownExpanded }
                ) {
                    val modeLabel = when (syncModeKey) {
                        "MIRROR_LOCAL_TO_REMOTE" -> "1) Sync (Local to Remote)"
                        "MIRROR_REMOTE_TO_LOCAL" -> "2) Sync (Remote to Local)"
                        "MIRROR_LOCAL_TO_LOCAL" -> "3) Sync (Local to Local)"
                        "BIDIRECTIONAL_LOCAL_REMOTE" -> "4) Bidirectional (Local vs Remote)"
                        "BIDIRECTIONAL_LOCAL_LOCAL" -> "5) Bidirectional (Local vs Local)"
                        else -> "1) Sync (Local to Remote)"
                    }

                    OutlinedTextField(
                        value = modeLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Sync Mode & Direction") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = syncModeDropdownExpanded) },
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth()
                    )

                    ExposedDropdownMenu(
                        expanded = syncModeDropdownExpanded,
                        onDismissRequest = { syncModeDropdownExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("1) Sync (Local to Remote)", style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "Make remote files exactly match source local data (remove remote files not in source)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                syncModeKey = "MIRROR_LOCAL_TO_REMOTE"
                                syncModeDropdownExpanded = false
                                if (sourceType != SyncTask.SOURCE_FILES) {
                                    localUriStr = "syncme://source/${sourceType.lowercase()}"
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("2) Sync (Remote to Local)", style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "Make local directory exactly match remote data (remove local files not in remote)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                syncModeKey = "MIRROR_REMOTE_TO_LOCAL"
                                syncModeDropdownExpanded = false
                                if (localUriStr.startsWith("syncme://")) {
                                    localUriStr = ""
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("3) Sync (Local to Local)", style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "Make local destination exactly match local source directory (remove destination files not in source)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                syncModeKey = "MIRROR_LOCAL_TO_LOCAL"
                                syncModeDropdownExpanded = false
                                if (sourceType != SyncTask.SOURCE_FILES) {
                                    localUriStr = "syncme://source/${sourceType.lowercase()}"
                                } else if (localUriStr.startsWith("syncme://")) {
                                    localUriStr = ""
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("4) Bidirectional (Local vs Remote)", style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "Full 2-way sync between local path and remote WebDAV server",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                syncModeKey = "BIDIRECTIONAL_LOCAL_REMOTE"
                                syncModeDropdownExpanded = false
                                if (sourceType != SyncTask.SOURCE_FILES) {
                                    localUriStr = "syncme://source/${sourceType.lowercase()}"
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("5) Bidirectional (Local vs Local)", style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "Full 2-way sync between local source path and local destination path",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                syncModeKey = "BIDIRECTIONAL_LOCAL_LOCAL"
                                syncModeDropdownExpanded = false
                                if (sourceType != SyncTask.SOURCE_FILES) {
                                    localUriStr = "syncme://source/${sourceType.lowercase()}"
                                } else if (localUriStr.startsWith("syncme://")) {
                                    localUriStr = ""
                                }
                            }
                        )
                    }
                }

                
                if (syncModeKey.startsWith("BIDIRECTIONAL") || syncModeKey == "MIRROR_REMOTE_TO_LOCAL") {
                    ExposedDropdownMenuBox(
                        expanded = conflictDropdownExpanded,
                        onExpandedChange = { conflictDropdownExpanded = !conflictDropdownExpanded }
                    ) {
                        val isLocalVsLocal = (syncModeKey == "BIDIRECTIONAL_LOCAL_LOCAL")
                        val strategyLabel = when (conflictStrategy) {
                            SyncTask.STRATEGY_LOCAL_WINS -> if (isLocalVsLocal) "Source local path wins" else "Local Wins"
                            SyncTask.STRATEGY_REMOTE_WINS -> if (isLocalVsLocal) "Destination local path wins" else "Remote Wins"
                            else -> "Keep both with timestamp suffix"
                        }

                        OutlinedTextField(
                            value = strategyLabel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Conflict Resolution Strategy") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = conflictDropdownExpanded) },
                            modifier = Modifier
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth()
                        )

                        ExposedDropdownMenu(
                            expanded = conflictDropdownExpanded,
                            onDismissRequest = { conflictDropdownExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(if (isLocalVsLocal) "Source local path wins" else "Local Wins") },
                                onClick = {
                                    conflictStrategy = SyncTask.STRATEGY_LOCAL_WINS
                                    conflictDropdownExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (isLocalVsLocal) "Destination local path wins" else "Remote Wins") },
                                onClick = {
                                    conflictStrategy = SyncTask.STRATEGY_REMOTE_WINS
                                    conflictDropdownExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Keep both with timestamp suffix") },
                                onClick = {
                                    conflictStrategy = SyncTask.STRATEGY_KEEP_BOTH
                                    conflictDropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                
                if (syncModeKey.startsWith("MIRROR")) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = mirrorDeletes,
                            onCheckedChange = { mirrorDeletes = it }
                        )
                        Text(
                            text = "Mirror deletions",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Text(
                        text = if (mirrorDeletes) {
                            "Files that no longer exist at the source will be DELETED from the destination. Deletion is skipped when a run has errors, the source looks empty, or more than half of the files would go."
                        } else {
                            "Off: nothing is ever deleted at the destination."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                
                if (syncModeKey != "MIRROR_LOCAL_TO_LOCAL" && syncModeKey != "BIDIRECTIONAL_LOCAL_LOCAL") {
                    ExposedDropdownMenuBox(
                        expanded = webDavDropdownExpanded,
                        onExpandedChange = { webDavDropdownExpanded = !webDavDropdownExpanded }
                    ) {
                        val selectedConfig = webDavConfigs.find { it.id == selectedConfigId }
                        OutlinedTextField(
                            value = selectedConfig?.name ?: if (webDavConfigs.isEmpty()) "No WebDAV URL Available" else "Select WebDAV Target",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("WebDAV Target") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = webDavDropdownExpanded) },
                            modifier = Modifier
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth()
                        )

                        ExposedDropdownMenu(
                            expanded = webDavDropdownExpanded,
                            onDismissRequest = { webDavDropdownExpanded = false }
                        ) {
                            webDavConfigs.forEach { config ->
                                DropdownMenuItem(
                                    text = { Text("${config.name} (${config.url})") },
                                    onClick = {
                                        selectedConfigId = config.id
                                        webDavDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                
                if (syncModeKey == "MIRROR_REMOTE_TO_LOCAL") {
                    Text(
                        text = "Local Restore Directory Target",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedButton(
                        onClick = { folderLauncher.launch(null) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(imageVector = Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Select Local Restore Folder")
                    }

                    if (localUriStr.isNotEmpty() && !localUriStr.startsWith("syncme://")) {
                        Text(
                            text = "Selected Restore Folder:\n$localUriStr",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                
                if (syncModeKey == "MIRROR_LOCAL_TO_LOCAL" || syncModeKey == "BIDIRECTIONAL_LOCAL_LOCAL") {
                    Text(
                        text = "Local Destination Folder Target",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedButton(
                        onClick = { destFolderLauncher.launch(null) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(imageVector = Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Select Local Destination Folder")
                    }

                    val displayedDestUri = if (destinationLocalUriStr.isNotEmpty()) {
                        destinationLocalUriStr
                    } else if (sourceType != SyncTask.SOURCE_FILES && localUriStr.isNotEmpty() && !localUriStr.startsWith("syncme://")) {
                        localUriStr
                    } else ""

                    if (displayedDestUri.isNotEmpty()) {
                        Text(
                            text = "Selected Destination Folder:\n$displayedDestUri",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                
                if (sourceType == SyncTask.SOURCE_FILES && syncModeKey != "MIRROR_REMOTE_TO_LOCAL") {
                    Text(
                        text = "Local Source Selection",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { folderLauncher.launch(null) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(imageVector = Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Select Folder")
                        }

                        OutlinedButton(
                            onClick = { singleFileLauncher.launch(arrayOf("*/*")) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(imageVector = Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Select File")
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = isSingleFile,
                            onCheckedChange = { isSingleFile = it }
                        )
                        Text(
                            text = "Selected folder contains only one file for sync",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    if (localUriStr.isNotEmpty()) {
                        Text(
                            text = "Selected Source Path:\n$localUriStr",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                
                if (sourceType == SyncTask.SOURCE_FILES) {
                    Text(
                        text = "File Exclusion & Filtering Rules",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    ExposedDropdownMenuBox(
                        expanded = filterDropdownExpanded,
                        onExpandedChange = { filterDropdownExpanded = !filterDropdownExpanded }
                    ) {
                        val filterLabel = when (filterCriteria) {
                            SyncTask.FILTER_EXTENSION -> "File Extension Based"
                            SyncTask.FILTER_SIZE -> "File Size Based"
                            SyncTask.FILTER_BOTH -> "Both Extension & Size Based"
                            else -> "None"
                        }

                        OutlinedTextField(
                            value = filterLabel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Filter Criteria") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = filterDropdownExpanded) },
                            modifier = Modifier
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth()
                        )

                        ExposedDropdownMenu(
                            expanded = filterDropdownExpanded,
                            onDismissRequest = { filterDropdownExpanded = false }
                        ) {
                            listOf(
                                SyncTask.FILTER_NONE to "None",
                                SyncTask.FILTER_EXTENSION to "File Extension Based",
                                SyncTask.FILTER_SIZE to "File Size Based",
                                SyncTask.FILTER_BOTH to "Both Extension & Size Based"
                            ).forEach { (criteria, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        filterCriteria = criteria
                                        filterDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    if (filterCriteria == SyncTask.FILTER_EXTENSION || filterCriteria == SyncTask.FILTER_BOTH) {
                        OutlinedTextField(
                            value = excludedExtensions,
                            onValueChange = { excludedExtensions = it },
                            label = { Text("Excluded Extensions") },
                            placeholder = { Text("e.g. .tmp, .log, .bak") },
                            singleLine = true,
                            supportingText = { Text("Comma-separated list of file extensions to exclude") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (filterCriteria == SyncTask.FILTER_SIZE || filterCriteria == SyncTask.FILTER_BOTH) {
                        OutlinedTextField(
                            value = maxFileSizeMbStr,
                            onValueChange = { maxFileSizeMbStr = it.filter { char -> char.isDigit() } },
                            label = { Text("Max File Size Threshold (MB)") },
                            placeholder = { Text("e.g. 500") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            supportingText = { Text("Files larger than this limit will be skipped from sync") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                
                Text(
                    text = "Security & Encryption",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = isEncrypted,
                        onCheckedChange = { checked ->
                            if (!checked && sourceType != SyncTask.SOURCE_FILES && syncModeKey != "MIRROR_REMOTE_TO_LOCAL") {
                                plaintextConfirmText = ""
                                showPlaintextConfirm = true
                            } else {
                                isEncrypted = checked
                            }
                        }
                    )
                    Icon(imageVector = Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (syncModeKey == "MIRROR_REMOTE_TO_LOCAL") "Remote Data is Encrypted (E2EE)" else "Enable End-to-End Encryption (E2EE)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (showPlaintextConfirm) {
                    AlertDialog(
                        onDismissRequest = { showPlaintextConfirm = false },
                        title = { Text("Store without encryption?") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Contacts, messages and call logs will be saved as readable text. Anyone with access to the destination can read them. Type UNENCRYPTED to confirm.")
                                OutlinedTextField(
                                    value = plaintextConfirmText,
                                    onValueChange = { plaintextConfirmText = it },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        },
                        confirmButton = {
                            Button(
                                enabled = plaintextConfirmText == "UNENCRYPTED",
                                onClick = {
                                    isEncrypted = false
                                    showPlaintextConfirm = false
                                }
                            ) {
                                Text("Turn off")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showPlaintextConfirm = false }) {
                                Text("Keep encryption")
                            }
                        }
                    )
                }

                if (isEncrypted) {
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        isError = passphraseChanged && passphrasePolicyError != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        label = { Text("Encryption Passphrase") },
                        placeholder = { Text("Enter strong passphrase") },
                        singleLine = true,
                        visualTransformation = if (passphraseVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { passphraseVisible = !passphraseVisible }) {
                                Icon(
                                    imageVector = if (passphraseVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "Toggle Passphrase Visibility"
                                )
                            }
                        },
                        supportingText = {
                            Text(
                                passphrasePolicyError
                                    ?: "AES-256-GCM, per-file keys, PBKDF2 (650,000 iterations). Stored securely via Android KeyStore. Keep a record of your passphrase; encrypted backups cannot be recovered without it."
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    TextButton(
                        onClick = {
                            val generated = PassphraseGenerator.generate()
                            passphrase = generated
                            passphraseConfirm = generated
                            passphraseVisible = true 
                        }
                    ) {
                        Text("Generate a random passphrase")
                    }

                    if (passphraseChanged) {
                        OutlinedTextField(
                            value = passphraseConfirm,
                            onValueChange = { passphraseConfirm = it },
                            label = { Text("Confirm Passphrase") },
                            singleLine = true,
                            isError = passphraseConfirm.isNotEmpty() && passphraseConfirmMismatch,
                            visualTransformation = if (passphraseVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                            supportingText = {
                                if (passphraseConfirm.isNotEmpty() && passphraseConfirmMismatch) {
                                    Text("Passphrases do not match.")
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                
                ExposedDropdownMenuBox(
                    expanded = freqDropdownExpanded,
                    onExpandedChange = { freqDropdownExpanded = !freqDropdownExpanded }
                ) {
                    val freqLabel = when (frequencyType) {
                        SyncTask.FREQ_DAILY -> "Daily"
                        SyncTask.FREQ_WEEKLY -> "Weekly"
                        SyncTask.FREQ_MONTHLY -> "Monthly"
                        SyncTask.FREQ_QUARTERLY -> "Quarterly"
                        SyncTask.FREQ_CUSTOM -> "Custom"
                        else -> frequencyType
                    }

                    OutlinedTextField(
                        value = freqLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Sync Frequency") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = freqDropdownExpanded) },
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth()
                    )

                    ExposedDropdownMenu(
                        expanded = freqDropdownExpanded,
                        onDismissRequest = { freqDropdownExpanded = false }
                    ) {
                        listOf(
                            SyncTask.FREQ_DAILY to "Daily",
                            SyncTask.FREQ_WEEKLY to "Weekly",
                            SyncTask.FREQ_MONTHLY to "Monthly",
                            SyncTask.FREQ_QUARTERLY to "Quarterly",
                            SyncTask.FREQ_CUSTOM to "Custom..."
                        ).forEach { (type, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    frequencyType = type
                                    freqDropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                
                if (frequencyType == SyncTask.FREQ_CUSTOM) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = customValue,
                            onValueChange = { customValue = it.filter { char -> char.isDigit() } },
                            label = { Text("Every X") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )

                        ExposedDropdownMenuBox(
                            expanded = unitDropdownExpanded,
                            onExpandedChange = { unitDropdownExpanded = !unitDropdownExpanded },
                            modifier = Modifier.weight(1.2f)
                        ) {
                            val unitLabel = when (customUnit) {
                                SyncTask.UNIT_DAYS -> "Day/s"
                                SyncTask.UNIT_WEEKS -> "Week/s"
                                SyncTask.UNIT_MONTHS -> "Month/s"
                                SyncTask.UNIT_YEARS -> "Year/s"
                                else -> customUnit
                            }

                            OutlinedTextField(
                                value = unitLabel,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Unit") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = unitDropdownExpanded) },
                                modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            )

                            ExposedDropdownMenu(
                                expanded = unitDropdownExpanded,
                                onDismissRequest = { unitDropdownExpanded = false }
                            ) {
                                listOf(
                                    SyncTask.UNIT_DAYS to "Day/s",
                                    SyncTask.UNIT_WEEKS to "Week/s",
                                    SyncTask.UNIT_MONTHS to "Month/s",
                                    SyncTask.UNIT_YEARS to "Year/s"
                                ).forEach { (unit, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            customUnit = unit
                                            unitDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val isWebDavValid = (syncModeKey == "MIRROR_LOCAL_TO_LOCAL" || syncModeKey == "BIDIRECTIONAL_LOCAL_LOCAL") || (selectedConfigId != -1L)

            val isLocalPathValid = when (syncModeKey) {
                "MIRROR_LOCAL_TO_REMOTE", "BIDIRECTIONAL_LOCAL_REMOTE" -> {
                    if (sourceType == SyncTask.SOURCE_FILES) localUriStr.isNotBlank() else true
                }
                "MIRROR_REMOTE_TO_LOCAL" -> {
                    localUriStr.isNotBlank() && !localUriStr.startsWith("syncme://")
                }
                "MIRROR_LOCAL_TO_LOCAL", "BIDIRECTIONAL_LOCAL_LOCAL" -> {
                    if (sourceType == SyncTask.SOURCE_FILES) {
                        localUriStr.isNotBlank() && destinationLocalUriStr.isNotBlank()
                    } else {
                        destinationLocalUriStr.isNotBlank() || (localUriStr.isNotBlank() && !localUriStr.startsWith("syncme://"))
                    }
                }
                else -> false
            }

            val hasStoredPassphrase = !initialPassphrase.isNullOrBlank()
            val isPassphraseValid = !isEncrypted || if (passphraseChanged) {
                passphrasePolicyError == null && !passphraseConfirmMismatch
            } else {
                hasStoredPassphrase
            }
            val isFormValid = name.isNotBlank() && isWebDavValid && isLocalPathValid && permissionGranted && isPassphraseValid

            Button(
                enabled = isFormValid,
                onClick = {
                    val activePassphrase = if (isEncrypted) {
                        if (passphraseChanged) passphrase else initialPassphrase
                    } else null

                    val finalLocalUri = if (syncModeKey == "MIRROR_REMOTE_TO_LOCAL" || sourceType == SyncTask.SOURCE_FILES) {
                        localUriStr
                    } else {
                        "syncme://source/${sourceType.lowercase()}"
                    }

                    val finalDestUri = if (syncModeKey == "MIRROR_LOCAL_TO_LOCAL" || syncModeKey == "BIDIRECTIONAL_LOCAL_LOCAL") {
                        if (sourceType != SyncTask.SOURCE_FILES && destinationLocalUriStr.isBlank()) localUriStr else destinationLocalUriStr
                    } else ""

                    val isMirror = mirrorDeletes && syncModeKey.startsWith("MIRROR")

                    val direction = when (syncModeKey) {
                        "MIRROR_REMOTE_TO_LOCAL" -> SyncTask.DIRECTION_REMOTE_TO_LOCAL
                        "BIDIRECTIONAL_LOCAL_REMOTE", "BIDIRECTIONAL_LOCAL_LOCAL" -> SyncTask.DIRECTION_BIDIRECTIONAL
                        else -> SyncTask.DIRECTION_LOCAL_TO_REMOTE
                    }

                    val destMode = if (syncModeKey == "MIRROR_LOCAL_TO_LOCAL" || syncModeKey == "BIDIRECTIONAL_LOCAL_LOCAL") SyncTask.DESTINATION_LOCAL else SyncTask.DESTINATION_REMOTE

                    val task = SyncTask(
                        id = taskToEdit?.id ?: 0L,
                        name = name.trim(),
                        webDavConfigId = if (destMode == SyncTask.DESTINATION_LOCAL) null else (if (selectedConfigId != -1L) selectedConfigId else null),
                        localUri = finalLocalUri,
                        destinationLocalUri = finalDestUri,
                        isSingleFile = if (sourceType != SyncTask.SOURCE_FILES || syncModeKey == "MIRROR_REMOTE_TO_LOCAL") false else isSingleFile,
                        isEncrypted = isEncrypted,
                        syncDirection = direction,
                        sourceType = sourceType,
                        destinationMode = destMode,
                        isMirrorSync = isMirror,
                        conflictStrategy = conflictStrategy,
                        filterCriteria = filterCriteria,
                        excludedExtensions = excludedExtensions.trim(),
                        maxFileSizeMb = maxFileSizeMbStr.toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
                        frequencyType = frequencyType,
                        customValue = customValue.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                        customUnit = customUnit,
                        lastSyncTime = taskToEdit?.lastSyncTime,
                        lastSyncStatus = taskToEdit?.lastSyncStatus ?: SyncTask.STATUS_NEVER,
                        lastSyncError = taskToEdit?.lastSyncError,
                        isEnabled = taskToEdit?.isEnabled ?: true
                    )
                    onSave(task, activePassphrase)
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
