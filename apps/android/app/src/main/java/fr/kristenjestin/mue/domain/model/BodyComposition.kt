package fr.kristenjestin.mue.domain.model

import java.time.LocalDate

/**
 * L'estimation de composition corporelle attachée à une pesée (PRD_SCALE 12.3, 13).
 *
 * **Un enfant facultatif, jamais une entité autonome** (BR-SCALE-006). [date] est à la fois son
 * identité et la clé étrangère vers la mesure parente : il ne peut donc exister au plus qu'une
 * composition par jour, et elle disparaît avec son poids (PRD_SCALE 21.1, cascade).
 *
 * **Un instantané, pas une vue** (FR-BODY-004, BR-SCALE-014). Les quatre entrées de calcul —
 * [inputWeightCg], [inputHeightCm], [inputAgeYears], [inputSex] — sont copiées ici au moment du
 * calcul, avec [formulaId] et [formulaVersion]. Corriger sa taille ou sa date de naissance des
 * mois plus tard ne réécrit donc aucune valeur passée. C'est aussi ce qui rend possible le
 * recalcul complet de l'historique lors d'un changement de formule, par une migration explicite
 * et versionnée, plutôt qu'une perte de données.
 *
 * **Ce qui n'est pas ici : l'impédance.** Elle est portée par [Measurement.impedanceOhm]
 * (FR-BODY-004, BR-SCALE-008). Une impédance parfaitement valide est mesurée dès la première
 * pesée, souvent bien avant que l'utilisateur ait renseigné son sexe ; la ranger dans la
 * composition la ferait disparaître exactement dans ce cas, et des semaines de mesures seraient
 * définitivement perdues le jour où le profil devient complet — alors que c'est précisément ce
 * jour-là que le calcul rétroactif de FR-BODY-006 en a besoin.
 *
 * **Pourquoi des entiers.** PRD_SCALE 21.1 impose de ne stocker aucun flottant : la précision
 * décimale est conservée pendant le calcul, l'arrondi n'est appliqué qu'une seule fois, vers les
 * unités entières ci-dessous. Deux implémentations — Kotlin et TypeScript (PRD_SCALE 13.2) —
 * doivent produire les mêmes entiers pour le même payload, ce qu'un `Double` sérialisé ne
 * garantirait pas.
 *
 * @property date Date de la mesure parente. Identité de la composition (PRD_SCALE 21.1).
 * @property formulaId Jeu de formules employé, `mue-foot-to-foot-v1` pour cette spécification.
 * @property formulaVersion Version entière du jeu de formules, `1` ici (PRD_SCALE 13.2).
 * @property inputWeightCg Poids exact utilisé, en centièmes de kilogramme. BR-SCALE-015 impose
 *   qu'il soit toujours égal au poids de la mesure parente.
 * @property inputHeightCm Taille du profil au moment du calcul.
 * @property inputAgeYears Âge entier **à la date de la mesure**, jamais à la date du calcul
 *   (FR-BODY-006).
 * @property inputSex Terme `sexCoefficient` de l'équation (PRD_SCALE 13.2).
 * @property bodyFatDeciPercent Masse grasse en dixièmes de pour cent.
 * @property fatFreeMassCg Masse maigre en centièmes de kilogramme, même unité que le poids.
 * @property bodyWaterDeciPercent Eau corporelle en dixièmes de pour cent.
 * @property restingEnergyKcal Dépense énergétique au repos, arrondie à la kilocalorie.
 */
data class BodyComposition(
    val date: LocalDate,
    val formulaId: String,
    val formulaVersion: Int,
    val inputWeightCg: Int,
    val inputHeightCm: Int,
    val inputAgeYears: Int,
    val inputSex: Sex,
    val bodyFatDeciPercent: Int,
    val fatFreeMassCg: Int,
    val bodyWaterDeciPercent: Int,
    val restingEnergyKcal: Int,
) {
    /**
     * Masse grasse en pour cent, à la décimale près (PRD_SCALE FR-BODY-003 : une seule décimale).
     * Dérivée à la lecture, jamais stockée : le seul chiffre qui fait foi est l'entier.
     */
    val bodyFatPercent: Double get() = bodyFatDeciPercent / 10.0

    /** Masse maigre en kilogrammes, dérivée pour l'affichage. Libellé anglais `Fat-free mass`. */
    val fatFreeMassKg: Double get() = fatFreeMassCg / 100.0

    /** Eau corporelle en pour cent, dérivée pour l'affichage. Libellé anglais `Body water`. */
    val bodyWaterPercent: Double get() = bodyWaterDeciPercent / 10.0
}
