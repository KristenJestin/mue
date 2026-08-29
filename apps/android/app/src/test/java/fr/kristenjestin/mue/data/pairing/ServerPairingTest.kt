package fr.kristenjestin.mue.data.pairing

import fr.kristenjestin.mue.data.local.database.SyncStateEntity
import fr.kristenjestin.mue.data.sync.SyncOutcome
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sync PRD 9.2 and 9.3, proved without a socket, a Keystore or an emulator.
 *
 * The tests are grouped by the promise they keep rather than by the method they call, because the
 * promises are what the PRD is made of: nothing is stored unless everything worked, the password
 * is never one of the things stored, a second account never merges into this Room file, and a
 * disconnect deletes a token and nothing else.
 */
class ServerPairingTest {

    private val completed = SyncOutcome.Completed(
        recovered = 0,
        applied = 2,
        duplicates = 0,
        rejected = 0,
        deferred = 0,
        unreadable = 0,
        pages = 1,
        changes = 3,
        moreAvailable = false,
    )

    // --- the happy path ------------------------------------------------------------------------

    @Test
    fun aSuccessfulPairingStoresTheServerTheAccountAndTheBearer() = runTest {
        val store = FakePairingStore()
        val tokens = FakeTokenStore()
        val pairing = pairing(store, tokens)

        val result = pairing.pair("mue.home.arpa", "kris@example.org", "correct horse")

        val paired = assertIs<PairingResult.Paired>(result)
        assertEquals("mue.home.arpa", paired.serverName)
        assertEquals("bearer-1", tokens.token)

        val stored = requireNotNull(store.current)
        assertEquals("https://mue.home.arpa", stored.serverUrl)
        assertEquals("mue.home.arpa", stored.serverName)
        // Lowercased, because an email address is not case-sensitive and the guard compares it.
        assertEquals("kris@example.org", stored.accountId)
        assertEquals("device-1", stored.deviceId)
    }

    @Test
    fun aSuccessfulPairingRunsTheInitialSynchronisationAndReportsIt() = runTest {
        var runs = 0
        val pairing = pairing(firstSync = { runs++; completed })

        val paired = assertIs<PairingResult.Paired>(
            pairing.pair("https://mue.home.arpa", "kris@example.org", "correct horse"),
        )

        assertEquals(1, runs)
        assertEquals(completed, paired.firstSync)
    }

    /**
     * PRD 12.3: the cursor is the server's, and one kept across a disconnect can outlive the
     * journal it indexes. A pairing therefore starts reading from the beginning, which is free
     * because applying a page repeats no effect (FR-SYNC-006).
     */
    @Test
    fun aPairingResetsTheCursorAndTheLastSuccess() = runTest {
        val store = FakePairingStore(
            SyncStateEntity(
                accountId = "kris@example.org",
                deviceId = "device-existing",
                cursor = "eyJ2IjoxfQ==",
                lastSuccessAt = 1_700_000_000_000L,
                lastErrorCode = "client.unreachable",
                lastErrorMessage = "The server could not be reached.",
            ),
        )

        pairing(store).pair("https://mue.home.arpa", "kris@example.org", "correct horse")

        val stored = requireNotNull(store.current)
        assertNull(stored.cursor)
        assertNull(stored.lastSuccessAt)
        assertNull(stored.lastErrorCode)
        assertNull(stored.lastErrorMessage)
    }

    /** PRD 12.1: the device id is the phone, not the session, so a re-pairing keeps it. */
    @Test
    fun aRepairingKeepsTheDeviceIdentityTheServerAlreadyKnows() = runTest {
        val store = FakePairingStore(SyncStateEntity(deviceId = "device-existing"))

        pairing(store).pair("https://mue.home.arpa", "kris@example.org", "correct horse")

        assertEquals("device-existing", store.current?.deviceId)
    }

    /** The one-shot DataStore-to-Room copy must not run twice because a server was connected. */
    @Test
    fun aPairingPreservesTheProfileSeedingFlag() = runTest {
        val store = FakePairingStore(SyncStateEntity(profileSeeded = true))

        pairing(store).pair("https://mue.home.arpa", "kris@example.org", "correct horse")

        assertEquals(true, store.current?.profileSeeded)
    }

