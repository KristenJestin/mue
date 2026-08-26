package fr.kristenjestin.mue.ui.progress

import fr.kristenjestin.mue.domain.logic.BodyCompositionFormula
import fr.kristenjestin.mue.domain.logic.BodyCompositionResult
import fr.kristenjestin.mue.domain.logic.RetroactiveBodyComposition
import fr.kristenjestin.mue.domain.model.BodyComposition
import fr.kristenjestin.mue.domain.model.Measurement
import fr.kristenjestin.mue.domain.model.UserProfile
import java.time.LocalDate

/**
 * Ce que l'écran `Progress` a besoin de savoir pour poser — ou taire — la composition corporelle
 * (PRD_SCALE FR-BODY-005, 18.1, 18.4).
 *
 * **Pourquoi un état à part et pas quatre valeurs dans [ProgressUiState].** La section n'est pas
 * seulement quatre nombres : elle décide aussi d'exister, d'expliquer ce qui manque, de dire qu'un
 * profil sort du domaine, et de proposer de remplir le passé. Ces quatre décisions ont chacune
 * leur condition dans le PRD et elles interagissent ; les éparpiller dans l'état de l'écran les
 * rendrait impossibles à lire ensemble, et donc impossibles à tenir.
 *
 * **Aucune catégorie, aucun seuil.** Rien ici ne classe une valeur. FR-BODY-003 n'autorise qu'une
 * seule mise en perspective — l'écart avec la composition précédente — et c'est précisément
 * pourquoi cet état porte [latest] **et** [previous] plutôt qu'une valeur et un rang.
 *
 * @property latest la composition de la date la plus récente **de la période sélectionnée**,
 *   `null` quand la période n'en contient aucune.
 * @property previous la composition immédiatement précédente **dans la même période**, `null`
 *   quand il n'y en a pas de seconde. FR-BODY-005 impose alors un écart à `—` plutôt qu'un écart
 *   pris hors période.
 * @property hasHistory `true` dès qu'une composition existe **où que ce soit** dans l'historique,
 *   même produite par une balance depuis oubliée (PRD_SCALE 18.1, BR-SCALE-010).
 * @property missingProfileInputs ce qui manque au profil courant, vide quand il est complet
 *   (FR-BODY-001).
 * @property isOutOfDomain profil complet, mais une pesée d'aujourd'hui serait refusée par le
 *   domaine d'âge ou d'IMC de FR-BODY-001.
 * @property hasPairedScale au moins une balance associée. PRD_SCALE 18.4 conditionne à cela
 *   l'explication du profil incomplet : sans balance, réclamer une taille pour une estimation que
 *   rien ne peut produire serait une demande gratuite.
 * @property retroactiveCount combien de pesées passées un profil désormais complet permettrait de
 *   compléter (FR-BODY-006), sur **tout** l'historique et non sur la période.
 */
