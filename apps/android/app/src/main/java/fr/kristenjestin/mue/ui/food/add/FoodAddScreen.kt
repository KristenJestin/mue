package fr.kristenjestin.mue.ui.food.add

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.kristenjestin.mue.domain.logic.FoodLabels
import fr.kristenjestin.mue.domain.model.FoodLogEntryId
import fr.kristenjestin.mue.domain.model.MealSlot
import fr.kristenjestin.mue.ui.activity.ActivityIcons
import fr.kristenjestin.mue.ui.components.MueChoiceCard
import fr.kristenjestin.mue.ui.components.MueChoiceRow
import fr.kristenjestin.mue.ui.components.MueIcon
import fr.kristenjestin.mue.ui.components.MueIcons
import fr.kristenjestin.mue.ui.components.MuePickerField
import fr.kristenjestin.mue.ui.components.MuePreviewHost
import fr.kristenjestin.mue.ui.components.MuePrimaryButton
import fr.kristenjestin.mue.ui.components.MueScreenTitle
import fr.kristenjestin.mue.ui.components.MueSecondaryButton
import fr.kristenjestin.mue.ui.components.MueSegmentedChoice
import fr.kristenjestin.mue.ui.components.MueSplitRow
import fr.kristenjestin.mue.ui.components.MueStickyActionRamp
import fr.kristenjestin.mue.ui.components.MueStickyBottomAction
import fr.kristenjestin.mue.ui.components.MueSubScreenScaffold
import fr.kristenjestin.mue.ui.components.MueSurfaceCard
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.components.MueTextField
import fr.kristenjestin.mue.ui.food.FoodIcons
import fr.kristenjestin.mue.ui.food.FoodTestTags
import fr.kristenjestin.mue.ui.food.scan.BarcodeScannerPreview
import fr.kristenjestin.mue.ui.food.scan.FoodCameraPermission
import fr.kristenjestin.mue.ui.food.scan.rememberFoodCameraPermission
import fr.kristenjestin.mue.ui.theme.MueMinTouchTarget
import fr.kristenjestin.mue.ui.theme.MueTheme
import java.time.LocalDate
import java.time.LocalTime

/** Two moments a row, so a moment's name has room to wrap rather than to be cut. */
private const val SLOTS_PER_ROW = 2

private val CloseIconSize: Dp = 18.dp

/** The step buttons of the portion counter, at the touch minimum PRD_FOOD 18 sets. */
private val StepButtonSize: Dp = MueMinTouchTarget

/** How far a step button fades when the counter has reached PRD_FOOD 15's end of the range. */
private const val DisabledStepAlpha = 0.2f

/**
 * The viewfinder's height (FR-FOOD-003).
 *
 * Fixed rather than an aspect ratio, and short rather than full-bleed. A barcode is read at
 * arm's length from thirty centimetres away and needs a band, not a portrait frame; keeping it
 * short is also what leaves the barcode field and its button on the same screenful at a large
 * font scale, which is the whole of PRD_FOOD 18's claim that the two are equals.
 */
private val ScannerPreviewHeight: Dp = 220.dp

/**
 * `Add food` (PRD_FOOD 7), wired to the journal and the catalogue.
 *
 * [viewModel] is the flow's shared instance — the picker writes into the very same one — and is a
 * parameter only so a test can hand its own in.
 *
 * The three callbacks are the three things this sheet cannot do itself: close, open the picker,
 * and hand over to the recipes. Each is one line on the stack, and the stack is `FoodNavHost`'s.
 */
