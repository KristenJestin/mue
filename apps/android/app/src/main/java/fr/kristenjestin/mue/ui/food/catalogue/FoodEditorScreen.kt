package fr.kristenjestin.mue.ui.food.catalogue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.ReferenceUnit
import fr.kristenjestin.mue.ui.components.MueBottomSheet
import fr.kristenjestin.mue.ui.components.MueIcon
import fr.kristenjestin.mue.ui.components.MueIcons
import fr.kristenjestin.mue.ui.components.MuePreviewHost
import fr.kristenjestin.mue.ui.components.MuePrimaryButton
import fr.kristenjestin.mue.ui.components.MueSecondaryButton
import fr.kristenjestin.mue.ui.components.MueSegmentedChoice
import fr.kristenjestin.mue.ui.components.MueStickyActionRamp
import fr.kristenjestin.mue.ui.components.MueStickyBottomAction
import fr.kristenjestin.mue.ui.components.MueSubScreenScaffold
import fr.kristenjestin.mue.ui.components.MueSurfaceCard
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.components.MueTextField
import fr.kristenjestin.mue.ui.food.FoodTestTags
import fr.kristenjestin.mue.ui.theme.MueTheme

/** The five per-100 fields, keyed by the names `FoodTestTags.nutrientField` already reserves. */
private const val ENERGY_KEY = "energy"
private const val PROTEIN_KEY = "protein"
private const val CARBS_KEY = "carbs"
private const val FAT_KEY = "fat"
private const val FIBRE_KEY = "fibre"

/**
 * The `Food editor` sheet (PRD_FOOD 7), wired to the catalogue.
 *
 * [onFinished] fires once the form has saved, duplicated or deleted: the sheet has nothing left
 * to show and the stack drops it. It is an effect rather than a callback inside `onSave` because
 * the write is asynchronous and the answer — saved, or refused by PRD_FOOD 9.1 — only arrives
 * afterwards.
 */
@Composable
internal fun FoodEditorRoute(
    foodId: FoodId?,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    prefillName: String? = null,
    viewModel: FoodEditorViewModel = foodEditorViewModel(foodId, prefillName),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.isFinished) {
        if (state.isFinished) onFinished()
    }

    FoodEditorScreen(
        state = state,
        actions = FoodEditorActions(
            onNameChange = viewModel::onNameChange,
            onBrandChange = viewModel::onBrandChange,
            onBarcodeChange = viewModel::onBarcodeChange,
            onReferenceUnitChange = viewModel::onReferenceUnitChange,
            onEnergyChange = viewModel::onEnergyChange,
            onProteinChange = viewModel::onProteinChange,
            onCarbsChange = viewModel::onCarbsChange,
            onFatChange = viewModel::onFatChange,
            onFibreChange = viewModel::onFibreChange,
            onServingLabelChange = viewModel::onServingLabelChange,
            onServingSizeChange = viewModel::onServingSizeChange,
            onSave = viewModel::onSave,
            onDeleteRequested = viewModel::onDeleteRequested,
            onDeleteConfirmed = viewModel::onDeleteConfirmed,
            onDeletionDismissed = viewModel::onDeletionDismissed,
            onBack = onFinished,
        ),
        modifier = modifier,
    )
}

/** Eleven fields and four verbs, gathered so the screen's signature stays readable. */
internal data class FoodEditorActions(
    val onNameChange: (String) -> Unit = {},
    val onBrandChange: (String) -> Unit = {},
    val onBarcodeChange: (String) -> Unit = {},
    val onReferenceUnitChange: (ReferenceUnit) -> Unit = {},
    val onEnergyChange: (String) -> Unit = {},
    val onProteinChange: (String) -> Unit = {},
    val onCarbsChange: (String) -> Unit = {},
    val onFatChange: (String) -> Unit = {},
    val onFibreChange: (String) -> Unit = {},
    val onServingLabelChange: (String) -> Unit = {},
    val onServingSizeChange: (String) -> Unit = {},
    val onSave: () -> Unit = {},
    val onDeleteRequested: () -> Unit = {},
    val onDeleteConfirmed: () -> Unit = {},
    val onDeletionDismissed: () -> Unit = {},
    val onBack: () -> Unit = {},
)

/**
 * One form for the three things PRD_FOOD 9.3 and FR-CATALOG-003 ask of a food card: creating one,
 * correcting one, and duplicating a reference entry.
 *
 * **Every bound comes from `FoodValidation`.** The errors below are the strings that object
 * publishes, placed beside the field each of them belongs to (PRD_FOOD 15), and nothing is ever
 * cleared when one appears.
 *
 * The five nutrition fields stay visible when `Show energy` is off. FR-FOOD-010 hides *displayed*
 * figures and requires the rest of the module to keep working; a form whose inputs disappeared
 * would be exactly the broken journey PRD_FOOD 22 forbids.
 */
