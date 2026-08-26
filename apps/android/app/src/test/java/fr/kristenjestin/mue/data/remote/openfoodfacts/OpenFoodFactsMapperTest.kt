package fr.kristenjestin.mue.data.remote.openfoodfacts

import fr.kristenjestin.mue.domain.model.Energy
import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.FoodSource
import fr.kristenjestin.mue.domain.model.Macro
import fr.kristenjestin.mue.domain.model.Nutrients
import fr.kristenjestin.mue.domain.model.ReferenceUnit
import fr.kristenjestin.mue.domain.repository.LookupFailure
import fr.kristenjestin.mue.domain.repository.ProductLookupResult
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun found(fixture: String, barcode: String, id: FoodId = FoodId("fixed")): Food {
    val result = OpenFoodFactsMapper.read(barcode, OpenFoodFactsFixtures.read(fixture), id)
    return assertIs<ProductLookupResult.Found>(result).food
}

/** The recorded Nutella card, mapped field by field. */
class OpenFoodFactsFoundTest {

    private val food = found(OpenFoodFactsFixtures.FOUND, OpenFoodFactsFixtures.FOUND_BARCODE)

    @Test
    fun `the card becomes the catalogue candidate PRD_FOOD 9-2 describes`() {
        assertEquals("Nutella", food.name)
        assertEquals(FoodSource.OPEN_FOOD_FACTS, food.source)
        assertEquals(ReferenceUnit.GRAM, food.referenceUnit)
        assertEquals("3017620422003", food.barcode)
    }

    /** PRD_FOOD 9.2: the copy is editable and synchronised; only Ciqual is read-only. */
    @Test
    fun `the candidate shares the life cycle of a personal food`() {
        assertFalse(food.isReadOnly)
        assertTrue(food.source.isSynchronised)
    }

    @Test
    fun `brands is a list upstream and one brand here`() {
        assertEquals("Nutella", food.brand)
    }

    @Test
    fun `PRD_FOOD 14 keeps an Open Food Facts image as a remote url`() {
        assertEquals(
            "https://images.openfoodfacts.org/images/products/301/762/042/2003/front_en.879.400.jpg",
            food.imageRef,
        )
    }

    @Test
    fun `the four documented nutrients are the ones the manufacturer states`() {
        assertEquals(Energy.ofMilliKcalOrNull(539_000L), food.per100.energy)
        assertEquals(Macro.ofMilligramsOrNull(6_300L), food.per100.protein)
        assertEquals(Macro.ofMilligramsOrNull(57_500L), food.per100.carbs)
        assertEquals(Macro.ofMilligramsOrNull(30_900L), food.per100.fat)
    }

    /**
     * The rule this module exists for, on the most scanned card in the database.
     *
     * Open Food Facts does document a fibre figure for Nutella — 3.675 g — and marks it
     * `"source": "estimate"`, computed from the ingredient list. PRD_FOOD 17 says missing values
     * are "jamais estimées", so Mue has no fibre for Nutella and says so.
     */
    @Test
    fun `an estimated nutrient is unknown, not borrowed`() {
        assertNull(food.per100.fibre)
        assertNotEquals(Macro.ZERO, food.per100.fibre)
    }

    @Test
    fun `a card with no usual portion offers no serving counter`() {
        assertNull(food.servingLabel)
        assertNull(food.servingSize)
        assertFalse(food.hasUsualServing)
    }

    @Test
    fun `a known energy coexists with an unknown fibre`() {
        assertFalse(food.per100.isUnknown)
        assertFalse(food.per100.isFullyKnown)
    }

    @Test
    fun `PRD_FOOD 15 accepts the card, since the known macros stay under 100 g`() {
        assertTrue(food.per100.isMacroSumWithinPer100Limit)
    }
}

/**
 * The incomplete record, which is the ordinary one.
 *
 * The recorded Marmite card holds energy, protein and carbohydrate and has **no `fat` entry and
 * no `fiber` entry at all** — not a zero, not an empty string: the keys are absent. PRD_FOOD 9.2
 * calls that nominal, PRD_FOOD 13.1 forbids reading it as `0`, and PRD_FOOD 17 shows it as `—`.
 */
class OpenFoodFactsIncompleteRecordTest {