@Composable
internal fun FoodAddRoute(
    date: LocalDate?,
    slot: MealSlot?,
    entryId: FoodLogEntryId?,
    onClose: () -> Unit,
    onSearchFood: () -> Unit,
    onUseRecipe: () -> Unit,
    /**
     * PRD_FOOD 17: "Produit absent d'Open Food Facts → bascule vers la création manuelle
     * **pré-remplie**." The sheet knows the number; only the stack can open the editor.
     */
    onCreateFood: (barcode: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FoodAddViewModel = foodAddViewModel(),
) {
    // Idempotent: coming back from the picker recomposes this sheet, and the draft must survive.
    LaunchedEffect(date, slot, entryId) { viewModel.start(date, slot, entryId) }

    val state by viewModel.uiState.collectAsStateWithLifecycle()

    /*
     * The one way out of this sheet, whichever control was used.
     *
     * `Close` and the system back have to behave identically — two ways out of one sheet must not
     * differ silently — so both go through here, and [FoodAddViewModel.onLeft] decides what
     * becomes of the draft: kept when it holds something typed, forgotten when it does not.
     *
     * The handler is nested inside the module's own, and nested handlers resolve innermost first,
     * so back reaches this before `FoodNavHost` pops the stack. It disappears with the sheet:
     * pushing the food picker takes this composable out of composition, which is exactly why
     * going to the picker is not "leaving" and costs the draft nothing.
     */
    val leave = remember(viewModel, onClose) {
        {
            viewModel.onLeft()
            onClose()
        }
    }
    BackHandler(onBack = leave)

    /*
     * PRD_FOOD 18's camera, read here rather than inside the panel.
     *
     * It is a property of the process and not of the draft, so it is read where a composable can
     * read it and folded into the state that is already computed. `withCamera` is a pure function
     * and owns every sentence it produces, which is what keeps PRD_FOOD 17's three explanations
     * provable on the JVM while the permission itself cannot be.
     */
    val camera = rememberFoodCameraPermission()
    val context = LocalContext.current

    val actions = remember(viewModel, leave, onSearchFood, onUseRecipe, onCreateFood, camera) {
        FoodAddActions(
            onClose = leave,
            onSearchFood = onSearchFood,
            onUseRecipe = onUseRecipe,
            onQuickAdd = viewModel::onQuickAddChosen,
            onScan = viewModel::onScanChosen,
            onBarcodeChange = viewModel::onBarcodeChange,
            onBarcodeScanned = viewModel::onBarcodeScanned,
            onLookUpBarcode = viewModel::onLookUpBarcode,
            onRetryLookup = viewModel::onRetryLookup,
            onUseScannedProduct = viewModel::onUseScannedProduct,
            onCreateFromBarcode = { viewModel.barcodeToCreateFrom()?.let(onCreateFood) },
            /*
             * One control, two doors, and which one it is depends on whether Android would still
             * show a prompt. Before the first ask it is the prompt; after a refusal Android
             * refuses to show one at all, so offering it again would be a button that does
             * nothing — FR-TIMER-012 learned this on the notification permission and answered it
             * from `Profile` the same way. The label says which door it is; `withCamera` writes it.
             */
            onCameraAction = {
                if (camera.canRequest) {
                    camera.request()
                } else {
                    context.startActivity(FoodCameraPermission.settingsIntent(context))
                }
            },
            onBackToPaths = viewModel::onBackToPaths,
            onQuantityChange = viewModel::onQuantityChange,
            onPortionStep = viewModel::onPortionStep,
            onCookedStateChange = viewModel::onCookedStateChange,
            onQuickTitleChange = viewModel::onQuickTitleChange,
            onQuickEnergyChange = viewModel::onQuickEnergyChange,
            onQuickProteinChange = viewModel::onQuickProteinChange,
            onServingsChange = viewModel::onServingsChange,
            onSlotSelected = viewModel::onSlotSelected,
            onOpenTimePicker = viewModel::onShowTimePicker,
            onDismissTimePicker = viewModel::onDismissTimePicker,
            onTimePicked = viewModel::onTimePicked,
            onSave = viewModel::save,
            onSaved = {
                viewModel.onSaveConfirmationFinished()
                onClose()
            },
            onDelete = viewModel::delete,
            onDeleted = {
                viewModel.onDeleteConfirmationFinished()
                onClose()
            },
        )
    }

    FoodAddScreen(
        // The scan panel is the only part of this state a ViewModel cannot finish, so it is
        // finished here and nowhere else. Every other stage is untouched by this line.
        state = state.copy(
            scan = state.scan?.withCamera(
                isGranted = camera.isGranted,
                isAvailable = camera.isAvailable,
                canRequest = camera.canRequest,
            ),
        ),
        actions = actions,
        modifier = modifier,
    )
}

/** Everything the sheet can ask for, so its layout can be driven with no database behind it. */
@Stable
internal class FoodAddActions(
    val onClose: () -> Unit = {},
    val onSearchFood: () -> Unit = {},
    val onUseRecipe: () -> Unit = {},
    val onQuickAdd: () -> Unit = {},
    /** FR-FOOD-003: the fourth way in — the camera and the typed number, one path. */
    val onScan: () -> Unit = {},
    val onBarcodeChange: (String) -> Unit = {},
    /** A code the camera read. It lands in the field, then takes the field's own road. */
    val onBarcodeScanned: (String) -> Unit = {},
    val onLookUpBarcode: () -> Unit = {},
    val onRetryLookup: () -> Unit = {},
    /** PRD_FOOD 9.2: the copy into the local catalogue happens here and nowhere earlier. */
    val onUseScannedProduct: () -> Unit = {},
    /** PRD_FOOD 17: the creation prefilled with the code nothing was found for. */
    val onCreateFromBarcode: () -> Unit = {},
    /**
     * PRD_FOOD 18: the system prompt, at most once in the life of the install — and after a
     * refusal, the Android settings page instead, because a second prompt is never shown.
     */
    val onCameraAction: () -> Unit = {},
    /** PRD_FOOD 7: back to the ways in, from whichever one was taken. */
    val onBackToPaths: () -> Unit = {},
    val onQuantityChange: (String) -> Unit = {},
    /** True adds a usual portion, false removes one (PRD_FOOD 15: half a portion at a time). */
    val onPortionStep: (Boolean) -> Unit = {},
    val onCookedStateChange: (Boolean) -> Unit = {},
    val onQuickTitleChange: (String) -> Unit = {},
    val onQuickEnergyChange: (String) -> Unit = {},
    val onQuickProteinChange: (String) -> Unit = {},
    val onServingsChange: (String) -> Unit = {},
    val onSlotSelected: (MealSlot) -> Unit = {},
    val onOpenTimePicker: () -> Unit = {},
    val onDismissTimePicker: () -> Unit = {},
    val onTimePicked: (LocalTime) -> Unit = {},
    val onSave: () -> Unit = {},
    /** Fired once the button's confirmation has played out, as on the shipped forms. */
    val onSaved: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onDeleted: () -> Unit = {},
)

/**
 * The sheet itself: a way in, then how much, then which moment (PRD_FOOD 7 and 10.3).
 *
 * State is handed in whole, so every test drives what reaches the screen rather than how a
 * ViewModel got there.
 */
@Composable
internal fun FoodAddScreen(
    state: FoodAddUiState,
    actions: FoodAddActions,
    modifier: Modifier = Modifier,
) {
    var actionHeight by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    val spacing = MueTheme.spacing

    // The band sits outside the scaffold, as on `Log activity`: it is chrome over the whole
    // window, so its edge runs the full width instead of stopping at the gutter.
    Box(modifier = modifier.fillMaxSize()) {
        MueSubScreenScaffold(
            title = state.screenTitle,
            onNavigateBack = actions.onClose,
            navigationIcon = {
                MueIcon(
                    iconName = MueIcons.CLOSE,
                    tint = MueTheme.colors.textSecondary,
                    size = CloseIconSize,
                )
            },
            navigationContentDescription = FoodAddMessages.CLOSE,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag(FoodTestTags.ADD_SHEET)
                    /*
                     * The pinned action's clearance, split where the band itself is split.
                     *
                     * Outside the scroll the viewport ends above the *solid* part, so a field
                     * taking focus is brought somewhere the keyboard and the action leave
                     * visible. The ramp is left in, because it draws over live content — which
                     * is both what dissolves the rows leaving the screen and what lets a thumb
                     * landing in the fade still scroll, rather than meeting the 112 dp dead
                     * zone a solid band would put there.
                     */
                    .padding(bottom = (actionHeight - MueStickyActionRamp).coerceAtLeast(0.dp))
                    .verticalScroll(scroll)
                    // Inside the scroll, so the last card comes to rest clear of the fade.
                    .padding(bottom = MueStickyActionRamp),
                verticalArrangement = Arrangement.spacedBy(spacing.lg),
            ) {
                when {
                    /*
                     * A line being corrected is read back from the journal, and until it arrives
                     * the sheet knows nothing about it. Drawing the ways in for that instant
                     * would flash `What did you eat?` over an entry that already answers it.
                     */
                    state.isLoading -> Unit

                    else -> Stage(state, actions)
                }
            }
        }

        if (state.showsSaveAction && !state.isLoading) {
            MueStickyBottomAction(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .onSizeChanged { size ->
                        actionHeight = with(density) { size.height.toDp() }
                    },
                coversContent = scroll.canScrollForward,
            ) {
                SaveArea(state, actions)
            }
        }
    }

    FoodAddTimeSheet(
        visible = state.isTimePickerVisible,
        selected = state.time,
        onDismiss = actions.onDismissTimePicker,
        onConfirm = actions.onTimePicked,
    )
}

