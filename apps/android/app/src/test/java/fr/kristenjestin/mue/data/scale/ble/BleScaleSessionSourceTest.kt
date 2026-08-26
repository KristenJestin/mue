package fr.kristenjestin.mue.data.scale.ble

import fr.kristenjestin.mue.data.scale.protocol.MueScaleDrivers
import fr.kristenjestin.mue.data.scale.protocol.REAL_IMPEDANCE_ABSENT_FRAME
import fr.kristenjestin.mue.data.scale.protocol.REAL_IMPEDANCE_ACK
import fr.kristenjestin.mue.data.scale.protocol.REAL_IMPEDANCE_FRAME
import fr.kristenjestin.mue.data.scale.protocol.REAL_INIT_COMMANDS
import fr.kristenjestin.mue.data.scale.protocol.REAL_STABLE_WEIGHT_FRAME
import fr.kristenjestin.mue.data.scale.protocol.REAL_WEIGHT_ACK
import fr.kristenjestin.mue.data.scale.protocol.hexToBytes
import fr.kristenjestin.mue.data.scale.protocol.toHex
import fr.kristenjestin.mue.domain.model.ScaleDevice
import fr.kristenjestin.mue.domain.model.ScaleSessionState
import fr.kristenjestin.mue.domain.model.ScaleUnavailableReason
import fr.kristenjestin.mue.domain.model.Weight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Les deux minutes de FR-SCALE-020, recopiées ici : le module les garde privées. */
private const val SEARCH_WINDOW_MS = 120_000L

/** Les dix secondes d'attente de l'impédance (PRD_SCALE 14.3 point 5). */
private const val IMPEDANCE_WINDOW_MS = 10_000L

/** Le premier palier de backoff après une tentative ratée (FR-SCALE-020). */
private const val FIRST_BACKOFF_MS = 500L

/**
 * La machine à états de mesure, éprouvée **sans Bluetooth, sans Android, sans Robolectric**
 * (PRD_SCALE 21.3).
 *
 * Chaque test de ce fichier est une exigence écrite du PRD, pas une propriété d'implémentation.
 * L'horloge est virtuelle : les deux minutes de la fenêtre de recherche et les dix secondes de
 * l'attente d'impédance s'écoulent en quelques microsecondes, ce qui est la seule façon de les
 * couvrir toutes les deux dans une suite qu'on relance à chaque modification.
 *
 * **Sur l'usage de `runCurrent` plutôt que d'`advanceUntilIdle`.** La machine tient en permanence
 * un compte à rebours de deux minutes ; `advanceUntilIdle` le déclencherait à chaque appel, et
 * chaque test se terminerait en `NotFound`. `runCurrent` n'exécute que ce qui est dû à l'instant
 * virtuel courant, ce qui laisse le temps être avancé explicitement, là où le test le décide.
 */
class BleScaleSessionSourceTest {

    private val hbScale = pairedScale(id = "scale-hb", address = "FF:10:00:1F:52:C3")
    private val hbAdvertisement = advertisementOf(address = "FF:10:00:1F:52:C3")

    private val otherScale = pairedScale(id = "scale-other", address = "AA:BB:CC:DD:EE:FF")
    private val otherAdvertisement = advertisementOf(address = "AA:BB:CC:DD:EE:FF")

    /**
     * Une machine, son transport et son dépôt, montés sur la portée d'arrière-plan du test.
     *
     * `backgroundScope` et non `TestScope` : le collecteur du dépôt de l'[BleScaleSessionSource]
     * vit aussi longtemps que la machine, et une portée qu'il faudrait attendre empêcherait tout
     * test de se terminer.
     */
    private class Fixture(scope: CoroutineScope, devices: List<ScaleDevice>) {
        val transport = FakeScaleTransport()
        val scales = FakeScaleRepository(devices)
        private var sessions = 0

        val source = BleScaleSessionSource(
            transport = transport,
            scales = scales,
            drivers = MueScaleDrivers,
            scope = scope,
            now = { TEST_NOW },
            newSessionId = { "session-${++sessions}" },
        )

