package fr.kristenjestin.mue.data.sync

import androidx.room.withTransaction
import fr.kristenjestin.mue.data.local.database.MueDatabase
import fr.kristenjestin.mue.data.local.database.SyncAggregateStateEntity
import fr.kristenjestin.mue.data.local.database.SyncMutationEntity
import fr.kristenjestin.mue.data.local.database.StrengthExerciseEntity
import fr.kristenjestin.mue.data.local.database.StrengthSetEntity
import fr.kristenjestin.mue.data.local.database.SyncStateEntity
import fr.kristenjestin.mue.data.local.database.toDomain
import fr.kristenjestin.mue.data.remote.sync.ActivitySessionUpsertChangeDto
import fr.kristenjestin.mue.data.remote.sync.CustomExerciseUpsertChangeDto
import fr.kristenjestin.mue.data.remote.sync.DeleteChangeDto
import fr.kristenjestin.mue.data.remote.sync.FoodLogEntryUpsertChangeDto
import fr.kristenjestin.mue.data.remote.sync.FoodUpsertChangeDto
import fr.kristenjestin.mue.data.remote.sync.HealthProfileUpsertChangeDto
import fr.kristenjestin.mue.data.remote.sync.MealPlanEntryUpsertChangeDto
import fr.kristenjestin.mue.data.remote.sync.MeasurementUpsertChangeDto
import fr.kristenjestin.mue.data.remote.sync.RecipeUpsertChangeDto
import fr.kristenjestin.mue.data.remote.sync.SyncChangeDto
import fr.kristenjestin.mue.data.remote.sync.SyncWire
import fr.kristenjestin.mue.data.repository.toDomain
import fr.kristenjestin.mue.domain.model.FoodAggregates
import fr.kristenjestin.mue.domain.model.MealPlanKey
import java.util.UUID

/**
 * Everything the engine does to local storage, as one interface.
 *
 * The engine holds no `MueDatabase`, no DAO and no `withTransaction`. That is not layering for
 * its own sake: the decisions the engine makes — push before pull, requeue on every failure,
 * never advance the cursor past a change it cannot apply — are the ones FR-SYNC-002, FR-SYNC-006
 * and PRD 12.4 are about, and they have to be provable by a JVM test that runs in milliseconds
 * on every commit. Behind this interface they are; behind a Room database they would need an
 * emulator, and a test that needs an emulator is a test that runs on Fridays.
 *
 * [applyPage] is the interface's reason for being shaped this way. PRD 19 requires a remote
 * aggregate to be applied *and its cursor advanced* in one local transaction; expressing that as
 * two calls would make it possible to write one without the other, so it is one call, and the
 * Room implementation is the only place that knows a transaction exists.
 */
interface SyncStore {

    /** See `SyncDao.requeueInflight`. Called first, at engine start, before anything else. */
    suspend fun requeueInflight(): Int

    /**
     * Everything that has to happen to the outbox before a send, because nothing else ever will.
     *
     * Called at engine start, immediately after [requeueInflight], and for the same reason: a
     * queue can be left in a state that no future run of this application could leave on its own,
     * and only something that runs before a send can unstick it. There are three such states, and
     * each was found the same way — a counter in `Data & sync` that would not fall:
     *
     * 1. **An identifier no server will read.** `SyncOutbox` minted a UUIDv4 where
     *    `mutationIdSchema` is `z.uuidv7()`, and the whole batch was refused before its payload
     *    was looked at. See [OutboxRepair].
     * 2. **An aggregate identifier a newer build spells differently.** `MealPlanKey` joined its
     *    pair with a `/`, which `aggregateIdSchema` has never accepted. See [MealPlanIdRepair].
     * 3. **A row that was never journalled at all.** `RoomActivityRepository` took no outbox, so
     *    every session ever recorded — with its metrics, its equipment, its exercises and its sets
     *    — existed on one phone and in no queue. Adding the outbox fixes the next save; the
     *    backfill reaches back for the ones already written.
     *
     * Second and not first, deliberately. [requeueInflight] moves the rows a killed process left
     * `inflight` back to `pending`, and none of the three passes touches an `inflight` row — so
     * running them afterwards is what lets a stranded row be repaired in the same engine start
     * rather than in the next one.
     *
     * The name is narrower than the pass has become and should widen with it; what it must not do
     * is stay accurate by shedding work that has to run here.
     *
     * @return how many rows were repaired or journalled, so a caller can log a real number.
     */
    suspend fun repairUnsendableMutationIds(): Int