@Composable
internal fun FoodEditorScreen(
    state: FoodEditorUiState,
    actions: FoodEditorActions,
    modifier: Modifier = Modifier,
) {
    val spacing = MueTheme.spacing
    val colors = MueTheme.colors
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    var actionHeight by remember { mutableStateOf(0.dp) }

    Box(modifier = modifier.fillMaxSize().testTag(FoodTestTags.FOOD_EDITOR)) {
        MueSubScreenScaffold(
            title = state.title,
            onNavigateBack = actions.onBack,
            navigationIcon = {
                MueIcon(MueIcons.ARROW_LEFT, tint = colors.textSecondary, size = 18.dp)
            },
            navigationContentDescription = FoodCatalogueMessages.BACK,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    // The shipped split: the viewport ends above the solid block, the ramp is
                    // left over live content so a thumb in the fade still scrolls.
                    .padding(bottom = (actionHeight - MueStickyActionRamp).coerceAtLeast(0.dp))
                    .verticalScroll(scroll)
                    .padding(bottom = MueStickyActionRamp),
                verticalArrangement = Arrangement.spacedBy(spacing.lg),
            ) {
                ProvenanceCard(state)

                MueTextField(
                    label = FoodCatalogueMessages.NAME_LABEL,
                    value = state.name,
                    onValueChange = actions.onNameChange,
                    modifier = Modifier.testTag(FoodTestTags.FOOD_NAME_FIELD),
                    placeholder = FoodCatalogueMessages.NAME_PLACEHOLDER,
                    errorMessage = state.nameError,
                    enabled = !state.isReadOnly,
                    singleLine = false,
                )

                MueTextField(
                    label = FoodCatalogueMessages.BRAND_LABEL,
                    value = state.brand,
                    onValueChange = actions.onBrandChange,
                    modifier = Modifier.testTag(FoodTestTags.FOOD_BRAND_FIELD),
                    placeholder = FoodCatalogueMessages.OPTIONAL_PLACEHOLDER,
                    errorMessage = state.brandError,
                    enabled = !state.isReadOnly,
                )

                MueTextField(
                    label = FoodCatalogueMessages.BARCODE_LABEL,
                    value = state.barcode,
                    onValueChange = actions.onBarcodeChange,
                    modifier = Modifier.testTag(FoodTestTags.BARCODE_FIELD),
                    placeholder = FoodCatalogueMessages.OPTIONAL_PLACEHOLDER,
                    errorMessage = state.barcodeError,
                    enabled = !state.isReadOnly,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )

                /* PRD_FOOD 8.6: gram or millilitre, chosen once and never converted afterwards. */
                MueSurfaceCard(
                    shape = MueTheme.shapes.field,
                    verticalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    MueText(
                        FoodCatalogueMessages.UNIT_LABEL,
                        MueTheme.typography.label,
                        color = colors.textTertiary,
                    )
                    MueSegmentedChoice(
                        options = ReferenceUnit.entries,
                        selected = state.referenceUnit,
                        onSelect = { if (!state.isReadOnly) actions.onReferenceUnitChange(it) },
                        label = FoodCatalogueMessages::unitLabel,
                        modifier = Modifier.testTag(FoodTestTags.REFERENCE_UNIT_PICKER),
                    )
                }

                Section(FoodCatalogueMessages.VALUES_TITLE, state.basisLabel)

                MueText(
                    FoodCatalogueMessages.VALUES_HINT,
                    MueTheme.typography.caption,
                    color = colors.textTertiary,
                )

                NutrientField(
                    key = ENERGY_KEY,
                    label = FoodCatalogueMessages.ENERGY_LABEL,
                    suffix = FoodCatalogueMessages.ENERGY_UNIT_SUFFIX,
                    value = state.energy,
                    error = state.energyError,
                    enabled = !state.isReadOnly,
                    onValueChange = actions.onEnergyChange,
                )
                NutrientField(
                    key = PROTEIN_KEY,
                    label = FoodCatalogueMessages.PROTEIN_LABEL,
                    suffix = FoodCatalogueMessages.MACRO_UNIT_SUFFIX,
                    value = state.protein,
                    error = state.proteinError,
                    enabled = !state.isReadOnly,
                    onValueChange = actions.onProteinChange,
                )
                NutrientField(
                    key = CARBS_KEY,
                    label = FoodCatalogueMessages.CARBS_LABEL,
                    suffix = FoodCatalogueMessages.MACRO_UNIT_SUFFIX,
                    value = state.carbs,
                    error = state.carbsError,
                    enabled = !state.isReadOnly,
                    onValueChange = actions.onCarbsChange,
                )
                NutrientField(
                    key = FAT_KEY,
                    label = FoodCatalogueMessages.FAT_LABEL,
                    suffix = FoodCatalogueMessages.MACRO_UNIT_SUFFIX,
                    value = state.fat,
                    error = state.fatError,
                    enabled = !state.isReadOnly,
                    onValueChange = actions.onFatChange,
                )
                NutrientField(
                    key = FIBRE_KEY,
                    label = FoodCatalogueMessages.FIBRE_LABEL,
                    suffix = FoodCatalogueMessages.MACRO_UNIT_SUFFIX,
                    value = state.fibre,
                    error = state.fibreError,
                    enabled = !state.isReadOnly,
                    onValueChange = actions.onFibreChange,
                )

                /*
                 * PRD_FOOD 15's one rule that belongs to no single field, so it is shown under
                 * the group it judges rather than beside a field it would blame unfairly.
                 */
                state.macroSumError?.let { message ->
                    MueText(message, MueTheme.typography.caption, color = colors.error)
                }

                Section(FoodCatalogueMessages.SERVING_TITLE, null)

                MueText(
                    FoodCatalogueMessages.SERVING_HINT,
                    MueTheme.typography.caption,
                    color = colors.textTertiary,
                )

                MueTextField(
                    label = FoodCatalogueMessages.SERVING_LABEL_LABEL,
                    value = state.servingLabel,
                    onValueChange = actions.onServingLabelChange,
                    modifier = Modifier.testTag(FoodTestTags.SERVING_FIELD),
                    placeholder = FoodCatalogueMessages.SERVING_LABEL_PLACEHOLDER,
                    enabled = !state.isReadOnly,
                )

                MueTextField(
                    label = FoodCatalogueMessages.SERVING_SIZE_LABEL,
                    value = state.servingSize,
                    onValueChange = actions.onServingSizeChange,
                    placeholder = FoodCatalogueMessages.OPTIONAL_PLACEHOLDER,
                    suffix = state.referenceUnit.symbol,
                    errorMessage = state.servingError,
                    enabled = !state.isReadOnly,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )

                if (state.saveRefused) {
                    MueText(
                        FoodCatalogueMessages.SAVE_REFUSED,
                        MueTheme.typography.caption,
                        color = colors.error,
                    )
                }
            }
        }

        MueStickyBottomAction(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { size ->
                    actionHeight = with(density) { size.height.toDp() }
                },
            coversContent = scroll.canScrollForward,
        ) {
            MuePrimaryButton(
                label = state.primaryLabel,
                onClick = actions.onSave,
                modifier = Modifier.testTag(FoodTestTags.CONFIRM_BUTTON),
            )

            if (state.canDelete) {
                MueSecondaryButton(
                    label = FoodCatalogueMessages.DELETE,
                    onClick = actions.onDeleteRequested,
                    modifier = Modifier.testTag(FoodTestTags.DELETE_BUTTON),
                    contentColor = colors.error,
                )
            }
        }
    }

    FoodDeletionSheet(
        state = state.deletion,
        foodName = state.name,
        onConfirm = actions.onDeleteConfirmed,
        onDismiss = actions.onDeletionDismissed,
    )
}