    private val food =
        found(OpenFoodFactsFixtures.INCOMPLETE, OpenFoodFactsFixtures.INCOMPLETE_BARCODE)

    @Test
    fun `an absent nutrient maps to null`() {
        assertNull(food.per100.fat)
        assertNull(food.per100.fibre)
    }

    /** The same two assertions written the way a `?: 0` would break them. */
    @Test
    fun `an absent nutrient is never a zero`() {
        assertNotEquals(Macro.ZERO, food.per100.fat)
        assertNotEquals(Macro.ZERO, food.per100.fibre)
        assertNotEquals(Macro.ofMilligramsOrNull(0L), food.per100.fat)
        assertNotEquals(Macro.ofMilligramsOrNull(0L), food.per100.fibre)
    }

    @Test
    fun `what the card does document survives intact`() {
        assertEquals("Yeast Extract", food.name)
        assertEquals("Marmite", food.brand)
        assertEquals(Energy.ofMilliKcalOrNull(260_000L), food.per100.energy)
        assertEquals(Macro.ofMilligramsOrNull(34_000L), food.per100.protein)
        assertEquals(Macro.ofMilligramsOrNull(30_000L), food.per100.carbs)
    }

    @Test
    fun `an incomplete card is a food, not a failure`() {
        assertFalse(food.per100.isUnknown)
        assertFalse(food.per100.isFullyKnown)
    }

    @Test
    fun `a usual portion arrives with both of its halves`() {
        assertEquals("8 gram", food.servingLabel)
        assertEquals(8.0, food.servingSize?.amount)
        assertTrue(food.hasUsualServing)
    }

    /** PRD_FOOD 13.1: one unknown contribution makes only its own metric unknown. */
    @Test
    fun `the missing metrics stay missing through a strict sum`() {
        val total = Nutrients.strictSum(listOf(food.per100, food.per100))

        assertEquals(Energy.ofMilliKcalOrNull(520_000L), total.energy)
        assertEquals(Macro.ofMilligramsOrNull(68_000L), total.protein)
        assertNull(total.fat)
        assertNull(total.fibre)
    }
}

/**
 * Zero and unknown, side by side in one recorded card.
 *
 * Coca-Cola documents `0` protein and `0` fat and documents no fibre at all. If the two ever
 * collapsed into one another this fixture would be the first to say so.
 */
class OpenFoodFactsKnownZeroTest {

    private val food =
        found(OpenFoodFactsFixtures.KNOWN_ZEROS, OpenFoodFactsFixtures.KNOWN_ZEROS_BARCODE)

    @Test
    fun `a documented zero is a known zero`() {
        assertEquals(Macro.ZERO, food.per100.protein)
        assertEquals(Macro.ZERO, food.per100.fat)
    }

    @Test
    fun `an absent nutrient on the same card is unknown`() {
        assertNull(food.per100.fibre)
    }

    @Test
    fun `the values that need rounding land on the stored thousandth`() {
        // 42.1212121212121 kcal and 10.6060606060606 g, per 100 g, as recorded.
        assertEquals(42_121, food.per100.energy?.milliKcal)
        assertEquals(10_606, food.per100.carbs?.milligrams)
    }

    @Test
    fun `a 330 g portion becomes a usual serving`() {
        assertEquals("330 g", food.servingLabel)
        assertEquals(330.0, food.servingSize?.amount)
    }
}

/**
 * Kilojoules.
 *
 * European labels quote them, Mue stores kilocalories, and the two differ by 4.184 — which is
 * exactly the factor a careless mapper multiplies a whole catalogue by.
 */
class OpenFoodFactsEnergyTest {

    /**
     * The trap, on a real card. Nutella's generic `energy` says 2 252 with `"unit": "kJ"`, and
     * its label says 539 kcal. Reading `energy` at face value would store 2 252 kcal.
     */
    @Test
    fun `the generic energy field is never read as kilocalories`() {
        val food = found(OpenFoodFactsFixtures.FOUND, OpenFoodFactsFixtures.FOUND_BARCODE)

        assertEquals(539_000, food.per100.energy?.milliKcal)
        assertNotEquals(Energy.ofMilliKcalOrNull(2_252_000L), food.per100.energy)
    }

