package fr.kristenjestin.mue.data.local.database

import fr.kristenjestin.mue.domain.model.CookedRatio
import fr.kristenjestin.mue.domain.model.Energy
import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.FoodSource
import fr.kristenjestin.mue.domain.model.Macro
import fr.kristenjestin.mue.domain.model.Nutrients
import fr.kristenjestin.mue.domain.model.Quantity
import fr.kristenjestin.mue.domain.model.ReferenceUnit
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val STAMP = 1_772_000_000_000L

private fun food(
    per100: Nutrients = Nutrients.UNKNOWN,
    source: FoodSource = FoodSource.CUSTOM,
    name: String = "Crème fraîche Épaisse",
): Food = Food(
    id = FoodId("11111111-1111-4111-8111-111111111111"),
    name = name,
    source = source,
    per100 = per100,
)

private fun milli(value: Long): Energy = assertNotNull(Energy.ofMilliKcalOrNull(value))

private fun mg(value: Long): Macro = assertNotNull(Macro.ofMilligramsOrNull(value))

class FoodEntityRoundTripTest {

    @Test
    fun `a fully described food survives the round trip unchanged`() {
        val original = Food(
            id = FoodId("22222222-2222-4222-8222-222222222222"),
            name = "Yaourt nature",
            source = FoodSource.OPEN_FOOD_FACTS,
            referenceUnit = ReferenceUnit.MILLILITRE,
            per100 = Nutrients(
                energy = milli(62_700),
                protein = mg(3_800),
                carbs = mg(4_900),
                fat = mg(3_000),
                fibre = mg(0),
            ),
            brand = "Marque Déposée",
            barcode = "3245390110019",
            sourceId = "3245390110019",
            sourceVersion = "v3.6",
            servingLabel = "pot",
            servingSize = Quantity.ofThousandthsOrNull(125_000),
            cookedRatio = CookedRatio.ofThousandthsOrNull(720),
            rawLabel = "Cru",
            cookedLabel = "Cuit",
            imageRef = "images/yaourt.webp",
        )

        assertEquals(original, original.toEntity(STAMP, STAMP).toDomain())
    }

    @Test
    fun `a minimal food survives the round trip unchanged`() {
        val original = food()

        assertEquals(original, original.toEntity(STAMP, STAMP).toDomain())
    }

    @Test
    fun `every source round trips through its stable id`() {
        FoodSource.entries.forEach { source ->
            val stored = food(source = source).toEntity(STAMP, STAMP)

            assertEquals(source.id, stored.source)
            assertEquals(source, stored.toDomain().source)
        }
    }

    @Test
    fun `every reference unit round trips through its stable id`() {
        ReferenceUnit.entries.forEach { unit ->
            val stored = food().copy(referenceUnit = unit).toEntity(STAMP, STAMP)

            assertEquals(unit.id, stored.referenceUnit)
            assertEquals(unit, stored.toDomain().referenceUnit)
        }
    }

    @Test
    fun `the folded columns are written for the search index`() {
        val stored = food(name = "Crème fraîche Épaisse").copy(brand = "Élé").toEntity(STAMP, STAMP)

        assertEquals("creme fraiche epaisse", stored.nameFolded)
        assertEquals("ele", stored.brandFolded)
    }

    @Test
    fun `a food with no brand folds no brand`() {
        assertNull(food().toEntity(STAMP, STAMP).brandFolded)
    }

    @Test
    fun `the timestamps are stored as handed over`() {
        val stored = food().toEntity(createdAt = 10L, updatedAt = 20L)

        assertEquals(10L, stored.createdAt)
        assertEquals(20L, stored.updatedAt)
    }
}

/**
 * PRD_FOOD 9.2: a missing value "reste `null`, est saisissable dans la copie locale et n'est
 * jamais devinée". These are the tests that make that a property of the storage rather than a
 * promise in a document — and they are why no file in this module writes `?: 0`.
 */
class FoodEntityUnknownIsNotZeroTest {

    @Test
    fun `an unknown protein is stored as NULL and not as zero`() {
        val stored = food(per100 = Nutrients(energy = milli(54_200))).toEntity(STAMP, STAMP)

        assertNull(stored.per100.proteinMilligrams)
        assertNull(stored.per100.carbsMilligrams)
        assertNull(stored.per100.fatMilligrams)
        assertNull(stored.per100.fibreMilligrams)
        assertEquals(54_200, stored.per100.energyMilliKcal)
    }

