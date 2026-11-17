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
    var destinationMode by remember { mutableStateOf(taskToEdit?.destinationMode ?: SyncTask.DESTINATION_REMOTE) }
    var localUriStr by remember { mutableStateOf(taskToEdit?.localUri ?: "") }
    var isSingleFile by remember { mutableStateOf(taskToEdit?.isSingleFile ?: false) }
    var syncDirection by remember { mutableStateOf(taskToEdit?.syncDirection ?: SyncTask.DIRECTION_LOCAL_TO_REMOTE) }

    var isEncrypted by remember { mutableStateOf(taskToEdit?.isEncrypted ?: false) }
    var passphrase by remember { mutableStateOf(initialPassphrase ?: "") }
    var passphraseVisible by remember { mutableStateOf(false) }

    var frequencyType by remember { mutableStateOf(taskToEdit?.frequencyType ?: SyncTask.FREQ_DAILY) }
    var customValue by remember { mutableStateOf(taskToEdit?.customValue?.toString() ?: "1") }
    var customUnit by remember { mutableStateOf(taskToEdit?.customUnit ?: SyncTask.UNIT_DAYS) }

    var webDavDropdownExpanded by remember { mutableStateOf(false) }
    var sourceDropdownExpanded by remember { mutableStateOf(false) }
    var destinationDropdownExpanded by remember { mutableStateOf(false) }
    var directionDropdownExpanded by remember { mutableStateOf(false) }
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
                                    if (type != SyncTask.SOURCE_FILES) {
                                        if (destinationMode == SyncTask.DESTINATION_REMOTE) {
                                            localUriStr = "syncme://source/${type.lowercase()}"
                                        }
                                        syncDirection = SyncTask.DIRECTION_LOCAL_TO_REMOTE
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

                
                if (sourceType != SyncTask.SOURCE_FILES) {
                    ExposedDropdownMenuBox(
                        expanded = destinationDropdownExpanded,
                        onExpandedChange = { destinationDropdownExpanded = !destinationDropdownExpanded }
                    ) {
                        val destLabel = if (destinationMode == SyncTask.DESTINATION_LOCAL) {
                            "Local -> Local (Export to Device Folder)"
                        } else {
                            "Local -> Remote (Cloud WebDAV)"
                        }

                        OutlinedTextField(
                            value = destLabel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Sync Destination Mode") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = destinationDropdownExpanded) },
                            modifier = Modifier
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth()
                        )

                        ExposedDropdownMenu(
                            expanded = destinationDropdownExpanded,
                            onDismissRequest = { destinationDropdownExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Local -> Remote (Cloud WebDAV)") },
                                onClick = {
                                    destinationMode = SyncTask.DESTINATION_REMOTE
                                    destinationDropdownExpanded = false
                                    localUriStr = "syncme://source/${sourceType.lowercase()}"
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Local -> Local (Export to Device Folder)") },
                                onClick = {
                                    destinationMode = SyncTask.DESTINATION_LOCAL
                                    destinationDropdownExpanded = false
                                    if (localUriStr.startsWith("syncme://")) {
                                        localUriStr = ""
                                    }
                                }
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

        
                if (sourceType == SyncTask.SOURCE_FILES) {
                    ExposedDropdownMenuBox(
                        expanded = directionDropdownExpanded,
                        onExpandedChange = { directionDropdownExpanded = !directionDropdownExpanded }
                    ) {
                        val directionLabel = if (syncDirection == SyncTask.DIRECTION_REMOTE_TO_LOCAL) {
                            "Remote -> Local (Download / Restore)"
                        } else {
                            "Local -> Remote (Upload / Backup)"
                        }

                        OutlinedTextField(
                            value = directionLabel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Sync Direction") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = directionDropdownExpanded) },
                            modifier = Modifier
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth()
                        )

                        ExposedDropdownMenu(
                            expanded = directionDropdownExpanded,
                            onDismissRequest = { directionDropdownExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Local -> Remote (Upload / Backup)") },
                                onClick = {
                                    syncDirection = SyncTask.DIRECTION_LOCAL_TO_REMOTE
                                    directionDropdownExpanded = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Remote -> Local (Download / Restore)") },
                                onClick = {
                                    syncDirection = SyncTask.DIRECTION_REMOTE_TO_LOCAL
                                    directionDropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                
                if (destinationMode == SyncTask.DESTINATION_REMOTE) {
                    ExposedDropdownMenuBox(
                        expanded = webDavDropdownExpanded,
                        onExpandedChange = { webDavDropdownExpanded = !webDavDropdownExpanded }
                    ) {
                        val selectedConfig = webDavConfigs.find { it.id == selectedConfigId }
                        OutlinedTextField(
                            value = selectedConfig?.name ?: if (webDavConfigs.isEmpty()) "No WebDAV URL Available" else "Select WebDAV URL",
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

            
                if (sourceType != SyncTask.SOURCE_FILES && destinationMode == SyncTask.DESTINATION_LOCAL) {
                    Text(
                        text = "Local Export Folder Target",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedButton(
                        onClick = { folderLauncher.launch(null) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(imageVector = Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Select Local Destination Folder")
                    }

                    if (localUriStr.isNotEmpty() && !localUriStr.startsWith("syncme://")) {
                        Text(
                            text = "Selected Export Folder:\n$localUriStr",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

            
                if (sourceType == SyncTask.SOURCE_FILES) {
                    Text(
                        text = if (syncDirection == SyncTask.DIRECTION_REMOTE_TO_LOCAL) "Local Restore Target" else "Local Source Selection",
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

                        if (syncDirection == SyncTask.DIRECTION_LOCAL_TO_REMOTE) {
                            OutlinedButton(
                                onClick = { singleFileLauncher.launch(arrayOf("*/*")) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(imageVector = Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Select File")
                            }
                        }
                    }

                    if (syncDirection == SyncTask.DIRECTION_LOCAL_TO_REMOTE) {
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
                    }

                    if (localUriStr.isNotEmpty()) {
                        Text(
                            text = "Selected Path:\n$localUriStr",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
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
                        onCheckedChange = { isEncrypted = it }
                    )
                    Icon(imageVector = Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (syncDirection == SyncTask.DIRECTION_REMOTE_TO_LOCAL) "Remote Data is Encrypted (E2EE)" else "Enable End-to-End Encryption (E2EE)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (isEncrypted) {
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
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
                            Text("AES-256-GCM + PBKDF2 (650,000 iterations). Stored securely via Android KeyStore. Keep a record of your passphrase; encrypted backups cannot be recovered without it.")
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
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
            val isWebDavValid = (destinationMode == SyncTask.DESTINATION_LOCAL) || (selectedConfigId != -1L)
            val hasStoredPassphrase = !initialPassphrase.isNullOrBlank()
            val isPassphraseValid = !isEncrypted || passphrase.isNotBlank() || hasStoredPassphrase
            val isFormValid = name.isNotBlank() && isWebDavValid && localUriStr.isNotBlank() && permissionGranted && isPassphraseValid
            Button(
                enabled = isFormValid,
                onClick = {
                    val activePassphrase = if (isEncrypted) {
                        if (passphrase.isNotBlank()) passphrase.trim() else initialPassphrase
                    } else null

                    val task = SyncTask(
                        id = taskToEdit?.id ?: 0L,
                        name = name.trim(),
                        webDavConfigId = if (destinationMode == SyncTask.DESTINATION_LOCAL) -1L else selectedConfigId,
                        localUri = localUriStr,
                        isSingleFile = if (sourceType != SyncTask.SOURCE_FILES) false else isSingleFile,
                        isEncrypted = isEncrypted,
                        syncDirection = if (sourceType != SyncTask.SOURCE_FILES) SyncTask.DIRECTION_LOCAL_TO_REMOTE else syncDirection,
                        sourceType = sourceType,
                        destinationMode = destinationMode,
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
