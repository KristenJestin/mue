package fr.kristenjestin.mue.ui.navigation

/**
 * The three permanent tabs of PRD section 8, declared in bar order.
 *
 * The order is the navigation model itself: a tab change is "forward" when the destination
 * sits further right, which is what gives the transition its direction.
 */
enum class MueDestination(val label: String) {
    ENTRY("Entry"),
    PROGRESS("Progress"),
    PROFILE("Profile"),
}
