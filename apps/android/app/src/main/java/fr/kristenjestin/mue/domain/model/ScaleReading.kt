package fr.kristenjestin.mue.domain.model

import java.time.Instant

/**
 * Ce qui sort de la rencontre entre un pilote et une balance appairée (PRD_SCALE 9.4).
 *
 * Troisième et dernier des objets distingués par PRD_SCALE 9.1 : un **événement**, dont la durée
 * de vie se compte en secondes.
 *
 * **Pourquoi ce type n'est jamais persisté tel quel** (PRD_SCALE 9.4). Une lecture n'est pas une
 * donnée de santé, c'est une observation en cours. Elle alimente l'état de l'écran `Entry`
 * (PRD_SCALE 11) et, **si et seulement si l'utilisateur valide**, elle devient une [Measurement]
 * portant éventuellement une [BodyComposition]. Écrire une lecture en base court-circuiterait ce
 * consentement, et surtout ferait entrer dans l'historique des valeurs instables
 * (BR-SCALE-001) ou hors bornes (BR-SCALE-002) que le domaine refuse. Corollaire pratique :
 * [weightHundredthsKg] est un `Int` nu et non un [Weight], parce qu'une trame peut parfaitement
 * transporter une valeur que [Weight] rejetterait ; la conversion, et donc la validation, a lieu
 * au moment de la promotion en [Measurement].
 *
 * **Pourquoi [sessionId].** La liaison BLE est asynchrone et une notification peut arriver après
 * l'enregistrement, l'annulation, une retouche manuelle ou le début d'une autre pesée. Chaque
 * lecture porte donc l'identifiant de la tentative qui l'a produite, et la machine à états le
 * vérifie avant de modifier quoi que ce soit (PRD_SCALE 21.2). C'est ce qui empêche une trame
 * d'impédance tardive de compléter la mauvaise pesée (BR-SCALE-012).
 *
 * @property sessionId UUID de la tentative de pesée en mémoire. Ne survit pas au processus.
 * @property weightHundredthsKg Poids en centièmes de kilogramme, l'unité de vérité de tout Mue.
 *   Non validé : voir ci-dessus.
 * @property isStable Une lecture instable est affichée mais n'est jamais enregistrable
 *   (BR-SCALE-001).
 * @property impedanceOhm Impédance corporelle totale, `null` quand la balance a signalé une mesure
 *   impossible — pieds nus insuffisants, contact partiel (FR-BODY-002, BR-SCALE-005). Une absence,
 *   jamais une valeur.
 * @property receivedAt Horloge du téléphone à la réception, pas une horloge de l'appareil.
 * @property scaleId [ScaleDevice.id] de la balance émettrice.
 */
data class ScaleReading(
    val sessionId: String,
    val weightHundredthsKg: Int,
    val isStable: Boolean,
    val impedanceOhm: Int?,
    val receivedAt: Instant,
    val scaleId: String,
)