        val state: ScaleSessionState get() = source.state.value

        /** L'unique liaison ouverte à cet instant. Échoue si le test en a créé plusieurs. */
        val link: FakeScaleLink get() = transport.links.last()
    }

    private fun TestScope.fixture(devices: List<ScaleDevice> = listOf(pairedScale())) =
        Fixture(backgroundScope, devices)

    /** Scan lancé, balance découverte, liaison ouverte, séquence émise : l'état d'attente. */
    private fun Fixture.reachWaitingForStepOn(scope: TestScope) {
        source.start()
        scope.runCurrent()
        transport.advertise(hbAdvertisement)
        scope.runCurrent()
    }

    // region séquence nominale

    /**
     * PRD_SCALE 11 : `Searching` → `Connecting` → `WaitingForStepOn` → `Measuring` → `Stable` →
     * `Complete`. Le chemin complet, avec les trames et les acquittements réels de PRD_SCALE 14.3.
     */
    @Test
    fun `une pesée nominale va du scan à la composition corporelle`() = runTest {
        val f = fixture(listOf(hbScale))

        f.source.start()
        runCurrent()
        assertEquals(ScaleSessionState.Searching, f.state)
        assertTrue(f.transport.isScanning)

        f.transport.advertise(hbAdvertisement)
        runCurrent()

        // FR-SCALE-020 : « le scan s'arrête immédiatement lorsqu'une balance candidate est trouvée ».
        assertFalse(f.transport.isScanning)
        assertEquals(1, f.transport.scanStops)
        assertEquals(ScaleSessionState.WaitingForStepOn, f.state)

        // PRD_SCALE 14.3 : les trois commandes d'initialisation, dans l'ordre, une à la fois.
        assertEquals(REAL_INIT_COMMANDS, f.link.writes)

        // FR-SCALE-001 : un contact réussi rafraîchit l'adresse et le nom annoncé.
        val contact = f.scales.contacts.single()
        assertEquals(hbScale.id, contact.id)
        assertEquals(hbAdvertisement.address, contact.address)
        assertEquals("HB BODY FAT", contact.advertisedName)
        assertEquals(TEST_NOW, contact.at)

        f.link.deliver(weightFrame(3_240, stable = false))
        runCurrent()
        assertEquals(ScaleSessionState.Measuring(3_240), f.state)
        // BR-SCALE-001 : une valeur instable n'est jamais enregistrable.
        assertNull(f.state.stableReading)

        // La trame stable relevée sur matériel, verbatim : 85,75 kg (PRD_SCALE 14.4).
        f.link.deliver(hexToBytes(REAL_STABLE_WEIGHT_FRAME))
        runCurrent()
        val stable = assertIs<ScaleSessionState.Stable>(f.state)
        assertEquals(8_575, stable.reading.weightHundredthsKg)
        assertTrue(stable.reading.isStable)
        assertNull(stable.reading.impedanceOhm)
        assertEquals("session-1", stable.reading.sessionId)
        assertEquals(hbScale.id, stable.reading.scaleId)
        assertEquals(TEST_NOW, stable.reading.receivedAt)
        // « Sans cet acquittement, la mesure d'impédance n'est jamais lancée » (PRD_SCALE 14.3).
        assertEquals(REAL_INIT_COMMANDS + REAL_WEIGHT_ACK, f.link.writes)

        f.link.deliver(hexToBytes(REAL_IMPEDANCE_FRAME))
        runCurrent()
        val complete = assertIs<ScaleSessionState.Complete>(f.state)
        assertEquals(545, complete.reading.impedanceOhm)
        assertEquals(8_575, complete.reading.weightHundredthsKg)
        assertFalse(complete.impedanceRefused)
        assertEquals(REAL_INIT_COMMANDS + REAL_WEIGHT_ACK + REAL_IMPEDANCE_ACK, f.link.writes)

        // La session est conclue : la liaison est refermée et plus rien ne cherche.
        assertTrue(f.link.closed)
        assertFalse(f.transport.isScanning)
    }

