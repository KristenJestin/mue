package fr.kristenjestin.mue.ui.entry

import androidx.lifecycle.SavedStateHandle
import fr.kristenjestin.mue.domain.model.Measurement
import fr.kristenjestin.mue.domain.model.MeasurementSource
import fr.kristenjestin.mue.domain.model.ScaleSessionState
import fr.kristenjestin.mue.domain.model.ScaleUnavailableReason
import fr.kristenjestin.mue.domain.model.Sex
import fr.kristenjestin.mue.domain.model.UserPreferences
import fr.kristenjestin.mue.domain.model.UserProfile
import fr.kristenjestin.mue.domain.model.Weight
import fr.kristenjestin.mue.testing.LocaleRule
import fr.kristenjestin.mue.ui.scale.ScaleMessages
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val TODAY: LocalDate = LocalDate.of(2026, 8, 23)

/**
 * Ce qu'`Entry` fait d'une pesée reçue (PRD_SCALE 12.2, 17, 18).
 *
 * Aucun Android, aucun Bluetooth : la machine de mesure est pilotée en assignant des états à
 * [FakeScaleSessionSource], ce qui est exactement le découpage que PRD_SCALE 21.2 impose — le
 * décodage d'un côté, la liaison de l'autre, et l'écran qui n'observe qu'un état.
 *
 * Deux détails de `StateFlow` gouvernent l'écriture de ces tests et expliquent leurs émissions
 * intermédiaires : deux valeurs égales de suite ne sont pas rediffusées, et un état porté par une
 * `data class` est égal à lui-même. Réémettre `Unavailable(PERMISSION_MISSING)` ne réveille donc
 * personne ; il faut passer par un autre état, ce que fait aussi la vraie couche de liaison.
 */
class EntryScaleTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    @get:Rule
    val locale = LocaleRule(Locale.UK)

    /** Complet et dans le domaine de l'équation à [TODAY] : 36 ans, IMC ≈ 23,5 (FR-BODY-001). */
    private val completeProfile = UserProfile(
        heightCm = 178,
        birthDate = LocalDate.of(1990, 3, 4),
        sex = Sex.MALE,
    )

    private fun viewModel(
        scale: FakeScaleSessionSource,
        history: List<Measurement> = emptyList(),
        profile: UserProfile = UserProfile.EMPTY,
        repository: FakeMeasurementRepository = FakeMeasurementRepository(history),
        savedState: SavedStateHandle = SavedStateHandle(),
    ) = EntryViewModel(
        measurements = repository,
        profiles = FakeUserProfileRepository(profile),
        preferences = FakeUserPreferencesRepository(UserPreferences.DEFAULT),
        savedState = savedState,
        today = { TODAY },
        scaleSession = scale,
    )

    private fun paired() = FakeScaleSessionSource(ScaleSessionState.Idle)

    // --- FR-SCALE-020, le scan suit la visibilité -------------------------------------

    @Test
    fun `le scan démarre quand Entry devient visible et s'arrête quand il cesse de l'être`() =
        runTest {
            val scale = paired()
            val model = viewModel(scale)

            assertEquals(0, scale.starts)

            model.onEntryVisible()
            assertEquals(1, scale.starts)
            assertEquals(0, scale.stops)

            model.onEntryHidden()
            assertEquals(1, scale.stops)
        }

    @Test
    fun `quitter l'écran retire l'indication de recherche`() = runTest {
        val scale = paired()
        val model = viewModel(scale)
        model.onEntryVisible()
        scale.emit(ScaleSessionState.Searching)
        assertEquals(EntryScaleIndicator.SEARCHING, model.uiState.value.scale.indicator)

        model.onEntryHidden()

        assertNull(model.uiState.value.scale.indicator)
        assertFalse(model.uiState.value.scale.keepScreenOn)
    }

    /**
     * PRD_SCALE 18.1 : sans balance enregistrée, l'écran est **strictement** celui du PRD socle.
     *
     * Le test porte sur l'égalité avec [EntryScaleUiState.ABSENT] et non sur un drapeau : c'est ce
     * qui interdit à un futur champ d'ajouter une invite ou un badge sans faire échouer ce test.
     */
    @Test
    fun `sans balance enregistrée l'écran n'a rien de plus`() = runTest {
        val scale = FakeScaleSessionSource(ScaleSessionState.Absent)
        val model = viewModel(scale)

        model.onEntryVisible()

        assertEquals(EntryScaleUiState.ABSENT, model.uiState.value.scale)
        assertFalse(model.uiState.value.scale.paired)
        // `start` est appelé quand même : c'est la source qui sait s'il existe une balance, et son
        // contrat garantit qu'un appel sans balance ne scanne rien et ne demande aucune permission.
        assertEquals(1, scale.starts)
    }

    /**
     * PRD_SCALE 18.1 : oublier la dernière balance rend l'écran du PRD socle, immédiatement.
     *
     * Le test part d'un écran qui a **déjà** quelque chose à montrer — l'indicateur discret d'une
     * recherche en cours — pour que le retour à [EntryScaleUiState.ABSENT] soit un vrai
     * changement. La version précédente réémettait `Absent` sur un état déjà `Absent` : un
     * `StateFlow` élimine cette émission, le collecteur ne tournait pas, et le test comparait
     * l'état initial à lui-même. Il serait resté vert même si la branche `Absent` posait un badge.
     *
     * L'assertion sur `paired` est celle qui compte : elle échoue si cette branche passait par
     * `updateScale`, qui marque l'appairage sur tout ce qu'elle touche.
     */
    @Test
    fun `oublier la dernière balance ramène l'écran à celui du PRD socle`() = runTest {
        val scale = FakeScaleSessionSource(ScaleSessionState.Idle)
        val model = viewModel(scale)
        val before = model.uiState.value

        scale.emit(ScaleSessionState.Searching)
        assertTrue(model.uiState.value.scale.paired, "l'écran doit d'abord avoir quelque chose")
        assertEquals(EntryScaleIndicator.SEARCHING, model.uiState.value.scale.indicator)

        scale.emit(ScaleSessionState.Absent)

        assertEquals(EntryScaleUiState.ABSENT, model.uiState.value.scale)
        assertFalse(model.uiState.value.scale.paired)
        assertFalse(model.uiState.value.scale.keepScreenOn)
        // Rien de tout cela n'a touché à la valeur que l'utilisateur compose (BR-SCALE-011).
        assertEquals(before.weight, model.uiState.value.weight)
    }

    // --- FR-SCALE-022, le poids reçu --------------------------------------------------

    @Test
    fun `une mesure stable pose sa valeur sur la règle avec sa provenance`() = runTest {
        val scale = paired()
        val model = viewModel(scale)
        val before = model.uiState.value.weightRevision

        scale.emit(ScaleSessionState.Stable(scaleReadingOf(74.35)))

        val state = model.uiState.value
        assertEquals(7_435, state.weight.hundredthsKg)
        assertTrue(state.scale.fromScale)
        // Le point décisif : la règle ne suit que `weightRevision`, et une pesée reçue doit donc
        // emprunter le même chemin que le seed historique et les boutons `−` / `+`.
        assertTrue(state.weightRevision > before)
        assertEquals(state.weightRevision, state.scale.arrivalRevision)
    }

    /** BR-SCALE-009 : une pesée reçue est un événement présent. */
    @Test
    fun `un poids stable reçu sélectionne aujourd'hui`() = runTest {
        val scale = paired()
        val model = viewModel(scale)
        model.onDateSelected(TODAY.minusDays(4))
        assertEquals(TODAY.minusDays(4), model.uiState.value.date)

        scale.emit(ScaleSessionState.Stable(scaleReadingOf(74.35)))

        assertEquals(TODAY, model.uiState.value.date)
        assertTrue(model.uiState.value.isToday)
    }

    /** PRD_SCALE 11 et BR-SCALE-001 : le flux instable est visible et n'engage rien. */
    @Test
    fun `une trame instable ne touche jamais la valeur`() = runTest {
        val scale = paired()
        val model = viewModel(scale)

        scale.emit(ScaleSessionState.Measuring(7_800))

        val state = model.uiState.value
        assertEquals(Weight.DEFAULT, state.weight)
        assertEquals(0, state.weightRevision)
        assertEquals(EntryScaleIndicator.MEASURING, state.scale.indicator)
        assertEquals(7_800, state.scale.liveHundredths)
        assertFalse(state.scale.fromScale)
    }

    // --- BR-SCALE-013, la reprise en main ---------------------------------------------

    @Test
    fun `une modification aux boutons retire la provenance et invalide l'impédance`() = runTest {
        val scale = paired()
        val repository = FakeMeasurementRepository()
        val model = viewModel(scale, profile = completeProfile, repository = repository)
        scale.emit(ScaleSessionState.Complete(scaleReadingOf(74.35, impedanceOhm = 520)))

        model.onStep(1)

        assertFalse(model.uiState.value.scale.fromScale)
        assertEquals(7_440, model.uiState.value.weight.hundredthsKg)
        // La session est close pour que la trame suivante de cette liaison n'y change plus rien.
        assertEquals(1, scale.closes)

        model.onSave()
        val saved = repository.stored.single()
        assertEquals(MeasurementSource.MANUAL, saved.source)
        assertNull(saved.sourceScaleId)
        assertNull(saved.impedanceOhm)
        assertNull(saved.bodyComposition)
    }

    @Test
    fun `une modification au clavier retire la provenance`() = runTest {
        val scale = paired()
        val model = viewModel(scale)
        scale.emit(ScaleSessionState.Stable(scaleReadingOf(74.35)))

        model.onManualEntryOpened()
        model.onManualInputChanged("80.0")

        assertFalse(model.uiState.value.scale.fromScale)
        assertEquals(8_000, model.uiState.value.weight.hundredthsKg)
    }

    /**
     * L'écho de la règle n'est pas une reprise en main.
     *
     * L'écran republie la position de la règle avant chaque enregistrement ; republier la valeur
     * qu'une balance vient de poser retirerait sa provenance au moment précis où on l'enregistre.
     */
    @Test
    fun `republier la même valeur ne retire pas la provenance`() = runTest {
        val scale = paired()
        val model = viewModel(scale)
        scale.emit(ScaleSessionState.Stable(scaleReadingOf(74.35)))

        model.onWeightChanged(Weight.ofHundredthsClamped(7_435))
        assertTrue(model.uiState.value.scale.fromScale)

        model.onWeightChanged(Weight.ofHundredthsClamped(7_440))
        assertFalse(model.uiState.value.scale.fromScale)
    }

    /** BR-SCALE-013 : la trame tardive de la session close ne complète plus rien. */
    @Test
    fun `une impédance tardive ne rétablit rien après une modification manuelle`() = runTest {
        val scale = paired()
        val model = viewModel(scale)
        scale.emit(ScaleSessionState.Stable(scaleReadingOf(74.35, sessionId = "s1")))
        model.onStep(1)

        scale.emit(
            ScaleSessionState.Complete(scaleReadingOf(74.35, impedanceOhm = 520, sessionId = "s1")),
        )

        val state = model.uiState.value
        assertFalse(state.scale.fromScale)
        assertEquals(7_440, state.weight.hundredthsKg)
        assertFalse(state.scale.barefootHint)
    }

    /** FR-SCALE-022 : une nouvelle mesure stable remplace la valeur et rétablit la provenance. */
    @Test
    fun `une nouvelle session remplace une valeur reprise en main`() = runTest {
        val scale = paired()
        val model = viewModel(scale)
        scale.emit(ScaleSessionState.Stable(scaleReadingOf(74.35, sessionId = "s1")))
        model.onStep(1)
        assertFalse(model.uiState.value.scale.fromScale)

        scale.emit(ScaleSessionState.Stable(scaleReadingOf(75.10, sessionId = "s2")))

        val state = model.uiState.value
        assertEquals(7_510, state.weight.hundredthsKg)
        assertTrue(state.scale.fromScale)
        assertEquals(state.weightRevision, state.scale.arrivalRevision)
    }

    /** BR-SCALE-009 : Mue n'enregistre jamais une impédance mesurée aujourd'hui comme historique. */
    @Test
    fun `choisir une autre date transforme la valeur reçue en saisie manuelle`() = runTest {
        val scale = paired()
        val repository = FakeMeasurementRepository()
        val model = viewModel(scale, profile = completeProfile, repository = repository)
        scale.emit(ScaleSessionState.Complete(scaleReadingOf(74.35, impedanceOhm = 520)))

        model.onDateSelected(TODAY.minusDays(1))

        val state = model.uiState.value
        // Le poids reste affiché ; c'est sa provenance qui part.
        assertEquals(7_435, state.weight.hundredthsKg)
        assertFalse(state.scale.fromScale)
        assertFalse(state.scale.barefootHint)

        model.onSave()
        val saved = repository.stored.single()
        assertEquals(MeasurementSource.MANUAL, saved.source)
        assertNull(saved.impedanceOhm)
        assertNull(saved.bodyComposition)
    }

    @Test
    fun `revenir sur aujourd'hui ne rétablit pas une provenance perdue`() = runTest {
        val scale = paired()
        val model = viewModel(scale)
        scale.emit(ScaleSessionState.Stable(scaleReadingOf(74.35)))
        model.onDateSelected(TODAY.minusDays(1))

        model.onDateSelected(TODAY)

        assertFalse(model.uiState.value.scale.fromScale)
    }

    // --- FR-SCALE-023, l'enregistrement -----------------------------------------------

    @Test
    fun `recevoir un poids n'enregistre rien`() = runTest {
        val scale = paired()
        val repository = FakeMeasurementRepository()
        val model = viewModel(scale, repository = repository)

        scale.emit(ScaleSessionState.Stable(scaleReadingOf(74.35)))
        scale.emit(ScaleSessionState.Complete(scaleReadingOf(74.35, impedanceOhm = 520)))

        assertTrue(repository.stored.isEmpty())
    }

    /** BR-SCALE-012 : le poids seul, la session close, la trame tardive ignorée. */
    @Test
    fun `enregistrer pendant que l'impédance est attendue n'enregistre que le poids`() = runTest {
        val scale = paired()
        val repository = FakeMeasurementRepository()
        val model = viewModel(scale, profile = completeProfile, repository = repository)
        scale.emit(ScaleSessionState.Stable(scaleReadingOf(74.35, sessionId = "s1")))

        model.onSave()

        val saved = repository.stored.single()
        assertEquals(MeasurementSource.SCALE, saved.source)
        assertEquals(7_435, saved.weight.hundredthsKg)
        assertNull(saved.impedanceOhm)
        assertNull(saved.bodyComposition)
        assertEquals(1, scale.closes)

        // La composition n'est jamais ajoutée en silence après la confirmation `Saved`.
        scale.emit(
            ScaleSessionState.Complete(scaleReadingOf(74.35, impedanceOhm = 520, sessionId = "s1")),
        )

        assertNull(repository.stored.single().bodyComposition)
        assertNull(repository.stored.single().impedanceOhm)
        assertFalse(model.uiState.value.scale.barefootHint)
    }

    @Test
    fun `enregistrer avec une impédance exploitable écrit poids provenance impédance et composition`() =
        runTest {
            val scale = paired()
            val repository = FakeMeasurementRepository()
            val model = viewModel(scale, profile = completeProfile, repository = repository)
            scale.emit(ScaleSessionState.Complete(scaleReadingOf(74.35, impedanceOhm = 520)))

            model.onSave()

            val saved = repository.stored.single()
            assertEquals(MeasurementSource.SCALE, saved.source)
            assertEquals("scale-1", saved.sourceScaleId)
            assertEquals(520, saved.impedanceOhm)
            val composition = assertNotNull(saved.bodyComposition)
            assertEquals(saved.date, composition.date)
            // BR-SCALE-015 : l'instantané d'entrée est le poids de la mesure parente.
            assertEquals(saved.weight.hundredthsKg, composition.inputWeightCg)
        }

    /** FR-BODY-004 et BR-SCALE-008 : un profil incomplet n'empêche que le calcul. */
    @Test
    fun `l'impédance est conservée même sans composition calculable`() = runTest {
        val scale = paired()
        val repository = FakeMeasurementRepository()
        val model = viewModel(scale, profile = UserProfile.EMPTY, repository = repository)
        scale.emit(ScaleSessionState.Complete(scaleReadingOf(74.35, impedanceOhm = 520)))

        model.onSave()

        val saved = repository.stored.single()
        assertEquals(520, saved.impedanceOhm)
        assertNull(saved.bodyComposition)
    }

    /** BR-SCALE-005 : une impédance non mesurable est une absence, jamais une valeur. */
    @Test
    fun `une impédance refusée n'est pas enregistrée`() = runTest {
        val scale = paired()
        val repository = FakeMeasurementRepository()
        val model = viewModel(scale, profile = completeProfile, repository = repository)
        scale.emit(
            ScaleSessionState.Complete(
                scaleReadingOf(74.35, impedanceOhm = null),
                impedanceRefused = true,
            ),
        )

        model.onSave()

        val saved = repository.stored.single()
        assertEquals(MeasurementSource.SCALE, saved.source)
        assertNull(saved.impedanceOhm)
        assertNull(saved.bodyComposition)
    }

    @Test
    fun `après l'enregistrement aucune nouvelle recherche ne démarre`() = runTest {
        val scale = paired()
        val model = viewModel(scale)
        model.onEntryVisible()
        scale.emit(ScaleSessionState.Stable(scaleReadingOf(74.35)))

        model.onSave()

        assertEquals(1, scale.starts)
        assertEquals(1, scale.closes)
        assertEquals(0, scale.retries)
    }

    // --- PRD_SCALE 18.3, le conseil pieds nus -----------------------------------------

    @Test
    fun `le conseil pieds nus n'apparaît que sur une impédance explicitement refusée`() = runTest {
        val scale = paired()
        val model = viewModel(scale)

        scale.emit(
            ScaleSessionState.Complete(
                scaleReadingOf(74.35, sessionId = "s1"),
                impedanceRefused = true,
            ),
        )
        assertTrue(model.uiState.value.scale.barefootHint)

        // Un délai de dix secondes écoulé n'est pas un refus (PRD_SCALE 18.3).
        scale.emit(ScaleSessionState.Stable(scaleReadingOf(74.35, sessionId = "s2")))
        scale.emit(
            ScaleSessionState.Complete(
                scaleReadingOf(74.35, sessionId = "s2"),
                impedanceRefused = false,
            ),
        )
        assertFalse(model.uiState.value.scale.barefootHint)
    }

    // --- FR-SCALE-024, hors bornes ----------------------------------------------------

    @Test
    fun `une mesure stable hors bornes laisse l'écran inchangé`() = runTest {
        val scale = paired()
        val model = viewModel(scale)
        val before = model.uiState.value

        // 18 kg : une main appuyée sur le plateau, mesurée parfaitement stable.
        scale.emit(ScaleSessionState.OutOfRange(1_800))

        val after = model.uiState.value
        assertEquals(before.weight, after.weight)
        assertEquals(before.weightRevision, after.weightRevision)
        assertEquals(before.date, after.date)
        assertFalse(after.scale.fromScale)
        assertTrue(after.scale.outOfRange)
        assertEquals(
            "This measurement is outside the range Mue records",
            ScaleMessages.MEASUREMENT_OUT_OF_RANGE,
        )
    }

    @Test
    fun `le message hors bornes part dès que la valeur redevient celle de l'utilisateur`() =
        runTest {
            val scale = paired()
            val model = viewModel(scale)
            scale.emit(ScaleSessionState.OutOfRange(1_800))

            model.onStep(1)

            assertFalse(model.uiState.value.scale.outOfRange)
        }

    // --- FR-SCALE-025, les états actionnables -----------------------------------------

    @Test
    fun `Bluetooth éteint propose de l'activer sans bloquer la saisie`() = runTest {
        val scale = paired()
        val repository = FakeMeasurementRepository()
        val model = viewModel(scale, repository = repository)
        model.onEntryVisible()

        scale.emit(ScaleSessionState.Unavailable(ScaleUnavailableReason.BLUETOOTH_OFF))

        assertEquals(EntryScaleStatus.BLUETOOTH_OFF, model.uiState.value.scale.status)
        assertEquals("Bluetooth is off · Enable", model.uiState.value.scale.status?.message)

        // BR-SCALE-011 : rien de Mue n'est indisponible pour autant.
        model.onStep(2)
        model.onSave()
        assertEquals(1, repository.stored.size)
    }

    @Test
    fun `une permission absente renvoie aux réglages, une seule fois par affichage`() = runTest {
        val scale = paired()
        val model = viewModel(scale)
        model.onEntryVisible()
        scale.emit(ScaleSessionState.Unavailable(ScaleUnavailableReason.PERMISSION_MISSING))
        assertEquals(EntryScaleStatus.PERMISSION_MISSING, model.uiState.value.scale.status)
        assertEquals("Scale unavailable · Open settings", model.uiState.value.scale.status?.message)

        model.onScaleStatusAction(EntryScaleStatus.PERMISSION_MISSING)
        assertNull(model.uiState.value.scale.status)

        // Pas de relance spontanée dans le même affichage.
        scale.emit(ScaleSessionState.Searching)
        scale.emit(ScaleSessionState.Unavailable(ScaleUnavailableReason.PERMISSION_MISSING))
        assertNull(model.uiState.value.scale.status)

        // Un nouvel affichage rend sa parole à la ligne.
        model.onEntryVisible()
        scale.emit(ScaleSessionState.Searching)
        scale.emit(ScaleSessionState.Unavailable(ScaleUnavailableReason.PERMISSION_MISSING))
        assertEquals(EntryScaleStatus.PERMISSION_MISSING, model.uiState.value.scale.status)
    }

    @Test
    fun `la localisation système coupée est actionnable comme une permission`() = runTest {
        val scale = paired()
        val model = viewModel(scale)

        scale.emit(ScaleSessionState.Unavailable(ScaleUnavailableReason.SYSTEM_LOCATION_OFF))

        assertEquals(EntryScaleStatus.SYSTEM_LOCATION_OFF, model.uiState.value.scale.status)
        assertEquals(ScaleMessages.SCALE_UNAVAILABLE, model.uiState.value.scale.status?.message)
    }

    @Test
    fun `aucune balance trouvée propose une nouvelle session`() = runTest {
        val scale = paired()
        val model = viewModel(scale)
        scale.emit(ScaleSessionState.NotFound)
        assertEquals(EntryScaleStatus.NOT_FOUND, model.uiState.value.scale.status)
        assertEquals("Scale not found · Try again", model.uiState.value.scale.status?.message)

        model.onScaleStatusAction(EntryScaleStatus.NOT_FOUND)

        assertEquals(1, scale.retries)
    }

    /** PRD_SCALE 18.2 : endormie, hors de portée, déconnectée — aucun message d'erreur. */
    @Test
    fun `chercher et se connecter ne produit aucun message d'erreur`() = runTest {
        val scale = paired()
        val model = viewModel(scale)

        scale.emit(ScaleSessionState.Searching)
        assertNull(model.uiState.value.scale.status)
        assertEquals(EntryScaleIndicator.SEARCHING, model.uiState.value.scale.indicator)

        scale.emit(ScaleSessionState.Connecting)
        assertNull(model.uiState.value.scale.status)

        scale.emit(ScaleSessionState.WaitingForStepOn)
        assertNull(model.uiState.value.scale.status)
        assertEquals(EntryScaleIndicator.STEP_ON, model.uiState.value.scale.indicator)
        assertEquals("Step on the scale", model.uiState.value.scale.indicator?.message)
    }

    // --- FR-SCALE-020 et PRD_SCALE 20 -------------------------------------------------

    @Test
    fun `l'écran reste éveillé pendant la session de recherche et pas au-delà`() = runTest {
        val scale = paired()
        val model = viewModel(scale)

        scale.emit(ScaleSessionState.Searching)
        assertTrue(model.uiState.value.scale.keepScreenOn)

        scale.emit(ScaleSessionState.WaitingForStepOn)
        assertTrue(model.uiState.value.scale.keepScreenOn)

        scale.emit(ScaleSessionState.Stable(scaleReadingOf(74.35)))
        assertFalse(model.uiState.value.scale.keepScreenOn)
    }

    @Test
    fun `l'écran ne reste pas éveillé après un délai écoulé`() = runTest {
        val scale = paired()
        val model = viewModel(scale)
        scale.emit(ScaleSessionState.Searching)

        scale.emit(ScaleSessionState.NotFound)

        assertFalse(model.uiState.value.scale.keepScreenOn)
    }

    @Test
    fun `l'arrivée d'une mesure est annoncée, jamais une trame`() = runTest {
        val scale = paired()
        val model = viewModel(scale)

        scale.emit(ScaleSessionState.Searching)
        assertNull(model.uiState.value.scale.announcement)

        scale.emit(ScaleSessionState.Measuring(7_400))
        assertNull(model.uiState.value.scale.announcement)

        scale.emit(ScaleSessionState.Measuring(7_420))
        assertNull(model.uiState.value.scale.announcement)

        scale.emit(ScaleSessionState.Stable(scaleReadingOf(74.35)))
        assertEquals(
            EntryScaleAnnouncement.MEASUREMENT_RECEIVED,
            model.uiState.value.scale.announcement,
        )
    }

    @Test
    fun `l'indisponibilité est annoncée une fois par affichage`() = runTest {
        val scale = paired()
        val model = viewModel(scale)
        model.onEntryVisible()

        scale.emit(ScaleSessionState.Unavailable(ScaleUnavailableReason.BLUETOOTH_OFF))

        assertEquals(EntryScaleAnnouncement.UNAVAILABLE, model.uiState.value.scale.announcement)

        /*
         * « Une fois par affichage » (PRD_SCALE 20) — la moitié du nom que ce test ne prouvait pas,
         * puisqu'il n'émettait l'indisponibilité qu'une seule fois.
         *
         * La reprise n'est pas rare : la radio rallumée puis recoupée, ou une session qui repasse
         * par ce constat à chaque tentative, feraient répéter la même phrase dans le lecteur
         * d'écran. Ce qui rend la règle observable, c'est qu'une autre annonce ait pris la parole
         * entre-temps : si l'indisponibilité se réannonçait, elle la remplacerait.
         */
        scale.emit(ScaleSessionState.Stable(scaleReadingOf(74.35)))
        assertEquals(
            EntryScaleAnnouncement.MEASUREMENT_RECEIVED,
            model.uiState.value.scale.announcement,
        )

        scale.emit(ScaleSessionState.Unavailable(ScaleUnavailableReason.BLUETOOTH_OFF))
        assertEquals(
            EntryScaleAnnouncement.MEASUREMENT_RECEIVED,
            model.uiState.value.scale.announcement,
            "l'indisponibilité ne reprend pas la parole dans le même affichage",
        )

        // Un nouvel affichage lui rend son droit de parole. `Searching` entre les deux parce qu'un
        // `StateFlow` élimine une valeur réémise à l'identique.
        model.onEntryVisible()
        scale.emit(ScaleSessionState.Searching)
        scale.emit(ScaleSessionState.Unavailable(ScaleUnavailableReason.BLUETOOTH_OFF))
        assertEquals(EntryScaleAnnouncement.UNAVAILABLE, model.uiState.value.scale.announcement)
    }

    @Test
    fun `reprendre la main tait l'annonce`() = runTest {
        val scale = paired()
        val model = viewModel(scale)
        scale.emit(ScaleSessionState.Stable(scaleReadingOf(74.35)))

        model.onStep(-1)

        assertNull(model.uiState.value.scale.announcement)
    }
}
