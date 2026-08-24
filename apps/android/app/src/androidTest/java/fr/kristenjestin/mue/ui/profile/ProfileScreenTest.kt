package fr.kristenjestin.mue.ui.profile

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.kristenjestin.mue.domain.logic.Bmi
import fr.kristenjestin.mue.domain.logic.BmiCalculator
import fr.kristenjestin.mue.domain.logic.BmiCategory
import fr.kristenjestin.mue.domain.logic.MueValidation
import fr.kristenjestin.mue.ui.awaitText
import fr.kristenjestin.mue.ui.components.BmiReferenceScale
import fr.kristenjestin.mue.ui.components.MueBmiCardTags
import fr.kristenjestin.mue.ui.components.MueSaveConfirmationLabel
import fr.kristenjestin.mue.ui.components.formatBmiValue
import fr.kristenjestin.mue.ui.theme.MueTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals

/**
 * The screen rendered from a fixed state, so every case PRD 15.2 distinguishes can be shown
 * on demand: a classified BMI, a value with no category, and no BMI at all.
 *
 * The full amber card lives on Progress now; what Profile keeps is the compact readout under
 * the Height field, whose job is to confirm that what is being typed took effect.
 */
@RunWith(AndroidJUnit4::class)
class ProfileScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val today = LocalDate.of(2026, 8, 23)

    @Test
    fun classifiedBmiShowsTheValueTheBandAndTheDisclaimer() {
        setContent(
            ProfileUiState(
                heightInput = "180",
                birthDate = LocalDate.of(1992, 4, 16),
                ageYears = 34,
                bmi = Bmi.Classified(23.0, BmiCategory.HEALTHY_WEIGHT),
            ),
        )

        composeRule.onNodeWithTag(ProfileTestTags.BMI_READOUT)
            .assertContentDescriptionEquals(bmiDescription(23.0, BmiCategory.HEALTHY_WEIGHT))
        composeRule.onNodeWithText(readout(23.0, BmiCategory.HEALTHY_WEIGHT)).assertExists()
        composeRule.onNodeWithText(BmiCalculator.DISCLAIMER).assertExists()
    }

    /** The full card and its reference bar belong to Progress; Profile never draws them. */
    @Test
    fun theFullCardStaysOnProgress() {
        setContent(
            ProfileUiState(
                heightInput = "180",
                bmi = Bmi.Classified(23.0, BmiCategory.HEALTHY_WEIGHT),
            ),
        )

        composeRule.onNodeWithTag(MueBmiCardTags.CARD).assertDoesNotExist()
        composeRule.onNodeWithTag(MueBmiCardTags.REFERENCE_BAR).assertDoesNotExist()
        BmiReferenceScale.SHORT_LABELS.forEach { label ->
            composeRule.onNodeWithText(label).assertDoesNotExist()
        }
    }

    @Test
    fun aValueOnlyBmiIsNeverNamed() {
        setContent(ProfileUiState(heightInput = "180", bmi = Bmi.ValueOnly(23.0)))

        composeRule.onNodeWithTag(ProfileTestTags.BMI_READOUT)
            .assertContentDescriptionEquals(bmiDescription(23.0, category = null))
        composeRule.onNodeWithText(readout(23.0, category = null)).assertExists()
        composeRule.onNodeWithText(BmiCalculator.DISCLAIMER).assertExists()
        composeRule.onNodeWithText(BmiCategory.HEALTHY_WEIGHT.label).assertDoesNotExist()
    }

    @Test
    fun noBmiMeansNoReadoutAtAll() {
        setContent(ProfileUiState())

        composeRule.onNodeWithTag(ProfileTestTags.BMI_READOUT).assertDoesNotExist()
        composeRule.onNodeWithText(BmiCalculator.DISCLAIMER).assertDoesNotExist()
    }

    @Test
    fun theAgeIsShownBesideTheDateOfBirth() {
        setContent(
            ProfileUiState(birthDate = LocalDate.of(1992, 4, 16), ageYears = 34),
        )

        composeRule.onNodeWithText("34 years").assertExists()
    }

    @Test
    fun anOutOfRangeHeightShowsItsExactMessage() {
        setContent(ProfileUiState(heightInput = "999", heightError = MueValidation.HEIGHT_ERROR))

        composeRule.onNodeWithText("Height must be between 120 and 230 cm").assertExists()
    }

    @Test
    fun anInvalidBirthDateShowsItsExactMessage() {
        setContent(ProfileUiState(birthDateError = MueValidation.BIRTH_DATE_ERROR))

        composeRule.onNodeWithText("Enter a valid date of birth").assertExists()
    }

    @Test
    fun typingAHeightIsReported() {
        val typed = mutableListOf<String>()
        setContent(ProfileUiState(heightInput = "180"), onHeightChange = { typed += it })

        composeRule
            .onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(ProfileTestTags.HEIGHT_FIELD)))
            .performTextReplacement("172")

        assertEquals(listOf("172"), typed)
    }

    @Test
    fun typingADisplayNameIsReported() {
        val typed = mutableListOf<String>()
        setContent(ProfileUiState(), onDisplayNameChange = { typed += it })

        composeRule
            .onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(ProfileTestTags.NAME_FIELD)))
            .performTextReplacement("Kris")

        assertEquals(listOf("Kris"), typed)
    }

    @Test
    fun theSaveButtonReportsATap() {
        var saves = 0
        setContent(ProfileUiState(), onSave = { saves++ })

        composeRule.onNodeWithTag(ProfileTestTags.SAVE_BUTTON).performScrollTo().performClick()

        assertEquals(1, saves)
    }

    @Test
    fun aSuccessfulSaveShowsItsConfirmation() {
        setContent(ProfileUiState(profileSaved = true))

        // The word arrives once the label has let go of `Save profile`, not on the frame of
        // the tap: the button fades one out before the other comes in.
        composeRule.awaitText(MueSaveConfirmationLabel)
    }

    @Test
    fun aStorageFailureIsShownInsteadOfAConfirmation() {
        setContent(ProfileUiState(saveError = ProfileViewModel.SAVE_ERROR))

        composeRule.onNodeWithText(ProfileViewModel.SAVE_ERROR).assertExists()
        composeRule.onNodeWithText(MueSaveConfirmationLabel).assertDoesNotExist()
    }

    @Test
    fun theHapticsToggleReflectsAndReportsItsState() {
        val toggles = mutableListOf<Boolean>()
        setContent(ProfileUiState(hapticsEnabled = true), onHapticsEnabledChange = { toggles += it })

        composeRule.onNodeWithTag(ProfileTestTags.HAPTICS_TOGGLE).performScrollTo().assertIsOn()
        composeRule.onNodeWithTag(ProfileTestTags.HAPTICS_TOGGLE).performClick()

        assertEquals(listOf(false), toggles)
    }

    @Test
    fun aDisabledHapticsPreferenceIsShownAsOff() {
        setContent(ProfileUiState(hapticsEnabled = false))

        composeRule.onNodeWithTag(ProfileTestTags.HAPTICS_TOGGLE).performScrollTo().assertIsOff()
    }

    @Test
    fun theExportActionReportsATap() {
        var exports = 0
        setContent(ProfileUiState(), onExport = { exports++ })

        composeRule.onNodeWithTag(ProfileTestTags.EXPORT_BUTTON).performScrollTo().performClick()

        assertEquals(1, exports)
    }

    @Test
    fun aRunningExportBlocksASecondTap() {
        setContent(ProfileUiState(export = ExportState.InProgress))

        composeRule.onNodeWithTag(ProfileTestTags.EXPORT_BUTTON)
            .performScrollTo()
            .assertIsNotEnabled()
    }

    @Test
    fun aFailedExportShowsAnErrorAndStaysRetryable() {
        setContent(ProfileUiState(export = ExportState.Failed(ProfileViewModel.EXPORT_ERROR)))

        composeRule.onNodeWithText(ProfileViewModel.EXPORT_ERROR).performScrollTo().assertExists()
        composeRule.onNodeWithTag(ProfileTestTags.EXPORT_BUTTON).performScrollTo().assertIsEnabled()
    }

    @Test
    fun thePrivacyCardIsAlwaysVisible() {
        setContent(ProfileUiState())

        composeRule.onNodeWithText("Why do we use this data?").performScrollTo().assertExists()
    }

    @Test
    fun theDateOfBirthFieldOpensTheDatePicker() {
        setContent(ProfileUiState())

        composeRule.onNodeWithTag(ProfileTestTags.BIRTH_DATE_FIELD).performScrollTo().performClick()

        composeRule.onNodeWithText("Use this date").assertExists()
    }

    @Test
    fun theDatePickerCanRemoveAnExistingBirthDate() {
        val changes = mutableListOf<LocalDate?>()
        setContent(
            ProfileUiState(birthDate = LocalDate.of(1992, 4, 16), ageYears = 34),
            onBirthDateChange = { changes += it },
        )

        composeRule.onNodeWithTag(ProfileTestTags.BIRTH_DATE_FIELD).performScrollTo().performClick()
        composeRule.onNodeWithText("Remove").performClick()

        assertEquals(listOf<LocalDate?>(null), changes)
    }

    @Test
    fun theScreenDoesNotDrawTheBottomTabBar() {
        setContent(ProfileUiState())

        listOf("Entry", "Progress").forEach { tab ->
            composeRule.onNodeWithText(tab).assertDoesNotExist()
        }
    }

    /*
     * The readout's two voices. Both go through `formatBmiValue` rather than being spelled
     * out, because the number follows the language of the device running the test (BR-010).
     */

    /** What is on screen: `BMI 23.0 · Healthy weight`. */
    private fun readout(value: Double, category: BmiCategory?): String =
        "BMI " + formatBmiValue(value, Locale.getDefault()) +
            (category?.let { " · ${it.label}" } ?: "")

    /** What TalkBack reads, which names the index in full. */
    private fun bmiDescription(value: Double, category: BmiCategory?): String =
        "Body mass index " + formatBmiValue(value, Locale.getDefault()) +
            (category?.let { ", ${it.label}" } ?: "")

    private fun setContent(
        state: ProfileUiState,
        onDisplayNameChange: (String) -> Unit = {},
        onHeightChange: (String) -> Unit = {},
        onBirthDateChange: (LocalDate?) -> Unit = {},
        onSave: () -> Unit = {},
        onSaveConfirmationFinished: () -> Unit = {},
        onHapticsEnabledChange: (Boolean) -> Unit = {},
        onExport: () -> Unit = {},
    ) {
        composeRule.setContent {
            MueTheme {
                ProfileScreen(
                    state = state,
                    onDisplayNameChange = onDisplayNameChange,
                    onHeightChange = onHeightChange,
                    onBirthDateChange = onBirthDateChange,
                    onSave = onSave,
                    onSaveConfirmationFinished = onSaveConfirmationFinished,
                    onHapticsEnabledChange = onHapticsEnabledChange,
                    onExport = onExport,
                    today = today,
                )
            }
        }
    }
}
