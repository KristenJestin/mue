package fr.kristenjestin.mue.di

import android.content.Context
import android.content.pm.ApplicationInfo
import fr.kristenjestin.mue.data.local.database.MueDatabase
import fr.kristenjestin.mue.data.repository.RoomScaleRepository
import fr.kristenjestin.mue.data.scale.ble.AndroidScaleLog
import fr.kristenjestin.mue.data.scale.ble.AndroidScaleTransport
import fr.kristenjestin.mue.data.scale.ble.BleScaleSessionSource
import fr.kristenjestin.mue.data.scale.ble.ScaleTransport
import fr.kristenjestin.mue.data.scale.protocol.MueScaleDrivers
import fr.kristenjestin.mue.domain.model.ScaleDriverRegistry
import fr.kristenjestin.mue.domain.repository.ScaleRepository
import fr.kristenjestin.mue.domain.repository.ScaleSessionSource
import fr.kristenjestin.mue.ui.scale.ScaleDiscovery
import fr.kristenjestin.mue.ui.scale.TransportScaleDiscovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Tout ce dont le module balance a besoin, en un seul endroit.
 *
 * [AppContainer] y gagne **une** propriété pour tout le module, exactement comme le minuteur, la
 * synchronisation et le module alimentaire avant lui, afin que le registre de pilotes, la couche
 * de liaison Bluetooth et les écrans d'appairage encore à écrire puissent s'y brancher sans que le
 * conteneur livré ait à rebouger (PRD_SCALE 21.2, PRD 20.2).
 *
 * Il contient la persistance, le registre de pilotes et la source de session Bluetooth. Chacun est
 * une propriété `by lazy` de plus ici et aucune ligne ailleurs, ce qui est exactement ce que
 * FR-SCALE-030 exige du coût d'un nouveau modèle de balance : un fichier de pilote et une ligne
 * dans `MueScaleDrivers`, sans qu'aucun écran, aucun état d'interface ni aucune table ne bouge.
 *
 * Paresseux comme tout ce que [AppContainer] tient : le repository ouvre la base de données, et un
 * démarrage à froid qui n'atteint jamais l'écran des balances ne doit pas le payer.
 *
 * @property applicationContext Le contexte applicatif, dont la couche Bluetooth de PRD_SCALE 21.2
 *   se sert pour son `BluetoothManager`, ses vérifications de permission et le drapeau de build qui
 *   décide du pilote fictif. La persistance, elle, n'en a aucun usage.
 */