    /**
     * The recorded card with its `energy-kcal` entry removed, leaving 1 087 kJ.
     * 1 087 / 4.184 = 259.799… kcal, stored to the thousandth.
     */
    @Test
    fun `a card that states only kilojoules is converted, not refused`() {
        val food = found(
            OpenFoodFactsFixtures.KILOJOULES_ONLY,
            OpenFoodFactsFixtures.INCOMPLETE_BARCODE,
        )

        assertEquals(259_799, food.per100.energy?.milliKcal)
    }

    @Test
    fun `removing the kilocalorie entry changes the energy and nothing else`() {
        val kilocalories =
            found(OpenFoodFactsFixtures.INCOMPLETE, OpenFoodFactsFixtures.INCOMPLETE_BARCODE)
        val kilojoules =
            found(OpenFoodFactsFixtures.KILOJOULES_ONLY, OpenFoodFactsFixtures.INCOMPLETE_BARCODE)

        assertEquals(kilocalories.copy(per100 = Nutrients.UNKNOWN), kilojoules.copy(per100 = Nutrients.UNKNOWN))
        assertEquals(kilocalories.per100.protein, kilojoules.per100.protein)
        assertEquals(kilocalories.per100.carbs, kilojoules.per100.carbs)
        assertNotEquals(kilocalories.per100.energy, kilojoules.per100.energy)
    }

    @Test
    fun `the unit decides, and an unreadable one yields no energy`() {
        fun energyOf(unit: String?) = OpenFoodFactsMapper.kilocaloriesOrNull(
            OpenFoodFactsNutrient(value = JsonPrimitive(100.0), unit = unit, source = "packaging"),
        )

        assertEquals(Energy.ofMilliKcalOrNull(100_000L), energyOf("kcal"))
        assertEquals(Energy.ofMilliKcalOrNull(100_000L), energyOf("KCAL"))
        assertEquals(Energy.ofMilliKcalOrNull(23_901L), energyOf("kJ"))
        assertEquals(Energy.ofMilliKcalOrNull(23_901L), energyOf("kj"))
        assertNull(energyOf(null))
        assertNull(energyOf(""))
        assertNull(energyOf("g"))
        assertNull(energyOf("% vol"))
    }

    @Test
    fun `an estimated energy is skipped and the next key is tried`() {
        val set = OpenFoodFactsNutrientSet(
            per = "100g",
            preparation = "as_sold",
            nutrients = mapOf(
                "energy-kcal" to OpenFoodFactsNutrient(
                    value = JsonPrimitive(500.0),
                    unit = "kcal",
                    source = "estimate",
                ),
                "energy-kj" to OpenFoodFactsNutrient(
                    value = JsonPrimitive(418.4),
                    unit = "kJ",
                    source = "packaging",
                ),
            ),
        )

        assertEquals(Energy.ofMilliKcalOrNull(100_000L), OpenFoodFactsMapper.nutrientsOf(set).energy)
    }

    @Test
    fun `an energy above PRD_FOOD 15's ceiling is unknown rather than clamped`() {
        val nutrient =
            OpenFoodFactsNutrient(value = JsonPrimitive(2252.0), unit = "kcal", source = "packaging")

        assertNull(OpenFoodFactsMapper.kilocaloriesOrNull(nutrient))
    }

    @Test
    fun `the conversion factor is the one the labelling rules use`() {
        assertEquals(4.184, OpenFoodFactsMapper.KILOJOULES_PER_KILOCALORIE, 0.0)
    }

    @Test
    fun `kilocalories are preferred to kilojoules, and both to the generic field`() {
        assertEquals(listOf("energy-kcal", "energy-kj", "energy"), OpenFoodFactsMapper.ENERGY_KEYS)
    }
}

/** What a macronutrient has to be to become a number, and what it becomes otherwise. */
class OpenFoodFactsMacroTest {

    private fun macro(
        value: Double? = 12.5,
        unit: String? = "g",
        source: String? = "packaging",
    ) = OpenFoodFactsMapper.macroOrNull(
        OpenFoodFactsNutrient(
            value = value?.let { JsonPrimitive(it) },
            unit = unit,
            source = source,
        ),
    )

    @Test
    fun `grams become milligrams`() {
        assertEquals(Macro.ofMilligramsOrNull(12_500L), macro())
        assertEquals(Macro.ofMilligramsOrNull(12_500L), macro(unit = "G"))
        assertEquals(Macro.ofMilligramsOrNull(12_500L), macro(unit = "grams"))
    }

