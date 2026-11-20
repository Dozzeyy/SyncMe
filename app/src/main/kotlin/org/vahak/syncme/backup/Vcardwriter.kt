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

package org.vahak.syncme.backup

import java.io.ByteArrayOutputStream

data class VCardContact(
    val displayName: String = "",
    val familyName: String = "",
    val givenName: String = "",
    val middleName: String = "",
    val prefix: String = "",
    val suffix: String = "",
    val phones: List<Typed> = emptyList(),
    val emails: List<Typed> = emptyList(),
    val organization: String = "",
    val title: String = "",
    val addresses: List<Address> = emptyList(),
    val urls: List<String> = emptyList(),
    val birthday: String = "",
    val note: String = ""
) {

    data class Typed(val type: String, val value: String)

    data class Address(
        val type: String,
        val poBox: String = "",
        val street: String = "",
        val city: String = "",
        val region: String = "",
        val postalCode: String = "",
        val country: String = ""
    )
}


object VCardWriter {

    private const val CRLF = "\r\n"
    private val BIRTHDAY_REGEX = Regex("""^(\d{4}|-)-\d{2}-\d{2}$""")

    fun write(contacts: List<VCardContact>): ByteArray {
        val out = ByteArrayOutputStream()
        for (c in contacts) {
            val card = card(c) ?: continue
            out.write(card.toByteArray(Charsets.UTF_8))
        }
        return out.toByteArray()
    }

    internal fun card(c: VCardContact): String? {
        val phones = c.phones.filter { it.value.isNotBlank() }.distinctBy { it.value.filter(Char::isDigit).ifEmpty { it.value } }
        val emails = c.emails.filter { it.value.isNotBlank() }.distinctBy { it.value.trim().lowercase() }
        val addresses = c.addresses.filter {
            listOf(it.poBox, it.street, it.city, it.region, it.postalCode, it.country).any(String::isNotBlank)
        }
        val urls = c.urls.filter { it.isNotBlank() }.distinct()

        val fn = sequenceOf(
            c.displayName,
            listOf(c.prefix, c.givenName, c.middleName, c.familyName, c.suffix).filter { it.isNotBlank() }.joinToString(" "),
            c.organization,
            phones.firstOrNull()?.value.orEmpty(),
            emails.firstOrNull()?.value.orEmpty()
        ).map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null

        val sb = StringBuilder()
        fun line(name: String, value: String) = sb.append(fold("$name:$value")).append(CRLF)

        line("BEGIN", "VCARD")
        line("VERSION", "3.0")
        line("N", listOf(c.familyName, c.givenName, c.middleName, c.prefix, c.suffix).joinToString(";") { esc(it) })
        line("FN", esc(fn))
        if (c.organization.isNotBlank()) line("ORG", esc(c.organization))
        if (c.title.isNotBlank()) line("TITLE", esc(c.title))
        phones.forEach { line("TEL;TYPE=${it.type}", esc(it.value)) }
        emails.forEach { line("EMAIL;TYPE=${it.type}", esc(it.value)) }
        addresses.forEach {
            
            val v = listOf(it.poBox, "", it.street, it.city, it.region, it.postalCode, it.country).joinToString(";") { s -> esc(s) }
            line("ADR;TYPE=${it.type}", v)
        }
        urls.forEach { line("URL", esc(it)) }
        if (BIRTHDAY_REGEX.matches(c.birthday.trim())) line("BDAY", c.birthday.trim())
        if (c.note.isNotBlank()) line("NOTE", esc(c.note))
        line("END", "VCARD")
        return sb.toString()
    }

    
    internal fun esc(raw: String): String {
        val sb = StringBuilder(raw.length + 8)
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            when {
                ch == '\r' -> {
                    sb.append("\\n")
                    if (i + 1 < raw.length && raw[i + 1] == '\n') i++
                }
                ch == '\n' -> sb.append("\\n")
                ch == '\\' -> sb.append("\\\\")
                ch == ';' -> sb.append("\\;")
                ch == ',' -> sb.append("\\,")
                ch == '\t' -> sb.append(' ')
                ch.code < 0x20 || ch.code == 0x7F -> Unit
                else -> sb.append(ch)
            }
            i++
        }
        return sb.toString()
    }


    internal fun fold(line: String): String {
        if (line.toByteArray(Charsets.UTF_8).size <= 75) return line
        val sb = StringBuilder()
        var octets = 0
        var limit = 75
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            val n = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (octets + n > limit) {
                sb.append(CRLF).append(' ')
                octets = 0
                limit = 74
            }
            sb.appendCodePoint(cp)
            octets += n
            i += Character.charCount(cp)
        }
        return sb.toString()
    }
}