/** Whichever of PRD_FOOD 7's stages the sheet is on. */
@Composable
private fun ColumnScope.Stage(state: FoodAddUiState, actions: FoodAddActions) {
    if (state.canReturnToPaths) BackToPaths(actions)

    when (state.stage) {
        FoodAddStage.PATHS -> WaysIn(actions)

        // FR-FOOD-003. The panel owns its own action, so no `Save entry` is raised under it.
        FoodAddStage.SCAN -> state.scan?.let { ScanSection(it, actions) }

        FoodAddStage.AMOUNT -> {
            ChosenFood(state, actions)
            AmountSection(state, actions)
            Figures(state)
            MomentSection(state, actions)
        }

        FoodAddStage.QUICK -> {
            QuickAddSection(state, actions)
            Figures(state)
            MomentSection(state, actions)
        }

        FoodAddStage.SERVINGS -> {
            ServingsSection(state, actions)
            Figures(state)
            MomentSection(state, actions)
        }

        FoodAddStage.FROZEN -> {
            MissingFood()
            Figures(state)
            MomentSection(state, actions)
        }
    }
}

// region the ways in (PRD_FOOD 7)

/**
 * PRD_FOOD 7's four ways in, all four of them.
 *
 * The barcode used to be missing from this list, and the note that stood here said why: no ML Kit
 * dependency, no camera permission, no `ProductLookup` implementation and nothing registered on
 * `AppContainer.food`. All four now exist, and the tile leads to a stage that works with the
 * camera and works without it — which is the condition PRD_FOOD 18 sets, not an extra.
 *
 * Its position is second, between the search and the recipes, because it is the other way of
 * reaching a *catalogue* food: the two paths that end on `How much?` sit together, and the two
 * that do not follow them.
 */
@Composable
private fun ColumnScope.WaysIn(actions: FoodAddActions) {
    MueScreenTitle(
        title = FoodAddMessages.PATHS_TITLE,
        eyebrow = FoodAddMessages.PATHS_EYEBROW,
        modifier = Modifier.padding(top = MueTheme.spacing.md, bottom = MueTheme.spacing.sm),
    )

    WayIn(
        iconName = ActivityIcons.SEARCH,
        title = FoodAddMessages.SEARCH_PATH,
        description = FoodAddMessages.SEARCH_PATH_DESCRIPTION,
        testTag = FoodTestTags.ADD_BY_SEARCH,
        onClick = actions.onSearchFood,
    )
    WayIn(
        iconName = FoodIcons.BARCODE,
        title = FoodAddMessages.SCAN_PATH,
        description = FoodAddMessages.SCAN_PATH_DESCRIPTION,
        testTag = FoodTestTags.ADD_BY_SCAN,
        onClick = actions.onScan,
    )
    WayIn(
        iconName = FoodIcons.CHEF_HAT,
        title = FoodAddMessages.RECIPE_PATH,
        description = FoodAddMessages.RECIPE_PATH_DESCRIPTION,
        testTag = FoodTestTags.ADD_BY_RECIPE,
        onClick = actions.onUseRecipe,
    )
    WayIn(
        iconName = MueIcons.ZAP,
        title = FoodAddMessages.QUICK_PATH,
        description = FoodAddMessages.QUICK_PATH_DESCRIPTION,
        testTag = FoodTestTags.ADD_QUICK,
        onClick = actions.onQuickAdd,
    )
}

/**
 * The step back to the three cards, above whatever the chosen path put on screen.
 *
 * First in the column, because it is what the reader is looking for when they realise they took
 * the wrong way in — and because anywhere below the quantity it would be under the fold on a
 * small phone at a large font size.
 *
 * A quiet row rather than a button: it undoes a choice, it does not perform the screen's action,
 * and giving it a button's weight would put it in competition with `Save entry`. The touch target
 * is still the full [MueMinTouchTarget] and the whole row is the control, arrow included, so
 * nobody has to hit the glyph.
 */
@Composable
private fun BackToPaths(actions: FoodAddActions) {
    val colors = MueTheme.colors
    Row(
        modifier = Modifier
            .clip(MueTheme.shapes.field)
            .clickable(role = Role.Button, onClick = actions.onBackToPaths)
            .heightIn(min = MueMinTouchTarget)
            .padding(horizontal = MueTheme.spacing.sm)
            .testTag(FoodTestTags.ADD_BACK_TO_PATHS)
            /*
             * One announcement rather than two: the arrow and the label are one control, and the
             * `Day` screen's own add row is announced the same way. Cleared *after* `clickable`,
             * which is what keeps the action and the role on the node while silencing the glyph
             * and the text under it.
             */
            .clearAndSetSemantics {
                contentDescription = FoodAddMessages.CHANGE_PATH
                role = Role.Button
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MueTheme.spacing.sm),
    ) {
        MueIcon(iconName = MueIcons.ARROW_LEFT, tint = colors.textTertiary, size = 16.dp)
        // Never capped: the label is the whole of what this control says it does.
        MueText(
            text = FoodAddMessages.CHANGE_PATH,
            style = MueTheme.typography.caption,
            color = colors.textSecondary,
        )
    }
}

