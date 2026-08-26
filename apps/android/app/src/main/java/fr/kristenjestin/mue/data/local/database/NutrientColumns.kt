package fr.kristenjestin.mue.data.local.database

import androidx.room.ColumnInfo
import fr.kristenjestin.mue.domain.model.Energy
import fr.kristenjestin.mue.domain.model.Macro
import fr.kristenjestin.mue.domain.model.Nutrients

/**
 * The five nutrients, as five **nullable integer** columns, embedded by `food` and by
 * `food_log_entry`.
 *
 * Two decisions are frozen into this shape, and neither is stylistic.
 *
 * **Integers.** Energy is whole milli-kilocalories and each macro whole milligrams, exactly as
 * `Energy` and `Macro` hold them. No `REAL` column exists anywhere in this database — PRD_FOOD
 * 13.1 makes a daily total the strict sum of its lines, and a float would let two devices
 * disagree on the last digit of the same day.
 *
 * **Nullable.** PRD_FOOD 9.2 accepts an incomplete product sheet, and its missing values "restent
 * `null`, sont saisissables dans la copie locale et ne sont jamais devinées". A column defaulted
 * to `0` would say "this yoghurt contains no protein", which is a claim, not an absence — and it
 * is a claim the totals of 13.1 would then add up. So `null` travels all the way down to SQLite
 * and all the way back, and nothing in this module ever writes `?: 0` to make it disappear.
 *
 * The read direction is deliberately `?.let { …OrNull(…) }` rather than a non-null cast: a value
 * outside the range its unit accepts is unknown too, never clamped to a bound the user never
 * entered.
 */
data class NutrientColumns(
    @ColumnInfo(name = "energy_milli_kcal")
    val energyMilliKcal: Int? = null,

    @ColumnInfo(name = "protein_milligrams")
    val proteinMilligrams: Int? = null,

    @ColumnInfo(name = "carbs_milligrams")
    val carbsMilligrams: Int? = null,

    @ColumnInfo(name = "fat_milligrams")
    val fatMilligrams: Int? = null,

    @ColumnInfo(name = "fibre_milligrams")
    val fibreMilligrams: Int? = null,
)

fun NutrientColumns.toDomain(): Nutrients = Nutrients(
    energy = energyMilliKcal?.let { Energy.ofMilliKcalOrNull(it.toLong()) },
    protein = proteinMilligrams?.let { Macro.ofMilligramsOrNull(it.toLong()) },
    carbs = carbsMilligrams?.let { Macro.ofMilligramsOrNull(it.toLong()) },
    fat = fatMilligrams?.let { Macro.ofMilligramsOrNull(it.toLong()) },
    fibre = fibreMilligrams?.let { Macro.ofMilligramsOrNull(it.toLong()) },
)

fun Nutrients.toColumns(): NutrientColumns = NutrientColumns(
    energyMilliKcal = energy?.milliKcal,
    proteinMilligrams = protein?.milligrams,
    carbsMilligrams = carbs?.milligrams,
    fatMilligrams = fat?.milligrams,
    fibreMilligrams = fibre?.milligrams,
)
