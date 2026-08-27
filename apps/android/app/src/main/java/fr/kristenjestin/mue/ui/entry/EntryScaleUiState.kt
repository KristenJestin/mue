package fr.kristenjestin.mue.ui.entry

import androidx.compose.runtime.Immutable
import fr.kristenjestin.mue.ui.scale.ScaleMessages

/**
 * Ce que l'écran `Entry` dessine **en plus** lorsqu'une balance est appairée.
 *
 * Un objet séparé de [EntryUiState] plutôt que six champs de plus, pour une raison qui est une
 * exigence et non un goût : PRD_SCALE 18.1 veut qu'`Entry` soit *strictement* celui du PRD socle
 * tant qu'aucune balance n'est enregistrée. [ABSENT] est cette garantie sous une forme qu'un test
 * peut nommer — un seul `assertEquals(EntryScaleUiState.ABSENT, state.scale)` ferme la porte à
 * tout élément ajouté, tout badge et toute invite, là où six booléens à `false` se seraient
 * vérifiés un par un et auraient laissé passer le septième.
 *
 * Rien ici ne porte de poids « reçu ». La valeur reçue vit dans [EntryUiState.weight] comme
 * n'importe quelle autre : c'est ce qui rend vraie, par construction, la promesse de FR-SCALE-022
 * qu'une mesure reçue reste entièrement modifiable au doigt, aux boutons et au clavier. Ce type ne
 * dit que *d'où* elle vient et ce que l'écran a le droit d'en déduire.
 *
 * @property paired Au moins une balance est enregistrée (PRD_SCALE 11 : tout sauf `Absent`).
 *   Gouverne la seule réservation de place de l'écran ; sans balance, aucune ligne n'est réservée.
 * @property indicator L'indication discrète de recherche, de connexion ou de mesure, ou `null`.
 * @property liveHundredths Le flux instable, en centièmes de kilogramme, pendant
 *   [EntryScaleIndicator.MEASURING]. **Il pilote l'affichage — la règle et le grand chiffre — et
 *   ne devient jamais [EntryUiState.weight]** (PRD_SCALE 11, BR-SCALE-001). C'est toute la
 *   distinction : la valeur enregistrable a un seul chemin d'écriture, `postWeight`, que le flux
 *   n'emprunte pas ; ce champ est un canal d'affichage parallèle, que `EntryScreen` recopie dans
 *   [RulerState] sans passer par [EntryUiState.weightRevision]. Un appui sur `Save measurement`
 *   ne peut donc pas enregistrer une trame instable, et pas seulement parce que le bouton est
 *   éteint.
 * @property status La ligne actionnable de PRD_SCALE 18.5, ou `null`. Elle ne bloque jamais la
 *   saisie manuelle (BR-SCALE-011).
 * @property fromScale La valeur affichée vient de la balance et personne n'y a touché depuis
 *   (FR-SCALE-022). C'est aussi la condition pour joindre une impédance à l'enregistrement : une
 *   fois `false`, l'impédance reçue ou attendue est invalidée (BR-SCALE-013).
 * @property arrivalRevision [EntryUiState.weightRevision] au moment où la valeur a été posée par
 *   la balance. L'écran s'en sert pour distinguer la révision qu'il doit *rejoindre en glissant*
 *   (PRD_SCALE 19) de celles qu'il rejoint d'un saut — le seed historique, `−` / `+`, le clavier.
 * @property outOfRange Une mesure **stable** hors de `30.0–250.0 kg` vient d'arriver
 *   (FR-SCALE-024). L'écran l'annonce et ne change rien d'autre.
 * @property barefootHint Le pilote a explicitement signalé une impédance non mesurable
 *   (BR-SCALE-005). Jamais après un enregistrement anticipé ni après un délai écoulé
 *   (PRD_SCALE 18.3).
 * @property announcement Le seul changement à annoncer aux services d'accessibilité, ou `null`
 *   (PRD_SCALE 20). Volontairement pas une chaîne : le texte se compose avec le poids **formaté
 *   par l'écran**, pour que l'annonce et la valeur visible ne puissent jamais diverger.
 * @property keepScreenOn La session de recherche court : l'écran reste éveillé le temps que
 *   l'utilisateur pose son téléphone et monte sur la balance (FR-SCALE-020).
 */
