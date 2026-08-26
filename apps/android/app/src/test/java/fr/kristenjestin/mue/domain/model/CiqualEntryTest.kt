package fr.kristenjestin.mue.domain.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val apple = CiqualEntry(
    code = "13032",
    name = "Apple, raw",
    energyMilliKcal = 89_000,
    proteinMilligrams = 300,
    carbsMilligrams = 11_600,
    fatMilligrams = 200,
    fibreMilligrams = 2_000,
    servingLabel = "apple",
    servingThousandths = 150_000,
)

class CiqualCatalogueSerialisationTest {

    @Test
    fun `a catalogue survives a round trip through the file the generator writes`() {
        val catalogue = CiqualCatalogue(version = "2025.1", entries = listOf(apple))
        val json = CiqualCatalogue.toJson(catalogue)
        assertEquals(catalogue, CiqualCatalogue.fromJsonOrNull(json))
    }

    @Test
    fun `an absent constituent decodes to unknown and an explicit zero to a known zero`() {
        val json = """
            {"version":"2025.1","entries":[
              {"code":"1","name":"Water","energyMilliKcal":0,"fatMilligrams":0}
            ]}
        """.trimIndent()
        val entry = assertNotNull(CiqualCatalogue.fromJsonOrNull(json)).entries.single()
        assertEquals(0, entry.energyMilliKcal)
        assertEquals(0, entry.fatMilligrams)
        assertNull(entry.proteinMilligrams)
        assertNull(entry.carbsMilligrams)
        assertNull(entry.fibreMilligrams)
    }

    @Test
    fun `that distinction survives all the way into the catalogue entry`() {
        val json = """
            {"version":"v","entries":[{"code":"1","name":"Water","energyMilliKcal":0}]}
        """.trimIndent()
        val entry = assertNotNull(CiqualCatalogue.fromJsonOrNull(json)).entries.single()
        val water = assertNotNull(entry.toFoodOrNull("v"))
        assertEquals(Energy.ZERO, water.per100.energy)
        assertNull(water.per100.protein)
        assertFalse(water.per100.isUnknown)
    }

    @Test
    fun `a regenerated file carrying a field this build predates still decodes`() {
        val json = """
            {"version":"2026.1","generatedAt":"2026-01-01","entries":[
              {"code":"1","name":"Rice","energyMilliKcal":350000,"waterGrams":12}
            ]}
        """.trimIndent()
        val catalogue = assertNotNull(CiqualCatalogue.fromJsonOrNull(json))
        assertEquals("2026.1", catalogue.version)
        assertEquals(350_000, catalogue.entries.single().energyMilliKcal)
    }

    @Test
    fun `an unreadable resource is an empty catalogue rather than a crash`() {
        assertNull(CiqualCatalogue.fromJsonOrNull(""))
        assertNull(CiqualCatalogue.fromJsonOrNull("not json"))
        assertNull(CiqualCatalogue.fromJsonOrNull("{}"))
        assertNull(CiqualCatalogue.fromJsonOrNull("""{"version":"v","entries":[{"name":"x"}]}"""))
        assertNull(CiqualCatalogue.fromJsonOrNull("""{"version":"v","entries":"nope"}"""))
    }

    @Test
    fun `a catalogue with no entry at all is legal and empty`() {
        val catalogue = assertNotNull(CiqualCatalogue.fromJsonOrNull("""{"version":"v"}"""))
        assertEquals("v", catalogue.version)
        assertTrue(catalogue.entries.isEmpty())
    }

    @Test
    fun `every number in the file is an integer in the module's canonical unit`() {
        val json = CiqualCatalogue.toJson(CiqualCatalogue("v", listOf(apple)))
        assertTrue(json.contains("\"energyMilliKcal\":89000"))
        assertTrue(json.contains("\"proteinMilligrams\":300"))
        assertFalse(json.contains("."))
    }
}

class CiqualEntryToFoodTest {

    @Test
    fun `a well-formed row becomes a read-only catalogue entry carrying its version`() {
        val food = assertNotNull(apple.toFoodOrNull("2025.1", FoodId("seeded")))
        assertEquals(FoodId("seeded"), food.id)
        assertEquals("Apple, raw", food.name)
        assertEquals(FoodSource.CIQUAL, food.source)
        assertTrue(food.isReadOnly)
        assertEquals("13032", food.sourceId)
        assertEquals("2025.1", food.sourceVersion)
        assertEquals(ReferenceUnit.GRAM, food.referenceUnit)
    }

    @Test
    fun `the five constituents of PRD_FOOD 9-1 cross into the bundle unchanged`() {
        val food = assertNotNull(apple.toFoodOrNull("v"))
        assertEquals(89_000, food.per100.energy?.milliKcal)
        assertEquals(300, food.per100.protein?.milligrams)
        assertEquals(11_600, food.per100.carbs?.milligrams)
        assertEquals(200, food.per100.fat?.milligrams)
        assertEquals(2_000, food.per100.fibre?.milligrams)
        assertTrue(food.per100.isFullyKnown)
    }

    @Test
    fun `a usual serving crosses whole, or not at all`() {
        val food = assertNotNull(apple.toFoodOrNull("v"))
        assertEquals("apple", food.servingLabel)
        assertEquals(150_000, food.servingSize?.thousandths)
        assertTrue(food.hasUsualServing)
        assertNull(apple.copy(servingLabel = null, servingThousandths = null).toFoodOrNull("v")?.servingLabel)
        assertNull(apple.copy(servingLabel = null).toFoodOrNull("v"))
        assertNull(apple.copy(servingThousandths = null).toFoodOrNull("v"))
        assertNull(apple.copy(servingLabel = "  ").toFoodOrNull("v"))
    }

