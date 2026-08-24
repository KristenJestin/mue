package fr.kristenjestin.mue.ui.activity

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import fr.kristenjestin.mue.domain.model.ActivityId

/**
 * Choosing a preset and filling a session (PRD FR-ACTIVITY-004 to 008), and the same form
 * again when a session is edited (PRD 7).
 *
 * [sessionId] is null while creating, which is what tells `Save activity` from `Save changes`
 * and what decides whether `Delete activity` appears at all. [onOpenStrengthSession] leaves the
 * draft untouched: PRD 9.1 makes the detailed editor another view of the very same one.
 */
@Composable
fun LogActivityScreen(
    sessionId: ActivityId?,
    onBack: () -> Unit,
    onOpenStrengthSession: () -> Unit,
    onSaved: () -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
}
