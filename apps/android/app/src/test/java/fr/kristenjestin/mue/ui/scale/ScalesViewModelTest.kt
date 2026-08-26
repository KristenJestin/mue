package fr.kristenjestin.mue.ui.scale

import fr.kristenjestin.mue.domain.model.ScaleDevice
import fr.kristenjestin.mue.testing.LocaleRule
import fr.kristenjestin.mue.ui.profile.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val SEEN: Instant = Instant.parse("2026-08-25T07:12:00Z")

/**
 * `Profile > Scales` : la liste, le renommage, l'oubli et la présence (FR-SCALE-010, 013, 014).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScalesViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    @get:Rule
    val locale = LocaleRule(Locale.UK)

    // region la liste (FR-SCALE-013)

    @Test
    fun `chaque balance porte son nom, son modèle et son dernier contact`() = runTest {
        val harness = harness(
            devices = listOf(
                scaleDeviceOf(id = "a", displayName = "Bathroom scale", lastSeenAt = SEEN),
            ),
        )

        val scale = harness.state().scales.single()
        assertEquals("Bathroom scale", scale.displayName)
        assertEquals("Fake Scale One", scale.modelName)
        assertEquals(SEEN, scale.lastSeenAt)
    }

    @Test
    fun `une balance jamais jointe se lit Never connected`() = runTest {
        val harness = harness(devices = listOf(scaleDeviceOf(id = "a", lastSeenAt = null)))

        val line = harness.state().scales.single().statusLine(Locale.UK)
        assertTrue(line.startsWith(ScaleMessages.NEVER_CONNECTED), line)
        assertFalse(line.contains(ScaleMessages.LAST_SEEN_LABEL), line)
    }

    /**
     * PRD_SCALE 18.5 : sans scan, la ligne n'affirme rien sur la portée.
     *
     * `inRange` vaut `false` pour tout le monde quand aucun scan ne tourne, et ce `false` veut dire
     * « personne n'a regardé », pas « la balance est absente ». L'écrire `Not in range` ferait
     * passer une radio éteinte pour une balance endormie et désignerait le mauvais geste. Le dernier
     * contact, lui, reste vrai et reste affiché.
     */
    @Test
    fun `sans scan la ligne s'arrête au dernier contact`() = runTest {
        val harness = harness(devices = listOf(scaleDeviceOf(id = "a", lastSeenAt = SEEN)))

        val line = harness.state().scales.single()
            .statusLine(Locale.UK, presenceKnown = false)

        assertTrue(line.startsWith(ScaleMessages.LAST_SEEN_LABEL), line)
        assertFalse(line.contains(ScaleMessages.NOT_IN_RANGE), line)
        assertFalse(line.contains(ScaleMessages.IN_RANGE), line)
    }

    /** Le pilote a été retiré depuis l'appairage : la balance se lit, elle ne disparaît pas. */
    @Test
    fun `une balance dont le pilote n'existe plus reste dans la liste`() = runTest {
        val harness = harness(
            devices = listOf(scaleDeviceOf(id = "a", driverId = "driver-that-left")),
        )

        val scale = harness.state().scales.single()
        assertEquals(ScaleMessages.UNKNOWN_MODEL, scale.modelName)
    }

    // endregion

    // region la présence pendant que l'écran est ouvert (FR-SCALE-013)

    @Test
    fun `une balance annoncée pendant que l'écran est ouvert passe à portée`() = runTest {
        val harness = harness(
            devices = listOf(scaleDeviceOf(id = "a", address = "AA:BB:CC:DD:EE:01")),
        )

        assertFalse(harness.state().scales.single().inRange)

        harness.viewModel.onScreenVisible()
        harness.discovery.emit(advertisementOf("AA:BB:CC:DD:EE:01", "Fake Scale"))

        assertTrue(harness.state().scales.single().inRange)
    }

    @Test
    fun `fermer l'écran arrête le scan et oublie ce qui était à portée`() = runTest {
        val harness = harness(devices = listOf(scaleDeviceOf(id = "a")))
        harness.viewModel.onScreenVisible()
        harness.discovery.emit(advertisementOf("AA:BB:CC:DD:EE:01", "Fake Scale"))
        harness.state()

        harness.viewModel.onScreenHidden()

        assertFalse(harness.discovery.isScanning)
        assertFalse(harness.state().scales.single().inRange)
    }

    /**
     * La liste et la fiche s'appuient sur le même ViewModel et sont composées ensemble le temps
     * d'une transition. Le premier des deux à disparaître ne doit pas éteindre le scan de l'autre.
     */
    @Test
    fun `le scan survit à un écran qui s'en va tant qu'un autre le veut`() = runTest {
        val harness = harness(devices = listOf(scaleDeviceOf(id = "a")))

        harness.viewModel.onScreenVisible()
        harness.viewModel.onScreenVisible()
        harness.viewModel.onScreenHidden()

        assertTrue(harness.discovery.isScanning)

        harness.viewModel.onScreenHidden()

        assertFalse(harness.discovery.isScanning)
    }

    // endregion

    // region renommer (FR-SCALE-013)

    @Test
    fun `renommer ne touche que le nom`() = runTest {
        val harness = harness(
            devices = listOf(scaleDeviceOf(id = "a", displayName = "Fake Scale One")),
        )

        harness.viewModel.onRenamed("a", "  Bathroom scale  ")
        harness.state()

        assertEquals("Bathroom scale", harness.scales.stored.single().displayName)
        assertEquals(listOf("rename:a:Bathroom scale"), harness.scales.writes)
    }

    @Test
    fun `un nom vide laisse la balance telle quelle`() = runTest {
        val harness = harness(
            devices = listOf(scaleDeviceOf(id = "a", displayName = "Fake Scale One")),
        )

        harness.viewModel.onRenamed("a", "   ")
        harness.state()

        assertEquals("Fake Scale One", harness.scales.stored.single().displayName)
        assertTrue(harness.scales.writes.isEmpty(), "${harness.scales.writes}")
    }

    // endregion

    // region oublier (FR-SCALE-014, BR-SCALE-010)

    @Test
    fun `oublier passe par une confirmation`() = runTest {
        val harness = harness(devices = listOf(scaleDeviceOf(id = "a")))

        harness.viewModel.onForgetRequested("a")

        assertEquals("a", harness.state().forgetTarget?.id)
        assertEquals(1, harness.scales.stored.size)
        assertTrue(harness.scales.writes.isEmpty(), "${harness.scales.writes}")
    }

    @Test
    fun `garder la balance ne change rien`() = runTest {
        val harness = harness(devices = listOf(scaleDeviceOf(id = "a")))

        harness.viewModel.onForgetRequested("a")
        harness.viewModel.onForgetCancelled()

        assertNull(harness.state().forgetTarget)
        assertEquals(1, harness.scales.stored.size)
        assertTrue(harness.scales.writes.isEmpty(), "${harness.scales.writes}")
    }

    /**
     * BR-SCALE-010 : l'oubli supprime la balance **et rien d'autre**. Aucune mesure n'est touchée,
     * ce que ce test affirme en lisant la liste complète des écritures : `forget`, une fois, seule.
     */
    @Test
    fun `oublier une balance n'écrit rien d'autre que son oubli`() = runTest {
        val harness = harness(
            devices = listOf(scaleDeviceOf(id = "a"), scaleDeviceOf(id = "b", displayName = "Two")),
        )

        harness.viewModel.onForgetRequested("a")
        harness.viewModel.onForgetConfirmed()
        harness.state()

        assertEquals(listOf("forget:a"), harness.scales.writes)
        assertEquals(listOf("b"), harness.scales.stored.map { it.id })
        assertNull(harness.state().forgetTarget)
    }

    @Test
    fun `confirmer sans avoir demandé n'oublie rien`() = runTest {
        val harness = harness(devices = listOf(scaleDeviceOf(id = "a")))

        harness.viewModel.onForgetConfirmed()
        harness.state()

        assertTrue(harness.scales.writes.isEmpty(), "${harness.scales.writes}")
    }

    // endregion

    private class Harness(
        val viewModel: ScalesViewModel,
        val scales: FakeScaleRepository,
        val discovery: FakeScaleDiscovery,
        private val scope: TestScope,
    ) {
        fun state(): ScalesUiState {
            scope.runCurrent()
            return viewModel.state.value
        }
    }

    private fun TestScope.harness(
        devices: List<ScaleDevice> = emptyList(),
    ): Harness {
        val scales = FakeScaleRepository(devices)
        val discovery = FakeScaleDiscovery()
        val viewModel = ScalesViewModel(
            scales = scales,
            drivers = FakeScaleDriverRegistry(
                listOf(FakeUiScaleDriver("fake-driver", "Fake Scale One", "Fake Scale")),
            ),
            discovery = discovery,
        )
        val eager = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(eager) { viewModel.state.collect {} }
        runCurrent()
        return Harness(viewModel, scales, discovery, this)
    }
}
