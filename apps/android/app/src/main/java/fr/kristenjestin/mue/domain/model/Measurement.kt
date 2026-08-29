package fr.kristenjestin.mue.domain.model

import java.time.LocalDate
import kotlin.math.roundToLong

/**
 * A weight, stored as whole hundredths of a kilogram.
 *
 * The PRD requires the 0.05 kg step to be preserved exactly (PRD 11.1, BR-003). Floating
 * point would drift, so the hundredth is the unit of truth everywhere in the app and doubles
 * only ever appear at the display boundary.
 */
@JvmInline
value class Weight private constructor(val hundredthsKg: Int) : Comparable<Weight> {

    val kilograms: Double get() = hundredthsKg / 100.0

    operator fun minus(other: Weight): Int = hundredthsKg - other.hundredthsKg

    override fun compareTo(other: Weight): Int = hundredthsKg.compareTo(other.hundredthsKg)

    companion object {
        /** 30.0 kg, per PRD BR-003. */
        const val MIN_HUNDREDTHS: Int = 3_000

        /** 250.0 kg, per PRD BR-003. */
        const val MAX_HUNDREDTHS: Int = 25_000

        /** 0.05 kg: the only interval a weight may land on (PRD BR-003). */
        const val STEP_HUNDREDTHS: Int = 5

        /** The value Entry starts from when no measurement exists (PRD FR-ENTRY-001). */
        val DEFAULT: Weight = Weight(7_000)

        val RANGE: IntRange = MIN_HUNDREDTHS..MAX_HUNDREDTHS

        fun ofHundredthsOrNull(hundredthsKg: Int): Weight? =
            if (hundredthsKg in RANGE) Weight(hundredthsKg) else null

        /**
         * Rounds to the nearest 0.05 kg, then range-checks. Returns null when out of bounds.
         *
         * Both bounds are themselves multiples of the step, so rounding first can never push a
         * value that was inside the range back out of it. The step count is counted in `Long`
         * because the caller is a text field and may well hand over `1e30`.
         */
        fun ofKilogramsOrNull(kilograms: Double): Weight? {
            if (!kilograms.isFinite()) return null
            val steps = (kilograms * 100.0 / STEP_HUNDREDTHS).roundToLong()
            if (steps !in MIN_STEPS..MAX_STEPS) return null
            return Weight(steps.toInt() * STEP_HUNDREDTHS)
        }

        /** Clamps into the valid range instead of rejecting. Used by the ruler, never by input validation. */
        fun ofHundredthsClamped(hundredthsKg: Int): Weight =
            Weight(hundredthsKg.coerceIn(MIN_HUNDREDTHS, MAX_HUNDREDTHS))

        private val MIN_STEPS: Long = (MIN_HUNDREDTHS / STEP_HUNDREDTHS).toLong()
        private val MAX_STEPS: Long = (MAX_HUNDREDTHS / STEP_HUNDREDTHS).toLong()
    }
}

/**
 * One weight recorded for one calendar day.
 *
 * The date is a pure local date with no time and no zone, which makes a
 * timezone-induced off-by-one-day impossible (PRD 11.1).
 *
 * Le module balance en fait un petit agrégat (PRD_SCALE 21.1) : provenance, impédance brute et
 * composition corporelle facultative voyagent **avec** le poids et s'écrivent dans une seule
 * transaction. Tous les champs ajoutés ont une valeur par défaut, de sorte qu'une saisie manuelle
 * s'écrit toujours `Measurement(date, weight)` et qu'aucun appelant antérieur au module n'a eu à
 * changer.
 *
 * @property source D'où vient ce poids (PRD_SCALE 21.1). Retoucher à la main un poids reçu le
 *   ramène à [MeasurementSource.MANUAL] et retire [sourceScaleId], [impedanceOhm] et
 *   [bodyComposition] (BR-SCALE-013, BR-SCALE-009).
 * @property sourceScaleId [ScaleDevice.id] de la balance émettrice. **Strictement local, jamais
 *   synchronisé** (PRD_SCALE 22) : cet identifiant ne désigne rien en dehors de ce téléphone.
 *   Passe à `null` quand la balance est oubliée, sans que la mesure ni sa provenance ne bougent
 *   (BR-SCALE-010).
 * @property impedanceOhm Impédance corporelle totale mesurée en même temps que le poids.
 *
 *   **Portée par la mesure et non par [BodyComposition]** (FR-BODY-004, BR-SCALE-008). Une
 *   impédance parfaitement exploitable est produite dès les premières pesées, alors que le profil
 *   est encore incomplet — c'est le cas normal tant que le sexe n'est pas renseigné. Rangée dans
 *   la composition, elle disparaîtrait exactement dans ce cas : des semaines de mesures seraient
 *   perdues sans recours le jour où le profil est complété, c'est-à-dire précisément le jour où le
 *   calcul rétroactif de FR-BODY-006 en aurait besoin. Un entier de deux octets ne coûte rien à
 *   conserver ; sa perte est définitive. `null` lorsque la balance a signalé une mesure impossible
 *   (BR-SCALE-005).
 * @property bodyComposition Estimation dérivée, enfant facultatif qui ne peut exister seul
 *   (BR-SCALE-006). Un payload sans composition **supprime** la composition antérieure de cette
 *   date, dans la même transaction (BR-SCALE-007).
 */
data class Measurement(
    val date: LocalDate,
    val weight: Weight,
    val source: MeasurementSource = MeasurementSource.MANUAL,
    val sourceScaleId: String? = null,
    val impedanceOhm: Int? = null,
    val bodyComposition: BodyComposition? = null,
)