    @Test
    fun `PRD_FOOD 8-6 carries the ratio Ciqual's raw and cooked pair produced`() {
        val pasta = apple.copy(code = "9640", name = "Pasta, dry", cookedRatioThousandths = 2_300)
        val food = assertNotNull(pasta.toFoodOrNull("v"))
        assertEquals(2_300, food.cookedRatio?.thousandths)
        assertEquals(2.3, assertNotNull(food.cookedRatio).ratio)
        assertTrue(food.hasCookedState)
        assertFalse(assertNotNull(apple.toFoodOrNull("v")).hasCookedState)
    }

    @Test
    fun `a liquid says so, and an unreadable unit is a gram`() {
        assertEquals(
            ReferenceUnit.MILLILITRE,
            assertNotNull(apple.copy(unit = "millilitre").toFoodOrNull("v")).referenceUnit,
        )
        assertEquals(
            ReferenceUnit.GRAM,
            assertNotNull(apple.copy(unit = "litre").toFoodOrNull("v")).referenceUnit,
        )
    }

    @Test
    fun `a name is trimmed, and PRD_FOOD 15's bounds reject the rest`() {
        assertEquals("Rice", assertNotNull(apple.copy(name = "  Rice  ").toFoodOrNull("v")).name)
        assertNull(apple.copy(name = "").toFoodOrNull("v"))
        assertNull(apple.copy(name = "   ").toFoodOrNull("v"))
        assertNull(apple.copy(name = "x".repeat(81)).toFoodOrNull("v"))
        assertNotNull(apple.copy(name = "x".repeat(80)).toFoodOrNull("v"))
    }

    @Test
    fun `a row with no source code is not a Ciqual row`() {
        assertNull(apple.copy(code = "").toFoodOrNull("v"))
        assertNull(apple.copy(code = "   ").toFoodOrNull("v"))
    }

    @Test
    fun `an energy outside PRD_FOOD 15's per-100 bounds rejects the whole row`() {
        assertNotNull(apple.copy(energyMilliKcal = 900_000).toFoodOrNull("v"))
        assertNull(apple.copy(energyMilliKcal = 900_001).toFoodOrNull("v"))
        assertNull(apple.copy(energyMilliKcal = -1).toFoodOrNull("v"))
        assertNotNull(apple.copy(energyMilliKcal = 0).toFoodOrNull("v"))
    }

    @Test
    fun `a macronutrient outside its per-100 bounds rejects the whole row`() {
        assertNotNull(apple.copy(proteinMilligrams = 100_000).toFoodOrNull("v"))
        assertNull(apple.copy(proteinMilligrams = 100_001).toFoodOrNull("v"))
        assertNull(apple.copy(carbsMilligrams = -1).toFoodOrNull("v"))
        assertNull(apple.copy(fatMilligrams = Int.MAX_VALUE).toFoodOrNull("v"))
        assertNull(apple.copy(fibreMilligrams = 100_001).toFoodOrNull("v"))
    }

    @Test
    fun `a ratio outside PRD_FOOD 15's bounds rejects the whole row`() {
        assertNotNull(apple.copy(cookedRatioThousandths = 300).toFoodOrNull("v"))
        assertNotNull(apple.copy(cookedRatioThousandths = 5_000).toFoodOrNull("v"))
        assertNull(apple.copy(cookedRatioThousandths = 299).toFoodOrNull("v"))
        assertNull(apple.copy(cookedRatioThousandths = 5_001).toFoodOrNull("v"))
        assertNull(apple.copy(cookedRatioThousandths = 0).toFoodOrNull("v"))
    }

    @Test
    fun `a serving size outside PRD_FOOD 15's bounds rejects the whole row`() {
        assertNotNull(apple.copy(servingThousandths = 1_000).toFoodOrNull("v"))
        assertNotNull(apple.copy(servingThousandths = 2_000_000).toFoodOrNull("v"))
        assertNull(apple.copy(servingThousandths = 999).toFoodOrNull("v"))
        assertNull(apple.copy(servingThousandths = 2_000_001).toFoodOrNull("v"))
    }

    @Test
    fun `a row documenting nothing at all is still a usable catalogue entry`() {
        val bare = CiqualEntry(code = "1", name = "Something")
        val food = assertNotNull(bare.toFoodOrNull("v"))
        assertTrue(food.per100.isUnknown)
        assertFalse(food.hasUsualServing)
        assertFalse(food.hasCookedState)
    }

    @Test
    fun `the hundred-gram macronutrient sum is a form rule and never drops a shipped row`() {
        val oil = CiqualEntry(
            code = "17270",
            name = "Olive oil",
            energyMilliKcal = 899_000,
            proteinMilligrams = 0,
            carbsMilligrams = 0,
            fatMilligrams = 100_000,
        )
        val food = assertNotNull(oil.toFoodOrNull("v"))
        assertTrue(food.per100.isMacroSumWithinPer100Limit)

        val rounded = oil.copy(proteinMilligrams = 500)
        val stillSeeded = assertNotNull(rounded.toFoodOrNull("v"))
        assertFalse(stillSeeded.per100.isMacroSumWithinPer100Limit)
    }

    @Test
    fun `each seeded row gets its own identity when none is supplied`() {
        val first = assertNotNull(apple.toFoodOrNull("v"))
        val second = assertNotNull(apple.toFoodOrNull("v"))
        assertFalse(first.id == second.id)
        assertEquals(first.sourceId, second.sourceId)
    }
}
