package fr.kristenjestin.mue.domain.logic

import fr.kristenjestin.mue.domain.model.Sex
import fr.kristenjestin.mue.domain.model.UserProfile
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * L'identité, les coefficients et le domaine de validité du jeu de formules `mue-foot-to-foot-v1`
 * (PRD_SCALE 13.2).
 *
 * **Pourquoi un objet séparé du calcul.** Trois choses sans rapport de rythme vivent ici : ce qui
 * est écrit dans chaque ligne stockée ([ID], [VERSION]), ce qui décide si un calcul a le droit
 * d'exister ([AGE_RANGE_YEARS], [MIN_BMI]–[MAX_BMI]), et ce que l'écran `Progress` doit dire de
 * ces chiffres ([DETAILED_CAUTION]). Changer un coefficient impose une nouvelle version et une
 * migration de recalcul (FR-BODY-004) ; corriger une virgule du texte de prudence n'impose rien.
 * Les mélanger au calcul rendrait cette différence invisible.
 *
 * **Le domaine est plus étroit que la publication.** L'équation a été validée de 16 à 75 ans
 * ([VALIDATION_AGE_RANGE_YEARS]) ; FR-BODY-001 ne l'autorise qu'à partir de 20 ans, l'âge auquel
 * PRD FR-BMI-002 accepte déjà de nommer une catégorie d'IMC. Le PRD tranche explicitement en
 * faveur du domaine produit, plus conservateur.
 *
 * @see BodyCompositionCalculator pour le calcul lui-même et les motifs de refus.
 */
object BodyCompositionFormula {

    /**
     * Identifiant stable du jeu de formules (PRD_SCALE 13.2), copié dans chaque
     * `BodyComposition.formulaId`. Un jeu différent — coefficients, hydratation, ou simple
     * changement d'ordre des arrondis — porte un autre identifiant ou une autre [VERSION].
     */
    const val ID: String = "mue-foot-to-foot-v1"

    /** Version entière du jeu de formules (PRD_SCALE 13.2). */
    const val VERSION: Int = 1

    // ------------------------------------------------------------------ domaine de validité

    /**
     * Âge admis, **inclus aux deux bornes**, apprécié à la date de la mesure et jamais à la date
     * du calcul (FR-BODY-001, FR-BODY-006).
     */
    val AGE_RANGE_YEARS: IntRange = 20..75

    /** Borne basse d'IMC du domaine de développement de l'équation, incluse (FR-BODY-001). */
    val MIN_BMI: BigDecimal = BigDecimal("15.8")

    /** Borne haute d'IMC du domaine de développement de l'équation, incluse (FR-BODY-001). */
    val MAX_BMI: BigDecimal = BigDecimal("43.1")

    // ------------------------------------------------------------------ arithmétique décimale

    /**
     * Nombre de décimales conservées par toute grandeur intermédiaire.
     *
     * **C'est une clause de contrat inter-langages, pas un détail d'implémentation.**
     * PRD_SCALE 13.2 exige qu'« un même payload produise les mêmes entiers stockés » en Kotlin et
     * en TypeScript. Un pipeline `Double` ne le garantit pas : les deux langages partagent bien
     * IEEE 754 binary64, mais dès qu'une valeur mathématiquement égale à `x,xx5` doit être
     * arrondie, sa représentation binaire tombe juste au-dessus ou juste en dessous de la
     * mi-chemin selon l'ordre des opérations, et les deux implémentations divergent d'une unité
     * de stockage sans qu'aucun test ne le voie venir.
     *
     * La règle retenue est donc entièrement décimale, et énoncée pour être réimplémentée
     * ailleurs :
     *
     * 1. toute multiplication et toute division est arrondie **immédiatement** à [WORKING_SCALE]
     *    décimales en [ROUNDING] ;
     * 2. les additions et soustractions de valeurs déjà à [WORKING_SCALE] décimales sont exactes
     *    et ne réarrondissent rien ;
     * 3. l'arrondi vers les unités entières de stockage n'est appliqué **qu'une seule fois**, tout
     *    à la fin (PRD_SCALE 21.1).
     *
     * Un port TypeScript la reproduit exactement avec `BigInt` et sans aucune dépendance : les
     * grandeurs sont des entiers d'échelle `10^12`, `mul(a,b) = round(a*b / 10^12)`,
     * `div(a,b) = round(a*10^12 / b)`, l'arrondi étant `floor((2n + d) / 2d)` sur les valeurs
     * positives — c'est l'implémentation qui a produit
     * `src/test/resources/bodycomposition/mue-foot-to-foot-v1.json`.
     *
     * Douze décimales : très au-delà des quatre chiffres significatifs que les unités de stockage
     * exposent, assez peu pour que les entiers intermédiaires restent minuscules.
     */
    const val WORKING_SCALE: Int = 12