/**
 * One way in.
 *
 * A card rather than a tile: the description is a full sentence, and a fixed-height tile would
 * either ellipsise it or force it into two words a line at a large font scale. Nothing here is
 * `maxLines`-capped, so the card grows instead of cutting.
 */
@Composable
private fun WayIn(
    iconName: String,
    title: String,
    description: String,
    testTag: String,
    onClick: () -> Unit,
) {
    val colors = MueTheme.colors
    MueSurfaceCard(
        modifier = Modifier.fillMaxWidth().testTag(testTag),
        shape = MueTheme.shapes.card,
        contentPadding = PaddingValues(MueTheme.spacing.xl),
        onClick = onClick,
        onClickLabel = title,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clearAndSetSemantics { contentDescription = "$title. $description" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(MueMinTouchTarget)
                    .clip(MueTheme.shapes.field)
                    .background(colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                MueIcon(iconName = iconName, tint = colors.onAccentSoft, size = 18.dp)
            }
            Column(
                modifier = Modifier.weight(1f).padding(start = MueTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.xxs),
            ) {
                MueText(title, MueTheme.typography.bodyStrong)
                MueText(description, MueTheme.typography.caption, color = colors.textTertiary)
            }
        }
    }
}

// endregion

// region the food and its quantity (FR-FOOD-002 and 006)

/**
 * The chosen food, its provenance, and the way back to the picker (FR-CATALOG-004).
 *
 * Split by [MueSplitRow] rather than laid out as a plain `Row`, for the reason that component
 * records — and this card is where the reason was still costing a name. A `Row` measures its
 * unweighted children first and at whatever width they ask for, so the icon tile and the chevron
 * were taken out of the line before the name saw any of it. At the largest font scale on a 360 dp
 * phone that left the name 168 dp, and the catalogue's longest entry needs 185 dp for the single
 * word `pomegranate` alone: it came out broken **in the middle of a word**, over ten lines.
 * `onNodeWithText` could not see it — the semantics string is the whole name however the glyphs
 * fall — and `FoodAddLayoutTest` reads the text layout instead, which is what caught it.
 *
 * The measured split gives the chevron its own line once the name cannot be read beside it, and
 * the name then gets the card's full width. Below that scale this draws exactly what it drew.
 */
@Composable
private fun ChosenFood(state: FoodAddUiState, actions: FoodAddActions) {
    val food = state.food ?: return
    val colors = MueTheme.colors
    MueSurfaceCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MueTheme.shapes.card,
        contentPadding = PaddingValues(MueTheme.spacing.xl),
        onClick = actions.onSearchFood,
        onClickLabel = FoodAddMessages.CHANGE_FOOD,
    ) {
        MueSplitRow(
            modifier = Modifier
                .fillMaxWidth()
                .clearAndSetSemantics { contentDescription = food.description },
            gap = MueTheme.spacing.md,
            stackedGap = MueTheme.spacing.sm,
            start = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    /*
                     * The gutter between the tile and the name is the row's own spacing rather
                     * than padding on the column, so it is part of what `minIntrinsicWidth`
                     * reports — which is what makes the split's decision honest instead of
                     * optimistic by 16 dp.
                     */
                    horizontalArrangement = Arrangement.spacedBy(MueTheme.spacing.lg),
                ) {
                    Box(
                        modifier = Modifier
                            .size(MueMinTouchTarget)
                            .clip(MueTheme.shapes.field)
                            .background(colors.accentSoft),
                        contentAlignment = Alignment.Center,
                    ) {
                        MueIcon(
                            iconName = food.iconName,
                            tint = colors.onAccentSoft,
                            size = 18.dp,
                        )
                    }
                    Column(
                        verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.xxs),
                    ) {
                        // Never capped: PRD_FOOD 15 lets a name run to 80 characters, and a name
                        // cut short still satisfies every assertion a semantics string can make.
                        MueText(food.name, MueTheme.typography.bodyStrong)
                        MueText(food.meta, MueTheme.typography.micro, color = colors.textTertiary)
                    }
                }
            },
            // The card opens the picker again; a tappable card with no mark on it says nothing.
            end = {
                MueIcon(
                    iconName = MueIcons.CHEVRON_RIGHT,
                    tint = colors.textTertiary,
                    size = 16.dp,
                )
            },
        )
    }
}

/**
 * How much of it (FR-FOOD-006), in the two readings PRD_FOOD 8.6 keeps at once.
 *
 * The counter and the weight field are both here, and neither is a mode: stepping the counter
 * fills the field with what it resolves to, and typing in the field drops the counter. The
 * raw/cooked selector appears only on a food that carries a ratio, and the field's own label
 * carries the state word with it — which is the point of the whole block: `600 g` of something
 * weighed cooked is not `600 g` of it as the catalogue describes it.
 */