@Immutable
data class EntryScaleUiState(
    val paired: Boolean = false,
    val indicator: EntryScaleIndicator? = null,
    val liveHundredths: Int? = null,
    val status: EntryScaleStatus? = null,
    val fromScale: Boolean = false,
    val arrivalRevision: Int = 0,
    val outOfRange: Boolean = false,
    val barefootHint: Boolean = false,
    val announcement: EntryScaleAnnouncement? = null,
    val keepScreenOn: Boolean = false,
) {

    /**
     * Le flux instable court : la valeur à l'écran n'appartient à personne encore.
     *
     * C'est le seul prédicat de cet écran qui éteigne quoi que ce soit, et il n'en éteint que
     * trois : les contrôles `−` et `+`, la saisie au clavier et `Save measurement`. Chacun des
     * trois se battrait avec la balance pour la même valeur ; tout le reste — la règle au doigt,
     * la date, les onglets — reste vivant, parce que BR-SCALE-011 veut que toutes les fonctions de
     * Mue restent disponibles et que le §7.3 veut que la balance propose là où l'utilisateur
     * dispose. Verrouiller l'écran ferait de la balance un maître.
     */
    val streaming: Boolean get() = indicator == EntryScaleIndicator.MEASURING

    /**
     * La pastille d'en-tête, ou `null` quand aucune balance n'est enregistrée (FR-SCALE-020).
     *
     * Dérivée plutôt que portée : l'état de liaison est déjà entièrement décrit par les quatre
     * champs qu'elle lit, et un cinquième champ à tenir en cohérence avec eux serait un endroit de
     * plus où l'écran pourrait mentir. Écrite ici et non dans le composable pour qu'un test JVM
     * puisse la lire — ce que dit la pastille dans chaque état est une règle, pas un pixel.
     *
     * **Trois branches, et la dernière est une offre** (FR-SCALE-023). Ce qui demande un geste
     * système passe en premier ; une session vivante — et elle seule — passe ensuite ; tout le
     * reste propose de relancer une recherche. Cette troisième branche est la correction d'un
     * défaut, pas un ajout : la pastille n'était actionnable que dans les quatre états de
     * PRD_SCALE 18.5, si bien qu'après un enregistrement, après une reprise en main et après une
     * mesure hors bornes, l'écran n'offrait plus **aucun** chemin vers une nouvelle pesée — il
     * fallait quitter l'onglet `Entry` et y revenir, ce que rien n'indiquait. FR-SCALE-023 dit
     * pourtant « tant que l'utilisateur ne quitte pas `Entry` **ou n'active pas explicitement
     * `Try again`** » : le second membre de cette phrase n'existait que derrière l'expiration des
     * deux minutes.
     *
     * Ce qui distingue encore ces trois culs-de-sac tient en un booléen, [fromScale], et il ne
     * décide que de la couleur : la valeur à l'écran vient de la balance, donc l'en-tête reste
     * ambre et la provenance ne se perd pas entre l'enregistrement et la pesée suivante. Le
     * libellé, lui, est le même partout — l'affordance est ce qu'on est venu chercher, et une
     * pastille cliquable qui ne dit pas qu'elle l'est vaut moins que pas de bouton du tout.
     *
     * [indicator] passe **avant** [fromScale], ce qui n'était pas le cas tant que la relance
     * n'existait pas : une session ne pouvait alors se rouvrir derrière une valeur reçue qu'en
     * quittant l'écran. Elle se rouvre maintenant d'un doigt, et une pastille qui répondrait
     * « poids reçu » à quelqu'un qui vient d'appuyer sur `Try again` serait muette au moment
     * précis où on lui demande quelque chose. Aucun autre état n'est concerné : une mesure posée
     * efface l'indication en arrivant (`acceptReading`), donc les deux champs ne sont jamais
     * vrais ensemble en dehors d'une relance.
     */
    val linkChip: EntryLinkChip?
        get() {
            if (!paired) return null
            status?.let { actionable ->
                // PRD_SCALE 20 : l'indisponibilité est le seul changement que cette pastille
                // annonce d'elle-même ; l'arrivée d'une mesure appartient à la marque de
                // provenance, qui la dit avec sa valeur et une seule fois.
                val announced = announcement == EntryScaleAnnouncement.UNAVAILABLE
                return EntryLinkChip(
                    label = actionable.chipLabel,
                    description = if (announced) {
                        ScaleMessages.UNAVAILABLE_ANNOUNCEMENT
                    } else {
                        actionable.message
                    },
                    active = false,
                    pulsing = false,
                    announce = announced,
                    action = actionable.action,
                )
            }
            indicator?.let {
                // Une session court : il n'y a rien à relancer, et le point respire pour le dire.
                return EntryLinkChip(
                    label = it.chipLabel,
                    description = it.message,
                    active = true,
                    pulsing = true,
                )
            }
            return EntryLinkChip(
                label = ScaleMessages.LINK_TRY_AGAIN,
                description = ScaleMessages.LINK_SEARCH_AGAIN,
                active = fromScale,
                pulsing = false,
                action = EntryScaleAction.RESTART_SEARCH,
            )
        }

    companion object {
        /**
         * Aucune balance enregistrée : l'écran est exactement celui du PRD socle (PRD_SCALE 18.1).
         *
         * C'est aussi l'état d'une application dont la couche de liaison n'est pas câblée, ce qui
         * en fait le défaut de [EntryUiState] et non un cas particulier.
         */
        val ABSENT: EntryScaleUiState = EntryScaleUiState()
    }
}

