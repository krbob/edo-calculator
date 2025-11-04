package net.bobinski.edocalculator.core.time

import kotlinx.datetime.LocalDate
import kotlinx.datetime.number

fun LocalDate.toIsoString(): String = String.format("%04d-%02d-%02d", year, month.number, day)