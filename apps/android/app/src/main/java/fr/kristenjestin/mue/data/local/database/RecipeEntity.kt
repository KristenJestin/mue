package fr.kristenjestin.mue.data.local.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.Quantity
import fr.kristenjestin.mue.domain.model.Recipe
import fr.kristenjestin.mue.domain.model.RecipeId
import fr.kristenjestin.mue.domain.model.RecipeIngredient
import fr.kristenjestin.mue.domain.model.RecipeIngredientId
import fr.kristenjestin.mue.domain.model.RecipeType
import fr.kristenjestin.mue.domain.model.ReferenceUnit
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The saved shortcut of PRD_FOOD 8.3: "ces aliments, dans ces quantités".
 *
 * **No nutritional column exists here, on purpose.** 8.3 recomputes a recipe from its
 * ingredients and its `baseServings` at every display, so that correcting one food corrects
 * every recipe that uses it "sans migration". A cached total would be the migration.
 *
 * `steps` is a JSON array in one `TEXT` column rather than a sixth table. A step has no
 * identity, is never queried, never joined and never counted; its only structure is its order,
 * which a JSON array already is. The same reasoning already put an activity draft's review form
 * in one column.
 */
@Entity(
    tableName = RecipeEntity.TABLE_NAME,
    indices = [Index(value = ["name_folded"])],
)
data class RecipeEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "name_folded")
    val nameFolded: String,

    @ColumnInfo(name = "type")
    val type: String,

    @ColumnInfo(name = "base_servings")
    val baseServings: Int,

    @ColumnInfo(name = "description")
    val description: String? = null,

    @ColumnInfo(name = "prep_time_minutes")
    val prepTimeMinutes: Int? = null,

    @ColumnInfo(name = "steps")
    val steps: String,

    @ColumnInfo(name = "image_ref")
    val imageRef: String? = null,

    @ColumnInfo(name = "is_favourite")
    val isFavourite: Boolean,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
) {
    companion object {
        const val TABLE_NAME = "recipe"
    }
}

/**
 * One ingredient of one recipe (PRD_FOOD 8.3). Quantities are for the whole recipe, never per
 * serving, so nothing here has to be divided before it is stored.
 *
 * **`recipe_id` is a foreign key with an index; `food_id` is an index and deliberately not a
 * foreign key.** The first makes deleting a recipe one cascading statement instead of a table
 * scan, and Room refuses to compile a declared foreign key without the index that serves it.
 * The second is PRD_FOOD 21.2: "une recette peut référencer un aliment que le client n'a pas
 * encore reçu … il ne rejette pas l'agrégat". A constraint would reject exactly the arrival
 * order the sync PRD guarantees is legal, and [foodName] is the snapshot 21.2 asks the client to
 * display in the meantime.
 */
@Entity(
    tableName = RecipeIngredientEntity.TABLE_NAME,
    foreignKeys = [
        ForeignKey(
            entity = RecipeEntity::class,
            parentColumns = ["id"],
            childColumns = ["recipe_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["recipe_id"]),
        Index(value = ["food_id"]),
    ],
)
data class RecipeIngredientEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "recipe_id")
    val recipeId: String,

    @ColumnInfo(name = "food_id")
    val foodId: String,

    @ColumnInfo(name = "quantity_thousandths")
    val quantityThousandths: Int,

    @ColumnInfo(name = "unit")
    val unit: String,

    @ColumnInfo(name = "position")
    val position: Int,

    /** PRD_FOOD 21.2: what the row is shown as until the food it names arrives. */
    @ColumnInfo(name = "food_name")
    val foodName: String? = null,
) {
    companion object {
        const val TABLE_NAME = "recipe_ingredient"
    }
}

/** The two rows of one recipe, read together because 21.2 synchronises them together. */
data class RecipeWithIngredients(
    val recipe: RecipeEntity,
    val ingredients: List<RecipeIngredientEntity>,
)

private val stepsFormat = Json
private val stepsSerializer = ListSerializer(String.serializer())

/**
 * A column that will not parse yields no steps rather than a crash: the steps are prose beside
 * a recipe, and losing them must not make the ingredients — the part that produces numbers —
 * unreadable.
 */
internal fun decodeSteps(raw: String): List<String> = try {
    stepsFormat.decodeFromString(stepsSerializer, raw)
} catch (_: IllegalArgumentException) {
    emptyList()
}

internal fun encodeSteps(steps: List<String>): String =
    stepsFormat.encodeToString(stepsSerializer, steps)

fun RecipeEntity.toDomain(): Recipe = Recipe(
    id = RecipeId(id),
    name = name,
    type = RecipeType.fromId(type),
    baseServings = baseServings,
    description = description,
    prepTimeMinutes = prepTimeMinutes,
    steps = decodeSteps(steps),
    imageRef = imageRef,
    isFavourite = isFavourite,
)

fun Recipe.toEntity(createdAt: Long, updatedAt: Long): RecipeEntity = RecipeEntity(
    id = id.value,
    name = name,
    nameFolded = nameFolded,
    type = type.id,
    baseServings = baseServings,
    description = description,
    prepTimeMinutes = prepTimeMinutes,
    steps = encodeSteps(steps),
    imageRef = imageRef,
    isFavourite = isFavourite,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

/**
 * A quantity below one thousandth of a gram cannot exist — `Quantity` refuses zero — so a row
 * carrying one is unreadable rather than silently zero, and the ingredient is dropped by
 * [toDomainOrNull] instead of contributing nothing to a total that would still look complete.
 */
fun RecipeIngredientEntity.toDomainOrNull(): RecipeIngredient? {
    val amount = Quantity.ofThousandthsOrNull(quantityThousandths.toLong()) ?: return null
    return RecipeIngredient(
        id = RecipeIngredientId(id),
        foodId = FoodId(foodId),
        quantity = amount,
        unit = ReferenceUnit.fromId(unit),
        position = position,
        foodName = foodName,
    )
}

fun RecipeIngredient.toEntity(recipeId: String): RecipeIngredientEntity = RecipeIngredientEntity(
    id = id.value,
    recipeId = recipeId,
    foodId = foodId.value,
    quantityThousandths = quantity.thousandths,
    unit = unit.id,
    position = position,
    foodName = foodName,
)

/** Kept beside the entity so the folded form of a recipe name has exactly one definition. */
internal fun foldName(name: String): String = Food.fold(name)
