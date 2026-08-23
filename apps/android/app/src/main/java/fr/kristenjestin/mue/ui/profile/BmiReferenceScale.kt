package fr.kristenjestin.mue.ui.profile

import fr.kristenjestin.mue.domain.logic.BmiCategory

/**
 * Geometry of the four-band reference bar of the Profile prototype.
 *
 * The bands are drawn as four equal slices even though they cover unequal BMI spans: the bar
 * is a legend, not a scale. The marker is therefore placed *within* its band rather than by
 * a single linear mapping, which is what keeps `24.9` and `25.1` on opposite sides of the
 * boundary they are named after.
 *
 * The bar is only ever shown for a [fr.kristenjestin.mue.domain.logic.Bmi.Classified] value
 * (PRD FR-BMI-002); this file never decides that.
 */
internal object BmiReferenceScale {

    /**
     * Short labels under the bar, in band order. They mirror the prototype, where the
     * healthy band is abbreviated to fit a quarter of the card; the full
     * [BmiCategory.label] is shown next to the value.
     */
    val SHORT_LABELS: List<String> = listOf("Underweight", "Healthy", "Overweight", "Obesity")

    val CATEGORIES: List<BmiCategory> = listOf(
        BmiCategory.UNDERWEIGHT,
        BmiCategory.HEALTHY_WEIGHT,
        BmiCategory.OVERWEIGHT,
        BmiCategory.OBESITY,
    )

    /**
     * Band edges. The outer two are display bounds, not medical ones: the underweight and
     * obesity bands are open-ended, so the marker is simply pinned once it reaches the end
     * of the bar.
     */
    private val EDGES = doubleArrayOf(15.0, 18.5, 25.0, 30.0, 40.0)

    private const val BAND_COUNT = 4

    fun bandIndexOf(category: BmiCategory): Int = CATEGORIES.indexOf(category)

    /** Position of the marker along the whole bar, from `0f` at the far left to `1f`. */
    fun markerFraction(value: Double): Float {
        val band = when {
            value < EDGES[1] -> 0
            value < EDGES[2] -> 1
            value < EDGES[3] -> 2
            else -> 3
        }
        val span = EDGES[band + 1] - EDGES[band]
        val withinBand = ((value - EDGES[band]) / span).coerceIn(0.0, 1.0)
        return ((band + withinBand) / BAND_COUNT).toFloat()
    }
}
