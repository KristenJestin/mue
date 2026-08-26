package fr.kristenjestin.mue.ui.profile

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import fr.kristenjestin.mue.ui.scale.ScaleDetailScreen
import fr.kristenjestin.mue.ui.scale.ScaleScanScreen
import fr.kristenjestin.mue.ui.scale.ScalesScreen
import fr.kristenjestin.mue.ui.theme.LocalReduceMotion
import fr.kristenjestin.mue.ui.theme.MueMotion

/**
 * L'onglet `Profile`, qui n'était qu'un écran jusqu'à ce module (PRD_SCALE 8).
 *
 * PRD_SCALE 8 range la gestion des balances dans `Profile` — « un réglage d'appareil, invisible
 * depuis les écrans principaux » — ce qui donne à cet onglet ses trois premiers sous-écrans et donc
 * sa première pile. Elle est écrite sur le modèle exact de celle de l'onglet `Activity` : une
 * liste, un `AnimatedContent`, un emplacement d'état par route, et un `BackHandler` qui répond
 * avant celui du châssis.
 *
 * La barre d'onglets reste au-dessus de cet hôte et n'apprend jamais qu'un sous-écran est ouvert :
 * `Profile > Scales` la garde visible comme tous les autres écrans de l'application.
 */
@Composable
fun ProfileNavHost(modifier: Modifier = Modifier) {
    val stack = rememberProfileStack()
    ProfileNavHost(stack = stack, modifier = modifier) { route ->
        ProfileDestination(route = route, stack = stack, modifier = Modifier.fillMaxSize())
    }
}

/**
 * La mécanique de la pile, les écrans laissés à l'appelant pour qu'un test puisse la piloter sans
 * base de données derrière — la même séparation que le châssis d'onglets et les deux autres piles.
 *
 * Chaque route est composée dans son propre emplacement de [rememberSaveableStateHolder] : la liste
 * des balances revient déroulée là où on l'avait laissée, et un écran dépilé rend son emplacement
 * plutôt que d'accueillir la visite suivante avec un formulaire abandonné.
 */
@Composable
internal fun ProfileNavHost(
    stack: ProfileStack,
    modifier: Modifier = Modifier,
    content: @Composable (ProfileRoute) -> Unit,
) {
    val screenStates = rememberSaveableStateHolder()
    val keys = stack.entries.map(ProfileRoute::key)

    /*
     * Les gestionnaires imbriqués répondent du plus profond au plus haut : celui-ci passe avant
     * celui du châssis, donc le retour se déplace dans le module au lieu d'en sortir. Sur le
     * profil nu il est désactivé et le châssis reprend la main, ce qui ramène à `Entry`.
     */
    BackHandler(enabled = stack.canGoBack) { stack.pop() }

    val live = remember { mutableSetOf<String>() }
    LaunchedEffect(keys) {
        live.filterNot(keys::contains).forEach { gone ->
            screenStates.removeState(gone)
            live.remove(gone)
        }
        live.addAll(keys)
    }

    // Les deux directions sont résolues ici parce que `transitionSpec` s'exécute hors composition.
    val deeper = profileStackTransition(deeper = true)
    val shallower = profileStackTransition(deeper = false)

    AnimatedContent(
        targetState = stack.entries,
        modifier = modifier,
        transitionSpec = { if (targetState.size >= initialState.size) deeper else shallower },
        contentKey = { entries -> entries.last().key },
        label = "profileStack",
    ) { entries ->
        val route = entries.last()
        screenStates.SaveableStateProvider(route.key) { content(route) }
    }
}

/**
 * Où chaque écran retombe sur la pile.
 *
 * Quatre branches, et chacune dit une règle du PRD : `Add a scale` est le **seul** chemin vers le
 * flux d'appairage (FR-SCALE-010), une association réussie ramène à la liste (FR-SCALE-012), et une
 * balance oubliée referme sa fiche parce qu'il n'y a plus rien à y montrer (FR-SCALE-014).
 */
@Composable
private fun ProfileDestination(
    route: ProfileRoute,
    stack: ProfileStack,
    modifier: Modifier = Modifier,
) {
    when (route) {
        ProfileRoute.Profile -> ProfileScreen(
            onOpenScales = { stack.push(ProfileRoute.Scales) },
            modifier = modifier,
        )

        ProfileRoute.Scales -> ScalesScreen(
            onBack = { stack.pop() },
            onAddScale = { stack.push(ProfileRoute.ScaleScan) },
            onOpenScale = { scaleId -> stack.push(ProfileRoute.ScaleDetail(scaleId)) },
            modifier = modifier,
        )

        ProfileRoute.ScaleScan -> ScaleScanScreen(
            onBack = { stack.pop() },
            onPaired = { stack.pop() },
            modifier = modifier,
        )

        is ProfileRoute.ScaleDetail -> ScaleDetailScreen(
            scaleId = route.scaleId,
            onBack = { stack.pop() },
            onForgotten = { stack.pop() },
            modifier = modifier,
        )
    }
}

/**
 * Aller plus profond lève le nouvel écran par-dessus l'ancien ; revenir le laisse se reposer. Sous
 * réduction des animations, le déplacement disparaît et le fondu croisé reste, comme partout
 * ailleurs dans l'application.
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