    /** A phone must identify what it is talking to before it offers a password to it. */
    @Test
    fun theServerIsIdentifiedBeforeAnyCredentialIsSent() = runTest {
        val api = FakePairingApi(onProbe = { throw PairingException(PairingFailure.HostNotFound("x")) })

        val result = pairing(api = api).pair("https://x", "kris@example.org", "correct horse")

        assertIs<PairingFailure.HostNotFound>(assertIs<PairingResult.Failed>(result).failure)
        assertTrue(api.signedIn.isEmpty())
    }

    // --- every failure has a name ----------------------------------------------------------------

    /**
     * The default here is [CleartextPolicy.Refused], which is `release`'s: a build that can be
     * published refuses `http://` before a socket exists, and `api.probed` being empty is what
     * says the refusal happened at the keyboard rather than on the wire.
     */
    @Test
    fun anInvalidAddressStopsBeforeTheNetwork() = runTest {
        val api = FakePairingApi()

        val result = pairing(api = api).pair("http://mue.home.arpa", "kris@example.org", "pw")

        assertIs<PairingFailure.InsecureScheme>(assertIs<PairingResult.Failed>(result).failure)
        assertTrue(api.probed.isEmpty())
    }

    /**
     * The owner's own build, end to end: the address he types is the address that is stored and
     * the address every later request is made against.
     *
     * `server_url` is asserted rather than just the outcome, because a pairing that succeeded
     * while quietly writing `https://` into the row would look identical from the screen and fail
     * at the first synchronisation — with `UntrustedCertificate`, about a server that has no
     * certificate because it was never asked for one.
     */
    @Test
    fun aBuildThatPermitsCleartextPairsWithTheAddressAsTyped() = runTest {
        val store = FakePairingStore()
        val api = FakePairingApi()

        val result = pairing(store = store, api = api, cleartext = CleartextPolicy.Permitted)
            .pair("http://192.168.1.100:3000", "kris@example.org", "correct horse")

        val paired = assertIs<PairingResult.Paired>(result)
        assertEquals("192.168.1.100:3000", paired.serverName)
        assertEquals("http://192.168.1.100:3000", requireNotNull(store.current).serverUrl)
        assertEquals(listOf("http://192.168.1.100:3000"), api.probed)
    }

    /** PRD 9.3's "the server has moved" path answers to the same policy, and not to another one. */
    @Test
    fun signingInAgainAtACleartextAddressFollowsTheSameRule() = runTest {
        val store = FakePairingStore(paired())

        val refused = pairing(store = store).reauthenticate("http://192.168.1.100:3000", "pw")
        assertIs<PairingFailure.InsecureScheme>(assertIs<PairingResult.Failed>(refused).failure)

        val permitted = pairing(store = store, cleartext = CleartextPolicy.Permitted)
            .reauthenticate("http://192.168.1.100:3000", "pw")
        assertIs<PairingResult.Paired>(permitted)
        assertEquals("http://192.168.1.100:3000", requireNotNull(store.current).serverUrl)
    }

    @Test
    fun missingCredentialsAreNamedRatherThanSentAsEmptyStrings() = runTest {
        val api = FakePairingApi()

        val blankEmail = pairing(api = api).pair("https://mue.home.arpa", " ", "pw")
        val blankPassword = pairing(api = api).pair("https://mue.home.arpa", "kris@example.org", "")

        assertEquals(
            PairingFailure.CredentialsMissing,
            assertIs<PairingResult.Failed>(blankEmail).failure,
        )
        assertEquals(
            PairingFailure.CredentialsMissing,
            assertIs<PairingResult.Failed>(blankPassword).failure,
        )
        assertTrue(api.probed.isEmpty())
    }

    @Test
    fun aRefusedPasswordStoresNothingAtAll() = runTest {
        val store = FakePairingStore()
        val tokens = FakeTokenStore()
        val api = FakePairingApi(
            onSignIn = { _, _, _ -> throw PairingException(PairingFailure.CredentialsRejected) },
        )

        val result = pairing(store, tokens, api)
            .pair("https://mue.home.arpa", "kris@example.org", "wrong")

        assertEquals(
            PairingFailure.CredentialsRejected,
            assertIs<PairingResult.Failed>(result).failure,
        )
        assertNull(tokens.token)
        assertNull(store.current)
        assertTrue(store.writes.isEmpty())
    }

