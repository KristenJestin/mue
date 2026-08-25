package fr.kristenjestin.mue.data.local.database

import fr.kristenjestin.mue.domain.model.ActivityDuration
import fr.kristenjestin.mue.domain.model.EquipmentType
import fr.kristenjestin.mue.domain.model.Load
import fr.kristenjestin.mue.domain.model.PerceivedEffort
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/*
 * How the Activities module turns a domain value into a column and back.
 *
 * None of this is a Room `@TypeConverter` on purpose. PRD 16.1 persists an enum by its stable
 * id and PRD 16.3 persists a date as ISO text, and an entity whose fields are already `String`
 * and `Int` exports a schema that reads like the table it describes. A converter would hide
 * both decisions behind a type and let a renamed constant silently rewrite the history.
 *
 * Every enum already carries its own `id` and a total `fromId`, so only the cases with a null
 * or a bound to defend appear here.
 */

/** `HH:mm`, and never `HH:mm:ss`: [LocalTime.toString] grows a seconds field the moment one is set. */
private val TIME_OF_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

internal fun LocalDate.toColumn(): String = toString()

internal fun String.toLocalDateColumn(): LocalDate = LocalDate.parse(this)

internal fun LocalTime.toColumn(): String = format(TIME_OF_DAY)

/** A time that cannot be read is an absent one, which PRD 8.2 keeps distinct from midnight. */
internal fun String?.toLocalTimeColumn(): LocalTime? =
    this?.let { runCatching { LocalTime.parse(it, TIME_OF_DAY) }.getOrNull() }

/** PRD 8.4 and 9.2 both allow no equipment at all, so the column is nullable on both sides. */
internal fun EquipmentType?.toColumn(): String? = this?.id

internal fun String?.toEquipmentTypeColumn(): EquipmentType? = this?.let(EquipmentType::fromId)

internal fun Int?.toPerceivedEffortColumn(): PerceivedEffort? = this?.let(PerceivedEffort::ofOrNull)

internal fun Int?.toLoadColumn(): Load? = this?.let(Load::ofGramsOrNull)

internal fun Int?.toDurationColumn(): ActivityDuration? = this?.let(ActivityDuration::ofSecondsOrNull)