/**
 * Tout ce que la pastille d'en-tête dessine, en une valeur (PRD_SCALE 11, 19, 20).
 *
 * Elle est le seul porteur de l'état de liaison depuis que celui-ci a quitté le bas de l'écran :
 * l'ancienne version le répartissait entre un point de présence dans l'en-tête et une légende sous
 * la valeur, et aucun des deux ne répondait à la première question qu'on se pose — « est-ce qu'elle
 * parle à ma balance, là, maintenant ? ».
 *
 * **Discrète par construction** (PRD_SCALE 19) : une pastille de la taille de celle qui portait la
 * date, dans un coin, qui ne concurrence jamais la valeur du poids.
 *
 * @property label Deux mots au plus, ou `null` quand la pastille n'a rien à écrire. Un [label] nul
 *   et une [action] non nulle ne vont jamais ensemble : un bouton qui ne se nomme pas est pire
 *   que pas de bouton, et c'est la raison pour laquelle `Try again` s'affiche jusque derrière une
 *   valeur reçue, là où la pastille se taisait autrefois (FR-SCALE-023).
 * @property description Ce qu'un lecteur d'écran entend, **toujours complet**, y compris quand
 *   [label] est `null` : une couleur et un point ne s'énoncent pas (PRD_SCALE 20). Dès qu'il y a
 *   une [action], cette phrase dit le **geste** et non l'état : c'est le nom accessible d'un
 *   bouton.
 * @property active La liaison vit : ambre plutôt que gris. Vrai pendant la session et tant que la
 *   valeur à l'écran vient de la balance, faux quand rien ne s'est passé ou que quelque chose
 *   attend un geste système.
 * @property pulsing Le point respire, parce que quelque chose est en cours. Une mesure posée ne
 *   pulse pas : elle est arrivée.
 * @property announce Le seul cas où la pastille prend la parole d'elle-même (PRD_SCALE 20).
 * @property action Le geste offert, ou `null` — ce dernier cas étant exactement celui d'une
 *   session en cours. Rien ne s'ouvre et rien ne se relance sans lui.
 */
@Immutable
data class EntryLinkChip(
    val label: String?,
    val description: String,
    val active: Boolean,
    val pulsing: Boolean,
    val announce: Boolean = false,
    val action: EntryScaleAction? = null,
)

