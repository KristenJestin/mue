package fr.kristenjestin.mue.ui.progress

import androidx.lifecycle.SavedStateHandle
import fr.kristenjestin.mue.domain.logic.Bmi
import fr.kristenjestin.mue.domain.logic.BodyCompositionCalculator
import fr.kristenjestin.mue.domain.logic.compositionOrNull
import fr.kristenjestin.mue.domain.model.BodyComposition
import fr.kristenjestin.mue.domain.model.Measurement
import fr.kristenjestin.mue.domain.model.MeasurementSource
import fr.kristenjestin.mue.domain.model.Period
import fr.kristenjestin.mue.domain.model.ScaleDevice
import fr.kristenjestin.mue.domain.model.Sex
import fr.kristenjestin.mue.domain.model.UserProfile
import fr.kristenjestin.mue.domain.model.Weight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * La composition corporelle vue depuis `Progress` (PRD_SCALE FR-BODY-005, FR-BODY-006, 18.4).
 *
 * Ce fichier ne rejoue pas la sélection des deux compositions — `BodyCompositionUiStateTest` s'en
 * charge en JVM pure, sans flux. Ce qui se vérifie ici est ce qui appartient réellement au
 * ViewModel : le filtre de période appliqué à la section, l'existence d'une balance lue depuis son
 * repository, et l'écriture rétroactive, qui est la seule opération de cet écran à créer des
 * données de santé pour des dates passées.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProgressBodyCompositionTest {

    private val mainDispatcher = StandardTestDispatcher()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-08-23T09:00:00Z"), ZoneOffset.UTC)

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // region l'IMC n'est pas touché

    /**
     * PRD_SCALE FR-PROFILE-007 : **l'IMC n'utilise pas le sexe** et son affichage n'est modifié en
     * rien par ce champ. C'est la promesse écrite sous le champ lui-même sur `Profile` ; un IMC qui
     * bougerait en le renseignant la démentirait.
     */
    @Test
    fun `l'IMC est identique avant et après le renseignement du sexe`() = runTest {
        val measurements = listOf(withImpedance(daysAgo(0), 74.5, 500))

        val without = bmiOf(measurements, PROFILE.copy(sex = null))
        val with = bmiOf(measurements, PROFILE)

        assertEquals(without, with)
        assertTrue(without is Bmi.Classified)
    }

    // endregion

    // region la section suit la période

    /** FR-BODY-005 : la section suit la période sélectionnée, comme tous les indicateurs. */
    @Test
    fun `la section suit la période sélectionnée`() = progressTest(
        measurements = listOf(
            composed(daysAgo(60), 76.0, 530),
            composed(daysAgo(40), 75.4, 520),
            composed(daysAgo(3), 74.5, 500),
        ),
    ) { viewModel, _ ->
        assertEquals(daysAgo(3), assertNotNull(viewModel.composition().latest).date)
        assertNull(viewModel.composition().previous)

        viewModel.selectPeriod(Period.THREE_MONTHS)
        advanceUntilIdle()

        assertEquals(daysAgo(3), assertNotNull(viewModel.composition().latest).date)
        assertEquals(daysAgo(40), assertNotNull(viewModel.composition().previous).date)
    }

    /** FR-BODY-005 : une période sans composition n'emprunte jamais une valeur hors période. */
    @Test
    fun `une période sans composition n'emprunte rien`() = progressTest(
        measurements = listOf(composed(daysAgo(60), 76.0, 530)),
    ) { viewModel, _ ->
        assertNull(viewModel.composition().latest)
        assertTrue(viewModel.composition().hasHistory)
        assertTrue(viewModel.composition().showCards)
    }

    /** BR-SCALE-010 : oublier la balance ne masque pas les compositions déjà enregistrées. */
    @Test
    fun `la section reste visible sans aucune balance appairée`() = progressTest(
        measurements = listOf(composed(daysAgo(3), 74.5, 500)),
        scales = emptyList(),
    ) { viewModel, _ ->
        assertTrue(viewModel.composition().isVisible)
        assertFalse(viewModel.composition().hasPairedScale)
    }

    /** PRD_SCALE 18.4 : l'explication du profil incomplet suppose une balance associée. */
    @Test
    fun `une balance appairée fait apparaître l'explication du profil incomplet`() = progressTest(
        measurements = listOf(withImpedance(daysAgo(3), 74.5, 500)),
        profile = PROFILE.copy(sex = null),
        scales = listOf(pairedScale()),
    ) { viewModel, _ ->
        assertTrue(viewModel.composition().showIncompleteProfile)
    }

    // endregion

    // region le calcul rétroactif

    /**
     * FR-BODY-006 : accepter la proposition écrit les compositions manquantes, chacune avec l'âge
     * de sa propre date, puis la proposition disparaît d'elle-même — il ne reste plus rien à
     * compléter.
     */
    @Test
    fun `accepter la proposition écrit les compositions manquantes`() = progressTest(
        measurements = listOf(
            withImpedance(LocalDate.of(2020, 6, 15), 74.5, 500),
            withImpedance(daysAgo(3), 74.5, 500),
        ),
        period = Period.ALL,
    ) { viewModel, repository ->
        assertEquals(2, viewModel.composition().retroactiveCount)
        assertTrue(viewModel.composition().showRetroactiveProposal)

        viewModel.completePastWeighIns()
        advanceUntilIdle()

        val byDate = repository.measurements.associateBy { it.date }
        assertEquals(30, assertNotNull(byDate[LocalDate.of(2020, 6, 15)]?.bodyComposition).inputAgeYears)
        assertEquals(36, assertNotNull(byDate[daysAgo(3)]?.bodyComposition).inputAgeYears)

        assertEquals(0, viewModel.composition().retroactiveCount)
        assertFalse(viewModel.composition().showRetroactiveProposal)
        assertTrue(viewModel.composition().hasHistory)
    }

    /** FR-BODY-006 : une composition déjà enregistrée n'est jamais écrasée. */
    @Test
    fun `accepter la proposition n'écrase aucune composition existante`() = progressTest(
        measurements = listOf(
            withImpedance(daysAgo(5), 74.5, 500).copy(bodyComposition = frozen(daysAgo(5))),
            withImpedance(daysAgo(3), 74.5, 500),
        ),
        period = Period.ALL,
    ) { viewModel, repository ->
        assertEquals(1, viewModel.composition().retroactiveCount)

        viewModel.completePastWeighIns()
        advanceUntilIdle()

        val untouched = assertNotNull(
            repository.measurements.first { it.date == daysAgo(5) }.bodyComposition,
        )
        assertEquals(frozen(daysAgo(5)), untouched)
    }

    /**
     * BR-SCALE-008 et BR-SCALE-013 : l'écriture rétroactive ajoute une composition et ne touche à
     * rien d'autre — ni le poids, ni la provenance, ni l'impédance.
     */
    @Test
    fun `l'écriture rétroactive ne touche ni au poids ni à la provenance`() = progressTest(
        measurements = listOf(
            withImpedance(daysAgo(3), 74.5, 500).copy(sourceScaleId = "scale-1"),
        ),
        period = Period.ALL,
    ) { viewModel, repository ->
        viewModel.completePastWeighIns()
        advanceUntilIdle()

        val saved = repository.measurements.single()
        assertEquals(74.5, saved.weight.kilograms, 1e-9)
        assertEquals(MeasurementSource.SCALE, saved.source)
        assertEquals("scale-1", saved.sourceScaleId)
        assertEquals(500, saved.impedanceOhm)
        assertNotNull(saved.bodyComposition)
    }

    /**
     * PRD_SCALE 21.1 et BR-SCALE-013 : « modifier le poids ou la date depuis l'historique
     * transforme la mesure en `manual` et retire **à la fois** sa composition et son impédance. »
     *
     * `RoomMeasurementRepositoryTest` prouve que le dépôt honore un payload sans composition ;
     * cela ne dit rien de ce que **cet écran envoie**. Sans ce test, [ProgressViewModel.saveEdit]
     * pouvait recopier l'impédance et la provenance de la mesure d'origine sur le poids retapé, et
     * l'unique symptôme aurait été une impédance mesurée sur 74,5 kg rattachée à 71,2 kg — une
     * donnée fausse plutôt qu'une donnée absente, que rien à l'écran ne distingue.
     *
     * L'assertion sur `sourceScaleId` compte autant que les deux autres : c'est l'identifiant qui
     * ferait croire, dans un export ou côté serveur, que la balance a produit cette valeur.
     */
    @Test
    fun `retoucher un poids reçu depuis l'historique lui retire impédance et composition`() =
        progressTest(
            measurements = listOf(
                composed(daysAgo(3), 74.5, 500).copy(sourceScaleId = "scale-1"),
            ),
            period = Period.ALL,
        ) { viewModel, repository ->
            val received = repository.measurements.single()
            assertNotNull(received.bodyComposition, "le fixture doit partir d'une pesée complète")

            viewModel.openEditor(received)
            viewModel.updateWeightInput("71.2")
            viewModel.saveEdit()
            advanceUntilIdle()

            val edited = repository.measurements.single()
            assertEquals(71.2, edited.weight.kilograms, 1e-9)
            assertEquals(MeasurementSource.MANUAL, edited.source)
            assertNull(edited.sourceScaleId)
            assertNull(edited.impedanceOhm)
            assertNull(edited.bodyComposition)
        }

    /** PRD_SCALE 18.4 : sans aucune pesée à compléter, rien n'est proposé et rien n'est écrit. */
    @Test
    fun `sans pesée à compléter accepter la proposition n'écrit rien`() = progressTest(
        measurements = listOf(manual(daysAgo(3), 74.5)),
        period = Period.ALL,
    ) { viewModel, repository ->
        assertFalse(viewModel.composition().showRetroactiveProposal)

        viewModel.completePastWeighIns()
        advanceUntilIdle()

        assertNull(repository.measurements.single().bodyComposition)
    }

    /**
     * FR-BODY-001 : le profil est ce qui débloque le passé. Tant qu'il est incomplet, aucune pesée
     * n'est complétable, et la proposition ne s'affiche pas — c'est l'explication du profil
     * incomplet qui prend sa place (PRD_SCALE 18.4).
     */
    @Test
    fun `un profil incomplet ne propose aucun calcul rétroactif`() = progressTest(
        measurements = listOf(withImpedance(daysAgo(3), 74.5, 500)),
        profile = PROFILE.copy(sex = null),
        scales = listOf(pairedScale()),
    ) { viewModel, _ ->
        assertEquals(0, viewModel.composition().retroactiveCount)
        assertFalse(viewModel.composition().showRetroactiveProposal)
        assertTrue(viewModel.composition().showIncompleteProfile)
    }

    // endregion

    // region harness

    private fun ProgressViewModel.composition(): BodyCompositionUiState = uiState.value.composition

    private suspend fun TestScope.bmiOf(
        measurements: List<Measurement>,
        profile: UserProfile,
    ): Bmi {
        val viewModel = ProgressViewModel(
            measurementRepository = FakeMeasurementRepository(measurements),
            userProfileRepository = FakeUserProfileRepository(profile),
            scaleRepository = FakeScaleRepository(),
            savedStateHandle = SavedStateHandle(),
            clock = clock,
        )
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()
        return viewModel.uiState.value.bmi
    }

    private fun progressTest(
        measurements: List<Measurement> = emptyList(),
        profile: UserProfile = PROFILE,
        scales: List<ScaleDevice> = listOf(pairedScale()),
        period: Period? = null,
        body: suspend TestScope.(ProgressViewModel, FakeMeasurementRepository) -> Unit,
    ) = runTest {
        val repository = FakeMeasurementRepository(measurements)
        val viewModel = ProgressViewModel(
            measurementRepository = repository,
            userProfileRepository = FakeUserProfileRepository(profile),
            scaleRepository = FakeScaleRepository(scales),
            savedStateHandle = SavedStateHandle(),
            clock = clock,
        )
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()
        period?.let {
            viewModel.selectPeriod(it)
            advanceUntilIdle()
        }
        body(viewModel, repository)
    }

    // endregion
}

