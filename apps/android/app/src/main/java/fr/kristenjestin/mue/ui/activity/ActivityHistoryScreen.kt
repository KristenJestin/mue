package fr.kristenjestin.mue.ui.activity

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import fr.kristenjestin.mue.domain.model.ActivityId

/**
 * Every session ever recorded, grouped by month and unlimited in length
 * (PRD FR-ACTIVITY-012). The cards are the dashboard's own and open the same editor.
 */
@Composable
fun ActivityHistoryScreen(
    onBack: () -> Unit,
    onOpenSession: (ActivityId) -> Unit,
    modifier: Modifier = Modifier,
) {
}
