package fr.kristenjestin.mue.ui.progress

import fr.kristenjestin.mue.domain.model.BodyComposition
import fr.kristenjestin.mue.ui.scale.ScaleMessages
import fr.kristenjestin.mue.ui.scale.ScaleTestTags
import java.util.Locale

/**
 * Les unités écrites, à côté du nombre qu'elles suivent.
 *
 * **Délibérément hors de [ScaleMessages]** : ce ne sont pas des phrases adressées à quelqu'un mais
 * du formatage, au même titre que le nombre de décimales décidé par [BodyCompositionMetric.value].
 * Leurs formes **parlées**, que la synthèse vocale prononce réellement, sont dans [ScaleMessages]
 * avec le reste de ce que PRD_SCALE 20 fait dire à l'écran.
 */
private const val PERCENT = "%"
private const val KILOGRAMS = "kg"
private const val KILOCALORIES = "kcal"

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
 * @property unit l'unité affichée à côté du nombre, prise aux constantes de formatage de ce
 *   fichier : c'est de la mise en page, pas une phrase.
 * @property spokenUnit la même unité en toutes lettres, prise à [ScaleMessages] avec le reste de ce
 *   qui est **dit** à l'utilisateur (PRD_SCALE 20) : `%` se lit `percent`, pas « pour cent le
 *   symbole ».
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
        unit = PERCENT,
        spokenUnit = ScaleMessages.SPOKEN_PERCENT,
    ),

    FAT_FREE_MASS(
        label = ScaleMessages.FAT_FREE_MASS,
        testTag = ScaleTestTags.FAT_FREE_MASS_CARD,
        unit = KILOGRAMS,
        spokenUnit = ScaleMessages.SPOKEN_KILOGRAMS,
    ),

    BODY_WATER(
        label = ScaleMessages.BODY_WATER,
        testTag = ScaleTestTags.BODY_WATER_CARD,
        unit = PERCENT,
        spokenUnit = ScaleMessages.SPOKEN_PERCENT,
    ),

    /**
     * La dépense énergétique au repos. Regroupée avec les trois autres parce qu'elle partage leur
     * instantané de profil et leur date, mais PRD_SCALE 13.2 rappelle qu'elle **n'utilise pas
     * l'impédance** : son libellé ne prétend donc rien mesurer.
     */
    RESTING_ENERGY(
        label = ScaleMessages.RESTING_ENERGY,
        testTag = ScaleTestTags.RESTING_ENERGY_CARD,
        unit = KILOCALORIES,
        spokenUnit = ScaleMessages.SPOKEN_KILOCALORIES,
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