    /**
     * The bearer is tried once before it is kept. A token stored without being tried is a paired
     * server that fails at every later synchronisation for a reason no screen could name.
     */
    @Test
    fun aBearerTheServerWillNotAcceptIsNeverStored() = runTest {
        val store = FakePairingStore()
        val tokens = FakeTokenStore()
        val api = FakePairingApi(
            onAccount = { _, _ ->
                throw PairingException(PairingFailure.SessionRejected("mue.home.arpa"))
            },
        )

        val result = pairing(store, tokens, api)
            .pair("https://mue.home.arpa", "kris@example.org", "correct horse")

        assertIs<PairingFailure.SessionRejected>(assertIs<PairingResult.Failed>(result).failure)
        assertNull(tokens.token)
        assertNull(store.current)
    }

    /** A row with a server and no token would be a pairing that can only fail. */
    @Test
    fun aFailedRowWriteGivesTheTokenBackAndRevokesTheSession() = runTest {
        val store = FakePairingStore().apply { failOnSave = true }
        val tokens = FakeTokenStore()
        val api = FakePairingApi()

        val result = pairing(store, tokens, api)
            .pair("https://mue.home.arpa", "kris@example.org", "correct horse")

        assertIs<PairingFailure.NotStored>(assertIs<PairingResult.Failed>(result).failure)
        assertNull(tokens.token)
        assertNull(store.current)
        assertEquals(listOf("https://mue.home.arpa" to "bearer-1"), api.revoked)
    }

    /**
     * A server too new for this build answers `upgrade_required` on the first pull. That is not a
     * failed pairing — the credentials were right and the session is real — so the pairing stands
     * and the outcome is carried up rather than swallowed.
     */
    @Test
    fun aServerThisBuildCannotReadStaysPairedAndSaysSo() = runTest {
        val store = FakePairingStore()
        val upgrade = SyncOutcome.UpgradeRequired(
            fr.kristenjestin.mue.data.remote.sync.MueErrorDto(
                code = "sync.upgrade_required",
                message = "Update Mue to read measurement v2.",
                retryable = false,
            ),
        )

        val result = pairing(store, firstSync = { upgrade })
            .pair("https://mue.home.arpa", "kris@example.org", "correct horse")

        assertEquals(upgrade, assertIs<PairingResult.Paired>(result).firstSync)
        assertEquals("https://mue.home.arpa", store.current?.serverUrl)
    }

    // --- PRD 9.3's trap ---------------------------------------------------------------------------

    @Test
    fun signingInAsAnotherAccountIsRefusedBeforeAnythingIsWritten() = runTest {
        val store = FakePairingStore(SyncStateEntity(accountId = "kris@example.org"))
        val tokens = FakeTokenStore()
        val api = FakePairingApi(
            onAccount = { _, _ -> PairingAccount("user_2", "someone@example.org", "Someone") },
        )

        val result = pairing(store, tokens, api)
            .pair("https://mue.home.arpa", "someone@example.org", "correct horse")

        val failure = assertIs<PairingFailure.DifferentAccount>(
            assertIs<PairingResult.Failed>(result).failure,
        )
        assertEquals("kris@example.org", failure.storedAccount)
        assertEquals("someone@example.org", failure.offeredAccount)
        assertNull(tokens.token)
        assertTrue(store.writes.isEmpty())
    }

    /** A session this app caused to exist and will not keep does not get to outlive the attempt. */
    @Test
    fun theSessionMintedForARefusedSecondAccountIsRevoked() = runTest {
        val store = FakePairingStore(SyncStateEntity(accountId = "kris@example.org"))
        val api = FakePairingApi(
            onAccount = { _, _ -> PairingAccount("user_2", "someone@example.org", null) },
        )

        pairing(store, api = api).pair("https://mue.home.arpa", "someone@example.org", "pw")

        assertEquals(listOf("https://mue.home.arpa" to "bearer-1"), api.revoked)
    }

    /** PRD 9.3: "se reconnecter au même compte reprend la synchronisation." */
    @Test
    fun theSameAccountInADifferentCaseIsTheSameAccount() = runTest {
        val store = FakePairingStore(SyncStateEntity(accountId = "kris@example.org"))

        val result = pairing(store).pair("https://mue.home.arpa", "KRIS@Example.org", "pw")

        assertIs<PairingResult.Paired>(result)
    }

    /**
     * PRD 18: a reinstalled server mints a new user id for the same person. The guard is on the
     * email precisely so that the case the PRD names as supported is not refused.
     */
    @Test
    fun aReinstalledServerWithANewUserIdForTheSamePersonIsAllowed() = runTest {
        val store = FakePairingStore(SyncStateEntity(accountId = "kris@example.org"))
        val api = FakePairingApi(
            onAccount = { _, _ -> PairingAccount("user_999", "kris@example.org", "Kris") },
        )

        assertIs<PairingResult.Paired>(
            pairing(store, api = api).pair("https://mue.home.arpa", "kris@example.org", "pw"),
        )
    }

