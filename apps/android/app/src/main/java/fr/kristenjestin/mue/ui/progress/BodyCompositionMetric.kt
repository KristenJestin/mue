package fr.kristenjestin.mue.ui.progress

import fr.kristenjestin.mue.domain.logic.BodyCompositionResult
import fr.kristenjestin.mue.domain.model.BodyComposition
import fr.kristenjestin.mue.ui.scale.ScaleMessages
import fr.kristenjestin.mue.ui.scale.ScaleTestTags
import java.util.Locale

/**
 * Les quatre grandeurs de PRD_SCALE FR-BODY-003, avec tout ce qui les distingue à l'écran.
 *
 * **Une énumération plutôt que quatre composables.** Les quatre cartes ne diffèrent que par un
 * libellé, une unité, un champ lu et le nombre de décimales ; les écrire quatre fois, c'est
 * s'exposer à ce que la troisième reçoive un jour un seuil, une couleur ou une barre que les
 * autres n'ont pas. La règle d'affichage est donc énoncée une seule fois, ici, et la carte n'est
 * qu'un gabarit. C'est aussi ce qui rend le formatage et l'écart testables en JVM pure, sans
 * Compose : ce sont les deux endroits où FR-BODY-003 se casse en silence.
 *
 * **Ce que cette énumération ne porte pas** : aucune borne, aucune plage, aucun rang, aucune
 * couleur. FR-BODY-003 interdit les catégories et les seuils, et le fait qu'aucune donnée de ce
 * genre n'existe ici est la manière la plus sûre de tenir cette interdiction — on ne peut pas
 * afficher un seuil qui n'a jamais été écrit.
 *
 * @property label le libellé anglais, mot pour mot celui de FR-BODY-003.
 * @property testTag la carte correspondante, réservée d'avance dans [ScaleTestTags].
 * @property unit l'unité affichée à côté du nombre.
 * @property spokenUnit la même unité en toutes lettres, pour l'annonce d'accessibilité
 *   (PRD_SCALE 20) : `%` se lit `percent`, pas « pour cent le symbole ».
 */
