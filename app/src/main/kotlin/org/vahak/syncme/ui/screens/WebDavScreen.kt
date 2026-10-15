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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.vahak.syncme.data.WebDavConfig
import org.vahak.syncme.ui.MainViewModel

@Composable
fun WebDavScreen(viewModel: MainViewModel) {
    val configs by viewModel.webDavConfigs.collectAsState()

    var showDialog by remember { mutableStateOf(false) }
    var configToEdit by remember { mutableStateOf<WebDavConfig?>(null) }
    var configToDelete by remember { mutableStateOf<WebDavConfig?>(null) }

    var isTestingConnection by remember { mutableStateOf(false) }
    var testResultDialogText by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        if (configs.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Cloud,
                    contentDescription = null,
                    modifier = Modifier.size(80.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "No WebDAV Servers Configured",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Add your cloud server details (e.g. Nextcloud, ownCloud, WebDAV URL) to enable syncing.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = {
                    configToEdit = null
                    showDialog = true
                }) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add WebDAV Server")
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(configs, key = { it.id }) { config ->
                    WebDavConfigCard(
                        config = config,
                        onTest = {
                            isTestingConnection = true
                            viewModel.testWebDavConnection(config) { success, msg ->
                                isTestingConnection = false
                                testResultDialogText = Pair(success, msg)
                            }
                        },
                        onEdit = {
                            configToEdit = config
                            showDialog = true
                        },
                        onDelete = {
                            configToDelete = config
                        }
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = {
                configToEdit = null
                showDialog = true
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
        ) {
            Icon(imageVector = Icons.Default.Add, contentDescription = "Add WebDAV Server")
        }
    }

    if (showDialog) {
        val initialPasswordRes = configToEdit?.let { viewModel.getWebDavPasswordResult(it.id) }
        val initialPassword = if (initialPasswordRes is org.vahak.syncme.crypto.StorageResult.Success) initialPasswordRes.data else ""
        WebDavConfigDialog(
            configToEdit = configToEdit,
            initialPassword = initialPassword,
            onDismiss = { showDialog = false },
            onSave = { config ->
                viewModel.saveWebDavConfig(config) { success, errorMsg ->
                    if (success) {
                        showDialog = false
                    } else {
                        testResultDialogText = Pair(false, errorMsg ?: "Failed to save WebDAV configuration.")
                    }
                }
            }
        )
    }

    configToDelete?.let { config ->
        AlertDialog(
            onDismissRequest = { configToDelete = null },
            title = { Text("Delete WebDAV Configuration") },
            text = { Text("Are you sure you want to delete '${config.name}'? Tasks linked to this WebDAV server will also be deleted.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteWebDavConfig(config)
                        configToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { configToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }


    if (isTestingConnection) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Testing Connection") },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(16.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("Connecting to WebDAV server...")
                }
            },
            confirmButton = {}
        )
    }


    testResultDialogText?.let { (success, msg) ->
        AlertDialog(
            onDismissRequest = { testResultDialogText = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (success) Icons.Default.CheckCircle else Icons.Default.Error,
                        contentDescription = null,
                        tint = if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (success) "Connection Successful" else "Connection Failed")
                }
            },
            text = { Text(msg) },
            confirmButton = {
                Button(onClick = { testResultDialogText = null }) {
                    Text("OK")
                }
            }
        )
    }
}

@Composable
fun WebDavConfigCard(
    config: WebDavConfig,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
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
                text = config.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "URL: ${config.url}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "User: ${config.username}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onTest) {
                    Icon(
                        imageVector = Icons.Default.NetworkCheck,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Test Connection")
                }

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(onClick = onEdit) {
                    Icon(imageVector = Icons.Default.Edit, contentDescription = "Edit WebDAV")
                }

                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete WebDAV",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
fun WebDavConfigDialog(
    configToEdit: WebDavConfig?,
    initialPassword: String = "",
    onDismiss: () -> Unit,
    onSave: (WebDavConfig) -> Unit
) {
    var name by remember { mutableStateOf(configToEdit?.name ?: "") }
    var url by remember { mutableStateOf(configToEdit?.url ?: "") }
    var username by remember { mutableStateOf(configToEdit?.username ?: "") }
    var password by remember { mutableStateOf(initialPassword) }
    var passwordVisible by remember { mutableStateOf(false) }

    val isHttpWarning = url.trim().startsWith("http://", ignoreCase = true)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (configToEdit == null) "Add WebDAV Server" else "Edit WebDAV Server") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Server Name") },
                    placeholder = { Text("e.g. Nextcloud") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("WebDAV URL") },
                    placeholder = { Text("https://example.com/remote.php/dav/files/user/") },
                    singleLine = true,
                    isError = isHttpWarning,
                    supportingText = if (isHttpWarning) {
                        { Text("Cleartext http:// is blocked for security. Must use https://", color = MaterialTheme.colorScheme.error) }
                    } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "Toggle Password Visibility"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() && url.isNotBlank() && !isHttpWarning && username.isNotBlank(),
                onClick = {
                    onSave(
                        WebDavConfig(
                            id = configToEdit?.id ?: 0L,
                            name = name.trim(),
                            url = url.trim(),
                            username = username.trim(),
                            password = password
                        )
                    )
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
