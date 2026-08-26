package fr.kristenjestin.mue.domain.logic

import fr.kristenjestin.mue.domain.model.Energy
import fr.kristenjestin.mue.domain.model.LoggedAmount
import fr.kristenjestin.mue.domain.model.Macro
import fr.kristenjestin.mue.domain.model.Nutrients
import fr.kristenjestin.mue.domain.model.ReferenceUnit
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val TODAY: LocalDate = LocalDate.parse("2026-08-19")

/** PRD_FOOD 15: "Nom d'aliment ou de recette : 1 a 80 caracteres apres nettoyage des espaces." */
class FoodValidationNameTest {

    @Test
    fun `a name of one character is enough`() {
        assertEquals("a", FoodValidation.validateName("a").valueOrNull)
    }

    @Test
    fun `a name of eighty characters is still accepted`() {
        val name = "a".repeat(80)
        assertEquals(name, FoodValidation.validateName(name).valueOrNull)
    }

    @Test
    fun `a name of eighty-one characters is refused`() {
        assertEquals(FoodValidation.NAME_ERROR, FoodValidation.validateName("a".repeat(81)).errorMessage)
    }

    @Test
    fun `an empty name is refused`() {
        assertEquals(FoodValidation.NAME_ERROR, FoodValidation.validateName("").errorMessage)
    }

    @Test
    fun `a name of nothing but spaces is refused, because it is trimmed first`() {
        assertEquals(FoodValidation.NAME_ERROR, FoodValidation.validateName("    ").errorMessage)
    }

    @Test
    fun `surrounding spaces are cleaned rather than counted`() {
        assertEquals("Apple", FoodValidation.validateName("  Apple  ").valueOrNull)
    }

    @Test
    fun `eighty characters padded with spaces still fits`() {
        val name = "b".repeat(80)
        assertEquals(name, FoodValidation.validateName("  $name  ").valueOrNull)
    }

    @Test
    fun `a recipe name follows the same rule as a food name`() {
        assertTrue(FoodValidation.validateName("Coconut curry").isValid)
    }

    @Test
    fun `a quick add title follows the same rule again`() {
        assertTrue(FoodValidation.validateName("Restaurant plate").isValid)
    }
}

/** PRD_FOOD 15's optional identity fields, which PRD_FOOD 18 keeps usable without a camera. */
class FoodValidationIdentityTest {

    @Test
    fun `no brand at all is valid and means null`() {
        assertNull(FoodValidation.validateBrand("").valueOrNull)
        assertTrue(FoodValidation.validateBrand(null).isValid)
        assertTrue(FoodValidation.validateBrand("   ").isValid)
    }

    @Test
    fun `a brand is trimmed`() {
        assertEquals("Bjorg", FoodValidation.validateBrand(" Bjorg ").valueOrNull)
    }

    @Test
    fun `a brand longer than the guard is refused`() {
        assertEquals(FoodValidation.BRAND_ERROR, FoodValidation.validateBrand("x".repeat(81)).errorMessage)
    }

    @Test
    fun `a brand of exactly eighty characters passes`() {
        assertTrue(FoodValidation.validateBrand("x".repeat(80)).isValid)
    }

    @Test
    fun `no barcode is valid, since PRD_FOOD 18 keeps the module usable without a camera`() {
        assertNull(FoodValidation.validateBarcode(null).valueOrNull)
        assertTrue(FoodValidation.validateBarcode("").isValid)
    }

    @Test
    fun `an EAN-8 is the shortest barcode accepted`() {
        assertEquals("40123455", FoodValidation.validateBarcode("40123455").valueOrNull)
        assertEquals(FoodValidation.BARCODE_ERROR, FoodValidation.validateBarcode("4012345").errorMessage)
    }

    @Test
    fun `a GTIN-14 is the longest barcode accepted`() {
        assertTrue(FoodValidation.validateBarcode("1".repeat(14)).isValid)
        assertEquals(FoodValidation.BARCODE_ERROR, FoodValidation.validateBarcode("1".repeat(15)).errorMessage)
    }

