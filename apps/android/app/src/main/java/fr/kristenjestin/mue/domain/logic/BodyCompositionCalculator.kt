package fr.kristenjestin.mue.domain.logic

import fr.kristenjestin.mue.domain.model.BodyComposition
import fr.kristenjestin.mue.domain.model.Measurement
import fr.kristenjestin.mue.domain.model.Sex
import fr.kristenjestin.mue.domain.model.UserProfile
import fr.kristenjestin.mue.domain.model.Weight
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Ce qu'a donné une tentative de calcul de composition corporelle.
 *
 * **Pourquoi pas un `BodyComposition?`.** Une absence muette obligerait chaque appelant à refaire
 * lui-même le diagnostic pour écrire les messages de PRD_SCALE 18.4 — et à le refaire avec ses
 * propres seuils, qui dériveraient. Or les six issues appellent six comportements différents :
 * enregistrer la composition ; nommer l'entrée de profil manquante et rappeler que les pesées
 * déjà enregistrées pourront être complétées (FR-BODY-006) ; expliquer sobrement que les
 * estimations ne sont pas disponibles pour ce profil, **sans afficher l'IMC ni l'âge**
 * (FR-BODY-001) ; conseiller les pieds nus, mais uniquement quand la balance a signalé une
 * impédance non mesurable (PRD_SCALE 18.3) ; journaliser une aberration sans rien montrer.
 * Un `null` unique les confondrait toutes.
 *
 * Dans tous les cas de refus, **le poids reste valide et l'impédance exploitable reste enregistrée
 * sur la mesure** (FR-BODY-004, BR-SCALE-008) : ce type ne décide que du sort de la composition.
 *
 * [wireOutcome] et les `wireValue` voisins sont la forme partagée avec les vecteurs de test
 * versionnés de `src/test/resources/bodycomposition/mue-foot-to-foot-v1.json`, que
 * l'implémentation TypeScript de PRD_SCALE 13.2 rejouera telle quelle.
 */
sealed interface BodyCompositionResult {

    /** Nom stable de l'issue, partagé avec les vecteurs de test versionnés. */
    val wireOutcome: String

    /** La composition a été calculée et peut être écrite avec sa mesure. */
    data class Calculated(val composition: BodyComposition) : BodyCompositionResult {
        override val wireOutcome: String get() = OUTCOME_CALCULATED
    }

    /**
     * Aucune composition. Le poids s'enregistre normalement et **aucune erreur n'est affichée**
     * (FR-BODY-001).
     */
    sealed interface Refused : BodyCompositionResult

    /**
     * Il manque au moins une des trois entrées de profil (FR-BODY-001, PRD_SCALE 18.4).
     *
     * [missing] dit **lesquelles**, dans l'ordre de déclaration de [ProfileInput], pour que le
     * message nomme l'élément à renseigner au lieu d'inviter à « compléter le profil ».
     */
    data class MissingProfileInput(val missing: Set<ProfileInput>) : Refused {
        override val wireOutcome: String get() = OUTCOME_MISSING_PROFILE_INPUT
    }

    /**
     * L'âge **à la date de la mesure** sort de [BodyCompositionFormula.AGE_RANGE_YEARS]
     * (FR-BODY-001).
     *
     * [ageYears] est conservé pour les tests et le journal technique ; l'écran, lui, n'affiche pas
     * l'âge — FR-BODY-001 refuse qu'une limite de validité d'équation se lise comme un jugement.
     */
    data class AgeOutOfDomain(val ageYears: Int) : Refused {
        override val wireOutcome: String get() = OUTCOME_AGE_OUT_OF_DOMAIN
    }

