package fr.kristenjestin.mue.ui.profile

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import fr.kristenjestin.mue.domain.logic.Bmi
import fr.kristenjestin.mue.domain.logic.BmiCategory
import fr.kristenjestin.mue.domain.logic.MueValidation
import fr.kristenjestin.mue.timer.TimerNotificationPermission
import fr.kristenjestin.mue.timer.rememberTimerNotificationPermission
import fr.kristenjestin.mue.ui.components.MueContentTopFade
import fr.kristenjestin.mue.ui.components.MueHeaderChip
import fr.kristenjestin.mue.ui.components.MuePickerField
import fr.kristenjestin.mue.ui.components.MuePrimaryButton
import fr.kristenjestin.mue.ui.components.MueScreenScaffold
import fr.kristenjestin.mue.ui.components.MueScreenTitle
import fr.kristenjestin.mue.ui.components.MueSecondaryButton
import fr.kristenjestin.mue.ui.components.MueSurfaceCard
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.components.MueTextField
import fr.kristenjestin.mue.ui.components.rememberMueLocale
import fr.kristenjestin.mue.ui.sync.DataSyncSection
import fr.kristenjestin.mue.ui.sync.DataSyncUiState
import fr.kristenjestin.mue.ui.sync.ServerSettingsRoute
import fr.kristenjestin.mue.ui.sync.SyncMessages
import fr.kristenjestin.mue.ui.sync.SyncStatus
import fr.kristenjestin.mue.ui.sync.SyncViewModel
import fr.kristenjestin.mue.ui.theme.LocalReduceMotion
import fr.kristenjestin.mue.ui.theme.MueMotion
import fr.kristenjestin.mue.ui.theme.MueTheme
import fr.kristenjestin.mue.ui.timer.TimerMessages
import java.time.LocalDate

private const val SCREEN_EYEBROW = "Your reference points"
private const val SCREEN_TITLE = "Tracking shaped around you."
private const val SAVE_LABEL = "Save profile"
private const val EXPORT_LABEL = "Export weight data"
private const val EXPORT_CHOOSER_TITLE = "Export weight data"
private const val PRIVACY_TITLE = "Why do we use this data?"
private const val PRIVACY_BODY =
    "Height is used to calculate BMI. Age may provide context for future indicators. " +
        "This information stays on your device in the first version."
private const val EXPORT_BODY =
    "Export your complete weight history as a CSV file. Your name, height and date of " +
        "birth are never included."
private const val HAPTICS_TITLE = "Haptic feedback"
private const val HAPTICS_BODY =
    "Short vibrations while adjusting the scale and when a measurement is saved."
private const val NOT_SET = "Not set"

/**
 * `Profile`: the health profile, the BMI it feeds, the preferences and the CSV export.
 *
 * The bottom tab bar is drawn by the navigation layer, above this screen, so that it never
 * moves during a tab transition (PRD 8).
 */