/**
 * FR-CATALOG-004: "chaque aliment affiche sa source", and PRD_FOOD 9.1 says what that means for
 * a reference entry — it is read here, duplicated, and never written.
 */
@Composable
private fun ProvenanceCard(state: FoodEditorUiState) {
    val colors = MueTheme.colors
    MueSurfaceCard(
        shape = MueTheme.shapes.field,
        verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.sm),
    ) {
        Column {
            MueText(
                text = state.sourceLabel,
                style = MueTheme.typography.bodyStrong,
            )
            MueText(
                text = state.basisLabel,
                style = MueTheme.typography.caption,
                color = colors.textTertiary,
            )
        }
        if (state.isReadOnly) {
            MueText(
                FoodCatalogueMessages.READ_ONLY_NOTE,
                MueTheme.typography.caption,
                color = colors.textSecondary,
            )
        }
        if (state.keepsSource) {
            MueText(
                FoodCatalogueMessages.KEEPS_SOURCE_NOTE,
                MueTheme.typography.caption,
                color = colors.textSecondary,
            )
        }
    }
}

@Composable
private fun Section(title: String, basis: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.xxs)) {
        MueText(title, MueTheme.typography.sectionTitle)
        basis?.let {
            MueText(it, MueTheme.typography.caption, color = MueTheme.colors.textQuiet)
        }
    }
}

