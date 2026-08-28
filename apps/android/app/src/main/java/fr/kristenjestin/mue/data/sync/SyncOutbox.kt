package fr.kristenjestin.mue.data.sync

import fr.kristenjestin.mue.data.local.database.HealthProfileEntity
import fr.kristenjestin.mue.data.local.database.SyncAggregateStateEntity
import fr.kristenjestin.mue.data.local.database.SyncMutationEntity
import fr.kristenjestin.mue.domain.model.ActivityId
import fr.kristenjestin.mue.domain.model.ActivitySessionDetail
import fr.kristenjestin.mue.domain.model.ExerciseDefinition
import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodAggregates
import fr.kristenjestin.mue.domain.model.FoodId
import fr.kristenjestin.mue.domain.model.FoodLogEntry
import fr.kristenjestin.mue.domain.model.FoodLogEntryId
import fr.kristenjestin.mue.domain.model.MealPlanEntry
import fr.kristenjestin.mue.domain.model.MealPlanKey
import fr.kristenjestin.mue.domain.model.Measurement
import fr.kristenjestin.mue.domain.model.MeasurementSource
import fr.kristenjestin.mue.domain.model.RecipeDetail
import fr.kristenjestin.mue.domain.model.RecipeId
import fr.kristenjestin.mue.domain.model.Sex
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * Mints the outbox row for a local change. It builds the row and nothing else: writing it is
 * the business DAO's job, in the transaction that writes the business row (FR-SYNC-001).
 *
 * The id and the clock are injected so a test can assert on an exact row rather than on the
 * shape of one; both defaults are what the app uses.
 *
 * [now] is a wall clock and is treated as a *proposal*. The send order may not rest on the
 * phone's clock (PRD 12.3 and 13.1), so `SyncJournalDao.sequenced` floors every stamp at one
 * past the highest one already in the outbox, inside the transaction that inserts the row. A
 * clock that steps backwards between two saves â which is what a phone does when it synchronises
 * its time â therefore cannot reorder them.
 *
 * A row is written whether or not a server is paired. Making it conditional would put a read of
 * `sync_state` inside every save for a table that grows by one small row a day, and would make
 * the guarantee of FR-SYNC-001 depend on a flag; the initial synchronisation of FR-SYNC-003
 * sends the whole local history anyway, so the engine is free to collapse what it finds waiting.
 *
 * ## Why the send is announced from here
 *
 * PRD 9.4 lists the moments at which a synchronisation is attempted and *a local change is not
 * one of them*, which is why a birth date typed in the foreground sat at `Changes pending` until
 * the app was backgrounded or the hourly worker came round. [minted] closes that gap. This class
 * is the single place that knows a row was created - for a measurement, for the health profile,
 * for a finished session, for a personal exercise definition and for the four Food aggregates
 * alike - so one announcement here covers every write path there is, and a tenth repository added
 * next month is covered by construction rather than by somebody remembering.
 *
 * It *announces*; it does not schedule. Scheduling means WorkManager, WorkManager means a
 * `Context`, and a `Context` here would cost this class the JVM tests that assert on the exact
 * row it mints. The flow is the seam: the `data` layer says **something was written**, and
 * `di/`, which already holds the application context, turns that into a constrained one-shot.
 */