    @Test
    fun `an absent unit is grams, because that is the only unit PRD_FOOD 15 quotes`() {
        assertEquals(Macro.ofMilligramsOrNull(12_500L), macro(unit = null))
        assertEquals(Macro.ofMilligramsOrNull(12_500L), macro(unit = " "))
    }

    @Test
    fun `a unit that is stated and is not grams is refused, not rescaled`() {
        assertNull(macro(unit = "mg"))
        assertNull(macro(unit = "%"))
        assertNull(macro(unit = "kcal"))
    }

    @Test
    fun `an estimated macronutrient is unknown`() {
        assertNull(macro(source = "estimate"))
        assertNull(macro(source = "ESTIMATE"))
    }

    @Test
    fun `a value from any other source is kept`() {
        assertEquals(Macro.ofMilligramsOrNull(12_500L), macro(source = "manufacturer"))
        assertEquals(Macro.ofMilligramsOrNull(12_500L), macro(source = "usda"))
        assertEquals(Macro.ofMilligramsOrNull(12_500L), macro(source = null))
    }

    @Test
    fun `a quoted number is a number, because Open Food Facts quotes some of them`() {
        val quoted = OpenFoodFactsNutrient(value = JsonPrimitive("12.5"), unit = "g")

        assertEquals(Macro.ofMilligramsOrNull(12_500L), OpenFoodFactsMapper.macroOrNull(quoted))
    }

    @Test
    fun `an unreadable value is unknown and never zero`() {
        val nonsense = OpenFoodFactsNutrient(value = JsonPrimitive("traces"), unit = "g")

        assertNull(OpenFoodFactsMapper.macroOrNull(nonsense))
        assertNull(macro(value = null))
        assertNull(OpenFoodFactsMapper.macroOrNull(null))
    }

    @Test
    fun `a value outside PRD_FOOD 15's 0 to 100 g is unknown rather than clamped`() {
        assertNull(macro(value = 101.0))
        assertNull(macro(value = -1.0))
        assertEquals(Macro.ZERO, macro(value = 0.0))
        assertEquals(Macro.ofMilligramsOrNull(100_000L), macro(value = 100.0))
    }

    /** Two different quantities; borrowing one for the other would misstate the card. */
    @Test
    fun `the American gross carbohydrate is not read as the European net one`() {
        val set = OpenFoodFactsNutrientSet(
            per = "100g",
            nutrients = mapOf(
                "carbohydrates-total" to OpenFoodFactsNutrient(
                    value = JsonPrimitive(40.0),
                    unit = "g",
                    source = "packaging",
                ),
            ),
        )

        assertNull(OpenFoodFactsMapper.nutrientsOf(set).carbs)
    }
}

/** The two whole-set guards: what the values are quoted against, and which state they describe. */
class OpenFoodFactsNutrientSetTest {

    private fun nutrientSet(per: String?, preparation: String? = "as_sold") = OpenFoodFactsNutrientSet(
        per = per,
        preparation = preparation,
        nutrients = mapOf(
            "proteins" to OpenFoodFactsNutrient(
                value = JsonPrimitive(10.0),
                unit = "g",
                source = "packaging",
            ),
        ),
    )

    @Test
    fun `a set quoted per 100 g or per 100 ml is read`() {
        assertEquals(Macro.ofMilligramsOrNull(10_000L), OpenFoodFactsMapper.nutrientsOf(nutrientSet("100g")).protein)
        assertEquals(Macro.ofMilligramsOrNull(10_000L), OpenFoodFactsMapper.nutrientsOf(nutrientSet("100ml")).protein)
    }

    @Test
    fun `a set quoted against anything else carries no values`() {
        assertEquals(Nutrients.UNKNOWN, OpenFoodFactsMapper.nutrientsOf(nutrientSet("serving")))
        assertEquals(Nutrients.UNKNOWN, OpenFoodFactsMapper.nutrientsOf(nutrientSet(null)))
    }

