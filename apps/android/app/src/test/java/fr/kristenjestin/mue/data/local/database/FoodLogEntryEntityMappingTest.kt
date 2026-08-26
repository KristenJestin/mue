package fr.kristenjestin.mue.data.local.database

import fr.kristenjestin.mue.domain.model.Energy
import fr.kristenjestin.mue.domain.model.Estimation
import fr.kristenjestin.mue.domain.model.FoodLogEntry
import fr.kristenjestin.mue.domain.model.FoodLogEntryId
import fr.kristenjestin.mue.domain.model.FoodLogKind
import fr.kristenjestin.mue.domain.model.LoggedAmount
import fr.kristenjestin.mue.domain.model.Macro
import fr.kristenjestin.mue.domain.model.MealPlanKey
import fr.kristenjestin.mue.domain.model.MealSlot
import fr.kristenjestin.mue.domain.model.Nutrients
import fr.kristenjestin.mue.domain.model.Quantity
import fr.kristenjestin.mue.domain.model.QuantityUnit
import fr.kristenjestin.mue.domain.model.ReferenceUnit
import fr.kristenjestin.mue.domain.model.Servings
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val STAMP = 1_772_000_000_000L

private fun entry(
    amount: LoggedAmount = LoggedAmount.Unmeasured,
    nutrients: Nutrients = Nutrients.UNKNOWN,
    kind: FoodLogKind = FoodLogKind.QUICK,
): FoodLogEntry = FoodLogEntry(
    id = FoodLogEntryId("33333333-3333-4333-8333-333333333333"),
    consumedOn = LocalDate.of(2026, 8, 25),
    consumedAt = LocalTime.of(13, 5),
    slot = MealSlot.LUNCH,
    kind = kind,
    title = "Déjeuner",
    amount = amount,
    nutrients = nutrients,
    estimation = Estimation.APPROXIMATE,
)

private fun grams(value: Long): Quantity = assertNotNull(Quantity.ofThousandthsOrNull(value))

private fun servings(value: Long): Servings = assertNotNull(Servings.ofThousandthsOrNull(value))

class FoodLogEntryRoundTripTest {

    @Test
    fun `a fully described line survives the round trip unchanged`() {
        val original = FoodLogEntry(
            id = FoodLogEntryId("44444444-4444-4444-8444-444444444444"),
            consumedOn = LocalDate.of(2024, 2, 29),
            consumedAt = LocalTime.of(20, 15, 30),
            slot = MealSlot.DINNER,
            kind = FoodLogKind.FOOD,
            title = "Blanc de poulet",
            amount = LoggedAmount.Measured(grams(180_000), ReferenceUnit.GRAM),
            nutrients = Nutrients(
                energy = assertNotNull(Energy.ofMilliKcalOrNull(217_800)),
                protein = assertNotNull(Macro.ofMilligramsOrNull(40_680)),
            ),
            estimation = Estimation.MEASURED,
            sourceRef = "55555555-5555-4555-8555-555555555555",
            amountLabel = "180 g",
            portions = servings(1_500),
            weighedCooked = true,
            fromPlan = MealPlanKey(LocalDate.of(2024, 2, 29), MealSlot.DINNER),
        )

        assertEquals(original, original.toEntity(STAMP, STAMP).toDomain())
    }

    @Test
    fun `a bare quick add survives the round trip unchanged`() {
        val original = entry()

        assertEquals(original, original.toEntity(STAMP, STAMP).toDomain())
    }

    @Test
    fun `every moment round trips through its stable id`() {
        MealSlot.entries.forEach { slot ->
            val stored = entry().copy(slot = slot).toEntity(STAMP, STAMP)

            assertEquals(slot.id, stored.slot)
            assertEquals(slot, stored.toDomain().slot)
        }
    }

    @Test
    fun `every kind and every estimation round trips through its stable id`() {
        FoodLogKind.entries.forEach { kind ->
            assertEquals(kind, entry(kind = kind).toEntity(STAMP, STAMP).toDomain().kind)
        }
        Estimation.entries.forEach { estimation ->
            val stored = entry().copy(estimation = estimation).toEntity(STAMP, STAMP)
            assertEquals(estimation, stored.toDomain().estimation)
        }
    }

    @Test
    fun `a time with seconds keeps its seconds`() {
        val original = entry().copy(consumedAt = LocalTime.of(7, 45, 12))

        assertEquals("07:45:12", original.toEntity(STAMP, STAMP).consumedAt)
        assertEquals(LocalTime.of(7, 45, 12), original.toEntity(STAMP, STAMP).toDomain().consumedAt)
    }