    /**
     * Les trames que ces tests fabriquent sont bien celles du PRD, et non une invention du test.
     *
     * L'impédance est reproduite **octet pour octet** (PRD_SCALE 14.5). La trame de poids ne peut
     * pas l'être : celle relevée sur matériel porte un `0x21` en position 7, dans un champ que le
     * protocole documenté n'attribue à rien et que le pilote ne lit pas (PRD_SCALE 14.4 ne décrit
     * que la position 4 et les positions 8–9). Fabriquer un octet dont on ignore le sens serait
     * inventer une observation ; la trame réelle est donc jouée **verbatim** dans la pesée nominale
     * ci-dessus, et le constructeur du test ne sert qu'aux poids que la validation n'a pas produits
     * — hors bornes, hors du pas, répétitions.
     */
    @Test
    fun `les trames fabriquées par les doubles sont celles relevées sur matériel`() {
        assertEquals(REAL_IMPEDANCE_FRAME, impedanceFrame(545).toHex())
        assertEquals(REAL_IMPEDANCE_ABSENT_FRAME, impedanceFrame(null).toHex())

        // La trame de poids réelle et sa reconstruction décrivent la même pesée, aux champs non
        // documentés près.
        val real = hexToBytes(REAL_STABLE_WEIGHT_FRAME)
        val rebuilt = weightFrame(8_575, stable = true)
        assertEquals(real.size, rebuilt.size)
        for (index in intArrayOf(0, 1, 2, 3, 4, 8, 9)) {
            assertEquals(real[index], rebuilt[index], "octet $index")
        }
    }

    // endregion

    // region fin de session et trames tardives

    /**
     * BR-SCALE-012 : « lorsque l'impédance est encore attendue, l'appui enregistre le poids seul,
     * clôt la session et ignore toute trame tardive. La composition n'est jamais ajoutée
     * silencieusement après la confirmation `Saved`. »
     */
    @Test
    fun `une impédance arrivée après closeSession ne complète jamais la mesure`() = runTest {
        val f = fixture(listOf(hbScale))
        f.reachWaitingForStepOn(this)

        f.link.deliver(weightFrame(8_575, stable = true))
        runCurrent()
        assertIs<ScaleSessionState.Stable>(f.state)

        // L'utilisateur appuie sur `Save measurement` pendant que l'impédance est attendue.
        f.source.closeSession()
        runCurrent()
        assertEquals(ScaleSessionState.Idle, f.state)
        assertTrue(f.link.closed)

        f.link.deliver(impedanceFrame(545))
        advanceTimeBy(IMPEDANCE_WINDOW_MS)
        runCurrent()

        // Ni `Complete`, ni composition ajoutée après coup.
        assertEquals(ScaleSessionState.Idle, f.state)
        assertEquals(listOf(impedanceFrame(545).toHex()), f.link.lateFrames)
    }

    /**
     * PRD_SCALE 21.2 : « une notification tardive d'une ancienne liaison ne complète jamais une
     * autre pesée. »
     */
    @Test
    fun `une trame d'une ancienne session n'agit pas sur la session en cours`() = runTest {
        val f = fixture(listOf(hbScale))
        f.reachWaitingForStepOn(this)
        val firstLink = f.link

        // L'écran disparaît puis revient : nouvelle session, nouvelle liaison.
        f.source.stop()
        runCurrent()
        f.source.start()
        runCurrent()
        f.transport.advertise(hbAdvertisement)
        runCurrent()
        val secondLink = f.link
        assertEquals(2, f.transport.links.size)
        assertEquals(ScaleSessionState.WaitingForStepOn, f.state)

        // La première liaison livre enfin son poids stable. Il appartient à une session morte.
        firstLink.deliver(weightFrame(8_575, stable = true))
        runCurrent()
        assertEquals(ScaleSessionState.WaitingForStepOn, f.state)
        assertNull(f.state.stableReading)

        // La session en cours, elle, fonctionne normalement et porte son propre identifiant.
        secondLink.deliver(weightFrame(7_120, stable = true))
        runCurrent()
        val stable = assertIs<ScaleSessionState.Stable>(f.state)
        assertEquals(7_120, stable.reading.weightHundredthsKg)
        assertEquals("session-2", stable.reading.sessionId)
    }