    @Test
    fun `an unknown protein reads back as null and not as zero`() {
        val read = food(per100 = Nutrients(energy = milli(54_200)))
            .toEntity(STAMP, STAMP)
            .toDomain()

        assertNull(read.per100.protein)
        assertEquals(milli(54_200), read.per100.energy)
    }

    @Test
    fun `a known zero protein reads back as zero and not as null`() {
        val read = food(per100 = Nutrients(protein = mg(0))).toEntity(STAMP, STAMP).toDomain()

        assertEquals(Macro.ZERO, read.per100.protein)
        assertEquals(0, read.per100.protein?.milligrams)
    }

    /**
     * The pair in one row: olive oil genuinely has no protein and no carbohydrate, and its fibre
     * is simply not reported. Zero and unknown have to leave the same row telling those two
     * things apart, or a daily total of PRD_FOOD 13.1 would silently invent one of them.
     */
    @Test
    fun `zero and unknown coexist in one row and stay distinguishable`() {
        val original = food(
            per100 = Nutrients(
                energy = milli(899_000),
                protein = mg(0),
                carbs = mg(0),
                fat = mg(99_900),
            ),
        )

        val stored = original.toEntity(STAMP, STAMP)
        assertEquals(0, stored.per100.proteinMilligrams)
        assertEquals(0, stored.per100.carbsMilligrams)
        assertNull(stored.per100.fibreMilligrams)

        val read = stored.toDomain()
        assertEquals(Macro.ZERO, read.per100.protein)
        assertEquals(Macro.ZERO, read.per100.carbs)
        assertNull(read.per100.fibre)
        assertEquals(original, read)
    }

    @Test
    fun `an entirely unknown food is five NULLs, not five zeroes`() {
        val stored = food(per100 = Nutrients.UNKNOWN).toEntity(STAMP, STAMP)

        assertNull(stored.per100.energyMilliKcal)
        assertNull(stored.per100.proteinMilligrams)
        assertNull(stored.per100.carbsMilligrams)
        assertNull(stored.per100.fatMilligrams)
        assertNull(stored.per100.fibreMilligrams)
        assertTrue(stored.toDomain().per100.isUnknown)
    }

    @Test
    fun `an entirely known zero food is five zeroes, not five NULLs`() {
        val stored = food(per100 = Nutrients.ZERO).toEntity(STAMP, STAMP)

        assertEquals(listOf(0, 0, 0, 0, 0), stored.per100.toList())
        assertTrue(stored.toDomain().per100.isFullyKnown)
        assertEquals(Nutrients.ZERO, stored.toDomain().per100)
    }

    /**
     * Room hands back whatever the column holds, including a value no unit accepts. Reading it as
     * unknown is the only honest answer: clamping would put a number in front of the user that
     * nobody entered, and `?: 0` would put the most misleading one of all.
     */
    @Test
    fun `a stored value outside its range reads back as unknown rather than clamped`() {
        val read = NutrientColumns(
            energyMilliKcal = -1,
            proteinMilligrams = -5,
            carbsMilligrams = 12_000,
        ).toDomain()

        assertNull(read.energy)
        assertNull(read.protein)
        assertEquals(mg(12_000), read.carbs)
    }
}

class FoodEntityOptionalColumnTest {

    @Test
    fun `a serving size outside the usual range reads back as absent`() {
        val read = food().toEntity(STAMP, STAMP).copy(servingThousandths = -3).toDomain()

        assertNull(read.servingSize)
    }

    @Test
    fun `a cooked ratio outside its range reads back as absent`() {
        val read = food().toEntity(STAMP, STAMP).copy(cookedRatioThousandths = 99_999).toDomain()

        assertNull(read.cookedRatio)
    }

    @Test
    fun `a usual serving needs both its label and its size`() {
        val labelOnly = food().toEntity(STAMP, STAMP).copy(servingLabel = "pot").toDomain()

        assertEquals("pot", labelOnly.servingLabel)
        assertNull(labelOnly.servingSize)
        assertTrue(!labelOnly.hasUsualServing)
    }

    @Test
    fun `the default raw and cooked labels survive storage`() {
        val read = food().toEntity(STAMP, STAMP).toDomain()

        assertEquals(Food.DEFAULT_RAW_LABEL, read.rawLabel)
        assertEquals(Food.DEFAULT_COOKED_LABEL, read.cookedLabel)
    }
}

private fun NutrientColumns.toList(): List<Int?> =
    listOf(energyMilliKcal, proteinMilligrams, carbsMilligrams, fatMilligrams, fibreMilligrams)
