package fr.kristenjestin.mue.ui.scale

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.kristenjestin.mue.ui.field
import fr.kristenjestin.mue.ui.theme.MueTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

private val SCALE = PairedScale(
    id = "a",
    displayName = "Bathroom scale",
    modelName = "Homebuds HB9027",
    driverId = "homebuds-hb9027",
    address = "FF:10:00:1F:52:C3",
    advertisedName = "Health Scale",
    lastSeenAt = Instant.parse("2026-08-25T07:12:00Z"),
    inRange = false,
)

/**
 * Ce que demande un appareil sous Android 12 ou plus (PRD_SCALE 16.1).
 *
 * Écrit en dur plutôt que lu de `ScalePermissions.REQUIRED` : le test doit vérifier que l'écran
 * affiche la liste qu'on lui donne, pas qu'il affiche la même liste que celle qu'il lirait.
 */
private val REQUIRED_PERMISSIONS = listOf(
    "android.permission.BLUETOOTH_SCAN",
    "android.permission.BLUETOOTH_CONNECT",
)

/**
 * La fiche d'une balance : renommer, diagnostiquer, oublier (FR-SCALE-013, 014).
 *
 * Le brouillon du nom est hissé dans le test, comme l'exige la convention du dépôt : c'est l'écran
 * sans état qui est piloté, pas le ViewModel derrière lui.
 */
