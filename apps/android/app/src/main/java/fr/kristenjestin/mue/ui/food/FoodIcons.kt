package fr.kristenjestin.mue.ui.food

import fr.kristenjestin.mue.domain.model.FoodLogKind
import fr.kristenjestin.mue.domain.model.FoodSource
import fr.kristenjestin.mue.domain.model.MealSlot
import fr.kristenjestin.mue.ui.activity.ActivityIcons
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
 * PRD_FOOD 19 names two tables and nothing else — the moments, and one stable icon per food
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

    /** PRD_FOOD 19's other moments: sunrise, sun and moon (PRD_FOOD 10.1's order). */
    const val SUNRISE: String = "ic_sunrise"
    const val SUN: String = "ic_sun"
    const val MOON: String = "ic_moon"

    /**
     * The two glyphs the six moments needed and PRD_FOOD 19's table of four did not have.
     *
     * That table — sunrise, sun, fruit, moon — was written for a day with one snack in it. With a
     * snack after each meal the fruit can only stand for one of the three, and two moments drawn
     * with the same glyph is exactly the defect the tab bar was fixed for: a reader tells them
     * apart by the word alone, and the glyph stops carrying anything.
     *
     * So the fruit stays on the afternoon `Snack`, which is the moment PRD_FOOD 19 named it for,
     * and the two new ones take the hour they belong to rather than a second food: a cup for the
     * mid-morning break, and the night sky for what is eaten after dinner.
     */
    const val COFFEE: String = "ic_coffee"
    const val MOON_STAR: String = "ic_moon_star"

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
     * The glyph beside a view's name in the switcher (PRD_FOOD 7).
     *
     * Not a fifth table but a reading of the two above: a recipe is a chef's hat wherever it
     * appears, and the generic catalogue is the fruit [forSource] already gives it — so the
     * switcher names the same things the module already names, and a reader who has seen a
     * recipe row recognises the view that holds them. `Day` takes the calendar the module's
     * own date sheet opens with, and `Trends` the chart the app's `Progress` tab already uses.
     */
    fun forView(view: FoodRoute.View): String = when (view) {
        FoodRoute.Day -> MueIcons.CALENDAR_DAYS
        FoodRoute.Trends -> ActivityIcons.TAB_PROGRESS
        FoodRoute.Recipes -> CHEF_HAT
        FoodRoute.Foods -> APPLE
    }

    /**
     * PRD_FOOD 19: the moments are told apart by their icon, and no two of them share one.
     *
     * `Snack` keeps the fruit rather than a clock: it is the afternoon moment PRD_FOOD 19 named
     * it for. The two moments that flank the day — the mid-morning break and what is eaten after
     * dinner — take an hour of the sky or a cup instead of a second food, so the six glyphs read
     * as six different times rather than as three fruits.
     */
    fun forSlot(slot: MealSlot): String = when (slot) {
        MealSlot.BREAKFAST -> SUNRISE
        MealSlot.MORNING_SNACK -> COFFEE
        MealSlot.LUNCH -> SUN
        MealSlot.SNACK -> APPLE
        MealSlot.DINNER -> MOON
        MealSlot.EVENING_SNACK -> MOON_STAR
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
