package fr.kristenjestin.mue.domain.model

import java.util.UUID

/**
 * Ce qu'un appareil annonce lorsqu'il est découvert par un scan BLE (PRD_SCALE 9.2).
 *
 * C'est la seule entrée de [ScaleDriver.recognises] : un pilote décide s'il sait piloter un
 * appareil à partir de son nom, de ses UUID de service et de ses données fabricant, et de rien
 * d'autre. Le type est volontairement dépourvu de toute dépendance Android, afin que la
 * reconnaissance soit testable en JVM pure (PRD_SCALE 21.3).
 *
 * L'égalité est **structurelle**, y compris pour [manufacturerData] : un `ByteArray` compare par
 * référence en Kotlin, si bien que l'égalité générée par `data class` déclarerait différentes deux
 * annonces au contenu identique. Les tests de reconnaissance des pilotes construisent leurs
 * annonces à la main et échoueraient de façon incompréhensible ; [equals] et [hashCode] sont donc
 * écrits à la main.
 *
 * @property address Adresse Bluetooth vue par le scan. Indice de localisation, jamais une identité
 *   (PRD_SCALE 10.1) — voir [ScaleDevice.id].
 * @property name Nom annoncé, `null` quand l'appareil n'en publie pas.
 * @property serviceUuids UUID de service annoncés, en **minuscules** et sous forme 128 bits
 *   canonique. La normalisation appartient à la couche de scan, pas aux pilotes : sans elle chaque
 *   pilote devrait réimplémenter la comparaison de la forme courte 16 bits et de la forme longue.
 * @property manufacturerData Données fabricant, indexées par identifiant de société.
 */
data class ScaleAdvertisement(
    val address: String,
    val name: String?,
    val serviceUuids: List<String>,
    val manufacturerData: Map<Int, ByteArray>,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScaleAdvertisement) return false
        if (address != other.address) return false
        if (name != other.name) return false
        if (serviceUuids != other.serviceUuids) return false
        if (manufacturerData.size != other.manufacturerData.size) return false
        for ((company, bytes) in manufacturerData) {
            val theirs = other.manufacturerData[company] ?: return false
            if (!bytes.contentEquals(theirs)) return false
        }
        return true
    }

    override fun hashCode(): Int {
        var result = address.hashCode()
        result = 31 * result + (name?.hashCode() ?: 0)
        result = 31 * result + serviceUuids.hashCode()
        // Somme et non accumulation ordonnée : le parcours d'une Map n'a pas d'ordre garanti,
        // alors que deux annonces égales doivent avoir le même code de hachage.
        result = 31 * result + manufacturerData.entries.sumOf { (company, bytes) ->
            31 * company + bytes.contentHashCode()
        }
        return result
    }

    override fun toString(): String =
        "ScaleAdvertisement(address=$address, name=$name, serviceUuids=$serviceUuids, " +
            "manufacturerData=${manufacturerData.keys})"
}

/**
 * Ce qu'un modèle de balance sait mesurer (PRD_SCALE 9.2).
 *
 * Déclaré par le pilote et non déduit à l'usage : c'est ce qui permet à l'interface de savoir, dès
 * l'appairage, qu'aucune composition corporelle ne viendra jamais d'un appareil sans impédance, et
 * donc de ne rien promettre. Une balance qui *peut* mesurer l'impédance mais n'y parvient pas ce
 * jour-là relève d'un autre cas, celui de FR-BODY-002.
 *
 * @property providesWeight Déclaratif : **aucun code ne le lit aujourd'hui**, et pour cause, tous
 *   les pilotes livrés valent `true` — un appareil qui ne pèse pas n'est pas une balance. Il reste
 *   du contrat de domaine écrit en vague 0 ; le retirer toucherait des fichiers hors du périmètre
 *   de ce correctif et se décide au niveau du contrat, pas ici.
 * @property providesImpedance Lu par la machine à états : un pilote qui ne fournit pas d'impédance
 *   conclut sur `Complete` sans attendre les dix secondes de PRD_SCALE 14.3.
 */
data class ScaleCapabilities(
    val providesWeight: Boolean,
    val providesImpedance: Boolean,
)

/**
 * Comment on écoute une balance (PRD_SCALE 9.2).
 *
 * [GATT] ouvre une connexion, s'abonne à une caractéristique et dialogue ; [ADVERTISEMENT] se
 * contente d'écouter les annonces diffusées, sans jamais se connecter. Les deux modes existent
 * dans la nature et l'un ne peut pas émuler l'autre : les distinguer ici évite à la couche de
 * liaison de deviner.
 */
enum class ScaleLinkMode { GATT, ADVERTISEMENT }

