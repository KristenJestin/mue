package fr.kristenjestin.mue.data.sync

import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodLogEntry
import fr.kristenjestin.mue.domain.model.MealPlanEntry
import fr.kristenjestin.mue.domain.model.RecipeDetail
import kotlinx.serialization.Serializable

/**
 * The wire shape of the four food aggregates, versioned by [PAYLOAD_SCHEMA_VERSION] exactly as
 * `MeasurementPayload` is.
 *
 * **Every number is an integer of its canonical unit**, and every unknown metric is an absent
 * field rather than a zero. That is the same rule the columns obey, for the same reason: PRD_FOOD
 * 13.1 forbids inventing a value, and a payload that turned a missing protein into `0` would
 * make the server store a claim the phone never made — and would then hand it back on the next
 * pull as fact. `encodeDefaults` is left off, so a `null` metric simply does not appear.
 *
 * The payloads live beside the outbox rather than in `data/remote`, because until the hand
 * written DTOs of the sync PRD's section 20.4 land, this is the only definition of the format —
 * and the schema version is what lets the two move independently once they meet.
 */
@Serializable
data class FoodPayload(
    val id: String,
    val name: String,
    val source: String,
    val referenceUnit: String,
    val rawLabel: String,
    val cookedLabel: String,
    val energyMilliKcal: Int? = null,
    val proteinMilligrams: Int? = null,
    val carbsMilligrams: Int? = null,
    val fatMilligrams: Int? = null,
    val fibreMilligrams: Int? = null,
    val brand: String? = null,
    val barcode: String? = null,
    val sourceId: String? = null,
    val sourceVersion: String? = null,
    val servingLabel: String? = null,
    val servingThousandths: Int? = null,
    val cookedRatioThousandths: Int? = null,
    val imageRef: String? = null,
)

@Serializable
data class RecipeIngredientPayload(
    val id: String,
    val foodId: String,
    val quantityThousandths: Int,
    val unit: String,
    val position: Int,
    val foodName: String? = null,
)

/** PRD_FOOD 21.2: "une recette n'apparaît jamais sans ses ingrédients". */
@Serializable
data class RecipePayload(
    val id: String,
    val name: String,
    val type: String,
    val baseServings: Int,
    val isFavourite: Boolean,
    val ingredients: List<RecipeIngredientPayload> = emptyList(),
    val description: String? = null,
    val prepTimeMinutes: Int? = null,
    val steps: List<String> = emptyList(),
    val imageRef: String? = null,
)

@Serializable
data class FoodLogEntryPayload(
    val id: String,
    val consumedOn: String,
    val consumedAt: String,
    val slot: String,
    val kind: String,
    val title: String,
    val estimation: String,
    val weighedCooked: Boolean,
    val energyMilliKcal: Int? = null,
    val proteinMilligrams: Int? = null,
    val carbsMilligrams: Int? = null,
    val fatMilligrams: Int? = null,
    val fibreMilligrams: Int? = null,
    val sourceRef: String? = null,
    val amountLabel: String? = null,
    val quantityThousandths: Int? = null,
    val quantityUnit: String? = null,
    val portionsThousandths: Int? = null,
    val fromPlan: String? = null,
)

@Serializable
data class MealPlanEntryPayload(
    val plannedOn: String,
    val slot: String,
    val recipeId: String,
    val plannedServingsThousandths: Int,
    val consumedLogEntryId: String? = null,
)

internal fun Food.toPayload(): FoodPayload = FoodPayload(
    id = id.value,
    name = name,
    source = source.id,
    referenceUnit = referenceUnit.id,
    rawLabel = rawLabel,
    cookedLabel = cookedLabel,
    energyMilliKcal = per100.energy?.milliKcal,
    proteinMilligrams = per100.protein?.milligrams,
    carbsMilligrams = per100.carbs?.milligrams,
    fatMilligrams = per100.fat?.milligrams,
    fibreMilligrams = per100.fibre?.milligrams,
    brand = brand,
    barcode = barcode,
    sourceId = sourceId,
    sourceVersion = sourceVersion,
    servingLabel = servingLabel,
    servingThousandths = servingSize?.thousandths,
    cookedRatioThousandths = cookedRatio?.thousandths,
    imageRef = imageRef,
)

internal fun RecipeDetail.toPayload(): RecipePayload = RecipePayload(
    id = recipe.id.value,
    name = recipe.name,
    type = recipe.type.id,
    baseServings = recipe.baseServings,
    isFavourite = recipe.isFavourite,
    ingredients = ingredients.map { ingredient ->
        RecipeIngredientPayload(
            id = ingredient.id.value,
            foodId = ingredient.foodId.value,
            quantityThousandths = ingredient.quantity.thousandths,
            unit = ingredient.unit.id,
            position = ingredient.position,
            foodName = ingredient.foodName,
        )
    },
    description = recipe.description,
    prepTimeMinutes = recipe.prepTimeMinutes,
    steps = recipe.steps,
    imageRef = recipe.imageRef,
)

internal fun FoodLogEntry.toPayload(): FoodLogEntryPayload = FoodLogEntryPayload(
    id = id.value,
    consumedOn = consumedOn.toString(),
    consumedAt = consumedAt.toString(),
    slot = slot.id,
    kind = kind.id,
    title = title,
    estimation = estimation.id,
    weighedCooked = weighedCooked,
    energyMilliKcal = nutrients.energy?.milliKcal,
    proteinMilligrams = nutrients.protein?.milligrams,
    carbsMilligrams = nutrients.carbs?.milligrams,
    fatMilligrams = nutrients.fat?.milligrams,
    fibreMilligrams = nutrients.fibre?.milligrams,
    sourceRef = sourceRef,
    amountLabel = amountLabel,
    quantityThousandths = measuredQuantity?.thousandths ?: consumedServings?.thousandths,
    quantityUnit = quantityUnit?.id,
    portionsThousandths = portions?.thousandths,
    fromPlan = fromPlan?.aggregateId,
)

internal fun MealPlanEntry.toPayload(): MealPlanEntryPayload = MealPlanEntryPayload(
    plannedOn = plannedOn.toString(),
    slot = slot.id,
    recipeId = recipeId.value,
    plannedServingsThousandths = plannedServings.thousandths,
    consumedLogEntryId = consumedLogEntryId?.value,
)