/**
 * Ce qu'un appui sur la pastille déclenche — et rien de ce qui l'a rendue actionnable.
 *
 * **Pourquoi un type de plus.** La pastille portait jusqu'ici un [EntryScaleStatus] en guise
 * d'action, ce qui allait tant que tout geste naissait d'un état de PRD_SCALE 18.5. FR-SCALE-023
 * en demande un qui n'a pas d'état : relancer une recherche après un enregistrement, après une
 * reprise en main ou après une mesure hors bornes. Réemployer [EntryScaleStatus.NOT_FOUND] pour ce
 * geste aurait été un mensonge à deux étages — « scale not found » est faux juste après une pesée
 * réussie, et cette valeur porte la phrase de PRD_SCALE 18.5 mot pour mot, donc un lecteur
 * d'écran se serait entendu annoncer une balance introuvable au moment où il venait d'en recevoir
 * le poids. Séparer les deux notions rend la chose impossible à écrire : un état décrit ce qui
 * *est*, une action décrit ce qui *arrive* quand on appuie, et l'écran n'a plus besoin du premier
 * pour router le second.
 *
 * Trois de ces quatre valeurs ouvrent un écran du système, ce que seule l'interface peut faire —
 * un `ViewModel` n'a pas de `Context` et ne doit pas en acquérir un. Aucune n'ouvre quoi que ce
 * soit d'elle-même : chacune décrit un geste que l'utilisateur a fait (FR-SCALE-025).
 */
enum class EntryScaleAction {

    /**
     * Ouvre une nouvelle session de deux minutes (FR-SCALE-020, FR-SCALE-023).
     *
     * Le `Try again` de FR-SCALE-023, offert dès qu'une balance est appairée et qu'aucune session
     * ne court — pas seulement à l'expiration du délai. La session close ne renaît pas au passage :
     * la nouvelle porte un autre `sessionId`, et l'ancien reste retenu pour que ses trames tardives
     * ne complètent jamais celle-ci (PRD_SCALE 9.4, BR-SCALE-012).
     */
    RESTART_SEARCH,

    /** Ouvre la demande d'activation de la radio, portée par le système. */
    ENABLE_BLUETOOTH,

    /** Ouvre la fiche de l'application, seul endroit où une permission refusée se rend. */
    OPEN_APP_SETTINGS,

    /** Ouvre le réglage de localisation du système (API ≤ 30, PRD_SCALE 16.1). */
    OPEN_LOCATION_SETTINGS,
}

/**
 * L'indication discrète de PRD_SCALE 11, réduite à ce que l'écran en montre.
 *
 * Quatre états du domaine et quatre libellés, alors que `Searching` et `Connecting` pourraient se
 * confondre : les distinguer ne coûte rien ici et le mot exact est celui de PRD_SCALE 11.
 *
 * **Discret par construction** (PRD_SCALE 19) : une pastille dans un coin et une légende sous la
 * valeur, jamais une carte, jamais un élément qui pourrait concurrencer la valeur du poids.
 *
 * @property message La phrase entière : ce qu'un lecteur d'écran entend, et ce que la légende sous
 *   la valeur affiche quand elle a quelque chose à dire.
 * @property chipLabel La même chose en deux mots, pour le coin de l'écran. `Looking for your scale`
 *   est la bonne phrase sous une valeur et déborde d'une pastille ; les deux états dont la phrase
 *   tient déjà en un mot réemploient la leur au lieu d'en inventer une seconde.
 */
enum class EntryScaleIndicator(val message: String, val chipLabel: String) {
    SEARCHING(ScaleMessages.SEARCHING, ScaleMessages.LINK_SEARCHING),
    CONNECTING(ScaleMessages.CONNECTING, ScaleMessages.CONNECTING),
    STEP_ON(ScaleMessages.STEP_ON_THE_SCALE, ScaleMessages.LINK_READY),
    MEASURING(ScaleMessages.MEASURING, ScaleMessages.MEASURING),
}

