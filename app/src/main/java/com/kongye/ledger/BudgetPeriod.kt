package com.kongye.ledger

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import org.json.JSONObject

internal enum class BudgetPeriod(val id: String, val key: String = "budget:$id") {
    DAY("day"), WEEK("week"), MONTH("month", "budget"), QUARTER("quarter"), YEAR("year");

    fun bounds(date: LocalDate, monthlyCycle: (LocalDate) -> Pair<LocalDate, LocalDate>): Pair<LocalDate, LocalDate> =
        when (this) {
            DAY -> date to date.plusDays(1)
            WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).let { it to it.plusWeeks(1) }
            MONTH -> monthlyCycle(date)
            QUARTER -> date.withDayOfMonth(1).withMonth((date.monthValue - 1) / 3 * 3 + 1).let { it to it.plusMonths(3) }
            YEAR -> date.withDayOfYear(1).let { it to it.plusYears(1) }
        }
}

internal data class DailyBudgetBasis(
    val period: BudgetPeriod,
    val limit: Long,
    val earlierSpending: Long,
    val daysLeft: Long
) {
    val remaining: Long get() = (limit - earlierSpending).coerceAtLeast(0)
    val daily: Long get() = remaining / daysLeft

    fun json(): JSONObject = JSONObject()
        .put("period", period.id)
        .put("limit", limit)
        .put("earlierSpending", earlierSpending)
        .put("daysLeft", daysLeft)
        .put("remaining", remaining)
        .put("daily", daily)
}