    /**
     * L'IMC sort du domaine `15.8–43.1` dans lequel l'équation a été développée (FR-BODY-001).
     *
     * [bmi] est la valeur exacte, non arrondie, qui a servi à la décision — `null` seulement dans
     * le cas où la taille ne permet pas de la calculer, inatteignable depuis
     * [BodyCompositionCalculator.calculate] qui valide la taille avant. Comme [ageYears] plus
     * haut, elle sert au diagnostic et jamais à l'affichage.
     */
    data class BmiOutOfDomain(val bmi: BigDecimal?) : Refused {
        override val wireOutcome: String get() = OUTCOME_BMI_OUT_OF_DOMAIN
    }

    /**
     * Impédance absente, nulle ou négative (BR-SCALE-005, FR-BODY-002).
     *
     * C'est aussi l'issue de toute mesure saisie à la main, qui n'en porte simplement pas. Ce
     * motif ne justifie donc **pas** à lui seul le conseil « pieds nus » de PRD_SCALE 18.3 :
     * l'écran ne le donne que lorsque le pilote a explicitement signalé une mesure impossible,
     * information qui vit dans la session de pesée, pas ici.
     */
    data class ImpedanceUnusable(val impedanceOhm: Int?) : Refused {
        override val wireOutcome: String get() = OUTCOME_IMPEDANCE_UNUSABLE
    }

    /**
     * Le calcul a abouti mais son résultat est physiquement impossible (PRD_SCALE 13.2).
     *
     * C'est le filet de sécurité d'une impédance aberrante que rien n'interdisait en amont : une
     * impédance très basse gonfle le terme `taille² / impédance` jusqu'à une masse maigre
     * supérieure au poids. **Rien n'est ramené dans les bornes** ; la composition est simplement
     * absente. [check] nomme le premier contrôle qui a échoué, pour le journal technique.
     */
    data class PhysicallyImplausible(val check: PlausibilityCheck) : Refused {
        override val wireOutcome: String get() = OUTCOME_PHYSICALLY_IMPLAUSIBLE
    }

    /**
     * Les trois entrées de profil qu'une composition exige (FR-BODY-001).
     *
     * [BIRTH_DATE] et non « âge » : c'est la date de naissance qui manque au profil, et c'est elle
     * que l'écran demande. L'âge n'en est que la projection sur la date de la mesure.
     */
    enum class ProfileInput(val wireValue: String) {
        HEIGHT("height"),
        BIRTH_DATE("birthDate"),
        SEX("sex"),
    }

    /** Les contrôles de sortie de PRD_SCALE 13.2, dans l'ordre où ils sont appliqués. */
    enum class PlausibilityCheck(val wireValue: String) {
        /** `FFM > 0`. */
        FAT_FREE_MASS_NOT_POSITIVE("fat-free-mass-not-positive"),

        /** `FFM ≤ poids`. */
        FAT_FREE_MASS_ABOVE_WEIGHT("fat-free-mass-above-weight"),

        /** `0 < masse grasse % < 100`. */
        BODY_FAT_PERCENT_OUT_OF_RANGE("body-fat-percent-out-of-range"),

        /** `eau ≤ poids`. */
        BODY_WATER_ABOVE_WEIGHT("body-water-above-weight"),

        /** `0 < eau % < 100`. */
        BODY_WATER_PERCENT_OUT_OF_RANGE("body-water-percent-out-of-range"),

        /** `dépense énergétique au repos > 0`. */
        RESTING_ENERGY_NOT_POSITIVE("resting-energy-not-positive"),
    }

    companion object {
        const val OUTCOME_CALCULATED: String = "calculated"
        const val OUTCOME_MISSING_PROFILE_INPUT: String = "missing-profile-input"
        const val OUTCOME_AGE_OUT_OF_DOMAIN: String = "age-out-of-domain"
        const val OUTCOME_BMI_OUT_OF_DOMAIN: String = "bmi-out-of-domain"
        const val OUTCOME_IMPEDANCE_UNUSABLE: String = "impedance-unusable"
        const val OUTCOME_PHYSICALLY_IMPLAUSIBLE: String = "physically-implausible"
    }
}