/**
 * Le profil GATT qu'une couche BLE doit ouvrir pour un pilote (PRD_SCALE 9.2, 14.6).
 *
 * Le pilote décrit *quoi* ouvrir, la couche data sait *comment* : c'est la séparation exigée par
 * PRD_SCALE 21.2 entre décodage pur et machine de liaison.
 *
 * Trois UUID et non un service standard : la balance de référence expose bien un service de
 * pesage normalisé, mais il est inutilisable en pratique (PRD_SCALE 14.6), et le protocole réel
 * passe par un service propriétaire.
 *
 * @property service Service à découvrir.
 * @property notify Caractéristique à laquelle s'abonner pour recevoir les trames.
 * @property write Caractéristique sur laquelle émettre les [ScaleWrite] du pilote.
 */
data class ScaleGattProfile(
    val service: UUID,
    val notify: UUID,
    val write: UUID,
)

/**
 * Une écriture que le pilote demande d'émettre.
 *
 * **Une seule à la fois.** La couche de liaison émet la suivante après l'acquittement de la
 * précédente lorsque [awaitAck] est vrai : la pile BLE d'Android n'accepte qu'une opération GATT
 * en vol et rejette silencieusement les suivantes, ce qui produirait une séquence de mesure
 * tronquée sans aucune erreur visible.
 *
 * L'égalité est **structurelle** : [bytes] est un `ByteArray`, que Kotlin compare par référence.
 * Une `data class` déclarerait donc différentes deux écritures d'octets identiques, et toutes les
 * assertions du type `assertEquals(listOf(ScaleWrite(...)), session.onSubscribed())` échoueraient
 * sans que le message de test n'explique rien. [equals] et [hashCode] sont écrits à la main pour
 * cette raison.
 *
 * @property bytes Trame à écrire, telle quelle.
 * @property awaitAck Attendre l'acquittement avant l'écriture suivante. Vrai par défaut.
 */
data class ScaleWrite(
    val bytes: ByteArray,
    val awaitAck: Boolean = true,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScaleWrite) return false
        return awaitAck == other.awaitAck && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + awaitAck.hashCode()

    override fun toString(): String =
        "ScaleWrite(bytes=${bytes.joinToString(" ") { "%02x".format(it) }}, awaitAck=$awaitAck)"
}

/**
 * Ce qu'une trame reçue signifie, du point de vue du domaine (PRD_SCALE 9.2, 21.2).
 *
 * Produit par une fonction **pure** — [ScaleDriverSession.onFrame] — qui ne lève jamais. Une trame
 * arrive d'un appareil tiers dont on ne contrôle ni le micrologiciel ni l'état des piles : la
 * seule discipline tenable est qu'un octet inattendu produise un événement, jamais une exception
 * remontant dans un callback BLE où plus personne ne peut la rattraper.
 *
 * Le type ferme la liste des interprétations possibles, ce qui rend le `when` de la machine à
 * états exhaustif et vérifié à la compilation.
 */
sealed interface ScaleFrameEvent {

    /**
     * Trame valide mais sans intérêt pour le domaine — battement de cœur, écho d'une commande,
     * état intermédiaire du protocole. Se distingue de [Rejected] : rien d'anormal n'est arrivé.
     */
    data object Ignored : ScaleFrameEvent

    /**
     * Trame refusée : contrôle par OU exclusif faux, longueur incohérente, entête inconnu
     * (BR-SCALE-003). Elle est journalisée et **jamais interprétée**.
     *
     * @property reason Texte de **diagnostic interne, en anglais**, destiné au journal technique.
     *   Il n'est jamais affiché à l'utilisateur : PRD_SCALE 18.5 range les trames incohérentes
     *   parmi les erreurs journalisées en interne, précisément parce qu'un message de protocole
     *   n'apprend rien à quelqu'un qui monte sur une balance. Il n'est donc ni traduit, ni
     *   localisé, ni stable d'une version à l'autre.
     */
    data class Rejected(val reason: String) : ScaleFrameEvent

    /**
     * Une mesure de poids.
     *
     * @property hundredthsKg Poids en centièmes de kilogramme, **non validé** : les bornes de
     *   BR-SCALE-002 sont appliquées par la machine à états, pas par le décodeur, afin qu'une
     *   valeur hors domaine reste distinguable d'une trame illisible.
     * @property stable Fin de pesée. Une valeur instable s'affiche mais ne s'enregistre jamais
     *   (BR-SCALE-001).
     * @property replies Écritures que le protocole exige en réponse à cette trame — accusé,
     *   passage à l'étape d'impédance. Le décodeur reste pur : il *décrit* ce qu'il faut émettre,
     *   il n'émet rien.
     */
    data class Weight(
        val hundredthsKg: Int,
        val stable: Boolean,
        val replies: List<ScaleWrite>,
    ) : ScaleFrameEvent

    /**
     * Une mesure d'impédance.
     *
     * @property ohm Impédance corporelle totale, `null` quand le pilote a explicitement reconnu le
     *   marqueur de mesure impossible (BR-SCALE-005, FR-BODY-002) — contact insuffisant, pieds non
     *   nus. C'est une absence, jamais une valeur : le poids, lui, reste parfaitement valide.
     * @property replies Écritures exigées en réponse, comme pour [Weight].
     */
    data class Impedance(
        val ohm: Int?,
        val replies: List<ScaleWrite>,
    ) : ScaleFrameEvent
}