    /** `stop()` ferme la liaison, arrête le scan et invalide la session (contrat de l'interface). */
    @Test
    fun `stop ferme la liaison, arrête le scan et invalide la session`() = runTest {
        val f = fixture(listOf(hbScale))
        f.reachWaitingForStepOn(this)
        val link = f.link

        f.source.stop()
        runCurrent()

        assertEquals(ScaleSessionState.Idle, f.state)
        assertTrue(link.closed)
        assertFalse(f.transport.isScanning)

        link.deliver(weightFrame(8_575, stable = true))
        runCurrent()
        assertEquals(ScaleSessionState.Idle, f.state)
    }

    // endregion

    // region reprise silencieuse

    /**
     * PRD_SCALE 18.5 : « liaison perdue en cours de mesure : reprise silencieuse sur le temps
     * restant. Aucun message d'erreur. » FR-SCALE-021 : « une déconnexion n'affiche pas d'erreur et
     * ne demande aucune action. »
     */
    @Test
    fun `une déconnexion en cours de mesure reprend en silence et la mesure suivante aboutit`() =
        runTest {
            val f = fixture(listOf(hbScale))
            val seen = mutableListOf<ScaleSessionState>()
            f.reachWaitingForStepOn(this)

            f.link.deliver(weightFrame(4_050, stable = false))
            runCurrent()
            assertEquals(ScaleSessionState.Measuring(4_050), f.state)

            // La balance s'endort au milieu de la pesée.
            f.link.dropLink()
            runCurrent()
            seen += f.state

            // Aucune erreur : l'état retombe simplement en recherche.
            assertEquals(ScaleSessionState.Searching, f.state)

            // Le backoff court de FR-SCALE-020, puis un nouveau scan sur le temps restant.
            assertFalse(f.transport.isScanning)
            advanceTimeBy(FIRST_BACKOFF_MS + 1)
            runCurrent()
            assertTrue(f.transport.isScanning)
            assertEquals(2, f.transport.scanStarts)

            f.transport.advertise(hbAdvertisement)
            runCurrent()
            assertEquals(ScaleSessionState.WaitingForStepOn, f.state)

            f.transport.links.last().deliver(weightFrame(8_575, stable = true))
            runCurrent()
            val stable = assertIs<ScaleSessionState.Stable>(f.state)
            assertEquals(8_575, stable.reading.weightHundredthsKg)
            // Toujours la même session : la fenêtre de deux minutes n'a pas été rouverte.
            assertEquals("session-1", stable.reading.sessionId)

            assertTrue(seen.none { it is ScaleSessionState.Unavailable })
            assertTrue(seen.none { it == ScaleSessionState.NotFound })
        }

    /**
     * FR-SCALE-020 : « si la connexion échoue, le scan peut reprendre avec un court backoff, sans
     * jamais dépasser les deux minutes de la session. »
     */
    @Test
    fun `une connexion refusée relance le scan sans ouvrir de nouvelle session`() = runTest {
        val f = fixture(listOf(hbScale))
        f.transport.connectionsToRefuse = 1

        f.source.start()
        runCurrent()
        f.transport.advertise(hbAdvertisement)
        runCurrent()

        assertEquals(ScaleSessionState.Searching, f.state)
        assertTrue(f.transport.links.isEmpty())

        advanceTimeBy(FIRST_BACKOFF_MS + 1)
        runCurrent()
        f.transport.advertise(hbAdvertisement)
        runCurrent()

        assertEquals(ScaleSessionState.WaitingForStepOn, f.state)
        assertEquals(2, f.transport.connectRequests.size)
    }