/** La composition si elle a été calculée, `null` sinon. Pour les appelants qui n'ont pas de message à écrire. */
val BodyCompositionResult.compositionOrNull: BodyComposition?
    get() = (this as? BodyCompositionResult.Calculated)?.composition

/**
 * Le calcul de composition corporelle de PRD_SCALE 13.2 : pur, déterministe, sans horloge.
 *
 * **Sans horloge, et c'est le point.** Rien ici ne lit la date du jour. La date de la mesure et
 * l'âge à cette date sont des paramètres, si bien que la même fonction sert la pesée du moment et
 * le recalcul rétroactif de FR-BODY-006 — qui doit employer l'âge de *chaque* date passée, jamais
 * celui du jour où l'utilisateur accepte la proposition. Une fonction qui appellerait
 * `LocalDate.now()` rendrait ce recalcul faux d'une année pour tous ceux dont l'anniversaire est
 * passé entre-temps, silencieusement.
 *
 * **Décimal, pas flottant.** Toute l'arithmétique est en [BigDecimal] avec l'échelle et le mode
 * d'arrondi explicites de [BodyCompositionFormula.WORKING_SCALE] et
 * [BodyCompositionFormula.ROUNDING] ; l'arrondi vers les unités entières de stockage n'est appliqué
 * qu'une seule fois, à la toute fin (PRD_SCALE 21.1). La règle complète, et la raison pour laquelle
 * un pipeline `Double` ne suffirait pas à tenir l'exigence « mêmes entiers stockés en Kotlin et en
 * TypeScript », est documentée sur [BodyCompositionFormula.WORKING_SCALE].
 *
 * **Ordre des portes.** Impédance, puis entrées de profil, puis âge, puis IMC, puis calcul, puis
 * contrôles de sortie. L'impédance passe en premier délibérément : sans elle il n'y a rien à
 * calculer quel que soit le profil, et surtout toute saisie manuelle en est dépourvue. Si le profil
 * était examiné d'abord, chaque poids saisi à la main d'un profil sans sexe répondrait
 * « il manque le sexe », et l'appelant de FR-BODY-006 qui compte les pesées passées complétables
 * les compterait toutes. Avec cet ordre, [BodyCompositionResult.MissingProfileInput] ne désigne que
 * des mesures qui portent réellement une impédance exploitable — c'est-à-dire exactement celles
 * qu'un profil complété débloquerait.
 */
object BodyCompositionCalculator {

    private val HUNDRED = BigDecimal("100")
    private val TEN = BigDecimal("10")

