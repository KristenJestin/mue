package fr.kristenjestin.mue.data.local.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import fr.kristenjestin.mue.domain.model.FoodLogEntryId
import fr.kristenjestin.mue.domain.model.MealPlanEntry
import fr.kristenjestin.mue.domain.model.MealPlanKey
import fr.kristenjestin.mue.domain.model.MealSlot
import fr.kristenjestin.mue.domain.model.RecipeId
import fr.kristenjestin.mue.domain.model.Servings
import java.time.LocalDate

/**
 * At most one proposition per date and moment (PRD_FOOD 8.5), enforced by the primary key.
 *
 * **The key is `(planned_on, slot)`, not a UUID.** PRD_FOOD 8.5 sketches an `id`; 21.3 says the
 * business key is `(date, moment)` and that two concurrent propositions on one moment resolve to
 * one, "jamais dupliquée". With a UUID those two sentences fight: two offline devices would mint
 * two ids for one slot, both would be valid rows, and the "au maximum une proposition" of 8.5
 * would have to be re-enforced by hand on every client, forever. With the composite key the
 * convergence is structural — the second write lands on the first row — exactly as a weight
 * measurement is keyed by its date rather than by an invented id. The merged `MealPlanEntry`
 * carries no `id` field, so this is already settled upstream and this table matches it.
 *
 * The key is also the `aggregate_id` the outbox journals, through `MealPlanKey.aggregateId`, so
 * the identity SQLite enforces and the identity the server reconciles are the same string.
 *
 * PRD_FOOD 20.2 asks for `meal_plan_entry(plannedOn, slot)` "en unicité". That is this primary
 * key: SQLite backs it with its own index, so declaring a second unique index over the same two
 * columns would only be a duplicate to maintain.
 *
 * `recipe_id` is a foreign key with its index. Unlike a recipe's ingredients, a proposition
 * without its recipe has nothing to show at all — 8.5 says "une proposition référence toujours
 * une recette" — so the cascade is the correct answer to a deleted recipe, and the index is what
 * keeps that cascade from scanning the table.
 */
@Entity(
    tableName = MealPlanEntryEntity.TABLE_NAME,
    primaryKeys = ["planned_on", "slot"],
    foreignKeys = [
        ForeignKey(
            entity = RecipeEntity::class,
            parentColumns = ["id"],
            childColumns = ["recipe_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["recipe_id"])],
)
data class MealPlanEntryEntity(
    @ColumnInfo(name = "planned_on")
    val plannedOn: String,

    @ColumnInfo(name = "slot")
    val slot: String,

    @ColumnInfo(name = "recipe_id")
    val recipeId: String,

    @ColumnInfo(name = "planned_servings_thousandths")
    val plannedServingsThousandths: Int,

    /** Set when the user confirms; cleared when the confirmation is undone (PRD_FOOD 8.5). */
    @ColumnInfo(name = "consumed_log_entry_id")
    val consumedLogEntryId: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
) {
    companion object {
        const val TABLE_NAME = "meal_plan_entry"

        /**
         * The order of the day, which is `FoodLogEntryEntity.SLOT_ORDER`'s own string and not a
         * second copy of it.
         *
         * A `const val` may be initialised from another `const val`, so Room still gets the
         * compile-time constant its `@Query` needs while the moments are transcribed into SQL
         * exactly once. The two were separate copies of the same `CASE`, which is the arrangement
         * where a sixth moment reaches the journal's ordering and not the planner's.
         */
        const val SLOT_ORDER: String = FoodLogEntryEntity.SLOT_ORDER
    }
}

/**
 * A proposition of no servings is not a proposition, so a row whose count cannot be read back is
 * dropped rather than served as zero portions — the caller sees no plan for that moment, which
 * is true, instead of a plan that contributes nothing to a total.
 */
fun MealPlanEntryEntity.toDomainOrNull(): MealPlanEntry? {
    val servings = Servings.ofThousandthsOrNull(plannedServingsThousandths.toLong()) ?: return null
    return MealPlanEntry(
        plannedOn = LocalDate.parse(plannedOn),
        slot = MealSlot.fromId(slot),
        recipeId = RecipeId(recipeId),
        plannedServings = servings,
        consumedLogEntryId = consumedLogEntryId?.let(::FoodLogEntryId),
    )
}

fun MealPlanEntry.toEntity(createdAt: Long, updatedAt: Long): MealPlanEntryEntity =
    MealPlanEntryEntity(
        plannedOn = plannedOn.toString(),
        slot = slot.id,
        recipeId = recipeId.value,
        plannedServingsThousandths = plannedServings.thousandths,
        consumedLogEntryId = consumedLogEntryId?.value,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

/** The key as SQLite holds it, so a query never has to reassemble it from a domain object. */
internal val MealPlanKey.storedDate: String get() = plannedOn.toString()

internal val MealPlanKey.storedSlot: String get() = slot.id