    // endregion

    // region la fenêtre de deux minutes

    /**
     * FR-SCALE-020 : « au terme des deux minutes, aucun scan ne redémarre en boucle. L'état discret
     * `Scale not found · Try again` permet de lancer une nouvelle session de deux minutes. »
     */
    @Test
    fun `au bout de deux minutes la recherche s'arrête définitivement et retry en rouvre une pleine`() =
        runTest {
            val f = fixture(listOf(hbScale))

            f.source.start()
            runCurrent()
            assertEquals(ScaleSessionState.Searching, f.state)

            advanceTimeBy(SEARCH_WINDOW_MS - 1)
            runCurrent()
            assertEquals(ScaleSessionState.Searching, f.state)

            advanceTimeBy(2)
            runCurrent()
            assertEquals(ScaleSessionState.NotFound, f.state)
            // « Mue annule le scan, ferme toute liaison en attente et invalide le sessionId. »
            assertFalse(f.transport.isScanning)

            // Aucun redémarrage en boucle, et un start() involontaire n'en provoque pas.
            advanceTimeBy(SEARCH_WINDOW_MS * 2)
            runCurrent()
            f.source.start()
            runCurrent()
            assertEquals(ScaleSessionState.NotFound, f.state)
            assertEquals(1, f.transport.scanStarts)

            // `Try again` ouvre une session pleine de deux minutes.
            f.source.retry()
            runCurrent()
            assertEquals(ScaleSessionState.Searching, f.state)
            assertEquals(2, f.transport.scanStarts)

            advanceTimeBy(SEARCH_WINDOW_MS - 1)
            runCurrent()
            assertEquals(ScaleSessionState.Searching, f.state)

            f.transport.advertise(hbAdvertisement)
            runCurrent()
            f.link.deliver(weightFrame(8_575, stable = true))
            runCurrent()
            assertEquals("session-2", assertIs<ScaleSessionState.Stable>(f.state).reading.sessionId)
        }

    /**
     * PRD_SCALE 14.3 point 5 : « attendre au maximum dix secondes après le poids stable […]
     * au-delà, la session devient `Complete` sans composition. »
     *
     * La fenêtre d'impédance succède aux deux minutes au lieu d'y être enfermée : un poids arrivé à
     * une minute cinquante-neuf mérite sa composition corporelle autant qu'un autre.
     */
    @Test
    fun `un poids stable reçu juste avant la fin de la fenêtre garde ses dix secondes d'impédance`() =
        runTest {
            val f = fixture(listOf(hbScale))
            f.reachWaitingForStepOn(this)

            advanceTimeBy(SEARCH_WINDOW_MS - 1_000)
            runCurrent()
            f.link.deliver(weightFrame(8_575, stable = true))
            runCurrent()
            assertIs<ScaleSessionState.Stable>(f.state)

            // Les deux minutes expirent : le compte à rebours ne doit pas écraser la mesure.
            advanceTimeBy(2_000)
            runCurrent()
            assertIs<ScaleSessionState.Stable>(f.state)

            f.link.deliver(impedanceFrame(545))
            runCurrent()
            assertEquals(545, assertIs<ScaleSessionState.Complete>(f.state).reading.impedanceOhm)
        }

    // endregion

    // region bornes

    /**
     * FR-SCALE-024 : « une mesure stable hors de `30.0–250.0 kg` n'est jamais posée sur la règle. »
     *
     * Le cas n'est pas théorique : des appuis à la main ont produit des mesures stables entre 14 et
     * 21 kg pendant la validation du protocole.
     */
    @Test
    fun `un poids stable hors bornes produit OutOfRange sans rien d'enregistrable`() = runTest {
        val f = fixture(listOf(hbScale))
        f.reachWaitingForStepOn(this)

        f.link.deliver(weightFrame(2_100, stable = true))
        runCurrent()

        assertEquals(ScaleSessionState.OutOfRange(2_100), f.state)
        assertNull(f.state.stableReading)
        // L'acquittement du poids stable n'est pas émis : rien ne justifie de lancer une mesure
        // d'impédance pour un poids que Mue refuse d'enregistrer (PRD_SCALE 14.3 point 4).
        assertEquals(REAL_INIT_COMMANDS, f.link.writes)
        assertTrue(f.link.closed)
    }