    /** A phone whose data has never belonged to anyone is adopted by whoever signs in (PRD 21). */
    @Test
    fun aPhoneThatHasNeverBeenPairedIsAdoptedWithoutAQuestion() = runTest {
        val store = FakePairingStore(SyncStateEntity(accountId = null))

        assertIs<PairingResult.Paired>(
            pairing(store).pair("https://mue.home.arpa", "anyone@example.org", "pw"),
        )
    }

    // --- PRD 9.3's disconnection -------------------------------------------------------------------

    @Test
    fun disconnectingRevokesRemotelyAndDeletesTheLocalToken() = runTest {
        val store = FakePairingStore(paired())
        val tokens = FakeTokenStore("bearer-1")
        val api = FakePairingApi(revokeAnswers = true)

        val result = pairing(store, tokens, api).disconnect()

        assertEquals(DisconnectResult.Revoked("mue.home.arpa"), result)
        assertEquals(listOf("https://mue.home.arpa" to "bearer-1"), api.revoked)
        assertNull(tokens.token)
        assertEquals(1, tokens.clears)
    }

    /**
     * PRD 9.3: an unreachable server never blocks a disconnect. The local token goes, and the
     * result says the remote session is still open so the user can end it elsewhere.
     */
    @Test
    fun anUnreachableServerStillLosesTheLocalToken() = runTest {
        val store = FakePairingStore(paired())
        val tokens = FakeTokenStore("bearer-1")

        val result = pairing(store, tokens, FakePairingApi(revokeAnswers = false)).disconnect()

        assertEquals(DisconnectResult.LocalOnly("mue.home.arpa"), result)
        assertNull(tokens.token)
    }

    /** PRD 9.3: "aucune donnée métier locale n'est supprimée", and the guard survives with it. */
    @Test
    fun disconnectingKeepsTheAccountTheDeviceAndEverythingElse() = runTest {
        val store = FakePairingStore(paired())

        pairing(store, FakeTokenStore("bearer-1")).disconnect()

        val stored = requireNotNull(store.current)
        assertNull(stored.serverUrl)
        assertNull(stored.serverName)
        assertNull(stored.cursor)
        assertNull(stored.lastSuccessAt)
        assertEquals("kris@example.org", stored.accountId)
        assertEquals("device-existing", stored.deviceId)
        assertEquals(true, stored.profileSeeded)
    }

    /** After a disconnect the guard still refuses somebody else, which is the point of keeping it. */
    @Test
    fun aDisconnectDoesNotReopenTheDoorToASecondAccount() = runTest {
        val store = FakePairingStore(paired())
        val tokens = FakeTokenStore("bearer-1")
        val api = FakePairingApi(
            onAccount = { _, _ -> PairingAccount("user_2", "someone@example.org", null) },
        )
        val pairing = pairing(store, tokens, api)

        pairing.disconnect()
        val result = pairing.pair("https://mue.home.arpa", "someone@example.org", "pw")

        assertIs<PairingFailure.DifferentAccount>(assertIs<PairingResult.Failed>(result).failure)
    }

    @Test
    fun disconnectingWithNoServerIsNotAnError() = runTest {
        assertEquals(DisconnectResult.NotPaired, pairing(FakePairingStore()).disconnect())
    }

    /**
     * A Keystore key invalidated by a lock-screen change leaves a server row with no readable
     * token. There is nothing to revoke, and a disconnect that waited for a network answer it
     * could never send would be stuck for ever.
     */
    @Test
    fun aPairingWhoseTokenIsUnreadableStillDisconnectsCleanly() = runTest {
        val store = FakePairingStore(paired())
        val api = FakePairingApi()

        val result = pairing(store, FakeTokenStore(null), api).disconnect()

        assertEquals(DisconnectResult.Revoked("mue.home.arpa"), result)
        assertTrue(api.revoked.isEmpty())
    }

    // --- signing in again, without giving the pairing up ---------------------------------------

