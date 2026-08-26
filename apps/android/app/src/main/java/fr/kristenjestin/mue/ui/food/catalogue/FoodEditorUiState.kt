package fr.kristenjestin.mue.ui.food.catalogue

import androidx.compose.runtime.Immutable
import fr.kristenjestin.mue.domain.logic.FoodValidation
import fr.kristenjestin.mue.domain.logic.NutrientsValidation
import fr.kristenjestin.mue.domain.logic.UsualServing
import fr.kristenjestin.mue.domain.logic.Validated
import fr.kristenjestin.mue.domain.logic.errorMessage
import fr.kristenjestin.mue.domain.logic.isValid
import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.FoodSource
import fr.kristenjestin.mue.domain.model.ReferenceUnit
import fr.kristenjestin.mue.ui.food.FoodIcons
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The food card being written, exactly as it was typed (PRD_FOOD 9.3 and 15).
 *
 * Every field is a raw input string rather than a parsed value, for the reason `ActivityDraft`
 * gives: a half-typed `7,` has to come back unchanged after the process is killed, and PRD_FOOD
 * 15 requires that a refused value never empty the form. It is stored as one JSON blob under one
 * `SavedStateHandle` key, again as `ActivityDraft` is — eleven `Bundle` keys would be eleven
 * chances for two of them to be restored out of step.
 *
 * **A blank field is a `null`, never a zero.** Nothing here supplies a default value for a
 * missing figure, and [FoodValidation] refuses to either; PRD_FOOD 15 says so twice and
 * PRD_FOOD 13.2 draws the difference.
 */
@Serializable
data class FoodEditorDraft(
    val name: String = "",
    val brand: String = "",
    val barcode: String = "",
    val unitId: String = ReferenceUnit.GRAM.id,
    val energy: String = "",
    val protein: String = "",
    val carbs: String = "",
    val fat: String = "",
    val fibre: String = "",
    val servingLabel: String = "",
    val servingSize: String = "",
    /**
     * True once `Save` has been pressed.
     *
     * PRD_FOOD 15 asks for a refused value to be "signalée à côté du champ concerné"; it does
     * not ask for a form to be scolded halfway through the first word. Errors appear on the
     * first attempt and are recomputed on every keystroke after it, so a corrected field stops
     * complaining as soon as it is right.
     */
    val attempted: Boolean = false,
) {

    val referenceUnit: ReferenceUnit get() = ReferenceUnit.fromId(unitId)

    fun toJson(): String = FORMAT.encodeToString(serializer(), this)

    companion object {

        /**
         * Total and non-throwing: a draft that cannot be read is a draft that was never there,
         * which costs some typing where trusting it would cost the first frame.
         */
        fun fromJson(raw: String?): FoodEditorDraft? {
            if (raw.isNullOrBlank()) return null
            return runCatching { FORMAT.decodeFromString(serializer(), raw) }.getOrNull()
        }

        /** A blank card, optionally already carrying the term a fruitless search was made on. */
        fun blank(prefillName: String? = null): FoodEditorDraft =
            FoodEditorDraft(name = prefillName.orEmpty())

        /**
         * An existing card, opened to be corrected or duplicated.
         *
         * The figures are written back as plain editable numbers rather than through
         * [fr.kristenjestin.mue.domain.logic.FoodLabels], which renders for the eye: `≈ 59 kcal`
         * is not something anyone can go on typing into. A value that is **unknown stays blank**
         * — the one thing this conversion must never do is turn a `null` into `0`.
         */
        fun of(food: Food): FoodEditorDraft = FoodEditorDraft(
            name = food.name,
            brand = food.brand.orEmpty(),
            barcode = food.barcode.orEmpty(),
            unitId = food.referenceUnit.id,
            energy = food.per100.energy?.let { canonical(it.milliKcal) }.orEmpty(),
            protein = food.per100.protein?.let { canonical(it.milligrams) }.orEmpty(),
            carbs = food.per100.carbs?.let { canonical(it.milligrams) }.orEmpty(),
            fat = food.per100.fat?.let { canonical(it.milligrams) }.orEmpty(),
            fibre = food.per100.fibre?.let { canonical(it.milligrams) }.orEmpty(),
            servingLabel = food.servingLabel.orEmpty(),
            servingSize = food.servingSize?.let { canonical(it.thousandths) }.orEmpty(),
        )

        /**
         * A stored thousandth written as an editable decimal — `274000` reads `274`, `10500`
         * reads `10.5`.
         *
         * Integer arithmetic and a full stop, exactly as `FoodLabels` does it and for the same
         * two reasons: no `Double` takes part so nothing drifts to `0.0000001`, and no separator
         * follows the phone's region so a value typed on one device is the value read on
         * another. [FoodValidation] accepts both separators on the way back in.
         */
        private fun canonical(value: Int): String {
            val whole = value / THOUSANDTHS
            val fraction = (value % THOUSANDTHS).toString().padStart(3, '0').trimEnd('0')
            return if (fraction.isEmpty()) "$whole" else "$whole.$fraction"
        }

        /** Every canonical unit of the module is a whole thousandth of its display unit. */
        private const val THOUSANDTHS: Int = 1_000

        private val FORMAT = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }
    }
}

