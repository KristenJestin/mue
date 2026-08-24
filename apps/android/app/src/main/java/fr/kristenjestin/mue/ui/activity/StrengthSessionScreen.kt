package fr.kristenjestin.mue.ui.activity

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The detailed strength editor: exercises, sets and their reps, loads and durations
 * (PRD FR-ACTIVITY-009).
 *
 * It edits the draft the log form already holds, so [onBack] returns to that form with
 * everything typed here still in place (PRD 9.1).
 */
@Composable
fun StrengthSessionScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
}