    /** FR-SCALE-024 : « pendant le flux instable, les valeurs hors bornes sont ignorées silencieusement. » */
    @Test
    fun `les valeurs instables hors bornes sont ignorées sans changer l'état`() = runTest {
        val f = fixture(listOf(hbScale))
        f.reachWaitingForStepOn(this)

        f.link.deliver(weightFrame(1_400, stable = false))
        f.link.deliver(weightFrame(2_990, stable = false))
        f.link.deliver(weightFrame(25_005, stable = false))
        runCurrent()

        assertEquals(ScaleSessionState.WaitingForStepOn, f.state)

        f.link.deliver(weightFrame(3_000, stable = false))
        runCurrent()
        assertEquals(ScaleSessionState.Measuring(3_000), f.state)
    }

    // endregion

    // region impédance

    /**
     * PRD_SCALE 14.3 point 5 : au-delà de dix secondes, `Complete` sans composition — et sans
     * accuser l'utilisateur de rien.
     */
    @Test
    fun `une impédance qui n'arrive pas donne Complete sans refus`() = runTest {
        val f = fixture(listOf(hbScale))
        f.reachWaitingForStepOn(this)

        f.link.deliver(weightFrame(8_575, stable = true))
        runCurrent()

        advanceTimeBy(IMPEDANCE_WINDOW_MS - 1)
        runCurrent()
        assertIs<ScaleSessionState.Stable>(f.state)

        advanceTimeBy(2)
        runCurrent()
        val complete = assertIs<ScaleSessionState.Complete>(f.state)
        assertNull(complete.reading.impedanceOhm)
        // FR-BODY-002 et PRD_SCALE 18.3 : le conseil « pieds nus » ne s'affiche que sur un refus
        // explicite de la balance, jamais sur un délai écoulé.
        assertFalse(complete.impedanceRefused)
    }

    /**
     * BR-SCALE-005 : `0xFFFF` est une absence explicitement signalée par la balance — contact
     * partiel, pieds non nus — et c'est ce seul cas qui allume le conseil de FR-BODY-002.
     */
    @Test
    fun `une impédance explicitement impossible marque le refus`() = runTest {
        val f = fixture(listOf(hbScale))
        f.reachWaitingForStepOn(this)

        f.link.deliver(weightFrame(8_575, stable = true))
        runCurrent()
        f.link.deliver(impedanceFrame(null))
        runCurrent()

        val complete = assertIs<ScaleSessionState.Complete>(f.state)
        assertNull(complete.reading.impedanceOhm)
        assertTrue(complete.impedanceRefused)
        // Le poids, lui, reste parfaitement valide.
        assertEquals(8_575, complete.reading.weightHundredthsKg)
    }

    /**
     * PRD_SCALE 14.3 point 6 : la balance répète sa trame stable, et acquitter chaque répétition
     * empilerait des écritures dans une fenêtre déjà courte. FR-SCALE-015 : après un poids stable,
     * aucune autre source ne peut remplacer la mesure.
     */
    @Test
    fun `les répétitions de la trame stable n'empilent ni acquittement ni mesure`() = runTest {
        val f = fixture(listOf(hbScale))
        f.reachWaitingForStepOn(this)

        f.link.deliver(weightFrame(8_575, stable = true))
        runCurrent()
        f.link.deliver(weightFrame(8_575, stable = true))
        f.link.deliver(weightFrame(9_100, stable = true, acknowledged = true))
        runCurrent()

        assertEquals(REAL_INIT_COMMANDS + REAL_WEIGHT_ACK, f.link.writes)
        assertEquals(8_575, assertIs<ScaleSessionState.Stable>(f.state).reading.weightHundredthsKg)
    }

