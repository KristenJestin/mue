package fr.kristenjestin.mue.ui.scale

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.kristenjestin.mue.ui.components.MueIcon
import fr.kristenjestin.mue.ui.components.MueIcons
import fr.kristenjestin.mue.ui.components.MuePrimaryButton
import fr.kristenjestin.mue.ui.components.MueSecondaryButton
import fr.kristenjestin.mue.ui.components.MueSubScreenScaffold
import fr.kristenjestin.mue.ui.components.MueSurfaceCard
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.theme.MueTheme

/** Manque à `ScaleMessages` : ce que fait le geste qui déclenche la demande de permission. */
private const val ALLOW_BLUETOOTH = "Allow Bluetooth"

/** Manque à `ScaleMessages` : un appareil déjà appairé, retrouvé par le scan. */
private fun alreadyPairedAs(name: String): String = "Already paired as $name"

/** Manque à `ScaleMessages` : l'indice de rattachement, sur la ligne (FR-SCALE-001). */
private fun mightBe(name: String): String = "Might be $name"

/** Manque à `ScaleMessages` : la question du rattachement d'adresse (FR-SCALE-001). */
private const val REATTACH_TITLE = "Is this the same scale?"
private fun reattachBody(name: String): String =
    "Mue knows a scale called $name that is no longer answering at the address it had. A " +
        "battery change is enough to do that. Reattaching keeps its name and every measurement " +
        "it produced; adding it separately keeps both scales."
private const val REATTACH_CONFIRM = "Reattach"
private const val REATTACH_AS_NEW = "Add as a new scale"

/** Manque à `ScaleMessages` : la ligne d'état pendant qu'aucun scan n'a encore tourné. */
private const val SCAN_NOT_STARTED = "Ready when you are"

/** Manquent à `ScaleTestTags`. */
private const val ALLOW_PERMISSION_TAG = "scale:allowPermission"
private const val SCAN_STATUS_TAG = "scale:scanStatus"
private const val REATTACH_PROPOSAL_TAG = "scale:reattachProposal"
private const val REATTACH_CONFIRM_TAG = "scale:reattachConfirm"
private const val REATTACH_DECLINE_TAG = "scale:reattachDecline"

/**
 * Le flux d'appairage, câblé (FR-SCALE-011, 012, 025).
 *
 * **C'est ici, et nulle part ailleurs, que les permissions Bluetooth sont demandées.** Lire
 * `rememberScalePermissions()` ne déclenche rien ; seul `request()` ouvre une boîte de dialogue
 * système, et il n'est appelé que sur un geste délibéré, dans le contexte où sa raison est évidente
 * (FR-SCALE-025). Le lancement de l'application ne demande jamais rien.
 *
 * Le premier scan part tout seul dès que plus rien ne s'y oppose : l'écran a été ouvert *pour*
 * chercher, et faire appuyer sur un bouton de plus n'apprendrait rien à personne. Les suivants,
 * eux, sont demandés — un scan qui se relance indéfiniment est un scan qui vide la batterie sans
 * rien annoncer (FR-SCALE-011).
 */