/**
 * PRD_FOOD 15's table applied to the food form, **by calling it and never by restating it**.
 *
 * Not one bound appears in this file. `0 to 900 kcal`, `0 to 100 g`, the 80-character name, the
 * `1 to 2 000` portion and the rule that the known macronutrients may not add up past 100 g are
 * each already a validator on [FoodValidation], which in turn asks the value class that owns the
 * constant. A ceiling written a second time here would be a ceiling that could disagree with the
 * one the repository enforces.
 */
internal data class FoodEditorValidation(
    val name: Validated<String>,
    val brand: Validated<String?>,
    val barcode: Validated<String?>,
    val nutrients: NutrientsValidation,
    val serving: Validated<UsualServing?>,
) {

    val isValid: Boolean
        get() = name.isValid &&
            brand.isValid &&
            barcode.isValid &&
            nutrients is NutrientsValidation.Valid &&
            serving.isValid

    val invalid: NutrientsValidation.Invalid?
        get() = nutrients as? NutrientsValidation.Invalid

    companion object {
        fun of(draft: FoodEditorDraft): FoodEditorValidation = FoodEditorValidation(
            name = FoodValidation.validateName(draft.name),
            brand = FoodValidation.validateBrand(draft.brand),
            barcode = FoodValidation.validateBarcode(draft.barcode),
            nutrients = FoodValidation.validatePer100(
                energy = draft.energy,
                protein = draft.protein,
                carbs = draft.carbs,
                fat = draft.fat,
                fibre = draft.fibre,
            ),
            serving = FoodValidation.validateUsualServing(draft.servingLabel, draft.servingSize),
        )
    }
}

/** Which of the three things the one form is doing (FR-CATALOG-003). */
enum class FoodEditorMode {
    /** A food that does not exist yet, blank or prefilled from a fruitless search. */
    CREATE,

    /** A personal food or a copied product, being corrected. */
    EDIT,

    /** PRD_FOOD 9.1: a Ciqual entry, which is read and duplicated but never written. */
    REFERENCE,
}

/** What the deletion of a food is currently asking or explaining (PRD_FOOD 9.3 and 17). */
@Immutable
sealed interface FoodDeletionUiState {

    /** The question, before anything has been asked of the repository. */
    data object Confirming : FoodDeletionUiState

    /**
     * The answer, when it is a refusal.
     *
     * One shape for the three refusals rather than three, because what the screen does with all
     * of them is identical — it prints the sentence and offers the way out. What differs is the
     * sentence itself, and [FoodCatalogueMessages] is where the three are written.
     */
    data class Refused(val message: String) : FoodDeletionUiState
}

/**
 * The `Food editor` sheet (PRD_FOOD 7, 9.3, 15 and FR-CATALOG-003).
 *
 * One form creates, corrects and duplicates, and [mode] is what tells the three apart — the
 * arrangement `FoodRoute.FoodEditor` already assumes by carrying an optional id.
 *
 * The per-field errors are the strings [FoodValidation] publishes, verbatim. They are `null`
 * until [FoodEditorDraft.attempted], so a form is never red before it has been submitted, and
 * they never clear the fields they sit beside (PRD_FOOD 15).
 *
 * `Show energy` deliberately does **not** blank the five nutrition fields. FR-FOOD-010 hides
 * *displayed* values and requires the rest of the module to keep working, and a person who has
 * hidden the figures must still be able to write a food card down — a form whose inputs vanished
 * would be the broken journey that criterion exists to prevent. What the preference hides in the
 * catalogue is the browse list's figures, which are a reading rather than an entry.
 */