    /** PRD_FOOD 8.6 models a cooked state with a ratio, never by swapping the reference state. */
    @Test
    fun `a prepared set is not a per-100 value of the food as sold`() {
        assertEquals(
            Nutrients.UNKNOWN,
            OpenFoodFactsMapper.nutrientsOf(nutrientSet("100g", preparation = "prepared")),
        )
    }

    @Test
    fun `an unstated preparation is read as sold`() {
        assertEquals(
            Macro.ofMilligramsOrNull(10_000L),
            OpenFoodFactsMapper.nutrientsOf(nutrientSet("100g", preparation = null)).protein,
        )
    }

    @Test
    fun `a card with no nutrition object at all is a food with no values`() {
        assertEquals(Nutrients.UNKNOWN, OpenFoodFactsMapper.nutrientsOf(null))
    }

    @Test
    fun `PRD_FOOD 8-6 reads a drink per 100 ml`() {
        assertEquals(ReferenceUnit.MILLILITRE, OpenFoodFactsMapper.referenceUnitOf(nutrientSet("100ml")))
        assertEquals(ReferenceUnit.GRAM, OpenFoodFactsMapper.referenceUnitOf(nutrientSet("100g")))
        assertEquals(ReferenceUnit.GRAM, OpenFoodFactsMapper.referenceUnitOf(null))
    }
}

/** PRD_FOOD 16.3: what a copied product remembers about where it came from. */
class OpenFoodFactsProvenanceTest {

    @Test
    fun `the copy names its source, its id and its version`() {
        val food = found(OpenFoodFactsFixtures.FOUND, OpenFoodFactsFixtures.FOUND_BARCODE)

        assertEquals(FoodSource.OPEN_FOOD_FACTS, food.source)
        assertEquals("3017620422003", food.sourceId)
        assertEquals("3017620422003", food.barcode)
        assertEquals("v3.6/947", food.sourceVersion)
    }

    @Test
    fun `the version names the schema and the revision of the card`() {
        assertEquals("v3.6/947", OpenFoodFactsMapper.sourceVersionOf(947L))
        assertEquals("v3.6/1", OpenFoodFactsMapper.sourceVersionOf(1L))
        assertEquals("v3.6", OpenFoodFactsMapper.sourceVersionOf(null))
    }

    @Test
    fun `every recorded card records a version`() {
        listOf(
            OpenFoodFactsFixtures.FOUND to OpenFoodFactsFixtures.FOUND_BARCODE,
            OpenFoodFactsFixtures.INCOMPLETE to OpenFoodFactsFixtures.INCOMPLETE_BARCODE,
            OpenFoodFactsFixtures.KNOWN_ZEROS to OpenFoodFactsFixtures.KNOWN_ZEROS_BARCODE,
        ).forEach { (fixture, barcode) ->
            val food = found(fixture, barcode)
            assertTrue(food.sourceVersion.orEmpty().startsWith(OpenFoodFactsUrl.API_VERSION), fixture)
            assertEquals(barcode, food.sourceId, fixture)
        }
    }

    /**
     * Open Food Facts normalises some codes, so the number that was scanned and the id the card
     * is filed under are two different facts. PRD_FOOD 9.2 keeps both.
     */
    @Test
    fun `the scanned number stays the barcode even when the card is filed elsewhere`() {
        val product = OpenFoodFactsProduct(code = "00000000", productName = "Something")
        val food = OpenFoodFactsMapper.toFoodOrNull(product, "0000000000000", FoodId("fixed"))

        assertEquals("0000000000000", food?.barcode)
        assertEquals("00000000", food?.sourceId)
    }

    @Test
    fun `a card with no code of its own falls back to the number that was scanned`() {
        val product = OpenFoodFactsProduct(productName = "Something")
        val food = OpenFoodFactsMapper.toFoodOrNull(product, "3017620422003", FoodId("fixed"))

        assertEquals("3017620422003", food?.sourceId)
    }

    /** PRD_FOOD 9.2: the result is a candidate, written to the catalogue only when it is added. */
    @Test
    fun `the candidate carries the identity it was given`() {
        val food = found(OpenFoodFactsFixtures.FOUND, OpenFoodFactsFixtures.FOUND_BARCODE, FoodId("chosen"))

        assertEquals(FoodId("chosen"), food.id)
    }
}

/** PRD_FOOD 17: three outcomes, three screens, and never a crash or a half-built food. */
class OpenFoodFactsFailureTest {