internal enum class BodyCompositionMetric(
    val label: String,
    val testTag: String,
    val unit: String,
    val spokenUnit: String,
) {
    BODY_FAT(
        label = ScaleMessages.BODY_FAT,
        testTag = ScaleTestTags.BODY_FAT_CARD,
        unit = "%",
        spokenUnit = "percent",
    ),

    FAT_FREE_MASS(
        label = ScaleMessages.FAT_FREE_MASS,
        testTag = ScaleTestTags.FAT_FREE_MASS_CARD,
        unit = "kg",
        spokenUnit = "kilograms",
    ),

    BODY_WATER(
        label = ScaleMessages.BODY_WATER,
        testTag = ScaleTestTags.BODY_WATER_CARD,
        unit = "%",
        spokenUnit = "percent",
    ),

    /**
     * La dépense énergétique au repos. Regroupée avec les trois autres parce qu'elle partage leur
     * instantané de profil et leur date, mais PRD_SCALE 13.2 rappelle qu'elle **n'utilise pas
     * l'impédance** : son libellé ne prétend donc rien mesurer.
     */
    RESTING_ENERGY(
        label = ScaleMessages.RESTING_ENERGY,
        testTag = ScaleTestTags.RESTING_ENERGY_CARD,
        unit = "kcal",
        spokenUnit = "kilocalories",
    ),
    ;

    /**
     * La valeur principale, formatée : une décimale pour les pourcentages et les masses, un entier
     * pour la dépense énergétique au repos (FR-BODY-003).
     *
     * `—` quand [composition] est `null`, c'est-à-dire quand la période ne contient aucune
     * composition. Aucune valeur n'est empruntée hors période (FR-BODY-005).
     */
    fun value(composition: BodyComposition?, locale: Locale = Locale.getDefault()): String =
        when (this) {
            BODY_FAT -> ProgressFormat.estimate(composition?.bodyFatPercent, locale)
            FAT_FREE_MASS -> ProgressFormat.estimate(composition?.fatFreeMassKg, locale)
            BODY_WATER -> ProgressFormat.estimate(composition?.bodyWaterPercent, locale)
            RESTING_ENERGY -> ProgressFormat.energy(composition?.restingEnergyKcal, locale)
        }

    /**
     * L'écart avec la composition précédente **de la même période**, avec son signe — la seule
     * mise en perspective que FR-BODY-003 autorise.
     *
     * `—` sans seconde composition dans la période. Jamais un écart pris sur une composition
     * antérieure à la période : ce serait comparer deux fenêtres différentes sous un même signe.
     *
     * La soustraction porte sur les **entiers stockés**, avant toute division d'affichage. Passer
     * par les `Double` dérivés arrondirait deux fois, et un écart de `0,05` point pourrait
     * s'afficher `+0.1` alors que la différence des dixièmes stockés vaut `0`.
     */
    fun change(
        latest: BodyComposition?,
        previous: BodyComposition?,
        locale: Locale = Locale.getDefault(),
    ): String {
        if (latest == null || previous == null) return ProgressFormat.UNAVAILABLE
        return when (this) {
            BODY_FAT -> ProgressFormat.signedEstimate(
                (latest.bodyFatDeciPercent - previous.bodyFatDeciPercent) / DECI,
                locale,
            )

            FAT_FREE_MASS -> ProgressFormat.signedEstimate(
                (latest.fatFreeMassCg - previous.fatFreeMassCg) / CENTI,
                locale,
            )

            BODY_WATER -> ProgressFormat.signedEstimate(
                (latest.bodyWaterDeciPercent - previous.bodyWaterDeciPercent) / DECI,
                locale,
            )

            RESTING_ENERGY -> ProgressFormat.signedEnergy(
                latest.restingEnergyKcal - previous.restingEnergyKcal,
                locale,
            )
        }
    }

    private companion object {
        /** Dixièmes de pour cent vers pour cent (`BodyComposition.bodyFatDeciPercent`). */
        const val DECI = 10.0

        /** Centièmes de kilogramme vers kilogrammes (`BodyComposition.fatFreeMassCg`). */
        const val CENTI = 100.0
    }
}

/**
 * Les mots que la section de composition ajoute à ceux de [ScaleMessages].
 *
 * `ScaleMessages` couvre tout ce que PRD_SCALE écrit noir sur blanc ; ce qui suit est la
 * grammaire de l'écran autour — les liaisons, les unités parlées et les annonces
 * d'accessibilité de PRD_SCALE 20. Ils vivent ici plutôt que là-bas parce que trois modules
 * écrivent `ScaleMessages` en parallèle et qu'une écriture concurrente en perdrait la moitié ;
 * l'orchestrateur consolidera.
 *
 * Anglais à l'écran, français en commentaire, et des constantes Kotlin plutôt que
 * `res/values/strings.xml`, comme partout ailleurs dans Mue.
 */
internal object BodyCompositionMessages {

    /**
     * Ce qui ouvre le texte de prudence détaillé de `BodyCompositionFormula`
     * (FR-BODY-005, PRD_SCALE 13.3).
     *
     * Une question, pas un avertissement : le texte derrière explique d'où viennent les chiffres,
     * il ne met en garde contre rien.
     */
    const val HOW_ESTIMATED: String = "How these are estimated"

    /** L'en-tête de l'écart quand il n'y a pas de composition précédente à nommer. */
    const val CHANGE_LABEL: String = "Change"

    /** L'en-tête de l'écart, daté : un écart sans sa date ne dit pas sur quoi il porte. */
    fun changeSince(date: String): String = "Change since $date"