    @Test
    fun `a barcode is digits and nothing else`() {
        assertEquals(FoodValidation.BARCODE_ERROR, FoodValidation.validateBarcode("40123 55").errorMessage)
        assertEquals(FoodValidation.BARCODE_ERROR, FoodValidation.validateBarcode("4012345a").errorMessage)
    }
}

/**
 * PRD_FOOD 15: "Energie pour 100 : 0 a 900 kcal, ou inconnue. Un champ vide est enregistre
 * `null`, jamais `0`."
 */
class FoodValidationEnergyPer100Test {

    @Test
    fun `a blank field is valid and unknown, never a zero`() {
        val parsed = FoodValidation.validateEnergyPer100("")
        assertTrue(parsed.isValid)
        assertNull(parsed.valueOrNull)
    }

    @Test
    fun `a field of spaces is unknown too`() {
        assertNull(FoodValidation.validateEnergyPer100("   ").valueOrNull)
    }

    @Test
    fun `a typed zero is a known zero and is not the same as a blank field`() {
        assertEquals(Energy.ZERO, FoodValidation.validateEnergyPer100("0").valueOrNull)
        assertNull(FoodValidation.validateEnergyPer100("").valueOrNull)
    }

    @Test
    fun `nine hundred kilocalories is the ceiling of a per-100 value`() {
        assertEquals(900_000, FoodValidation.validateEnergyPer100("900").valueOrNull?.milliKcal)
        assertEquals(
            FoodValidation.ENERGY_PER_100_ERROR,
            FoodValidation.validateEnergyPer100("900.001").errorMessage,
        )
    }

    @Test
    fun `a negative energy is refused`() {
        assertEquals(
            FoodValidation.ENERGY_PER_100_ERROR,
            FoodValidation.validateEnergyPer100("-1").errorMessage,
        )
    }

    @Test
    fun `an unreadable energy is refused rather than read as zero`() {
        assertEquals(
            FoodValidation.ENERGY_PER_100_ERROR,
            FoodValidation.validateEnergyPer100("about a hundred").errorMessage,
        )
    }

    @Test
    fun `a comma is a decimal separator whatever the phone's language is`() {
        assertEquals(89_500, FoodValidation.validateEnergyPer100("89,5").valueOrNull?.milliKcal)
        assertEquals(89_500, FoodValidation.validateEnergyPer100("89.5").valueOrNull?.milliKcal)
    }

    @Test
    fun `a half-typed number keeps its draft alive`() {
        assertEquals(89_000, FoodValidation.validateEnergyPer100("89,").valueOrNull?.milliKcal)
    }
}

/** PRD_FOOD 15: "Macronutriment pour 100 : 0 a 100 g, ou inconnu." */
class FoodValidationMacroPer100Test {

    @Test
    fun `a blank macronutrient is unknown, never zero`() {
        assertNull(FoodValidation.validateMacroPer100("").valueOrNull)
        assertTrue(FoodValidation.validateMacroPer100("").isValid)
    }

    @Test
    fun `a typed zero is a known zero`() {
        assertEquals(Macro.ZERO, FoodValidation.validateMacroPer100("0").valueOrNull)
    }

    @Test
    fun `a hundred grams in a hundred grams is the physical ceiling`() {
        assertEquals(100_000, FoodValidation.validateMacroPer100("100").valueOrNull?.milligrams)
        assertEquals(
            FoodValidation.MACRO_PER_100_ERROR,
            FoodValidation.validateMacroPer100("100.1").errorMessage,
        )
    }

    @Test
    fun `a negative macronutrient is refused`() {
        assertEquals(
            FoodValidation.MACRO_PER_100_ERROR,
            FoodValidation.validateMacroPer100("-0.1").errorMessage,
        )
    }

    @Test
    fun `a tenth of a gram survives the round trip`() {
        assertEquals(31_400, FoodValidation.validateMacroPer100("31.4").valueOrNull?.milligrams)
    }

    @Test
    fun `the sum rule ignores the unknowns, as PRD_FOOD 15 says it must`() {
        val partial = Nutrients(protein = macroOf(60.0), fat = macroOf(30.0))
        assertTrue(FoodValidation.validateMacroSum(partial).isValid)
    }