    /** The paired server's origin, or null when this phone is paired with nothing. */
    suspend fun serverUrl(): String?

    /** This device's identifier, the `origin.id` every mutation carries (PRD 12.1). */
    suspend fun deviceId(): String?

    /** The opaque cursor, exactly as the server last sent it. Null before the first pull. */
    suspend fun cursor(): String?

    /**
     * The next rows to send, oldest first, **restricted to the aggregate types this build can
     * put on the wire** (`SyncWire.SENDABLE_LOCAL_AGGREGATE_TYPES`).
     *
     * The restriction belongs here rather than in the engine because it has to happen before the
     * window is taken: a queue holding [WIRE_PUSH_MAX_MUTATIONS] undeliverable rows would
     * otherwise return a window with nothing sendable in it and stall every row behind them for
     * ever, which is the blockage FR-SYNC-007 forbids.
     */
    suspend fun pending(limit: Int): List<SyncMutationEntity>

    /**
     * How many `pending` rows this build has no wire branch for.
     *
     * It is zero today, and it has not been for the life of this feature: `healthProfile` was
     * counted here, then the four food aggregates were, and all eight of PRD 10.1's matrix are on
     * the wire now. The number stays reported rather than removed because the state it describes
     * is one this codebase has reached twice — a row journalled by `SyncOutbox` ahead of the
     * contract that could carry it — and a run that says nothing about what it is holding back is
     * how `Changes pending` came to mean two different things.
     */
    suspend fun deferredCount(): Int

    suspend fun markInflight(mutationIds: List<String>)

    suspend fun requeuePending(mutationIds: List<String>)

    /**
     * The server accepted this mutation: drop it from the outbox and record the revision it
     * produced, in one transaction.
     *
     * [revision] is null when the server's decimal counter has no `Long` that holds it. The
     * mutation is still acknowledged — the server did apply it, and keeping the row would send
     * it again forever — but nothing is written where a truncated revision would go.
     */
    suspend fun acknowledge(mutation: SyncMutationEntity, revision: Long?, at: Long)

    /** FR-SYNC-007: the mutation is kept, marked `failed`, and skipped by every later send. */
    suspend fun reject(mutationId: String, code: String?, message: String?)

    /**
     * Applies a page and advances the cursor to [nextCursor] — one transaction, PRD 19, and
     * FR-SYNC-002 step 5.
     *
     * [nextCursor] is written verbatim. It is never parsed, compared or reconstructed here or
     * anywhere else in this package.
     */
    suspend fun applyPage(changes: List<SyncChangeDto>, nextCursor: String, at: Long)

    /** FR-SYNC-008: recorded so `Data & sync` can explain itself, and never alarming. */
    suspend fun recordFailure(code: String?, message: String?)
}

/**
 * [SyncStore] over Room.
 *
 * Every method here is a transaction or a single statement, and the interesting one is
 * [applyPage]: the changes and the cursor commit together or not at all, so a process death
 * during a pull leaves the phone with the cursor it had before and the page is simply fetched
 * again — which FR-SYNC-006 makes free, since re-applying a page repeats no effect.
 */
