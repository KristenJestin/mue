package fr.kristenjestin.mue.data.scale.ble

import fr.kristenjestin.mue.domain.model.ScaleAdvertisement
import fr.kristenjestin.mue.domain.model.ScaleGattProfile
import fr.kristenjestin.mue.domain.model.ScaleUnavailableReason
import fr.kristenjestin.mue.domain.model.ScaleWrite
import kotlinx.coroutines.flow.Flow

/**
 * Tout ce que la machine à états de session a le droit de savoir du Bluetooth (PRD_SCALE 21.2).
 *
 * **C'est la frontière qui rend la machine testable.** PRD_SCALE 21.3 exige que la machine à états
 * de mesure — déconnexion en cours, timeout de recherche, enregistrement avant impédance, trame
 * tardive d'une ancienne session, mesure hors bornes, impédance absente — soit couverte par des
 * tests unitaires **sans Bluetooth**. Aucune de ces situations n'est reproductible sur du matériel
 * à la demande : une balance ne se déconnecte pas au moment où le test le voudrait, et attendre
 * deux minutes réelles par cas n'est pas une suite de tests. Elles ne le deviennent que si la
 * liaison est une interface qu'un double remplace.
 *
 * Rien ici ne mentionne `android.bluetooth`, ni `Context`, ni permission : l'implémentation réelle
 * ([AndroidScaleTransport]) est le seul fichier du module qui les connaisse, et il ne contient
 * aucune règle métier. Le partage est délibérément placé là où le PRD le place — « une fonction
 * pure d'un côté, une machine à états de connexion de l'autre » — avec une troisième couche, ce
 * transport, qui ne fait qu'exécuter.
 */
internal interface ScaleTransport {

    /**
     * Ce qui empêche un scan d'avoir lieu, ou `null` si rien ne l'empêche (PRD_SCALE 16.1, 18.5).
     *
     * Lu **avant** tout scan et **après** avoir constaté qu'au moins une balance est enregistrée :
     * FR-SCALE-020 interdit de demander quoi que ce soit à l'utilisateur qui n'a jamais appairé de
     * balance. Cette méthode ne demande jamais de permission, elle constate.
     */
    fun availability(): ScaleUnavailableReason?

    /**
     * Les annonces BLE, tant que le flux est collecté.
     *
     * Flux **froid** : le scan démarre à la collecte et s'arrête à son annulation. C'est ce qui
     * donne gratuitement « le scan s'arrête dès qu'une balance candidate est trouvée »
     * (FR-SCALE-020) — la machine collecte jusqu'à la première candidate, et l'annulation de
     * l'opérateur terminal arrête la radio.
     *
     * Le flux échoue par une [ScaleTransportException] lorsque la plateforme refuse le scan ; il ne
     * se termine jamais de lui-même.
     */
    fun scan(): Flow<ScaleAdvertisement>

    /**
     * Ouvre une liaison et **ne rend la main qu'une fois l'abonnement aux notifications effectif**.
     *
     * Ce contrat n'est pas un détail de commodité : PRD_SCALE 14.3 impose que les commandes
     * d'initialisation partent après l'abonnement, parce qu'une balance qui reçoit sa commande de
     * démarrage avant que le descripteur `0x2902` soit écrit répond dans le vide et la pesée reste
     * bloquée sans la moindre erreur. En faisant de « abonné » la condition de retour, aucun
     * appelant ne peut se tromper d'ordre.
     *
     * Lève [ScaleTransportException] si la connexion, la découverte des services ou l'abonnement
     * échouent. Un échec **n'est jamais une erreur affichée** (PRD_SCALE 18.5) : la machine
     * reprend le scan sur le temps restant.
     */
    suspend fun connect(
        advertisement: ScaleAdvertisement,
        profile: ScaleGattProfile,
    ): ScaleLink
}

