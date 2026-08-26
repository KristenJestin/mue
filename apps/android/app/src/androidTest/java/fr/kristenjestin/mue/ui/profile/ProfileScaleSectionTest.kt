package fr.kristenjestin.mue.ui.profile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.kristenjestin.mue.domain.logic.Bmi
import fr.kristenjestin.mue.domain.logic.BmiCategory
import fr.kristenjestin.mue.domain.model.Sex
import fr.kristenjestin.mue.ui.components.formatBmiValue
import fr.kristenjestin.mue.ui.scale.ScaleMessages
import fr.kristenjestin.mue.ui.scale.ScaleTestTags
import fr.kristenjestin.mue.ui.theme.MueTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.Locale

private val TODAY: LocalDate = LocalDate.of(2026, 8, 26)

/**
 * Le champ sexe et la section `Scales` sur `Profile` (FR-PROFILE-007, FR-SCALE-010).
 *
 * Deux affirmations sont ici plus que des vérifications d'affichage : que le sexe **ne soit pas**
 * dans le groupe de la taille et de la date de naissance, et que l'IMC ne bouge pas d'un pixel
 * quand il change. La première est une exigence de disposition que seul un test de disposition peut
 * tenir ; la seconde est ce qui rend crédible tout ce que l'écran dit de ce champ.
 */
@RunWith(AndroidJUnit4::class)
class ProfileScaleSectionTest {

    @get:Rule
    val compose = createComposeRule()

    private val chosen = mutableListOf<Sex?>()
    private var scalesOpened = 0
    private val hoisted = mutableStateOf(ProfileUiState())
    private var composed = false

    // region le sexe (FR-PROFILE-007)

    @Test
    fun theSexFieldSaysWhatItIsFor() {
        show(ProfileUiState())

        compose.onNodeWithTag(ScaleTestTags.SEX_SECTION).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(ScaleMessages.SEX_SECTION_TITLE).assertIsDisplayed()
        compose.onNodeWithText(ScaleMessages.SEX_LABEL).assertIsDisplayed()
        compose.onNodeWithText(ScaleMessages.SEX_SECTION_BODY).assertIsDisplayed()
    }

    /**
     * FR-PROFILE-007 : le sexe n'est **jamais** aux côtés de la taille et de la date de naissance.
     * Le test lit l'arbre plutôt que la copie : le champ est dans le groupe de la composition
     * corporelle, et ni la taille ni la date de naissance n'y sont.
     */
    @Test
    fun theSexFieldIsNotInTheSameGroupAsTheBmiInputs() {
        show(ProfileUiState())

        compose.onNode(
            hasTestTag(ScaleTestTags.SEX_FIELD) and
                hasAnyAncestor(hasTestTag(ScaleTestTags.SEX_SECTION)),
        ).assertExists()

        compose.onNode(
            hasTestTag(ProfileTestTags.HEIGHT_FIELD) and
                hasAnyAncestor(hasTestTag(ScaleTestTags.SEX_SECTION)),
        ).assertDoesNotExist()

        compose.onNode(
            hasTestTag(ProfileTestTags.BIRTH_DATE_FIELD) and
                hasAnyAncestor(hasTestTag(ScaleTestTags.SEX_SECTION)),
        ).assertDoesNotExist()
    }

    @Test
    fun theThreeValuesAreOfferedAndNotSetIsOneOfThem() {
        show(ProfileUiState())

        compose.onNodeWithText(ScaleMessages.FEMALE).assertHasClickAction()
        compose.onNodeWithText(ScaleMessages.MALE).assertHasClickAction()
        compose.onNodeWithText(ScaleMessages.SEX_NOT_SET).assertHasClickAction().assertIsSelected()
    }

    @Test
    fun choosingAValueReportsIt() {
        show(ProfileUiState())

        compose.onNodeWithText(ScaleMessages.FEMALE).performScrollTo().performClick()

        assertEquals(listOf<Sex?>(Sex.FEMALE), chosen)
    }

    /** Un champ facultatif qu'on ne peut plus vider n'est pas facultatif. */
    @Test
    fun theEmptyStateCanBeReached() {
        show(ProfileUiState(sex = Sex.MALE))

        compose.onNodeWithText(ScaleMessages.MALE).assertIsSelected()
        compose.onNodeWithText(ScaleMessages.SEX_NOT_SET).performScrollTo().performClick()

        assertEquals(listOf<Sex?>(null), chosen)
    }

    /**
     * FR-PROFILE-007 : **l'IMC n'utilise pas le sexe** et son affichage n'en dépend en rien. La
     * valeur et la catégorie sont lues avant et après, sur les mêmes données.
     */
    @Test
    fun theBmiReadoutNeverChangesWithTheSex() {
        val classified = Bmi.Classified(23.0, BmiCategory.HEALTHY_WEIGHT)
        val value = formatBmiValue(classified.value, Locale.getDefault())
        show(ProfileUiState(heightInput = "180", bmi = classified))

        compose.onNodeWithTag(ProfileTestTags.BMI_READOUT).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(value, substring = true).assertExists()

        hoisted.value = hoisted.value.copy(sex = Sex.FEMALE)
        compose.waitForIdle()
        compose.onNodeWithText(value, substring = true).assertExists()

        hoisted.value = hoisted.value.copy(sex = Sex.MALE)
        compose.waitForIdle()
        compose.onNodeWithText(value, substring = true).assertExists()
        compose.onNodeWithTag(ProfileTestTags.BMI_READOUT).assertIsDisplayed()
    }

    // endregion

    // region la section Scales (FR-SCALE-010)

    @Test
    fun theScalesSectionStatesTheAbsenceWithoutMakingItAProblem() {
        show(ProfileUiState(pairedScaleCount = 0))

        compose.onNodeWithTag(ScaleTestTags.PROFILE_SECTION).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(ScaleMessages.NO_SCALE_PAIRED).assertIsDisplayed()
    }

    @Test
    fun theScalesSectionCountsThemWhenThereAreSome() {
        show(ProfileUiState(pairedScaleCount = 2))

        compose.onNodeWithText(ScaleMessages.scalesPaired(2)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theScalesSectionOpensTheDedicatedScreen() {
        show(ProfileUiState(pairedScaleCount = 1))

        compose.onNodeWithTag(ScaleTestTags.PROFILE_SECTION).performScrollTo().performClick()

        assertEquals(1, scalesOpened)
    }

    // endregion

    private fun show(state: ProfileUiState) {
        hoisted.value = state
        if (composed) {
            compose.waitForIdle()
            return
        }
        composed = true
        compose.setContent {
            val current by hoisted
            MueTheme {
                ProfileScreen(
                    state = current,
                    onDisplayNameChange = {},
                    onHeightChange = {},
                    onBirthDateChange = {},
                    onSave = {},
                    onSaveConfirmationFinished = {},
                    onHapticsEnabledChange = {},
                    onExport = {},
                    today = TODAY,
                    onSexChange = { chosen += it },
                    onOpenScales = { scalesOpened++ },
                )
            }
        }
        compose.waitForIdle()
    }
}