class RoomSyncStore(
    private val database: MueDatabase,
    /**
     * The same outbox every repository mints through, so a backfilled row is indistinguishable
     * from one a save wrote — same identifier rule, same payload schema version, same pending
     * state. It is defaulted because a test that only exercises the engine has no interest in
     * which instance it is; `SyncContainer` passes its own.
     */
    private val outbox: SyncOutbox = SyncOutbox(),
    /**
     * `session_equipment.id` is minted locally on every write, so a received session needs one per
     * item — the payload carries none, because the identifier is not stable across two saves of
     * the same session and could never be a merge key.
     *
     * It is injected for the same reason `RoomActivityRepository` injects its own: a test that
     * asserts on an exact row cannot do so against `UUID.randomUUID()`.
     */
    private val newRowId: () -> String = { UUID.randomUUID().toString() },
) : SyncStore {

    private val syncDao get() = database.syncDao()

    override suspend fun requeueInflight(): Int = syncDao.requeueInflight()

    /**
     * One transaction, and the identifiers come from [MutationIds] rather than from SQL.
     *
     * The transaction is not for atomicity of the *outcome* — the pass is idempotent, so a
     * process death halfway through simply leaves the rest for the next start — but for the
     * ordinary reason: dozens of single-statement writes outside one are dozens of fsyncs.
     *
     * The decision is `OutboxRepair.verdict`'s and only its, per row. Nothing is decided in the
     * `WHERE` clause of [SyncDao.remintMutationId], so the rule lives in one place and is
     * provable by a JVM test rather than by an emulator.
     */
    override suspend fun repairUnsendableMutationIds(): Int = database.withTransaction {
        var repaired = 0
        for (candidate in syncDao.repairCandidates()) {
            val verdict = OutboxRepair.verdict(
                state = candidate.state,
                mutationId = candidate.mutationId,
                attemptCount = candidate.attemptCount,
                lastErrorCode = candidate.lastErrorCode,
            )
            if (verdict != OutboxRepair.Verdict.REMINT) continue
            syncDao.remintMutationId(candidate.mutationId, MutationIds.random())
            repaired++
        }
        repaired + repairMealPlanIdentifiers() + backfillActivityJournal()
    }

    /**
     * The rows that were never journalled at all, journalled now.
     *
     * `MIGRATION_4_5` created `sync_mutations` empty and nothing has ever backfilled it. For a
     * measurement that is a known and separate gap; for a **session** it is the whole history of
     * the module, because `RoomActivityRepository` took no outbox until this change — every
     * finished session, with its metrics, its equipment, its exercises and its sets, existed on
     * one phone and in no queue anywhere. An uninstall took the lot, and unlike a food row there
     * is no catalogue to re-derive it from.
     *
     * Adding the outbox fixes the *next* save. This is what reaches back for the ones already
     * written, and it is a repair of the same family as the two above: a state no future run of
     * the application could leave on its own.
     *
     * The definitions go first. A session carries a snapshot of every definition it references, so
     * the order is not required for correctness — it is required for the *audit* to read the way
     * the data was created, and `SyncJournalDao.sequenced` makes the outbox drain in the order it
     * was minted.
     *
     * ## What else this pass reaches, and why that is right
     *
     * A definition materialised from a *received* session's snapshot — `ActivityDao
     * .resolveDefinition` inserts one when a session arrives referencing a definition this phone
     * does not hold — is also a candidate: it exists in `exercise_definitions`, it is
     * `is_custom = 1`, and no server has ever acknowledged it *as an aggregate*. Journalling it is
     * the correct answer rather than an accident. PRD 10.1 synchronises personal definitions, this
     * phone now holds one, and pushing it is what makes the two devices agree on the definition
     * itself rather than only on the sessions that quote it.
     *
     * `SyncDao`'s two queries carry the safety argument and the idempotence; nothing is decided
     * here beyond building the payload.
     */
    private suspend fun backfillActivityJournal(): Int {
        var journalled = 0

        for (id in syncDao.unjournalledCustomExercises(SyncAggregateStateEntity.TYPE_CUSTOM_EXERCISE)) {
            val definition = syncDao.exerciseDefinition(id) ?: continue
            enqueueBackfilled(outbox.customExerciseUpsert(definition.toDomain()))
            journalled++
        }

        for (id in syncDao.unjournalledActivitySessions(SyncAggregateStateEntity.TYPE_ACTIVITY_SESSION)) {
            val detail = database.activityDao().findDetailRows(id)?.toDomain() ?: continue
            enqueueBackfilled(outbox.activitySessionUpsert(detail))
            journalled++
        }

        return journalled
    }

    /**
     * One backfilled row, written exactly as a save would have written it.
     *
     * The same three statements `ActivityDao.saveDetailWithMutation` makes, minus the business
     * write — there is nothing to write, the row is already there. `markAggregateAlive` matters
     * even so: it clears a tombstone, and a session that exists in `activity_sessions` is by
     * definition not deleted.
     */
    private suspend fun enqueueBackfilled(mutation: SyncMutationEntity) {
        val row = syncDao.sequenced(mutation)
        val baseRevision = syncDao.revisionOf(row.aggregateType, row.aggregateId)
        syncDao.insertAggregateStateIfAbsent(
            SyncAggregateStateEntity(row.aggregateType, row.aggregateId)
        )
        syncDao.markAggregateAlive(row.aggregateType, row.aggregateId, row.mutationId)
        syncDao.enqueueMutation(row.copy(baseRevision = baseRevision))
    }

    /**
     * The second half of the same pass: aggregate identifiers a newer build spells differently.
     *
     * It runs inside [repairUnsendableMutationIds]'s transaction rather than behind a second
     * [SyncStore] method, because it answers the same question — *what has this queue been left
     * holding that no future run could unstick?* — and because the engine reports one number for
     * "rows this start repaired", which is what a log line is for.
     *
     * `MealPlanIdRepair` carries the argument for why rewriting these identifiers is safe. The
     * short version is structural rather than observational: `mealPlanEntry` has never been in
     * `SENDABLE_LOCAL_AGGREGATE_TYPES`, so no row of that type has ever left a phone, so no
     * server has recorded the old spelling and there is nothing to fork away from.
     */
    private suspend fun repairMealPlanIdentifiers(): Int {
        val type = MealPlanIdRepair.FOOD_MEAL_PLAN_TYPE
        var renamed = 0

        for (candidate in syncDao.aggregateIdRepairCandidates(type)) {
            val verdict = MealPlanIdRepair.verdict(
                aggregateType = candidate.aggregateType,
                aggregateId = candidate.aggregateId,
                state = candidate.state,
            )
            if (verdict != MealPlanIdRepair.Verdict.RENAME) continue
            val canonical = MealPlanIdRepair.canonicalOrNull(candidate.aggregateId) ?: continue
            syncDao.renameMutationAggregateId(candidate.mutationId, canonical)
            renamed++
        }

        /*
         * `sync_aggregate_state` moves too, and it has to.
         *
         * That table is keyed by `(aggregate_type, aggregate_id)` and holds the local tombstone of
         * FR-SYNC-005. Renaming the outbox and leaving this behind would have the next save insert
         * a *second* metadata row under the new spelling with no `deleted_at`, and a proposal the
         * user had deleted would quietly lose the tombstone that stops an old copy resurrecting
         * it.
         *
         * A row whose destination already exists is left alone rather than merged: the primary key
         * would refuse the update, and merging two metadata rows is a decision about server state
         * that this pass has no basis to make. It cannot arise from anything this build does —
         * nothing writes the canonical spelling until the repair has run — so leaving it is
         * leaving a case that does not occur, not papering over one that does.
         */
        val existing = syncDao.aggregateStatesOfType(type).map { it.aggregateId }.toSet()
        for (state in syncDao.aggregateStatesOfType(type)) {
            val canonical = MealPlanIdRepair.canonicalOrNull(state.aggregateId) ?: continue
            if (canonical in existing) continue
            syncDao.renameAggregateState(type, state.aggregateId, canonical)
        }

        return renamed
    }

    override suspend fun serverUrl(): String? = syncDao.syncState()?.serverUrl

    override suspend fun deviceId(): String? = syncDao.syncState()?.deviceId

    override suspend fun cursor(): String? = syncDao.syncState()?.cursor

    override suspend fun pending(limit: Int): List<SyncMutationEntity> =
        syncDao.pendingMutationsOfTypes(SyncWire.SENDABLE_LOCAL_AGGREGATE_TYPES, limit)

    override suspend fun deferredCount(): Int =
        syncDao.countPendingOfOtherTypes(SyncWire.SENDABLE_LOCAL_AGGREGATE_TYPES)

    override suspend fun markInflight(mutationIds: List<String>) {
        if (mutationIds.isEmpty()) return
        syncDao.setState(mutationIds, SyncMutationEntity.STATE_INFLIGHT)
    }

    override suspend fun requeuePending(mutationIds: List<String>) {
        if (mutationIds.isEmpty()) return
        syncDao.setState(mutationIds, SyncMutationEntity.STATE_PENDING)
    }

    override suspend fun acknowledge(mutation: SyncMutationEntity, revision: Long?, at: Long) {
        database.withTransaction {
            if (revision != null) {
                syncDao.insertAggregateStateIfAbsent(
                    SyncAggregateStateEntity(mutation.aggregateType, mutation.aggregateId)
                )
                syncDao.recordAcceptedRevision(
                    aggregateType = mutation.aggregateType,
                    aggregateId = mutation.aggregateId,
                    revision = revision,
                    mutationId = mutation.mutationId,
                    serverUpdatedAt = at,
                )
            }
            syncDao.deleteMutation(mutation.mutationId)
        }
    }

    override suspend fun reject(mutationId: String, code: String?, message: String?) {
        syncDao.markFailed(mutationId, code, message)
    }

    override suspend fun applyPage(changes: List<SyncChangeDto>, nextCursor: String, at: Long) {
        database.withTransaction {
            syncDao.insertSyncStateIfAbsent(SyncStateEntity())
            // In journal order, which is the order the server accepted them in. An upsert
            // followed by a delete of the same aggregate must not become a delete followed by
            // an upsert, and PRD 12.3 makes the sequence — not any clock — that order.
            for (change in changes) applyChange(change, at)
            syncDao.recordSuccess(nextCursor, at)
        }
    }

    override suspend fun recordFailure(code: String?, message: String?) {
        database.withTransaction {
            syncDao.insertSyncStateIfAbsent(SyncStateEntity())
            syncDao.recordFailure(code, message)
        }
    }

    /**
     * One journal entry, business row and metadata together.
     *
     * The metadata row is written from `meta` wholesale rather than field by field: `meta` is
     * the server's complete statement about the aggregate — revision, instants, tombstone,
     * origin and the mutation that produced it — and copying it entire is what keeps
     * `deletedAt` and `revision` from ever disagreeing about the same change.
     */
    private suspend fun applyChange(change: SyncChangeDto, at: Long) {
        // The engine refuses a page it cannot apply *before* calling `applyPage`, so this can
        // only be null if that check were removed. Throwing rolls the transaction back and
        // leaves the cursor where it was, which is what PRD 12.4 demands of exactly this case.
        val localType = requireNotNull(SyncWire.localAggregateType(change.aggregateType)) {
            "no local store for aggregate type ${change.aggregateType}"
        }
        when (change) {
            is MeasurementUpsertChangeDto -> applyMeasurementUpsert(change)

            /*
             * A whole session, written through the same five tables its own save uses — and
             * journalling nothing. A change that arrived from the server has already been
             * journalled there; minting an outbox row for it would push it straight back, take a
             * second revision, and return as another change.
             *
             * The definitions are resolved first, and outside `saveDetail` rather than inside it,
             * because `strength_exercises.exercise_definition_id` is a `RESTRICT` foreign key: an
             * exercise pointing at a definition this phone has never received would abort this
             * transaction, and the cursor is written in it. `ActivityDao.resolveDefinition` turns
             * the snapshot the payload carries into a row, or points the exercise at the
             * definition that already holds the same folded name (PRD_ACTIVITIES 9.2).
             */
            is ActivitySessionUpsertChangeDto -> {
                val activityDao = database.activityDao()
                val exercises = mutableListOf<StrengthExerciseEntity>()
                val sets = mutableListOf<StrengthSetEntity>()
                for (exercise in change.payload.exercises) {
                    val definitionId = activityDao.resolveDefinition(
                        SyncWire.definitionSnapshotEntity(exercise.definition)
                    )
                    exercises += SyncWire.strengthExerciseEntity(
                        sessionId = change.payload.id,
                        exercise = exercise,
                        definitionId = definitionId,
                    )
                    sets += SyncWire.strengthSetEntities(exercise)
                }
                activityDao.saveDetail(
                    session = SyncWire.activitySessionEntity(change.payload, at),
                    metrics = SyncWire.activityMetricEntities(change.payload),
                    equipment = SyncWire.sessionEquipmentEntities(change.payload, newRowId),
                    exercises = exercises,
                    sets = sets,
                )
            }

            is CustomExerciseUpsertChangeDto ->
                database.exerciseCatalogDao()
                    .applyRemote(SyncWire.customExerciseEntity(change.payload))

            is FoodUpsertChangeDto ->
                database.foodDao().upsert(SyncWire.foodEntity(change.payload, at))

            is RecipeUpsertChangeDto -> database.recipeDao().saveDetail(
                recipe = SyncWire.recipeEntity(change.payload, at),
                ingredients = SyncWire.recipeIngredientEntities(change.payload),
            )

            is FoodLogEntryUpsertChangeDto ->
                database.foodLogDao().upsert(SyncWire.foodLogEntryEntity(change.payload, at))

            is MealPlanEntryUpsertChangeDto ->
                database.mealPlanDao().upsert(SyncWire.mealPlanEntryEntity(change.payload, at))

            /*
             * The whole aggregate, replacing the row keyed by `HealthProfileEntity.ROW_ID` —
             * which `SyncWire.healthProfileEntity` supplies rather than `change.aggregateId`.
             * That is the client half of PRD 13.4's "un agrégat unique": a second device's
             * change updates the one row, and there is no code path by which a rival could be
             * inserted beside it.
             *
             * The payload applied here is the server's *merged* one, not what this phone last
             * pushed, so a height this device changed and a birth date another device set
             * arrive together and the two converge on the same profile.
             */
            is HealthProfileUpsertChangeDto ->
                database.healthProfileDao().upsert(SyncWire.healthProfileEntity(change.payload))

            // The delete branch is shared by every aggregate on the wire, so it dispatches on
            // the local type. It used to be able to assume `measurement`; a tombstone for the
            // profile would then have deleted the measurement dated `me`, which is a row that
            // cannot exist — a silent no-op hiding a change that was never applied.
            is DeleteChangeDto -> when (localType) {
                // La composition suit par `ON DELETE CASCADE` (BR-SCALE-007), l'autre moitié de
                // la règle que l'upsert ci-dessus tient par `upsertAggregate`. Une suppression ne
                // décrit aucun champ : elle retire la mesure entière, enfant compris.
                SyncAggregateStateEntity.TYPE_MEASUREMENT ->
                    database.measurementDao().deleteByDate(change.aggregateId)

                // Unreachable: `packages/domain` refuses a health profile delete, so no server
                // journals one. See `HealthProfileDao.clear` for why it is applied rather than
                // refused if one ever arrives.
                SyncAggregateStateEntity.TYPE_HEALTH_PROFILE -> database.healthProfileDao().clear()

                // The metrics, equipment, exercises and sets follow through SQLite's own cascade,
                // which is the same path a local deletion takes.
                SyncAggregateStateEntity.TYPE_ACTIVITY_SESSION ->
                    database.activityDao().deleteSession(change.aggregateId)

                /*
                 * Unreachable, and applied rather than refused if it ever arrives.
                 *
                 * `packages/domain` refuses a `customExerciseDefinition` delete — PRD_ACTIVITIES
                 * 9.2 keeps a definition for ever — so no server journals one. Throwing here would
                 * roll back the transaction that carries the cursor and stop the phone
                 * synchronising for good on a page it could never get past, which is a far worse
                 * answer to "the server did something impossible" than doing nothing. The tombstone
                 * below is still written, so a later upsert is judged against it.
                 */
                SyncAggregateStateEntity.TYPE_CUSTOM_EXERCISE -> Unit

                FoodAggregates.TYPE_FOOD -> database.foodDao().deleteById(change.aggregateId)

                FoodAggregates.TYPE_RECIPE -> database.recipeDao().deleteRecipe(change.aggregateId)

                FoodAggregates.TYPE_FOOD_LOG_ENTRY ->
                    database.foodLogDao().deleteById(change.aggregateId)

                /*
                 * The identifier is split back into the two columns the table is keyed by.
                 *
                 * `MealPlanKey.parseOrNull` reads either separator, so a tombstone the server
                 * journalled under an identifier this phone wrote before the change still deletes
                 * the right row. An unparseable one deletes nothing rather than throwing, for the
                 * reason above: a page that cannot be applied is a cursor that never moves again.
                 */
                FoodAggregates.TYPE_MEAL_PLAN_ENTRY ->
                    MealPlanKey.parseOrNull(change.aggregateId)?.let { key ->
                        database.mealPlanDao().delete(key.plannedOn.toString(), key.slot.id)
                    }

                else -> error("no local delete for aggregate type $localType")
            }
        }
        syncDao.putAggregateState(
            SyncAggregateStateEntity(
                aggregateType = localType,
                aggregateId = change.aggregateId,
                revision = SyncWire.counterOrNull(change.meta.revision),
                serverUpdatedAt = SyncWire.toEpochMillisOrNull(change.meta.updatedAt),
                deletedAt = SyncWire.toEpochMillisOrNull(change.meta.deletedAt),
                lastMutationId = change.meta.lastMutationId,
                originType = change.meta.originType,
                originId = change.meta.originId,
            )
        )
    }

    /**
     * Un upsert descendu du serveur, appliqué **comme le payload complet qu'il est désormais**.
     *
     * ## La règle provisoire est levée
     *
     * Cette fonction comparait le poids descendu au poids local et ne réécrivait rien lorsqu'ils
     * étaient égaux. C'était une garde, pas une optimisation : `MeasurementPayloadV1Dto` ne
     * portait alors que `date` et `weightCg`, un changement descendu était **partiel par
     * construction**, et appliquer BR-SCALE-007 — « un payload complet sans composition retire
     * l'ancienne composition » — à un payload connu pour être partiel aurait fait d'une
     * synchronisation un effaceur d'impédance irremplaçable.
     *
     * Son échéance portait un nom, et elle est arrivée : PRD_SCALE 22 fait traverser au fil la
     * provenance, l'impédance et la composition. La prémisse tombe, et avec elle la garde. Un
     * upsert descendu est de nouveau ce que PRD 12.2 dit qu'un upsert est — l'état complet de
     * l'agrégat — donc il s'écrit d'un bloc, sans lecture préalable et sans condition.
     *
     * ## Ce que cela rend vrai
     *
     * - **BR-SCALE-007 à la lettre.** `upsertAggregate` supprime la composition de la date puis
     *   réécrit celle du payload, s'il y en a une. Une descente sans composition retire donc bien
     *   celle qui existait : c'est une information portée par le payload et non un silence.
     * - **BR-SCALE-008.** L'impédance du payload est écrite telle quelle, y compris pour une
     *   mesure sans composition — ce qui est exactement la matière dont le calcul rétroactif de
     *   FR-BODY-006 a besoin sur chaque client.
     * - **BR-SCALE-015.** `SyncWire.bodyCompositionEntity` reprend `date` et `inputWeightCg` du
     *   parent, donc la composition écrite ne peut pas contredire le poids qui la porte.
     *
     * ## Ce que cela coûte, et pourquoi c'est le prix juste
     *
     * L'écho que le serveur renvoie de la propre poussée de cet appareil est appliqué — rien dans
     * ce paquet ne filtre par `origin` ni ne compare les révisions — et il ne coûte plus rien,
     * puisqu'il rapporte les champs qu'il avait emportés. **Sauf `source_scale_id`, qui ne quitte
     * jamais le téléphone (PRD_SCALE 16.2 et 22) et qu'aucune descente ne peut donc rétablir.**
     * Cette perte est bornée par construction : la colonne est déjà annulable par BR-SCALE-010 —
     * oublier une balance l'annule — et `source_type = 'scale'`, le fait métier, est synchronisé
     * et revient intact.
     *
     * **La contrepartie serveur est une dépendance, pas une hypothèse.** Tant que le gestionnaire
     * de `measurement` de `packages/domain` ne persiste pas et ne renvoie pas ces trois champs,
     * son écho est un payload complet *vide* de composition — et cette fonction, correctement,
     * l'appliquera comme un retrait. Les deux moitiés vont ensemble.
     */
    private suspend fun applyMeasurementUpsert(change: MeasurementUpsertChangeDto) {
        database.measurementDao().upsertAggregate(
            SyncWire.measurementEntity(change.payload),
            SyncWire.bodyCompositionEntity(change.payload),
        )
    }
}
