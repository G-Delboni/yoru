// Tempo no mesmo formato do servidor (app/sync.py):
//   instante UTC   "2026-10-31T18:45:12.123456Z"  (sempre 6 casas: comparar texto = comparar tempo)
//   data local     "2026-10-31"
//   hora de parede "2026-11-12T19:00:00" (sem fuso; provas e entregas)
package app.yoru.data

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

object Time {
    private val instantFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)
    private val wallFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)

    fun now(): String = instantFormat.format(Instant.now())
    fun today(): String = LocalDate.now().toString()
    fun nowWallClock(): String = wallFormat.format(LocalDateTime.now())
    fun newId(): String = UUID.randomUUID().toString()

    /** Começo do dia local de hoje, como instante UTC (para "concluídas hoje"). */
    fun startOfTodayUtc(): String =
        instantFormat.format(LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant())

    fun parseInstant(iso: String?): Instant? {
        if (iso.isNullOrBlank()) return null
        return runCatching { Instant.parse(iso) }.getOrNull()
    }

    /** "agora", "há 5 min", "há 3 h", "ontem" ou "28 out". */
    fun relative(iso: String?): String {
        val instant = parseInstant(iso) ?: return "nunca"
        val seconds = Duration.between(instant, Instant.now()).seconds
        val local = instant.atZone(ZoneId.systemDefault()).toLocalDate()
        return when {
            seconds < 60 -> "agora"
            seconds < 3600 -> "há ${seconds / 60} min"
            local == LocalDate.now() -> "há ${seconds / 3600} h"
            local == LocalDate.now().minusDays(1) -> "ontem"
            else -> shortDate(local.toString())
        }
    }

    private val months = listOf("jan", "fev", "mar", "abr", "mai", "jun", "jul", "ago", "set", "out", "nov", "dez")
    private val weekdays = listOf("segunda", "terça", "quarta", "quinta", "sexta", "sábado", "domingo")

    /** "31 out" a partir de "2026-10-31". Texto que não for data volta como veio. */
    fun shortDate(isoDate: String?): String {
        val d = runCatching { LocalDate.parse(isoDate?.take(10)) }.getOrNull() ?: return isoDate ?: ""
        return "${d.dayOfMonth} ${months[d.monthValue - 1]}"
    }

    /** "hoje", "amanhã", "em 3 dias", "há 2 dias", "sexta, 6 nov". */
    fun deadline(isoDate: String?): String {
        val d = runCatching { LocalDate.parse(isoDate?.take(10)) }.getOrNull() ?: return ""
        val days = Duration.between(LocalDate.now().atStartOfDay(), d.atStartOfDay()).toDays()
        return when {
            days == 0L -> "hoje"
            days == 1L -> "amanhã"
            days == -1L -> "ontem"
            days < 0 -> "há ${-days} dias"
            days < 7 -> "${weekdays[d.dayOfWeek.value - 1]}, ${shortDate(isoDate)}"
            else -> "em $days dias, ${shortDate(isoDate)}"
        }
    }

    fun greeting(): String {
        val h = LocalDateTime.now().hour
        return when {
            h < 6 -> "Boa madrugada"
            h < 12 -> "Bom dia"
            h < 18 -> "Boa tarde"
            else -> "Boa noite"
        }
    }

    fun longToday(): String {
        val d = LocalDate.now()
        val fullMonths = listOf("janeiro", "fevereiro", "março", "abril", "maio", "junho",
            "julho", "agosto", "setembro", "outubro", "novembro", "dezembro")
        return "${weekdays[d.dayOfWeek.value - 1].replaceFirstChar { it.uppercase() }}, ${d.dayOfMonth} de ${fullMonths[d.monthValue - 1]}"
    }
}