class SyncOutbox(
    // A UUIDv7 and not `UUID.randomUUID()`. `mutationIdSchema` is `z.uuidv7()` and the server
    // refuses a v4 before it looks at the payload, so this default is the difference between a
    // push that is applied and one that comes back `sync.invalid_payload`. See `MutationIds`.
    private val newMutationId: () -> String = MutationIds::random,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * One signal per minted row, for whoever schedules the send — `SyncContainer`, today.
     *
     * The configuration *is* the argument for a `SharedFlow` here, so it is spelled out:
     *
     * - **`extraBufferCapacity = 1` with `DROP_OLDEST` makes `tryEmit` total.** It never
     *   suspends, never fails and never returns false, so announcing a write is two atomic
     *   operations bolted onto an allocation the caller was making anyway. A save must never
     *   wait on a network decision, and this is what makes that a property of the type instead
     *   of a convention somebody has to keep.
     * - **The value is `Unit`, and the buffer holds one of them.** A hundred rows minted between
     *   two passes of the collector still say exactly one thing — *there is something to send* —
     *   so dropping the older ninety-nine loses nothing. A buffer that grew with the burst would
     *   make a recipe with forty ingredients cost forty wakeups to state what one states.
     * - **`replay = 0`**, so a collector that starts late does not schedule a send for a row the
     *   application start has already pushed, and so a process with nobody listening — every JVM
     *   test in this package, and every DAO test that builds its own outbox — accumulates
     *   nothing whatsoever.
     */
    private val mintedSignals = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Emits once for every row this outbox builds, whatever aggregate it belongs to. */
    val minted: SharedFlow<Unit> = mintedSignals.asSharedFlow()

    /**
     * A measurement is identified by its date on both sides, so the date is the aggregate id.
     *
     * **[Measurement.sourceScaleId] n'est pas lu ici, et ne doit jamais l'être.** PRD_SCALE 16.2
     * et 22 sont explicites : la provenance métier `scale` peut être synchronisée, mais
     * l'identifiant local de la balance, son adresse et son nom ne quittent jamais le téléphone.
     * L'identifiant ne désignerait de toute façon rien ailleurs — c'est un UUID tiré par cette
     * installation-ci (voir `ScaleDevice`).
     */
    fun measurementUpsert(measurement: Measurement): SyncMutationEntity = mutation(
        aggregateType = SyncAggregateStateEntity.TYPE_MEASUREMENT,
        aggregateId = measurement.date.toString(),
        op = SyncMutationEntity.OP_UPSERT,
        payload = Json.encodeToString(
            MeasurementPayload.serializer(),
            MeasurementPayload(
                date = measurement.date.toString(),
                weightCg = measurement.weight.hundredthsKg,
                sourceType = measurement.source.wireValue,
                impedanceOhm = measurement.impedanceOhm,
                bodyComposition = measurement.bodyComposition?.let {
                    BodyCompositionPayload(
                        formulaId = it.formulaId,
                        formulaVersion = it.formulaVersion,
                        inputWeightCg = it.inputWeightCg,
                        inputHeightCm = it.inputHeightCm,
                        inputAgeYears = it.inputAgeYears,
                        inputSex = it.inputSex.wireValue,
                        bodyFatDeciPercent = it.bodyFatDeciPercent,
                        fatFreeMassCg = it.fatFreeMassCg,
                        bodyWaterDeciPercent = it.bodyWaterDeciPercent,
                        restingEnergyKcal = it.restingEnergyKcal,
                    )
                },
            ),
        ),
    )

    fun measurementDelete(date: LocalDate): SyncMutationEntity = mutation(
        aggregateType = SyncAggregateStateEntity.TYPE_MEASUREMENT,
        aggregateId = date.toString(),
        op = SyncMutationEntity.OP_DELETE,
        payload = null,
    )

    /**
     * A finished session, whole (PRD 10.2).
     *
     * One row for the session **and** its metrics, its equipment, its exercises and their sets,
     * because the aggregate is one thing: *"une activité ne peut jamais apparaître sans ses
     * enfants obligatoires à cause d'une synchronisation partielle"*. Five outbox rows would be
     * five chances for four of them to arrive.
     *
     * `ActivityDao.saveDetailWithMutation` writes this row in the same transaction as the five
     * business tables, so FR-SYNC-001 holds for the aggregate rather than for its pieces.
     */
    fun activitySessionUpsert(detail: ActivitySessionDetail): SyncMutationEntity = mutation(
        aggregateType = SyncAggregateStateEntity.TYPE_ACTIVITY_SESSION,
        aggregateId = detail.session.id.value,
        op = SyncMutationEntity.OP_UPSERT,
        payload = Json.encodeToString(ActivitySessionPayload.serializer(), detail.toPayload()),
    )

    fun activitySessionDelete(id: ActivityId): SyncMutationEntity = mutation(
        aggregateType = SyncAggregateStateEntity.TYPE_ACTIVITY_SESSION,
        aggregateId = id.value,
        op = SyncMutationEntity.OP_DELETE,
        payload = null,
    )

    /**
     * A personal exercise definition (PRD 10.1).
     *
     * There is no delete counterpart, and the server refuses one. PRD_ACTIVITIES 9.2 keeps a
     * definition for ever — *"y compris lorsqu'aucune séance ne l'utilise plus"* — the V1 offers
     * no screen that could remove one, and `strength_exercises` holds a `RESTRICT` foreign key
     * onto it, so a tombstone would be a change no client could apply.
     *
     * Only a definition the user created is minted: the seventeen Mue ships are reference data
     * every phone already holds under the same identifiers, which PRD 10.1 marks `Synchronisé:
     * Non`. `ExerciseCatalogDao.findOrCreateWithMutation` is the one caller, and it mints only on
     * the branch that actually creates one.
     */
    fun customExerciseUpsert(definition: ExerciseDefinition): SyncMutationEntity = mutation(
        aggregateType = SyncAggregateStateEntity.TYPE_CUSTOM_EXERCISE,
        aggregateId = definition.id.value,
        op = SyncMutationEntity.OP_UPSERT,
        payload = Json.encodeToString(
            CustomExerciseDefinitionPayload.serializer(),
            definition.toPayload(),
        ),
    )

    /**
     * The four food aggregates of PRD_FOOD 21.2, minted here beside the measurement rather than
     * in a second outbox of their own. One mint point is what keeps `mutation_id`, the pending
     * state and the payload schema version identical for every aggregate the engine will later
     * drain, and `FoodAggregates` already names the four types the generic
     * `sync_aggregate_state` keys them by â so the Food module adds no synchronisation column to
     * any of its five tables (PRD_FOOD 20.1, answered by storage that already exists).
     */
    fun foodUpsert(food: Food): SyncMutationEntity = mutation(
        aggregateType = FoodAggregates.TYPE_FOOD,
        aggregateId = food.id.value,
        op = SyncMutationEntity.OP_UPSERT,
        payload = Json.encodeToString(FoodPayload.serializer(), food.toPayload()),
    )

    fun foodDelete(id: FoodId): SyncMutationEntity = mutation(
        aggregateType = FoodAggregates.TYPE_FOOD,
        aggregateId = id.value,
        op = SyncMutationEntity.OP_DELETE,
        payload = null,
    )

    /** PRD_FOOD 21.2: the recipe **with** its ingredients, in one payload, or not at all. */
    fun recipeUpsert(detail: RecipeDetail): SyncMutationEntity = mutation(
        aggregateType = FoodAggregates.TYPE_RECIPE,
        aggregateId = detail.id.value,
        op = SyncMutationEntity.OP_UPSERT,
        payload = Json.encodeToString(RecipePayload.serializer(), detail.toPayload()),
    )

    fun recipeDelete(id: RecipeId): SyncMutationEntity = mutation(
        aggregateType = FoodAggregates.TYPE_RECIPE,
        aggregateId = id.value,
        op = SyncMutationEntity.OP_DELETE,
        payload = null,
    )

    fun foodLogUpsert(entry: FoodLogEntry): SyncMutationEntity = mutation(
        aggregateType = FoodAggregates.TYPE_FOOD_LOG_ENTRY,
        aggregateId = entry.id.value,
        op = SyncMutationEntity.OP_UPSERT,
        payload = Json.encodeToString(FoodLogEntryPayload.serializer(), entry.toPayload()),
    )

    fun foodLogDelete(id: FoodLogEntryId): SyncMutationEntity = mutation(
        aggregateType = FoodAggregates.TYPE_FOOD_LOG_ENTRY,
        aggregateId = id.value,
        op = SyncMutationEntity.OP_DELETE,
        payload = null,
    )

    /**
     * A proposition is identified by `(date, moment)` on both sides (PRD_FOOD 21.3), so
     * `MealPlanKey.aggregateId` is the aggregate id and no id has to be invented for it â the
     * same argument that makes a measurement's date its own identity.
     */
    fun mealPlanUpsert(entry: MealPlanEntry): SyncMutationEntity = mutation(
        aggregateType = FoodAggregates.TYPE_MEAL_PLAN_ENTRY,
        aggregateId = entry.aggregateId,
        op = SyncMutationEntity.OP_UPSERT,
        payload = Json.encodeToString(MealPlanEntryPayload.serializer(), entry.toPayload()),
    )

    fun mealPlanDelete(key: MealPlanKey): SyncMutationEntity = mutation(
        aggregateType = FoodAggregates.TYPE_MEAL_PLAN_ENTRY,
        aggregateId = key.aggregateId,
        op = SyncMutationEntity.OP_DELETE,
        payload = null,
    )

    /**
     * The health profile is a single aggregate with a single identity, so the aggregate id is a
     * constant â [HealthProfileEntity.ROW_ID], the same `'me'` the table itself is keyed by.
     *
     * There is no delete counterpart. PRD 13.4 gives the profile no deletion: clearing a height
     * is an upsert whose payload says null, which the server can merge field by field, while a
     * tombstone would claim the profile itself ceased to exist â a state the domain does not
     * have and one that FR-SYNC-005 would then use to block its own resurrection.
     *
     * Both fields are nullable and both are always written, so an upsert states the whole
     * aggregate as PRD 12.2 requires: omitting a null would make "the user cleared their birth
     * date" indistinguishable from "this client does not know about birth dates".
     *
     * [sex] rejoint les deux autres pour la même raison et suit la même règle (PRD_SCALE 22 :
     * « le sexe rejoint l'agrégat `HealthProfile` » ; sync PRD 13.4 : fusion champ par champ).
     * Faire évoluer ce payload est sans risque aujourd'hui : `healthProfile` n'est pas dans
     * `SyncWire.SENDABLE_LOCAL_AGGREGATE_TYPES`, donc aucune de ces lignes ne part sur le fil —
     * elles sont journalisées, gardées, et sortiront inchangées le jour où le contrat serveur
     * aura la branche qui leur manque.
     */
    fun healthProfileUpsert(
        heightCm: Int?,
        birthDate: LocalDate?,
        sex: Sex?,
    ): SyncMutationEntity = mutation(
        aggregateType = SyncAggregateStateEntity.TYPE_HEALTH_PROFILE,
        aggregateId = HealthProfileEntity.ROW_ID,
        op = SyncMutationEntity.OP_UPSERT,
        payload = Json.encodeToString(
            HealthProfilePayload.serializer(),
            HealthProfilePayload(
                heightCm = heightCm,
                birthDate = birthDate?.toString(),
                sex = sex?.wireValue,
            ),
        ),
    )

    /**
     * The one funnel every public mint above goes through, and therefore the one place a send
     * has to be announced from.
     *
     * `baseRevision` is left null here and filled by the DAO, which reads it inside the
     * transaction; a revision read before the transaction opened could already be stale.
     */
    private fun mutation(
        aggregateType: String,
        aggregateId: String,
        op: String,
        payload: String?,
    ): SyncMutationEntity = SyncMutationEntity(
        mutationId = newMutationId(),
        aggregateType = aggregateType,
        aggregateId = aggregateId,
        op = op,
        baseRevision = null,
        payload = payload,
        payloadSchemaVersion = PAYLOAD_SCHEMA_VERSION,
        createdAt = now(),
        state = SyncMutationEntity.STATE_PENDING,
        attemptCount = 0,
        lastErrorCode = null,
        lastErrorMessage = null,
    ).also {
        // Announced after the row exists and before it is handed back, so nothing can observe a
        // signal for a row that was never built.
        //
        // It is deliberately *not* announced after the row is written. This class never sees
        // that transaction — the DAO owns it — and waiting for proof of the write would mean
        // threading a callback through nine DAOs, which is precisely how a tenth comes to be
        // forgotten. The cost of announcing early is a send scheduled for a transaction that
        // then rolled back; it finds an outbox with nothing left in it, pushes an empty batch
        // and succeeds. The cost of announcing late would be a change that never leaves.
        mintedSignals.tryEmit(Unit)
    }
}

