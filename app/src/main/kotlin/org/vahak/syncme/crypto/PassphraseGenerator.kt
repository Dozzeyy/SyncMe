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

import java.security.SecureRandom

object PassphraseGenerator {
    private const val UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"   // no I, O
    private const val LOWER = "abcdefghijkmnpqrstuvwxyz"    // no l, o
    private const val DIGITS = "23456789"                    // no 0, 1
    private const val ALPHABET = UPPER + LOWER + DIGITS
    const val GROUPS = 6
    const val GROUP_SIZE = 4

    fun generate(random: SecureRandom = SecureRandom()): String {
        while (true) {
            val chars = CharArray(GROUPS * GROUP_SIZE) { ALPHABET[random.nextInt(ALPHABET.length)] }
            val candidate = buildString {
                chars.forEachIndexed { i, c ->
                    if (i > 0 && i % GROUP_SIZE == 0) append('-')
                    append(c)
                }
            }
            
            val hasAll = candidate.any { it in UPPER } && candidate.any { it in LOWER } && candidate.any { it in DIGITS }
            if (hasAll && PassphrasePolicy.validate(candidate) == null) return candidate
        }
    }
}
