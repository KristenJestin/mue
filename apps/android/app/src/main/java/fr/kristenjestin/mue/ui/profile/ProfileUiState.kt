package fr.kristenjestin.mue.ui.profile

import androidx.compose.runtime.Immutable
import fr.kristenjestin.mue.domain.logic.Bmi
import fr.kristenjestin.mue.domain.model.UserPreferences
import java.io.File
import java.time.LocalDate

/**
 * Everything the Profile screen draws.
 *
 * The form fields are raw strings rather than parsed values: PRD 15.3 requires an invalid
 * entry to stay on screen exactly as typed so it can be corrected.
 */
@Immutable
data class ProfileUiState(
    val displayName: String = "",
    val heightInput: String = "",
    val birthDate: LocalDate? = null,
    /** Whole years derived from [birthDate]; never stored (PRD BR-005). */
    val ageYears: Int? = null,
    val heightError: String? = null,
    val birthDateError: String? = null,
    /**
     * Recomputed from the *form* and the latest measurement, so the card follows the height
     * being typed. The case matters: only [Bmi.Classified] may show the reference bar.
     */
    val bmi: Bmi = Bmi.Unavailable,
    val hapticsEnabled: Boolean = UserPreferences.DEFAULT.hapticsEnabled,
    /** Drives the transient `Profile saved ✓` label (PRD FR-PROFILE-003). */
    val profileSaved: Boolean = false,
    /** A storage failure, which PRD 15.4 forbids from looking like a success. */
    val saveError: String? = null,
    val export: ExportState = ExportState.Idle,
) {
    val bmiAvailable: Bmi.Available? get() = bmi as? Bmi.Available
}

/**
 * PRD 15.4 forbids a partial file or a false success, so the export has no "done" state:
 * it either hands a finished file to the share sheet or it reports [Failed].
 */
sealed interface ExportState {

    data object Idle : ExportState

    data object InProgress : ExportState

    data class Failed(val message: String) : ExportState
}

/** One-shot effects the screen must run with an Android `Context`. */
sealed interface ProfileEvent {

    /** The CSV is complete on disk and ready for `FileProvider` (PRD FR-CSV-001). */
    data class ShareCsv(val file: File) : ProfileEvent
}
