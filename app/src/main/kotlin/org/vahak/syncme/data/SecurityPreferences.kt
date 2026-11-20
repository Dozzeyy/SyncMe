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

package org.vahak.syncme.data

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecurityPreferences @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("syncme_security_prefs", Context.MODE_PRIVATE)

    private val _screenProtection = MutableStateFlow(prefs.getBoolean(KEY_SCREEN_PROTECTION, DEFAULT_SCREEN_PROTECTION))
    val screenProtection: StateFlow<Boolean> = _screenProtection

    fun setScreenProtection(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SCREEN_PROTECTION, enabled).apply()
        _screenProtection.value = enabled
    }

    companion object {
        const val KEY_SCREEN_PROTECTION = "screen_protection"
        const val DEFAULT_SCREEN_PROTECTION = true
    }
}