    @Test
    fun `the three known macronutrients may add up to exactly a hundred grams`() {
        val full = Nutrients(protein = macroOf(50.0), carbs = macroOf(30.0), fat = macroOf(20.0))
        assertTrue(FoodValidation.validateMacroSum(full).isValid)
    }

    @Test
    fun `a gram over a hundred is refused`() {
        val over = Nutrients(protein = macroOf(50.0), carbs = macroOf(30.0), fat = macroOf(21.0))
        assertEquals(FoodValidation.MACRO_SUM_ERROR, FoodValidation.validateMacroSum(over).errorMessage)
    }

    @Test
    fun `fibre stays outside the sum, because Ciqual counts it inside the carbohydrates`() {
        val withFibre = Nutrients(
            protein = macroOf(60.0),
            carbs = macroOf(30.0),
            fat = macroOf(10.0),
            fibre = macroOf(50.0),
        )
        assertTrue(FoodValidation.validateMacroSum(withFibre).isValid)
    }

    @Test
    fun `a card that knows nothing passes the sum rule`() {
        assertTrue(FoodValidation.validateMacroSum(Nutrients.UNKNOWN).isValid)
    }
}

/** The five per-100 fields of a food form, judged together (PRD_FOOD 15). */
class FoodValidationPer100FormTest {

    @Test
    fun `PRD_FOOD 15 - a food with no value at all is accepted`() {
        val result = FoodValidation.validatePer100("", "", "", "", "")
        assertEquals(NutrientsValidation.Valid(Nutrients.UNKNOWN), result)
    }

    @Test
    fun `an incomplete Open Food Facts card is the nominal case, not an error`() {
        val result = FoodValidation.validatePer100("250", "", "", "", "")
        val valid = assertNotNull(result as? NutrientsValidation.Valid)
        assertEquals(250_000, valid.nutrients.energy?.milliKcal)
        assertNull(valid.nutrients.protein)
        assertNull(valid.nutrients.fibre)
    }

    @Test
    fun `a complete card comes back complete`() {
        val result = FoodValidation.validatePer100("89", "0.3", "19", "0.2", "2.4")
        val valid = assertNotNull(result as? NutrientsValidation.Valid)
        assertTrue(valid.nutrients.isFullyKnown)
    }

    @Test
    fun `two bad fields are reported together so the screen can highlight both`() {
        val result = FoodValidation.validatePer100("1000", "", "", "500", "")
        val invalid = assertNotNull(result as? NutrientsValidation.Invalid)
        assertEquals(FoodValidation.ENERGY_PER_100_ERROR, invalid.energyError)
        assertEquals(FoodValidation.MACRO_PER_100_ERROR, invalid.fatError)
        assertNull(invalid.proteinError)
        assertNull(invalid.sumError)
    }

    @Test
    fun `the sum is only judged once every field could be read`() {
        val result = FoodValidation.validatePer100("nope", "60", "60", "", "")
        val invalid = assertNotNull(result as? NutrientsValidation.Invalid)
        assertEquals(FoodValidation.ENERGY_PER_100_ERROR, invalid.energyError)
        assertNull(invalid.sumError)
    }

    @Test
    fun `a sum over a hundred grams is a form error of its own`() {
        val result = FoodValidation.validatePer100("400", "60", "30", "20", "")
        val invalid = assertNotNull(result as? NutrientsValidation.Invalid)
        assertEquals(FoodValidation.MACRO_SUM_ERROR, invalid.sumError)
        assertNull(invalid.proteinError)
    }

    @Test
    fun `a zero typed in every field is five known zeroes`() {
        val result = FoodValidation.validatePer100("0", "0", "0", "0", "0")
        assertEquals(NutrientsValidation.Valid(Nutrients.ZERO), result)
    }
}

/** PRD_FOOD 15's quantity rows: the ratio, the portion and the ingredient. */
class FoodValidationQuantityTest {

    @Test
    fun `no cooking ratio at all is the ordinary case`() {
        assertNull(FoodValidation.validateCookedRatio("").valueOrNull)
        assertTrue(FoodValidation.validateCookedRatio("  ").isValid)
    }