    /**
     * Calcule la composition d'une pesée à partir d'entrées primitives.
     *
     * @param date date de la mesure ; identité de la composition produite (PRD_SCALE 21.1).
     * @param weightCg poids en centièmes de kilogramme, celui de la mesure parente (BR-SCALE-015).
     * @param heightCm taille du profil, `null` ou hors de `UserProfile.HEIGHT_RANGE_CM` si absente.
     * @param ageYears âge entier **à [date]**, `null` sans date de naissance. Se calcule avec
     *   `UserProfile.ageOn(date)` ; ne jamais y passer l'âge du jour du calcul (FR-BODY-006).
     * @param sex `null` tant que le profil ne le renseigne pas (FR-PROFILE-007).
     * @param impedanceOhm impédance de la mesure, `null` quand le pilote a signalé une mesure
     *   impossible (BR-SCALE-005) ou quand la pesée est manuelle.
     */
    fun calculate(
        date: LocalDate,
        weightCg: Int,
        heightCm: Int?,
        ageYears: Int?,
        sex: Sex?,
        impedanceOhm: Int?,
    ): BodyCompositionResult {
        val ohm = impedanceOhm?.takeIf { BodyCompositionFormula.isImpedanceUsable(it) }
            ?: return BodyCompositionResult.ImpedanceUnusable(impedanceOhm)

        val height = heightCm?.takeIf { BodyCompositionFormula.isHeightUsable(it) }
        if (height == null || ageYears == null || sex == null) {
            return BodyCompositionResult.MissingProfileInput(
                buildSet {
                    if (height == null) add(BodyCompositionResult.ProfileInput.HEIGHT)
                    if (ageYears == null) add(BodyCompositionResult.ProfileInput.BIRTH_DATE)
                    if (sex == null) add(BodyCompositionResult.ProfileInput.SEX)
                },
            )
        }

        if (!BodyCompositionFormula.isAgeInDomain(ageYears)) {
            return BodyCompositionResult.AgeOutOfDomain(ageYears)
        }

        val bmi = BodyCompositionFormula.bmiOrNull(weightCg, height)
        if (bmi == null || !BodyCompositionFormula.isBmiInDomain(bmi)) {
            return BodyCompositionResult.BmiOutOfDomain(bmi)
        }

        val decimals = compute(weightCg, height, ageYears, sex, ohm)

        plausibilityFailureOf(decimals)?.let { return BodyCompositionResult.PhysicallyImplausible(it) }

        // L'unique arrondi vers les unités entières de stockage (PRD_SCALE 21.1).
        val fatFreeMassCg = decimals.fatFreeMassKg.multiply(HUNDRED).toStoredInt()
        val bodyFatDeciPercent = decimals.bodyFatPercent.multiply(TEN).toStoredInt()
        val bodyWaterDeciPercent = decimals.bodyWaterPercent.multiply(TEN).toStoredInt()
        val restingEnergyKcal = decimals.restingEnergyKcal.toStoredInt()

        storedFailureOf(
            weightCg = weightCg,
            fatFreeMassCg = fatFreeMassCg,
            bodyFatDeciPercent = bodyFatDeciPercent,
            bodyWaterDeciPercent = bodyWaterDeciPercent,
            restingEnergyKcal = restingEnergyKcal,
        )?.let { return BodyCompositionResult.PhysicallyImplausible(it) }

        return BodyCompositionResult.Calculated(
            BodyComposition(
                date = date,
                formulaId = BodyCompositionFormula.ID,
                formulaVersion = BodyCompositionFormula.VERSION,
                inputWeightCg = weightCg,
                inputHeightCm = height,
                inputAgeYears = ageYears,
                inputSex = sex,
                bodyFatDeciPercent = bodyFatDeciPercent,
                fatFreeMassCg = fatFreeMassCg,
                bodyWaterDeciPercent = bodyWaterDeciPercent,
                restingEnergyKcal = restingEnergyKcal,
            ),
        )
    }

    /**
     * La même chose depuis un profil : l'âge est celui que le profil avait **à [date]**
     * (FR-BODY-006), pas celui d'aujourd'hui.
     */
    fun calculate(
        date: LocalDate,
        weight: Weight,
        profile: UserProfile,
        impedanceOhm: Int?,
    ): BodyCompositionResult = calculate(
        date = date,
        weightCg = weight.hundredthsKg,
        heightCm = profile.heightCm,
        ageYears = profile.ageOn(date),
        sex = profile.sex,
        impedanceOhm = impedanceOhm,
    )

    /**
     * La forme qu'appellera le recalcul rétroactif de FR-BODY-006 : une mesure déjà enregistrée,
     * qui porte sa propre date et sa propre impédance, contre le profil **courant**.
     *
     * L'approximation assumée par FR-BODY-006 est visible ici : faute d'historique de profil, la
     * taille et le sexe employés sont ceux d'aujourd'hui, seul l'âge est reconstitué à la date de
     * la mesure. L'explication qui accompagne la proposition doit le dire.
     */
    fun calculate(measurement: Measurement, profile: UserProfile): BodyCompositionResult =
        calculate(
            date = measurement.date,
            weight = measurement.weight,
            profile = profile,
            impedanceOhm = measurement.impedanceOhm,
        )

    /** Les grandeurs décimales, avant tout arrondi de stockage. */
    private class Decimals(
        val weightKg: BigDecimal,
        val fatFreeMassKg: BigDecimal,
        val bodyFatPercent: BigDecimal,
        val bodyWaterKg: BigDecimal,
        val bodyWaterPercent: BigDecimal,
        val restingEnergyKcal: BigDecimal,
    )