data class BodyCompositionUiState(
    val latest: BodyComposition?,
    val previous: BodyComposition?,
    val hasHistory: Boolean,
    val missingProfileInputs: Set<BodyCompositionResult.ProfileInput>,
    val isOutOfDomain: Boolean,
    val hasPairedScale: Boolean,
    val retroactiveCount: Int,
) {

    /**
     * PRD_SCALE 18.4 : ce qui manque est expliqué là où une balance peut y remédier, et l'accès à
     * `Profile` est proposé — jamais imposé, jamais bloquant.
     */
    val showIncompleteProfile: Boolean
        get() = hasPairedScale && missingProfileInputs.isNotEmpty()

    /**
     * FR-BODY-006 : la proposition n'apparaît qu'avec au moins une pesée à compléter. Un `0` ne
     * s'affiche pas, il se tait.
     */
    val showRetroactiveProposal: Boolean get() = retroactiveCount > 0

    /**
     * FR-BODY-005 : quatre cartes dès qu'une composition existe dans l'historique. Une période qui
     * n'en contient aucune les montre à `—` — elle **n'emprunte pas** la dernière connue.
     */
    val showCards: Boolean get() = hasHistory

    /**
     * La section est-elle à l'écran du tout (PRD_SCALE 18.1) ?
     *
     * Trois portes, et seulement trois. L'historique de composition, qui est la règle. Le couple
     * « balance associée + profil incomplet », qui est l'exception que FR-BODY-005 écrit
     * explicitement. Et la proposition rétroactive, qui **doit** en être une : le cas nominal de
     * FR-BODY-006 est justement celui d'un utilisateur qui n'a encore aucune composition — il
     * s'est pesé pendant des semaines sans avoir renseigné son sexe — et qui vient de compléter
     * son profil. À cet instant précis, l'historique de composition est vide, le profil n'est plus
     * incomplet, et une lecture littérale des deux premières portes ferait disparaître l'offre au
     * moment exact où elle a un sens. La proposition serait alors inatteignable.
     */
    val isVisible: Boolean
        get() = hasHistory || showIncompleteProfile || showRetroactiveProposal

    /**
     * FR-BODY-001 et PRD_SCALE 18.4 : le profil est complet mais sort du domaine de l'équation.
     *
     * Conditionné à [isVisible] et non l'inverse : c'est un commentaire sur une section déjà
     * posée, pas un motif de la poser. Il ne montre **ni l'IMC ni l'âge** et ne suggère aucune
     * modification des données — une limite de validité d'équation n'est pas un jugement.
     */
    val showUnavailableForProfile: Boolean
        get() = isVisible && missingProfileInputs.isEmpty() && isOutOfDomain

    companion object {

        /** Rien à montrer : ni historique, ni balance, ni passé à compléter. */
        val ABSENT: BodyCompositionUiState = BodyCompositionUiState(
            latest = null,
            previous = null,
            hasHistory = false,
            missingProfileInputs = emptySet(),
            isOutOfDomain = false,
            hasPairedScale = false,
            retroactiveCount = 0,
        )

        /**
         * Tout ce que la section décide, en une fonction pure — donc testable en JVM sans écran,
         * sans base et sans horloge (PRD_SCALE 21.3).
         *
         * @param allMeasurements l'historique complet ; sert à [hasHistory], à [isOutOfDomain] et
         *   au compte rétroactif, dont aucun n'appartient à la période.
         * @param inPeriod les mesures de la période sélectionnée, dans n'importe quel ordre.
         * @param today la date du jour, apportée par l'appelant : rien ici ne lit d'horloge.
         */
        fun from(
            allMeasurements: List<Measurement>,
            inPeriod: List<Measurement>,
            profile: UserProfile,
            today: LocalDate,
            hasPairedScale: Boolean,
        ): BodyCompositionUiState {
            /*
             * Les pesées sans composition — donc toutes celles sans impédance exploitable, et
             * celles dont le profil d'alors ne permettait pas le calcul — sont écartées avant le
             * tri (FR-BODY-005). C'est ce qui fait qu'une pesée manuelle d'aujourd'hui n'efface
             * pas les cartes : elle n'entre simplement pas dans le choix.
             */
            val compositions = inPeriod
                .mapNotNull { it.bodyComposition }
                .sortedBy { it.date }

            val missing = missingProfileInputsOf(profile)

            return BodyCompositionUiState(
                latest = compositions.lastOrNull(),
                previous = compositions.getOrNull(compositions.lastIndex - 1),
                hasHistory = allMeasurements.any { it.bodyComposition != null },
                missingProfileInputs = missing,
                isOutOfDomain = missing.isEmpty() &&
                    isOutOfDomain(allMeasurements, profile, today),
                hasPairedScale = hasPairedScale,
                retroactiveCount = RetroactiveBodyComposition.count(allMeasurements, profile),
            )
        }

        /**
         * Les entrées de FR-BODY-001 qui manquent au profil, dans le vocabulaire du domaine.
         *
         * La taille est jugée par [BodyCompositionFormula.isHeightUsable] plutôt que par un simple
         * test de nullité : une taille hors du domaine de saisie de PRD FR-PROFILE-001 ne peut pas
         * venir de l'écran `Profile`, et la traiter comme absente donne à l'utilisateur le seul
         * message sur lequel il peut agir.
         */
        fun missingProfileInputsOf(profile: UserProfile): Set<BodyCompositionResult.ProfileInput> =
            buildSet {
                if (!BodyCompositionFormula.isHeightUsable(profile.heightCm)) {
                    add(BodyCompositionResult.ProfileInput.HEIGHT)
                }
                if (profile.birthDate == null) {
                    add(BodyCompositionResult.ProfileInput.BIRTH_DATE)
                }
                if (profile.sex == null) {
                    add(BodyCompositionResult.ProfileInput.SEX)
                }
            }

        /**
         * Une pesée d'aujourd'hui serait-elle refusée par le domaine de FR-BODY-001 ?
         *
         * **La question porte sur le profil, pas sur une mesure passée**, parce que c'est ce que
         * `Body composition estimates are not available for this profile` affirme. L'âge est donc
         * celui d'aujourd'hui, et le poids retenu est le dernier enregistré — la meilleure
         * approximation disponible de ce que la balance lirait tout à l'heure. Sans aucune mesure,
         * la question n'a pas de réponse et la réponse est `false` : mieux vaut ne rien dire que
         * de décréter un profil hors domaine sur un poids inventé.
         *
         * Les seuils ne sont pas réécrits ici : [BodyCompositionFormula] les porte, avec l'IMC non
         * arrondi que la porte de domaine exige.
         */
        private fun isOutOfDomain(
            allMeasurements: List<Measurement>,
            profile: UserProfile,
            today: LocalDate,
        ): Boolean {
            val height = profile.heightCm ?: return false
            val age = profile.ageOn(today) ?: return false
            if (!BodyCompositionFormula.isAgeInDomain(age)) return true

            val latestWeight = allMeasurements.maxByOrNull { it.date }?.weight ?: return false
            val bmi = BodyCompositionFormula.bmiOrNull(latestWeight.hundredthsKg, height)
                ?: return false
            return !BodyCompositionFormula.isBmiInDomain(bmi)
        }
    }
}
