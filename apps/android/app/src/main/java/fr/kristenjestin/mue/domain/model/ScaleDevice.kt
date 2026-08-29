package fr.kristenjestin.mue.domain.model

import java.time.Instant

/**
 * Une balance appairée par l'utilisateur (PRD_SCALE 9.3).
 *
 * C'est l'un des trois objets que PRD_SCALE 9.1 interdit de confondre : le **pilote** est du code
 * livré avec l'application, la **mesure reçue** est un événement de quelques secondes, et cet
 * objet-ci est la donnée locale d'*un* appareil physique, qui survit jusqu'à l'oubli explicite.
 *
 * **Pourquoi [id] n'est jamais dérivé de l'adresse.** L'adresse Bluetooth de la balance de
 * référence est `FF:10:00:1F:52:C3` ; son premier octet la désigne comme une *adresse statique
 * aléatoire* (PRD_SCALE 10.1). Elle n'est pas gravée dans le matériel et peut être régénérée au
 * redémarrage de l'appareil — donc potentiellement à chaque changement de piles. Une balance
 * identifiée par son adresse disparaîtrait ce jour-là, et l'application n'aurait aucun moyen
 * d'expliquer pourquoi : de son point de vue, ce serait un appareil jamais vu. [id] est donc un
 * UUID tiré par Mue à l'appairage et rien d'autre ; [address] n'est qu'un **indice** de
 * localisation, mis à jour à chaque contact. C'est ce qui permet au rattachement proposé de
 * FR-SCALE-001 de changer l'adresse en conservant l'identifiant, le nom donné et l'historique.
 *
 * **[id] n'est jamais affiché** (PRD_SCALE 9.3). Il n'existe que pour que renommer une balance, ou
 * la voir changer d'adresse, ne casse aucune référence — en particulier
 * [Measurement.sourceScaleId], qui pointe ici en `ON DELETE SET NULL` afin qu'oublier une balance
 * ne supprime aucune mesure (BR-SCALE-010).
 *
 * Cette table est **purement locale** : elle n'est ni synchronisée ni exposée par MCP
 * (PRD_SCALE 22), à la manière du catalogue d'aliments.
 *
 * @property id UUID stable généré à l'appairage. Jamais dérivé de [address], jamais montré.
 * @property driverId Identifiant du [ScaleDriver] reconnu à l'appairage.
 * @property address Adresse Bluetooth connue au dernier contact. Indice, pas identité.
 * @property advertisedName Nom annoncé au dernier contact ; second terme de l'identité composite
 *   de FR-SCALE-001.
 * @property displayName Nom donné par l'utilisateur ; par défaut, le nom du modèle du pilote.
 * @property lastSeenAt Dernier contact réussi, `null` tant qu'aucun n'a eu lieu depuis l'appairage.
 * @property createdAt Instant de l'appairage.
 */
data class ScaleDevice(
    val id: String,
    val driverId: String,
    val address: String,
    val advertisedName: String,
    val displayName: String,
    val lastSeenAt: Instant?,
    val createdAt: Instant,
)
