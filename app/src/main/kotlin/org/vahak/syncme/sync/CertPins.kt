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

object CertPins {

    val PIN_REGEX = Regex("""sha256/[A-Za-z0-9+/]{43}=""")
    const val MAX_PINS = 5

    private val SEPARATORS = Regex("""[\s,;]+""")

    fun parse(raw: String?): List<String> =
        raw.orEmpty().split(SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }.distinct()


    fun isValid(raw: String?): Boolean {
        val pins = parse(raw)
        return pins.size <= MAX_PINS && pins.all { PIN_REGEX.matches(it) }
    }

    
    fun validPinsOrEmpty(raw: String?): List<String> = if (isValid(raw)) parse(raw) else emptyList()

    
    fun normalize(raw: String?): String = parse(raw).joinToString("\n")

    fun describeProblem(raw: String?): String? {
        val pins = parse(raw)
        return when {
            pins.size > MAX_PINS -> "At most $MAX_PINS pins are allowed"
            pins.any { !PIN_REGEX.matches(it) } -> "Each pin must be sha256/ followed by a 44-character base64 hash"
            else -> null
        }
    }
}