/**
 * The wire shape of a `Measurement`, versioned by [PAYLOAD_SCHEMA_VERSION] as PRD 12.4
 * requires. Hundredths of a kilogram, as everywhere else in Mue: no float reaches the database
 * and none reaches the wire either, so nothing can be rounded twice.
 *
 * It lives here, next to the writer, because it is the outbox's own format. When the hand
 * written DTOs of PRD 20.4 land in `data/remote`, this is the one place that has to agree with
 * them, and the schema version is what lets the two move independently until they do.
 *
 * ## Ce que ce payload ne porte pas
 *
 * Ni `sourceScaleId`, ni l'adresse, ni le nom de la balance. PRD_SCALE 16.2 et 22 les gardent sur
 * le téléphone ; seule la provenance **métier** ([sourceType]) est synchronisable. C'est une
 * absence de champ et pas un champ laissé vide : un champ optionnel finirait un jour rempli.
 *
 * ## Pourquoi les trois nouveaux champs ont une valeur par défaut
 *
 * Le [Json] par défaut de kotlinx.serialization a `encodeDefaults = false`, donc une propriété
 * égale à sa valeur par défaut n'est **pas écrite**. Une saisie manuelle sans impédance ni
 * composition — l'écrasante majorité de l'historique — produit donc encore exactement
 * `{"date":…,"weightCg":…}`, octet pour octet. Ce n'est pas une élégance : PRD 12.4 veut des
 * migrations additives, et une ligne d'outbox écrite avant une mise à jour doit rester lisible
 * après. Une pesée qui n'a rien de plus à dire ne dit rien de plus.
 *
 * [HealthProfilePayload] fait délibérément l'inverse — ses champs n'ont pas de valeur par défaut
 * et sont donc toujours écrits — parce que sync PRD 13.4 le fusionne **champ par champ** : le
 * serveur doit y distinguer « effacé » de « pas mentionné ». Une mesure est fusionnée en bloc,
 * agrégat entier (PRD_SCALE 22), donc la question ne s'y pose pas de la même façon.
 */