@Composable
fun ProfileScreen(modifier: Modifier = Modifier) {
    /*
     * Sync PRD 9.1 puts `Data & sync` inside `Profile`, and 9.1's `Server settings` is a screen
     * of its own. That makes `Profile` the third tab holding more than one screen, and it models
     * the stack the way the other two do rather than adopting a library for it: one saved
     * boolean, one `AnimatedContent`, and a `BackHandler` nested inside the shell's so back
     * closes the sub-screen before it ever reaches the tab selection.
     */
    var serverSettingsOpen by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = serverSettingsOpen) { serverSettingsOpen = false }

    // Resolved outside `transitionSpec`, which runs outside composition.
    val deeper = profileStackTransition(deeper = true)
    val shallower = profileStackTransition(deeper = false)

    AnimatedContent(
        targetState = serverSettingsOpen,
        modifier = modifier,
        transitionSpec = { if (targetState) deeper else shallower },
        label = "profileStack",
    ) { settingsOpen ->
        if (settingsOpen) {
            ServerSettingsRoute(
                onNavigateBack = { serverSettingsOpen = false },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            ProfileRoot(
                onOpenServerSettings = { serverSettingsOpen = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Going deeper raises the new screen over the old one, exactly as the Activity tab's stack does;
 * reduced motion drops the movement and keeps the cross-fade.
 */
@Composable
@ReadOnlyComposable
private fun profileStackTransition(deeper: Boolean): ContentTransform {
    val enterSpec = MueMotion.spec<Float>(MueMotion.ActivityOpenMillis, MueMotion.Enter)
    val exitSpec = MueMotion.spec<Float>(MueMotion.ActivityOpenMillis, MueMotion.Exit)
    if (LocalReduceMotion.current) {
        return fadeIn(enterSpec) togetherWith fadeOut(exitSpec)
    }
    val offsetSpec = MueMotion.spec<IntOffset>(MueMotion.ActivityOpenMillis, MueMotion.Standard)
    val direction = if (deeper) 1 else -1
    return (
        slideInVertically(offsetSpec) { height -> direction * height / 8 } + fadeIn(enterSpec)
        ) togetherWith (
        slideOutVertically(offsetSpec) { height -> -direction * height / 8 } + fadeOut(exitSpec)
        )
}

@Composable
private fun ProfileRoot(onOpenServerSettings: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: ProfileViewModel = viewModel(factory = ProfileViewModel.Factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    /*
     * Sync PRD 9.1's section reads its own state holder rather than borrowing `ProfileViewModel`.
     * They share nothing — one is a form over DataStore and Room's profile row, the other is the
     * outbox and the `sync_state` row — and the settings screen needs the very same instance,
     * which it gets because both take it from the activity's store.
     */
    val syncViewModel: SyncViewModel = viewModel(factory = SyncViewModel.Factory)
    val syncState by syncViewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel, context) {
        viewModel.events.collect { event ->
            when (event) {
                is ProfileEvent.ShareCsv ->
                    if (!context.shareCsvFile(event.file, EXPORT_CHOOSER_TITLE)) {
                        viewModel.onShareFailed()
                    }
            }
        }
    }

    /*
     * FR-TIMER-012's way back. The timer asks for `POST_NOTIFICATIONS` once, in the context of
     * the first `Start timer`, and never again — so once that one chance is spent this link is
     * the only route left, and the profile is where the requirement puts it.
     *
     * `!isGranted && !canRequest` is exactly "notifications are off and Mue will not ask": it
     * hides the row before the first timer, when the prompt is still to come, and it is also
     * right below Android 13, where `canRequest` is always false and the only way to switch
     * notifications off is Settings in the first place. Re-read on every resume, since that is
     * where the user goes to change it.
     */
    val notifications = rememberTimerNotificationPermission()

    ProfileScreen(
        state = state,
        syncState = syncState,
        onDisplayNameChange = viewModel::onDisplayNameChange,
        onHeightChange = viewModel::onHeightChange,
        onBirthDateChange = viewModel::onBirthDateChange,
        onSave = viewModel::saveProfile,
        onSaveConfirmationFinished = viewModel::onSaveConfirmationFinished,
        onHapticsEnabledChange = viewModel::onHapticsEnabledChange,
        onExport = viewModel::exportWeightData,
        onSyncNow = syncViewModel::syncNow,
        onOpenServerSettings = onOpenServerSettings,
        modifier = modifier,
        showNotificationSettings = !notifications.isGranted && !notifications.canRequest,
        onOpenNotificationSettings = {
            context.startActivity(TimerNotificationPermission.settingsIntent(context))
        },
    )
}

@Composable
internal fun ProfileScreen(
    state: ProfileUiState,
    onDisplayNameChange: (String) -> Unit,
    onHeightChange: (String) -> Unit,
    onBirthDateChange: (LocalDate?) -> Unit,
    onSave: () -> Unit,
    onSaveConfirmationFinished: () -> Unit,
    onHapticsEnabledChange: (Boolean) -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
    syncState: DataSyncUiState = DataSyncUiState(),
    onSyncNow: () -> Unit = {},
    onOpenServerSettings: () -> Unit = {},
    today: LocalDate = LocalDate.now(),
    showNotificationSettings: Boolean = false,
    onOpenNotificationSettings: () -> Unit = {},
) {
    val spacing = MueTheme.spacing
    val focusManager = LocalFocusManager.current
    var datePickerVisible by rememberSaveable { mutableStateOf(false) }

    MueScreenScaffold(
        modifier = modifier,
        trailing = { MueHeaderChip("Health profile") },
        topFade = MueContentTopFade,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.xl),
        ) {
            MueScreenTitle(
                title = SCREEN_TITLE,
                eyebrow = SCREEN_EYEBROW,
                // Clears the header fade, so nothing looks dimmed before the user scrolls.
                modifier = Modifier.padding(top = MueContentTopFade),
            )

            ProfileForm(
                state = state,
                onDisplayNameChange = onDisplayNameChange,
                onHeightChange = onHeightChange,
                onOpenDatePicker = {
                    focusManager.clearFocus()
                    datePickerVisible = true
                },
            )

            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                MuePrimaryButton(
                    label = SAVE_LABEL,
                    onClick = {
                        focusManager.clearFocus()
                        onSave()
                    },
                    modifier = Modifier.testTag(ProfileTestTags.SAVE_BUTTON),
                    success = state.profileSaved,
                    onSuccessFinished = onSaveConfirmationFinished,
                )
                state.saveError?.let { StatusLine(it, MueTheme.colors.error, assertive = true) }
            }

            MueSurfaceCard(
                shape = MueTheme.shapes.field,
                contentPadding = PaddingValues(spacing.lg),
            ) {
                MueText(PRIVACY_TITLE, MueTheme.typography.sectionTitle)
                MueText(
                    text = PRIVACY_BODY,
                    style = MueTheme.typography.caption,
                    color = MueTheme.colors.textSecondary,
                    modifier = Modifier.padding(top = spacing.xs),
                )
            }

            ProfileSection(title = "Preferences") {
                ProfileSwitchRow(
                    title = HAPTICS_TITLE,
                    description = HAPTICS_BODY,
                    checked = state.hapticsEnabled,
                    onCheckedChange = onHapticsEnabledChange,
                    modifier = Modifier.testTag(ProfileTestTags.HAPTICS_TOGGLE),
                )

                if (showNotificationSettings) {
                    NotificationSettingsCard(
                        onOpen = onOpenNotificationSettings,
                        modifier = Modifier.padding(top = spacing.md),
                    )
                }
            }

            ProfileSection(title = "Your data") {
                MueSurfaceCard(
                    shape = MueTheme.shapes.field,
                    contentPadding = PaddingValues(spacing.lg),
                ) {
                    MueText(
                        text = EXPORT_BODY,
                        style = MueTheme.typography.caption,
                        color = MueTheme.colors.textSecondary,
                    )
                    MueSecondaryButton(
                        label = EXPORT_LABEL,
                        onClick = onExport,
                        modifier = Modifier
                            .padding(top = spacing.md)
                            .testTag(ProfileTestTags.EXPORT_BUTTON),
                        enabled = state.export !is ExportState.InProgress,
                    )
                    when (val export = state.export) {
                        ExportState.Idle -> Unit
                        ExportState.InProgress -> StatusLine(
                            message = "Preparing your file…",
                            color = MueTheme.colors.accent,
                        )

                        is ExportState.Failed -> StatusLine(
                            message = export.message,
                            color = MueTheme.colors.error,
                            assertive = true,
                        )
                    }
                }
            }

            /*
             * Sync PRD 9.1, in the section the PRD names, on the screen the PRD names.
             *
             * It is the *only* place in the app that mentions a server. 9.1 ends with "L'absence
             * de serveur associé n'affiche aucune alerte sur les écrans principaux", so Entry,
             * Progress, Activity and Food gained nothing at all — no badge, no banner, no dot.
             * A person who never pairs a server never learns that one exists unless they come
             * here and read.
             */
            ProfileSection(title = SyncMessages.SECTION_TITLE) {
                DataSyncSection(
                    state = syncState,
                    onSyncNow = onSyncNow,
                    onOpenServerSettings = onOpenServerSettings,
                )
            }

            // Leaves the last card breathing room above the tab bar, as on Progress.
            Spacer(Modifier.height(spacing.xxxl))
        }

        BirthDatePickerSheet(
            visible = datePickerVisible,
            initialDate = state.birthDate,
            today = today,
            onDismissRequest = { datePickerVisible = false },
            onConfirm = { date ->
                onBirthDateChange(date)
                datePickerVisible = false
            },
        )
    }
}

/**
 * FR-TIMER-012's `Open notification settings`, shown only while there is something to open it
 * for.
 *
 * It is a statement before it is a link: the first line the reader needs is that nothing is
 * broken — a refused notification costs the background controls and never the measured time —
 * and the second is that Settings is the only route left, since Mue does not ask twice.
 */
@Composable
private fun NotificationSettingsCard(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = MueTheme.spacing
    MueSurfaceCard(
        modifier = modifier,
        shape = MueTheme.shapes.field,
        contentPadding = PaddingValues(spacing.lg),
    ) {
        MueText(TimerMessages.NOTIFICATION_SETTINGS_TITLE, MueTheme.typography.sectionTitle)
        MueText(
            text = TimerMessages.NOTIFICATION_SETTINGS_BODY,
            style = MueTheme.typography.caption,
            color = MueTheme.colors.textSecondary,
            modifier = Modifier.padding(top = spacing.xs),
        )
        MueSecondaryButton(
            label = TimerMessages.OPEN_NOTIFICATION_SETTINGS,
            onClick = onOpen,
            modifier = Modifier
                .padding(top = spacing.md)
                .testTag(ProfileTestTags.NOTIFICATION_SETTINGS),
        )
    }
}

@Composable
private fun ProfileForm(
    state: ProfileUiState,
    onDisplayNameChange: (String) -> Unit,
    onHeightChange: (String) -> Unit,
    onOpenDatePicker: () -> Unit,
) {
    val spacing = MueTheme.spacing
    val locale = rememberMueLocale()
    val focusManager = LocalFocusManager.current

    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        // FR-PROFILE-006 puts the name at the head of the form; it is optional and never
        // blocks a save, so it carries no error slot.
        MueTextField(
            label = "Display name",
            value = state.displayName,
            onValueChange = onDisplayNameChange,
            modifier = Modifier.testTag(ProfileTestTags.NAME_FIELD),
            placeholder = "Optional",
            textStyle = MueTheme.typography.bodyStrong,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Next,
            ),
        )

        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            MueTextField(
                label = "Height",
                value = state.heightInput,
                onValueChange = onHeightChange,
                modifier = Modifier.testTag(ProfileTestTags.HEIGHT_FIELD),
                placeholder = NOT_SET,
                suffix = "cm",
                errorMessage = state.heightError,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            )
            // No height or no measurement means no BMI at all (PRD 15.1, 15.2). The full
            // card lives on Progress, which is where a state is read rather than entered.
            state.bmiAvailable?.let { available ->
                BmiReadout(
                    bmi = available,
                    echoCount = state.saveEchoCount,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            MuePickerField(
                label = "Date of birth",
                value = state.birthDate?.let { formatBirthDate(it, locale) } ?: NOT_SET,
                onClick = onOpenDatePicker,
                modifier = Modifier.testTag(ProfileTestTags.BIRTH_DATE_FIELD),
                onClickLabel = "Choose a date of birth",
                trailingText = state.ageYears?.let(::formatAge),
            )
            state.birthDateError?.let { message ->
                MueText(
                    text = message,
                    style = MueTheme.typography.caption,
                    color = MueTheme.colors.error,
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .semantics {
                            error(message)
                            liveRegion = LiveRegionMode.Polite
                        },
                )
            }
        }
    }
}

@Composable
private fun ProfileSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(MueTheme.spacing.md)) {
        MueText(title, MueTheme.typography.sectionTitle)
        content()
    }
}