    @Test
    fun `the bounds of a cooking ratio are three tenths and five`() {
        assertEquals(300, FoodValidation.validateCookedRatio("0.3").valueOrNull?.thousandths)
        assertEquals(5_000, FoodValidation.validateCookedRatio("5").valueOrNull?.thousandths)
        assertEquals(FoodValidation.COOKED_RATIO_ERROR, FoodValidation.validateCookedRatio("0.299").errorMessage)
        assertEquals(FoodValidation.COOKED_RATIO_ERROR, FoodValidation.validateCookedRatio("5.001").errorMessage)
    }

    @Test
    fun `a ratio of zero is not a looser ratio, it is not a ratio`() {
        assertEquals(FoodValidation.COOKED_RATIO_ERROR, FoodValidation.validateCookedRatio("0").errorMessage)
    }

    @Test
    fun `the two ratios PRD_FOOD 8_6 tabulates are accepted`() {
        assertEquals(2_300, FoodValidation.validateCookedRatio("2.3").valueOrNull?.thousandths)
        assertEquals(720, FoodValidation.validateCookedRatio("0.72").valueOrNull?.thousandths)
    }

    @Test
    fun `a usual serving weighs from one to two thousand grams`() {
        assertEquals(1_000, FoodValidation.validateUsualServingSize("1").valueOrNull?.thousandths)
        assertEquals(2_000_000, FoodValidation.validateUsualServingSize("2000").valueOrNull?.thousandths)
        assertEquals(
            FoodValidation.USUAL_SERVING_SIZE_ERROR,
            FoodValidation.validateUsualServingSize("0.999").errorMessage,
        )
        assertEquals(
            FoodValidation.USUAL_SERVING_SIZE_ERROR,
            FoodValidation.validateUsualServingSize("2000.001").errorMessage,
        )
    }

    @Test
    fun `a food declaring no usual serving leaves both halves blank`() {
        assertNull(FoodValidation.validateUsualServing(null, "").valueOrNull)
        assertTrue(FoodValidation.validateUsualServing("  ", "  ").isValid)
    }

    @Test
    fun `a label with no weight cannot be turned into grams`() {
        assertEquals(
            FoodValidation.USUAL_SERVING_PAIR_ERROR,
            FoodValidation.validateUsualServing("apple", "").errorMessage,
        )
    }

    @Test
    fun `a weight with no label has nothing to put on the button`() {
        assertEquals(
            FoodValidation.USUAL_SERVING_PAIR_ERROR,
            FoodValidation.validateUsualServing(null, "150").errorMessage,
        )
    }

    @Test
    fun `both halves together make a usual serving`() {
        val serving = assertNotNull(FoodValidation.validateUsualServing(" apple ", "150").valueOrNull)
        assertEquals("apple", serving.label)
        assertEquals(150_000, serving.size.thousandths)
    }

    @Test
    fun `a usual serving with an out-of-range weight reports the weight's own error`() {
        assertEquals(
            FoodValidation.USUAL_SERVING_SIZE_ERROR,
            FoodValidation.validateUsualServing("pot", "5000").errorMessage,
        )
    }

    @Test
    fun `the portion counter runs from half a portion to twenty, by halves`() {
        assertEquals(500, FoodValidation.validateUsualPortions("0.5").valueOrNull?.thousandths)
        assertEquals(20_000, FoodValidation.validateUsualPortions("20").valueOrNull?.thousandths)
        assertEquals(
            FoodValidation.USUAL_PORTIONS_ERROR,
            FoodValidation.validateUsualPortions("0.2").errorMessage,
        )
        assertEquals(
            FoodValidation.USUAL_PORTIONS_ERROR,
            FoodValidation.validateUsualPortions("20.4").errorMessage,
        )
    }

    @Test
    fun `a blank portion count is not a portion count`() {
        assertEquals(FoodValidation.USUAL_PORTIONS_ERROR, FoodValidation.validateUsualPortions("").errorMessage)
    }

