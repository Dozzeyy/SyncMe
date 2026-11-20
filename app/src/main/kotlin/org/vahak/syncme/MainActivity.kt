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


package org.vahak.syncme

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import dagger.hilt.android.AndroidEntryPoint
import org.vahak.syncme.data.SecurityPreferences
import org.vahak.syncme.ui.MainScreen
import org.vahak.syncme.ui.MainViewModel
import org.vahak.syncme.ui.theme.SyncMeTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var securityPreferences: SecurityPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        applyScreenProtection(securityPreferences.screenProtection.value)
        setContent {
            val viewModel: MainViewModel = hiltViewModel()
            val themeMode by viewModel.themeMode.collectAsState()
            val screenProtection by viewModel.screenProtection.collectAsState()

            LaunchedEffect(screenProtection) { applyScreenProtection(screenProtection) }

            SyncMeTheme(themeMode = themeMode) {
                MainScreen(viewModel = viewModel)
            }
        }
    }

    private fun applyScreenProtection(enabled: Boolean) {
        if (enabled) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
