package fr.kristenjestin.mue.data.pairing

import fr.kristenjestin.mue.data.local.database.SyncStateEntity
import fr.kristenjestin.mue.data.sync.SyncOutcome
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L'appairage que la bêta fait toute seule au démarrage, et les quatre choses qui le bornent.
 *
 * Le propriétaire réinstalle la bêta souvent, n'a pas de parcours d'inscription et donnait déjà les
 * trois valeurs par `local.properties` (AGENTS.md §4.5) pour ne rien retaper. [BetaAutoPairing]
 * enlève l'écran et le bouton qui restaient. Tout ce fichier est à propos de ce que ça ne doit
 * *pas* faire, parce que la commodité, elle, tient en une ligne.
 *
 * Le premier groupe est celui qui a été écrit en premier et le seul qui protège une build
 * publiable : **sans les trois valeurs, rien ne part.** `release`, `local` et `debug` compilent les
 * trois ressources à la chaîne vide, comme une `beta` dont le `local.properties` ne dit rien — donc
 * comme presque toutes les builds que ce dépôt produit. Ce groupe n'assère pas seulement qu'aucun
 * appairage n'a lieu : il assère qu'aucun objet n'existe pour en tenter un, ce qui est la forme que
 * `BetaPairingDefaults` donne à la garantie. Un test qui se contenterait de compter les appels
 * resterait vert le jour où quelqu'un déplacerait la garde.
 *
 * Les groupes suivants sont les trois autres bornes : `sync_state` fait foi et un appairage
 * existant n'est jamais touché ; une seule tentative par lancement de processus, parce que Better
 * Auth limite le débit sur un seau partagé et qu'une boucle bloquerait l'appairage manuel du
 * propriétaire ; et un échec ne laisse rien derrière lui — ni jeton, ni ligne, ni relance — de
 * sorte que `Server settings` s'ouvre ensuite sur le formulaire pré-rempli d'avant
 * (`SyncViewModelTest.aFullyConfiguredBetaFillsAllThreeFields` décrit exactement cet état).
 *
 * Aucun émulateur et aucun `Context` : les ressources sont lues par `SyncContainer` et descendent
 * en paramètres, `ServerPairing` est le vrai objet derrière ses trois faux, et le journal est une
 * interface parce que `android.util.Log` lève dans un test JVM. La seule garantie qui ne se décide
 * pas ici est que `release` ne porte aucune des trois ressources, parce que c'est une affirmation
 * sur un artefact : `verifyReleaseCarriesNoBetaDefaults` relit la table de l'APK et fait échouer la
 * construction.
 */
class BetaAutoPairingTest {

    private val server = "https://mue.home.arpa"
    private val email = "beta@mue.test"
    private val password = "throwaway-beta-secret"

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

    // --- sans les trois valeurs, rien ne part -----------------------------------------------------

    /**
     * Le test qui protège la production, et il est posé sur les sept combinaisons incomplètes
     * plutôt que sur la seule combinaison vide : une machine qui a `mue.beta.server` d'avant que
     * `mue.beta.email` et `mue.beta.password` existent est un état ordinaire, pas une build à
     * moitié configurée, et c'est celui-là qui doit rester inerte sans que personne y pense.
     *
     * L'assertion est `null` et non « zéro appel ». Il n'y a pas d'objet, donc pas de code réseau à
     * atteindre, pas de `sync_state` à lire et pas de message à écrire ; compter les appels d'un
     * objet qui n'existe pas serait une assertion sur le mauvais fait.
     */
    @Test
    fun withoutTheThreeValuesThereIsNoObjectToRun() {
        val incomplete = listOf(
            Triple("", "", ""),
            Triple(server, "", ""),
            Triple("", email, ""),
            Triple("", "", password),
            Triple(server, email, ""),
            Triple(server, "", password),
            Triple("", email, password),
        )

        incomplete.forEach { (address, account, secret) ->
            assertNull(
                BetaPairingDefaults.of(address, account, secret),
                "\"$address\" / \"$account\" / \"$secret\" is not a fully configured beta",
            )
        }
    }

    /**
     * L'autre moitié, sans laquelle le test ci-dessus passerait sur une fonction qui rend toujours
     * `null` — c'est-à-dire sur une fonctionnalité qui ne marche jamais.
     */
    @Test
    fun theThreeValuesTogetherAreTheOnlyWayToAnObject() {
        val defaults = assertNotNull(BetaPairingDefaults.of(server, email, password))

        assertEquals(server, defaults.serverAddress)
        assertEquals(email, defaults.accountEmail)
        assertEquals(password, defaults.accountPassword)
    }

