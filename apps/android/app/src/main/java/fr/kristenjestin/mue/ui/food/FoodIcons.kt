package fr.kristenjestin.mue.ui.food

import fr.kristenjestin.mue.domain.model.FoodLogKind
import fr.kristenjestin.mue.domain.model.FoodSource
import fr.kristenjestin.mue.domain.model.MealSlot
import fr.kristenjestin.mue.ui.components.MueIcons

/**
 * The Lucide drawables of PRD_FOOD 19, named once so that the module's screens, the icon loader
 * and the resources agree without anyone having to coordinate — the same arrangement
 * `ActivityIcons` makes for its own module.
 *
 * These are resource *names* rather than `R.drawable` references: the vectors are imported one
 * by one, PRD_ACTIVITIES 14.1 rules out pulling in an icon library for the whole app, and naming
 * them as text keeps this file independent of the order in which the drawables land.
 *
 * PRD_FOOD 19 names two tables and nothing else — the four moments, and one stable icon per food
 * source — so those are the two functions below. Everything the module draws besides them is
 * chrome the app already imported (`search`, `plus`, `x`, `check`, `trash-2`, `chevron-right`,
 * `calendar-days`, `clock-3`, `sparkles`, `copy-plus`), which is why this object is short.
 */
object FoodIcons {

    /** The fifth permanent tab (PRD_FOOD 7). `utensils` is what the prototype's bar draws. */
    const val TAB_FOOD: String = "ic_utensils"

    /**
     * PRD_FOOD 19's "fruit": the `Snack` moment, and the generic catalogue as a provenance.
     *
     * Lucide draws it as `apple`, which is the name kept here — the PRD names the shape, the
     * icon family names the file, and inventing `ic_fruit` would hide which vector was imported.
     */
    const val APPLE: String = "ic_apple"

    /** PRD_FOOD 19's three other moments: sunrise, sun and moon (PRD_FOOD 10.1's order). */
    const val SUNRISE: String = "ic_sunrise"
    const val SUN: String = "ic_sun"
    const val MOON: String = "ic_moon"

    /**
     * A packaged product, both as a provenance and as the way one is added.
     *
     * The prototype draws `scan-line` on the action and `package-check` on the result, which
     * would be two glyphs for one idea; PRD_FOOD 18 makes the barcode itself the thing — the
     * camera has to have a manual alternative, and both paths end on the same code — so one
     * `barcode` covers the source, the scan and the field that replaces it.
     */
    const val BARCODE: String = "ic_barcode"

    /** A personal food (PRD_FOOD 9.3): something you wrote down, not something you looked up. */
    const val EGG: String = "ic_egg"

    /** A recipe, wherever one appears: a journal line, the catalogue, the `Use a recipe` path. */
    const val CHEF_HAT: String = "ic_chef_hat"

    /** PRD_FOOD 14: the cover of a recipe, and the only control that opens the camera. */
    const val CAMERA: String = "ic_camera"

    /** FR-RECIPE-005: a recipe is a favourite or it is not. Nothing else in Food is starred. */
    const val STAR: String = "ic_star"

    /**
     * PRD_FOOD 19: the four moments are told apart by their icon.
     *
     * `Snack` takes the fruit rather than a clock: PRD_FOOD 10.3 makes it the catch-all of the
     * day, so it is the one moment no hour of the sky can stand for.
     */
    fun forSlot(slot: MealSlot): String = when (slot) {
        MealSlot.BREAKFAST -> SUNRISE
        MealSlot.LUNCH -> SUN
        MealSlot.SNACK -> APPLE
        MealSlot.DINNER -> MOON
    }

    /**
     * PRD_FOOD 19: every provenance keeps the same icon wherever it is shown.
     *
     * It is provenance and never editability — PRD_FOOD 9.2 keeps [FoodSource.OPEN_FOOD_FACTS] on
     * a product whose values have been corrected — so the barcode stays on it for good.
     */
    fun forSource(source: FoodSource): String = when (source) {
        FoodSource.CIQUAL -> APPLE
        FoodSource.OPEN_FOOD_FACTS -> BARCODE
        FoodSource.CUSTOM -> EGG
    }

    /**
     * The same table read from a journal line (PRD_FOOD 10.2).
     *
     * [FoodLogKind.FOOD] answers the generic fruit because the line alone does not say where its
     * food came from; a caller holding the `Food` itself should ask [forSource] instead and will
     * get the barcode or the egg. `Quick` borrows the app's existing `zap`, which is already the
     * glyph the prototype puts on that path — a second name for one drawable would be a second
     * way to get it wrong.
     */
    fun forKind(kind: FoodLogKind): String = when (kind) {
        FoodLogKind.FOOD -> APPLE
        FoodLogKind.RECIPE -> CHEF_HAT
        FoodLogKind.QUICK -> MueIcons.ZAP
    }
}