/**
 * Une liaison ouverte avec **une** balance.
 *
 * L'interface est délibérément séquentielle — une trame après l'autre, une écriture après l'autre —
 * plutôt qu'un flux et un émetteur indépendants. La séquence de mesure de PRD_SCALE 14.3 *est* une
 * séquence : s'abonner, écrire trois commandes chacune après l'acquittement de la précédente,
 * attendre le flux de poids, acquitter le premier poids stable, attendre l'impédance au plus dix
 * secondes. Une API séquentielle rend cette machine lisible de haut en bas, et surtout rend
 * impossible l'erreur que PRD_SCALE 14.3 signale comme « le mode d'échec le plus probable, et il
 * est silencieux ».
 */
internal interface ScaleLink {

    /**
     * La prochaine trame notifiée, ou `null` quand la liaison est tombée.
     *
     * `null` **n'est pas une erreur** : la balance de référence interrompt la liaison quelques
     * secondes après la fin d'une mesure, et c'est la norme (FR-SCALE-021). La machine reprend en
     * silence sur le temps restant (PRD_SCALE 18.5).
     *
     * Les trames reçues pendant qu'une écriture est en cours ne sont **jamais perdues** :
     * l'implémentation les met en file. Sans cela, l'acquittement du poids stable — qui suspend
     * l'appelant jusqu'au callback d'écriture — ferait manquer la trame d'impédance qu'il déclenche.
     */
    suspend fun nextFrame(): ByteArray?

    /**
     * Émet une écriture et, quand [ScaleWrite.awaitAck] est vrai, **attend son acquittement**
     * (PRD_SCALE 14.3).
     *
     * La pile BLE d'Android n'accepte qu'une opération GATT en vol et rejette silencieusement les
     * suivantes. Enchaîner les trois commandes d'initialisation sans attendre est donc la façon
     * classique d'en perdre deux sans le moindre message, et de rester bloqué en attente d'un flux
     * de poids qui ne viendra jamais. Cette méthode est le seul chemin d'écriture du module, et
     * elle sérialise.
     *
     * Lève [ScaleTransportException] si la liaison est tombée ou si l'acquittement n'arrive pas.
     */
    suspend fun write(write: ScaleWrite)

    /**
     * Ferme la liaison. Idempotent, non suspendant : appelable depuis un `finally` déjà annulé.
     *
     * Après cet appel [nextFrame] rend `null`. Une pile BLE réelle peut encore livrer un callback
     * d'une liaison qu'on vient de fermer, ce qui est précisément la raison d'être du contrôle de
     * `sessionId` de PRD_SCALE 21.2 côté machine à états.
     */
    fun close()
}

/**
 * Une panne de liaison : radio, GATT, service absent, écriture refusée, acquittement jamais reçu.
 *
 * **Elle ne s'affiche jamais.** PRD_SCALE 18.5 range la liaison perdue en cours de mesure parmi
 * les situations sans message d'erreur, et FR-SCALE-025 rappelle qu'une balance endormie, hors de
 * portée ou déconnectée ne produit aucun message. Ce type existe pour que la machine distingue
 * « la tentative a échoué, on réessaie sur le temps restant » d'une annulation de la session, pas
 * pour remonter dans l'interface.
 */
internal class ScaleTransportException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Le journal technique du module (PRD_SCALE 18.5).
 *
 * « Trames incohérentes : journalisées en interne. L'utilisateur voit une absence de mesure, pas
 * une erreur de protocole. » Une interface plutôt qu'un appel direct à `android.util.Log` pour une
 * raison précise : `Log` est une classe stub dans les tests JVM, dont chaque méthode lève
 * `RuntimeException("not mocked")`, et le module n'active pas `returnDefaultValues`. Un seul appel
 * à `Log.w` dans la machine à états ferait donc échouer toute la suite de PRD_SCALE 21.3 — celle
 * qui doit tourner « sans Bluetooth, sans Android ». Le journal réel vit dans [AndroidScaleLog].
 */
internal fun interface ScaleLog {

    fun log(message: String)

    companion object {
        /** Le journal des tests et des chemins sans Android : il n'écrit rien. */
        val NONE: ScaleLog = ScaleLog { }
    }
}