/** A one-line outcome under an action; announced so it is not missed without sight. */
@Composable
private fun StatusLine(message: String, color: Color, assertive: Boolean = false) {
    MueText(
        text = message,
        style = MueTheme.typography.caption,
        color = color,
        modifier = Modifier
            .padding(top = MueTheme.spacing.sm, start = 4.dp, end = 4.dp)
            .semantics {
                liveRegion = if (assertive) LiveRegionMode.Assertive else LiveRegionMode.Polite
            },
    )
}

private val PreviewToday: LocalDate = LocalDate.of(2026, 8, 23)

@Composable
private fun ProfilePreview(
    state: ProfileUiState,
    syncState: DataSyncUiState = DataSyncUiState(),
) {
    MueTheme {
        ProfileScreen(
            state = state,
            onDisplayNameChange = {},
            onHeightChange = {},
            onBirthDateChange = {},
            onSave = {},
            onSaveConfirmationFinished = {},
            onHapticsEnabledChange = {},
            onExport = {},
            syncState = syncState,
            today = PreviewToday,
        )
    }
}

@Preview(name = "Profile — classified BMI", widthDp = 390, heightDp = 1500)
@Composable
private fun ProfileClassifiedPreview() {
    ProfilePreview(
        ProfileUiState(
            displayName = "Kris",
            heightInput = "180",
            birthDate = LocalDate.of(1992, 4, 16),
            ageYears = 34,
            bmi = Bmi.Classified(23.0, BmiCategory.HEALTHY_WEIGHT),
        ),
    )
}

