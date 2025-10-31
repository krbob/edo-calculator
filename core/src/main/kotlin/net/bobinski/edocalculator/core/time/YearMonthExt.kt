package net.bobinski.edocalculator.core.time

import kotlinx.datetime.YearMonth

fun YearMonth.toIsoString(): String = String.format("%04d-%02d", year, month.ordinal + 1)