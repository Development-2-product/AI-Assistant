package com.iamode.app.core.diagnostics

/** Keeps local diagnostics useful without retaining identifiers that are unnecessary to troubleshoot. */
internal fun redactDiagnosticDetail(detail: String): String = detail
    .replace(EMAIL) { match -> "${match.groupValues[1]}***@${match.groupValues[2]}" }
    // Avoid retaining phone numbers if a provider puts one into an error message. This deliberately
    // requires at least eight digits, so normal status codes and timestamps remain readable.
    .replace(PHONE_NUMBER, "[redacted number]")
    .take(MAX_DETAIL_LENGTH)

private val EMAIL = Regex("([A-Za-z0-9])[A-Za-z0-9._%+-]*@([A-Za-z0-9.-]+)")
private val PHONE_NUMBER = Regex("(?<![A-Za-z0-9])\\+?\\d(?:[\\d -]{6,}\\d)(?![A-Za-z0-9])")
private const val MAX_DETAIL_LENGTH = 300
