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

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap


object SyncRunLock {
    @PublishedApi
    internal val locks = ConcurrentHashMap<Long, Mutex>()

    @PublishedApi
    internal fun mutexFor(taskId: Long): Mutex = locks.getOrPut(taskId) { Mutex() }

    fun isRunning(taskId: Long): Boolean = locks[taskId]?.isLocked == true

    
    suspend inline fun <T> runIfIdle(taskId: Long, block: () -> T): T? {
        val mutex = mutexFor(taskId)
        if (!mutex.tryLock()) return null
        try {
            return block()
        } finally {
            mutex.unlock()
        }
    }

    suspend inline fun <T> runExclusive(taskId: Long, block: () -> T): T =
        mutexFor(taskId).withLock { block() }
}