@Immutable
data class FoodEditorUiState(
    val mode: FoodEditorMode = FoodEditorMode.CREATE,
    val source: FoodSource = FoodSource.CUSTOM,
    val isLoading: Boolean = false,
    val name: String = "",
    val brand: String = "",
    val barcode: String = "",
    val referenceUnit: ReferenceUnit = ReferenceUnit.GRAM,
    val energy: String = "",
    val protein: String = "",
    val carbs: String = "",
    val fat: String = "",
    val fibre: String = "",
    val servingLabel: String = "",
    val servingSize: String = "",
    val nameError: String? = null,
    val brandError: String? = null,
    val barcodeError: String? = null,
    val energyError: String? = null,
    val proteinError: String? = null,
    val carbsError: String? = null,
    val fatError: String? = null,
    val fibreError: String? = null,
    /** PRD_FOOD 15's one rule that belongs to no single field. */
    val macroSumError: String? = null,
    val servingError: String? = null,
    /** PRD_FOOD 9.1: the repository refused the write because the row is reference data. */
    val saveRefused: Boolean = false,
    /** The sheet has done its work and the stack may drop it. */
    val isFinished: Boolean = false,
    val deletion: FoodDeletionUiState? = null,
) {

    val isReadOnly: Boolean get() = mode == FoodEditorMode.REFERENCE

    /** PRD_FOOD 9.1: a reference entry is duplicated instead of being edited in place. */
    val canDuplicate: Boolean get() = isReadOnly

    /** Only a row that exists and is not reference data can be removed (PRD_FOOD 9.1 and 9.3). */
    val canDelete: Boolean get() = mode == FoodEditorMode.EDIT

    val title: String
        get() = when (mode) {
            FoodEditorMode.CREATE -> FoodCatalogueMessages.NEW_TITLE
            FoodEditorMode.EDIT -> FoodCatalogueMessages.EDIT_TITLE
            FoodEditorMode.REFERENCE -> FoodCatalogueMessages.REFERENCE_TITLE
        }

    val primaryLabel: String
        get() = if (canDuplicate) FoodCatalogueMessages.DUPLICATE else FoodCatalogueMessages.SAVE

    val sourceLabel: String get() = FoodCatalogueMessages.sourceLabel(source)

    val sourceIconName: String get() = FoodIcons.forSource(source)

    val basisLabel: String get() = FoodCatalogueMessages.per100(referenceUnit)

    /** PRD_FOOD 9.2: a copied product keeps its provenance however much it is corrected. */
    val keepsSource: Boolean get() = source == FoodSource.OPEN_FOOD_FACTS

    companion object {

        fun of(
            draft: FoodEditorDraft,
            mode: FoodEditorMode,
            source: FoodSource,
            isLoading: Boolean = false,
            saveRefused: Boolean = false,
            isFinished: Boolean = false,
            deletion: FoodDeletionUiState? = null,
        ): FoodEditorUiState {
            val validation = FoodEditorValidation.of(draft)
            val show = draft.attempted
            val nutrients = validation.invalid

            return FoodEditorUiState(
                mode = mode,
                source = source,
                isLoading = isLoading,
                name = draft.name,
                brand = draft.brand,
                barcode = draft.barcode,
                referenceUnit = draft.referenceUnit,
                energy = draft.energy,
                protein = draft.protein,
                carbs = draft.carbs,
                fat = draft.fat,
                fibre = draft.fibre,
                servingLabel = draft.servingLabel,
                servingSize = draft.servingSize,
                nameError = validation.name.errorMessage.takeIf { show },
                brandError = validation.brand.errorMessage.takeIf { show },
                barcodeError = validation.barcode.errorMessage.takeIf { show },
                energyError = nutrients?.energyError.takeIf { show },
                proteinError = nutrients?.proteinError.takeIf { show },
                carbsError = nutrients?.carbsError.takeIf { show },
                fatError = nutrients?.fatError.takeIf { show },
                fibreError = nutrients?.fibreError.takeIf { show },
                macroSumError = nutrients?.sumError.takeIf { show },
                servingError = validation.serving.errorMessage.takeIf { show },
                saveRefused = saveRefused,
                isFinished = isFinished,
                deletion = deletion,
            )
        }
    }
}

/**
 * The `Food` a validated draft describes, or null when PRD_FOOD 15 refuses one of its fields.
 *
 * [existing] is what a correction keeps that a form cannot express. PRD_FOOD 9.2 keeps a copied
 * product's `source`, its `sourceId` and its barcode across any number of corrections, and
 * PRD_FOOD 8.6 says a `cookedRatio` "n'est jamais saisi à la main" — it is derived from the
 * raw/cooked pair at import — so the form carries no field for it and a correction must not drop
 * the one an entry already has.
 *
 * A duplicate passes a **new id** and [FoodSource.CUSTOM] instead: PRD_FOOD 9.1 makes the copy a
 * personal food, and it keeps no `sourceId`, which would claim the reference table produced a row
 * it does not know about.
 */
internal fun FoodEditorDraft.toFoodOrNull(
    id: FoodId,
    source: FoodSource,
    existing: Food? = null,
): Food? {
    val validation = FoodEditorValidation.of(this)
    if (!validation.isValid) return null

    val keepsProvenance = existing != null && existing.source == source
    val serving = (validation.serving as Validated.Valid).value

    return Food(
        id = id,
        name = (validation.name as Validated.Valid).value,
        source = source,
        referenceUnit = referenceUnit,
        per100 = (validation.nutrients as NutrientsValidation.Valid).nutrients,
        brand = (validation.brand as Validated.Valid).value,
        barcode = (validation.barcode as Validated.Valid).value,
        sourceId = existing?.sourceId.takeIf { keepsProvenance },
        sourceVersion = existing?.sourceVersion.takeIf { keepsProvenance },
        servingLabel = serving?.label,
        servingSize = serving?.size,
        /*
         * The ratio follows the aliment rather than the provenance. A duplicated pasta is the
         * same pasta, and PRD_FOOD 8.6 derives the ratio from the raw/cooked pair of the table
         * rather than from anything a person types — so dropping it on a copy would silently
         * take the cooked-weight selector away from a food that legitimately has one.
         */
        cookedRatio = existing?.cookedRatio,
        rawLabel = existing?.rawLabel ?: Food.DEFAULT_RAW_LABEL,
        cookedLabel = existing?.cookedLabel ?: Food.DEFAULT_COOKED_LABEL,
        /* PRD_FOOD 14: an Open Food Facts image is the remote product's, not the copy's. */
        imageRef = existing?.imageRef.takeIf { keepsProvenance },
    )
}
