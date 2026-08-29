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
 * What `Server settings` starts with in its two typed fields, and what it must never start with in
 * the third.
 *
 * The `beta` variant may be built with a server address and an account email baked in
 * (`default_server_address` and `default_account_email`, filled from `mue.beta.server` and
 * `mue.beta.email` in `local.properties` — see `build.gradle.kts`), because the owner runs the
 * development server on his own machine, recreates `mue_dev` with `docker compose down -v`, and
 * reinstalls the beta often enough that retyping an IP address and an email on a phone keyboard
 * every time is a real cost. Everything below exists to bound that convenience, and the bound is
 * what the tests are about rather than the convenience itself.
 *
 * The first group is the one that had to be written first, and it is now written twice — once per
 * key: **with no default, nothing moves.** `release`, `local` and `debug` compile both resources
 * as the empty string, and a clone whose `local.properties` says nothing builds a beta in the same
 * position, so the un-configured behaviour is the behaviour of almost every build this repository
 * produces. If that group ever goes red, the feature has leaked out of the one variant it was
 * asked for.
 *
 * The second group is the bound proper: a real value is never overwritten. A stored pairing wins
 * for both fields, and so does anything the owner has already typed — which matters because
 * [SyncViewModel.onLeaveSettings] deliberately keeps the address and the email, so
 * [SyncViewModel.onEnterSettings] runs again over fields that are not empty every time the screen
 * is reopened.
 *
 * The third group is one assertion repeated from several angles: **the password is never seeded.**
 * There is no parameter for it, no resource behind it and no key in `local.properties`, and the
 * tests state it anyway, because the reason is not a property of this class — it is that a
 * `resValue` ends up in an APK, and an APK is copied, kept and sent. `build.gradle.kts` carries
 * the argument; these keep it from being quietly relaxed here.
 *
 * No emulator and no `Context`: the resources are read by [SyncViewModel.Factory] and handed down
 * as parameters, which is the same arrangement `cleartext_server_permitted` uses to reach
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
     * The two keys are read independently, so an address configured alone stays alone. This is the
     * ordinary state of a machine that has `mue.beta.server` from before `mue.beta.email` existed,
     * and it must not be a half-filled form: `build.gradle.kts` resolves each key separately, and
     * [SyncViewModel] guards each field separately for the same reason.
     */
    @Test
    fun anAddressConfiguredAloneFillsTheAddressAlone() = runTest {
        val viewModel = viewModel(FakeSyncDao(), defaultServerAddress = "http://192.168.1.100:3000")

        viewModel.onEnterSettings()
        runCurrent()

        val form = viewModel.form.value
        assertEquals("http://192.168.1.100:3000", form.address)
        assertEquals("", form.email)
        assertEquals("", form.password)
    }

    // --- no email default: every build but a configured beta -------------------------------------

    /**
     * The first test of the second key, and the one that has to pass before any of the others
     * matter: **absent, the key changes nothing.** Every build but `beta` compiles
     * `default_account_email` as the empty string, and so does a `beta` on a machine whose
     * `local.properties` says nothing — which is every clone of this repository as it arrives.
     * If this goes red, `mue.beta.email` has stopped being optional.
     */
    @Test
    fun withoutADefaultAnUnpairedPhoneOpensOnAnEmptyEmailField() = runTest {
        val viewModel = viewModel(FakeSyncDao())

        viewModel.onEnterSettings()
        runCurrent()

        assertEquals("", viewModel.form.value.email)
    }

    /** The address key alone must not drag the email with it, and neither must a paired row. */
    @Test
    fun withoutADefaultAPairedPhoneStillOpensOnAnEmptyEmailField() = runTest {
        val viewModel = viewModel(FakeSyncDao(paired))

        viewModel.onEnterSettings()
        runCurrent()

        assertEquals("", viewModel.form.value.email)
    }

    @Test
    fun withoutADefaultATypedEmailSurvivesLeavingAndReturning() = runTest {
        val viewModel = viewModel(FakeSyncDao())

        viewModel.onEmailChange("kris@example.org")
        viewModel.onLeaveSettings()
        viewModel.onEnterSettings()
        runCurrent()

        assertEquals("kris@example.org", viewModel.form.value.email)
    }

    /** Blank is absent, on this side of the resource as on the Gradle side that trims it. */
    @Test
    fun aBlankEmailDefaultIsNotADefault() = runTest {
        val viewModel = viewModel(FakeSyncDao(), defaultAccountEmail = "   ")

        viewModel.onEnterSettings()
        runCurrent()

        assertEquals("", viewModel.form.value.email)
    }

    // --- with an email default: the beta the owner reinstalls -------------------------------------

    @Test
    fun anEmailDefaultFillsTheFieldOfAPhoneThatHasNeverBeenPaired() = runTest {
        val viewModel = viewModel(FakeSyncDao(), defaultAccountEmail = "kris@example.org")

        viewModel.onEnterSettings()
        runCurrent()

        assertEquals("kris@example.org", viewModel.form.value.email)
    }

    /**
     * The email's half of the assertion the address design is built around, and it is a different
     * shape: the address is *replaced* by `sync_state.server_url`, the email is simply *not
     * offered*.
     *
     * `sync_state.account_id` holds the paired account and this deliberately does not read it.
     * Filling the box from the stored row would be a change to `release`, `local` and `debug`
     * as well — they would start showing an account where they show nothing — and the emptiness of
     * the resource everywhere but `beta` is the only thing keeping this feature inside the one
     * variant that asked for it. Nothing is lost: `Sign in` reads the account from
     * `ServerPairing.reauthenticate` and never from this form.
     */
    @Test
    fun aPairedPhoneIsOfferedNoEmailAtAll() = runTest {
        val viewModel = viewModel(FakeSyncDao(paired), defaultAccountEmail = "seeded@mue.test")

        viewModel.onEnterSettings()
        runCurrent()

        assertEquals("", viewModel.form.value.email)
    }

    /**
     * Typed, then the screen is reopened — every rotation and every trip through `Profile`. An
     * account the owner has begun typing outranks the one the build guessed, exactly as an address
     * does, or the default would undo the typing at the moment he came back to finish it.
     */
    @Test
    fun anEmailDefaultNeverReplacesAnEmailTheOwnerHasTyped() = runTest {
        val viewModel = viewModel(FakeSyncDao(), defaultAccountEmail = "seeded@mue.test")

        viewModel.onEmailChange("someone.else@example.org")
        viewModel.onEnterSettings()
        runCurrent()

        assertEquals("someone.else@example.org", viewModel.form.value.email)
    }

    /** The other half of the independence: an email configured alone fills the email alone. */
    @Test
    fun anEmailConfiguredAloneFillsTheEmailAlone() = runTest {
        val viewModel = viewModel(FakeSyncDao(), defaultAccountEmail = "kris@example.org")

        viewModel.onEnterSettings()
        runCurrent()

        val form = viewModel.form.value
        assertEquals("", form.address)
        assertEquals("kris@example.org", form.email)
    }

    // --- and never the password -------------------------------------------------------------------

    /**
     * Both keys set, which is the fully configured beta the owner actually builds — and the
     * password box is still empty.
     *
     * There is no parameter that could fill it, so this cannot fail without someone having added
     * one; that is the point of writing it down. The reason is not about this class: a third
     * `resValue` would put the password into `res/values/values.xml` inside every APK, and an APK
     * is copied onto a phone, kept beside the PRDs and sent over a chat to be installed. An address
     * and an email survive being read out of a build someone else is holding. A password does not,
     * and PRD 9.2 has it typed at every pairing precisely so that no copy of it exists to be read.
     */
    @Test
    fun aFullyConfiguredBetaStillAsksForThePassword() = runTest {
        val viewModel = viewModel(
            FakeSyncDao(),
            defaultServerAddress = "http://192.168.1.100:3000",
            defaultAccountEmail = "kris@example.org",
        )

        viewModel.onEnterSettings()
        runCurrent()

        val form = viewModel.form.value
        assertEquals("http://192.168.1.100:3000", form.address)
        assertEquals("kris@example.org", form.email)
        assertEquals("", form.password)
    }

    /**
     * And leaving the screen still throws the password away while the two seeded fields stay.
     * [SyncViewModel.onLeaveSettings] is what makes the password's absence from the build
     * meaningful rather than incidental: one clears on the way out, the others deliberately do not.
     */
    @Test
    fun leavingTheScreenClearsThePasswordAndKeepsTheSeededFields() = runTest {
        val viewModel = viewModel(
            FakeSyncDao(),
            defaultServerAddress = "http://192.168.1.100:3000",
            defaultAccountEmail = "kris@example.org",
        )

        viewModel.onEnterSettings()
        runCurrent()
        viewModel.onPasswordChange("correct-horse-battery")
        viewModel.onLeaveSettings()

        val form = viewModel.form.value
        assertEquals("http://192.168.1.100:3000", form.address)
        assertEquals("kris@example.org", form.email)
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
        defaultAccountEmail: String = "",
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
        defaultAccountEmail = defaultAccountEmail,
    )
}