    @Test
    fun `an ingredient quantity is strictly above zero and at most five thousand`() {
        assertEquals(1, FoodValidation.validateIngredientQuantity("0.001").valueOrNull?.thousandths)
        assertEquals(5_000_000, FoodValidation.validateIngredientQuantity("5000").valueOrNull?.thousandths)
        assertEquals(
            FoodValidation.INGREDIENT_QUANTITY_ERROR,
            FoodValidation.validateIngredientQuantity("0").errorMessage,
        )
        assertEquals(
            FoodValidation.INGREDIENT_QUANTITY_ERROR,
            FoodValidation.validateIngredientQuantity("5000.001").errorMessage,
        )
    }

    @Test
    fun `an ingredient quantity of nothing typed is refused rather than read as zero`() {
        assertEquals(
            FoodValidation.INGREDIENT_QUANTITY_ERROR,
            FoodValidation.validateIngredientQuantity("").errorMessage,
        )
        assertEquals(
            FoodValidation.INGREDIENT_QUANTITY_ERROR,
            FoodValidation.validateIngredientQuantity("-5").errorMessage,
        )
    }
}

/** PRD_FOOD 15's recipe rows: servings, ingredients and steps. */
class FoodValidationRecipeTest {

    @Test
    fun `a recipe serves a whole number from one to twelve`() {
        assertEquals(1, FoodValidation.validateBaseServings("1").valueOrNull)
        assertEquals(12, FoodValidation.validateBaseServings("12").valueOrNull)
        assertEquals(FoodValidation.BASE_SERVINGS_ERROR, FoodValidation.validateBaseServings("0").errorMessage)
        assertEquals(FoodValidation.BASE_SERVINGS_ERROR, FoodValidation.validateBaseServings("13").errorMessage)
    }

    @Test
    fun `two and a half servings is not a number of servings a recipe is written for`() {
        assertEquals(FoodValidation.BASE_SERVINGS_ERROR, FoodValidation.validateBaseServings("2.5").errorMessage)
    }

    @Test
    fun `a blank servings field is refused`() {
        assertEquals(FoodValidation.BASE_SERVINGS_ERROR, FoodValidation.validateBaseServings("").errorMessage)
    }

    @Test
    fun `the integer form of the rule agrees with the typed form`() {
        assertTrue(FoodValidation.validateBaseServings(4).isValid)
        assertFalse(FoodValidation.validateBaseServings(-1).isValid)
    }

    @Test
    fun `PRD_FOOD 22 - a recipe cannot be saved without an ingredient`() {
        assertEquals(FoodValidation.INGREDIENT_COUNT_ERROR, FoodValidation.validateIngredientCount(0).errorMessage)
        assertTrue(FoodValidation.validateIngredientCount(1).isValid)
    }

    @Test
    fun `forty ingredients is the ceiling`() {
        assertTrue(FoodValidation.validateIngredientCount(40).isValid)
        assertEquals(FoodValidation.INGREDIENT_COUNT_ERROR, FoodValidation.validateIngredientCount(41).errorMessage)
    }

    @Test
    fun `the rows themselves are judged by their own count`() {
        val rows = listOf(ingredientOf("food-1", 100.0))
        assertEquals(rows, FoodValidation.validateIngredients(rows).valueOrNull)
        assertFalse(FoodValidation.validateIngredients(emptyList()).isValid)
    }

    @Test
    fun `a recipe may have no step at all`() {
        assertEquals(emptyList<String>(), FoodValidation.validateSteps("").valueOrNull)
    }

    @Test
    fun `steps are typed one per line, and blank lines are not steps`() {
        val steps = FoodValidation.validateSteps("Boil the water\n\n  Add the pasta  \n").valueOrNull
        assertEquals(listOf("Boil the water", "Add the pasta"), steps)
    }

    @Test
    fun `thirty steps is the ceiling`() {
        val thirty = List(30) { "step $it" }
        assertTrue(FoodValidation.validateSteps(thirty).isValid)
        assertEquals(FoodValidation.STEPS_ERROR, FoodValidation.validateSteps(List(31) { "step $it" }).errorMessage)
    }