/**
 * One per-100 value.
 *
 * A decimal keyboard, because PRD_FOOD 15 allows tenths and `FoodValidation` accepts both `.`
 * and `,` whatever the phone's language is.
 */
@Composable
private fun NutrientField(
    key: String,
    label: String,
    suffix: String,
    value: String,
    error: String?,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    MueTextField(
        label = label,
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.testTag(FoodTestTags.nutrientField(key)),
        placeholder = FoodCatalogueMessages.OPTIONAL_PLACEHOLDER,
        suffix = suffix,
        errorMessage = error,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}

/**
 * The question, and then the answer when the answer is a refusal (PRD_FOOD 9.3 and 17).
 *
 * One sheet for both, because the person is in one conversation: "delete this?" and "no, and
 * here is why" belong in the same place. A successful deletion has nothing to say here — the
 * sheet it was asked from closes with it.
 */
@Composable
private fun FoodDeletionSheet(
    state: FoodDeletionUiState?,
    foodName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    MueBottomSheet(
        visible = state != null,
        onDismissRequest = onDismiss,
        title = FoodCatalogueMessages.DELETE_TITLE,
    ) {
        when (state) {
            null -> Unit

            FoodDeletionUiState.Confirming -> {
                MueText(
                    FoodCatalogueMessages.deleteBody(foodName),
                    MueTheme.typography.body,
                    color = MueTheme.colors.textSecondary,
                )
                /*
                 * No tag of its own. `FoodTestTags.DELETE_BUTTON` already belongs to the editor's
                 * own control, which is still composed behind this sheet, and a second node
                 * answering to it would make every assertion ambiguous. The word on the button
                 * is enough to name it, and it is not the word on the one behind.
                 */
                MuePrimaryButton(
                    label = FoodCatalogueMessages.DELETE_CONFIRM,
                    onClick = onConfirm,
                )
                MueSecondaryButton(
                    label = FoodCatalogueMessages.DELETE_CANCEL,
                    onClick = onDismiss,
                )
            }

            is FoodDeletionUiState.Refused -> {
                /*
                 * PRD_FOOD 17: the refusal names what stands in the way. `UsedByRecipes` carries
                 * the recipe names precisely so this sentence can print them rather than count
                 * them, and PRD_FOOD 22 makes that naming an acceptance criterion.
                 */
                MueText(
                    state.message,
                    MueTheme.typography.body,
                    color = MueTheme.colors.textSecondary,
                )
                MueSecondaryButton(
                    label = FoodCatalogueMessages.CLOSE_DELETION,
                    onClick = onDismiss,
                )
            }
        }
    }
}

// region previews

@Preview(name = "Food editor — a copied product", showBackground = true, backgroundColor = 0xFF101012, heightDp = 1200)
@Composable
private fun FoodEditorPreview() {
    MuePreviewHost(padding = 0) {
        FoodEditorScreen(
            state = previewFoodEditorState(),
            actions = FoodEditorActions(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** PRD_FOOD 9.1: nothing to type, one thing to do. */
@Preview(name = "Food editor — reference entry", showBackground = true, backgroundColor = 0xFF101012, heightDp = 1200)
@Composable
private fun FoodEditorReferencePreview() {
    MuePreviewHost(padding = 0) {
        FoodEditorScreen(
            state = referenceFoodEditorState(),
            actions = FoodEditorActions(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** PRD_FOOD 15: a refused value sits beside its field and empties nothing. */
@Preview(name = "Food editor — refused values", showBackground = true, backgroundColor = 0xFF101012, heightDp = 1200)
@Composable
private fun FoodEditorRefusedPreview() {
    MuePreviewHost(padding = 0) {
        FoodEditorScreen(
            state = refusedFoodEditorState(),
            actions = FoodEditorActions(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** The same form on the narrowest phone at the largest text size (PRD_FOOD 18). */
@Preview(
    name = "Food editor — 360 dp · largest font",
    showBackground = true,
    backgroundColor = 0xFF101012,
    widthDp = 360,
    heightDp = 1200,
    fontScale = 2.0f,
)
@Composable
private fun FoodEditorNarrowPreview() {
    MuePreviewHost(padding = 0) {
        FoodEditorScreen(
            state = previewFoodEditorState(),
            actions = FoodEditorActions(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

// endregion