    /**
     * Les cinq équations de PRD_SCALE 13.2, dans l'ordre où elles se déduisent.
     *
     * Chaque produit et chaque quotient est ramené à [BodyCompositionFormula.WORKING_SCALE]
     * décimales dès qu'il est formé ; les sommes de valeurs déjà à cette échelle sont exactes.
     * C'est cette granularité-là — arrondir à chaque opération plutôt qu'à chaque ligne — que le
     * portage TypeScript doit reproduire, et c'est pourquoi elle est écrite ainsi plutôt qu'en
     * une seule expression.
     *
     * Les pourcentages divisent `masse × 100` par le poids, et non `masse / poids` puis `× 100` :
     * une seule division, donc un seul arrondi, et un arrondi qui n'est pas ensuite multiplié
     * par cent.
     */
    private fun compute(
        weightCg: Int,
        heightCm: Int,
        ageYears: Int,
        sex: Sex,
        impedanceOhm: Int,
    ): Decimals {
        val weightKg = BigDecimal(weightCg).divideWorking(HUNDRED)
        val impedanceIndex = BigDecimal(heightCm.toLong() * heightCm.toLong())
            .divideWorking(BigDecimal(impedanceOhm))

        val fatFreeMassKg = BodyCompositionFormula.INTERCEPT
            .add(BodyCompositionFormula.WEIGHT_COEFFICIENT.multiplyWorking(weightKg))
            .add(BodyCompositionFormula.IMPEDANCE_INDEX_COEFFICIENT.multiplyWorking(impedanceIndex))
            .subtract(BodyCompositionFormula.AGE_COEFFICIENT.multiplyWorking(BigDecimal(ageYears)))
            .add(
                BodyCompositionFormula.SEX_COEFFICIENT
                    .multiplyWorking(BigDecimal(BodyCompositionFormula.sexCoefficient(sex))),
            )

        val fatMassKg = weightKg.subtract(fatFreeMassKg)
        val bodyFatPercent = fatMassKg.multiplyWorking(HUNDRED).divideWorking(weightKg)

        val bodyWaterKg = fatFreeMassKg.multiplyWorking(BodyCompositionFormula.FAT_FREE_MASS_HYDRATION)
        val bodyWaterPercent = bodyWaterKg.multiplyWorking(HUNDRED).divideWorking(weightKg)

        val restingEnergyKcal = BodyCompositionFormula.RESTING_ENERGY_WEIGHT_COEFFICIENT
            .multiplyWorking(weightKg)
            .add(
                BodyCompositionFormula.RESTING_ENERGY_HEIGHT_COEFFICIENT
                    .multiplyWorking(BigDecimal(heightCm)),
            )
            .subtract(
                BodyCompositionFormula.RESTING_ENERGY_AGE_COEFFICIENT
                    .multiplyWorking(BigDecimal(ageYears)),
            )
            .add(BodyCompositionFormula.restingEnergyOffset(sex))

        return Decimals(
            weightKg = weightKg,
            fatFreeMassKg = fatFreeMassKg,
            bodyFatPercent = bodyFatPercent,
            bodyWaterKg = bodyWaterKg,
            bodyWaterPercent = bodyWaterPercent,
            restingEnergyKcal = restingEnergyKcal,
        )
    }