    /**
     * FR-BODY-005 : la date de la valeur affichée reste visible, faute de quoi elle passerait pour
     * la dernière pesée de poids — ce qu'elle n'est pas, puisque les pesées sans impédance sont
     * ignorées pour la choisir.
     */
    fun measuredOn(date: String): String = "${ScaleMessages.MEASURED_ON_LABEL} $date"

    /**
     * PRD_SCALE 18.4 : ce qui manque, nommément.
     *
     * Écrit à partir de [BodyCompositionResult.ProfileInput] plutôt que codé en dur, de sorte que
     * l'utilisateur qui n'a omis que son sexe ne se voie pas réclamer une taille qu'il a déjà
     * donnée. Avec les trois entrées manquantes, la phrase produite est **exactement**
     * [ScaleMessages.PROFILE_INCOMPLETE_BODY] — un test le verrouille, pour que la formulation
     * partagée reste la référence et que celle-ci n'en soit qu'une variante plus précise.
     *
     * La seconde phrase est FR-BODY-006 par anticipation : ce n'est pas seulement l'avenir qui est
     * en jeu, l'impédance déjà mesurée a été conservée et le passé pourra être complété.
     */
    fun profileIncompleteBody(missing: Set<BodyCompositionResult.ProfileInput>): String {
        val named = BodyCompositionResult.ProfileInput.entries
            .filter { it in missing }
            .map(::profileInputPhrase)
        return "Estimates need ${joinWithAnd(named)}. $IMPEDANCE_KEPT"
    }

    /**
     * PRD_SCALE 20 : la valeur principale, lue avec son unité en toutes lettres et sa date.
     *
     * Seuls les deux libellés sont mis en minuscules, jamais la date : `Aug 20, 2026` passé à
     * `lowercase` deviendrait `aug 20, 2026`, que les synthèses vocales lisent comme un mot.
     */
    fun valueDescription(metric: BodyCompositionMetric, value: String, date: String): String =
        "${metric.label} ${ScaleMessages.ESTIMATE.lowercase(Locale.ROOT)} $value " +
            "${metric.spokenUnit}, " +
            "${ScaleMessages.MEASURED_ON_LABEL.lowercase(Locale.ROOT)} $date"

    /** PRD_SCALE 20 : une période sans composition, dite plutôt que laissée à un tiret muet. */
    fun valueUnavailableDescription(metric: BodyCompositionMetric): String =
        "${metric.label} estimate unavailable for this period"

    /** PRD_SCALE 20 : l'écart, lu avec ce à quoi il se compare. */
    fun changeDescription(
        metric: BodyCompositionMetric,
        change: String,
        previousDate: String,
    ): String = "${changeSince(previousDate)}, $change ${metric.spokenUnit}"

    /** PRD_SCALE 20 : pourquoi l'écart est un tiret. Un fait, pas un manque. */
    const val NO_PREVIOUS_DESCRIPTION: String = "No earlier estimate in this period"

    /**
     * La seconde phrase de [ScaleMessages.PROFILE_INCOMPLETE_BODY], reprise mot pour mot pendant
     * que la première est rendue spécifique.
     */
    private const val IMPEDANCE_KEPT: String =
        "Mue kept the impedance it already measured, so past weigh-ins can be completed too."

    /** Le nom de chaque entrée manquante, tel que l'écran `Profile` l'appelle. */
    private fun profileInputPhrase(input: BodyCompositionResult.ProfileInput): String =
        when (input) {
            BodyCompositionResult.ProfileInput.HEIGHT -> "your height"
            BodyCompositionResult.ProfileInput.BIRTH_DATE -> "your date of birth"
            BodyCompositionResult.ProfileInput.SEX -> "your sex"
        }

    /** `a`, `a and b`, `a, b and c` — sans virgule avant le `and`, comme le reste de l'app. */
    private fun joinWithAnd(items: List<String>): String = when (items.size) {
        0, 1 -> items.joinToString()
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }
}
