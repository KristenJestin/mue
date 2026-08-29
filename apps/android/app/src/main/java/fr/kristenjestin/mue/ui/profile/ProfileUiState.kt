package fr.kristenjestin.mue.ui.profile

import androidx.compose.runtime.Immutable
import fr.kristenjestin.mue.domain.logic.Bmi
import fr.kristenjestin.mue.domain.model.Sex
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
     * Le sexe, facultatif (PRD_SCALE FR-PROFILE-007).
     *
     * `null` est l'état « non renseigné », qui est **valide** et ne bloque jamais l'enregistrement
     * du profil. Il est délibérément absent de tout ce qui touche à [bmi] : les catégories adultes
     * de PRD FR-BMI-002 sont les mêmes pour tout le monde, et ce champ ne sert qu'aux estimations
     * de composition corporelle de PRD_SCALE 13.2.
     */
    val sex: Sex? = null,
    /**
     * Combien de balances sont associées (FR-SCALE-010).
     *
     * `0` se lit `No scale paired`, qui est un état parfaitement normal : `Entry` reste strictement
     * l'écran du PRD socle sans balance (PRD_SCALE 18.1), et rien sur `Profile` ne le présente
     * comme une lacune.
     */
    val pairedScaleCount: Int = 0,
    /**
     * Recomputed from the *form* and the latest measurement, so the readout follows the
     * height being typed. The case matters: only [Bmi.Classified] may be named.
     */
    val bmi: Bmi = Bmi.Unavailable,
    val hapticsEnabled: Boolean = UserPreferences.DEFAULT.hapticsEnabled,
    /** Drives the transient `Saved` confirmation on the button (PRD FR-PROFILE-003). */
    val profileSaved: Boolean = false,
    /** Bumped on every successful save so the BMI readout can hop once (PRD 13). */
    val saveEchoCount: Int = 0,
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