private val TODAY: LocalDate = LocalDate.of(2026, 8, 23)

/** 36 ans à [TODAY], 178 cm : un profil complet, à l'intérieur du domaine de FR-BODY-001. */
private val PROFILE = UserProfile(
    heightCm = 178,
    birthDate = LocalDate.of(1990, 1, 1),
    sex = Sex.MALE,
)

private fun daysAgo(days: Long): LocalDate = TODAY.minusDays(days)

private fun kilograms(value: Double): Weight =
    requireNotNull(Weight.ofKilogramsOrNull(value)) { "$value kg est hors domaine" }

private fun manual(date: LocalDate, value: Double): Measurement =
    Measurement(date = date, weight = kilograms(value))

private fun withImpedance(date: LocalDate, value: Double, impedanceOhm: Int): Measurement =
    Measurement(
        date = date,
        weight = kilograms(value),
        source = MeasurementSource.SCALE,
        impedanceOhm = impedanceOhm,
    )

private fun composed(date: LocalDate, value: Double, impedanceOhm: Int): Measurement {
    val measurement = withImpedance(date, value, impedanceOhm)
    val composition = requireNotNull(
        BodyCompositionCalculator.calculate(measurement, PROFILE).compositionOrNull,
    ) { "le fixture doit être dans le domaine de FR-BODY-001" }
    return measurement.copy(bodyComposition = composition)
}

/** Une composition dont les entrées sont volontairement fausses : un recalcul se verrait. */
private fun frozen(date: LocalDate): BodyComposition = BodyComposition(
    date = date,
    formulaId = "already-there",
    formulaVersion = 99,
    inputWeightCg = 1,
    inputHeightCm = 1,
    inputAgeYears = 1,
    inputSex = Sex.FEMALE,
    bodyFatDeciPercent = 1,
    fatFreeMassCg = 1,
    bodyWaterDeciPercent = 1,
    restingEnergyKcal = 1,
)
