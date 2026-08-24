package fr.kristenjestin.mue.ui.activity

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import fr.kristenjestin.mue.domain.model.ActivityId

/**
 * The Activity tab: the current week and the five most recent sessions (PRD FR-ACTIVITY-001
 * and 002).
 *
 * [onSeeAll] opens the full history and is only wired when there are more than five sessions;
 * [onOpenSession] opens a session in the same editor the history uses.
 */
@Composable
fun ActivityScreen(
    onLogActivity: () -> Unit,
    onSeeAll: () -> Unit,
    onOpenSession: (ActivityId) -> Unit,
    modifier: Modifier = Modifier,
) {
}
