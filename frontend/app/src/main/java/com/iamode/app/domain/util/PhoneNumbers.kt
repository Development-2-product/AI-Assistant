package com.iamode.app.domain.util

object PhoneNumbers {
    /** Default country for 10-digit local numbers. */
    private const val DEFAULT_COUNTRY_CODE = "91"

    /** Normalizes to digits with country code, e.g. "+91 98480 12345" and "098480 12345" -> "919848012345". */
    fun normalize(raw: String): String {
        val digits = raw.filter { it.isDigit() }.trimStart('0')
        return if (digits.length == 10) DEFAULT_COUNTRY_CODE + digits else digits
    }

    fun looksLikeNumber(text: String): Boolean {
        val digits = text.count { it.isDigit() }
        return digits >= 7 && text.all { it.isDigit() || it in "+ -()" }
    }
}