    /**
     * [RoundingMode.HALF_UP] — la mi-chemin s'éloigne de zéro.
     *
     * Retenu contre `HALF_EVEN` parce qu'il se réimplémente en une ligne dans n'importe quel
     * langage, y compris en arithmétique entière, alors que l'arrondi au pair demande un test
     * supplémentaire que la moitié des portages oublient. Les vecteurs de test versionnés
     * couvrent une mi-chemin exacte sur chacune des quatre sorties.
     */
    val ROUNDING: RoundingMode = RoundingMode.HALF_UP

    // ------------------------------------------------------------------ coefficients publiés

    /** Constante de l'équation de masse maigre pied-pied (PRD_SCALE 13.2). */
    val INTERCEPT: BigDecimal = BigDecimal("13.055")

    /** Coefficient du poids en kilogrammes. */
    val WEIGHT_COEFFICIENT: BigDecimal = BigDecimal("0.204")

    /** Coefficient de l'indice d'impédance `taille² / impédance`, en cm²/Ω. */
    val IMPEDANCE_INDEX_COEFFICIENT: BigDecimal = BigDecimal("0.394")

    /** Coefficient de l'âge, **soustrait** dans l'équation. */
    val AGE_COEFFICIENT: BigDecimal = BigDecimal("0.136")

    /** Coefficient du terme de sexe, ajouté pour [Sex.MALE] seulement. */
    val SEX_COEFFICIENT: BigDecimal = BigDecimal("8.125")

    /**
     * Hydratation moyenne de la masse maigre (Wang et al., 1999).
     *
     * **Une moyenne physiologique, pas une mesure.** La balance ne mesure pas l'eau : l'eau
     * corporelle affichée est la masse maigre multipliée par ce facteur fixe, et elle ne bouge
     * donc jamais indépendamment d'elle. Le texte de prudence le dit explicitement, faute de quoi
     * l'utilisateur lirait une quatrième mesure là où il n'y a qu'un produit.
     */
    val FAT_FREE_MASS_HYDRATION: BigDecimal = BigDecimal("0.732")

    /** Coefficient du poids de Mifflin–St Jeor, en kcal/kg. */
    val RESTING_ENERGY_WEIGHT_COEFFICIENT: BigDecimal = BigDecimal("10")

    /** Coefficient de la taille de Mifflin–St Jeor, en kcal/cm. */
    val RESTING_ENERGY_HEIGHT_COEFFICIENT: BigDecimal = BigDecimal("6.25")

    /** Coefficient de l'âge de Mifflin–St Jeor, **soustrait**. */
    val RESTING_ENERGY_AGE_COEFFICIENT: BigDecimal = BigDecimal("5")

    /** Décalage `−161` de Mifflin–St Jeor pour [Sex.FEMALE]. */
    val RESTING_ENERGY_FEMALE_OFFSET: BigDecimal = BigDecimal("-161")

    /** Décalage `+5` de Mifflin–St Jeor pour [Sex.MALE]. */
    val RESTING_ENERGY_MALE_OFFSET: BigDecimal = BigDecimal("5")

    // ------------------------------------------------------------------ provenance et incertitude

    /**
     * Erreur type publiée de l'équation de masse maigre, en kilogrammes (Chen et al., 2015).
     *
     * `3.17 kg` sur la masse maigre, soit environ trois points de pourcentage de masse grasse pour
     * un adulte de 80 kg : c'est l'ordre de grandeur de l'écart légitime entre deux personnes
     * ayant exactement la même lecture. Ce nombre est la raison pour laquelle FR-BODY-003 interdit
     * les seuils et les catégories, et pour laquelle la seule mise en perspective autorisée est
     * l'écart avec la mesure précédente — un écart partage la même erreur systématique et la
     * neutralise en partie, un seuil non.
     */
    const val STANDARD_ERROR_KG: Double = 3.17

    /** Taille de la population de validation de l'équation (Chen et al., 2015). */
    const val VALIDATION_SAMPLE_SIZE: Int = 554

    /**
     * Âges couverts par la population de validation. Plus large que [AGE_RANGE_YEARS], que
     * FR-BODY-001 restreint volontairement.
     */
    val VALIDATION_AGE_RANGE_YEARS: IntRange = 16..75

