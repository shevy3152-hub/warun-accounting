package com.warun.accounting.ui.input

import java.time.LocalDate

internal fun formatDateInput(
    rawValue: String,
    currentYear: Int = LocalDate.now().year
): String {
    val normalized = rawValue
        .map(::normalizeDateCharacter)
        .filter { it.isDigit() || it == '-' }
        .joinToString("")
        .replace(RepeatedSeparator, "-")
        .trimStart('-')

    if (normalized.isBlank()) return ""
    if ('-' in normalized) {
        return formatSeparatedDate(normalized, currentYear)
    }

    val digits = normalized.take(MaxDateDigits)
    if (digits.length == 4 && digits.isValidMonthDay()) {
        return "$currentYear-${digits.take(2)}-${digits.takeLast(2)}"
    }
    return when (digits.length) {
        in 0..4 -> digits
        in 5..6 -> "${digits.take(4)}-${digits.drop(4)}"
        else -> "${digits.take(4)}-${digits.substring(4, 6)}-${digits.drop(6)}"
    }
}

private fun formatSeparatedDate(value: String, currentYear: Int): String {
    val parts = value.split('-').take(3)
    val first = parts.getOrElse(0) { "" }
    val second = parts.getOrElse(1) { "" }
    val third = parts.getOrElse(2) { "" }

    if (first.length <= 2 && first.toIntOrNull() in 1..12) {
        val month = first.padStart(2, '0')
        val day = second.take(2)
        return buildString {
            append(currentYear)
            append('-')
            append(month)
            if (parts.size >= 2) {
                append('-')
                append(day)
            }
        }
    }

    val year = first.take(4)
    val month = second.take(2)
    val carriedDay = second.drop(2)
    val day = (carriedDay + third).take(2)
    val hasDayComponent = carriedDay.isNotEmpty() || parts.size >= 3
    return buildString {
        append(year)
        if (parts.size >= 2) {
            append('-')
            append(
                if (hasDayComponent && month.length == 1) {
                    month.padStart(2, '0')
                } else {
                    month
                }
            )
        }
        if (hasDayComponent) {
            append('-')
            append(day)
        }
    }
}

private fun normalizeDateCharacter(character: Char): Char = when (character) {
    in '０'..'９' -> '0' + (character - '０')
    '/', '／', '・', '.', '．', 'ー', '−', '―', '‐', '年', '月', '日' -> '-'
    else -> character
}

private fun String.isValidMonthDay(): Boolean {
    val month = take(2).toIntOrNull() ?: return false
    val day = takeLast(2).toIntOrNull() ?: return false
    return month in 1..12 && day in 1..31
}

private val RepeatedSeparator = Regex("-+")
private const val MaxDateDigits = 8
