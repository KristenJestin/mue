package fr.kristenjestin.mue.ui.navigation

import androidx.annotation.DrawableRes
import fr.kristenjestin.mue.ui.activity.ActivityIcons
import fr.kristenjestin.mue.ui.components.MueIcons
import fr.kristenjestin.mue.ui.food.FoodIcons

/**
 * The five permanent tabs of PRD_FOOD section 7, declared in bar order.
 *
 * The order is the navigation model itself: a tab change is "forward" when the destination
 * sits further right, which is what gives the transition its direction. `Activity` therefore
 * sits third on purpose, between `Progress` and `Profile`.
 *
 * PRD_FOOD 7 inserts `Food` **between `Activity` and `Profile`** rather than appending it, which
 * is what keeps `Profile` last where every user of the app already reaches for it — and keeps the
 * two recording tabs, what you did and what you ate, side by side.
 */
enum class MueDestination(val label: String, val iconName: String) {
    ENTRY("Entry", ActivityIcons.TAB_ENTRY),
    PROGRESS("Progress", ActivityIcons.TAB_PROGRESS),
    ACTIVITY("Activity", ActivityIcons.TAB_ACTIVITY),
    FOOD("Food", FoodIcons.TAB_FOOD),
    PROFILE("Profile", ActivityIcons.TAB_PROFILE),
    ;

    @get:DrawableRes
    val iconRes: Int get() = MueIcons.resource(iconName)
}