    /**
     * The defect this section was written for.
     *
     * An account recreated on the server leaves `sync_state.last_error_code` at
     * `auth.unauthenticated` and every later sync refused. The address is right, the account is
     * right, and only the bearer is stale — so the fix is a password, not a disconnection and a
     * retyped address, email and password to rebuild a row that was already correct.
     */
    @Test
    fun signingInAgainReplacesTheBearerAndKeepsEverythingElse() = runTest {
        val store = FakePairingStore(
            paired().copy(
                lastErrorCode = "auth.unauthenticated",
                lastErrorMessage = "Sign in to synchronise.",
            ),
        )
        val tokens = FakeTokenStore("stale-bearer")
        val pairing = pairing(store, tokens)

        val result = pairing.reauthenticate("https://mue.home.arpa", "correct horse")

        assertIs<PairingResult.Paired>(result)
        assertEquals("bearer-1", tokens.token)

        val stored = requireNotNull(store.current)
        assertEquals("https://mue.home.arpa", stored.serverUrl)
        assertEquals("kris@example.org", stored.accountId)
        // The phone is the same phone, so the server keeps knowing it as the same device.
        assertEquals("device-existing", stored.deviceId)
        assertTrue(stored.profileSeeded)
        // The refusal is cleared by the thing that fixed it, and not left to the next run.
        assertNull(stored.lastErrorCode)
        assertNull(stored.lastErrorMessage)
    }

    /** The email is never asked for: it is the one this phone's data already belongs to. */
    @Test
    fun signingInAgainUsesTheStoredAccountAndNotOneThatWasTyped() = runTest {
        val api = FakePairingApi()

        pairing(FakePairingStore(paired()), FakeTokenStore("stale"), api)
            .reauthenticate("https://mue.home.arpa", "correct horse")

        assertEquals(
            listOf(Triple("https://mue.home.arpa", "kris@example.org", "correct horse")),
            api.signedIn,
        )
    }

    /**
     * PRD 9.3 forbids merging another account's data; it does not forbid a server moving.
     *
     * A home router hands out a new address and the development certificate is issued for an IP,
     * so this is the ordinary case — and before there was a sign-in on the paired screen it could
     * only be reached by disconnecting first.
     */
    @Test
    fun signingInAgainMayMoveTheServerBecauseAnAddressIsNotAnAccount() = runTest {
        val store = FakePairingStore(paired())

        val result = pairing(store, FakeTokenStore("stale")).reauthenticate(
            "https://192.168.1.100:3000",
            "correct horse",
        )

        assertIs<PairingResult.Paired>(result)
        val stored = requireNotNull(store.current)
        assertEquals("https://192.168.1.100:3000", stored.serverUrl)
        assertEquals("192.168.1.100:3000", stored.serverName)
        assertEquals("kris@example.org", stored.accountId)
    }

    /**
     * PRD 9.3's guard is not bypassed by the shorter path.
     *
     * Signing in again has no email parameter, so the only way another account can arrive is a
     * server answering with a different one — and that is refused before a byte is stored,
     * exactly as a full pairing refuses it.
     */
    @Test
    fun signingInAgainRefusesAnAddressWhereTheAccountTurnsOutToBeSomebodyElse() = runTest {
        val store = FakePairingStore(paired())
        val tokens = FakeTokenStore("stale")
        val api = FakePairingApi(
            onAccount = { _, _ ->
                PairingAccount(id = "user_2", email = "someone@example.org", displayName = null)
            },
        )

        val result = pairing(store, tokens, api).reauthenticate("https://elsewhere.arpa", "pw")

        val failed = assertIs<PairingResult.Failed>(result)
        assertIs<PairingFailure.DifferentAccount>(failed.failure)
        // Nothing moved: the old address, the old account and even the old, useless bearer.
        assertEquals("https://mue.home.arpa", store.current?.serverUrl)
        assertEquals("kris@example.org", store.current?.accountId)
        assertEquals("stale", tokens.token)
        // And the session that refusal caused to exist does not outlive it (PRD 15.3).
        assertEquals(listOf("https://elsewhere.arpa" to "bearer-1"), api.revoked)
    }

    /** A wrong password is named as a wrong password, and changes nothing. */
    @Test
    fun aWrongPasswordLeavesTheSignedOutPairingExactlyAsItWas() = runTest {
        val store = FakePairingStore(paired())
        val tokens = FakeTokenStore("stale")
        val api = FakePairingApi(
            onSignIn = { _, _, _ -> throw PairingException(PairingFailure.CredentialsRejected) },
        )

        val result = pairing(store, tokens, api).reauthenticate("https://mue.home.arpa", "wrong")

        val failed = assertIs<PairingResult.Failed>(result)
        assertEquals(PairingFailure.CredentialsRejected, failed.failure)
        assertTrue(store.writes.isEmpty())
        assertEquals("stale", tokens.token)
    }

