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
        // La pastille d'en-tête comprise : sans balance, elle n'existe pas (FR-SCALE-020).
        assertNull(model.uiState.value.scale.linkChip)
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

    /**
     * PRD_SCALE 11 et BR-SCALE-001 : le flux pilote l'affichage et n'entre jamais dans la valeur
     * enregistrable.
     *
     * Les deux assertions qui portent la règle sont [EntryUiState.weight] et
     * [EntryUiState.weightRevision]. La seconde n'est pas une redondance : `weightRevision` est le
     * compteur du **chemin d'écriture** de cet écran, et une trame qui l'aurait fait avancer aurait
     * traversé ce chemin — donc aurait pu, un jour, s'y arrêter. Ce que la balance obtient ici est
     * `liveHundredths`, un canal parallèle que seul l'affichage lit.
     */
    @Test
    fun `une trame instable ne touche jamais la valeur enregistrable`() = runTest {
        val scale = paired()
        val model = viewModel(scale)

        scale.emit(ScaleSessionState.Measuring(7_800))
        scale.emit(ScaleSessionState.Measuring(8_150))
        scale.emit(ScaleSessionState.Measuring(8_570))

        val state = model.uiState.value
        assertEquals(Weight.DEFAULT, state.weight)
        assertEquals(0, state.weightRevision)
        assertEquals(EntryScaleIndicator.MEASURING, state.scale.indicator)
        assertEquals(8_570, state.scale.liveHundredths)
        assertTrue(state.scale.streaming)
        assertFalse(state.scale.fromScale)
    }

    /**
     * BR-SCALE-001, **au niveau du `ViewModel`** et pas seulement sur le bouton.
     *
     * L'écran éteint `Save measurement` pendant le flux ; ce test-ci existe parce qu'un bouton
     * grisé est une protection d'interface, et que cette règle est métier. Un appui qui arrive
     * quand même — action d'accessibilité, image de retard, appelant futur — ne doit rien écrire.
     */
    @Test
    fun `enregistrer pendant le flux instable est refusé par le ViewModel`() = runTest {
        val scale = paired()
        val repository = FakeMeasurementRepository()
        val model = viewModel(scale, repository = repository)
        scale.emit(ScaleSessionState.Measuring(8_570))

        model.onSave()

        assertTrue(repository.stored.isEmpty(), "aucune mesure ne s'enregistre pendant le flux")
        assertFalse(model.uiState.value.justSaved)
        // La session n'est pas close non plus : rien ne s'est passé, et la balance a le droit de
        // conclure sur une mesure stable (FR-SCALE-023 ne parle que d'un enregistrement effectif).
        assertEquals(0, scale.closes)

        // Le poids stable qui suit, lui, s'enregistre normalement.
        scale.emit(ScaleSessionState.Stable(scaleReadingOf(85.75)))
        model.onSave()
        assertEquals(8_575, repository.stored.single().weight.hundredthsKg)
    }

    /**
     * FR-SCALE-022 et BR-SCALE-013 : un glissement pendant la mesure reprend la main.
     *
     * C'est la règle existante appliquée telle quelle à un état où elle ne s'appliquait pas encore,
     * puisque la reprise en main ne se déclenchait que sur une provenance déjà posée. Trois effets
     * et pas un de moins : le flux s'arrête, la provenance ne se pose plus, et la session est close
     * pour que la trame suivante de cette liaison ne ramène pas le poids sous le doigt.
     */
    @Test
    fun `un glissement pendant la mesure reprend la valeur et clôt la session`() = runTest {
        val scale = paired()
        val model = viewModel(scale)
        model.onEntryVisible()
        scale.emit(ScaleSessionState.Measuring(8_570))
        assertTrue(model.uiState.value.scale.keepScreenOn)

        model.onWeightChanged(Weight.ofHundredthsClamped(7_200))

        val state = model.uiState.value
        assertEquals(7_200, state.weight.hundredthsKg)
        assertFalse(state.scale.streaming)
        assertNull(state.scale.indicator)
        assertNull(state.scale.liveHundredths)
        assertFalse(state.scale.fromScale)
        assertEquals(1, scale.closes)
        // Le téléphone qu'on venait de poser est de nouveau en main (FR-SCALE-020).
        assertFalse(state.scale.keepScreenOn)
        // Et la valeur reprise est enregistrable, comme une saisie manuelle (BR-SCALE-011).
        assertTrue(state.scale.linkChip != null)
    }

    /** BR-SCALE-011 : la même reprise, aux boutons et au clavier. */
    @Test
    fun `les boutons et le clavier reprennent aussi la main pendant la mesure`() = runTest {
        val scale = paired()
        val model = viewModel(scale)
        scale.emit(ScaleSessionState.Measuring(8_570))

        model.onStep(1)

        assertFalse(model.uiState.value.scale.streaming)
        assertEquals(1, scale.closes)

        scale.emit(ScaleSessionState.Measuring(8_600))
        assertTrue(model.uiState.value.scale.streaming)

        model.onManualEntryOpened()
        model.onManualInputChanged("80.0")

        assertFalse(model.uiState.value.scale.streaming)
        assertEquals(8_000, model.uiState.value.weight.hundredthsKg)
        assertEquals(2, scale.closes)
    }

    // --- BR-SCALE-013, la reprise en main ---------------------------------------------

    @Test
    fun `une modification aux boutons retire la provenance et invalide l'impédance`() = runTest {
        val scale = paired()
        val repository = FakeMeasurementRepository()
        val model = viewModel(scale, profile = completeProfile, repository = repository)
        scale.emit(
            ScaleSessionState.Complete(
                scaleReadingOf(74.35, impedanceOhm = 520),
                impedanceRefused = false,
            ),
        )

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
            ScaleSessionState.Complete(
                scaleReadingOf(74.35, impedanceOhm = 520, sessionId = "s1"),
                impedanceRefused = false,
            ),
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
        scale.emit(
            ScaleSessionState.Complete(
                scaleReadingOf(74.35, impedanceOhm = 520),
                impedanceRefused = false,
            ),
        )

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
        scale.emit(
            ScaleSessionState.Complete(
                scaleReadingOf(74.35, impedanceOhm = 520),
                impedanceRefused = false,
            ),
        )

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
            ScaleSessionState.Complete(
                scaleReadingOf(74.35, impedanceOhm = 520, sessionId = "s1"),
                impedanceRefused = false,
            ),
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
            scale.emit(
                ScaleSessionState.Complete(
                    scaleReadingOf(74.35, impedanceOhm = 520),
                    impedanceRefused = false,
                ),
            )

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
        scale.emit(
            ScaleSessionState.Complete(
                scaleReadingOf(74.35, impedanceOhm = 520),
                impedanceRefused = false,
            ),
        )

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

        model.onScaleAction(EntryScaleAction.OPEN_APP_SETTINGS)
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

        model.onScaleAction(EntryScaleAction.RESTART_SEARCH)

        assertEquals(1, scale.retries)
        // Le constat relancé cesse d'être vrai à l'instant de l'appui : la pastille ne peut plus
        // annoncer une balance introuvable à un lecteur d'écran (PRD_SCALE 18.5, 20).
        assertNull(model.uiState.value.scale.status)
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

    /**
     * PRD_SCALE 20, la moitié qui manquait : **l'annonce a un porteur, et un seul**.
     *
     * L'arrivée était annoncée par la marque `From your scale` sous la valeur. Cette marque a
     * disparu — la pastille ambre de l'en-tête est allumée pour cette seule raison et le disait
     * déjà —, et l'exigence serait partie avec elle sans que rien ne le signale, parce que les
     * tests qui la vérifiaient interrogeaient le nœud supprimé. Ce test la vérifie là où elle vit
     * maintenant, et surtout aux trois moments où elle ne doit **pas** se produire : pendant le
     * flux instable, sur une trame stable répétée, et une fois la valeur reprise.
     *
     * Il porte sur `linkChip.announcement` et non sur la phrase, parce que la phrase se termine
     * dans l'écran, avec le poids formaté ; `EntryScaleScreenTest` la vérifie entière.
     */
    @Test
    fun `l'arrivée est annoncée par la pastille, une fois, et par elle seule`() = runTest {
        val scale = paired()
        val model = viewModel(scale)
        fun chip() = assertNotNull(model.uiState.value.scale.linkChip)

        scale.emit(ScaleSessionState.Searching)
        assertNull(chip().announcement)

        // Le flux instable passe par la branche `indicator`, qui n'annonce rien : une région
        // active branchée là parlerait plusieurs fois par seconde pendant qu'on monte dessus.
        scale.emit(ScaleSessionState.Measuring(8_500))
        assertNull(chip().announcement)
        scale.emit(ScaleSessionState.Measuring(8_570))
        assertNull(chip().announcement)

        scale.emit(ScaleSessionState.Stable(scaleReadingOf(85.75)))
        assertEquals(EntryScaleAnnouncement.MEASUREMENT_RECEIVED, chip().announcement)

        /*
         * « Une seule fois par arrivée ». L'impédance de la même session arrive derrière la mesure
         * et repasse par `acceptReading` : l'état doit en ressortir identique au champ près, sans
         * quoi la description de la pastille changerait pour la même valeur et le lecteur d'écran
         * entendrait la pesée deux fois.
         */
        val announced = model.uiState.value
        scale.emit(
            ScaleSessionState.Complete(scaleReadingOf(85.75), impedanceRefused = false)
        )
        assertEquals(announced, model.uiState.value, "la même mesure ne se réannonce pas")

        // Et la valeur reprise en main retire l'annonce avec la provenance (BR-SCALE-013) : une
        // arrivée annoncée derrière un poids qui n'est plus celui de la balance serait un mensonge.
        model.onStep(1)
        assertNull(chip().announcement)
        assertFalse(model.uiState.value.scale.fromScale)
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

    // --- La pastille d'en-tête (PRD_SCALE 11, 19, 20, FR-SCALE-020) --------------------

    /**
     * Ce que la pastille dit dans chaque état, et ce qu'elle ne dit pas.
     *
     * Un seul test pour la table entière, parce que ce qui est vérifié est justement qu'elle est
     * une table : chaque état de liaison a exactement une ligne, et l'ordre entre elles — ce qui
     * attend un geste, puis ce qui est arrivé, puis ce qui est en cours — est ce qui garantit
     * qu'aucun état n'en masque un autre.
     */
    @Test
    fun `la pastille dit l'état de la liaison, un état à la fois`() = runTest {
        val scale = paired()
        val model = viewModel(scale)
        fun chip() = assertNotNull(model.uiState.value.scale.linkChip)

        scale.emit(ScaleSessionState.Searching)
        assertEquals("Searching", chip().label)
        assertEquals(ScaleMessages.SEARCHING, chip().description)
        assertTrue(chip().active)
        assertTrue(chip().pulsing)
        assertNull(chip().action)

        scale.emit(ScaleSessionState.Connecting)
        assertEquals("Connecting", chip().label)

        scale.emit(ScaleSessionState.WaitingForStepOn)
        assertEquals("Ready", chip().label)
        // La pastille dit la liaison, la légende sous la valeur dit le geste (PRD_SCALE 7.4).
        assertEquals(ScaleMessages.STEP_ON_THE_SCALE, chip().description)

        scale.emit(ScaleSessionState.Measuring(8_570))
        assertEquals("Measuring", chip().label)

        scale.emit(ScaleSessionState.NotFound)
        assertEquals("Try again", chip().label)
        assertEquals("Scale not found · Try again", chip().description)
        assertFalse(chip().active)
        assertFalse(chip().pulsing)
        assertEquals(EntryScaleAction.RESTART_SEARCH, chip().action)

        scale.emit(ScaleSessionState.Unavailable(ScaleUnavailableReason.BLUETOOTH_OFF))
        assertEquals("Bluetooth off", chip().label)
        assertEquals(EntryScaleAction.ENABLE_BLUETOOTH, chip().action)
        // PRD_SCALE 20 : l'indisponibilité remplace la phrase de la pastille le temps d'être dite.
        assertEquals(EntryScaleAnnouncement.UNAVAILABLE, chip().announcement)
        assertEquals(ScaleMessages.UNAVAILABLE_ANNOUNCEMENT, chip().description)

        scale.emit(ScaleSessionState.Searching)
        scale.emit(ScaleSessionState.Unavailable(ScaleUnavailableReason.PERMISSION_MISSING))
        assertEquals("Unavailable", chip().label)
        assertEquals(EntryScaleAction.OPEN_APP_SETTINGS, chip().action)

        // Le geste et le constat sont deux choses : la localisation coupée dit la même phrase que
        // la permission absente et n'ouvre pas le même écran (PRD_SCALE 16.1, 18.5).
        scale.emit(ScaleSessionState.Searching)
        scale.emit(ScaleSessionState.Unavailable(ScaleUnavailableReason.SYSTEM_LOCATION_OFF))
        assertEquals("Unavailable", chip().label)
        assertEquals(EntryScaleAction.OPEN_LOCATION_SETTINGS, chip().action)
    }

    /**
     * PRD_SCALE 19 et FR-SCALE-023 : le poids reçu garde sa couleur, et gagne une offre.
     *
     * La pastille perdait ici son libellé, ce qui était juste tant qu'elle était décorative :
     * nommer la balance n'apprend rien — on est debout dessus — et le nom par défaut est celui du
     * modèle, qui la ferait déborder. Elle est actionnable dans cet état depuis que `Try again` ne
     * dépend plus du délai de deux minutes, et une pastille qu'on peut toucher sans que rien ne le
     * dise est pire qu'une pastille inerte. Ce qu'elle **montre** est donc l'offre, et ce qu'elle
     * **dit** est le geste ; l'ambre, elle, ne bouge pas, parce que la valeur à l'écran vient
     * toujours de la balance.
     */
    @Test
    fun `une fois le poids reçu la pastille propose une nouvelle recherche`() = runTest {
        val scale = paired()
        val model = viewModel(scale)
        scale.emit(ScaleSessionState.Measuring(8_570))
        assertEquals("Measuring", model.uiState.value.scale.linkChip?.label)

        scale.emit(ScaleSessionState.Stable(scaleReadingOf(85.75)))

        val chip = assertNotNull(model.uiState.value.scale.linkChip)
        assertEquals(ScaleMessages.LINK_TRY_AGAIN, chip.label)
        assertEquals("Look for your scale again", chip.description)
        assertTrue(chip.active, "la provenance ne se perd pas dans l'en-tête")
        assertFalse(chip.pulsing)
        assertEquals(EntryScaleAction.RESTART_SEARCH, chip.action)
        // PRD_SCALE 20 : et c'est elle, désormais, qui annonce l'arrivée. La marque de provenance
        // qui la portait a disparu de sous la valeur ; l'exigence, elle, n'a pas bougé.
        assertEquals(EntryScaleAnnouncement.MEASUREMENT_RECEIVED, chip.announcement)
    }

    /**
     * PRD_SCALE 18.2 et FR-SCALE-023 : entre deux sessions, la pastille propose la suivante.
     *
     * Elle se contentait d'énoncer `No scale in range`, ce qui est vrai, n'est pas une faute
     * (PRD_SCALE 7.3) et ne mène nulle part : c'était le troisième cul-de-sac de cet écran, avec
     * l'enregistrement et la reprise en main. Le gris reste — rien n'est en cours et la valeur
     * affichée n'appartient plus à la balance —, mais il y a maintenant quelque chose à toucher.
     */
    @Test
    fun `entre deux sessions la pastille propose d'en rouvrir une`() = runTest {
        val scale = paired()
        val model = viewModel(scale)

        scale.emit(ScaleSessionState.Searching)
        scale.emit(ScaleSessionState.Idle)

        val chip = assertNotNull(model.uiState.value.scale.linkChip)
        assertEquals(ScaleMessages.LINK_TRY_AGAIN, chip.label)
        assertEquals(EntryScaleAction.RESTART_SEARCH, chip.action)
        assertFalse(chip.active)
        assertEquals("Look for your scale again", chip.description)

        model.onScaleAction(assertNotNull(chip.action))

        assertEquals(1, scale.retries)
    }

    // --- FR-SCALE-023, les trois culs-de-sac ------------------------------------------

    /*
     * `Try again` existait, et n'était branché que sur l'expiration des deux minutes.
     *
     * FR-SCALE-023 dit « aucune nouvelle recherche ne démarre tant que l'utilisateur ne quitte pas
     * `Entry` **ou n'active pas explicitement `Try again`** ». Les trois tests qui suivent sont les
     * trois états d'où ce second chemin manquait : après un enregistrement, après une reprise en
     * main, après une mesure hors bornes. Dans les trois, le seul moyen de repeser était de quitter
     * l'onglet et d'y revenir, et rien ne l'indiquait. Ils sont écrits séparément parce que ce sont
     * trois chemins différents vers la même pastille — la session close par `onSave`, celle close
     * par `takeValueBack`, et celle que la couche BLE conclut d'elle-même.
     */

    @Test
    fun `après un enregistrement la pastille propose une nouvelle recherche`() = runTest {
        val scale = paired()
        val repository = FakeMeasurementRepository()
        val model = viewModel(scale, repository = repository, profile = completeProfile)
        model.onEntryVisible()
        scale.emit(ScaleSessionState.Stable(scaleReadingOf(74.35)))

        model.onSave()

        assertEquals(1, repository.stored.size)
        assertEquals(1, scale.closes, "FR-SCALE-023 : l'enregistrement clôt bien la session")

        val chip = assertNotNull(model.uiState.value.scale.linkChip)
        assertEquals(ScaleMessages.LINK_TRY_AGAIN, chip.label)
        assertEquals(ScaleMessages.LINK_SEARCH_AGAIN, chip.description)
        assertEquals(EntryScaleAction.RESTART_SEARCH, chip.action)
        assertTrue(chip.active, "la valeur enregistrée vient toujours de la balance : ambre")

        model.onScaleAction(assertNotNull(chip.action))

        assertEquals(1, scale.retries)
    }

    @Test
    fun `après une reprise en main la pastille propose une nouvelle recherche`() = runTest {
        val scale = paired()
        val model = viewModel(scale)
        scale.emit(ScaleSessionState.Stable(scaleReadingOf(74.35)))

        model.onStep(1)

        val chip = assertNotNull(model.uiState.value.scale.linkChip)
        assertEquals(ScaleMessages.LINK_TRY_AGAIN, chip.label)
        assertEquals(EntryScaleAction.RESTART_SEARCH, chip.action)
        assertFalse(chip.active, "la valeur est redevenue celle de l'utilisateur : plus d'ambre")

        model.onScaleAction(assertNotNull(chip.action))

        assertEquals(1, scale.retries)
    }

    @Test
    fun `après une mesure hors bornes la pastille propose une nouvelle recherche`() = runTest {
        val scale = paired()
        val model = viewModel(scale)
        scale.emit(ScaleSessionState.Searching)

        // 18 kg : une main appuyée sur le plateau (FR-SCALE-024).
        scale.emit(ScaleSessionState.OutOfRange(1_800))

        val chip = assertNotNull(model.uiState.value.scale.linkChip)
        assertEquals(ScaleMessages.LINK_TRY_AGAIN, chip.label)
        assertEquals(EntryScaleAction.RESTART_SEARCH, chip.action)

        model.onScaleAction(assertNotNull(chip.action))

        assertEquals(1, scale.retries)
        // Le message de refus part avec l'appui : il parlait de la pesée qu'on vient d'abandonner.
        assertFalse(model.uiState.value.scale.outOfRange)
    }

    /**
     * L'autre moitié de la règle : une session en cours n'a rien à proposer.
     *
     * Offrir `Try again` pendant que la balance cherche, se connecte, attend qu'on monte ou mesure
     * ferait de la pastille un bouton d'annulation déguisé — un doigt maladroit couperait la pesée
     * en train d'aboutir. C'est l'indication discrète de PRD_SCALE 11 qui occupe alors la pastille,
     * et elle ne se touche pas.
     */
    @Test
    fun `pendant une session vivante la pastille n'offre aucune relance`() = runTest {
        val scale = paired()
        val model = viewModel(scale)

        val live = listOf(
            ScaleSessionState.Searching,
            ScaleSessionState.Connecting,
            ScaleSessionState.WaitingForStepOn,
            ScaleSessionState.Measuring(7_400),
        )
        for (state in live) {
            scale.emit(state)
            val chip = assertNotNull(model.uiState.value.scale.linkChip, "$state")
            assertNull(chip.action, "$state")
            assertTrue(chip.pulsing, "$state")
        }

        /*
         * Y compris derrière une valeur déjà reçue, ce qui est exactement l'état d'une relance qui
         * vient de partir. La pastille dit alors la session en cours et non la mesure précédente :
         * répondre « poids reçu » à quelqu'un qui vient d'appuyer sur `Try again` serait se taire
         * au moment où on lui demande quelque chose.
         */
        scale.emit(ScaleSessionState.Stable(scaleReadingOf(74.35)))
        scale.emit(ScaleSessionState.Searching)
        val searching = assertNotNull(model.uiState.value.scale.linkChip)
        assertEquals(ScaleMessages.LINK_SEARCHING, searching.label)
        assertNull(searching.action)
        assertTrue(searching.active, "la valeur à l'écran vient toujours de la balance")

        // Et sans balance appairée, il n'y a toujours rien à toucher (PRD_SCALE 18.1).
        scale.emit(ScaleSessionState.Absent)
        assertNull(model.uiState.value.scale.linkChip)
    }

    /**
     * PRD_SCALE 9.4 et BR-SCALE-012 : relancer ne rouvre pas la session close, il en ouvre une.
     *
     * Le cas est celui d'un enregistrement anticipé — l'utilisateur appuie sur `Save measurement`
     * pendant que l'impédance est encore attendue — suivi d'un `Try again`. La trame en retard
     * arrive alors *pendant* la nouvelle session, porteuse de l'ancien identifiant. Rien ne
     * l'autorise à compléter quoi que ce soit : la composition n'est jamais ajoutée en silence
     * après la confirmation `Saved`, et la nouvelle pesée est une pesée neuve.
     */
    @Test
    fun `une trame de la session close ne complète pas la session relancée`() = runTest {
        val scale = paired()
        val repository = FakeMeasurementRepository()
        val model = viewModel(scale, repository = repository, profile = completeProfile)
        model.onEntryVisible()
        scale.emit(ScaleSessionState.Stable(scaleReadingOf(74.35, sessionId = "session-1")))
        model.onSave()
        assertEquals(1, repository.stored.size)

        // La relance passe par la pastille, comme le doigt de l'utilisateur : ce test tomberait
        // aussi si l'écran cessait de l'offrir après un enregistrement.
        val offered = assertNotNull(model.uiState.value.scale.linkChip)
        model.onScaleAction(assertNotNull(offered.action))
        scale.emit(ScaleSessionState.Searching)

        // L'impédance de la pesée déjà enregistrée arrive enfin, avec l'ancien identifiant.
        scale.emit(
            ScaleSessionState.Complete(
                scaleReadingOf(74.35, impedanceOhm = 545, sessionId = "session-1"),
                impedanceRefused = true,
            ),
        )

        val state = model.uiState.value
        assertEquals(
            EntryScaleIndicator.SEARCHING,
            state.scale.indicator,
            "la trame tardive ne change rien du tout, pas même l'indication",
        )
        assertFalse(state.scale.barefootHint)
        assertNull(repository.stored.single().impedanceOhm)
        assertNull(repository.stored.single().bodyComposition)

        // La session relancée, elle, pose bien sa propre mesure, sous son propre identifiant.
        scale.emit(ScaleSessionState.Stable(scaleReadingOf(80.0, sessionId = "session-2")))
        assertEquals(8_000, model.uiState.value.weight.hundredthsKg)
        assertTrue(model.uiState.value.scale.fromScale)

        model.onSave()
        val saved = repository.stored.single()
        assertEquals(8_000, saved.weight.hundredthsKg)
        assertEquals(MeasurementSource.SCALE, saved.source)
        assertNull(saved.impedanceOhm, "l'impédance de la session close n'a pas été reprise")
        assertNull(saved.bodyComposition)
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