@Serializable
data class MeasurementPayload(
    val date: String,
    val weightCg: Int,
    val sourceType: String = MeasurementSource.MANUAL.wireValue,
    val impedanceOhm: Int? = null,
    val bodyComposition: BodyCompositionPayload? = null,
)

/**
 * La composition corporelle telle qu'elle voyage — **à l'intérieur** de [MeasurementPayload].
 *
 * PRD_SCALE 22 : « `BodyComposition` n'est pas un agrégat synchronisé indépendant ; elle voyage
 * dans le payload complet de `Measurement`, qui porte seul les métadonnées communes ». Il n'existe
 * donc ni `bodyCompositionUpsert`, ni type d'agrégat, ni pierre tombale pour elle : l'absence de
 * cet objet dans un payload complet **est** l'ordre de retirer la composition antérieure
 * (BR-SCALE-007).
 *
 * La date est absente : elle serait toujours celle du parent, et deux copies d'un même fait
 * finissent par se contredire. L'impédance l'est aussi, mais pour la raison inverse — elle
 * appartient à la mesure et non à la composition (FR-BODY-004, BR-SCALE-008), et se synchronise
 * donc même pour une pesée dont aucune composition n'a pu être calculée, ce qui est exactement ce
 * dont le calcul rétroactif de FR-BODY-006 a besoin sur les autres clients.
 *
 * Tout est entier : PRD_SCALE 13.2 exige que Kotlin et TypeScript produisent les **mêmes** entiers
 * stockés pour le même payload, ce qu'un flottant sérialisé ne garantirait pas.
 */