@RunWith(AndroidJUnit4::class)
class ScaleDetailScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val savedNames = mutableListOf<String>()
    private var forgetRequested = 0
    private var forgetCancelled = 0
    private var forgetConfirmed = 0

    @Test
    fun theNameCanBeReplaced() {
        show(SCALE)

        compose.field(ScaleTestTags.RENAME_FIELD).performTextReplacement("Upstairs")
        compose.onNodeWithTag(ScaleTestTags.RENAME_CONFIRM).performScrollTo().performClick()

        assertEquals(listOf("Upstairs"), savedNames)
    }

    /**
     * FR-SCALE-013 : renommer est la seule chose que cet écran permette de *régler*, et elle vit
     * dans sa propre section — distincte du bloc technique, qui n'est là que pour être lu.
     */
    @Test
    fun renamingIsItsOwnSectionAndTheTechnicalBlockSetsNothing() {
        show(SCALE)

        compose.onNodeWithTag(ScaleTestTags.RENAME_SECTION).assertIsDisplayed()
        compose.onNodeWithText(ScaleMessages.RENAME_THIS_SCALE).assertIsDisplayed()
        // Le seul champ saisissable de l'écran est sous cette section, et il n'y en a qu'un.
        compose.onAllNodes(
            hasSetTextAction() and hasAnyAncestor(hasTestTag(ScaleTestTags.RENAME_SECTION)),
        ).assertCountEquals(1)

        // « Regroupées et présentées comme du diagnostic, pas comme un réglage » : rien de
        // saisissable sous cette carte, et la note qui le dit aussi pour un lecteur d'écran.
        compose.onAllNodes(
            hasSetTextAction() and hasAnyAncestor(hasTestTag(ScaleTestTags.DIAGNOSTICS)),
        ).assertCountEquals(0)
    }

    /** FR-SCALE-013 : adresse, nom annoncé et pilote, groupés et donnés comme du diagnostic. */
    @Test
    fun theTechnicalBlockShowsTheThreeValuesAndOffersNothingToChange() {
        show(SCALE)

        compose.onNodeWithTag(ScaleTestTags.DIAGNOSTICS).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(SCALE.address).assertIsDisplayed()
        compose.onNodeWithText(SCALE.advertisedName).assertIsDisplayed()
        compose.onNodeWithText(SCALE.driverId).assertIsDisplayed()
        compose.onNodeWithText(ScaleMessages.DIAGNOSTICS_TITLE).assertIsDisplayed()
        compose.onNodeWithText(ScaleMessages.DIAGNOSTICS_NOTE).assertIsDisplayed()
    }

    /**
     * FR-SCALE-013 : ce que cette version d'Android exige avant tout scan, dans le bloc de
     * diagnostic et nulle part ailleurs.
     *
     * C'est la seule ligne du bloc que personne ne peut retrouver pour son propre téléphone, et
     * elle reste un fait à lire : aucun bouton, aucune bascule, aucun jugement sur ce qui est
     * accordé — la carte ne dit pas si la permission est détenue, seulement laquelle est demandée.
     */
    @Test
    fun theTechnicalBlockNamesThePermissionsThisAndroidVersionAsksFor() {
        show(SCALE)

        compose.onNodeWithTag(ScaleTestTags.DIAGNOSTICS).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(ScaleMessages.DIAGNOSTICS_PERMISSIONS).assertIsDisplayed()
        compose.onNodeWithText("BLUETOOTH_SCAN, BLUETOOTH_CONNECT").assertIsDisplayed()
    }

    @Test
    fun theModelAndTheLastContactAreShown() {
        show(SCALE)

        compose.onNodeWithText(SCALE.modelName).assertIsDisplayed()
        compose.onNodeWithText(ScaleMessages.NOT_IN_RANGE).assertIsDisplayed()
    }

    /**
     * FR-SCALE-013 et PRD_SCALE 18.2 : hors de portée est l'état normal d'une balance endormie.
     *
     * Écrit en toutes lettres sur sa propre ligne, jamais porté par une couleur ou une pastille
     * (PRD_SCALE 20), et énoncé à plat — ce n'est pas une panne.
     */
    @Test
    fun theInRangeStateIsWrittenOutAndNeverStyledAsAFault() {
        show(SCALE)

        compose.onNodeWithTag(ScaleTestTags.DETAIL_STATUS)
            .performScrollTo()
            .assertTextEquals(ScaleMessages.NOT_IN_RANGE)
    }

    @Test
    fun aScaleInRangeSaysSoOnItsCard() {
        show(SCALE.copy(inRange = true))

        compose.onNodeWithTag(ScaleTestTags.DETAIL_STATUS)
            .performScrollTo()
            .assertTextEquals(ScaleMessages.IN_RANGE)
    }

    @Test
    fun aScaleThatWasNeverReachedSaysSo() {
        show(SCALE.copy(lastSeenAt = null))

        compose.onNodeWithText(ScaleMessages.NEVER_CONNECTED).assertIsDisplayed()
    }

    /**
     * FR-SCALE-014 : oublier demande une confirmation, donc un seul geste n'oublie rien.
     *
     * La question n'est pas encore posée tant que rien ne la porte dans l'état : c'est ce qui
     * garantit qu'elle survit à une rotation et qu'elle disparaît avec la balance qu'elle vise.
     */
    @Test
    fun forgettingAsksFirst() {
        show(SCALE)

        compose.onNodeWithTag(ScaleTestTags.FORGET_CONFIRMATION).assertDoesNotExist()
        compose.onNodeWithTag(ScaleTestTags.FORGET).performScrollTo().performClick()

        assertEquals(1, forgetRequested)
        assertEquals(0, forgetConfirmed)
    }

    /**
     * BR-SCALE-010 dans la confirmation elle-même : c'est cette phrase qui rend la question
     * acceptable sans réfléchir, et elle doit donc être à l'écran.
     *
     * « Every measurement it produced stays in your history » n'est pas une formule de politesse :
     * le schéma le tient par `ON DELETE SET NULL` sur `measurements.source_scale_id`, et une
     * confirmation qui promettrait moins ferait hésiter là où il n'y a rien à perdre.
     */
    @Test
    fun theConfirmationPromisesEveryMeasurementIsKept() {
        show(SCALE, forgetTarget = SCALE)

        compose.onNodeWithTag(ScaleTestTags.FORGET_CONFIRMATION).assertIsDisplayed()
        compose.onNodeWithText(ScaleMessages.FORGET_CONFIRMATION_TITLE).assertIsDisplayed()
        compose.onNodeWithText(ScaleMessages.FORGET_CONFIRMATION_BODY).assertIsDisplayed()
    }

    @Test
    fun theSafeAnswerKeepsTheScale() {
        show(SCALE, forgetTarget = SCALE)

        compose.onNodeWithTag(ScaleTestTags.KEEP_SCALE).assertHasClickAction().performClick()

        assertEquals(1, forgetCancelled)
        assertEquals(0, forgetConfirmed)
    }

    @Test
    fun confirmingForgetsIt() {
        show(SCALE, forgetTarget = SCALE)

        compose.onNodeWithTag(ScaleTestTags.CONFIRM_FORGET).performClick()

        assertEquals(1, forgetConfirmed)
    }

    /**
     * L'écran se referme sur une balance qui n'existe plus ; il ne doit rien dessiner d'elle.
     *
     * La fiche elle-même reste composée — c'est elle qui se referme, et une transition a besoin de
     * quelque chose à faire sortir —, mais plus une seule de ses sections ne parle d'un appareil
     * qui n'est plus enregistré. C'est aussi le cas d'une balance oubliée depuis un autre écran,
     * que rien n'a prévenu.
     */
    @Test
    fun aForgottenScaleLeavesNothingBehind() {
        show(scale = null)

        compose.onNodeWithTag(ScaleTestTags.DETAIL).assertIsDisplayed()
        compose.onNodeWithText(ScaleMessages.FORGET_THIS_SCALE).assertDoesNotExist()
        compose.onNodeWithTag(ScaleTestTags.DIAGNOSTICS).assertDoesNotExist()
        compose.onNodeWithTag(ScaleTestTags.RENAME_SECTION).assertDoesNotExist()
        compose.onNodeWithTag(ScaleTestTags.DETAIL_STATUS).assertDoesNotExist()
    }

    /** L'état est hissé ici : le brouillon du nom vit dans le test, jamais dans l'écran. */
    private fun show(scale: PairedScale?, forgetTarget: PairedScale? = null) {
        compose.setContent {
            var nameInput by remember { mutableStateOf(scale?.displayName.orEmpty()) }
            MueTheme {
                ScaleDetailContent(
                    scale = scale,
                    nameInput = nameInput,
                    requiredPermissions = REQUIRED_PERMISSIONS,
                    forgetTarget = forgetTarget,
                    onNameChange = { nameInput = it },
                    onSaveName = { savedNames += it },
                    onForgetRequested = { forgetRequested++ },
                    onForgetCancelled = { forgetCancelled++ },
                    onForgetConfirmed = { forgetConfirmed++ },
                    onBack = {},
                )
            }
        }
        compose.waitForIdle()
    }
}
