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

object MirrorSafety {
    private const val MIN_FILES_FOR_RATIO_CHECK = 4

    fun evaluate(
        sourceCount: Int,
        destinationCount: Int,
        deleteCount: Int,
        hadErrors: Boolean
    ): String? {
        if (deleteCount <= 0) return null
        if (hadErrors) {
            return "Mirror cleanup skipped: this run had errors, so $deleteCount file(s) were not deleted."
        }
        if (sourceCount == 0) {
            return "Mirror cleanup skipped: the source looks empty, so $deleteCount file(s) were not deleted."
        }
        if (destinationCount >= MIN_FILES_FOR_RATIO_CHECK && deleteCount * 2 > destinationCount) {
            return "Mirror cleanup skipped: it would delete $deleteCount of $destinationCount files. Delete them manually if intended."
        }
        return null
    }
}
