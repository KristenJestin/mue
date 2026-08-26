package fr.kristenjestin.mue.domain.repository

import fr.kristenjestin.mue.domain.model.ScaleSessionState
import kotlinx.coroutines.flow.StateFlow

/**
 * La source d'une pesée reçue, vue du domaine.
 *
 * C'est le seul point de contact entre l'écran `Entry` et le Bluetooth. L'interface utilisateur ne
 * traverse jamais la couche de liaison : elle observe [state] et appelle quatre méthodes
 * (PRD_SCALE 21.2). Tout ce qui relève du scan, du GATT, des acquittements et des délais vit
 * derrière cette frontière.
 *
 * **Le scan ne tourne qu'au premier plan** (PRD_SCALE 3.7). Cette interface n'offre donc aucun
 * moyen de démarrer une recherche persistante : [start] est appelé quand `Entry` devient visible,
 * [stop] dès qu'il cesse de l'être, et rien ne survit à un passage en arrière-plan.
 */
interface ScaleSessionSource {

    /**
     * L'état courant de la tentative de pesée.
     *
     * Vaut [ScaleSessionState.Absent] tant qu'aucune balance n'est enregistrée, et
     * [ScaleSessionState.Idle] lorsqu'une balance existe mais que l'écran n'est pas visible.
     * Un écran qui observe ce flux sans jamais appeler [start] ne déclenche aucun scan et ne
     * demande aucune permission.
     */
    val state: StateFlow<ScaleSessionState>

    /**
     * Ouvre une session de recherche de deux minutes (FR-SCALE-020).
     *
     * Sans balance enregistrée, l'appel est sans effet et l'état reste [ScaleSessionState.Absent] :
     * aucun scan, aucune permission demandée, aucun élément d'interface ajouté.
     *
     * Le délai de deux minutes couvre le scan, la connexion et l'attente que l'utilisateur monte
     * sur la balance. À son expiration, la recherche s'arrête définitivement et ne redémarre pas
     * en boucle.
     *
     * Appeler [start] alors qu'une session est déjà en cours ne la redémarre pas.
     */
    fun start()

    /**
     * Ferme toute liaison en cours et arrête le scan. Appelé dès qu'`Entry` cesse d'être visible.
     *
     * Le `sessionId` courant est invalidé : une notification tardive d'une liaison fermée ne
     * pourra jamais compléter une pesée ultérieure (PRD_SCALE 9.4, 21.2).
     */
    fun stop()

    /**
     * Ouvre une nouvelle session de deux minutes après un [ScaleSessionState.NotFound].
     *
     * C'est le seul chemin de relance en dehors d'une réouverture de l'écran (FR-SCALE-020).
     */
    fun retry()

    /**
     * Clôt définitivement la session courante sans quitter l'écran.
     *
     * Appelé après un enregistrement (FR-SCALE-023 : « après l'enregistrement, aucune nouvelle
     * recherche ne démarre tant que l'utilisateur ne quitte pas `Entry` ou n'active pas
     * explicitement `Try again` ») et après toute modification manuelle qui retire la provenance
     * matérielle (BR-SCALE-013).
     *
     * Toute trame arrivée après cet appel est ignorée. En particulier, une impédance tardive ne
     * complète jamais une mesure déjà enregistrée (BR-SCALE-012) : la composition n'est jamais
     * ajoutée en silence après la confirmation `Saved`.
     */
    fun closeSession()
}
