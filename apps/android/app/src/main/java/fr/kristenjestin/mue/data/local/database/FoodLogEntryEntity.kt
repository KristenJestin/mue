package fr.kristenjestin.mue.data.local.database

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import fr.kristenjestin.mue.domain.model.Estimation
import fr.kristenjestin.mue.domain.model.FoodLogEntry
import fr.kristenjestin.mue.domain.model.FoodLogEntryId
import fr.kristenjestin.mue.domain.model.FoodLogKind
import fr.kristenjestin.mue.domain.model.LoggedAmount
import fr.kristenjestin.mue.domain.model.MealPlanKey
import fr.kristenjestin.mue.domain.model.MealSlot
import fr.kristenjestin.mue.domain.model.Quantity
import fr.kristenjestin.mue.domain.model.QuantityUnit
import fr.kristenjestin.mue.domain.model.ReferenceUnit
import fr.kristenjestin.mue.domain.model.Servings
import java.time.LocalDate
import java.time.LocalTime

/**
 * One row per consumption (PRD_FOOD 8.4), carrying its own snapshot of the numbers.
 *
 * The nutrients are **copied** at save time, which is why [NutrientColumns] is embedded here and
 * not read through `sourceRef`: 8.4 says editing or deleting the food afterwards "ne change
 * jamais une ligne déjà journalisée", and 21.2 calls the line "autoportante" for the same
 * reason — it must apply on a device that has never seen the food it came from.
 *
 * `source_ref` is therefore **not a foreign key**. A line whose food was deleted is still a
 * valid line; a constraint would either forbid the deletion PRD_FOOD 9.3 allows, or cascade the
 * history away.
 *
 * `planned_on` / `plan_slot` are `fromPlan`, the business key of the proposition this line
 * confirmed (21.3). They are not a foreign key either: the line is its own aggregate and may
 * arrive before, or long after, the proposition that produced it.
 *
 * The amount is stored exactly as 8.4 describes it — a quantity and its unit — and the three
 * shapes of `LoggedAmount` fall out of the unit alone: `gram`/`millilitre` is a weighed amount,
 * `serving` is a fraction of a recipe, and a `NULL` unit is the unmeasured quick add. Nothing is
 * a float, and `weighed_cooked` is the flag of 8.6 rather than a second stored mass.
 */
@Entity(
    tableName = FoodLogEntryEntity.TABLE_NAME,
    indices = [
        Index(value = ["consumed_on", "slot", "consumed_at"]),
        Index(value = ["source_ref"]),
        Index(value = ["planned_on", "plan_slot"]),
    ],
)
data class FoodLogEntryEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "consumed_on")
    val consumedOn: String,

    @ColumnInfo(name = "consumed_at")
    val consumedAt: String,

    @ColumnInfo(name = "slot")
    val slot: String,

    @ColumnInfo(name = "kind")
    val kind: String,

    @ColumnInfo(name = "title")
    val title: String,

    @Embedded
    val nutrients: NutrientColumns,

    @ColumnInfo(name = "estimation")
    val estimation: String,

    @ColumnInfo(name = "source_ref")
    val sourceRef: String? = null,

    @ColumnInfo(name = "amount_label")
    val amountLabel: String? = null,

    @ColumnInfo(name = "quantity_thousandths")
    val quantityThousandths: Int? = null,

    @ColumnInfo(name = "quantity_unit")
    val quantityUnit: String? = null,

    @ColumnInfo(name = "portions_thousandths")
    val portionsThousandths: Int? = null,

    @ColumnInfo(name = "weighed_cooked")
    val weighedCooked: Boolean,

    @ColumnInfo(name = "planned_on")
    val plannedOn: String? = null,

    @ColumnInfo(name = "plan_slot")
    val planSlot: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
) {
    companion object {
        const val TABLE_NAME = "food_log_entry"

        /**
         * `MealSlot` persists by its stable id, and those ids do not sort into the order of the
         * day: `breakfast, dinner, lunch, snack` is alphabetical and wrong. The order of
         * `MealSlot.ORDERED` is spelled out here so that every query that groups a day by moment
         * agrees with the screen, and so that adding a moment is a change in one place.
         */
        const val SLOT_ORDER =
            "CASE slot WHEN 'breakfast' THEN 0 WHEN 'lunch' THEN 1 " +
                "WHEN 'snack' THEN 2 WHEN 'dinner' THEN 3 ELSE 4 END"
    }
}

/**
 * The unit decides the shape (PRD_FOOD 8.4). A unit naming a mass or a volume with no readable
 * quantity beside it, or a `serving` unit with none, degrades to [LoggedAmount.Unmeasured]
 * rather than to a quantity of zero: "how much" being unknown is what a quick add already is,
 * while zero would claim the user ate nothing.
 */
private fun loggedAmount(unit: String?, thousandths: Int?): LoggedAmount = when (unit) {
    null -> LoggedAmount.Unmeasured
    QuantityUnit.SERVING.id ->
        thousandths
            ?.let { Servings.ofThousandthsOrNull(it.toLong()) }
            ?.let(LoggedAmount::Portioned)
            ?: LoggedAmount.Unmeasured

    else ->
        thousandths
            ?.let { Quantity.ofThousandthsOrNull(it.toLong()) }
            ?.let { LoggedAmount.Measured(it, ReferenceUnit.fromId(unit)) }
            ?: LoggedAmount.Unmeasured
}

private val LoggedAmount.storedThousandths: Int?
    get() = when (this) {
        is LoggedAmount.Measured -> quantity.thousandths
        is LoggedAmount.Portioned -> servings.thousandths
        LoggedAmount.Unmeasured -> null
    }

fun FoodLogEntryEntity.toDomain(): FoodLogEntry = FoodLogEntry(
    id = FoodLogEntryId(id),
    consumedOn = LocalDate.parse(consumedOn),
    consumedAt = LocalTime.parse(consumedAt),
    slot = MealSlot.fromId(slot),
    kind = FoodLogKind.fromId(kind),
    title = title,
    amount = loggedAmount(quantityUnit, quantityThousandths),
    nutrients = nutrients.toDomain(),
    estimation = Estimation.fromId(estimation),
    sourceRef = sourceRef,
    amountLabel = amountLabel,
    portions = portionsThousandths?.let { Servings.ofThousandthsOrNull(it.toLong()) },
    weighedCooked = weighedCooked,
    fromPlan = plannedOn?.let { date ->
        planSlot?.let { slotId -> MealPlanKey(LocalDate.parse(date), MealSlot.fromId(slotId)) }
    },
)

fun FoodLogEntry.toEntity(createdAt: Long, updatedAt: Long): FoodLogEntryEntity =
    FoodLogEntryEntity(
        id = id.value,
        consumedOn = consumedOn.toString(),
        consumedAt = consumedAt.toString(),
        slot = slot.id,
        kind = kind.id,
        title = title,
        nutrients = nutrients.toColumns(),
        estimation = estimation.id,
        sourceRef = sourceRef,
        amountLabel = amountLabel,
        quantityThousandths = amount.storedThousandths,
        quantityUnit = amount.unit?.id,
        portionsThousandths = portions?.thousandths,
        weighedCooked = weighedCooked,
        plannedOn = fromPlan?.plannedOn?.toString(),
        planSlot = fromPlan?.slot?.id,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
