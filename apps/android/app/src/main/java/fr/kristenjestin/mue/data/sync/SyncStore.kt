package fr.kristenjestin.mue.data.sync

import androidx.room.withTransaction
import fr.kristenjestin.mue.data.local.database.MeasurementEntity
import fr.kristenjestin.mue.data.local.database.MueDatabase
import fr.kristenjestin.mue.data.local.database.SyncAggregateStateEntity
import fr.kristenjestin.mue.data.local.database.SyncMutationEntity
import fr.kristenjestin.mue.data.local.database.SyncStateEntity
import fr.kristenjestin.mue.data.remote.sync.DeleteChangeDto
import fr.kristenjestin.mue.data.remote.sync.MeasurementUpsertChangeDto
import fr.kristenjestin.mue.data.remote.sync.SyncChangeDto
import fr.kristenjestin.mue.data.remote.sync.SyncWire
import fr.kristenjestin.mue.domain.model.MeasurementSource

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
     * How many `pending` rows this build has no wire branch for — the health profile of PRD 13.4
     * today. They are journalled, kept, block nothing, and go out unchanged the day
     * `packages/contracts` grows their branch.
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
class RoomSyncStore(private val database: MueDatabase) : SyncStore {

    private val syncDao get() = database.syncDao()

    override suspend fun requeueInflight(): Int = syncDao.requeueInflight()

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
            for (change in changes) applyChange(change)
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
    private suspend fun applyChange(change: SyncChangeDto) {
        // The engine refuses a page it cannot apply *before* calling `applyPage`, so this can
        // only be null if that check were removed. Throwing rolls the transaction back and
        // leaves the cursor where it was, which is what PRD 12.4 demands of exactly this case.
        val localType = requireNotNull(SyncWire.localAggregateType(change.aggregateType)) {
            "no local store for aggregate type ${change.aggregateType}"
        }
        when (change) {
            is MeasurementUpsertChangeDto -> applyMeasurementUpsert(change)

            // La composition suit par `ON DELETE CASCADE` (BR-SCALE-007). Une suppression
            // distante est, elle, complète par nature : elle ne décrit aucun champ, elle retire
            // la mesure entière, et il n'y a donc rien à préserver.
            is DeleteChangeDto -> database.measurementDao().deleteByDate(change.aggregateId)
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
     * Un upsert descendu du serveur, appliqué **comme le payload partiel qu'il est**.
     *
     * `MeasurementPayloadV1Dto` ne transporte que `date` et `weightCg` : ni provenance, ni
     * impédance, ni composition — PRD_SCALE 22 les ajoutera au fil dans une phase ultérieure, et
     * rien n'est ajouté ici. Un changement descendu est donc **partiel par construction**, et
     * BR-SCALE-007 — « un payload complet sans composition retire l'ancienne composition » — ne
     * s'y applique pas : appliquer la règle du payload complet à un payload connu pour être
     * partiel transforme une synchronisation en effaceur.
     *
     * Ce qui se perdrait n'est pas récupérable. L'impédance (BR-SCALE-008, FR-BODY-004) est
     * mesurée par la balance au moment de la pesée et n'existe nulle part ailleurs : le serveur
     * ne peut pas la redescendre puisqu'il ne l'a jamais reçue, et une fois la colonne remise à
     * `NULL` le calcul rétroactif de FR-BODY-006 ne peut plus jamais reconstruire l'estimation.
     * `source_scale_id`, lui, ne quitte jamais le téléphone (PRD_SCALE 16.2 et 22), donc aucune
     * descente ne pourra jamais le rétablir non plus.
     *
     * Trois cas, une seule écriture :
     *
     * 1. **Aucune ligne locale à cette date.** Insertion nue. La provenance écrite est `server` —
     *    la constante que [MeasurementSource] a précisément pour ce cas — plutôt qu'un `manual`
     *    qui affirmerait une saisie à la main que personne n'a faite. Ni impédance ni composition,
     *    parce que le fil n'en portait aucune.
     * 2. **Ligne locale de même poids : rien n'est écrit.** Le changement n'apporte aucune
     *    information nouvelle sur le seul champ qu'il décrit, donc impédance, composition,
     *    provenance et `source_scale_id` restent intacts. C'est le cas de l'écho que le serveur
     *    renvoie de la propre poussée de cet appareil — rien dans ce paquet ne filtre les échos
     *    par `origin` ni ne compare les révisions, si bien que cette branche est la seule chose
     *    qui les rende inoffensifs.
     * 3. **Ligne locale de poids différent : vraie modification distante.** La composition **et**
     *    l'impédance partent, `source_scale_id` est annulé, la provenance devient `server`.
     *    PRD_SCALE 21.1 impose déjà exactement cela localement (BR-SCALE-013) : cette impédance a
     *    été mesurée en même temps que le poids d'origine, et la rattacher à une autre valeur en
     *    ferait une donnée fausse plutôt qu'une donnée ancienne. La règle distante est la règle
     *    locale.
     *
     * **Cette règle est provisoire, et son échéance porte un nom.** Le jour où PRD_SCALE 22 fait
     * porter au fil l'impédance et la composition — c'est-à-dire le jour où un champ s'ajoute à
     * `MeasurementPayloadV1Dto` — la prémisse ci-dessus tombe : le payload descendu devient
     * complet, BR-SCALE-007 s'applique alors **littéralement**, et cette fonction doit redevenir
     * un `upsertAggregate` inconditionnel portant l'impédance et la composition du payload, sans
     * la comparaison de poids ci-dessous. Toucher au payload sans toucher à cette fonction
     * laisserait le pull écrire des mesures dont il ignore la moitié des champs reçus.
     */
    private suspend fun applyMeasurementUpsert(change: MeasurementUpsertChangeDto) {
        val measurementDao = database.measurementDao()
        val existing = measurementDao.findByDate(change.payload.date)?.measurement
        // Cas 2 : le poids descendu est celui qui est déjà là. Ne rien écrire est ici une
        // décision, pas une optimisation — un `upsertAggregate` « équivalent » effacerait
        // l'impédance et la composition sans qu'aucun champ ne change de valeur.
        if (existing != null && existing.weightCg == change.payload.weightCg) return
        measurementDao.upsertAggregate(
            MeasurementEntity(
                date = change.payload.date,
                weightCg = change.payload.weightCg,
                sourceType = MeasurementSource.SERVER.wireValue,
                // Écrits explicitement plutôt que laissés aux valeurs par défaut de l'entité :
                // c'est le cas 3, où l'annulation est l'effet recherché et doit se lire ici.
                sourceScaleId = null,
                impedanceOhm = null,
            ),
            composition = null,
        )
    }
}
