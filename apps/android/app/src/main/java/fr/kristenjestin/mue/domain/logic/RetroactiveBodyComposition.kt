package fr.kristenjestin.mue.domain.logic

import fr.kristenjestin.mue.domain.model.Measurement
import fr.kristenjestin.mue.domain.model.UserProfile

/**
 * Le calcul rétroactif de FR-BODY-006 : quelles pesées déjà enregistrées un profil désormais
 * complet permettrait de compléter, et ce qu'il faudrait écrire pour le faire.
 *
 * **Pourquoi une fonction pure et non une méthode de ViewModel.** Ce qui est décidé ici crée des
 * données de santé pour des dates passées. Trois règles de FR-BODY-006 s'y jouent — l'âge de
 * *chaque* date, l'interdiction d'écraser une composition existante, et le compte annoncé à
 * l'utilisateur — et aucune des trois ne se teste en JVM pure depuis un ViewModel branché sur des
 * flux. Elles sont donc énoncées une fois, ici, où un test les rejoue sans Android.
 *
 * **Le compte et l'écriture viennent de la même fonction.** [count] n'est pas une heuristique
 * séparée mais la taille exacte de [plan] : PRD_SCALE 18.4 promet à l'utilisateur un nombre de
 * pesées, et une proposition qui annoncerait `4` avant d'en écrire `3` aurait menti sur une donnée
 * de santé. Recompter coûte une poignée de multiplications décimales sur un historique qui se
 * mesure en centaines de lignes ; l'écart entre les deux chiffres, lui, ne se rattraperait pas.
 *
 * **Ce que cette fonction n'a pas à savoir.** Ni l'impédance exploitable, ni le domaine d'âge, ni
 * le domaine d'IMC, ni les contrôles de plausibilité : [BodyCompositionCalculator] les applique
 * déjà, dans l'ordre où FR-BODY-001 les pose, et ne rend une composition que lorsque les six
 * portes sont franchies. Les redire ici, c'est se condamner à les faire diverger.
 *
 * @see BodyCompositionCalculator.calculate pour l'ordre des portes et les motifs de refus.
 */
object RetroactiveBodyComposition {

    /**
     * Les mesures que [profile] permet de compléter, chacune rendue **avec** la composition qu'il
     * faudrait lui attacher, dans l'ordre des dates.
     *
     * Le résultat est directement écrivable : chaque élément est la mesure d'origine copiée, donc
     * son poids, sa provenance, sa balance émettrice et son impédance sont conservés tels quels
     * (BR-SCALE-013, BR-SCALE-008). Il suffit de le repasser à `MeasurementRepository.save`, dont
     * BR-SCALE-007 garantit que poids et composition s'écrivent dans une seule transaction.
     *
     * **Une composition déjà enregistrée n'est jamais écrasée** (FR-BODY-006) : les mesures qui en
     * portent une sont écartées avant tout calcul. C'est ce qui rend l'opération répétable sans
     * dommage — accepter deux fois la proposition ne réécrit rien, parce que la première fois a
     * vidé la liste.
     *
     * **L'âge est celui de chaque date, pas celui d'aujourd'hui.**
     * [BodyCompositionCalculator.calculate] le reconstitue avec `UserProfile.ageOn(date)` de la
     * mesure. C'est la clause la plus facile à perdre de FR-BODY-006 et la plus difficile à voir :
     * employer l'âge du jour du calcul décale la masse maigre de `0,136 kg` par année d'écart,
     * silencieusement, sur des dizaines de lignes d'un coup.
     *
     * **La taille et le sexe, eux, sont ceux d'aujourd'hui.** Mue ne tient aucun historique de
     * profil et cette approximation est assumée par FR-BODY-006 — à la condition expresse qu'elle
     * soit visible dans l'explication qui accompagne la proposition
     * (`ScaleMessages.RETROACTIVE_EXPLANATION`).
     *
     * @param measurements l'historique complet, dans n'importe quel ordre.
     * @param profile le profil **au moment du calcul**.
     */
    fun plan(measurements: List<Measurement>, profile: UserProfile): List<Measurement> =
        measurements
            .asSequence()
            .filter { it.bodyComposition == null }
            .mapNotNull { measurement ->
                BodyCompositionCalculator.calculate(measurement, profile)
                    .compositionOrNull
                    ?.let { measurement.copy(bodyComposition = it) }
            }
            .sortedBy { it.date }
            .toList()

    /**
     * Combien de pesées passées peuvent être complétées (PRD_SCALE 18.4).
     *
     * `0` signifie que la proposition ne s'affiche pas du tout — pas qu'elle s'affiche avec un
     * zéro.
     */
    fun count(measurements: List<Measurement>, profile: UserProfile): Int =
        plan(measurements, profile).size
}