@Composable
private fun AmountSection(state: FoodAddUiState, actions: FoodAddActions) {
    val amount = state.amount ?: return
    val colors = MueTheme.colors

    FoodSectionCard(title = FoodAddMessages.AMOUNT_SECTION) {
        Column(verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.md)) {
            amount.servingLabel?.let { label ->
                PortionCounter(amount = amount, servingLabel = label, actions = actions)
            }

            amount.cookedStates?.let { states ->
                Column(verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.sm)) {
                    MueText(
                        text = FoodAddMessages.COOKED_STATE_LABEL,
                        style = MueTheme.typography.label,
                        color = colors.textTertiary,
                    )
                    MueSegmentedChoice(
                        options = listOf(false, true),
                        selected = amount.weighedCooked,
                        onSelect = actions.onCookedStateChange,
                        label = { cooked -> if (cooked) states.last() else states.first() },
                        modifier = Modifier.testTag(FoodTestTags.UNIT_PICKER),
                    )
                }
            }

            MueTextField(
                label = amount.quantityLabel,
                value = amount.quantity,
                onValueChange = actions.onQuantityChange,
                modifier = Modifier.testTag(FoodTestTags.QUANTITY_FIELD),
                suffix = amount.unitSymbol,
                errorMessage = state.errors.quantity,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )

            // PRD_FOOD 13.1: the weight the values are actually computed from, when the number
            // on the scale and the number behind the figures are two different numbers.
            amount.referenceNote?.let { note ->
                MueText(note, MueTheme.typography.micro, color = colors.accent)
            }

            if (amount.servingLabel != null) {
                MueText(
                    text = FoodAddMessages.PORTIONS_HINT,
                    style = MueTheme.typography.micro,
                    color = colors.textQuiet,
                )
            }
        }
    }
}

/**
 * PRD_FOOD 15: half a portion at a time, from 0.5 to 20, and the bounds disable the buttons.
 *
 * The two steps are chevrons rather than a plus and a minus because the app has never imported a
 * `minus` vector, and a drawable is not this screen's to add. Each carries its own label, so what
 * a chevron means is said rather than inferred from which way it points.
 */
@Composable
private fun PortionCounter(
    amount: FoodAmountUiState,
    servingLabel: String,
    actions: FoodAddActions,
) {
    val colors = MueTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().testTag(FoodTestTags.SERVINGS_STEPPER),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MueTheme.spacing.md),
    ) {
        StepButton(
            iconName = MueIcons.CHEVRON_DOWN,
            label = FoodAddMessages.FEWER_PORTIONS,
            enabled = amount.canRemovePortion,
            onClick = { actions.onPortionStep(false) },
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.xxs),
        ) {
            MueText(
                text = FoodAddMessages.PORTIONS_LABEL,
                style = MueTheme.typography.label,
                color = colors.textTertiary,
            )
            MueText(
                text = "${amount.portionsValue} ${FoodLabels.TIMES} $servingLabel",
                style = MueTheme.typography.bodyStrong,
                color = if (amount.portions == null) colors.textQuiet else colors.textPrimary,
            )
        }
        StepButton(
            iconName = MueIcons.CHEVRON_UP,
            label = FoodAddMessages.MORE_PORTIONS,
            enabled = amount.canAddPortion,
            onClick = { actions.onPortionStep(true) },
        )
    }
}

@Composable
private fun StepButton(
    iconName: String,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = MueTheme.colors
    Box(
        modifier = Modifier
            .size(StepButtonSize)
            .clip(MueTheme.shapes.field)
            .background(colors.surfaceStrong)
            .alpha(if (enabled) 1f else DisabledStepAlpha)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        MueIcon(iconName = iconName, tint = colors.textSecondary, size = 18.dp)
    }
}

// endregion

// region the scan (FR-FOOD-003, PRD_FOOD 9.2, 17 and 18)

/**
 * The scan stage: a viewfinder when there is one, a field always, and whatever the lookup said.
 *
 * **Read the order.** The camera is on top because it is what most people came for, and the field
 * is *not* hidden behind it: PRD_FOOD 18 makes the typed number "une alternative complète", and a
 * complete alternative that only appears once something has failed is a fallback wearing a
 * different word. Both are on screen at once, always, and the camera fills the field rather than
 * bypassing it — so what was decoded can be checked against the packet before it is looked up.
 *
 * Every branch below is one row of PRD_FOOD 17's table, and none of them empties the screen: an
 * unreadable code leaves the scanner running, a refused camera leaves the field, a failed lookup
 * leaves both plus the creation the number is already in.
 */
@Composable
private fun ColumnScope.ScanSection(scan: FoodScanUiState, actions: FoodAddActions) {
    val colors = MueTheme.colors

    FoodSectionCard(title = FoodAddMessages.SCAN_SECTION) {
        Column(verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.md)) {
            if (scan.isCameraLive) {
                BarcodeScannerPreview(
                    onBarcode = actions.onBarcodeScanned,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(ScannerPreviewHeight)
                        .testTag(FoodTestTags.SCANNER_PREVIEW),
                    contentDescription = FoodAddMessages.SCANNER_DESCRIPTION,
                )
            }

            /*
             * PRD_FOOD 17's explanation, in the accent rather than the error colour, because
             * nothing has gone wrong: a person who prefers not to give a camera to a food diary
             * has made a reasonable choice, and the path they are on is the same path.
             */
            scan.cameraExplanation?.let { explanation ->
                MueText(
                    text = explanation,
                    style = MueTheme.typography.caption,
                    color = colors.textSecondary,
                )
            }

            scan.cameraActionLabel?.let { label ->
                MueSecondaryButton(label = label, onClick = actions.onCameraAction)
            }

            MueTextField(
                label = FoodAddMessages.BARCODE_LABEL,
                value = scan.barcode,
                onValueChange = actions.onBarcodeChange,
                modifier = Modifier.testTag(FoodTestTags.BARCODE_FIELD),
                placeholder = FoodAddMessages.BARCODE_PLACEHOLDER,
                // The refusal is `FoodValidation.validateBarcode`'s own sentence, never a second
                // wording of the same rule — the module's standing arrangement.
                errorMessage = scan.barcodeError,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )

            MueText(
                text = if (scan.isCameraLive) {
                    FoodAddMessages.BARCODE_HINT
                } else {
                    // No camera on screen means no sentence about pointing one at anything.
                    FoodAddMessages.BARCODE_HINT_TYPED_ONLY
                },
                style = MueTheme.typography.micro,
                color = colors.textQuiet,
            )

            MuePrimaryButton(
                label = if (scan.isLookingUp) FoodAddMessages.LOOKING_UP else FoodAddMessages.LOOK_UP,
                onClick = actions.onLookUpBarcode,
                enabled = scan.canLookUp,
            )
        }
    }

    scan.found?.let { found -> ScannedProduct(found, scan.saveError, actions) }
    scan.notice?.let { notice -> ScanNotice(notice, actions) }
}