    /** The line is its own aggregate (PRD_FOOD 21.2), so the plan it came from is only a key. */
    @Test
    fun `a line with no plan stores neither half of the plan key`() {
        val stored = entry().toEntity(STAMP, STAMP)

        assertNull(stored.plannedOn)
        assertNull(stored.planSlot)
        assertNull(stored.toDomain().fromPlan)
    }

    @Test
    fun `a half written plan key reads back as no plan at all`() {
        val stored = entry().toEntity(STAMP, STAMP).copy(plannedOn = "2026-08-25")

        assertNull(stored.toDomain().fromPlan)
    }
}

/**
 * PRD_FOOD 8.4 stores one quantity and one unit; the three shapes of `LoggedAmount` are what
 * that pair means. These prove the pair is enough, both ways.
 */
class FoodLogEntryAmountTest {

    @Test
    fun `a weighed amount stores its unit and its thousandths`() {
        val original = entry(LoggedAmount.Measured(grams(180_500), ReferenceUnit.GRAM))
        val stored = original.toEntity(STAMP, STAMP)

        assertEquals(180_500, stored.quantityThousandths)
        assertEquals(QuantityUnit.GRAM.id, stored.quantityUnit)
        assertEquals(original.amount, stored.toDomain().amount)
    }

    @Test
    fun `a millilitre amount keeps its unit and never becomes grams`() {
        val original = entry(LoggedAmount.Measured(grams(250_000), ReferenceUnit.MILLILITRE))
        val stored = original.toEntity(STAMP, STAMP)

        assertEquals(QuantityUnit.MILLILITRE.id, stored.quantityUnit)
        assertEquals(original.amount, stored.toDomain().amount)
    }

    @Test
    fun `a portioned amount stores servings and reads back as servings`() {
        val original = entry(LoggedAmount.Portioned(servings(1_750)))
        val stored = original.toEntity(STAMP, STAMP)

        assertEquals(1_750, stored.quantityThousandths)
        assertEquals(QuantityUnit.SERVING.id, stored.quantityUnit)
        assertEquals(LoggedAmount.Portioned(servings(1_750)), stored.toDomain().amount)
    }

    @Test
    fun `an unmeasured amount stores no unit and no quantity`() {
        val stored = entry(LoggedAmount.Unmeasured).toEntity(STAMP, STAMP)

        assertNull(stored.quantityUnit)
        assertNull(stored.quantityThousandths)
        assertEquals(LoggedAmount.Unmeasured, stored.toDomain().amount)
    }

    @Test
    fun `a unit with no readable quantity degrades to unmeasured, never to zero`() {
        val stored = entry(LoggedAmount.Measured(grams(120_000), ReferenceUnit.GRAM))
            .toEntity(STAMP, STAMP)
            .copy(quantityThousandths = null)

        assertEquals(LoggedAmount.Unmeasured, stored.toDomain().amount)
        assertNull(stored.toDomain().measuredQuantity)
    }

    @Test
    fun `a serving unit with no readable count degrades to unmeasured`() {
        val stored = entry(LoggedAmount.Portioned(servings(1_000)))
            .toEntity(STAMP, STAMP)
            .copy(quantityThousandths = 0)

        assertEquals(LoggedAmount.Unmeasured, stored.toDomain().amount)
    }

    @Test
    fun `the usual portions count is stored beside the amount, not instead of it`() {
        val original = entry(LoggedAmount.Measured(grams(150_000), ReferenceUnit.GRAM))
            .copy(portions = servings(1_000))
        val stored = original.toEntity(STAMP, STAMP)

        assertEquals(150_000, stored.quantityThousandths)
        assertEquals(1_000, stored.portionsThousandths)
        assertEquals(original, stored.toDomain())
    }
}

class FoodLogEntryUnknownIsNotZeroTest {

    @Test
    fun `an unlogged macro is NULL in the column and null on the way back`() {
        val original = entry(
            nutrients = Nutrients(energy = assertNotNull(Energy.ofMilliKcalOrNull(320_000))),
        )
        val stored = original.toEntity(STAMP, STAMP)

        assertNull(stored.nutrients.proteinMilligrams)
        assertNull(stored.toDomain().nutrients.protein)
        assertEquals(320_000, stored.nutrients.energyMilliKcal)
    }

    @Test
    fun `a line with no energy at all does not count towards the energy average`() {
        val read = entry().toEntity(STAMP, STAMP).toDomain()

        assertTrue(!read.countsTowardsEnergyAverage)
        assertTrue(read.nutrients.isUnknown)
    }

    @Test
    fun `a zero energy line does count, because zero is a measurement`() {
        val original = entry(nutrients = Nutrients(energy = Energy.ZERO))
        val read = original.toEntity(STAMP, STAMP).toDomain()

        assertEquals(0, original.toEntity(STAMP, STAMP).nutrients.energyMilliKcal)
        assertTrue(read.countsTowardsEnergyAverage)
    }
}