    private fun read(body: String) =
        OpenFoodFactsMapper.read(OpenFoodFactsFixtures.FOUND_BARCODE, body, FoodId("fixed"))

    @Test
    fun `a recorded missing product prefills a manual creation`() {
        val result = OpenFoodFactsMapper.read(
            OpenFoodFactsFixtures.NOT_FOUND_BARCODE,
            OpenFoodFactsFixtures.read(OpenFoodFactsFixtures.NOT_FOUND),
        )

        assertEquals(ProductLookupResult.NotFound, result)
    }

    @Test
    fun `a truncated body is a failure, not a crash and not a partial food`() {
        val result = read(OpenFoodFactsFixtures.read(OpenFoodFactsFixtures.TRUNCATED))

        assertEquals(
            ProductLookupResult.Unavailable(LookupFailure.MALFORMED_RESPONSE),
            result,
        )
    }

    @Test
    fun `the outage page Open Food Facts serves under load is a failure`() {
        val result = read(OpenFoodFactsFixtures.read(OpenFoodFactsFixtures.NOT_JSON))

        assertEquals(
            ProductLookupResult.Unavailable(LookupFailure.MALFORMED_RESPONSE),
            result,
        )
    }

    @Test
    fun `a stated failure that is not a missing product is a service error`() {
        val result = read(OpenFoodFactsFixtures.read(OpenFoodFactsFixtures.SERVICE_FAILURE))

        assertEquals(ProductLookupResult.Unavailable(LookupFailure.SERVICE_ERROR), result)
    }

    @Test
    fun `no body of any shape ever throws`() {
        val bodies = listOf(
            "",
            "   ",
            "null",
            "[]",
            "{}",
            "{\"status\":\"success\"}",
            "{\"product\":null}",
            "{\"product\":{}}",
            "{\"product\":[]}",
            "{\"product\":{\"product_name\":\"\"}}",
            "{\"product\":{\"product_name\":\"   \"}}",
            "{\"product\":{\"product_name\":\"A\",\"nutrition\":\"oops\"}}",
            "{\"product\":{\"product_name\":\"A\",\"rev\":\"not a number\"}}",
            "{ unterminated",
            "﻿{}",
        )

        bodies.forEach { body ->
            val result = read(body)
            assertTrue(
                result is ProductLookupResult.NotFound ||
                    result is ProductLookupResult.Unavailable ||
                    result is ProductLookupResult.Found,
                body,
            )
        }
    }

    @Test
    fun `a body that is not an answer is never a food`() {
        val bodies = listOf("", "   ", "null", "[]", "{}", "{ unterminated", "<html></html>")

        bodies.forEach { body ->
            assertEquals(
                ProductLookupResult.Unavailable(LookupFailure.MALFORMED_RESPONSE),
                read(body),
                body,
            )
        }
    }

    /**
     * A card with nothing to call it cannot become a food PRD_FOOD 15 accepts, and telling
     * somebody to retry on a working network would be the wrong screen. It takes the same path
     * as a barcode Open Food Facts has never heard of: a manual creation, prefilled.
     */
    @Test
    fun `a nameless card routes to the manual creation, not to an error`() {
        assertEquals(ProductLookupResult.NotFound, read("{\"product\":{\"code\":\"3017620422003\"}}"))
        assertEquals(ProductLookupResult.NotFound, read("{\"product\":{\"product_name\":\"  \"}}"))
    }

    @Test
    fun `a success with no card at all is malformed, not a missing product`() {
        assertEquals(
            ProductLookupResult.Unavailable(LookupFailure.MALFORMED_RESPONSE),
            read("{\"status\":\"success\",\"result\":{\"id\":\"product_found\"}}"),
        )
    }