    /**
     * Les contrôles de sortie de PRD_SCALE 13.2, appliqués aux valeurs décimales, avant tout
     * arrondi. `null` quand tout passe.
     */
    private fun plausibilityFailureOf(d: Decimals): BodyCompositionResult.PlausibilityCheck? = when {
        d.fatFreeMassKg <= BigDecimal.ZERO ->
            BodyCompositionResult.PlausibilityCheck.FAT_FREE_MASS_NOT_POSITIVE

        d.fatFreeMassKg > d.weightKg ->
            BodyCompositionResult.PlausibilityCheck.FAT_FREE_MASS_ABOVE_WEIGHT

        d.bodyFatPercent <= BigDecimal.ZERO || d.bodyFatPercent >= HUNDRED ->
            BodyCompositionResult.PlausibilityCheck.BODY_FAT_PERCENT_OUT_OF_RANGE

        d.bodyWaterKg > d.weightKg ->
            BodyCompositionResult.PlausibilityCheck.BODY_WATER_ABOVE_WEIGHT

        d.bodyWaterPercent <= BigDecimal.ZERO || d.bodyWaterPercent >= HUNDRED ->
            BodyCompositionResult.PlausibilityCheck.BODY_WATER_PERCENT_OUT_OF_RANGE

        d.restingEnergyKcal <= BigDecimal.ZERO ->
            BodyCompositionResult.PlausibilityCheck.RESTING_ENERGY_NOT_POSITIVE

        else -> null
    }

    /**
     * Les mêmes contrôles, rejoués sur les entiers réellement stockés.
     *
     * L'arrondi est monotone : il ne peut pas faire passer une masse maigre au-dessus du poids si
     * elle était en dessous. Mais il peut faire atterrir `99,97 %` sur `1000` dixièmes, c'est-à-dire
     * afficher `100.0 %` de masse grasse — une valeur que PRD_SCALE 13.2 refuse au décimal et qu'il
     * serait absurde d'accepter à l'entier au motif qu'elle vient d'un arrondi. Ce second passage
     * garantit que **ce qui est stocké**, et non seulement ce qui a été calculé, satisfait les
     * bornes. Il n'en ramène aucun dans les bornes : il refuse.
     */
    private fun storedFailureOf(
        weightCg: Int,
        fatFreeMassCg: Int,
        bodyFatDeciPercent: Int,
        bodyWaterDeciPercent: Int,
        restingEnergyKcal: Int,
    ): BodyCompositionResult.PlausibilityCheck? = when {
        fatFreeMassCg <= 0 -> BodyCompositionResult.PlausibilityCheck.FAT_FREE_MASS_NOT_POSITIVE
        fatFreeMassCg > weightCg -> BodyCompositionResult.PlausibilityCheck.FAT_FREE_MASS_ABOVE_WEIGHT
        bodyFatDeciPercent !in 1..999 ->
            BodyCompositionResult.PlausibilityCheck.BODY_FAT_PERCENT_OUT_OF_RANGE

        bodyWaterDeciPercent !in 1..999 ->
            BodyCompositionResult.PlausibilityCheck.BODY_WATER_PERCENT_OUT_OF_RANGE

        restingEnergyKcal <= 0 -> BodyCompositionResult.PlausibilityCheck.RESTING_ENERGY_NOT_POSITIVE
        else -> null
    }

    /** Produit exact, ramené à l'échelle de travail. */
    private fun BigDecimal.multiplyWorking(other: BigDecimal): BigDecimal =
        multiply(other).setScale(BodyCompositionFormula.WORKING_SCALE, BodyCompositionFormula.ROUNDING)

    /** Quotient à l'échelle de travail. */
    private fun BigDecimal.divideWorking(other: BigDecimal): BigDecimal =
        divide(other, BodyCompositionFormula.WORKING_SCALE, BodyCompositionFormula.ROUNDING)

    /**
     * L'unique arrondi de tout le calcul : vers l'entier, en
     * [BodyCompositionFormula.ROUNDING] (PRD_SCALE 21.1).
     *
     * `intValueExact` plutôt que `toInt` : un dépassement de `Int` doit exploser au test plutôt
     * que d'écrire une valeur tronquée dans une donnée de santé. Les contrôles de plausibilité,
     * appliqués juste avant, bornent déjà toutes les grandeurs très en deçà.
     */
    private fun BigDecimal.toStoredInt(): Int =
        setScale(0, BodyCompositionFormula.ROUNDING).intValueExact()
}
