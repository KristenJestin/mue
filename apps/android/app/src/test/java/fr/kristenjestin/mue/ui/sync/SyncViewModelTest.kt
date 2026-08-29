package fr.kristenjestin.mue.ui.sync

import fr.kristenjestin.mue.data.local.database.FakeSyncDao
import fr.kristenjestin.mue.data.local.database.SyncStateEntity
import fr.kristenjestin.mue.data.pairing.CleartextPolicy
import fr.kristenjestin.mue.data.pairing.FakePairingApi
import fr.kristenjestin.mue.data.pairing.FakePairingStore
import fr.kristenjestin.mue.data.pairing.FakeTokenStore
import fr.kristenjestin.mue.data.pairing.ServerPairing
import fr.kristenjestin.mue.data.sync.FakeSyncStore
import fr.kristenjestin.mue.data.sync.ScriptedSyncApi
import fr.kristenjestin.mue.data.sync.SyncEngine
import fr.kristenjestin.mue.ui.profile.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * What `Server settings` starts with in its address field, which is now two rules instead of one.
 *
 * The `beta` variant may be built with a server address baked in (`default_server_address`, filled
 * from `local.properties` — see `build.gradle.kts`), because the owner runs the development server
 * on his own machine and reinstalls the beta often enough that retyping an IP address every time
 * is a real cost. Everything below exists to bound that convenience, and the bound is what the
 * tests are about rather than the convenience itself.
 *
 * The first group is the one that had to be written first: **with no default, nothing moves.**
 * `release`, `local` and `debug` compile the resource as the empty string, and a clone whose
 * `local.properties` says nothing builds a beta in the same position, so the un-configured
 * behaviour is the behaviour of almost every build this repository produces. If that group ever
 * goes red, the feature has leaked out of the one variant it was asked for.
 *
 * The second group is the bound proper: a real address is never overwritten. A stored pairing
 * wins, and so does anything the owner has already typed — which matters because
 * [SyncViewModel.onLeaveSettings] deliberately keeps the address, so [SyncViewModel.onEnterSettings]
 * runs again over a field that is not empty every time the screen is reopened.
 *
 * No emulator and no `Context`: the resource is read by [SyncViewModel.Factory] and handed down as
 * a parameter, which is the same arrangement `cleartext_server_permitted` uses to reach
 * `ServerAddresses.parse`, and it is what lets all of this be decided here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val paired = SyncStateEntity(
        serverUrl = "https://mue.home.arpa",
        serverName = "mue.home.arpa",
        accountId = "kris@example.org",
        deviceId = "device-1",
    )

    // --- no default: every build but a configured beta -------------------------------------------

    /** `release`, `local`, `debug`, and a `beta` built on a machine that configured nothing. */
    @Test
    fun withoutADefaultAnUnpairedPhoneOpensOnAnEmptyField() = runTest {
        val viewModel = viewModel(FakeSyncDao())

        viewModel.onEnterSettings()
        runCurrent()

        assertEquals("", viewModel.form.value.address)
    }

    @Test
    fun withoutADefaultAPairedPhoneStillOpensOnItsOwnServer() = runTest {
        val viewModel = viewModel(FakeSyncDao(paired))

        viewModel.onEnterSettings()
        runCurrent()

        assertEquals("https://mue.home.arpa", viewModel.form.value.address)
    }

    /**
     * Leaving and re-entering the screen keeps what was typed. It held before this change — the
     * seeding simply returned — and it has to keep holding for the same reason: the password is
     * what `onLeaveSettings` clears, and the address is what it deliberately does not.
     */
    @Test
    fun withoutADefaultATypedAddressSurvivesLeavingAndReturning() = runTest {
        val viewModel = viewModel(FakeSyncDao())

        viewModel.onAddressChange("https://192.168.1.42:3000")
        viewModel.onLeaveSettings()
        viewModel.onEnterSettings()
        runCurrent()

        assertEquals("https://192.168.1.42:3000", viewModel.form.value.address)
    }

    /**
     * A default made only of spaces is no default. `local.properties` is edited by hand and the
     * Gradle side already trims, so this is the second half of one decision rather than a second
     * one: blank means absent, on both sides of the resource.
     */
    @Test
    fun aBlankDefaultIsNotADefault() = runTest {
        val viewModel = viewModel(FakeSyncDao(), defaultServerAddress = "   ")

        viewModel.onEnterSettings()
        runCurrent()

        assertEquals("", viewModel.form.value.address)
    }

    // --- with a default: the beta the owner reinstalls --------------------------------------------

    @Test
    fun aDefaultFillsTheFieldOfAPhoneThatHasNeverBeenPaired() = runTest {
        val viewModel = viewModel(FakeSyncDao(), defaultServerAddress = "http://192.168.1.100:3000")

        viewModel.onEnterSettings()
        runCurrent()

        assertEquals("http://192.168.1.100:3000", viewModel.form.value.address)
    }

    /**
     * The assertion this whole design is built around. `sync_state.server_url` is where this
     * phone's history actually goes; a build-time guess replacing it would point the next
     * `Sign in` at a different machine, and would do it silently, on the one screen whose job is
     * to be unambiguous about which server this phone belongs to.
     */
    @Test
    fun aPairedPhoneSeesItsOwnServerAndNeverTheDefault() = runTest {
        val viewModel =
            viewModel(FakeSyncDao(paired), defaultServerAddress = "http://192.168.1.100:3000")

        viewModel.onEnterSettings()
        runCurrent()

        assertEquals("https://mue.home.arpa", viewModel.form.value.address)
    }

    /**
     * Typed, then the screen is reopened — which happens on every rotation and every trip through
     * `Profile`. The default has to lose to a field that already holds something, or it would
     * undo the typing at the moment the owner came back to finish it.
     */
    @Test
    fun aDefaultNeverReplacesAnAddressTheOwnerHasTyped() = runTest {
        val viewModel = viewModel(FakeSyncDao(), defaultServerAddress = "http://192.168.1.100:3000")

        viewModel.onAddressChange("https://mue.home.arpa")
        viewModel.onEnterSettings()
        runCurrent()

        assertEquals("https://mue.home.arpa", viewModel.form.value.address)
    }

    /**
     * The address and nothing else. An email and a password are credentials; a build artefact that
     * carried them would carry them into every copy of itself, and PRD 9.2 has the password kept
     * nowhere at all — least of all in a `values.xml`.
     */
    @Test
    fun aDefaultFillsTheAddressAndNoOtherField() = runTest {
        val viewModel = viewModel(FakeSyncDao(), defaultServerAddress = "http://192.168.1.100:3000")

        viewModel.onEnterSettings()
        runCurrent()

        val form = viewModel.form.value
        assertEquals("http://192.168.1.100:3000", form.address)
        assertEquals("", form.email)
        assertEquals("", form.password)
    }

    /**
     * The engine and the pairing are real objects because their types are, and neither is reached:
     * every test here calls the two lifecycle hooks of the screen and nothing else. Their seams
     * are wired to fakes that would fail loudly rather than plausibly if that ever stopped being
     * true.
     */
    private fun TestScope.viewModel(
        dao: FakeSyncDao,
        defaultServerAddress: String = "",
    ) = SyncViewModel(
        syncDao = dao,
        engine = SyncEngine(store = FakeSyncStore(), api = ScriptedSyncApi(), scope = this),
        pairing = ServerPairing(
            store = FakePairingStore(),
            tokenStore = FakeTokenStore(),
            api = FakePairingApi(),
            cleartext = CleartextPolicy.Refused,
            firstSync = { error("no pairing is attempted by these tests") },
        ),
        requestFollowUpSync = { error("no synchronisation is requested by these tests") },
        defaultServerAddress = defaultServerAddress,
    )
}
