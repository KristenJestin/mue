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
 * What `Server settings` starts with in its three typed fields, and everything that bounds it.
 *
 * The `beta` variant may be built with a server address, an account email and an account password
 * baked in (`default_server_address`, `default_account_email` and `default_account_password`,
 * filled from `mue.beta.server`, `mue.beta.email` and `mue.beta.password` in `local.properties` —
 * see `build.gradle.kts`), because the owner runs the development server on his own machine,
 * recreates `mue_dev` with `docker compose down -v`, and reinstalls the beta often enough that
 * retyping an IP address, an email and a password on a phone keyboard every time is a real cost.
 * Everything below exists to bound that convenience, and the bound is what the tests are about
 * rather than the convenience itself.
 *
 * The third key is a credential and the build gives it away in clear; that was argued and accepted
 * in `build.gradle.kts` on the condition that it only ever holds a throwaway for a disposable
 * account on a server unreachable from anywhere but the owner's own network. **No test here can
 * hold that condition up** — it is about the value someone types into a file, not about this class
 * — so nothing below pretends to check it. What is checkable is the shape of the offer, and that
 * is what every test asserts.
 *
 * The first group is the one that had to be written first, and it is now written three times —
 * once per key: **with no default, nothing moves.** `release`, `local` and `debug` compile all
 * three resources as the empty string, and a clone whose `local.properties` says nothing builds a
 * beta in the same position, so the un-configured behaviour is the behaviour of almost every build
 * this repository produces. If that group ever goes red, the feature has leaked out of the one
 * variant it was asked for.
 *
 * The second group is the bound proper: a real value is never overwritten. A stored pairing wins
 * for all three fields, and so does anything the owner has already typed — which matters because
 * [SyncViewModel.onLeaveSettings] deliberately keeps the address and the email, so
 * [SyncViewModel.onEnterSettings] runs again over fields that are not empty every time the screen
 * is reopened.
 *
 * The third group is what is different about the password and only about the password:
 * [SyncViewModel.onLeaveSettings] clears it, so it is the one field the seeding can reach twice.
 * The tests state both halves — a password a person typed does not survive the screen, and what
 * comes back on the next visit is the build's constant and never that — because the first half is
 * the property `onLeaveSettings` exists for and the second is the consequence of a default that
 * did not exist when it was written.
 *
 * No emulator and no `Context`: the resources are read by [SyncViewModel.Factory] and handed down
 * as parameters, which is the same arrangement `cleartext_server_permitted` uses to reach
 * `ServerAddresses.parse`, and it is what lets all of this be decided here. The one guarantee that
 * cannot be decided here is that `release` carries none of the three, because that is a claim
 * about an artefact: `verifyReleaseCarriesNoBetaDefaults` in `build.gradle.kts` reads the release
 * APK's own resource table and fails the build instead.
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
     * The three keys are read independently, so an address configured alone stays alone. This is
     * the ordinary state of a machine that has `mue.beta.server` from before `mue.beta.email` and
     * `mue.beta.password` existed, and it must not be a half-filled form: `build.gradle.kts`
     * resolves each key separately, and [SyncViewModel] guards each field separately for the same
     * reason. The password's emptiness here is the one that has to keep holding on its own — an
     * owner who configured a server and nothing else has not consented to a credential in his
     * build.
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

    /** The second face of the independence: an email configured alone fills the email alone. */
    @Test
    fun anEmailConfiguredAloneFillsTheEmailAlone() = runTest {
        val viewModel = viewModel(FakeSyncDao(), defaultAccountEmail = "kris@example.org")

        viewModel.onEnterSettings()
        runCurrent()

        val form = viewModel.form.value
        assertEquals("", form.address)
        assertEquals("kris@example.org", form.email)
        assertEquals("", form.password)
    }

    // --- no password default: every build but a configured beta -----------------------------------

    /**
     * The first test of the third key, written before the key could do anything, and the one that
     * has to pass before any of the others matter: **absent, the key changes nothing.**
     *
     * It carries more than the other two of its kind. `release`, `local` and `debug` compile
     * `default_account_password` as the empty string, and so does a `beta` on a machine whose
     * `local.properties` says nothing — which is every clone of this repository as it arrives — so
     * this is the assertion that says the daily application still asks for a password like it
     * always has. If it goes red, a credential has reached a build that was never part of the
     * arbitration.
     */
    @Test
    fun withoutADefaultAnUnpairedPhoneOpensOnAnEmptyPasswordField() = runTest {
        val viewModel = viewModel(FakeSyncDao())

        viewModel.onEnterSettings()
        runCurrent()

        assertEquals("", viewModel.form.value.password)
    }

    /** Neither of the other two keys drags the password with it, and neither does a paired row. */
    @Test
    fun withoutADefaultAPairedPhoneStillOpensOnAnEmptyPasswordField() = runTest {
        val viewModel = viewModel(FakeSyncDao(paired))

        viewModel.onEnterSettings()
        runCurrent()

        assertEquals("", viewModel.form.value.password)
    }

    /**
     * The transposition that comes out the other way round, and it is the whole reason the third
     * group exists. A typed address and a typed email survive leaving the screen;
     * [SyncViewModel.onLeaveSettings] clears the password, so a typed password does not — and with
     * no key configured there is nothing to put back.
     */
    @Test
    fun withoutADefaultATypedPasswordDoesNotSurviveLeavingAndReturning() = runTest {
        val viewModel = viewModel(FakeSyncDao())

        viewModel.onPasswordChange("correct-horse-battery")
        viewModel.onLeaveSettings()
        viewModel.onEnterSettings()
        runCurrent()

        assertEquals("", viewModel.form.value.password)
    }

    /** Blank is absent, on this side of the resource as on the Gradle side that trims it. */
    @Test
    fun aBlankPasswordDefaultIsNotADefault() = runTest {
        val viewModel = viewModel(FakeSyncDao(), defaultAccountPassword = "   ")

        viewModel.onEnterSettings()
        runCurrent()

        assertEquals("", viewModel.form.value.password)
    }

    // --- with a password default: the beta the owner reinstalls ------------------------------------

    @Test
    fun aPasswordDefaultFillsTheFieldOfAPhoneThatHasNeverBeenPaired() = runTest {
        val viewModel = viewModel(FakeSyncDao(), defaultAccountPassword = "throwaway-beta-secret")

        viewModel.onEnterSettings()
        runCurrent()

        assertEquals("throwaway-beta-secret", viewModel.form.value.password)
    }

    /**
     * The password's half of the assertion the address design is built around, and it is the
     * email's shape rather than the address's: nothing is stored to replace it with, so the
     * offer is simply not made.
     *
     * A paired phone that was handed a password would be handed one for an account it may no
     * longer belong to — `Sign in` sends whatever is in this box to `reauthenticate`, against the
     * account in `sync_state.account_id` — and it would be handed one on the `release` and `local`
     * builds too the day the resource stopped being empty there. Two reasons, one line: the stored
     * row wins outright and [SyncViewModel.seedForm] returns before any default is consulted.
     */
    @Test
    fun aPairedPhoneIsOfferedNoPasswordAtAll() = runTest {
        val viewModel = viewModel(FakeSyncDao(paired), defaultAccountPassword = "throwaway-beta-secret")

        viewModel.onEnterSettings()
        runCurrent()

        assertEquals("", viewModel.form.value.password)
    }

    /**
     * Typed, then the screen is reopened — every rotation and every trip through `Profile`. A
     * password the owner has begun typing outranks the one the build baked in, exactly as an
     * address and an email do. This is the case that matters most of the three: the owner reaching
     * for this field at all means the compiled throwaway is *not* the credential he wants sent, so
     * overwriting it would be overwriting the correction.
     */
    @Test
    fun aPasswordDefaultNeverReplacesAPasswordTheOwnerHasTyped() = runTest {
        val viewModel = viewModel(FakeSyncDao(), defaultAccountPassword = "throwaway-beta-secret")

        viewModel.onPasswordChange("the-one-he-actually-means")
        viewModel.onEnterSettings()
        runCurrent()

        assertEquals("the-one-he-actually-means", viewModel.form.value.password)
    }

    /** The third face of the independence: a password configured alone fills the password alone. */
    @Test
    fun aPasswordConfiguredAloneFillsThePasswordAlone() = runTest {
        val viewModel = viewModel(FakeSyncDao(), defaultAccountPassword = "throwaway-beta-secret")

        viewModel.onEnterSettings()
        runCurrent()

        val form = viewModel.form.value
        assertEquals("", form.address)
        assertEquals("", form.email)
        assertEquals("throwaway-beta-secret", form.password)
    }

    // --- what leaving the screen still throws away ------------------------------------------------

    /**
     * All three keys set, which is the fully configured beta the owner actually builds.
     *
     * The assertion this test used to make was the opposite one — that the password box stayed
     * empty however much was configured — and it was right for as long as no resource could fill
     * it. What replaced it is not weaker, it is narrower: the fields carry exactly what the build
     * was given, and every other test in this class says where that offer stops.
     */
    @Test
    fun aFullyConfiguredBetaFillsAllThreeFields() = runTest {
        val viewModel = viewModel(
            FakeSyncDao(),
            defaultServerAddress = "http://192.168.1.100:3000",
            defaultAccountEmail = "kris@example.org",
            defaultAccountPassword = "throwaway-beta-secret",
        )

        viewModel.onEnterSettings()
        runCurrent()

        val form = viewModel.form.value
        assertEquals("http://192.168.1.100:3000", form.address)
        assertEquals("kris@example.org", form.email)
        assertEquals("throwaway-beta-secret", form.password)
    }

    /**
     * Leaving the screen still throws the password away while the two other seeded fields stay,
     * and it throws away a password the owner typed over the default rather than the default.
     *
     * This is the line [SyncViewModel.onLeaveSettings] was written for and the one the third key
     * did not get to relax: the view model belongs to the activity and outlives `Server settings`,
     * so a credential a person typed must not still be sitting in it afterwards. The default's
     * disclosure is a decision taken in `build.gradle.kts` about one throwaway string; it is not a
     * licence to keep everything else.
     */
    @Test
    fun leavingTheScreenClearsThePasswordAndKeepsTheSeededFields() = runTest {
        val viewModel = viewModel(
            FakeSyncDao(),
            defaultServerAddress = "http://192.168.1.100:3000",
            defaultAccountEmail = "kris@example.org",
            defaultAccountPassword = "throwaway-beta-secret",
        )

        viewModel.onEnterSettings()
        runCurrent()
        viewModel.onPasswordChange("the-one-he-actually-means")
        viewModel.onLeaveSettings()

        val form = viewModel.form.value
        assertEquals("http://192.168.1.100:3000", form.address)
        assertEquals("kris@example.org", form.email)
        assertEquals("", form.password)
    }

    /**
     * And what the next visit offers is the build's constant, never the one that was typed over it.
     *
     * The decision written out in [SyncViewModel.seedForm]: re-entering seeds the password again,
     * because the string being put back is one `getString` already read out of a resource table
     * the process carries for its whole life, so withholding it would protect nothing. What must
     * not come back — and this is the half the assertion is really about — is
     * `the-one-he-actually-means`, which was typed by a person and is gone.
     */
    @Test
    fun aTypedPasswordIsGoneOnReturnAndOnlyTheBuildsOwnDefaultComesBack() = runTest {
        val viewModel = viewModel(FakeSyncDao(), defaultAccountPassword = "throwaway-beta-secret")

        viewModel.onEnterSettings()
        runCurrent()
        viewModel.onPasswordChange("the-one-he-actually-means")
        viewModel.onLeaveSettings()
        viewModel.onEnterSettings()
        runCurrent()

        assertEquals("throwaway-beta-secret", viewModel.form.value.password)
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
        defaultAccountPassword: String = "",
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
        defaultAccountPassword = defaultAccountPassword,
    )
}
