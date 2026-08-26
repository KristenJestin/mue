package fr.kristenjestin.mue.ui.scale

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import fr.kristenjestin.mue.ui.components.MuePrimaryButton
import fr.kristenjestin.mue.ui.components.MueSurfaceCard
import fr.kristenjestin.mue.ui.components.MueText
import fr.kristenjestin.mue.ui.theme.MueTheme

/**
 * Ce qui empêche de chercher une balance, et le seul geste qui le lève
 * (PRD_SCALE 16.1, 18.5, FR-SCALE-025).
 *
 * **Une seule implémentation, deux écrans.** PRD_SCALE 18.5 nomme `Scales` — « Bluetooth
 * désactivé : `Scales` propose de l'activer », « permission refusée ou révoquée : `Scales` explique
 * la permission manquante » — et FR-SCALE-025 met la demande de permission au premier appairage,
 * donc sur le flux de scan. Les deux écrans doivent la même phrase à la même condition, et deux
 * cartes écrites séparément auraient fini par expliquer la même radio éteinte de deux façons. Elle
 * vit donc ici, à côté de [ScanGate] qui l'énumère, et non dans l'un des deux écrans qui l'emploie.
 *
 * Une seule carte à la fois, dans l'ordre que `ScalePermissions` documente : la permission, puis la
 * radio, puis la localisation système. Chaque cas dit ce qu'il est **et** ce qu'il n'est pas — la
 * permission Bluetooth ne sert pas à localiser, et le reste de Mue continue de fonctionner sans elle
 * (BR-SCALE-011).
 *
 * **Rien ne s'ouvre tout seul.** Les quatre actions sont des rappels passés par l'écran ; aucune
 * n'est déclenchée par une composition, un `LaunchedEffect` ou l'arrivée sur l'écran. C'est la
 * dernière phrase de FR-SCALE-025 — « aucun nouvel écran système n'est ouvert sans action de
 * l'utilisateur » — tenue par construction plutôt que par vigilance.
 */
@Composable
internal fun ScaleGateCard(
    gate: ScanGate,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    modifier: Modifier = Modifier,
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
            actionLabel = ScaleMessages.ALLOW_BLUETOOTH
            action = onRequestPermission
            cardTag = ScaleTestTags.PERMISSION_EXPLANATION
            actionTag = ScaleTestTags.ALLOW_PERMISSION
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
        modifier = modifier.testTag(cardTag),
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
