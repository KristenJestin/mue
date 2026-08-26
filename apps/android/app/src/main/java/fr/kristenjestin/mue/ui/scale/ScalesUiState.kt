package fr.kristenjestin.mue.ui.scale

import androidx.compose.runtime.Immutable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Le modèle n'est plus livré : la balance reste utilisable à lire, jamais à piloter. */
private const val UNKNOWN_MODEL = "Unknown model"

/**
 * Une balance appairée, telle que `Profile > Scales` la montre (FR-SCALE-013).
 *
 * Les quatre premières lignes sont celles que le PRD énumère — nom donné, modèle reconnu, dernier
 * contact, état à portée — et les trois dernières sont le bloc de diagnostic de la fiche. Elles
 * voyagent ensemble parce qu'elles décrivent le même appareil, mais l'écran les sépare : le
 * diagnostic est quelque chose qu'on lit, jamais quelque chose qu'on règle.
 *
 * [id] n'est jamais affiché (PRD_SCALE 9.3). Il ne sert qu'à nommer la ligne pour un test et à
 * désigner la balance dans les actions.
 *
 * @property modelName Le nom du modèle du pilote, ou [UNKNOWN_MODEL] si ce pilote n'existe plus.
 *   Une balance appairée par une version antérieure peut référencer un pilote retiré depuis : le
 *   cas doit se lire, pas planter (PRD_SCALE 9.2), et surtout pas disparaître de la liste — une
 *   balance qu'on ne voit plus est une balance qu'on ne peut plus oublier.
 * @property inRange Vue par le scan pendant que l'écran est ouvert. Hors de portée est l'état
 *   normal d'une balance endormie (PRD_SCALE 18.2) : ce n'est jamais une anomalie, et l'écran ne
 *   le présente pas comme telle.
 */
@Immutable
internal data class PairedScale(
    val id: String,
    val displayName: String,
    val modelName: String,
    val driverId: String,
    val address: String,
    val advertisedName: String,
    val lastSeenAt: Instant?,
    val inRange: Boolean,
) {
    companion object {
        /** Ce que porte la fiche quand le pilote a disparu du registre. */
        const val UNKNOWN_MODEL_NAME: String = UNKNOWN_MODEL
    }
}

/**
 * Tout ce que `Profile > Scales` dessine (FR-SCALE-010, 013, 014, PRD_SCALE 18.1).
 *
 * [loading] existe pour que l'état vide de PRD_SCALE 18.1 ne clignote pas avant la première
 * lecture de la base : proposer `Add a scale` à quelqu'un qui en a trois, le temps d'une frame,
 * est une invitation adressée au mauvais lecteur.
 *
 * [forgetTarget] porte la confirmation de FR-SCALE-014. Elle est dans l'état plutôt que dans
 * l'écran parce que c'est le seul endroit d'où elle survit à une rotation, et parce qu'une
 * question posée sur une balance déjà oubliée n'a plus de sens : elle s'efface avec elle.
 */
@Immutable
internal data class ScalesUiState(
    val loading: Boolean = true,
    val scales: List<PairedScale> = emptyList(),
    val forgetTarget: PairedScale? = null,
) {
    /** PRD_SCALE 18.1 : l'état vide, une fois qu'on sait qu'il est vrai. */
    val isEmpty: Boolean get() = !loading && scales.isEmpty()

    fun scaleOrNull(id: String?): PairedScale? = scales.firstOrNull { it.id == id }
}

/**
 * `Last seen 23 August 2026`, ou `Never connected` (FR-SCALE-013).
 *
 * La date suit la langue du téléphone comme tout ce que Mue affiche (PRD BR-010), et l'heure du
 * jour est volontairement absente : ce que le lecteur veut savoir est « hier » ou « il y a un
 * mois », pas la minute. La forme longue de l'export CSV, elle, ne passe jamais par ici.
 */
internal fun formatLastSeen(
    lastSeenAt: Instant?,
    locale: Locale,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    if (lastSeenAt == null) return ScaleMessages.NEVER_CONNECTED
    val date = lastSeenAt.atZone(zone).toLocalDate()
    return DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale).format(date)
}
