package fr.kristenjestin.mue.ui.scale

import fr.kristenjestin.mue.domain.model.ScaleAdvertisement
import kotlinx.coroutines.flow.Flow

/**
 * Ce que les écrans de balance attendent d'un scan BLE, et rien de plus (FR-SCALE-011, 013).
 *
 * **Ce n'est pas un second contrat de domaine.** Le seul point de couplage entre l'interface et le
 * Bluetooth reste `ScaleSessionSource`, qui gouverne la pesée. Cette interface-ci existe parce que
 * `Profile > Scales` et le flux d'appairage ont besoin d'autre chose qu'une session de pesée : voir
 * les appareils **dont l'adresse est inconnue**, y compris ceux qu'aucun pilote ne reconnaît. Elle
 * est la plus petite frontière qui rende ces deux écrans pilotables par un double en mémoire, et
 * `TransportScaleDiscovery` la relie au scan réel de la couche de liaison.
 *
 * Elle est délibérément **dépourvue de toute dépendance Android**, comme [ScaleAdvertisement]
 * elle-même, de sorte que tout ce qui la consomme se pilote en JVM pure (PRD_SCALE 21.3).
 *
 * **Le scan ne tourne qu'au premier plan** (PRD_SCALE 3.7) : rien ici ne permet de démarrer une
 * recherche persistante, et [stop] est appelé dès qu'un écran cesse d'être visible.
 *
 * **Lire cette interface ne demande aucune permission.** [start] n'est appelé qu'une fois
 * `ScalePermissionsState.canScan` vérifié par l'écran ; un appel sans permission ne doit rien
 * émettre plutôt que lever, parce qu'une permission peut être révoquée pendant qu'un scan tourne.
 */
internal interface ScaleDiscovery {

    /**
     * Les annonces reçues pendant qu'un scan tourne.
     *
     * Flux **chaud** : s'y abonner ne démarre rien, [start] le fait. Un même appareil peut être
     * annoncé plusieurs fois par seconde ; la déduplication par adresse appartient au consommateur,
     * qui est le seul à savoir ce qu'il fait d'un doublon.
     */
    val advertisements: Flow<ScaleAdvertisement>

    /**
     * Ouvre le scan.
     *
     * **[start] et [stop] sont appariés et l'implémentation les compte.** Le scanner tourne tant
     * qu'au moins un appelant l'a ouvert. Ce n'est pas une précaution gratuite : `Profile > Scales`
     * repère les balances à portée pendant que l'écran est ouvert, le flux d'appairage scanne
     * aussi, et `AnimatedContent` compose les deux en même temps le temps d'une transition. Sans
     * comptage, l'écran qui s'en va couperait le scan que l'écran qui arrive vient d'ouvrir, et le
     * symptôme serait une liste vide pendant trente secondes sans que rien ne l'explique.
     */
    fun start()

    /** Referme ce que [start] a ouvert. Rien ne survit à un passage en arrière-plan. */
    fun stop()
}
