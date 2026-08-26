package fr.kristenjestin.mue.ui.scale

import androidx.compose.runtime.Immutable

/**
 * Un appareil découvert qu'un pilote sait lire (FR-SCALE-011).
 *
 * @property modelName Le modèle identifié, montré à côté du nom annoncé : c'est ce qui distingue
 *   cette section du reste de la liste, et ce qui permet de choisir sans se tromper entre deux
 *   appareils au nom obscur.
 * @property alreadyPairedAs Le nom donné à cette balance si **cette adresse même** est déjà
 *   enregistrée. Elle reste affichée plutôt que masquée : la retrouver en scannant est la façon la
 *   plus naturelle de vérifier qu'elle est bien celle qu'on croit. Elle n'est simplement pas
 *   sélectionnable, faute d'avoir quoi que ce soit à faire.
 * @property reattachTo La balance enregistrée que cet appareil pourrait être (FR-SCALE-001). Non
 *   nul lorsque l'adresse n'est connue de personne mais que le nom annoncé et le pilote désignent
 *   une balance enregistrée qui, elle, ne répond plus.
 */
@Immutable
internal data class DiscoveredScale(
    val address: String,
    val advertisedName: String,
    val driverId: String,
    val modelName: String,
    val alreadyPairedAs: String? = null,
    val reattachTo: ReattachCandidate? = null,
) {
    /** Rien à faire d'un appareil déjà appairé sous cette adresse. */
    val selectable: Boolean get() = alreadyPairedAs == null
}

/**
 * Une balance enregistrée qu'un appareil découvert pourrait être (FR-SCALE-001).
 *
 * Porte le nom donné par l'utilisateur, parce que c'est la seule chose qui permette de répondre à
 * la question posée : « est-ce là ta *Bathroom scale* ? ». L'identifiant ne sert qu'à agir.
 */
@Immutable
internal data class ReattachCandidate(
    val scaleId: String,
    val displayName: String,
)

/**
 * Un appareil que Mue voit sans savoir le lire (FR-SCALE-011).
 *
 * Ils sont listés à dessein, grisés et non sélectionnables : c'est ce qui permet à l'utilisateur de
 * constater que Mue voit bien sa balance mais ne sait pas encore lui parler, plutôt que de conclure
 * à une panne Bluetooth. Seuls les appareils qui **annoncent un nom** figurent ici — un scan BLE
 * voit des dizaines d'objets anonymes, et une liste d'adresses ne dit rien à personne, donc ne
 * remplirait pas l'unique fonction de cette section.
 */
@Immutable
internal data class UnsupportedDevice(
    val address: String,
    val name: String,
)

/**
 * La question posée avant tout rattachement (FR-SCALE-001).
 *
 * **Proposé, jamais silencieux** : deux balances identiques dans un même foyer ne doivent pas
 * fusionner à l'insu de l'utilisateur. Les deux réponses sont donc toutes deux constructives —
 * rattacher, ou appairer comme un second appareil — et aucune ne perd de données.
 */
@Immutable
internal data class ReattachProposal(
    val device: DiscoveredScale,
    val candidate: ReattachCandidate,
)

/**
 * Ce qui empêche un scan de démarrer, et que l'utilisateur peut corriger (PRD_SCALE 16.1, 18.5).
 *
 * Hissé hors de `ScalePermissionsState` pour que l'écran sans état — celui que les tests Compose
 * pilotent — n'ait besoin d'aucune permission Android pour dessiner ses quatre cas. L'ordre de
 * lecture est celui que `ScalePermissions` documente : la permission, puis la radio, puis la
 * localisation système.
 */
internal enum class ScanGate {

    /** Rien ne s'y oppose. */
    READY,

    /**
     * La permission n'a jamais été demandée. FR-SCALE-025 la demande **ici**, au premier
     * appairage, où sa raison est évidente — jamais au lancement de l'application.
     */
    PERMISSION_NEEDED,

    /** Demandée, refusée, et le système ne montrera plus sa boîte de dialogue. Reste les réglages. */
    PERMISSION_DENIED,

    /** La radio est éteinte. PRD_SCALE 18.5 propose de l'allumer plutôt que de le constater. */
    BLUETOOTH_OFF,

    /**
     * API ≤ 30 seulement : la localisation système conditionne tout scan BLE. Exigence de la
     * plateforme, expliquée plutôt que subie comme une liste vide (PRD_SCALE 16.1).
     */
    SYSTEM_LOCATION_OFF,
}

/**
 * Tout ce que le flux d'appairage dessine (FR-SCALE-011, 012, FR-SCALE-001).
 *
 * @property scanning Les trente secondes de FR-SCALE-011 courent. À leur terme le scan s'arrête et
 *   propose de recommencer ; il ne repart jamais tout seul.
 * @property started Un scan a déjà tourné. Distingue « on n'a pas encore cherché » de « on a
 *   cherché et rien trouvé », qui n'ont pas la même phrase (PRD_SCALE 7.3 : le silence n'est pas
 *   une erreur, mais il mérite d'être nommé).
 * @property pairedScaleId Renseigné une seule fois, quand une association a réussi : l'écran
 *   revient alors à la liste (FR-SCALE-012) et acquitte.
 */
@Immutable
internal data class ScaleScanUiState(
    val gate: ScanGate = ScanGate.READY,
    val scanning: Boolean = false,
    val started: Boolean = false,
    val recognised: List<DiscoveredScale> = emptyList(),
    val unsupported: List<UnsupportedDevice> = emptyList(),
    val proposal: ReattachProposal? = null,
    val pairedScaleId: String? = null,
) {
    /** Le scan est allé jusqu'au bout et n'a reconnu aucune balance (PRD_SCALE 7.3). */
    val finishedEmptyHanded: Boolean
        get() = started && !scanning && recognised.isEmpty()
}