/**
 * Les trois lignes actionnables de PRD_SCALE 18.5, plus le cas de la localisation système.
 *
 * Une seule est jamais à l'écran, ce qui est la raison pour laquelle `ScaleTestTags.ENTRY_STATUS`
 * en couvre trois : c'est le texte qui les distingue.
 *
 * [SYSTEM_LOCATION_OFF] réemploie délibérément le libellé de [PERMISSION_MISSING]. PRD_SCALE 18.5
 * ne réserve que trois phrases pour `Entry` et en inventer une quatrième les ferait diverger de
 * `ScaleMessages`, qui est verrouillé mot pour mot ; l'écran des balances, lui, garde l'explication
 * complète de PRD_SCALE 16.1. Ce qui change ici est la destination du geste — le réglage de
 * localisation plutôt que la fiche de l'application —, pas la phrase.
 *
 * Aucune de ces valeurs n'ouvre quoi que ce soit toute seule : chacune décrit un geste que
 * l'utilisateur peut faire, jamais un écran système que Mue ouvrirait de lui-même (FR-SCALE-025).
 *
 * @property message La phrase de PRD_SCALE 18.5, mot pour mot, point médian compris. Elle n'a pas
 *   quitté l'écran en passant dans l'en-tête : elle est ce que la pastille **dit**, donc son nom
 *   accessible (PRD_SCALE 20), là où [chipLabel] est ce qu'elle **montre**.
 * @property chipLabel La même chose en deux mots. Ce qui survit à la coupe est chaque fois l'offre
 *   — `Try again`, `Bluetooth off` — parce que c'est elle qui dit quoi faire du doigt.
 * @property action Ce que l'appui déclenche. Porté par l'état plutôt que déduit par l'écran : le
 *   lien entre un constat et le geste qui le répare est une règle du module, et la table où il
 *   s'écrit doit être la même que celle des phrases.
 */
enum class EntryScaleStatus(
    val message: String,
    val chipLabel: String,
    val action: EntryScaleAction,
) {
    /** Les deux minutes se sont écoulées. Le geste relance une session (FR-SCALE-020). */
    NOT_FOUND(
        ScaleMessages.SCALE_NOT_FOUND,
        ScaleMessages.LINK_TRY_AGAIN,
        EntryScaleAction.RESTART_SEARCH,
    ),

    /** La radio est éteinte. Le geste ouvre la demande d'activation du système. */
    BLUETOOTH_OFF(
        ScaleMessages.BLUETOOTH_IS_OFF,
        ScaleMessages.LINK_BLUETOOTH_OFF,
        EntryScaleAction.ENABLE_BLUETOOTH,
    ),

    /** Permission absente ou révoquée. Le geste ouvre la fiche de l'application. */
    PERMISSION_MISSING(
        ScaleMessages.SCALE_UNAVAILABLE,
        ScaleMessages.LINK_UNAVAILABLE,
        EntryScaleAction.OPEN_APP_SETTINGS,
    ),

    /** API ≤ 30 : le scanner de la plateforme exige la localisation système. */
    SYSTEM_LOCATION_OFF(
        ScaleMessages.SCALE_UNAVAILABLE,
        ScaleMessages.LINK_UNAVAILABLE,
        EntryScaleAction.OPEN_LOCATION_SETTINGS,
    ),
}

/**
 * Ce que les services d'accessibilité doivent entendre, et rien de plus (PRD_SCALE 20).
 *
 * Deux événements seulement — l'arrivée d'une mesure et l'indisponibilité —, **jamais une trame**.
 * Une région active branchée sur l'indicateur parlerait à chaque poids instable reçu, c'est-à-dire
 * plusieurs fois par seconde pendant qu'on monte sur la balance.
 */
enum class EntryScaleAnnouncement {
    /** Une mesure stable vient d'être posée sur la règle. Annoncée **avec sa valeur**. */
    MEASUREMENT_RECEIVED,

    /** La balance est devenue inutilisable. Le reste de l'écran fonctionne (BR-SCALE-011). */
    UNAVAILABLE,
}
