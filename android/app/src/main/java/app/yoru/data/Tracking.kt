// Acompanhamento: as mesmas contas de app/services_track.py, feitas no banco local.
package app.yoru.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class Period(val label: String) { Today("Hoje"), Week("Esta semana"), Month("Este mês") }

data class Series(val label: String, val unit: String, val kanji: String, val values: List<Float>, val totalOverride: Float? = null) {
    val total: Float get() = totalOverride ?: values.sum()
    val maxValue: Float get() = values.maxOrNull() ?: 0f
}

data class TrackingUi(
    val period: Period,
    val labels: List<String>,
    val series: List<Series>,
    val habitsPct: Int?,
    val doneTasks: List<Task>,
)

/** Linha de um hábito na tela: últimos 7 dias, feito hoje, sequência. */
data class HabitRow(val habit: Habit, val days: List<Pair<LocalDate, Boolean>>, val doneToday: Boolean, val streak: Int, val weekDone: Int)

object Tracking {
    private val instantFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)
    private val weekdays = listOf("seg", "ter", "qua", "qui", "sex", "sáb", "dom")

    fun range(period: Period, today: LocalDate = LocalDate.now()): Pair<LocalDate, LocalDate> = when (period) {
        Period.Today -> today to today
        Period.Week -> { val s = today.minusDays((today.dayOfWeek.value - 1).toLong()); s to s.plusDays(6) }
        Period.Month -> { val s = today.withDayOfMonth(1); s to s.plusMonths(1).minusDays(1) }
    }

    /** Início do dia local como instante UTC em texto (comparável com os carimbos do banco). */
    fun startOfDayUtc(day: LocalDate): String = instantFormat.format(day.atStartOfDay(ZoneId.systemDefault()).toInstant())

    fun localDateOf(iso: String?): LocalDate? =
        Time.parseInstant(iso)?.atZone(ZoneId.systemDefault())?.toLocalDate()

    private fun localHourOf(iso: String?): Int? = Time.parseInstant(iso)?.atZone(ZoneId.systemDefault())?.hour

    fun build(
        period: Period,
        doneTasks: List<Task>,
        focus: List<FocusSession>,
        logs: List<LogEntry>,
        bookIds: Set<String>,
        habits: List<Habit>,
        checks: List<HabitCheck>,
        today: LocalDate = LocalDate.now(),
    ): TrackingUi {
        val (start, end) = range(period, today)
        val focusOnly = focus.filter { it.kind == "focus" }
        val labels: List<String>
        val tasksVals: List<Float>
        val focusVals: List<Float>
        val studyVals: List<Float>
        val pagesVals: List<Float>

        if (period == Period.Today) {
            val hours = (6..23).toList()
            labels = hours.map { if (it % 3 == 0) "${it}h" else "" }
            fun <T> bucket(items: List<T>, hour: (T) -> Int?, value: (T) -> Float): List<Float> {
                val v = FloatArray(hours.size)
                for (it in items) { val h = hour(it); if (h != null && h >= 6) v[h - 6] += value(it) }
                return v.toList()
            }
            tasksVals = bucket(doneTasks, { localHourOf(it.completedAt) }, { 1f })
            focusVals = bucket(focusOnly, { localHourOf(it.startedAt) }, { it.minutes.toFloat() })
            studyVals = List(hours.size) { 0f }
            pagesVals = List(hours.size) { 0f }
        } else {
            val days = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.toList()
            val index = days.withIndex().associate { it.value to it.index }
            labels = if (period == Period.Week) days.map { weekdays[it.dayOfWeek.value - 1] }
            else days.map { if (it.dayOfMonth == 1 || it.dayOfMonth % 5 == 0) it.dayOfMonth.toString() else "" }
            fun <T> bucket(items: List<T>, day: (T) -> LocalDate?, value: (T) -> Float): List<Float> {
                val v = FloatArray(days.size)
                for (it in items) { val i = index[day(it)] ?: continue; v[i] += value(it) }
                return v.toList()
            }
            tasksVals = bucket(doneTasks, { localDateOf(it.completedAt) }, { 1f })
            focusVals = bucket(focusOnly, { localDateOf(it.startedAt) }, { it.minutes.toFloat() })
            studyVals = bucket(logs.filter { it.minutes != null }, { runCatching { LocalDate.parse(it.day) }.getOrNull() }, { (it.minutes ?: 0).toFloat() })
            pagesVals = bucket(logs.filter { it.amount != null && it.resourceId in bookIds }, { runCatching { LocalDate.parse(it.day) }.getOrNull() }, { (it.amount ?: 0.0).toFloat() })
        }

        val studyTotal = logs.sumOf { it.minutes ?: 0 }.toFloat()
        val pagesTotal = logs.filter { it.resourceId in bookIds }.sumOf { it.amount ?: 0.0 }.toFloat()
        val series = listOf(
            Series("Tarefas feitas", "tarefas", "務", tasksVals),
            Series("Foco", "min", "集", focusVals),
            Series("Estudo", "min", "学", studyVals, if (period == Period.Today) studyTotal else null),
            Series("Páginas lidas", "páginas", "読", pagesVals, if (period == Period.Today) pagesTotal else null),
        )

        // Hábitos: % de (hábitos ativos × dias do período até hoje) marcados
        val last = if (end.isAfter(today)) today else end
        val nDays = (java.time.temporal.ChronoUnit.DAYS.between(start, last) + 1).toInt()
        var pct: Int? = null
        if (habits.isNotEmpty() && nDays > 0) {
            val doneByHabit = checks.groupBy { it.habitId }.mapValues { e -> e.value.map { it.day }.toSet() }
            var possible = 0
            var got = 0
            for (h in habits) {
                val expected = (if (h.daysPerWeek >= 7) nDays else Math.round(nDays * h.daysPerWeek / 7f)).coerceAtLeast(1)
                possible += expected
                got += minOf(doneByHabit[h.id]?.size ?: 0, expected)
            }
            if (possible > 0) pct = Math.round(100f * got / possible)
        }
        return TrackingUi(period, labels, series, pct, doneTasks.take(12))
    }

    fun habitRows(habits: List<Habit>, checks: List<HabitCheck>, today: LocalDate = LocalDate.now()): List<HabitRow> {
        val done = checks.groupBy { it.habitId }.mapValues { e -> e.value.mapNotNull { runCatching { LocalDate.parse(it.day) }.getOrNull() }.toSet() }
        val week = (6 downTo 0).map { today.minusDays(it.toLong()) }
        return habits.map { h ->
            val mine = done[h.id] ?: emptySet()
            var d = if (today in mine) today else today.minusDays(1)
            var streak = 0
            while (d in mine) { streak++; d = d.minusDays(1) }
            HabitRow(h, week.map { it to (it in mine) }, today in mine, streak, week.count { it in mine })
        }
    }

    fun endOfDayUtc(day: LocalDate): String = startOfDayUtc(day.plusDays(1))

    @Suppress("unused")
    private fun nowInstant(): Instant = Instant.now()
}
