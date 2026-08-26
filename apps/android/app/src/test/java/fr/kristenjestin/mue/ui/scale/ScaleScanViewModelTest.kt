package fr.kristenjestin.mue.ui.scale

import fr.kristenjestin.mue.domain.model.ScaleDevice
import fr.kristenjestin.mue.ui.profile.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val NOW: Instant = Instant.parse("2026-08-26T08:00:00Z")

private const val DRIVER_ID = "fake-driver"
private const val ADVERTISED = "Fake Scale"
private const val MODEL = "Fake Scale One"

/**
 * Le flux d'appairage (FR-SCALE-011, 012) et le rattachement d'adresse (FR-SCALE-001).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScaleScanViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    // region le scan (FR-SCALE-011)

    @Test
    fun `les appareils reconnus portent le modèle identifié`() = runTest {
        val harness = harness()
        harness.viewModel.onScanRequested()

        harness.discovery.emit(advertisementOf("AA:01", ADVERTISED))

        val device = harness.state().recognised.single()
        assertEquals(MODEL, device.modelName)
        assertEquals(DRIVER_ID, device.driverId)
        assertTrue(device.selectable)
        assertTrue(harness.state().unsupported.isEmpty())
    }

    /**
     * FR-SCALE-011 : les autres sont listés plutôt que masqués. C'est ce qui transforme « le
     * Bluetooth est cassé » en « Mue ne connaît pas encore ce modèle ».
     */
    @Test
    fun `les appareils non pris en charge sont listés à part`() = runTest {
        val harness = harness()
        harness.viewModel.onScanRequested()

        harness.discovery.emit(advertisementOf("AA:01", ADVERTISED))
        harness.discovery.emit(advertisementOf("BB:02", "Living room speaker"))

        val state = harness.state()
        assertEquals(listOf("AA:01"), state.recognised.map { it.address })
        assertEquals(listOf("BB:02"), state.unsupported.map { it.address })
        assertEquals("Living room speaker", state.unsupported.single().name)
    }

    /** Un scan voit des dizaines d'objets anonymes ; une liste d'adresses ne dit rien à personne. */
    @Test
    fun `un appareil sans nom annoncé n'encombre pas la liste`() = runTest {
        val harness = harness()
        harness.viewModel.onScanRequested()

        harness.discovery.emit(advertisementOf("BB:02", null))
        harness.discovery.emit(advertisementOf("BB:03", "  "))

        assertTrue(harness.state().unsupported.isEmpty())
    }

    @Test
    fun `une même balance annoncée plusieurs fois ne figure qu'une fois`() = runTest {
        val harness = harness()
        harness.viewModel.onScanRequested()

        repeat(5) { harness.discovery.emit(advertisementOf("AA:01", ADVERTISED)) }

        assertEquals(1, harness.state().recognised.size)
    }

    /** FR-SCALE-011, à la lettre : trente secondes, puis l'offre de recommencer. */
    @Test
    fun `le scan s'arrête au bout de trente secondes`() = runTest {
        val harness = harness()
        harness.viewModel.onScanRequested()

        advanceTimeBy(ScaleScanViewModel.SCAN_DURATION_MILLIS - 1)
        assertTrue(harness.state().scanning)

        advanceTimeBy(2)

        val state = harness.state()
        assertFalse(state.scanning)
        assertTrue(state.started)
        assertTrue(state.finishedEmptyHanded)
        assertFalse(harness.discovery.isScanning)
    }

    @Test
    fun `recommencer repart d'une liste vide`() = runTest {
        val harness = harness()
        harness.viewModel.onScanRequested()
        harness.discovery.emit(advertisementOf("AA:01", ADVERTISED))
        advanceTimeBy(ScaleScanViewModel.SCAN_DURATION_MILLIS + 1)

        harness.viewModel.onScanRequested()

        val state = harness.state()
        assertTrue(state.scanning)
        assertTrue(state.recognised.isEmpty())
        assertEquals(2, harness.discovery.startCount)
    }

    /** FR-SCALE-025 : rien ne cherche tant qu'Android s'y oppose, et rien ne demande non plus. */
    @Test
    fun `aucun scan ne démarre tant qu'une condition Android s'y oppose`() = runTest {
        val harness = harness()
        harness.viewModel.onGateChanged(ScanGate.PERMISSION_NEEDED)

        harness.viewModel.onScanRequested()

        assertFalse(harness.state().scanning)
        assertFalse(harness.discovery.isScanning)
        assertEquals(0, harness.discovery.startCount)
    }

    @Test
    fun `la radio coupée pendant un scan l'arrête`() = runTest {
        val harness = harness()
        harness.viewModel.onScanRequested()

        harness.viewModel.onGateChanged(ScanGate.BLUETOOTH_OFF)

        assertFalse(harness.state().scanning)
        assertFalse(harness.discovery.isScanning)
    }

    // endregion

    // region l'association (FR-SCALE-012)

    @Test
    fun `choisir un appareil reconnu l'associe immédiatement`() = runTest {
        val harness = harness()
        harness.viewModel.onScanRequested()
        harness.discovery.emit(advertisementOf("AA:01", ADVERTISED))

        harness.viewModel.onDeviceSelected(harness.state().recognised.single())

        val stored = harness.scales.stored.single()
        assertEquals("id-1", stored.id)
        assertEquals(DRIVER_ID, stored.driverId)
        assertEquals("AA:01", stored.address)
        assertEquals(ADVERTISED, stored.advertisedName)
        // FR-SCALE-012 : le nom par défaut est celui du modèle.
        assertEquals(MODEL, stored.displayName)
        // Voir une annonce n'est pas un contact réussi : `Never connected` reste vrai.
        assertNull(stored.lastSeenAt)
        assertEquals(NOW, stored.createdAt)
    }

    @Test
    fun `une association réussie arrête le scan et se signale une fois`() = runTest {
        val harness = harness()
        harness.viewModel.onScanRequested()
        harness.discovery.emit(advertisementOf("AA:01", ADVERTISED))

        harness.viewModel.onDeviceSelected(harness.state().recognised.single())

        assertEquals("id-1", harness.state().pairedScaleId)
        assertFalse(harness.discovery.isScanning)

        harness.viewModel.onPairingHandled()
        assertNull(harness.state().pairedScaleId)
    }

    @Test
    fun `une balance déjà appairée sous cette adresse n'est pas sélectionnable`() = runTest {
        val harness = harness(
            devices = listOf(
                scaleDeviceOf(id = "a", address = "AA:01", displayName = "Bathroom scale"),
            ),
        )
        harness.viewModel.onScanRequested()
        harness.discovery.emit(advertisementOf("AA:01", ADVERTISED))

        val device = harness.state().recognised.single()
        assertEquals("Bathroom scale", device.alreadyPairedAs)
        assertFalse(device.selectable)

        harness.viewModel.onDeviceSelected(device)

        assertEquals(1, harness.scales.stored.size)
        assertTrue(harness.scales.writes.isEmpty(), "${harness.scales.writes}")
    }

    // endregion

    // region le rattachement d'adresse (FR-SCALE-001)

    /**
     * L'adresse enregistrée ne répond plus, le nom annoncé et le pilote correspondent : Mue
     * **propose**. Elle n'écrit rien tant que la question n'a pas de réponse.
     */
    @Test
    fun `un rattachement est proposé et jamais appliqué en silence`() = runTest {
        val harness = harness(
            devices = listOf(
                scaleDeviceOf(id = "a", address = "AA:OLD", displayName = "Bathroom scale"),
            ),
        )
        harness.viewModel.onScanRequested()
        harness.discovery.emit(advertisementOf("AA:NEW", ADVERTISED))

        val device = harness.state().recognised.single()
        assertEquals("a", device.reattachTo?.scaleId)
        assertEquals("Bathroom scale", device.reattachTo?.displayName)

        harness.viewModel.onDeviceSelected(device)

        val proposal = harness.state().proposal
        assertNotNull(proposal)
        assertEquals("AA:NEW", proposal.device.address)
        assertEquals("a", proposal.candidate.scaleId)
        // Rien n'a été écrit : ni rattachement, ni nouvelle balance.
        assertTrue(harness.scales.writes.isEmpty(), "${harness.scales.writes}")
        assertEquals("AA:OLD", harness.scales.stored.single().address)
    }

    @Test
    fun `rattacher met l'adresse à jour et conserve l'identité et l'historique`() = runTest {
        val harness = harness(
            devices = listOf(
                scaleDeviceOf(
                    id = "a",
                    address = "AA:OLD",
                    displayName = "Bathroom scale",
                    lastSeenAt = Instant.parse("2026-07-01T06:00:00Z"),
                ),
            ),
        )
        harness.viewModel.onScanRequested()
        harness.discovery.emit(advertisementOf("AA:NEW", ADVERTISED))
        harness.viewModel.onDeviceSelected(harness.state().recognised.single())

        harness.state()
        harness.viewModel.onReattachConfirmed()
        harness.state()

        val stored = harness.scales.stored.single()
        assertEquals("a", stored.id)
        assertEquals("Bathroom scale", stored.displayName)
        assertEquals("AA:NEW", stored.address)
        assertEquals(NOW, stored.lastSeenAt)
        assertEquals(listOf("markSeen:a:AA:NEW"), harness.scales.writes)
        assertEquals("a", harness.state().pairedScaleId)
    }

    /** Deux balances identiques dans un foyer : refuser le rattachement en appaire une seconde. */
    @Test
    fun `refuser le rattachement appaire un second appareil`() = runTest {
        val harness = harness(
            devices = listOf(
                scaleDeviceOf(id = "a", address = "AA:OLD", displayName = "Bathroom scale"),
            ),
        )
        harness.viewModel.onScanRequested()
        harness.discovery.emit(advertisementOf("AA:NEW", ADVERTISED))
        harness.viewModel.onDeviceSelected(harness.state().recognised.single())

        harness.state()
        harness.viewModel.onReattachDeclined()
        harness.state()

        assertEquals(listOf("a", "id-1"), harness.scales.stored.map { it.id })
        assertEquals("AA:OLD", harness.scales.stored.first().address)
        assertEquals("AA:NEW", harness.scales.stored.last().address)
    }

    @Test
    fun `fermer la question ne change rien`() = runTest {
        val harness = harness(
            devices = listOf(scaleDeviceOf(id = "a", address = "AA:OLD")),
        )
        harness.viewModel.onScanRequested()
        harness.discovery.emit(advertisementOf("AA:NEW", ADVERTISED))
        harness.viewModel.onDeviceSelected(harness.state().recognised.single())

        harness.state()
        harness.viewModel.onProposalDismissed()

        assertNull(harness.state().proposal)
        assertTrue(harness.scales.writes.isEmpty(), "${harness.scales.writes}")
    }

    /** L'adresse enregistrée répond : rien n'a changé, donc rien à rattacher. */
    @Test
    fun `aucun rattachement n'est proposé tant que l'adresse enregistrée répond`() = runTest {
        val harness = harness(
            devices = listOf(scaleDeviceOf(id = "a", address = "AA:OLD")),
        )
        harness.viewModel.onScanRequested()

        harness.discovery.emit(advertisementOf("AA:OLD", ADVERTISED))
        harness.discovery.emit(advertisementOf("AA:NEW", ADVERTISED))

        val newcomer = harness.state().recognised.first { it.address == "AA:NEW" }
        assertNull(newcomer.reattachTo)
    }

    /** Un pilote différent, même nom annoncé : ce n'est pas la même balance. */
    @Test
    fun `un pilote différent n'est jamais un candidat au rattachement`() = runTest {
        val harness = harness(
            devices = listOf(
                scaleDeviceOf(id = "a", driverId = "another-driver", address = "AA:OLD"),
            ),
        )
        harness.viewModel.onScanRequested()

        harness.discovery.emit(advertisementOf("AA:NEW", ADVERTISED))

        assertNull(harness.state().recognised.single().reattachTo)
    }

    // endregion

    private class Harness(
        val viewModel: ScaleScanViewModel,
        val scales: FakeScaleRepository,
        val discovery: FakeScaleDiscovery,
        private val scope: TestScope,
    ) {
        fun state(): ScaleScanUiState {
            scope.runCurrent()
            return viewModel.state.value
        }
    }

    private fun TestScope.harness(devices: List<ScaleDevice> = emptyList()): Harness {
        val scales = FakeScaleRepository(devices)
        val discovery = FakeScaleDiscovery()
        var minted = 0
        val viewModel = ScaleScanViewModel(
            scales = scales,
            drivers = FakeScaleDriverRegistry(
                listOf(FakeUiScaleDriver(DRIVER_ID, MODEL, ADVERTISED)),
            ),
            discovery = discovery,
            clock = { NOW },
            newId = { "id-${++minted}" },
        )
        val eager = UnconfinedTestDispatcher(testScheduler)
        backgroundScope.launch(eager) { viewModel.state.collect {} }
        runCurrent()
        return Harness(viewModel, scales, discovery, this)
    }
}
