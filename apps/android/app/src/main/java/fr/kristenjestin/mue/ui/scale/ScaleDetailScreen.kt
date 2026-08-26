package fr.kristenjestin.mue.ui.scale

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.kristenjestin.mue.ui.components.MueIcon
import fr.kristenjestin.mue.ui.components.MueIcons
import fr.kristenjestin.mue.ui.components.MueSecondaryButton
import fr.kristenjestin.mue.ui.components.MueSubScreenScaffold
import fr.kristenjestin.mue.ui.components.MueSurfaceCard
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.components.MueTextField
import fr.kristenjestin.mue.ui.components.rememberMueLocale
import fr.kristenjestin.mue.ui.theme.MueTheme
import java.time.Instant

/**
 * La fiche d'une balance (FR-SCALE-013, 014), câblée au même ViewModel que la liste.
 *
 * Elle disparaît d'elle-même quand la balance disparaît : un oubli confirmé vide l'état, et il n'y
 * a plus rien à montrer. C'est aussi ce qui rattrape le cas où la balance est oubliée depuis un
 * autre écran, sans qu'aucun des deux n'ait à prévenir l'autre.
 */
@Composable
internal fun ScaleDetailScreen(
    scaleId: String,
    onBack: () -> Unit,
    onForgotten: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = scalesViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val permissions = rememberScalePermissions()
    val scale = state.scaleOrNull(scaleId)

    val canScan = permissions.canScan
    DisposableEffect(viewModel, canScan) {
        if (canScan) viewModel.onScreenVisible()
        onDispose { if (canScan) viewModel.onScreenHidden() }
    }

    LaunchedEffect(state.loading, scale == null) {
        if (!state.loading && scale == null) onForgotten()
    }

    /*
     * Le brouillon du nom, `null` tant que l'utilisateur n'a rien tapé.
     *
     * Il ne peut pas être initialisé au nom stocké : la première composition arrive avant la
     * première lecture de la base, et le champ resterait vide. Tant qu'il vaut `null` le champ
     * montre ce qui est enregistré, y compris un renommage venu d'ailleurs ; dès la première
     * frappe il appartient à l'utilisateur.
     */
    var draft by rememberSaveable(scaleId) { mutableStateOf<String?>(null) }

    ScaleDetailContent(
        scale = scale,
        nameInput = draft ?: scale?.displayName.orEmpty(),
        forgetTarget = state.forgetTarget,
        onNameChange = { draft = it },
        onSaveName = { name ->
            viewModel.onRenamed(scaleId, name)
            draft = null
        },
        onForgetRequested = { viewModel.onForgetRequested(scaleId) },
        onForgetCancelled = viewModel::onForgetCancelled,
        onForgetConfirmed = viewModel::onForgetConfirmed,
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * Nom, identité, diagnostic, oubli — dans cet ordre, qui est celui de leur fréquence d'usage.
 *
 * Le bloc technique est **du diagnostic, pas un réglage** (FR-SCALE-013) : il n'a ni champ, ni
 * bouton, ni chevron, et sa note le dit en une phrase. L'adresse Bluetooth y figure parce que c'est
 * la seule chose qui permette de distinguer deux appareils identiques ; elle ne quitte jamais le
 * téléphone et n'apparaît dans aucun export (PRD_SCALE 16.2).
 */
@Composable
internal fun ScaleDetailContent(
    scale: PairedScale?,
    nameInput: String,
    forgetTarget: PairedScale?,
    onNameChange: (String) -> Unit,
    onSaveName: (String) -> Unit,
    onForgetRequested: () -> Unit,
    onForgetCancelled: () -> Unit,
    onForgetConfirmed: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = MueTheme.spacing
    val locale = rememberMueLocale()
    val focusManager = LocalFocusManager.current

    MueSubScreenScaffold(
        title = scale?.displayName ?: ScaleMessages.SCALES,
        onNavigateBack = onBack,
        navigationIcon = {
            MueIcon(MueIcons.ARROW_LEFT, tint = MueTheme.colors.textSecondary, size = 18.dp)
        },
        modifier = modifier.testTag(ScaleTestTags.DETAIL),
    ) {
        // Rien à dessiner avant la première lecture, ni après un oubli : l'écran est déjà en
        // train de se refermer.
        if (scale == null) return@MueSubScreenScaffold

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.lg),
        ) {
            Spacer(Modifier.height(spacing.md))

            Column(
                modifier = Modifier.testTag(ScaleTestTags.RENAME_SECTION),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                MueText(ScaleMessages.RENAME_THIS_SCALE, MueTheme.typography.sectionTitle)
                MueTextField(
                    label = ScaleMessages.SCALE_NAME_LABEL,
                    value = nameInput,
                    onValueChange = onNameChange,
                    modifier = Modifier.testTag(ScaleTestTags.RENAME_FIELD),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            onSaveName(nameInput)
                        },
                    ),
                )
                MueSecondaryButton(
                    label = ScaleMessages.SAVE_NAME,
                    onClick = {
                        focusManager.clearFocus()
                        onSaveName(nameInput)
                    },
                    modifier = Modifier.testTag(ScaleTestTags.RENAME_CONFIRM),
                )
            }

            MueSurfaceCard(contentPadding = PaddingValues(spacing.cardPadding)) {
                MueText(ScaleMessages.ABOUT_THIS_SCALE, MueTheme.typography.sectionTitle)
                DetailRow(ScaleMessages.MODEL_LABEL, scale.modelName, spacing.md)
                DetailRow(
                    label = ScaleMessages.LAST_SEEN_LABEL,
                    value = formatLastSeen(scale.lastSeenAt, locale),
                    topPadding = spacing.sm,
                )
                MueText(
                    text = if (scale.inRange) ScaleMessages.IN_RANGE else ScaleMessages.NOT_IN_RANGE,
                    style = MueTheme.typography.caption,
                    color = MueTheme.colors.textTertiary,
                    modifier = Modifier
                        .padding(top = spacing.sm)
                        .testTag(ScaleTestTags.DETAIL_STATUS)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            MueSurfaceCard(
                modifier = Modifier.testTag(ScaleTestTags.DIAGNOSTICS),
                contentPadding = PaddingValues(spacing.cardPadding),
            ) {
                MueText(ScaleMessages.DIAGNOSTICS_TITLE, MueTheme.typography.sectionTitle)
                MueText(
                    text = ScaleMessages.DIAGNOSTICS_NOTE,
                    style = MueTheme.typography.caption,
                    color = MueTheme.colors.textTertiary,
                    modifier = Modifier.padding(top = spacing.xs),
                )
                DetailRow(ScaleMessages.DIAGNOSTICS_ADDRESS, scale.address, spacing.md)
                DetailRow(
                    label = ScaleMessages.DIAGNOSTICS_ADVERTISED_NAME,
                    value = scale.advertisedName,
                    topPadding = spacing.sm,
                )
                DetailRow(ScaleMessages.DIAGNOSTICS_DRIVER, scale.driverId, spacing.sm)
            }

            MueSecondaryButton(
                label = ScaleMessages.FORGET_THIS_SCALE,
                onClick = onForgetRequested,
                modifier = Modifier.testTag(ScaleTestTags.FORGET),
                contentColor = MueTheme.colors.error,
            )

            Spacer(Modifier.height(spacing.xxxl))
        }
    }

    if (forgetTarget != null) {
        ForgetConfirmation(
            onConfirm = onForgetConfirmed,
            onDismiss = onForgetCancelled,
        )
    }
}