    /**
     * Blanc vaut absent, des deux côtés de la ressource : `localProperty` rogne côté Gradle et
     * `SyncViewModel.seedForm` lit blanc comme « aucun défaut ». Une troisième réponse ici ferait
     * partir un appairage sur une adresse faite d'espaces là où le formulaire, lui, ne proposerait
     * rien.
     */
    @Test
    fun aBlankValueIsAnAbsentValue() {
        assertNull(BetaPairingDefaults.of("   ", email, password))
        assertNull(BetaPairingDefaults.of(server, "   ", password))
        assertNull(BetaPairingDefaults.of(server, email, "   "))
    }

    /** Et le conteneur ne construit rien de plus : sans les trois, `MueApplication` n'appelle rien. */
    @Test
    fun anUnconfiguredBuildWiresNothingAtAll() {
        val api = FakePairingApi()
        val store = FakePairingStore()

        val automatic = autoPairing(store, api, serverAddress = "", accountEmail = "")

        assertNull(automatic)
        assertTrue(api.probed.isEmpty(), "no network call was made")
        assertTrue(api.signedIn.isEmpty(), "no credentials were offered")
        assertTrue(store.writes.isEmpty(), "no sync_state row was written")
    }

    // --- jamais sur un téléphone déjà appairé -----------------------------------------------------

    /**
     * `sync_state` fait foi, et il fait foi *avant* la première connexion.
     *
     * `ServerPairing.pair` accepterait volontiers ce ré-appairage — le garde de compte laisse
     * passer le même compte — et il remettrait le curseur à `null`, forçant la relecture du journal
     * entier, sur un téléphone qui n'avait besoin de rien. La borne n'est donc pas de la politesse
     * : c'est la même règle que `seedForm` applique au formulaire, un cran plus tôt.
     */
    @Test
    fun anAlreadyPairedPhoneIsNeverTouched() = runTest {
        val api = FakePairingApi()
        val tokens = FakeTokenStore("bearer-already-here")
        val store = FakePairingStore(
            SyncStateEntity(
                serverUrl = "https://mue.home.arpa",
                serverName = "mue.home.arpa",
                accountId = "kris@example.org",
                deviceId = "device-existing",
                cursor = "eyJ2IjoxfQ==",
            ),
        )
        val automatic = assertNotNull(autoPairing(store, api, tokens))

        val outcome = automatic.pairOnce()

        assertEquals(AutoPairingOutcome.AlreadyPaired, outcome)
        assertTrue(api.probed.isEmpty(), "an already paired phone is not even probed")
        assertTrue(api.signedIn.isEmpty(), "no credentials are offered over an existing pairing")
        assertTrue(api.revoked.isEmpty(), "and the session it holds is not disturbed")
        assertTrue(store.writes.isEmpty(), "the stored row is neither replaced nor refreshed")
        assertEquals("bearer-already-here", tokens.token)
    }

    // --- trois valeurs et un téléphone neuf : exactement une tentative ----------------------------

    /**
     * La commodité elle-même, et elle passe par le chemin du bouton : `ServerPairing.pair` est le
     * vrai objet, donc `probe` d'abord, bearer prouvé avant d'être gardé, ligne `sync_state`
     * écrite en entier et synchronisation initiale de PRD 9.2 dans la foulée. Un second chemin
     * réseau aurait été un second comportement à faire vieillir.
     */
    @Test
    fun anUnpairedPhoneIsPairedOnceWithTheConfiguredValues() = runTest {
        val api = FakePairingApi()
        val tokens = FakeTokenStore()
        val store = FakePairingStore()
        var firstSyncRuns = 0
        val automatic = assertNotNull(
            autoPairing(store, api, tokens, firstSync = { firstSyncRuns++; completed }),
        )

        val outcome = automatic.pairOnce()

        assertIs<AutoPairingOutcome.Paired>(outcome)
        assertEquals(listOf(server), api.probed)
        assertEquals(Triple(server, email, password), api.signedIn.single())
        assertEquals("bearer-1", tokens.token)
        assertEquals(server, store.writes.single().serverUrl)
        assertEquals(1, firstSyncRuns, "a successful pairing runs the initial synchronisation once")
    }

