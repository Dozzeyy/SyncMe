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

import android.content.Context
import android.net.Uri
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.RandomAccessFile

object StagingFiles {
    private const val CHUNK = 64 * 1024

    fun secureDelete(file: File) {
        try {
            if (file.isFile) {
                val length = file.length()
                if (length > 0) {
                    RandomAccessFile(file, "rw").use { raf ->
                        val zeros = ByteArray(CHUNK)
                        var remaining = length
                        raf.seek(0)
                        while (remaining > 0) {
                            val n = minOf(remaining, CHUNK.toLong()).toInt()
                            raf.write(zeros, 0, n)
                            remaining -= n
                        }
                        raf.fd.sync()
                    }
                }
            }
        } catch (_: Exception) {
            
        }
        try { file.delete() } catch (_: Exception) {}
    }

    fun secureDeleteRecursively(file: File) {
        try {
            if (file.isDirectory) file.listFiles()?.forEach { secureDeleteRecursively(it) }
            secureDelete(file)
        } catch (_: Exception) {}
    }


    fun cleanupStale(cacheDir: File) {
        try {
            cacheDir.listFiles()?.forEach { f ->
                val stale = (f.isDirectory && (f.name.startsWith("device_backup_staging_") || f.name.startsWith("enc_staging_"))) ||
                    (f.isFile && f.name.startsWith("temp_dl_"))
                if (stale) secureDeleteRecursively(f)
            }
        } catch (_: Exception) {}
    }
}

class UriRequestBody(
    private val context: Context,
    private val uri: Uri,
    private val length: Long,
    private val type: MediaType?
) : RequestBody() {
    override fun contentType(): MediaType? = type
    override fun contentLength(): Long = length
    override fun writeTo(sink: BufferedSink) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw java.io.IOException("Cannot open source")
        input.source().use { sink.writeAll(it) }
    }
}