/**
 * FR-SCALE-014 : l'oubli demande une confirmation, et la confirmation promet quelque chose.
 *
 * La seconde phrase est BR-SCALE-010 en toutes lettres — « chaque mesure produite reste dans votre
 * historique » — et c'est toute la raison pour laquelle cette question peut être acceptée sans
 * réfléchir. La réponse sûre est celle qui garde la balance ; c'est elle qui ferme la boîte.
 *
 * Un `AlertDialog` de Material, comme la suppression d'une activité : la confirmation doit être
 * modale et bloquante, et celle de la plateforme est déjà piégée au clavier, annonçable et
 * refermable sans geste de glissement (PRD_SCALE 20).
 */
@Composable
private fun ForgetConfirmation(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = MueTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(ScaleTestTags.FORGET_CONFIRMATION),
        title = {
            MueText(ScaleMessages.FORGET_CONFIRMATION_TITLE, MueTheme.typography.sectionTitle)
        },
        text = {
            MueText(
                ScaleMessages.FORGET_CONFIRMATION_BODY,
                MueTheme.typography.body,
                color = colors.textSecondary,
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag(ScaleTestTags.CONFIRM_FORGET),
            ) {
                MueText(ScaleMessages.FORGET, MueTheme.typography.button, color = colors.error)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(ScaleTestTags.KEEP_SCALE),
            ) {
                MueText(
                    ScaleMessages.KEEP_SCALE,
                    MueTheme.typography.button,
                    color = colors.textSecondary,
                )
            }
        },
        containerColor = colors.canvasElevated,
        shape = MueTheme.shapes.card,
    )
}

/** Un fait et son intitulé. Deux textes, aucune cible tactile : c'est là toute la démonstration. */
@Composable
private fun DetailRow(label: String, value: String, topPadding: Dp) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = topPadding)) {
        MueText(
            text = label,
            style = MueTheme.typography.caption,
            color = MueTheme.colors.textTertiary,
            modifier = Modifier.weight(1f),
        )
        MueText(
            text = value,
            style = MueTheme.typography.caption,
            color = MueTheme.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
    }
}

private val PreviewScale = PairedScale(
    id = "a",
    displayName = "Bathroom scale",
    modelName = "Homebuds HB9027",
    driverId = "homebuds-hb9027",
    address = "FF:10:00:1F:52:C3",
    advertisedName = "Health Scale",
    lastSeenAt = Instant.parse("2026-08-25T07:12:00Z"),
    inRange = true,
)

@Preview(name = "Scale detail", widthDp = 390, heightDp = 900)
@Composable
private fun ScaleDetailPreview() {
    MueTheme {
        ScaleDetailContent(
            scale = PreviewScale,
            nameInput = PreviewScale.displayName,
            forgetTarget = null,
            onNameChange = {},
            onSaveName = {},
            onForgetRequested = {},
            onForgetCancelled = {},
            onForgetConfirmed = {},
            onBack = {},
        )
    }
}