    @Test
    fun `a step of five hundred characters fits, and one more does not`() {
        assertTrue(FoodValidation.validateSteps(listOf("s".repeat(500))).isValid)
        assertEquals(FoodValidation.STEPS_ERROR, FoodValidation.validateSteps(listOf("s".repeat(501))).errorMessage)
    }

    @Test
    fun `no preparation time is valid and means null`() {
        assertNull(FoodValidation.validatePrepTime("").valueOrNull)
        assertTrue(FoodValidation.validatePrepTime("  ").isValid)
    }

    @Test
    fun `a preparation time runs from a minute to a full day`() {
        assertEquals(1, FoodValidation.validatePrepTime("1").valueOrNull)
        assertEquals(1_440, FoodValidation.validatePrepTime("1440").valueOrNull)
        assertEquals(FoodValidation.PREP_TIME_ERROR, FoodValidation.validatePrepTime("0").errorMessage)
        assertEquals(FoodValidation.PREP_TIME_ERROR, FoodValidation.validatePrepTime("1441").errorMessage)
    }

    @Test
    fun `a description never blocks a save and a blank one is no description`() {
        assertNull(FoodValidation.normalizeDescription("   "))
        assertNull(FoodValidation.normalizeDescription(null))
        assertEquals("A curry", FoodValidation.normalizeDescription("  A curry  "))
        assertEquals(500, FoodValidation.normalizeDescription("d".repeat(600))?.length)
    }
}

/** PRD_FOOD 15: "Portions consommees : 0,25 a 10, par pas de 0,25", planned ones included. */
class FoodValidationServingsTest {

    @Test
    fun `a quarter of a serving is the smallest portion consumed`() {
        assertEquals(250, FoodValidation.validateConsumedServings("0.25").valueOrNull?.thousandths)
    }

    @Test
    fun `ten servings is the largest`() {
        assertEquals(10_000, FoodValidation.validateConsumedServings("10").valueOrNull?.thousandths)
        assertEquals(
            FoodValidation.CONSUMED_SERVINGS_ERROR,
            FoodValidation.validateConsumedServings("10.5").errorMessage,
        )
    }

    @Test
    fun `nothing at all consumed is not a line of the journal`() {
        assertEquals(
            FoodValidation.CONSUMED_SERVINGS_ERROR,
            FoodValidation.validateConsumedServings("0").errorMessage,
        )
        assertEquals(
            FoodValidation.CONSUMED_SERVINGS_ERROR,
            FoodValidation.validateConsumedServings("").errorMessage,
        )
    }

    @Test
    fun `a negative portion count is refused`() {
        assertEquals(
            FoodValidation.CONSUMED_SERVINGS_ERROR,
            FoodValidation.validateConsumedServings("-1").errorMessage,
        )
    }

    @Test
    fun `one and a half servings is on the quarter step`() {
        assertEquals(1_500, FoodValidation.validateConsumedServings("1.5").valueOrNull?.thousandths)
    }

    @Test
    fun `the numeric form agrees with the typed form`() {
        assertEquals(
            FoodValidation.validateConsumedServings("2.25").valueOrNull,
            FoodValidation.validateConsumedServings(2.25).valueOrNull,
        )
    }

    @Test
    fun `a planned number of servings uses the counter of a consumed one`() {
        assertTrue(FoodValidation.validateConsumedServings("0.75").isValid)
    }
}

/** PRD_FOOD 15: "Ajout rapide : nom requis, energie requise de 0 a 5 000 kcal, proteines facultatives." */
class FoodValidationQuickAddTest {

    @Test
    fun `a quick add needs an energy and refuses a blank one`() {
        assertEquals(
            FoodValidation.QUICK_ADD_ENERGY_ERROR,
            FoodValidation.validateQuickAddEnergy("").errorMessage,
        )
    }

    @Test
    fun `a quick add energy runs from zero to five thousand kilocalories`() {
        assertEquals(0, FoodValidation.validateQuickAddEnergy("0").valueOrNull?.milliKcal)
        assertEquals(5_000_000, FoodValidation.validateQuickAddEnergy("5000").valueOrNull?.milliKcal)
        assertEquals(
            FoodValidation.QUICK_ADD_ENERGY_ERROR,
            FoodValidation.validateQuickAddEnergy("5000.001").errorMessage,
        )
        assertEquals(
            FoodValidation.QUICK_ADD_ENERGY_ERROR,
            FoodValidation.validateQuickAddEnergy("-1").errorMessage,
        )
    }