    /**
     * Une tentative par lancement de processus, et le drapeau est consommé avant même la lecture de
     * `sync_state` : ce qui doit être unique est la question posée, pas seulement l'appel réseau.
     */
    @Test
    fun aSecondCallInTheSameProcessAttemptsNothing() = runTest {
        val api = FakePairingApi()
        val store = FakePairingStore()
        val automatic = assertNotNull(autoPairing(store, api))

        assertIs<AutoPairingOutcome.Paired>(automatic.pairOnce())
        val second = automatic.pairOnce()

        assertEquals(AutoPairingOutcome.AlreadyAttempted, second)
        assertEquals(1, api.signedIn.size, "the second call offered nothing to the server")
        assertEquals(1, store.writes.size)
    }

    // --- un échec est silencieux, sans conséquence et sans relance ---------------------------------

    /**
     * Un mot de passe devenu faux — le cas que le propriétaire produit lui-même en recréant
     * `mue_dev` avec `docker compose down -v`.
     *
     * Rien n'est écrit, rien n'est gardé, et rien ne recommence. La deuxième moitié de l'assertion
     * est celle qui compte : Better Auth limite le débit sans en-tête d'adresse fiable, donc sur un
     * seau unique partagé, et une tentative par seconde ne raterait pas seulement son propre
     * appairage — elle bloquerait celui que le propriétaire tente à la main sur le même serveur,
     * c'est-à-dire précisément le recours que cet échec est censé laisser intact.
     */
    @Test
    fun aRefusedPasswordIsSilentAndIsNeverRetried() = runTest {
        val api = FakePairingApi(
            onSignIn = { _, _, _ -> throw PairingException(PairingFailure.CredentialsRejected) },
        )
        val tokens = FakeTokenStore()
        val store = FakePairingStore()
        val automatic = assertNotNull(autoPairing(store, api, tokens))

        val first = automatic.pairOnce()
        val second = automatic.pairOnce()

        assertIs<AutoPairingOutcome.Failed>(first)
        assertEquals(AutoPairingOutcome.AlreadyAttempted, second)
        assertEquals(1, api.signedIn.size, "one attempt, and the failure did not buy a second")
        assertNull(tokens.token, "nothing was kept")
        assertTrue(store.writes.isEmpty(), "and nothing was written")
        assertNull(
            store.current,
            "so sync_state stays absent and Server settings still opens on the seeded form",
        )
    }

    /**
     * Deux lancements de processus après un échec : une tentative chacun, jamais davantage dans un
     * même processus.
     *
     * Deux instances plutôt qu'une, parce que c'est ce qu'un second démarrage est — `MueApplication`
     * reconstruit son conteneur, donc un `AtomicBoolean` neuf. Le point de ce test est que l'unicité
     * est bornée au processus et pas au téléphone : un échec ne condamne pas la bêta jusqu'à la
     * réinstallation, il coûte un redémarrage.
     */
    @Test
    fun twoProcessStartsAfterAFailureAttemptOnceEach() = runTest {
        val api = FakePairingApi(
            onSignIn = { _, _, _ -> throw PairingException(PairingFailure.CredentialsRejected) },
        )
        val store = FakePairingStore()

        val firstStart = assertNotNull(autoPairing(store, api))
        assertIs<AutoPairingOutcome.Failed>(firstStart.pairOnce())
        assertIs<AutoPairingOutcome.AlreadyAttempted>(firstStart.pairOnce())

        val secondStart = assertNotNull(autoPairing(store, api))
        assertIs<AutoPairingOutcome.Failed>(secondStart.pairOnce())
        assertIs<AutoPairingOutcome.AlreadyAttempted>(secondStart.pairOnce())

        assertEquals(2, api.signedIn.size, "one attempt per process start, and only one")
        assertTrue(store.writes.isEmpty())
    }

    /**
     * Ce qui n'est pas une `PairingFailure` non plus : Room, Keystore, une pile TLS.
     *
     * `ServerPairing` nomme déjà tout ce que le réseau peut lever, mais un démarrage d'application
     * ne se termine pas sur une exception venue d'une commodité — le seul appelant est
     * `MueApplication.onCreate`, et ce qui remonterait de là remonterait dans `applicationScope`.
     */
    @Test
    fun anExceptionOnThePairingPathNeverReachesTheCaller() = runTest {
        val store = FakePairingStore()
        val log = RecordingLog()
        val automatic = assertNotNull(
            BetaPairingDefaults.of(server, email, password)?.let { defaults ->
                BetaAutoPairing(
                    defaults = defaults,
                    store = store,
                    pair = { _, _, _ -> throw IllegalStateException("keystore refused") },
                    log = log,
                )
            },
        )

        val outcome = automatic.pairOnce()

        assertIs<AutoPairingOutcome.Failed>(outcome)
        assertEquals("keystore refused", outcome.reason)
        assertTrue(store.writes.isEmpty())
    }