    /**
     * Comment la population de validation a été mesurée : debout, pied-pied, contre la DXA comme
     * méthode de référence. C'est exactement la posture qu'impose la balance de référence, et
     * c'est ce qui rend cette équation-là défendable ici alors qu'une équation main-pied, plus
     * courante dans la littérature, ne le serait pas.
     */
    const val VALIDATION_METHOD: String =
        "554 healthy adults aged 16 to 75, measured standing on a foot-to-foot impedance scale " +
            "and validated against DXA."

    // ------------------------------------------------------------------ textes d'interface

    /**
     * Phrase courte qui accompagne les quatre cartes de `Progress` (FR-BODY-005), dans le même
     * esprit que celle de l'IMC (`BmiCalculator.DISCLAIMER`).
     *
     * En anglais : l'application est anglophone et ses textes sont des constantes Kotlin, jamais
     * `res/values/strings.xml`.
     */
    const val DISCLAIMER: String =
        "Estimates from weight and impedance, not measurements."

    /**
     * Ce que FR-BODY-001 autorise à dire quand aucune composition n'est disponible.
     *
     * Sobre, et **sans montrer l'IMC ni l'âge** : les afficher là transformerait une limite du
     * domaine de validité d'une équation en jugement porté sur la personne.
     *
     * Reproduit au caractère près la phrase de FR-BODY-001, ponctuation finale comprise —
     * c'est-à-dire sans point : l'écran la pose comme une constatation, pas comme un verdict.
     */
    const val UNAVAILABLE_FOR_PROFILE: String =
        "Body composition estimates are not available for this profile"

    /**
     * Le texte de prudence détaillé accessible depuis `Progress`, paragraphe par paragraphe
     * (FR-BODY-005, PRD_SCALE 13.3). [DETAILED_CAUTION] en donne la version d'un seul tenant.
     *
     * Il vit ici, complet et prêt à afficher, pour deux raisons. D'abord parce qu'il énonce
     * exactement ce que les constantes voisines valent — la population de validation, l'erreur
     * type, le facteur d'hydratation fixe — et qu'un texte de prudence rangé loin des nombres
     * qu'il commente cesse d'être révisé en même temps qu'eux. Ensuite parce que PRD_SCALE 13.2
     * exige qu'il soit documenté « dans le code et dans le texte de prudence » : c'est le même
     * texte, écrit une fois.
     *
     * Il ne comporte ni seuil, ni catégorie, ni comparaison à une population de référence
     * (FR-BODY-003), et il nomme la limite que PRD_SCALE 13.3 refuse de taire : l'équation a été
     * validée dans une population asiatique en bonne santé et n'est pas universelle.
     */
    val DETAILED_CAUTION_PARAGRAPHS: List<String> = listOf(
        "These four figures are estimates, not measurements. The scale measures exactly two " +
            "things: your weight and your body's total electrical impedance. Body fat, fat-free " +
            "mass, body water and resting energy are computed from those two numbers together " +
            "with your height, age and sex.",
        "The fat-free mass equation ($ID) comes from a published study of " +
            "$VALIDATION_SAMPLE_SIZE healthy adults aged ${VALIDATION_AGE_RANGE_YEARS.first} to " +
            "${VALIDATION_AGE_RANGE_YEARS.last}, measured standing on a foot-to-foot impedance " +
            "scale and validated against DXA. Its published standard error is " +
            "$STANDARD_ERROR_KG kg, so two people with the same reading can genuinely differ by " +
            "about that much. That population was healthy and Asian, and the equation is not " +
            "universal, which is why it carries a version number and why Mue can replace it " +
            "later without losing anything it measured.",
        "Body water is fat-free mass multiplied by a fixed hydration factor of " +
            "$FAT_FREE_MASS_HYDRATION. That factor is a physiological average, not a reading of " +
            "how hydrated you are today. Resting energy uses the Mifflin-St Jeor equation and " +
            "does not use the impedance at all.",
        "Readings move with hydration, time of day, food, exercise and how well your bare feet " +
            "meet the plate. A single value says little; the direction over several weeks says " +
            "more. Mue shows no categories, no thresholds and no comparison with a reference " +
            "population, and none of this is a medical assessment.",
    )

    /**
     * Les mêmes paragraphes en un seul texte.
     *
     * Aucun retour à la ligne à l'intérieur d'un paragraphe : la coupure est celle du composant qui
     * l'affiche, à la largeur de l'écran, pas celle de la marge du fichier source. C'est aussi
     * pourquoi [DETAILED_CAUTION_PARAGRAPHS] reste exposé — une feuille qui veut espacer ses
     * paragraphes n'a pas à redécouper cette chaîne.
     */
    val DETAILED_CAUTION: String = DETAILED_CAUTION_PARAGRAPHS.joinToString(separator = "\n\n")

