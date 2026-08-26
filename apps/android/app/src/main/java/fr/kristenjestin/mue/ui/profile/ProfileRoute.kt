package fr.kristenjestin.mue.ui.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * Les écrans de l'onglet `Profile` (PRD_SCALE 8).
 *
 * L'onglet n'en avait qu'un jusqu'ici, et n'avait donc pas de pile. PRD_SCALE 8 lui en ajoute
 * trois : `Profile > Scales`, la fiche d'une balance et le flux d'appairage, rangés là où sont les
 * réglages d'appareil — « invisibles depuis les écrans principaux ». C'est la troisième pile de
 * l'application, écrite sur le modèle exact des deux premières.
 *
 * Chaque route sait s'écrire comme une seule chaîne, si bien que la pile entière traverse un
 * `Bundle` en texte et revient après une mort de processus (PRD 16.3).
 */
@Immutable
sealed interface ProfileRoute {

    /** Identifie la route dans la pile enregistrée, dans son emplacement d'état et à `AnimatedContent`. */
    val key: String

    /** Le profil santé lui-même, racine de l'onglet. */
    data object Profile : ProfileRoute {
        override val key: String = "profile"
    }

    /** `Profile > Scales` : associer, renommer, oublier, diagnostiquer (PRD_SCALE 8). */
    data object Scales : ProfileRoute {
        override val key: String = "scales"
    }

    /** Le flux d'appairage, ouvert par `Add a scale` et par lui seul (FR-SCALE-010). */
    data object ScaleScan : ProfileRoute {
        override val key: String = "scaleScan"
    }

    /**
     * La fiche d'une balance : son nom, son modèle, son diagnostic, son oubli (FR-SCALE-013).
     *
     * Elle porte l'`id` que Mue a tiré à l'appairage, jamais l'adresse — celle-ci peut changer au
     * remplacement des piles (PRD_SCALE 10.1), et une route qui en dépendrait rouvrirait une fiche
     * vide le jour où elle change.
     */
    data class ScaleDetail(val scaleId: String) : ProfileRoute {
        override val key: String get() = "$SCALE_DETAIL_KEY$ID_SEPARATOR$scaleId"
    }

    companion object {
        private const val SCALE_DETAIL_KEY = "scaleDetail"

        /** Ne peut pas figurer dans un identifiant, qui est un UUID. */
        private const val ID_SEPARATOR = ':'

        /**
         * L'inverse de [key]. Une clé illisible retombe sur [Profile] au lieu de lever : une pile
         * enregistrée survit au code qui l'a écrite, et perdre un écran vaut mieux qu'un plantage
         * à la première image après une mise à jour.
         *
         * C'est pourquoi elle reste **totale** à mesure que des routes s'ajoutent.
         */
        fun fromKey(key: String): ProfileRoute = when {
            key == Profile.key -> Profile
            key == Scales.key -> Scales
            key == ScaleScan.key -> ScaleScan
            key.startsWith("$SCALE_DETAIL_KEY$ID_SEPARATOR") ->
                ScaleDetail(key.substringAfter(ID_SEPARATOR))

            else -> Profile
        }
    }
}

/**
 * La pile de l'onglet `Profile`.
 *
 * La plus petite chose qui modélise quatre destinations : une liste dont la dernière entrée est ce
 * qui est à l'écran. Tout ce qu'une bibliothèque de navigation ajouterait ici — un graphe, des
 * fournisseurs d'entrées, un cycle de vie par entrée — ne ferait que redécrire cette liste.
 */
@Stable
class ProfileStack internal constructor(entries: List<ProfileRoute>) {

    var entries: List<ProfileRoute> by mutableStateOf(
        entries.ifEmpty { listOf(ProfileRoute.Profile) },
    )
        private set

    val current: ProfileRoute get() = entries.last()

    /** Faux sur le profil, d'où le retour appartient au châssis d'onglets et quitte le module. */
    val canGoBack: Boolean get() = entries.size > 1

    fun push(route: ProfileRoute) {
        entries = entries + route
    }

    /**
     * Retire les [count] écrans du dessus, jamais le profil.
     *
     * Une association réussie en retire un seul — le flux d'appairage — et retombe donc sur la
     * liste, ce que FR-SCALE-012 demande exactement. Un oubli confirmé depuis une fiche en retire
     * un aussi : la fiche d'une balance qui n'existe plus n'a rien à montrer.
     */
    fun pop(count: Int = 1) {
        entries = entries.take((entries.size - count).coerceAtLeast(1))
    }
}

private val ProfileStackSaver: Saver<ProfileStack, Any> = listSaver(
    save = { stack -> stack.entries.map(ProfileRoute::key) },
    restore = { keys -> ProfileStack(keys.map { key -> ProfileRoute.fromKey(key) }) },
)

/** Une pile qui survit à une rotation, à un passage par un autre onglet et à une mort de processus. */
@Composable
fun rememberProfileStack(): ProfileStack = rememberSaveable(saver = ProfileStackSaver) {
    ProfileStack(listOf(ProfileRoute.Profile))
}