    /**
     * Une base illisible se traite comme « peut-être déjà appairé », qui est la seule des deux
     * lectures dont l'erreur ne coûte rien : appairer par-dessus une ligne qu'on n'a pas su lire
     * remettrait le curseur à zéro sur un téléphone qui n'avait rien demandé.
     */
    @Test
    fun aStoreThatCannotBeReadIsNotAnInvitationToPair() = runTest {
        val api = FakePairingApi()
        val refusing = object : PairingStore {
            override suspend fun state(): SyncStateEntity? = throw IllegalStateException("no disk")
            override suspend fun save(state: SyncStateEntity) = error("nothing may be written")
        }
        val automatic = assertNotNull(autoPairing(refusing, api))

        assertIs<AutoPairingOutcome.Failed>(automatic.pairOnce())
        assertTrue(api.probed.isEmpty(), "the network is not reached when the row is unknown")
    }

    // --- observable -------------------------------------------------------------------------------

    /**
     * Sans trace, un appairage automatique qui échoue est indiscernable d'un appairage automatique
     * absent — et les deux se ressemblent depuis le téléphone : un formulaire pré-rempli et pas de
     * serveur. Les trois issues laissent donc une ligne.
     *
     * Et aucune ne porte le mot de passe. L'APK le porte en clair et `build.gradle.kts` l'assume,
     * mais cet arbitrage borne la divulgation à un artefact : `logcat` se lit par-dessus l'épaule et
     * se colle dans un rapport de bogue, donc il n'y a aucune raison d'élargir la borne ici.
     */
    @Test
    fun everyOutcomeLeavesATraceAndNoneOfThemCarriesThePassword() = runTest {
        val paired = RecordingLog()
        assertNotNull(autoPairing(FakePairingStore(), FakePairingApi(), log = paired)).pairOnce()

        val refused = RecordingLog()
        val rejecting = FakePairingApi(
            onSignIn = { _, _, _ -> throw PairingException(PairingFailure.CredentialsRejected) },
        )
        assertNotNull(autoPairing(FakePairingStore(), rejecting, log = refused)).pairOnce()

        val skipped = RecordingLog()
        val already = FakePairingStore(SyncStateEntity(serverUrl = "https://mue.home.arpa"))
        assertNotNull(autoPairing(already, FakePairingApi(), log = skipped)).pairOnce()

        listOf(paired, refused, skipped).forEach { log ->
            assertTrue(log.lines.isNotEmpty(), "every outcome says something: ${log.lines}")
            assertTrue(
                log.lines.none { it.contains(password) },
                "the password is never written to logcat: ${log.lines}",
            )
        }
        assertTrue(
            refused.lines.any { it.contains("failed") },
            "a failed attempt is distinguishable from no attempt: ${refused.lines}",
        )
    }

    // --- helpers ------------------------------------------------------------------------------------

    /**
     * Ce que `SyncContainer.betaAutoPairing` fait, dans le même ordre : les trois valeurs, puis un
     * objet ou `null`, puis le vrai [ServerPairing] derrière ses trois faux.
     *
     * `CleartextPolicy.Refused` parce que c'est la configuration de `release` et que l'adresse de
     * ces tests est en `https` : aucun cas ici ne parle d'un type de construction par accident.
     */
    private fun autoPairing(
        store: PairingStore,
        api: PairingApi,
        tokens: TokenStore = FakeTokenStore(),
        firstSync: suspend () -> SyncOutcome = { completed },
        log: AutoPairingLog = RecordingLog(),
        serverAddress: String = server,
        accountEmail: String = email,
        accountPassword: String = password,
    ): BetaAutoPairing? {
        val pairing = ServerPairing(
            store = store,
            tokenStore = tokens,
            api = api,
            cleartext = CleartextPolicy.Refused,
            firstSync = firstSync,
            newDeviceId = { "device-1" },
        )
        return BetaPairingDefaults.of(serverAddress, accountEmail, accountPassword)?.let { defaults ->
            BetaAutoPairing(
                defaults = defaults,
                store = store,
                pair = { address, account, secret -> pairing.pair(address, account, secret) },
                log = log,
            )
        }
    }

    /** Le journal, gardé plutôt qu'écrit : `android.util.Log` lève dans un test JVM. */
    private class RecordingLog : AutoPairingLog {
        val lines = mutableListOf<String>()

        override fun log(message: String) {
            lines += message
        }
    }
}
