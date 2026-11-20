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

package org.vahak.syncme.crypto

object PassphrasePolicy {
    const val MIN_LENGTH = 14
    private const val MIN_DISTINCT_CHARS = 6
    private const val LENGTH_WITHOUT_MIXED_CLASSES = 18

    
    private val WEAK_FRAGMENTS = listOf(
        "password", "passw0rd", "qwerty", "letmein", "iloveyou", "welcome", "admin", "syncme",
        "123456", "654321", "abcdef", "111111", "000000"
    )
    private const val LENGTH_ALLOWING_FRAGMENTS = 20
    private const val MAX_SAME_CHAR_RUN = 3
    private const val MAX_SEQUENCE_RUN = 4

    private val COMMON = setOf(
        "passwordpassword", "password1234", "password12345", "123456789012", "1234567890123",
        "qwertyuiop12", "qwertyuiopas", "iloveyou1234", "letmein12345", "administrator",
        "syncmepassword", "syncmepassphrase", "abcdefghijkl", "abcdef123456"
    )

    fun validate(passphrase: String): String? {
        if (passphrase.length < MIN_LENGTH) {
            return "Use at least $MIN_LENGTH characters."
        }
        if (passphrase.isBlank()) {
            return "Passphrase cannot be only spaces."
        }
        if (passphrase.toSet().size < MIN_DISTINCT_CHARS) {
            return "Passphrase repeats too few different characters."
        }
        if (passphrase.lowercase() in COMMON) {
            return "That passphrase is too common."
        }
        if (longestRun(passphrase) { a, b -> a == b } > MAX_SAME_CHAR_RUN) {
            return "Avoid repeating the same character more than $MAX_SAME_CHAR_RUN times in a row."
        }
        if (longestRun(passphrase.lowercase()) { a, b -> b - a == 1 } > MAX_SEQUENCE_RUN ||
            longestRun(passphrase.lowercase()) { a, b -> a - b == 1 } > MAX_SEQUENCE_RUN
        ) {
            return "Avoid sequences like abcde or 12345."
        }
        if (passphrase.length < LENGTH_ALLOWING_FRAGMENTS) {
            val lower = passphrase.lowercase()
            if (WEAK_FRAGMENTS.any { it in lower }) {
                return "That contains a very common word or pattern. Use something less predictable or a longer passphrase."
            }
        }
        if (passphrase.length < LENGTH_WITHOUT_MIXED_CLASSES && characterClasses(passphrase) < 2) {
            return "Mix letters, numbers or symbols, or use $LENGTH_WITHOUT_MIXED_CLASSES or more characters."
        }
        return null
    }

    private fun longestRun(s: String, step: (Char, Char) -> Boolean): Int {
        if (s.isEmpty()) return 0
        var best = 1
        var run = 1
        for (i in 1 until s.length) {
            if (step(s[i - 1], s[i])) {
                run++
                if (run > best) best = run
            } else {
                run = 1
            }
        }
        return best
    }

    private fun characterClasses(s: String): Int {
        var classes = 0
        if (s.any { it.isLowerCase() }) classes++
        if (s.any { it.isUpperCase() }) classes++
        if (s.any { it.isDigit() }) classes++
        if (s.any { !it.isLetterOrDigit() }) classes++
        return classes
    }
}