class ScaleContainer(
    val applicationContext: Context,
    private val database: MueDatabase,
) {
    /**
     * Les balances appairées (PRD_SCALE 9.3).
     *
     * Sans `SyncOutbox`, contrairement à toutes les autres propriétés de repository des
     * conteneurs de ce dépôt : PRD_SCALE 22 tient cette collection hors du fil, et
     * `RoomScaleRepository` n'accepte donc pas d'outbox du tout.
     */
    val scaleRepository: ScaleRepository by lazy { RoomScaleRepository(database.scaleDao()) }

    /**
     * Les pilotes livrés avec cette variante de build (PRD_SCALE 9.2, 15).
     *
     * Le drapeau vient de `ApplicationInfo` et non de `BuildConfig.DEBUG`, qui n'existe pas : le
     * module Android est compilé avec `buildFeatures { buildConfig = false }`, et l'activer pour ce
     * seul besoin imposerait de modifier le fichier de build partagé. C'est le contrat explicite de
     * [MueScaleDrivers.forBuild], qui garde du même coup le paquet des pilotes entièrement dépourvu
     * de dépendance Android, donc pilotable en JVM pure (PRD_SCALE 21.3).
     *
     * Conséquence : le pilote fictif rend l'application utilisable sur émulateur, où le BLE réel est
     * impossible, et disparaît d'une version publiée.
     */
    val scaleDrivers: ScaleDriverRegistry by lazy {
        val debuggable =
            (applicationContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        MueScaleDrivers.forBuild(debuggable)
    }

    /**
     * La liaison Bluetooth réelle : scan, connexion, abonnement, écritures sérialisées.
     *
     * `internal` parce que ce n'est pas un contrat de domaine : l'écran `Entry` n'y touche jamais,
     * il observe [scaleSessionSource]. Elle est exposée ici pour un seul autre usage, le **flux
     * d'appairage** — `Profile > Scales` a besoin d'un scan qui montre aussi les appareils dont
     * l'adresse est inconnue, ce que la session de mesure ne fait pas (FR-SCALE-011, FR-SCALE-001).
     *
     * Une seule instance, partagée : `BluetoothLeScanner` accepte plusieurs rappels simultanés, et
     * deux transports parallèles ne feraient que dupliquer la même absence d'état.
     *
     * L'adaptation vers [ScaleDiscovery] — comptage de références, démarrage et arrêt appariés —
     * reste écrite dans `ui/scale` ; c'est [scaleDiscovery] qui l'instancie, une seule fois.
     */
    internal val scaleTransport: ScaleTransport by lazy { AndroidScaleTransport(applicationContext) }

    /**
     * L'unique scan partagé du processus (FR-SCALE-011, 013).
     *
     * **Une seule instance, et c'est une contrainte de correction.** `TransportScaleDiscovery`
     * compte les ouvertures pour que le scan survive à la transition entre `Profile > Scales` et le
     * flux d'appairage, que `AnimatedContent` compose ensemble le temps d'une animation. Deux
     * adaptateurs compteraient chacun les leurs et ouvriraient deux scans BLE simultanés, ce qui
     * reviendrait à écrire le comptage pour ne pas s'en servir : les deux fabriques de ViewModel de
     * `ui/scale` lisent donc cette propriété-ci, et ne construisent jamais la leur.
     *
     * **Pourquoi `di` nomme un type de `ui` plutôt que l'inverse.** [ScaleDiscovery] est le port
     * que les écrans de balance définissent pour le scan, et `TransportScaleDiscovery` en est
     * l'adaptateur. Le mettre dans `data/scale/ble` aurait forcé la couche data à importer la
     * couche interface — une inversion qui n'existe nulle part ailleurs dans ce dépôt, alors que
     * `ui` importe déjà `data` (`ScaleMatching`, `ScaleTransport`). Ici, au contraire, la
     * dépendance est celle d'une **racine de composition** : sans framework de DI (contrat §2),
     * `AppContainer` est l'endroit dont le métier est précisément de connaître toutes les couches
     * pour les assembler. Une racine de composition qui n'aurait pas le droit de nommer ce qu'elle
     * assemble n'en serait pas une.
     *
     * `Main.immediate`, comme [scaleSessionSource] : le compte d'ouvertures de l'adaptateur n'a
     * aucune synchronisation et n'en a pas besoin tant qu'il reste confiné au fil d'où les écrans
     * appellent `start()` et `stop()`. `immediate` évite en prime un saut de dispatch entre le
     * `start()` d'un écran et l'ouverture du scan.
     *
     * Paresseux : construit à la première fabrique de ViewModel qui en a besoin, c'est-à-dire à
     * l'ouverture de `Profile > Scales`, jamais au démarrage de l'application.
     */
    internal val scaleDiscovery: ScaleDiscovery by lazy {
        TransportScaleDiscovery(
            scaleTransport,
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
    }

    /**
     * La source d'une pesée reçue, telle que l'écran `Entry` l'observe (PRD_SCALE 21.2).
     *
     * Unique point de contact entre l'interface et le Bluetooth : l'écran observe
     * [ScaleSessionSource.state] et appelle quatre méthodes, et rien du scan, du GATT, des
     * acquittements ni des délais ne traverse cette frontière.
     *
     * **La portée est `Dispatchers.Main.immediate`, et c'est une contrainte de correction, pas une
     * commodité.** La machine à états n'est pas synchronisée : son `sessionId`, ses travaux et son
     * état ne sont cohérents que confinés à un seul fil. Les callbacks Bluetooth, eux, arrivent
     * d'où la pile veut ; ils traversent les files et les continuations de la liaison, jamais cet
     * état. `immediate` évite en prime un saut de dispatch entre l'appel de `start()` par l'écran
     * et l'ouverture de la session.
     *
     * `SupervisorJob` pour qu'une session qui échoue ne stérilise pas la portée : l'application vit
     * plus longtemps que n'importe quelle pesée.
     *
     * Paresseux comme le reste : un démarrage à froid qui n'atteint jamais `Entry` ne paie ni la
     * base, ni la lecture des balances enregistrées.
     */
    val scaleSessionSource: ScaleSessionSource by lazy {
        BleScaleSessionSource(
            transport = scaleTransport,
            scales = scaleRepository,
            drivers = scaleDrivers,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            log = AndroidScaleLog,
        )
    }
}