/**
 * The product a lookup found, before anything has been written (PRD_FOOD 9.2 and 17).
 *
 * It exists as a screen at all because of one line of PRD_FOOD 9.2: the product is copied locally
 * "au moment de l'**ajout**". Showing the card first is what turns that clause into behaviour —
 * an incomplete record is visibly incomplete, with its dashes, before it becomes a row somebody
 * has to find and correct later.
 *
 * The five figures go through [Figures]' own component, so a fibre Open Food Facts marked
 * `estimate` reads `—` here exactly as it will on the food's card and in the journal. Nothing on
 * this path can turn it into `≈ 0 g`, because nothing on this path computes anything.
 */
@Composable
private fun ColumnScope.ScannedProduct(
    found: FoodScanFoundUiState,
    saveError: String?,
    actions: FoodAddActions,
) {
    val colors = MueTheme.colors
    FoodSectionCard(title = FoodAddMessages.SCANNED_PRODUCT_SECTION) {
        Column(verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.md)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clearAndSetSemantics { contentDescription = found.description },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(MueMinTouchTarget)
                        .clip(MueTheme.shapes.field)
                        .background(colors.accentSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    MueIcon(iconName = found.iconName, tint = colors.onAccentSoft, size = 18.dp)
                }
                Column(
                    modifier = Modifier.weight(1f).padding(start = MueTheme.spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.xxs),
                ) {
                    // Never capped: a product name is what tells somebody they scanned the right
                    // jar, and half of one tells them nothing.
                    MueText(found.name, MueTheme.typography.bodyStrong)
                    MueText(found.meta, MueTheme.typography.caption, color = colors.textTertiary)
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = found.per100.description },
                verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.xs),
            ) {
                MueText(
                    text = found.per100.header,
                    style = MueTheme.typography.label,
                    color = colors.textTertiary,
                )
                found.per100.rows.forEach { row ->
                    FoodFigureRow(
                        label = row.label,
                        value = row.value,
                        modifier = Modifier.testTag(FoodTestTags.nutrientField(row.key)),
                    )
                }
            }

            found.incompleteNote?.let { note ->
                MueText(
                    text = note,
                    style = MueTheme.typography.micro,
                    color = colors.textQuiet,
                )
            }

            saveError?.let { message ->
                MueText(
                    text = message,
                    style = MueTheme.typography.caption,
                    color = colors.error,
                    modifier = Modifier.semantics { error(message) },
                )
            }

            MuePrimaryButton(label = found.actionLabel, onClick = actions.onUseScannedProduct)
        }
    }
}

/**
 * What the panel says when there is no product (PRD_FOOD 17).
 *
 * The message is a live region: it arrives after a wait nobody watched, and a screen-reader user
 * who has put the phone down while a request was out must be told the answer rather than having
 * to go looking for it. It does not take focus — PRD_FOOD 18 forbids that of an added line, and
 * the same courtesy applies here.
 */
@Composable
private fun ColumnScope.ScanNotice(notice: FoodScanNoticeUiState, actions: FoodAddActions) {
    val colors = MueTheme.colors
    FoodSectionCard(title = notice.message) {
        Column(
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.md),
        ) {
            notice.detail?.let { detail ->
                MueText(
                    text = detail,
                    style = MueTheme.typography.caption,
                    color = colors.textSecondary,
                )
            }

            // The order is the order of what is likely to help. A failure is worth one more
            // attempt before anybody types a label out; a missing product never is, and offers
            // only the creation.
            if (notice.canRetry) {
                MuePrimaryButton(
                    label = FoodAddMessages.TRY_LOOKUP_AGAIN,
                    onClick = actions.onRetryLookup,
                )
            }
            if (notice.canCreate) {
                MueSecondaryButton(
                    label = FoodAddMessages.CREATE_FROM_BARCODE,
                    onClick = actions.onCreateFromBarcode,
                )
            }
        }
    }
}

// endregion

// region the quick add and the recipe correction