    @Test
    fun `a blank protein field is null and never a zero`() {
        val protein = FoodValidation.validateQuickAddProtein("")
        assertTrue(protein.isValid)
        assertNull(protein.valueOrNull)
        assertFalse(protein.valueOrNull == Macro.ZERO)
    }

    @Test
    fun `a typed zero protein is a known zero`() {
        assertEquals(Macro.ZERO, FoodValidation.validateQuickAddProtein("0").valueOrNull)
    }

    @Test
    fun `an unreadable protein is refused rather than dropped to null`() {
        assertEquals(
            FoodValidation.QUICK_ADD_PROTEIN_ERROR,
            FoodValidation.validateQuickAddProtein("some").errorMessage,
        )
    }

    @Test
    fun `a whole quick add with a blank protein stores null protein`() {
        val result = FoodValidation.validateQuickAdd("Restaurant plate", "650", "")
        val valid = assertNotNull(result as? QuickAddValidation.Valid)
        assertEquals("Restaurant plate", valid.draft.title)
        assertEquals(650_000, valid.draft.nutrients.energy?.milliKcal)
        assertNull(valid.draft.nutrients.protein)
    }

    @Test
    fun `the three metrics a quick add never states stay unknown`() {
        val result = FoodValidation.validateQuickAdd("Plate", "650", "20")
        val valid = assertNotNull(result as? QuickAddValidation.Valid)
        assertEquals(20_000, valid.draft.nutrients.protein?.milligrams)
        assertNull(valid.draft.nutrients.carbs)
        assertNull(valid.draft.nutrients.fat)
        assertNull(valid.draft.nutrients.fibre)
    }

    @Test
    fun `a quick add with no name and no energy reports both`() {
        val result = FoodValidation.validateQuickAdd("", "", "")
        val invalid = assertNotNull(result as? QuickAddValidation.Invalid)
        assertEquals(FoodValidation.NAME_ERROR, invalid.titleError)
        assertEquals(FoodValidation.QUICK_ADD_ENERGY_ERROR, invalid.energyError)
        assertNull(invalid.proteinError)
    }

    @Test
    fun `a quick add of zero kilocalories is a real quick add`() {
        val result = FoodValidation.validateQuickAdd("Black coffee", "0", "")
        val valid = assertNotNull(result as? QuickAddValidation.Valid)
        assertEquals(Energy.ZERO, valid.draft.nutrients.energy)
    }
}

/** PRD_FOOD 15's date and time rows, and PRD_FOOD 8.6's cooking state. */
class FoodValidationWhenTest {

    @Test
    fun `a consumption time is typed as hours and minutes`() {
        assertEquals(LocalTime.of(8, 0), FoodValidation.validateConsumedAt("08:00").valueOrNull)
        assertEquals(LocalTime.of(8, 5), FoodValidation.validateConsumedAt("8:5").valueOrNull)
    }

    @Test
    fun `an hour outside the clock is refused`() {
        assertEquals(FoodValidation.TIME_ERROR, FoodValidation.validateConsumedAt("24:00").errorMessage)
        assertEquals(FoodValidation.TIME_ERROR, FoodValidation.validateConsumedAt("-1:00").errorMessage)
    }

    @Test
    fun `a minute outside the hour is refused`() {
        assertEquals(FoodValidation.TIME_ERROR, FoodValidation.validateConsumedAt("12:60").errorMessage)
    }

    @Test
    fun `anything that is not two parts is refused`() {
        assertEquals(FoodValidation.TIME_ERROR, FoodValidation.validateConsumedAt("12").errorMessage)
        assertEquals(FoodValidation.TIME_ERROR, FoodValidation.validateConsumedAt("12:00:00").errorMessage)
        assertEquals(FoodValidation.TIME_ERROR, FoodValidation.validateConsumedAt("").errorMessage)
        assertEquals(FoodValidation.TIME_ERROR, FoodValidation.validateConsumedAt("noon").errorMessage)
    }