    @Test
    fun `unknown keys never break a card`() {
        val body = """
            {
              "status": "success",
              "a_field_added_next_year": {"deeply": {"nested": [1, 2, 3]}},
              "product": {
                "code": "3017620422003",
                "product_name": "Something",
                "another_new_field": "ignored",
                "nutrition": {
                  "input_sets": [{"per": "serving"}],
                  "aggregated_set": {
                    "per": "100g",
                    "preparation": "as_sold",
                    "future_field": 1,
                    "nutrients": {
                      "proteins": {"value": 3, "unit": "g", "source": "packaging", "new": 1}
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val food = assertIs<ProductLookupResult.Found>(read(body)).food

        assertEquals("Something", food.name)
        assertEquals(Macro.ofMilligramsOrNull(3_000L), food.per100.protein)
    }
}

/** PRD_FOOD 15's bounds, applied to text a third party wrote. */
class OpenFoodFactsTextTest {

    @Test
    fun `a name longer than PRD_FOOD 15 allows is truncated rather than refused`() {
        val long = "N".repeat(Food.MAX_NAME_LENGTH + 40)
        val food = OpenFoodFactsMapper.toFoodOrNull(
            OpenFoodFactsProduct(productName = long),
            "3017620422003",
            FoodId("fixed"),
        )

        assertEquals(Food.MAX_NAME_LENGTH, food?.name?.length)
    }

    @Test
    fun `a name is trimmed before it is measured`() {
        val food = OpenFoodFactsMapper.toFoodOrNull(
            OpenFoodFactsProduct(productName = "  Nutella  "),
            "3017620422003",
            FoodId("fixed"),
        )

        assertEquals("Nutella", food?.name)
    }

    @Test
    fun `the first brand of the list is the one kept`() {
        assertEquals("Nutella", OpenFoodFactsMapper.primaryBrandOrNull("Nutella, Ferrero, Yum yum"))
        assertEquals("Marmite", OpenFoodFactsMapper.primaryBrandOrNull("Marmite"))
        assertEquals("Bjorg", OpenFoodFactsMapper.primaryBrandOrNull("  Bjorg ,Distriborg"))
    }

    @Test
    fun `an absent or empty brand is null, not an empty string`() {
        assertNull(OpenFoodFactsMapper.primaryBrandOrNull(null))
        assertNull(OpenFoodFactsMapper.primaryBrandOrNull(""))
        assertNull(OpenFoodFactsMapper.primaryBrandOrNull("   "))
        assertNull(OpenFoodFactsMapper.primaryBrandOrNull(",Ferrero"))
    }

    @Test
    fun `a brand longer than PRD_FOOD 15 allows is truncated`() {
        val long = "B".repeat(Food.MAX_BRAND_LENGTH + 20)

        assertEquals(Food.MAX_BRAND_LENGTH, OpenFoodFactsMapper.primaryBrandOrNull(long)?.length)
    }

    @Test
    fun `a portion needs both of its halves`() {
        val labelOnly = OpenFoodFactsMapper.toFoodOrNull(
            OpenFoodFactsProduct(productName = "A", servingSize = "one pot"),
            "3017620422003",
            FoodId("fixed"),
        )
        val quantityOnly = OpenFoodFactsMapper.toFoodOrNull(
            OpenFoodFactsProduct(productName = "A", servingQuantity = JsonPrimitive(125)),
            "3017620422003",
            FoodId("fixed"),
        )

        assertEquals(false, labelOnly?.hasUsualServing)
        assertNull(labelOnly?.servingLabel)
        assertEquals(false, quantityOnly?.hasUsualServing)
        assertNull(quantityOnly?.servingSize)
    }

    @Test
    fun `a portion outside PRD_FOOD 15's 1 to 2000 is dropped, with its label`() {
        fun portion(quantity: Double) = OpenFoodFactsMapper.toFoodOrNull(
            OpenFoodFactsProduct(
                productName = "A",
                servingSize = "a portion",
                servingQuantity = JsonPrimitive(quantity),
            ),
            "3017620422003",
            FoodId("fixed"),
        )

        assertEquals(false, portion(0.0)?.hasUsualServing)
        assertEquals(false, portion(2_001.0)?.hasUsualServing)
        assertEquals(true, portion(1.0)?.hasUsualServing)
        assertEquals(true, portion(2_000.0)?.hasUsualServing)
    }

    @Test
    fun `a quoted portion is a portion, because Open Food Facts quotes some of them`() {
        val food = OpenFoodFactsMapper.toFoodOrNull(
            OpenFoodFactsProduct(
                productName = "A",
                servingSize = "330 g",
                servingQuantity = JsonPrimitive("330"),
            ),
            "3017620422003",
            FoodId("fixed"),
        )

        assertEquals(330.0, food?.servingSize?.amount)
    }
}
