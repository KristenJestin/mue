package fr.kristenjestin.mue.ui.profile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.activity.ComponentActivity
import fr.kristenjestin.mue.ui.theme.MueTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La pile de l'onglet `Profile` (PRD_SCALE 8), pilotée sans ses écrans.
 *
 * Le contenu est laissé à l'appelant précisément pour que ce test existe : ce qui est vérifié ici
 * est la mécanique — une route à l'écran à la fois, un retour qui dépile, et un retour désactivé sur
 * le profil nu, d'où il appartient au châssis d'onglets et quitte le module.
 */
@RunWith(AndroidJUnit4::class)
class ProfileNavHostTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var stack: ProfileStack

    @Test
    fun theProfileIsWhatTheTabShows() {
        start()

        compose.onNodeWithTag(ProfileRoute.Profile.key).assertExists()
        compose.onNodeWithTag(ProfileRoute.Scales.key).assertDoesNotExist()
    }

    @Test
    fun pushingAScreenPutsItOnTop() {
        start()

        compose.runOnUiThread { stack.push(ProfileRoute.Scales) }
        compose.waitForIdle()

        compose.onNodeWithTag(ProfileRoute.Scales.key).assertExists()
        assertTrue(stack.canGoBack)
    }

    /** Le retour se déplace dans le module avant d'en sortir. */
    @Test
    fun backWithinTheModuleReturnsToTheProfile() {
        start()
        compose.runOnUiThread { stack.push(ProfileRoute.Scales) }
        compose.waitForIdle()

        pressBack()

        compose.onNodeWithTag(ProfileRoute.Profile.key).assertExists()
        assertEquals(listOf<ProfileRoute>(ProfileRoute.Profile), stack.entries)
    }

    /** Sur le profil nu, le retour appartient au châssis : la pile n'y répond pas. */
    @Test
    fun backOnTheBareProfileLeavesTheModule() {
        var reachedTheShell = false
        compose.setContent {
            stack = rememberProfileStack()
            BackHandler(enabled = true) { reachedTheShell = true }
            MueTheme {
                ProfileNavHost(stack = stack) { route ->
                    Box(Modifier.testTag(route.key)) { Text(route.key) }
                }
            }
        }
        compose.waitForIdle()

        pressBack()

        assertTrue(reachedTheShell)
    }

    /** Une balance oubliée referme sa fiche : deux dépilages successifs restent sur le profil. */
    @Test
    fun poppingNeverGoesPastTheProfile() {
        start()

        compose.runOnUiThread {
            stack.push(ProfileRoute.Scales)
            stack.pop()
            stack.pop()
        }
        compose.waitForIdle()

        assertEquals(listOf<ProfileRoute>(ProfileRoute.Profile), stack.entries)
        compose.onNodeWithTag(ProfileRoute.Profile.key).assertExists()
    }

    /**
     * Le retour doit atterrir sur un arbre stabilisé : le dispatcher toucherait sinon un
     * gestionnaire à qui personne n'a encore dit qu'il était actif.
     */
    private fun pressBack() {
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun start() {
        compose.setContent {
            stack = rememberProfileStack()
            MueTheme {
                ProfileNavHost(stack = stack) { route ->
                    Box(Modifier.testTag(route.key)) { Text(route.key) }
                }
            }
        }
        compose.waitForIdle()
    }
}
