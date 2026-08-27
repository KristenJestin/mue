package fr.kristenjestin.mue.ui.entry

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import fr.kristenjestin.mue.MueApplication
import fr.kristenjestin.mue.domain.logic.BodyCompositionCalculator
import fr.kristenjestin.mue.domain.logic.BodyCompositionFormula
import fr.kristenjestin.mue.domain.logic.MueValidation
import fr.kristenjestin.mue.domain.logic.Validated
import fr.kristenjestin.mue.domain.logic.compositionOrNull
import fr.kristenjestin.mue.domain.model.Measurement
import fr.kristenjestin.mue.domain.model.MeasurementSource
import fr.kristenjestin.mue.domain.model.ScaleReading
import fr.kristenjestin.mue.domain.model.ScaleSessionState
import fr.kristenjestin.mue.domain.model.ScaleUnavailableReason
import fr.kristenjestin.mue.domain.model.UserProfile
import fr.kristenjestin.mue.domain.model.Weight
import fr.kristenjestin.mue.domain.repository.MeasurementRepository
import fr.kristenjestin.mue.domain.repository.ScaleSessionSource
import fr.kristenjestin.mue.domain.repository.UserPreferencesRepository
import fr.kristenjestin.mue.domain.repository.UserProfileRepository
import fr.kristenjestin.mue.ui.scale.keepsScreenAwake
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Holds the weight the user is composing, the date it belongs to, and nothing else.
 *
 * The value is session state, not stored state: it is seeded once from the history at app
 * start and then belongs to the user until the process ends (PRD FR-ENTRY-001). Everything
 * needed to rebuild it goes through [SavedStateHandle], so a rotation *and* a system-killed
 * process both come back to the same screen (PRD 16.3).
 *
 * **La balance appairée n'ajoute pas un second propriétaire à cette valeur** (PRD_SCALE 21.2).
 * Une pesée reçue emprunte le même chemin que le seed historique et les boutons `−` / `+` : elle
 * pose un [Weight] et incrémente [EntryUiState.weightRevision]. Tout ce que [scaleSession] apporte
 * en plus est une *provenance* — [EntryUiState.scale] — que le premier geste de l'utilisateur
 * retire (BR-SCALE-013). L'invariant qui en découle tient en une phrase : à aucun moment un
 * élément de cet écran n'est indisponible parce qu'une balance est en train de mesurer
 * (BR-SCALE-011).
 */