@Composable
internal fun ScaleScanScreen(
    onBack: () -> Unit,
    onPaired: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = scaleScanViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val permissions = rememberScalePermissions()
    val context = LocalContext.current

    val gate = permissions.toScanGate()
    LaunchedEffect(viewModel, gate) {
        viewModel.onGateChanged(gate)
        if (gate == ScanGate.READY) viewModel.onScanRequested()
    }

    DisposableEffect(viewModel) {
        onDispose { viewModel.onScreenHidden() }
    }

    // FR-SCALE-012 : une association réussie ramène à la liste des balances.
    LaunchedEffect(state.pairedScaleId) {
        if (state.pairedScaleId != null) {
            viewModel.onPairingHandled()
            onPaired()
        }
    }

    ScaleScanContent(
        state = state,
        onScanAgain = viewModel::onScanRequested,
        onDeviceSelected = viewModel::onDeviceSelected,
        onRequestPermission = permissions::request,
        onOpenSettings = { context.startActivity(permissions.appSettingsIntent) },
        onEnableBluetooth = { context.startActivity(permissions.enableBluetoothIntent) },
        onOpenLocationSettings = {
            context.startActivity(permissions.systemLocationSettingsIntent)
        },
        onReattachConfirmed = viewModel::onReattachConfirmed,
        onReattachDeclined = viewModel::onReattachDeclined,
        onProposalDismissed = viewModel::onProposalDismissed,
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * Ce que voit quelqu'un qui cherche sa balance (FR-SCALE-011).
 *
 * Trois choses sont à l'écran avant toute liste : que le scan tourne, que **trente secondes** lui
 * sont laissées, et qu'une balance endormie est invisible. Cette dernière phrase est la plus utile
 * de l'écran — sans elle, la liste paraît cassée à qui a sa balance sous les yeux, allumée et
 * endormie.
 *
 * Les appareils non pris en charge sont listés, grisés et non sélectionnables. C'est délibéré : ils
 * transforment « le Bluetooth est cassé » en « Mue ne connaît pas encore ce modèle », qui est la
 * vérité et sur quoi on peut agir.
 */
@Composable
internal fun ScaleScanContent(
    state: ScaleScanUiState,
    onScanAgain: () -> Unit,
    onDeviceSelected: (DiscoveredScale) -> Unit,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onReattachConfirmed: () -> Unit,
    onReattachDeclined: () -> Unit,
    onProposalDismissed: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MueTheme.spacing
    MueSubScreenScaffold(
        title = ScaleMessages.SCAN_TITLE,
        onNavigateBack = onBack,
        navigationIcon = {
            MueIcon(MueIcons.ARROW_LEFT, tint = MueTheme.colors.textSecondary, size = 18.dp)
        },
        modifier = modifier.testTag(ScaleTestTags.SCAN_SCREEN),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            Spacer(Modifier.height(spacing.md))

            if (state.gate != ScanGate.READY) {
                ScanGateCard(
                    gate = state.gate,
                    onRequestPermission = onRequestPermission,
                    onOpenSettings = onOpenSettings,
                    onEnableBluetooth = onEnableBluetooth,
                    onOpenLocationSettings = onOpenLocationSettings,
                )
            } else {
                ScanResults(
                    state = state,
                    onScanAgain = onScanAgain,
                    onDeviceSelected = onDeviceSelected,
                )
            }

            Spacer(Modifier.height(spacing.xxxl))
        }
    }

    state.proposal?.let { proposal ->
        ReattachProposalDialog(
            proposal = proposal,
            onConfirm = onReattachConfirmed,
            onDecline = onReattachDeclined,
            onDismiss = onProposalDismissed,
        )
    }
}

/**
 * Ce que le scan a trouvé, et l'offre de recommencer.
 *
 * Séparé de son écran pour une raison de lisibilité et une seule : la carte des conditions
 * d'Android et cette liste ne coexistent jamais, et les enchaîner dans un même bloc rendrait ce
 * fait invisible.
 */
@Composable
private fun ColumnScope.ScanResults(
    state: ScaleScanUiState,
    onScanAgain: () -> Unit,
    onDeviceSelected: (DiscoveredScale) -> Unit,
) {
    val spacing = MueTheme.spacing

    // FR-SCALE-011 : une balance endormie est invisible, il faut monter dessus.
    MueSurfaceCard(
        modifier = Modifier.testTag(ScaleTestTags.SCAN_HINT),
        shape = MueTheme.shapes.field,
        contentPadding = PaddingValues(spacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MueIcon(iconName = MueIcons.BLUETOOTH, tint = MueTheme.colors.accent, size = 18.dp)
            MueText(
                text = ScaleMessages.SCAN_WAKE_HINT,
                style = MueTheme.typography.caption,
                color = MueTheme.colors.textSecondary,
                modifier = Modifier.padding(start = spacing.md),
            )
        }
    }

    MueText(
        text = when {
            state.scanning -> ScaleMessages.SCANNING
            state.started -> ScaleMessages.SCAN_FINISHED
            else -> SCAN_NOT_STARTED
        },
        style = MueTheme.typography.label,
        color = MueTheme.colors.textTertiary,
        modifier = Modifier
            .testTag(SCAN_STATUS_TAG)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )

    if (state.recognised.isNotEmpty()) {
        Column(
            modifier = Modifier.testTag(ScaleTestTags.RECOGNISED_DEVICES),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            MueText(ScaleMessages.RECOGNISED_HEADING, MueTheme.typography.sectionTitle)
            state.recognised.forEach { device ->
                RecognisedDeviceRow(device = device, onClick = { onDeviceSelected(device) })
            }
        }
    }

    // PRD_SCALE 7.3 : le silence n'est pas une erreur, mais il se nomme.
    if (state.finishedEmptyHanded) {
        MueText(
            text = ScaleMessages.SCAN_FOUND_NOTHING,
            style = MueTheme.typography.body,
            color = MueTheme.colors.textSecondary,
        )
    }

    if (state.unsupported.isNotEmpty()) {
        Column(
            modifier = Modifier.testTag(ScaleTestTags.UNSUPPORTED_DEVICES),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            MueText(ScaleMessages.UNSUPPORTED_HEADING, MueTheme.typography.sectionTitle)
            MueText(
                text = ScaleMessages.UNSUPPORTED_NOTE,
                style = MueTheme.typography.caption,
                color = MueTheme.colors.textTertiary,
            )
            state.unsupported.forEach { device -> UnsupportedDeviceRow(device) }
        }
    }

    /*
     * FR-SCALE-011 : le scan s'arrête au bout de trente secondes et propose de recommencer.
     * L'offre n'apparaît qu'une fois qu'il s'est arrêté, faute de quoi elle inviterait à
     * interrompre une recherche qui est encore en train d'aboutir.
     */
    if (!state.scanning) {
        MuePrimaryButton(
            label = ScaleMessages.SCAN_AGAIN,
            onClick = onScanAgain,
            modifier = Modifier.testTag(ScaleTestTags.SCAN_AGAIN),
        )
    }
}

/**
 * Une balance qu'un pilote sait lire, avec le modèle identifié (FR-SCALE-011).
 *
 * Elle est sélectionnable sauf si cette adresse est déjà appairée : dans ce cas la ligne reste
 * affichée — la retrouver en scannant est la façon la plus naturelle de vérifier qu'elle est bien
 * celle qu'on croit — et dit sous quel nom.
 */
@Composable
private fun RecognisedDeviceRow(device: DiscoveredScale, onClick: () -> Unit) {
    val spacing = MueTheme.spacing
    val note = when {
        device.alreadyPairedAs != null -> alreadyPairedAs(device.alreadyPairedAs)
        device.reattachTo != null -> mightBe(device.reattachTo.displayName)
        else -> null
    }
    MueSurfaceCard(
        modifier = Modifier.testTag(ScaleTestTags.device(device.address)),
        shape = MueTheme.shapes.field,
        contentPadding = PaddingValues(spacing.lg),
        onClick = if (device.selectable) onClick else null,
        onClickLabel = ScaleMessages.ADD_A_SCALE,
    ) {
        MueText(
            text = device.advertisedName.ifEmpty { device.modelName },
            style = MueTheme.typography.bodyStrong,
            color = if (device.selectable) {
                MueTheme.colors.textPrimary
            } else {
                MueTheme.colors.textTertiary
            },
        )
        MueText(
            text = device.modelName,
            style = MueTheme.typography.caption,
            color = MueTheme.colors.textSecondary,
            modifier = Modifier.padding(top = spacing.xxs),
        )
        note?.let {
            MueText(
                text = it,
                style = MueTheme.typography.caption,
                color = MueTheme.colors.textTertiary,
                modifier = Modifier.padding(top = spacing.xs),
            )
        }
    }
}

/**
 * Un appareil que Mue voit sans savoir le lire (FR-SCALE-011).
 *
 * Grisé et sans action : ni `onClick`, ni `Role.Button`, donc rien à activer pour un service
 * d'accessibilité non plus. La mention `Not supported` est écrite, jamais seulement suggérée par la
 * couleur (PRD_SCALE 20).
 */
@Composable
private fun UnsupportedDeviceRow(device: UnsupportedDevice) {
    val spacing = MueTheme.spacing
    MueSurfaceCard(
        modifier = Modifier.testTag(ScaleTestTags.device(device.address)),
        shape = MueTheme.shapes.field,
        contentPadding = PaddingValues(spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MueText(
                text = device.name,
                style = MueTheme.typography.body,
                color = MueTheme.colors.textQuiet,
                modifier = Modifier.weight(1f),
            )
            MueText(
                text = ScaleMessages.UNSUPPORTED_BADGE,
                style = MueTheme.typography.caption,
                color = MueTheme.colors.textQuiet,
            )
        }
    }
}

/**
 * Ce qui empêche de chercher, et le seul geste qui le lève (PRD_SCALE 16.1, 18.5).
 *
 * Une seule carte à la fois, dans l'ordre que `ScalePermissions` documente : la permission, puis la
 * radio, puis la localisation système. Chaque cas dit ce qu'il est **et** ce qu'il n'est pas — la
 * permission Bluetooth ne sert pas à localiser, et le reste de Mue continue de fonctionner sans
 * elle (BR-SCALE-011).
 */
@Composable
private fun ScanGateCard(
    gate: ScanGate,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onOpenLocationSettings: () -> Unit,
) {
    val spacing = MueTheme.spacing
    val explanation: String
    val actionLabel: String
    val action: () -> Unit
    val cardTag: String
    val actionTag: String

    when (gate) {
        ScanGate.PERMISSION_NEEDED -> {
            explanation = ScaleMessages.PERMISSION_EXPLANATION
            actionLabel = ALLOW_BLUETOOTH
            action = onRequestPermission
            cardTag = ScaleTestTags.PERMISSION_EXPLANATION
            actionTag = ALLOW_PERMISSION_TAG
        }

        ScanGate.PERMISSION_DENIED -> {
            explanation = ScaleMessages.PERMISSION_DENIED_EXPLANATION
            actionLabel = ScaleMessages.OPEN_SETTINGS
            action = onOpenSettings
            cardTag = ScaleTestTags.PERMISSION_EXPLANATION
            actionTag = ScaleTestTags.OPEN_SETTINGS
        }

        ScanGate.BLUETOOTH_OFF -> {
            explanation = ScaleMessages.BLUETOOTH_OFF_EXPLANATION
            actionLabel = ScaleMessages.ENABLE_BLUETOOTH
            action = onEnableBluetooth
            cardTag = ScaleTestTags.PERMISSION_EXPLANATION
            actionTag = ScaleTestTags.ENABLE_BLUETOOTH
        }

        ScanGate.SYSTEM_LOCATION_OFF -> {
            explanation = ScaleMessages.SYSTEM_LOCATION_EXPLANATION
            actionLabel = ScaleMessages.OPEN_LOCATION_SETTINGS
            action = onOpenLocationSettings
            cardTag = ScaleTestTags.LOCATION_EXPLANATION
            actionTag = ScaleTestTags.OPEN_LOCATION_SETTINGS
        }

        ScanGate.READY -> return
    }

    MueSurfaceCard(
        modifier = Modifier.testTag(cardTag),
        contentPadding = PaddingValues(spacing.cardPadding),
    ) {
        MueText(
            text = explanation,
            style = MueTheme.typography.body,
            color = MueTheme.colors.textSecondary,
        )
        MuePrimaryButton(
            label = actionLabel,
            onClick = action,
            modifier = Modifier
                .padding(top = spacing.lg)
                .testTag(actionTag),
        )
    }
}

/**
 * La question du rattachement d'adresse (FR-SCALE-001).
 *
 * **Proposée, jamais silencieuse.** Deux balances identiques dans un même foyer ne doivent pas
 * fusionner à l'insu de leur propriétaire, et c'est le seul cas où la bonne réponse dépend de
 * quelque chose que l'application ne peut pas voir. Les deux réponses sont donc constructives, et
 * le corps du message dit ce que chacune conserve.
 */
@Composable
private fun ReattachProposalDialog(
    proposal: ReattachProposal,
    onConfirm: () -> Unit,
    onDecline: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MueTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(REATTACH_PROPOSAL_TAG),
        title = { MueText(REATTACH_TITLE, MueTheme.typography.sectionTitle) },
        text = {
            MueText(
                text = reattachBody(proposal.candidate.displayName),
                style = MueTheme.typography.body,
                color = colors.textSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(REATTACH_CONFIRM_TAG)) {
                MueText(REATTACH_CONFIRM, MueTheme.typography.button, color = colors.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline, modifier = Modifier.testTag(REATTACH_DECLINE_TAG)) {
                MueText(REATTACH_AS_NEW, MueTheme.typography.button, color = colors.textSecondary)
            }
        },
        containerColor = colors.canvasElevated,
        shape = MueTheme.shapes.card,
    )
}

/**
 * Les quatre conditions d'Android, réduites à ce que l'écran doit dire (PRD_SCALE 18.5).
 *
 * L'ordre est celui de `ScalePermissions` : la permission d'abord, parce qu'elle conditionne même
 * la façon dont Mue peut proposer d'allumer la radio ; puis la radio ; puis la localisation
 * système, qui n'existe que jusqu'à Android 11.
 */
internal fun ScalePermissionsState.toScanGate(): ScanGate = when {
    isPermanentlyDenied -> ScanGate.PERMISSION_DENIED
    !isGranted -> ScanGate.PERMISSION_NEEDED
    !isBluetoothEnabled -> ScanGate.BLUETOOTH_OFF
    requiresSystemLocation && !isSystemLocationEnabled -> ScanGate.SYSTEM_LOCATION_OFF
    else -> ScanGate.READY
}

private val PreviewRecognised = listOf(
    DiscoveredScale(
        address = "FF:10:00:1F:52:C3",
        advertisedName = "Health Scale",
        driverId = "homebuds-hb9027",
        modelName = "Homebuds HB9027",
    ),
    DiscoveredScale(
        address = "FF:10:00:1F:52:C9",
        advertisedName = "Health Scale",
        driverId = "homebuds-hb9027",
        modelName = "Homebuds HB9027",
        reattachTo = ReattachCandidate("a", "Bathroom scale"),
    ),
)

private val PreviewUnsupported = listOf(
    UnsupportedDevice("AA:BB:CC:DD:EE:01", "Living room speaker"),
    UnsupportedDevice("AA:BB:CC:DD:EE:02", "QN-Scale"),
)

@Composable
private fun ScanPreview(state: ScaleScanUiState) {
    MueTheme {
        ScaleScanContent(
            state = state,
            onScanAgain = {},
            onDeviceSelected = {},
            onRequestPermission = {},
            onOpenSettings = {},
            onEnableBluetooth = {},
            onOpenLocationSettings = {},
            onReattachConfirmed = {},
            onReattachDeclined = {},
            onProposalDismissed = {},
            onBack = {},
        )
    }
}

@Preview(name = "Scan — scanning", widthDp = 390, heightDp = 900)
@Composable
private fun ScanScanningPreview() {
    ScanPreview(
        ScaleScanUiState(
            scanning = true,
            started = true,
            recognised = PreviewRecognised,
            unsupported = PreviewUnsupported,
        ),
    )
}

@Preview(name = "Scan — nothing found", widthDp = 390, heightDp = 720)
@Composable
private fun ScanEmptyPreview() {
    ScanPreview(ScaleScanUiState(started = true, unsupported = PreviewUnsupported))
}

@Preview(name = "Scan — permission needed", widthDp = 390, heightDp = 720)
@Composable
private fun ScanPermissionPreview() {
    ScanPreview(ScaleScanUiState(gate = ScanGate.PERMISSION_NEEDED))
}