    // ------------------------------------------------------------------ domaine, en fonctions

    /**
     * `true` si [ageYears] — l'âge **à la date de la mesure** — est dans le domaine de
     * FR-BODY-001, bornes incluses.
     *
     * L'âge se calcule avec `UserProfile.ageOn(measurementDate)`, jamais avec la date du jour :
     * FR-BODY-006 recalcule des mesures anciennes, et l'instantané doit porter l'âge que la
     * personne avait ce jour-là.
     */
    fun isAgeInDomain(ageYears: Int): Boolean = ageYears in AGE_RANGE_YEARS

    /**
     * L'IMC exact utilisé par la porte de domaine, ou `null` si [heightCm] ne permet pas de le
     * calculer.
     *
     * Volontairement **non arrondi**, contrairement à [BmiCalculator] qui, lui, classe la valeur
     * telle qu'elle est affichée. Les deux règles sont justes chacune à sa place : l'IMC affiché
     * doit correspondre à la catégorie annoncée, tandis que la porte de domaine décide si une
     * équation a été validée pour cette morphologie. Arrondir d'abord laisserait entrer un IMC de
     * `43.14` au motif qu'il s'écrit `43.1`, c'est-à-dire élargirait le domaine publié — exactement
     * ce que PRD_SCALE 13.2 interdit quand il refuse qu'un résultat soit ramené dans les bornes.
     *
     * Calculé en une seule division exacte, `weightCg × 100 / heightCm²`, plutôt qu'en repassant
     * par des mètres : deux divisions successives introduiraient deux arrondis là où le rapport
     * est un quotient d'entiers.
     */
    fun bmiOrNull(weightCg: Int, heightCm: Int): BigDecimal? {
        if (heightCm <= 0) return null
        return BigDecimal(weightCg.toLong() * 100L)
            .divide(BigDecimal(heightCm.toLong() * heightCm.toLong()), WORKING_SCALE, ROUNDING)
    }

    /** `true` si [bmi] appartient à `[MIN_BMI, MAX_BMI]`, bornes incluses (FR-BODY-001). */
    fun isBmiInDomain(bmi: BigDecimal): Boolean =
        bmi >= MIN_BMI && bmi <= MAX_BMI

    /**
     * `true` si [heightCm] peut alimenter l'équation.
     *
     * Le domaine est celui de la saisie du profil (PRD FR-PROFILE-001) : une taille en dehors ne
     * peut pas venir de l'écran `Profile`, et la traiter comme une entrée manquante plutôt que
     * comme un refus de domaine donne à l'utilisateur le seul message utile — renseigner une
     * taille.
     */
    fun isHeightUsable(heightCm: Int?): Boolean =
        heightCm != null && heightCm in UserProfile.HEIGHT_RANGE_CM

    /**
     * `true` si [impedanceOhm] est exploitable (BR-SCALE-005).
     *
     * Nulle, négative ou absente : rien à calculer. Le marqueur d'absence propre à chaque appareil
     * — `0xFFFF` pour la balance de référence — est converti en `null` par le pilote bien avant
     * d'arriver ici, parce que sa valeur numérique dépend du protocole et que le domaine n'a pas à
     * connaître les protocoles. Une impédance présente mais aberrante n'est pas filtrée ici non
     * plus : elle produit une masse maigre absurde, et c'est le contrôle de sortie de
     * PRD_SCALE 13.2 qui la refuse, avec un motif qui dit ce qui a réellement échoué.
     */
    fun isImpedanceUsable(impedanceOhm: Int?): Boolean =
        impedanceOhm != null && impedanceOhm > 0

    /** Le terme `sexCoefficient` de l'équation : `0` pour [Sex.FEMALE], `1` pour [Sex.MALE]. */
    fun sexCoefficient(sex: Sex): Int = when (sex) {
        Sex.FEMALE -> 0
        Sex.MALE -> 1
    }

    /** Le décalage de Mifflin–St Jeor : `−161` pour [Sex.FEMALE], `+5` pour [Sex.MALE]. */
    fun restingEnergyOffset(sex: Sex): BigDecimal = when (sex) {
        Sex.FEMALE -> RESTING_ENERGY_FEMALE_OFFSET
        Sex.MALE -> RESTING_ENERGY_MALE_OFFSET
    }
}