class EntryViewModel(
    private val measurements: MeasurementRepository,
    private val profiles: UserProfileRepository,
    private val preferences: UserPreferencesRepository,
    private val savedState: SavedStateHandle,
    private val today: () -> LocalDate = LocalDate::now,
    /**
     * La couche de liaison, ou `null` quand il n'y en a aucune.
     *
     * `null` n'est pas un défaut de commodité : c'est l'état d'une application dont le module
     * Bluetooth n'est pas câblé, et il se lit exactement comme [ScaleSessionState.Absent] — aucun
     * scan, aucune permission demandée, aucun élément ajouté à l'écran (PRD_SCALE 18.1). Le seul
     * chemin qui parle de Bluetooth part d'ici (PRD_SCALE 21.2) ; l'interface ne traverse jamais
     * la couche de liaison.
     */
    private val scaleSession: ScaleSessionSource? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(restoredState())
    val uiState: StateFlow<EntryUiState> = _uiState.asStateFlow()

    /** True once the value on screen belongs to the user; the history must no longer overwrite it. */
    private var valueIsUserOwned: Boolean = savedState.contains(KEY_WEIGHT_HUNDREDTHS)

    /**
     * La lecture dont la valeur est actuellement sur la règle, tant que personne n'y a touché.
     *
     * Délibérément hors de [SavedStateHandle]. PRD_SCALE 21.2 : l'état de liaison et l'impédance
     * en attente ne survivent pas à la destruction du processus, alors que le poids affiché, lui,
     * survit — il redevient donc une saisie manuelle sans provenance ni composition, ce qui est
     * exactement ce que produit un champ non restauré.
     */
    private var acceptedReading: ScaleReading? = null

    /**
     * L'identifiant de la session dont les trames ne doivent plus rien changer.
     *
     * Le mécanisme de PRD_SCALE 9.4 et 21.2, appliqué du côté de l'écran : après un
     * enregistrement (BR-SCALE-012) ou une reprise en main manuelle (BR-SCALE-013), une trame
     * d'impédance en retard porte encore l'identifiant de la session qu'on vient de clore et se
     * reconnaît à cela seul. Une **nouvelle** session en porte un autre, si bien que la mesure
     * stable suivante remplace bien la valeur et rétablit la provenance (FR-SCALE-022) au lieu
     * d'être filtrée avec les retardataires.
     */
    private var closedSessionId: String? = null

    /**
     * `Scale unavailable · Open settings`, une seule fois par affichage (FR-SCALE-025).
     *
     * Remis à `false` par [onEntryVisible], c'est-à-dire à chaque fois qu'`Entry` redevient
     * visible — y compris au retour des réglages, où l'utilisateur a le droit de revoir la ligne
     * si sa réponse n'a rien changé. Aucun dialogue n'est jamais ouvert : la ligne attend un
     * geste, elle ne le provoque pas.
     */
    private var permissionNoticeSpent: Boolean = false

    /** PRD_SCALE 20 : l'indisponibilité s'annonce une fois par affichage, pas à chaque état. */
    private var unavailableAnnounced: Boolean = false

    /** Le profil courant, pour la composition corporelle (FR-BODY-001). Jamais persisté ici. */
    private var profile: UserProfile = UserProfile.EMPTY

    init {
        if (!valueIsUserOwned) seedFromHistory()
        observeProfile()
        observePreferences()
        observeScale()
    }

    // --- The scale ------------------------------------------------------------------

    /**
     * Called on every frame of a drag, so it must stay allocation-cheap and never suspend.
     *
     * An unchanged value returns at once. That is not only an optimisation: the scale echoes
     * its starting position back the moment it appears, and treating that echo as a choice
     * would cancel the seeding of FR-ENTRY-001 before the history had time to answer.
     */
    fun onWeightChanged(weight: Weight) {
        if (_uiState.value.weight == weight) return
        valueIsUserOwned = true
        // Un doigt sur la règle est une reprise en main comme une autre (BR-SCALE-013). Le retour
        // anticipé ci-dessus est ce qui l'empêche de se déclencher à tort : l'écran republie la
        // position de la règle avant chaque enregistrement, et republier la valeur qu'une balance
        // vient de poser retirerait sa provenance au moment précis où on l'enregistre.
        takeValueBack()
        savedState[KEY_WEIGHT_HUNDREDTHS] = weight.hundredthsKg
        _uiState.update { it.copy(weight = weight) }
    }

    /** [steps] presses of `−` or `+`, 0.05 kg each, clamped at the end stop (PRD FR-ENTRY-003). */
    fun onStep(steps: Int) {
        val current = _uiState.value.weight
        setWeight(Weight.ofHundredthsClamped(RulerPhysics.step(current.hundredthsKg, steps)))
    }

    /** Any source other than the scale itself; the scale is told to follow. */
    private fun setWeight(weight: Weight) {
        takeValueBack()
        postWeight(weight)
    }

    /**
     * Pose une valeur et demande à la règle de la rejoindre, sans rien dire de sa provenance.
     *
     * C'est l'incrément de [EntryUiState.weightRevision] qui est le vrai contenu de cette
     * fonction : l'écran ne déplace la règle que sur ce compteur, et [onWeightChanged] — l'écho
     * de la règle elle-même — ne l'incrémente délibérément pas. Une pesée reçue emprunte donc
     * exactement le chemin du seed historique et des boutons `−` / `+`, ce qui est la seule
     * manière pour elle d'apparaître sous le marqueur.
     */
    private fun postWeight(weight: Weight) {
        valueIsUserOwned = true
        savedState[KEY_WEIGHT_HUNDREDTHS] = weight.hundredthsKg
        _uiState.update { it.copy(weight = weight, weightRevision = it.weightRevision + 1) }
    }

    // --- Manual entry ---------------------------------------------------------------

    fun onManualEntryOpened() {
        val text = EntryFormat.weight(_uiState.value.weight)
        savedState[KEY_MANUAL_ENTRY] = true
        savedState[KEY_MANUAL_INPUT] = text
        _uiState.update { it.copy(manualEntry = true, manualInput = text, manualError = null) }
    }

    /**
     * Commits every keystroke that parses, so the hero readout tracks what is being typed.
     * A blank field is "not finished yet", not an error: the message would otherwise fire
     * the moment the user clears the value in order to retype it.
     */
    fun onManualInputChanged(raw: String) {
        savedState[KEY_MANUAL_INPUT] = raw
        if (raw.isBlank()) {
            _uiState.update { it.copy(manualInput = raw, manualError = null) }
            return
        }
        when (val parsed = MueValidation.validateWeightInput(raw)) {
            is Validated.Valid -> {
                setWeight(parsed.value)
                _uiState.update { it.copy(manualInput = raw, manualError = null) }
            }

            is Validated.Invalid ->
                _uiState.update { it.copy(manualInput = raw, manualError = parsed.message) }
        }
    }

    /**
     * The keyboard's `Done`. Returns true when the scale came back.
     *
     * PRD FR-ENTRY-004 wants `Done` to restore the scale and PRD 15.3 wants an invalid value
     * kept on screen for correction. Both hold only if an invalid value refuses to close.
     */
    fun onManualEntryConfirmed(): Boolean =
        when (val parsed = MueValidation.validateWeightInput(_uiState.value.manualInput)) {
            is Validated.Valid -> {
                setWeight(parsed.value)
                savedState[KEY_MANUAL_ENTRY] = false
                _uiState.update { it.copy(manualEntry = false, manualError = null) }
                true
            }

            is Validated.Invalid -> {
                _uiState.update { it.copy(manualError = parsed.message) }
                false
            }
        }

    /** Leaving manual entry without committing: the scale keeps the last valid weight. */
    fun onManualEntryDismissed() {
        savedState[KEY_MANUAL_ENTRY] = false
        _uiState.update { it.copy(manualEntry = false, manualError = null) }
    }

    // --- Date -----------------------------------------------------------------------

    fun onDatePickerOpened() {
        _uiState.update { it.copy(datePickerVisible = true) }
    }

    fun onDatePickerDismissed() {
        _uiState.update { it.copy(datePickerVisible = false) }
    }

    /**
     * PRD FR-ENTRY-005: a date change never touches the weight, not even when a measurement
     * already exists on the chosen day. The history is deliberately not consulted here.
     */
    fun onDateSelected(date: LocalDate) {
        val currentDay = today()
        if (!MueValidation.isMeasurementDateAllowed(date, currentDay)) return
        savedState[KEY_DATE] = date.toString()
        // BR-SCALE-009 : le poids reste affiché, mais une pesée reçue ce matin n'est pas la pesée
        // d'un autre jour. Mue n'enregistre jamais une impédance mesurée aujourd'hui comme une
        // composition historique, alors la valeur redevient simplement celle de l'utilisateur.
        if (date != currentDay) takeValueBack()
        _uiState.update { it.copy(date = date, today = currentDay, datePickerVisible = false) }
    }

    // --- Saving ---------------------------------------------------------------------

    /**
     * Creates the measurement, or replaces the one already on that date (PRD BR-001, BR-002).
     *
     * **La session se clôt sur l'appui, pas sur l'écriture** (FR-SCALE-023, BR-SCALE-012). Tout ce
     * qui décide du contenu de la mesure — la lecture retenue, l'impédance, la fermeture de la
     * session — est fait ici, avant la coroutine. Le faire après aurait laissé une trame
     * d'impédance arriver pendant l'écriture et compléter en silence une mesure déjà confirmée
     * `Saved`, ce qui est précisément ce que cette règle interdit.
     *
     * La session est close même lorsque le poids a été saisi à la main : FR-SCALE-023 veut qu'après
     * un enregistrement aucune nouvelle recherche ne démarre tant que l'utilisateur n'a pas quitté
     * `Entry` ou activé `Try again`.
     */
    fun onSave() {
        val snapshot = _uiState.value
        /*
         * BR-SCALE-001, ici et pas seulement sur le bouton.
         *
         * L'écran éteint `Save measurement` pendant le flux instable, mais un bouton grisé est une
         * protection d'interface : elle vaut pour un doigt, pas pour une action d'accessibilité,
         * pas pour un appui arrivé sur la même image que la première trame, et pas pour le
         * prochain appelant de cette méthode. La valeur affichée pendant le flux ne traverse
         * jamais le chemin d'écriture — elle n'est donc pas dans `snapshot.weight` — mais celle
         * qui s'y trouve encore est celle d'avant la mesure, et l'enregistrer serait enregistrer
         * un poids que personne n'a demandé pendant qu'on est debout sur la balance.
         */
        if (snapshot.scale.streaming) return
        if (!MueValidation.isMeasurementDateAllowed(snapshot.date, today())) return
        val measurement = measurementToSave(snapshot)
        closeSession()
        viewModelScope.launch {
            runCatching { measurements.save(measurement) }
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            justSaved = true,
                            saveError = null,
                            saveFlareCount = it.saveFlareCount + 1,
                        )
                    }
                }
                // PRD 15.4: a failed write never shows a confirmation.
                .onFailure {
                    _uiState.update { it.copy(justSaved = false, saveError = SAVE_ERROR) }
                }
        }
    }

    fun onSaveConfirmationFinished() {
        _uiState.update { it.copy(justSaved = false) }
    }

    /**
     * Ce qui part en base, poids seul ou agrégat complet, en **une** transaction (FR-SCALE-023).
     *
     * Trois règles se croisent ici et aucune n'est négociable :
     *
     * - sans provenance, c'est une saisie manuelle et rien d'autre — même si une balance a émis
     *   quelque chose il y a dix secondes (BR-SCALE-013) ;
     * - l'impédance exploitable part avec la mesure **même si aucune composition n'a pu être
     *   calculée** (FR-BODY-004, BR-SCALE-008) : un profil incomplet ou hors domaine n'empêche
     *   que le calcul, jamais la conservation ;
     * - le poids envoyé au calcul est celui de la mesure parente, donc `inputWeightCg` lui est
     *   égal par construction (BR-SCALE-015).
     *
     * L'impédance est filtrée par [BodyCompositionFormula.isImpedanceUsable] et non recopiée telle
     * quelle : une valeur nulle ou négative est une absence déguisée (BR-SCALE-005), et la
     * conserver ferait compter cette mesure parmi les pesées complétables de FR-BODY-006.
     */
    private fun measurementToSave(snapshot: EntryUiState): Measurement {
        val reading = acceptedReading?.takeIf { snapshot.scale.fromScale }
            ?: return Measurement(snapshot.date, snapshot.weight)

        val impedanceOhm = reading.impedanceOhm
            ?.takeIf { BodyCompositionFormula.isImpedanceUsable(it) }

        return Measurement(
            date = snapshot.date,
            weight = snapshot.weight,
            source = MeasurementSource.SCALE,
            sourceScaleId = reading.scaleId,
            impedanceOhm = impedanceOhm,
            bodyComposition = BodyCompositionCalculator
                .calculate(snapshot.date, snapshot.weight, profile, impedanceOhm)
                .compositionOrNull,
        )
    }

    // --- La balance (PRD_SCALE 12.2) --------------------------------------------------

    /**
     * `Entry` est visible : la session de recherche s'ouvre (FR-SCALE-020).
     *
     * Appelé depuis le cycle de vie de l'écran et non depuis `init`, ce qui est la différence
     * entre « scanner quand on regarde » et « scanner en arrière-plan ». Sans balance
     * enregistrée, [ScaleSessionSource.start] est sans effet et aucune permission n'est demandée.
     *
     * C'est aussi le début d'un nouvel *affichage*, au sens de FR-SCALE-025 : les deux notices
     * qui ne se donnent qu'une fois par affichage retrouvent leur droit de parole.
     */
    fun onEntryVisible() {
        permissionNoticeSpent = false
        unavailableAnnounced = false
        _uiState.update { it.copy(scale = it.scale.copy(outOfRange = false)) }
        scaleSession?.start()
    }

    /** `Entry` n'est plus visible : plus de scan, plus de liaison, plus rien (FR-SCALE-020). */
    fun onEntryHidden() {
        scaleSession?.stop()
        _uiState.update {
            it.copy(
                scale = it.scale.copy(
                    indicator = null,
                    liveHundredths = null,
                    announcement = null,
                    keepScreenOn = false,
                ),
            )
        }
    }

    /**
     * Le geste offert par la ligne d'état de PRD_SCALE 18.5.
     *
     * Seul [EntryScaleStatus.NOT_FOUND] agit ici — c'est l'unique chemin de relance hors d'une
     * réouverture de l'écran (FR-SCALE-020). Les trois autres ouvrent un réglage système, ce qui
     * appartient à l'écran : un `ViewModel` n'a pas de `Context` et ne doit pas en avoir un.
     * Ce qui est enregistré ici, c'est que la notice de permission a été donnée pour cet
     * affichage et ne sera pas relancée spontanément (FR-SCALE-025).
     */
    fun onScaleStatusAction(status: EntryScaleStatus) {
        when (status) {
            EntryScaleStatus.NOT_FOUND -> {
                // `closedSessionId` n'est pas effacé : la nouvelle session porte un autre
                // identifiant, et laisser l'ancien en place est ce qui garantit qu'une trame de
                // la précédente ne pourra jamais compléter celle-ci (PRD_SCALE 9.4).
                _uiState.update { it.copy(scale = it.scale.copy(outOfRange = false)) }
                scaleSession?.retry()
            }

            EntryScaleStatus.PERMISSION_MISSING, EntryScaleStatus.SYSTEM_LOCATION_OFF -> {
                permissionNoticeSpent = true
                _uiState.update { it.copy(scale = it.scale.copy(status = null)) }
            }

            EntryScaleStatus.BLUETOOTH_OFF -> Unit
        }
    }

    private fun observeScale() {
        val source = scaleSession ?: return
        viewModelScope.launch { source.state.collect(::onScaleState) }
    }

    /**
     * L'unique endroit où un état de liaison devient de l'interface.
     *
     * Écrit comme un `when` exhaustif sur [ScaleSessionState] plutôt que comme une suite de
     * drapeaux : les états s'excluent, et un `when` que le compilateur vérifie est ce qui garantit
     * qu'un état ajouté plus tard ne restera pas silencieusement invisible.
     *
     * **L'éveil de l'écran se décide une seule fois, à la fin, et jamais dans une branche**
     * (FR-SCALE-020). C'est [keepsScreenAwake] qui énonce la règle, une bonne fois pour toutes ;
     * les deux sorties anticipées ci-dessous sont les deux seuls cas où il n'y a rien à écrire du
     * tout, et elles disent pourquoi.
     */
    private fun onScaleState(state: ScaleSessionState) {
        when (state) {
            /*
             * Le seul état sans balance. Il repasse par [_uiState] directement et non par
             * [updateScale], qui marquerait l'appairage : sur un `Entry` sans balance, rien de ce
             * module n'existe (PRD_SCALE 18.1).
             */
            ScaleSessionState.Absent -> {
                _uiState.update { it.copy(scale = EntryScaleUiState.ABSENT) }
                return
            }

            // Une trame de la session close est ignorée **sans rien changer**, l'éveil de l'écran
            // compris (BR-SCALE-012, BR-SCALE-013).
            is ScaleSessionState.Stable -> {
                if (state.reading.isStale()) return
                acceptReading(state.weight, state.reading, impedanceRefused = false)
            }

            is ScaleSessionState.Complete -> {
                if (state.reading.isStale()) return
                acceptReading(state.weight, state.reading, state.impedanceRefused)
            }

            ScaleSessionState.Idle -> updateScale {
                it.copy(indicator = null, liveHundredths = null)
            }

            ScaleSessionState.Searching -> searching(EntryScaleIndicator.SEARCHING)
            ScaleSessionState.Connecting -> searching(EntryScaleIndicator.CONNECTING)
            ScaleSessionState.WaitingForStepOn -> searching(EntryScaleIndicator.STEP_ON)
            is ScaleSessionState.Measuring ->
                searching(EntryScaleIndicator.MEASURING, state.hundredthsKg)

            is ScaleSessionState.OutOfRange -> outOfRange()
            ScaleSessionState.NotFound -> settled(EntryScaleStatus.NOT_FOUND)
            is ScaleSessionState.Unavailable -> unavailable(state.reason)
        }

        /*
         * FR-SCALE-020, en une ligne et à un seul endroit : l'écran veille pendant le scan, la
         * connexion, l'attente que l'utilisateur monte et le flux instable qui suit ; le maintien
         * cesse dès qu'un poids stable est reçu, que le délai expire ou qu'`Entry` n'est plus
         * visible — les deux premiers parce qu'ils sortent de cette liste, le troisième par
         * [onEntryHidden].
         */
        updateScale { it.copy(keepScreenOn = state.keepsScreenAwake) }
    }

    /** PRD_SCALE 9.4 : une trame en retard porte l'identifiant de la session qu'on vient de clore. */
    private fun ScaleReading.isStale(): Boolean = sessionId == closedSessionId

    /**
     * Les quatre états qui précèdent une valeur : l'indication discrète, et rien d'autre.
     *
     * [liveHundredths] **ne passe pas par [postWeight]**, et c'est tout ce qui sépare un flux
     * visible d'un flux enregistrable. PRD_SCALE 11 veut que la valeur suive le flux, marquée
     * comme non définitive : l'écran recopie donc ce champ dans la position vivante de la règle,
     * là où arrivent aussi les pixels d'un glissement, tandis que [EntryUiState.weight] — la seule
     * valeur que `Save measurement` sache lire — n'est pas touchée. BR-SCALE-001 tient par
     * l'absence d'un chemin, pas par la présence d'un garde.
     */
    private fun searching(indicator: EntryScaleIndicator, liveHundredths: Int? = null) =
        updateScale {
            it.copy(
                indicator = indicator,
                liveHundredths = liveHundredths,
                status = null,
            )
        }

    /**
     * Une mesure stable se pose sur la règle, avec sa provenance et la date du jour.
     *
     * Quatre effets, dans cet ordre, et chacun a sa règle : la valeur passe par [setWeight] pour
     * incrémenter [EntryUiState.weightRevision] — sans quoi la règle ne bougerait pas — ;
     * la date devient celle du jour (BR-SCALE-009) ; la provenance s'affiche (FR-SCALE-022) ;
     * l'arrivée est annoncée avec sa valeur (PRD_SCALE 20).
     *
     * [setWeight] retire la provenance au passage, puisqu'il sert d'ordinaire les gestes de
     * l'utilisateur ; elle est reposée juste après. L'inverse — un chemin privé qui ne la retire
     * pas — aurait dupliqué la logique de reprise en main, qui est la seule chose de cet écran
     * qu'il ne faut pas se tromper à écrire deux fois.
     *
     * Une trame de la session close n'arrive jamais ici : [onScaleState] la filtre avant, pour que
     * l'ignorer ne change **rien du tout** (BR-SCALE-012, BR-SCALE-013).
     *
     * **Le poids arrive validé et n'est pas revalidé ici** (PRD_SCALE 14.4, BR-SCALE-002). L'arrondi
     * au pas et les bornes sont appliqués « à la frontière du domaine », c'est-à-dire dans la couche
     * BLE, et [ScaleSessionState.Stable] comme [ScaleSessionState.Complete] portent la garantie
     * jusqu'ici sous la forme d'un [Weight]. Cet écran revalidait autrefois par un second
     * algorithme — `ofKilogramsOrNull`, donc un `Double` et un arrondi au plus proche — qui pouvait
     * refuser ce que la couche BLE venait d'accepter et transformer une mesure posée en avis « hors
     * bornes ». Une seule frontière de validation, et c'est celle qui parle à la balance.
     */
    private fun acceptReading(
        weight: Weight,
        reading: ScaleReading,
        impedanceRefused: Boolean,
    ) {
        val previous = acceptedReading
        val alreadyOnScreen = previous?.sessionId == reading.sessionId &&
            _uiState.value.scale.fromScale &&
            _uiState.value.weight == weight

        if (!alreadyOnScreen) {
            // Une mesure d'une autre session remplace celle-ci : ses trames tardives, elles, ne
            // doivent plus rien compléter (PRD_SCALE 9.4).
            if (previous != null && previous.sessionId != reading.sessionId) {
                closedSessionId = previous.sessionId
            }
            postWeight(weight)
            selectToday()
        }
        acceptedReading = reading

        _uiState.update {
            it.copy(
                scale = it.scale.copy(
                    paired = true,
                    indicator = null,
                    liveHundredths = null,
                    status = null,
                    fromScale = true,
                    arrivalRevision = it.weightRevision,
                    outOfRange = false,
                    // PRD_SCALE 18.3 : uniquement quand le pilote a signalé une mesure impossible.
                    // Un délai écoulé arrive ici avec `impedanceRefused = false`, et un
                    // enregistrement anticipé n'arrive pas ici du tout — sa session est close.
                    barefootHint = impedanceRefused,
                    announcement = EntryScaleAnnouncement.MEASUREMENT_RECEIVED,
                ),
            )
        }
    }

    /**
     * Une mesure stable hors de `30.0–250.0 kg` : un message, et **l'écran reste inchangé**
     * (FR-SCALE-024).
     *
     * Ni la règle, ni la date, ni la provenance ne bougent. Le cas n'est pas théorique : un appui
     * de la main sur le plateau produit une mesure parfaitement stable autour de 18 kg.
     */
    private fun outOfRange() = updateScale {
        it.copy(indicator = null, liveHundredths = null, outOfRange = true)
    }

    /**
     * BR-SCALE-009 : une pesée reçue est un événement présent, donc datée d'aujourd'hui.
     *
     * Écrit à part de [onDateSelected] et non par un appel : celui-ci referme la feuille de
     * sélection et retire la provenance hors d'aujourd'hui, deux effets qui appartiennent au
     * geste de l'utilisateur et à lui seul.
     */
    private fun selectToday() {
        val currentDay = today()
        savedState[KEY_DATE] = currentDay.toString()
        _uiState.update { it.copy(date = currentDay, today = currentDay) }
    }

    /** Fin de session sans mesure : une ligne actionnable, et rien de bloqué (BR-SCALE-011). */
    private fun settled(status: EntryScaleStatus) = updateScale {
        it.copy(indicator = null, liveHundredths = null, status = status)
    }

    /**
     * Bluetooth éteint, permission absente, localisation système coupée (FR-SCALE-025).
     *
     * `Scale unavailable · Open settings` ne se donne qu'une fois par affichage et n'ouvre aucun
     * dialogue ; `Bluetooth is off · Enable` n'a pas cette limite, parce qu'allumer la radio est
     * un geste que l'utilisateur refait volontiers et qui n'a jamais été refusé.
     */
    private fun unavailable(reason: ScaleUnavailableReason) {
        val status = when (reason) {
            ScaleUnavailableReason.BLUETOOTH_OFF -> EntryScaleStatus.BLUETOOTH_OFF
            ScaleUnavailableReason.PERMISSION_MISSING -> EntryScaleStatus.PERMISSION_MISSING
            ScaleUnavailableReason.SYSTEM_LOCATION_OFF -> EntryScaleStatus.SYSTEM_LOCATION_OFF
        }
        val spent = permissionNoticeSpent && status != EntryScaleStatus.BLUETOOTH_OFF
        val announce = !unavailableAnnounced
        unavailableAnnounced = true
        updateScale {
            it.copy(
                indicator = null,
                liveHundredths = null,
                status = status.takeUnless { spent },
                announcement = if (announce) EntryScaleAnnouncement.UNAVAILABLE else it.announcement,
            )
        }
    }

    /**
     * La valeur redevient celle de l'utilisateur (BR-SCALE-013).
     *
     * Provenance retirée, impédance reçue ou attendue invalidée, conseil « pieds nus » retiré,
     * session close pour que la trame suivante de cette liaison n'y change plus rien. Ce qui
     * n'est **pas** fait ici compte autant : la valeur affichée ne bouge pas, et rien n'empêche
     * une nouvelle mesure stable de la remplacer — elle arrivera dans une autre session, donc
     * avec un autre identifiant, et rétablira la provenance (FR-SCALE-022).
     *
     * **Le flux instable se reprend de la même façon**, et c'est la seule chose que cette règle
     * demandait de plus. Un glissement pendant la mesure est un geste comme un autre
     * (FR-SCALE-022, BR-SCALE-013) : il coupe le flux, referme la session et rend l'écran à son
     * propriétaire, au lieu de laisser la balance ramener le poids sous le doigt à la trame
     * suivante. L'éveil de l'écran part avec, puisque le téléphone qu'on venait de poser est de
     * nouveau en main (FR-SCALE-020).
     */
    private fun takeValueBack() {
        val scale = _uiState.value.scale
        val streaming = scale.streaming
        if (!scale.fromScale && !scale.outOfRange && !streaming) return
        if (scale.fromScale || streaming) {
            closeSession()
            acceptedReading = null
        }
        _uiState.update {
            it.copy(
                scale = it.scale.copy(
                    indicator = null,
                    liveHundredths = null,
                    fromScale = false,
                    barefootHint = false,
                    outOfRange = false,
                    announcement = null,
                    keepScreenOn = if (streaming) false else it.scale.keepScreenOn,
                ),
            )
        }
    }

    /**
     * Clôt la session courante et retient son identifiant, pour ignorer ses trames tardives.
     *
     * [acceptedReading] n'est **pas** effacé ici : après un enregistrement, la valeur à l'écran
     * vient toujours de la balance et un second appui doit produire exactement la même mesure.
     * C'est [takeValueBack] qui l'efface, parce que c'est là que la valeur change de propriétaire.
     */
    private fun closeSession() {
        closedSessionId = acceptedReading?.sessionId ?: closedSessionId
        scaleSession?.closeSession()
    }

    /**
     * Modifie l'état de la balance **sans jamais le faire exister** (PRD_SCALE 18.1).
     *
     * Toute mise à jour qui passe par ici marque l'appairage, et le seul chemin qui remet
     * [EntryScaleUiState.ABSENT] est [ScaleSessionState.Absent] lui-même. Un badge ne peut donc
     * pas se poser sur l'écran de quelqu'un qui n'a pas de balance : il n'existe aucun chemin
     * pour l'y mettre.
     */
    private fun updateScale(block: (EntryScaleUiState) -> EntryScaleUiState) {
        _uiState.update { it.copy(scale = block(it.scale.copy(paired = true))) }
    }

    // --- Wiring ---------------------------------------------------------------------

    private fun restoredState(): EntryUiState {
        val restoredHundredths: Int? = savedState[KEY_WEIGHT_HUNDREDTHS]
        val restoredDate: String? = savedState[KEY_DATE]
        val currentDay = today()
        return EntryUiState(
            weight = restoredHundredths?.let(Weight::ofHundredthsClamped) ?: Weight.DEFAULT,
            date = restoredDate?.let(LocalDate::parse) ?: currentDay,
            today = currentDay,
            manualEntry = savedState[KEY_MANUAL_ENTRY] ?: false,
            manualInput = savedState[KEY_MANUAL_INPUT] ?: "",
        )
    }

    private fun seedFromHistory() {
        viewModelScope.launch {
            val latest = runCatching { measurements.observeLatest().first() }.getOrNull()
            // A drag that started before the database answered wins; the value is the user's.
            if (latest != null && !valueIsUserOwned) {
                _uiState.update {
                    it.copy(weight = latest.weight, weightRevision = it.weightRevision + 1)
                }
            }
        }
    }

    private fun observeProfile() {
        viewModelScope.launch {
            profiles.profile.collect { latest ->
                // Retenu tel quel pour la composition corporelle (FR-BODY-001) : la taille, la
                // date de naissance et le sexe doivent être là au moment de l'appui sur
                // `Save measurement`, pas une suspension plus tard.
                profile = latest
                val name = MueValidation.normalizeDisplayName(latest.displayName)
                _uiState.update { state -> state.copy(greeting = name?.let { "Hello $it," }) }
            }
        }
    }

    private fun observePreferences() {
        viewModelScope.launch {
            preferences.preferences.collect { prefs ->
                _uiState.update { it.copy(hapticsEnabled = prefs.hapticsEnabled) }
            }
        }
    }

    companion object {
        /** PRD 15.4 asks for a comprehensible message and a retry, not a silent failure. */
        const val SAVE_ERROR: String = "Could not save this measurement. Try again."

        private const val KEY_WEIGHT_HUNDREDTHS = "entry.weightHundredths"
        private const val KEY_DATE = "entry.date"
        private const val KEY_MANUAL_ENTRY = "entry.manualEntry"
        private const val KEY_MANUAL_INPUT = "entry.manualInput"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as MueApplication
                EntryViewModel(
                    measurements = app.container.measurementRepository,
                    profiles = app.container.userProfileRepository,
                    preferences = app.container.userPreferencesRepository,
                    savedState = createSavedStateHandle(),
                    /*
                     * L'unique point de contact entre `Entry` et le Bluetooth (PRD_SCALE 21.2).
                     *
                     * Paresseux dans le conteneur : le lire ici n'ouvre ni la base, ni la radio.
                     * `BleScaleSessionSource` ne fait rien avant `start()`, que seul le cycle de
                     * vie de l'écran déclenche (`onEntryVisible`), et sans balance enregistrée ce
                     * `start()` lui-même est sans effet — aucun scan, aucune permission demandée.
                     *
                     * `null` reste un cas légitime du constructeur et non un vestige : c'est ainsi
                     * que les tests JVM pilotent l'écran sans Bluetooth, et c'est aussi ce que lit
                     * un `Entry` dont le module balance n'est pas câblé — exactement
                     * `ScaleSessionState.Absent`, donc l'écran du PRD socle (PRD_SCALE 18.1).
                     */
                    scaleSession = app.container.scale.scaleSessionSource,
                )
            }
        }
    }
}