/**
 * Un pilote de protocole (PRD_SCALE 9.2).
 *
 * Une unité de code livrée avec l'application : jamais configurée par l'utilisateur, jamais
 * téléchargée, jamais désactivable. Un pilote sait parler à une *famille* d'appareils, là où un
 * [ScaleDevice] est *un* appareil physique.
 *
 * **Pourquoi la reconnaissance vit ici et non dans une table centrale** (PRD_SCALE 9.2). Si un
 * fichier partagé associait « ce nom annoncé → ce pilote », chaque nouveau modèle imposerait de
 * modifier ce fichier, et donc de rouvrir un point de conflit commun à tous les pilotes. Pire, la
 * table ne pourrait exprimer que les critères qu'elle a prévus : le jour où un modèle ne se
 * reconnaît qu'à un octet de ses données fabricant, il faudrait étendre le schéma de la table pour
 * tout le monde. En déléguant [recognises] au pilote, le critère est du code arbitraire, local, et
 * testable isolément. Conséquence directe, exigée par FR-SCALE-030 : **ajouter un modèle de
 * balance = un fichier de pilote + une ligne dans le registre**, aucun écran, aucun état
 * d'interface, aucune table.
 *
 * Aucun membre de cette interface ne touche à Android ni au Bluetooth : un pilote se teste
 * entièrement en JVM pure (PRD_SCALE 21.3).
 */
interface ScaleDriver {

    /** Identifiant stable, stocké dans [ScaleDevice.driverId]. Le renommer orpheline les balances. */
    val id: String

    /** Nom lisible du modèle ou de la famille. Sert de [ScaleDevice.displayName] par défaut. */
    val modelName: String

    val linkMode: ScaleLinkMode

    val capabilities: ScaleCapabilities

    /** `null` en mode [ScaleLinkMode.ADVERTISEMENT], où aucune connexion n'est ouverte. */
    val gattProfile: ScaleGattProfile?

    /** Ce pilote sait-il parler à l'appareil qui a émis cette annonce ? Fonction pure. */
    fun recognises(advertisement: ScaleAdvertisement): Boolean

    /**
     * Ouvre l'état protocolaire d'une nouvelle liaison. Un appel par tentative de pesée : une
     * session est jetable et ne doit jamais être réutilisée d'une liaison à la suivante, sans quoi
     * l'étape en cours d'une pesée abandonnée contaminerait la suivante.
     */
    fun newSession(): ScaleDriverSession
}

/**
 * La machine du protocole d'**une** liaison (PRD_SCALE 21.2).
 *
 * Sans état Android, sans Bluetooth, testable en JVM pure : la couche de liaison lui passe les
 * octets reçus et exécute ce qu'elle renvoie. C'est la séparation exigée par PRD_SCALE 21.2 entre
 * « une fonction pure d'un côté, une machine à états de connexion de l'autre » — et ce qui permet
 * de couvrir intégralement le décodage avant toute mise au point sur matériel (PRD_SCALE 21.3).
 *
 * Une session est **mutable et à usage unique** : elle mémorise l'étape atteinte dans la séquence
 * de mesure. Elle n'est jamais partagée entre deux liaisons.
 */
interface ScaleDriverSession {

    /**
     * Écritures à émettre dès l'abonnement aux notifications établi, dans l'ordre.
     *
     * Pas plus tôt : une balance qui reçoit sa commande de démarrage avant que l'abonnement soit
     * effectif répond dans le vide, et la pesée reste bloquée sans erreur.
     */
    fun onSubscribed(): List<ScaleWrite>

    /**
     * Décodage **pur** d'une notification. Ne doit **jamais** lever : une trame tronquée,
     * inattendue ou corrompue produit [ScaleFrameEvent.Rejected] (BR-SCALE-003).
     */
    fun onFrame(frame: ByteArray): ScaleFrameEvent
}

/**
 * L'ensemble des pilotes livrés avec l'application (PRD_SCALE 9.2, 15).
 *
 * Le registre n'est pas une table de correspondance : il ne connaît aucun critère de
 * reconnaissance, il se contente d'interroger ses pilotes dans l'ordre. C'est la seule liste
 * partagée que l'ajout d'un modèle modifie (FR-SCALE-030).
 */
interface ScaleDriverRegistry {

    /** Les pilotes, dans l'ordre d'interrogation de [recognise]. */
    val drivers: List<ScaleDriver>

    /**
     * Le pilote de [id], ou `null` s'il n'existe plus. Une balance appairée par une version
     * antérieure peut référencer un pilote retiré depuis : le cas doit se lire, pas planter.
     */
    fun byId(id: String): ScaleDriver?

    /** Le premier pilote qui reconnaît cette annonce, ou `null`. */
    fun recognise(advertisement: ScaleAdvertisement): ScaleDriver?
}