    /**
     * There is nobody to sign in as, so the screen is told so rather than shown a form it has no
     * email for. `Disconnect server` is on the same card, which is what the message names.
     */
    @Test
    fun aPairingThatNeverLearnedItsAccountSaysSoRatherThanGuessing() = runTest {
        val store = FakePairingStore(paired().copy(accountId = "  "))
        val api = FakePairingApi()

        val result = pairing(store, FakeTokenStore("stale"), api)
            .reauthenticate("https://mue.home.arpa", "correct horse")

        val failed = assertIs<PairingResult.Failed>(result)
        assertEquals(PairingFailure.AccountUnknown, failed.failure)
        // No password was ever offered to the server, and no probe was made.
        assertTrue(api.signedIn.isEmpty())
        assertTrue(api.probed.isEmpty())
    }

    /** An emptied address field is named, not silently replaced by the one already stored. */
    @Test
    fun anEmptyAddressIsRefusedByNameRatherThanFilledInBehindTheUser() = runTest {
        val result = pairing(FakePairingStore(paired()), FakeTokenStore("stale"))
            .reauthenticate("   ", "correct horse")

        val failed = assertIs<PairingResult.Failed>(result)
        assertEquals(PairingFailure.AddressMissing, failed.failure)
    }

    /**
     * The message a screen shows must name a control that screen has.
     *
     * Signing in again happens on a card with a server address, a password and nothing else: the
     * account is `sync_state.account_id` and there is deliberately no field for a second one.
     * Delegating straight to [ServerPairing.pair] made an empty password answer with
     * `CredentialsMissing` — "Enter the email address and password of your Mue account" — over a
     * card with no email box. That is the fault this whole screen was rebuilt to remove,
     * reintroduced one call deeper, and it is why the check lives in `reauthenticate`.
     */
    @Test
    fun anEmptyPasswordDoesNotTellTheUserToFillInAFieldThatIsNotThere() = runTest {
        val api = FakePairingApi()

        val result = pairing(FakePairingStore(paired()), FakeTokenStore("stale"), api)
            .reauthenticate("https://mue.home.arpa", "")

        val failed = assertIs<PairingResult.Failed>(result)
        assertEquals(PairingFailure.PasswordMissing, failed.failure)
        assertFalse(failed.failure.message.contains("email", ignoreCase = true))
        // And nothing was attempted: no probe, no sign-in, no token touched.
        assertTrue(api.probed.isEmpty())
        assertTrue(api.signedIn.isEmpty())
    }

    /** The full form still names both, because it still has both fields. */
    @Test
    fun theUnpairedFormStillNamesBothCredentialsBecauseItHasBothFields() = runTest {
        val result = pairing().pair("https://mue.home.arpa", "kris@example.org", "")

        val failed = assertIs<PairingResult.Failed>(result)
        assertEquals(PairingFailure.CredentialsMissing, failed.failure)
    }

    /** An empty address outranks an empty password, as it does on the pairing form. */
    @Test
    fun anEmptyAddressIsNamedBeforeAnEmptyPassword() = runTest {
        val result = pairing(FakePairingStore(paired()), FakeTokenStore("stale"))
            .reauthenticate("", "")

        val failed = assertIs<PairingResult.Failed>(result)
        assertEquals(PairingFailure.AddressMissing, failed.failure)
    }

    // --- helpers ------------------------------------------------------------------------------------

    private fun paired() = SyncStateEntity(
        serverUrl = "https://mue.home.arpa",
        serverName = "mue.home.arpa",
        accountId = "kris@example.org",
        deviceId = "device-existing",
        cursor = "eyJ2IjoxfQ==",
        lastSuccessAt = 1_700_000_000_000L,
        profileSeeded = true,
    )

    /**
     * `Refused` by default, because that is `release`'s configuration and therefore the one every
     * case written before cleartext existed was asserting. A permissive case says so at its call
     * site (see the two above), so no test is about a build type by accident.
     */
    private fun pairing(
        store: PairingStore = FakePairingStore(),
        tokens: TokenStore = FakeTokenStore(),
        api: PairingApi = FakePairingApi(),
        firstSync: suspend () -> SyncOutcome = { completed },
        cleartext: CleartextPolicy = CleartextPolicy.Refused,
    ) = ServerPairing(
        store = store,
        tokenStore = tokens,
        api = api,
        cleartext = cleartext,
        firstSync = firstSync,
        newDeviceId = { "device-1" },
    )
}