    // endregion

    // region plusieurs balances, aucune balance, indisponibilité

    /**
     * FR-SCALE-015 : « Mue se lie à la première qui répond […] dès qu'une connexion commence, les
     * autres balances sont ignorées pour cette session. »
     */
    @Test
    fun `la première balance qui répond verrouille la session`() = runTest {
        val f = fixture(listOf(hbScale, otherScale))

        f.source.start()
        runCurrent()
        f.transport.advertise(hbAdvertisement, otherAdvertisement)
        runCurrent()

        assertEquals(listOf(hbAdvertisement.address), f.transport.connectRequests)
        assertEquals(1, f.transport.links.size)

        // La seconde continue d'annoncer sa présence : elle reste ignorée.
        f.transport.advertise(otherAdvertisement)
        runCurrent()
        assertEquals(1, f.transport.connectRequests.size)

        f.link.deliver(weightFrame(8_575, stable = true))
        runCurrent()
        assertEquals(hbScale.id, assertIs<ScaleSessionState.Stable>(f.state).reading.scaleId)
    }

    /**
     * FR-SCALE-020 : « aucune balance enregistrée : aucun scan, aucune permission demandée, aucun
     * élément d'interface ajouté. »
     */
    @Test
    fun `sans balance enregistrée rien n'est demandé au transport`() = runTest {
        val f = fixture(devices = emptyList())

        f.source.start()
        runCurrent()

        assertEquals(ScaleSessionState.Absent, f.state)
        assertTrue(f.transport.untouched)

        // Y compris après un `Try again` involontaire.
        f.source.retry()
        runCurrent()
        assertEquals(ScaleSessionState.Absent, f.state)
        assertTrue(f.transport.untouched)
    }

    /** L'état de repos suit le dépôt sans qu'aucun scan n'ait lieu (contrat de `ScaleSessionSource`). */
    @Test
    fun `l'état au repos distingue aucune balance d'une balance au repos`() = runTest {
        val f = fixture(devices = emptyList())
        runCurrent()
        assertEquals(ScaleSessionState.Absent, f.state)

        f.scales.save(hbScale)
        runCurrent()
        assertEquals(ScaleSessionState.Idle, f.state)
        assertTrue(f.transport.untouched)
    }

    /**
     * FR-SCALE-025 et PRD_SCALE 18.5 : les trois causes actionnables, et aucun scan tant qu'elles
     * durent.
     */
    @Test
    fun `bluetooth éteint, permission absente et localisation éteinte empêchent tout scan`() =
        runTest {
            for (reason in ScaleUnavailableReason.entries) {
                val f = fixture(listOf(hbScale))
                f.transport.unavailable = reason

                f.source.start()
                runCurrent()

                assertEquals(ScaleSessionState.Unavailable(reason), f.state)
                assertEquals(0, f.transport.scanStarts)

                // Le constat peut changer : la radio se rallume depuis le volet système.
                f.transport.unavailable = null
                f.source.start()
                runCurrent()
                assertEquals(ScaleSessionState.Searching, f.state)
            }
        }

    // endregion

    // region arrondi au pas et maintien de l'écran

    /**
     * PRD_SCALE 14.4 : « l'arrondi au pas reste appliqué à la frontière du domaine, pour tout
     * pilote. » Un futur pilote au centième ne doit pas produire de valeur entre deux crans.
     */
    @Test
    fun `un poids qui ne tombe pas sur le pas est arrondi avant d'entrer dans le domaine`() =
        runTest {
            val f = fixture(listOf(hbScale))
            f.reachWaitingForStepOn(this)

            f.link.deliver(weightFrame(8_573, stable = true))
            runCurrent()

            val stable = assertIs<ScaleSessionState.Stable>(f.state)
            assertEquals(8_575, stable.reading.weightHundredthsKg)
            assertNotNull(Weight.ofHundredthsOrNull(stable.reading.weightHundredthsKg))
        }

    // endregion
}