@Preview(name = "Profile — value-only BMI", widthDp = 390, heightDp = 1500)
@Composable
private fun ProfileValueOnlyPreview() {
    ProfilePreview(
        ProfileUiState(
            heightInput = "180",
            bmi = Bmi.ValueOnly(23.0),
        ),
    )
}

@Preview(name = "Profile — no BMI", widthDp = 390, heightDp = 1500)
@Composable
private fun ProfileNoBmiPreview() {
    ProfilePreview(ProfileUiState(hapticsEnabled = false))
}

/** Sync PRD 9.1 with a server behind it, which is the state the section is really for. */
@Preview(name = "Profile — synced", widthDp = 390, heightDp = 1600)
@Composable
private fun ProfileSyncedPreview() {
    ProfilePreview(
        state = ProfileUiState(displayName = "Kris", heightInput = "180"),
        syncState = DataSyncUiState(
            status = SyncStatus.CHANGES_PENDING,
            serverName = "mue.home.arpa",
            account = "kris@example.org",
            lastSuccessAt = 1_756_240_000_000L,
            outstandingChanges = 2,
            undeliverableChanges = 1,
        ),
    )
}

@Preview(name = "Profile — validation error", widthDp = 390, heightDp = 1500)
@Composable
private fun ProfileErrorPreview() {
    ProfilePreview(
        ProfileUiState(
            displayName = "Kris",
            heightInput = "999",
            birthDate = LocalDate.of(2030, 1, 1),
            heightError = MueValidation.HEIGHT_ERROR,
            birthDateError = MueValidation.BIRTH_DATE_ERROR,
            export = ExportState.Failed(ProfileViewModel.EXPORT_ERROR),
        ),
    )
}