@Serializable
data class BodyCompositionPayload(
    val formulaId: String,
    val formulaVersion: Int,
    val inputWeightCg: Int,
    val inputHeightCm: Int,
    val inputAgeYears: Int,
    val inputSex: String,
    val bodyFatDeciPercent: Int,
    val fatFreeMassCg: Int,
    val bodyWaterDeciPercent: Int,
    val restingEnergyKcal: Int,
)

/**
 * The wire shape of the health profile aggregate (PRD 10.2 and 13.4), versioned by
 * [PAYLOAD_SCHEMA_VERSION] like every other payload.
 *
 * `explicitNulls` is left at its default so both fields are always present: PRD 13.4 lets the
 * server merge the two fields separately when they were not modified concurrently, and it can
 * only do that if it can tell "cleared" from "not mentioned".
 *
 * This payload spent the whole life of the feature with no envelope to travel in:
 * `AGGREGATE_TYPES` in `packages/contracts` did not name `healthProfile`, so `SyncWire.toEnvelope`
 * held every row back and `Data & sync` counted a change that could not fall. It was journalled
 * throughout, which is what made the fix a contract edit rather than a recovery — FR-SYNC-001 is
 * about not losing the change, and a change kept in the outbox goes out unaltered the day the
 * contract grows the branch for it. Six more aggregates have since made the same journey.
 */
@Serializable
data class HealthProfilePayload(
    val heightCm: Int?,
    val birthDate: String?,
    /**
     * `female`, `male` ou `null` (PRD_SCALE FR-PROFILE-007, 22). Sans valeur par défaut, comme
     * ses deux voisins et pour la même raison : la fusion champ par champ de sync PRD 13.4 a
     * besoin de distinguer « l'utilisateur a effacé son sexe » de « ce client ne connaît pas ce
     * champ ».
     */
    val sex: String?,
)

/** Bumped only when an older client could no longer apply a payload (PRD 12.4). */
const val PAYLOAD_SCHEMA_VERSION: Int = 1
