package fr.kristenjestin.mue.ui.navigation

import androidx.annotation.DrawableRes
import fr.kristenjestin.mue.ui.activity.ActivityIcons
import fr.kristenjestin.mue.ui.components.MueIcons

/**
 * The four permanent tabs of PRD_ACTIVITIES section 7, declared in bar order.
 *
 * The order is the navigation model itself: a tab change is "forward" when the destination
 * sits further right, which is what gives the transition its direction. `Activity` therefore
 * sits third on purpose, between `Progress` and `Profile`.
 */
enum class MueDestination(val label: String, val iconName: String) {
    ENTRY("Entry", ActivityIcons.TAB_ENTRY),
    PROGRESS("Progress", ActivityIcons.TAB_PROGRESS),
    ACTIVITY("Activity", ActivityIcons.TAB_ACTIVITY),
    PROFILE("Profile", ActivityIcons.TAB_PROFILE),
    ;

    @get:DrawableRes
    val iconRes: Int get() = MueIcons.resource(iconName)
}