/** FR-FOOD-005: a name, an energy, and an optional protein that stays unknown when unstated. */
@Composable
private fun QuickAddSection(state: FoodAddUiState, actions: FoodAddActions) {
    val quick = state.quick ?: return
    FoodSectionCard(title = FoodAddMessages.QUICK_SECTION) {
        Column(verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.md)) {
            MueTextField(
                label = FoodAddMessages.QUICK_NAME_LABEL,
                value = quick.title,
                onValueChange = actions.onQuickTitleChange,
                modifier = Modifier.testTag(FoodTestTags.QUICK_NAME_FIELD),
                errorMessage = state.errors.title,
            )
            MueTextField(
                label = FoodAddMessages.QUICK_ENERGY_LABEL,
                value = quick.energy,
                onValueChange = actions.onQuickEnergyChange,
                modifier = Modifier.testTag(FoodTestTags.QUICK_ENERGY_FIELD),
                suffix = FoodLabels.ENERGY_UNIT,
                errorMessage = state.errors.energy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            MueTextField(
                label = FoodAddMessages.QUICK_PROTEIN_LABEL,
                value = quick.protein,
                onValueChange = actions.onQuickProteinChange,
                suffix = FoodLabels.MACRO_UNIT,
                errorMessage = state.errors.protein,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            MueText(
                text = FoodAddMessages.QUICK_APPROXIMATE,
                style = MueTheme.typography.micro,
                color = MueTheme.colors.textQuiet,
            )
        }
    }
}

/** FR-FOOD-008 on a recipe line: the servings eaten, rescaled from the frozen snapshot. */
@Composable
private fun ServingsSection(state: FoodAddUiState, actions: FoodAddActions) {
    FoodSectionCard(title = FoodAddMessages.SERVINGS_SECTION) {
        Column(verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.md)) {
            MueTextField(
                label = FoodAddMessages.SERVINGS_LABEL,
                value = state.servings,
                onValueChange = actions.onServingsChange,
                modifier = Modifier.testTag(FoodTestTags.QUANTITY_FIELD),
                errorMessage = state.errors.servings,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            MueText(
                text = FoodAddMessages.SERVINGS_FROZEN,
                style = MueTheme.typography.micro,
                color = MueTheme.colors.textQuiet,
            )
        }
    }
}

/** PRD_FOOD 17: "aliment supprimé mais journalisé — la ligne reste intacte". */
@Composable
private fun MissingFood() {
    FoodSectionCard(title = FoodAddMessages.MISSING_FOOD) {
        MueText(
            text = FoodAddMessages.MISSING_FOOD_DETAIL,
            style = MueTheme.typography.caption,
            color = MueTheme.colors.textSecondary,
        )
    }
}

// endregion

// region the figures (PRD_FOOD 13.2)

/**
 * What the line is worth, recomputed on every keystroke (FR-FOOD-002).
 *
 * Until a quantity is given these are the food's per-100 values, which is what they *are* — not a
 * contribution of nothing, and not a row of dashes either. The two states have different headers
 * and the same five rows.
 *
 * Each row keeps its own handle and its own glyphs: `≈ 0.0 g` and `—` are two different facts
 * (PRD_FOOD 13.1), and the only way to prove the screen still tells them apart at the last step
 * is to read what it drew.
 */
@Composable
private fun Figures(state: FoodAddUiState) {
    val figures = state.figures ?: return
    FoodSectionCard(title = figures.header, description = figures.description) {
        figures.rows.forEach { row ->
            FoodFigureRow(
                label = row.label,
                value = row.value,
                /*
                 * The handle is `FoodTestTags.nutrientField`, which names exactly these five
                 * metrics. It was written for the food editor's own per-100 fields; the two
                 * screens never share a frame, and one name for one metric is what keeps a test
                 * able to say "the protein row" rather than "some dash somewhere".
                 */
                modifier = Modifier.testTag(FoodTestTags.nutrientField(row.key)),
            )
        }
    }
}

// endregion

// region when and where (PRD_FOOD 10.3)

/** The moment and the hour, preselected by FR-FOOD-007 and changed in one gesture. */
@Composable
private fun MomentSection(state: FoodAddUiState, actions: FoodAddActions) {
    FoodSectionCard(title = FoodAddMessages.SLOT_SECTION) {
        Column(verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.md)) {
            Column(
                modifier = Modifier.fillMaxWidth().testTag(FoodTestTags.SLOT_PICKER),
                verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.sm),
            ) {
                state.slots.chunked(SLOTS_PER_ROW).forEach { row ->
                    MueChoiceRow {
                        row.forEach { option ->
                            MueChoiceCard(
                                label = option.label,
                                selected = option.selected,
                                onClick = { actions.onSlotSelected(option.slot) },
                                /*
                                 * PRD_FOOD 10.3's window, on the thing being chosen.
                                 *
                                 * `MueChoiceCard` never caps a description, so at the largest font
                                 * size `05:00 – 10:00` wraps and the tile grows rather than the
                                 * hours being cut — half a window is a wrong window.
                                 */
                                description = option.hoursLabel,
                                icon = {
                                    MueIcon(
                                        iconName = option.iconName,
                                        tint = if (option.selected) {
                                            MueTheme.colors.onAccentSoft
                                        } else {
                                            MueTheme.colors.textTertiary
                                        },
                                    )
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            MuePickerField(
                label = FoodAddMessages.TIME_LABEL,
                value = state.timeLabel,
                onClick = actions.onOpenTimePicker,
                modifier = Modifier.testTag(FoodTestTags.TIME_FIELD),
                onClickLabel = FoodAddMessages.TIME_SHEET_TITLE,
                trailingText = FoodAddMessages.CHANGE_TIME,
            )

            /*
             * PRD_FOOD 10.3: the two controls no longer sit side by side saying nothing to each
             * other. When the hour and the moment disagree the screen says so, in the accent
             * rather than the error colour — nothing is wrong, a late breakfast is a real meal,
             * and the sentence ends by confirming the choice rather than asking for it back.
             *
             * Uncapped: this is the only place the pairing is explained, and half an explanation
             * is worse than none.
             */
            state.slotTimeNote?.let { note ->
                MueText(
                    text = note,
                    style = MueTheme.typography.micro,
                    color = MueTheme.colors.accent,
                    modifier = Modifier.testTag(FoodTestTags.SLOT_TIME_NOTE),
                )
            }

            /*
             * Which day this line lands on (PRD_FOOD 10.1 and FR-FOOD-009).
             *
             * The sheet does not choose the date — the journal it was opened from did — but a
             * retroactive line has to say which day it is being written to, or `Save entry`
             * would write to a day the reader has no way of seeing from here.
             */
            MueText(
                text = state.dateLabel,
                style = MueTheme.typography.micro,
                color = MueTheme.colors.textQuiet,
                modifier = Modifier.semantics { contentDescription = state.dateDescription },
            )

            state.errors.time?.let { message ->
                MueText(
                    text = message,
                    style = MueTheme.typography.caption,
                    color = MueTheme.colors.error,
                    modifier = Modifier.semantics { error(message) },
                )
            }

            state.errors.date?.let { message ->
                MueText(
                    text = message,
                    style = MueTheme.typography.caption,
                    color = MueTheme.colors.error,
                    modifier = Modifier.semantics { error(message) },
                )
            }
        }
    }
}

// endregion

// region saving (FR-FOOD-008)

@Composable
private fun ColumnScope.SaveArea(state: FoodAddUiState, actions: FoodAddActions) {
    // PRD_FOOD 15: the refusal beside the action, so a reader hears why the save did nothing.
    state.errors.summary?.let { message ->
        MueText(
            text = message,
            style = MueTheme.typography.caption,
            color = MueTheme.colors.error,
            modifier = Modifier.semantics {
                error(message)
                liveRegion = LiveRegionMode.Polite
            },
        )
    }

    state.saveError?.let { message ->
        MueText(
            text = message,
            style = MueTheme.typography.caption,
            color = MueTheme.colors.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }

    if (state.justDeleted) {
        LaunchedEffect(Unit) { actions.onDeleted() }
        MueText(
            text = FoodAddMessages.ENTRY_DELETED,
            style = MueTheme.typography.bodyStrong,
            color = MueTheme.colors.accent,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }

    MuePrimaryButton(
        label = state.saveLabel,
        onClick = actions.onSave,
        modifier = Modifier
            .testTag(FoodTestTags.CONFIRM_BUTTON)
            .semantics { contentDescription = state.saveDescription },
        enabled = !state.justDeleted,
        success = state.justSaved,
        onSuccessFinished = actions.onSaved,
    )

    if (state.saveError != null) {
        MueSecondaryButton(label = FoodAddMessages.TRY_AGAIN, onClick = actions.onSave)
    }

    if (state.canDelete) {
        MueSecondaryButton(
            label = FoodAddMessages.DELETE_ENTRY,
            onClick = actions.onDelete,
            modifier = Modifier.testTag(FoodTestTags.DELETE_BUTTON),
            enabled = !state.justDeleted,
            contentColor = MueTheme.colors.error,
        )
    }
}

// endregion

// region previews

@Preview(name = "Add food — ways in", showBackground = true, backgroundColor = 0xFF101012, heightDp = 800)
@Composable
private fun FoodAddPathsPreview() {
    MuePreviewHost(padding = 0) {
        FoodAddScreen(
            state = previewPathsState(),
            actions = FoodAddActions(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * The quantity stage on a food that has both a usual portion and a cooking ratio.
 *
 * What to look at: the field's label says which state the number is read in, and the line under
 * it says what that number is counted as once converted. Without the two, `600 g` of cooked rice
 * quietly counts nearly three times the energy actually eaten.
 */
@Preview(name = "Add food — cooked rice", showBackground = true, backgroundColor = 0xFF101012, heightDp = 900)
@Composable
private fun FoodAddCookedPreview() {
    MuePreviewHost(padding = 0) {
        FoodAddScreen(
            state = previewCookedState(),
            actions = FoodAddActions(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * A late breakfast, and the two things that stop it reading as a contradiction (PRD_FOOD 10.3).
 *
 * What to look for: `05:00 – 10:00` under `Breakfast`, so a moment is no longer a word with
 * nothing behind it — and, under the time, one line naming the moment six in the evening usually
 * belongs to and confirming that the choice stands. Nothing is refused and nothing is moved: the
 * windows "ne créent aucune contrainte", and a late breakfast is a real meal.
 *
 * The step back to the ways in is at the top of the same picture, which is the other thing this
 * sheet was missing: a path chosen used to be a path kept until the line was saved.
 */
@Preview(name = "Add food — breakfast at six", showBackground = true, backgroundColor = 0xFF101012, heightDp = 900)
@Composable
private fun FoodAddLateBreakfastPreview() {
    MuePreviewHost(padding = 0) {
        FoodAddScreen(
            state = previewLateBreakfastState(),
            actions = FoodAddActions(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * The same stage on the narrowest phone at the largest font scale.
 *
 * What to look for: every food name wrapped at a space rather than mid-word, every figure whole,
 * and the five nutrient rows still readable as label-and-value pairs — stacked rather than
 * crushed once the two no longer fit side by side.
 */
@Preview(
    name = "Add food — 360 dp · largest font",
    showBackground = true,
    backgroundColor = 0xFF101012,
    widthDp = 360,
    heightDp = 900,
    fontScale = 2.0f,
)
@Composable
private fun FoodAddNarrowPreview() {
    MuePreviewHost(padding = 0) {
        FoodAddScreen(
            state = previewLongNameState(),
            actions = FoodAddActions(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * The scan with the camera refused (PRD_FOOD 17 and 18).
 *
 * What to look for: the field is a full control with its own label, placeholder and button, and
 * the sentence above it explains without scolding. Nothing here is smaller, greyer or further
 * down than it would be with the camera on — a "complete alternative" that looked like a
 * consolation would not be one.
 */
@Preview(name = "Add food — scan, camera refused", showBackground = true, backgroundColor = 0xFF101012, heightDp = 900)
@Composable
private fun FoodAddScanRefusedPreview() {
    MuePreviewHost(padding = 0) {
        FoodAddScreen(
            state = previewScanRefusedState(),
            actions = FoodAddActions(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * A product found, with a hole in it (PRD_FOOD 9.2, 13.1 and 17).
 *
 * What to look for: four figures and one `—`, on a real card, before anything has been written to
 * the catalogue. Open Food Facts *has* a fibre number for this product and marked it `estimate`;
 * refusing it is the difference between "Mue does not know" and "Mue made something up".
 */
@Preview(name = "Add food — scanned product", showBackground = true, backgroundColor = 0xFF101012, heightDp = 900)
@Composable
private fun FoodAddScanFoundPreview() {
    MuePreviewHost(padding = 0) {
        FoodAddScreen(
            state = previewScanFoundState(),
            actions = FoodAddActions(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * A quick add, and the whole of PRD_FOOD 13.1 in one card.
 *
 * Its energy is typed, its protein is not: the figures read `≈ 300 kcal` beside four `—`. Held
 * against the preview above — where an espresso's `≈ 0.0 g` is a known zero — the two drawings
 * are what the module's discipline comes down to.
 */
@Preview(name = "Add food — quick add", showBackground = true, backgroundColor = 0xFF101012, heightDp = 900)
@Composable
private fun FoodAddQuickPreview() {
    MuePreviewHost(padding = 0) {
        FoodAddScreen(
            state = previewQuickState(),
            actions = FoodAddActions(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

// endregion
