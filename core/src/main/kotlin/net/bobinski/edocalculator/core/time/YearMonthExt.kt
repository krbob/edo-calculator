package net.bobinski.edocalculator.core.time

import kotlinx.datetime.YearMonth
import kotlinx.datetime.number

fun YearMonth.toIsoString(): String = String.format("%04d-%02d", year, month.number)