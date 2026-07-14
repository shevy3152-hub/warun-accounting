package com.warun.accounting.ui.util

import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val yenFormatter = NumberFormat.getCurrencyInstance(Locale.JAPAN).apply {
    maximumFractionDigits = 0
}

fun Long.toYen(): String = yenFormatter.format(this)

fun todayString(): String = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

fun currentMonthString(): String = YearMonth.now().toString()
