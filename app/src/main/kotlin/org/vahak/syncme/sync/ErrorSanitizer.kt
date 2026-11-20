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

object ErrorSanitizer {
    const val DEFAULT_MESSAGE = "Sync operation failed."
    const val MAX_LENGTH = 250

    private val SENSITIVE_NAMES =
        "password|passwd|pwd|passphrase|pass|token|access_token|refresh_token|secret|client_secret|api[_-]?key|apikey|auth|authorization|key|pin"

    
    private val URL_USERINFO = Regex("""(?i)\b([a-z][a-z0-9+.-]*://)[^/?#\s@]*@""")
    private val URL_QUERY = Regex("""(?i)(\b[a-z][a-z0-9+.-]*://[^\s?#]*)\?[^\s#]*""")
    private val AUTH_HEADER = Regex("""(?i)\b(authorization|proxy-authorization)\s*[:=]\s*(basic|bearer|digest)?\s*[^\s,;]+""")
    private val SCHEME_CREDENTIAL = Regex("""(?i)\b(basic|bearer)\s+[A-Za-z0-9+/=._~-]{8,}""")
    
    private val NAMED_VALUE = Regex(
        """(?i)(["']?\b(?:$SENSITIVE_NAMES)\b["']?\s*[:=]\s*)("[^"]*"|'[^']*'|[^\s&,;"']+)"""
    )
    private val PIN_VALUE = Regex("""sha256/[A-Za-z0-9+/]{20,}={0,2}""")
    
    private val LONG_OPAQUE = Regex("""[A-Za-z0-9+/_-]{40,}={0,2}""")

    fun sanitize(raw: String?): String {
        if (raw.isNullOrBlank()) return DEFAULT_MESSAGE
        var s = raw
        s = URL_USERINFO.replace(s, "$1***@")
        s = AUTH_HEADER.replace(s) { m -> "${m.groupValues[1]}: ***" }
        s = SCHEME_CREDENTIAL.replace(s) { m -> "${m.groupValues[1]} ***" }
        s = URL_QUERY.replace(s, "$1?***")
        s = NAMED_VALUE.replace(s) { m -> "${m.groupValues[1]}***" }
        s = PIN_VALUE.replace(s, "sha256/***")
        s = LONG_OPAQUE.replace(s, "***")
        
        s = s.filter { it == '\n' || it == ' ' || !it.isISOControl() }.replace('\n', ' ').trim()
        if (s.length > MAX_LENGTH) s = s.take(MAX_LENGTH - 1).trimEnd() + "…"
        return s.ifBlank { DEFAULT_MESSAGE }
    }
}