    @Test
    fun `a time is stored to the minute`() {
        assertEquals(
            LocalTime.of(13, 45),
            FoodValidation.normalizeConsumedAt(LocalTime.of(13, 45, 32, 900)),
        )
    }

    @Test
    fun `today may be logged and tomorrow may not`() {
        assertTrue(FoodValidation.validateConsumedOn(TODAY, TODAY).isValid)
        assertTrue(FoodValidation.validateConsumedOn(TODAY.minusDays(30), TODAY).isValid)
        assertEquals(
            FoodValidation.CONSUMED_DATE_ERROR,
            FoodValidation.validateConsumedOn(TODAY.plusDays(1), TODAY).errorMessage,
        )
    }

    @Test
    fun `a proposal may be made from today up to sixty days ahead`() {
        assertTrue(FoodValidation.validatePlannedOn(TODAY, TODAY).isValid)
        assertTrue(FoodValidation.validatePlannedOn(TODAY.plusDays(60), TODAY).isValid)
        assertEquals(
            FoodValidation.PLANNED_DATE_ERROR,
            FoodValidation.validatePlannedOn(TODAY.plusDays(61), TODAY).errorMessage,
        )
        assertEquals(
            FoodValidation.PLANNED_DATE_ERROR,
            FoodValidation.validatePlannedOn(TODAY.minusDays(1), TODAY).errorMessage,
        )
    }

    @Test
    fun `a weight really can be read in the cooked state`() {
        val amount = LoggedAmount.Measured(quantityOf(150.0), ReferenceUnit.GRAM)
        assertEquals(true, FoodValidation.validateWeighedCooked(amount, true, chickenBreast()).valueOrNull)
    }

    @Test
    fun `PRD_FOOD 15 - a recipe line counted in servings cannot be weighed cooked`() {
        val amount = LoggedAmount.Portioned(servingsOf(1.5))
        assertEquals(
            FoodValidation.COOKED_STATE_UNIT_ERROR,
            FoodValidation.validateWeighedCooked(amount, true).errorMessage,
        )
    }

    @Test
    fun `a quick add has no quantity, so it has no cooked state either`() {
        assertEquals(
            FoodValidation.COOKED_STATE_UNIT_ERROR,
            FoodValidation.validateWeighedCooked(LoggedAmount.Unmeasured, true).errorMessage,
        )
    }

    @Test
    fun `a serving count that claims nothing about cooking is perfectly valid`() {
        val amount = LoggedAmount.Portioned(servingsOf(1.5))
        assertEquals(false, FoodValidation.validateWeighedCooked(amount, false).valueOrNull)
    }

    @Test
    fun `PRD_FOOD 22 - the selector never appears on a food without a ratio`() {
        val amount = LoggedAmount.Measured(quantityOf(150.0), ReferenceUnit.GRAM)
        val plain = foodOf(cookedRatio = null)
        assertEquals(
            FoodValidation.COOKED_STATE_UNAVAILABLE_ERROR,
            FoodValidation.validateWeighedCooked(amount, true, plain).errorMessage,
        )
    }

    @Test
    fun `a weight with no food to check against is accepted`() {
        val amount = LoggedAmount.Measured(quantityOf(150.0), ReferenceUnit.GRAM)
        assertTrue(FoodValidation.validateWeighedCooked(amount, true, null).isValid)
    }

    @Test
    fun `the math still reads a refused flag as an identity, so a frozen line never moves`() {
        val plain = foodOf(cookedRatio = null, per100 = per100(energy = 100.0))
        val amount = LoggedAmount.Measured(quantityOf(150.0), ReferenceUnit.GRAM)
        assertFalse(FoodValidation.validateWeighedCooked(amount, true, plain).isValid)
        assertEquals(
            NutritionMath.foodContribution(plain, quantityOf(150.0), weighedCooked = false),
            NutritionMath.foodContribution(plain, quantityOf(150.0), weighedCooked = true),
        )
    }
}
