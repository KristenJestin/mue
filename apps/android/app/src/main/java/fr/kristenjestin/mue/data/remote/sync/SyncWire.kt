package fr.kristenjestin.mue.data.remote.sync

import fr.kristenjestin.mue.data.local.database.ActivityMetricEntity
import fr.kristenjestin.mue.data.local.database.ActivitySessionEntity
import fr.kristenjestin.mue.data.local.database.BodyCompositionEntity
import fr.kristenjestin.mue.data.local.database.ExerciseDefinitionEntity
import fr.kristenjestin.mue.data.local.database.FoodEntity
import fr.kristenjestin.mue.data.local.database.FoodLogEntryEntity
import fr.kristenjestin.mue.data.local.database.HealthProfileEntity
import fr.kristenjestin.mue.data.local.database.MealPlanEntryEntity
import fr.kristenjestin.mue.data.local.database.MeasurementEntity
import fr.kristenjestin.mue.data.local.database.NutrientColumns
import fr.kristenjestin.mue.data.local.database.RecipeEntity
import fr.kristenjestin.mue.data.local.database.RecipeIngredientEntity
import fr.kristenjestin.mue.data.local.database.SessionEquipmentEntity
import fr.kristenjestin.mue.data.local.database.StrengthExerciseEntity
import fr.kristenjestin.mue.data.local.database.StrengthSetEntity
import fr.kristenjestin.mue.data.local.database.SyncAggregateStateEntity
import fr.kristenjestin.mue.data.local.database.SyncMutationEntity
import fr.kristenjestin.mue.data.local.database.encodeSteps
import fr.kristenjestin.mue.data.sync.HealthProfilePayload
import fr.kristenjestin.mue.data.sync.MeasurementPayload
import fr.kristenjestin.mue.data.sync.PAYLOAD_SCHEMA_VERSION
import fr.kristenjestin.mue.domain.model.ExerciseDefinition
import fr.kristenjestin.mue.domain.model.Food
import fr.kristenjestin.mue.domain.model.FoodAggregates
import fr.kristenjestin.mue.domain.model.MealPlanKey
import fr.kristenjestin.mue.domain.model.MeasurementSource
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import java.time.DateTimeException
import java.time.Instant
import java.util.Locale

/**
 * The seam between `sync_mutations` and the wire, and the one place either shape is converted.
 *
 * ## What this file refuses to do
 *
 * It never mints a mutation id. [SyncMutationEntity.mutationId] is minted once, by the
 * transaction that wrote the business row, and is the primary key of the outbox table; every
 * send and every retry reads that same value. FR-SYNC-006's "renvoyer la même mutation retourne
 * le même résultat métier sans répéter son effet" is a property of *where the identifier comes
 * from*, and it survives only as long as nothing downstream is allowed to generate one.
 *
 * ## Every aggregate of PRD 10.1 is here now
 *
 * [toEnvelope] still returns null rather than throwing for a row it has no wire shape for, and
 * the branch is still reachable — a `healthProfile` or `customExerciseDefinition` delete, which
 * nothing mints and the server refuses — but it is no longer the ordinary case. It was: two
 * aggregates journalled every save into rows that could never be sent, and four more that were
 * never journalled at all, while the matrix marked all six `Synchronisé: Oui`.
 *
 * What that looked like from the outside is worth keeping, because it is what a defect of this
 * kind looks like next time: `Data & sync` showed a number of changes waiting that could not
 * fall, and a counter that never moves is indistinguishable from a fault. The fix was never on
 * this side of the wire. It was `AGGREGATE_TYPES`.
 *
 * ## The one value this file rewrites
 *
 * `mealPlanEntry` identifiers and the `fromPlan` of a journalled food-log payload were written
 * with a `/`, which `aggregateIdSchema` has never accepted. Both are normalised here, on the way
 * out, through `MealPlanKey` — which parses either spelling and writes only the canonical one. A
 * stored payload is never edited to achieve that: `MealPlanIdRepair` moves the *identifier*
 * column, and a payload keeps whatever the user's own save put in it.
 */
object SyncWire {

    /**
     * The payload versions this build can apply, per aggregate type — `PullRequest`'s
     * `supportedSchemaVersions`, and the client half of PRD 12.4.
     *
     * It is derived from [PAYLOAD_SCHEMA_VERSION], the constant the outbox stamps its rows with,
     * so the versions the client claims to understand and the versions it actually writes cannot
     * drift apart in a refactor.
     *
     * All eight are declared. A type the client omits is one the server treats as unsupported, so
     * a missing entry here would answer `upgrade_required` for a change this build can apply
     * perfectly well and stop the cursor dead.
     */
    val SUPPORTED_SCHEMA_VERSIONS: Map<String, List<Int>> = mapOf(
        WIRE_AGGREGATE_ACTIVITY_SESSION to listOf(PAYLOAD_SCHEMA_VERSION),
        WIRE_AGGREGATE_CUSTOM_EXERCISE to listOf(PAYLOAD_SCHEMA_VERSION),
        WIRE_AGGREGATE_FOOD to listOf(PAYLOAD_SCHEMA_VERSION),
        WIRE_AGGREGATE_FOOD_LOG_ENTRY to listOf(PAYLOAD_SCHEMA_VERSION),
        WIRE_AGGREGATE_HEALTH_PROFILE to listOf(PAYLOAD_SCHEMA_VERSION),
        WIRE_AGGREGATE_MEAL_PLAN_ENTRY to listOf(PAYLOAD_SCHEMA_VERSION),
        WIRE_AGGREGATE_MEASUREMENT to listOf(PAYLOAD_SCHEMA_VERSION),
        WIRE_AGGREGATE_RECIPE to listOf(PAYLOAD_SCHEMA_VERSION),
    )

    /**
     * The `sync_aggregate_state.aggregate_type` values [toEnvelope] has a wire branch for, and
     * the only ones a send may select.
     *
     * Filtering the queue on this list is not an optimisation, it is what keeps FR-SYNC-007's
     * "une mutation invalide ne bloque pas indéfiniment toutes les mutations suivantes" true of a
     * queue that still contains rows nothing can send. The four food aggregates were journalled
     * at every save and had no branch here for as long as `AGGREGATE_TYPES` lacked one; a send
     * that simply took the oldest `WIRE_PUSH_MAX_MUTATIONS` rows would therefore, once that many
     * food entries had accumulated, return a window containing nothing sendable, and every
     * measurement queued behind them would stop going out **permanently**, with no error
     * anywhere. Selecting by type makes that impossible however many undeliverable rows pile up.
     *
     * The list holds every aggregate today, so the guard protects nothing at this moment — which
     * is exactly when it is worth keeping, because the next aggregate to be journalled ahead of
     * its contract will find it already in place.
     */
    val SENDABLE_LOCAL_AGGREGATE_TYPES: List<String> = listOf(
        SyncAggregateStateEntity.TYPE_ACTIVITY_SESSION,
        SyncAggregateStateEntity.TYPE_CUSTOM_EXERCISE,
        SyncAggregateStateEntity.TYPE_HEALTH_PROFILE,
        SyncAggregateStateEntity.TYPE_MEASUREMENT,
        FoodAggregates.TYPE_FOOD,
        FoodAggregates.TYPE_FOOD_LOG_ENTRY,
        FoodAggregates.TYPE_MEAL_PLAN_ENTRY,
        FoodAggregates.TYPE_RECIPE,
    )

    /**
     * One outbox row as the server reads it, or null when this build has no wire shape for it.
     *
     * @throws SerializationException if a stored payload cannot be read back. That is a local
     * corruption, not a protocol event, and the caller turns it into a rejected mutation rather
     * than a failed synchronisation, so one bad row cannot stall the queue behind it.
     */
    fun toEnvelope(
        mutation: SyncMutationEntity,
        origin: OriginDto,
    ): MutationEnvelopeDto? {
        val clientOccurredAt = toInstantText(mutation.createdAt)
        val baseRevision = mutation.baseRevision?.toString()

        return when (mutation.op) {
            SyncMutationEntity.OP_DELETE -> when (mutation.aggregateType) {
                // A delete is shaped identically for every aggregate type the server knows, so
                // the wire union accepts the enum rather than a literal. It is still gated on
                // the type: a delete of an aggregate the server cannot name is refused there
                // just as an upsert would be, and refusing it here keeps the outbox quiet.
                //
                // `healthProfile` and `customExerciseDefinition` are deliberately absent. Neither
                // has a deletion — PRD 13.4 describes fields that empty rather than a profile
                // that ceases to exist, and PRD_ACTIVITIES 9.2 keeps a definition for ever — and
                // the server refuses one for both. Nothing mints one either; this is the second
                // of the two guards.
                SyncAggregateStateEntity.TYPE_MEASUREMENT,
                SyncAggregateStateEntity.TYPE_ACTIVITY_SESSION,
                FoodAggregates.TYPE_FOOD,
                FoodAggregates.TYPE_RECIPE,
                FoodAggregates.TYPE_FOOD_LOG_ENTRY,
                -> DeleteMutationDto(
                    mutationId = mutation.mutationId,
                    aggregateType = mutation.aggregateType,
                    aggregateId = mutation.aggregateId,
                    baseRevision = baseRevision,
                    payloadSchemaVersion = mutation.payloadSchemaVersion,
                    origin = origin,
                    clientOccurredAt = clientOccurredAt,
                )

                // The identifier is normalised on the way out, so a row journalled before the
                // separator changed goes out correctly whether or not `MealPlanIdRepair` has
                // reached it yet. Belt and braces: the repair moves the column, this makes the
                // wire independent of whether it has.
                FoodAggregates.TYPE_MEAL_PLAN_ENTRY -> DeleteMutationDto(
                    mutationId = mutation.mutationId,
                    aggregateType = mutation.aggregateType,
                    aggregateId = canonicalMealPlanId(mutation.aggregateId),
                    baseRevision = baseRevision,
                    payloadSchemaVersion = mutation.payloadSchemaVersion,
                    origin = origin,
                    clientOccurredAt = clientOccurredAt,
                )

                else -> null
            }

            SyncMutationEntity.OP_UPSERT -> when (mutation.aggregateType) {
                SyncAggregateStateEntity.TYPE_MEASUREMENT -> MeasurementUpsertMutationDto(
                    mutationId = mutation.mutationId,
                    aggregateType = WIRE_AGGREGATE_MEASUREMENT,
                    aggregateId = mutation.aggregateId,
                    baseRevision = baseRevision,
                    payloadSchemaVersion = mutation.payloadSchemaVersion,
                    payload = decode(MeasurementPayloadV1Dto.serializer(), mutation),
                    origin = origin,
                    clientOccurredAt = clientOccurredAt,
                )

                /*
                 * The stored payload is re-read through the *wire* DTO rather than reused from
                 * `SyncOutbox.HealthProfilePayload`, exactly as the measurement above is. The
                 * two shapes agree today and are owned by different files; decoding is what
                 * makes a disagreement a caught `SerializationException` on one row instead of
                 * a body the server rejects for the whole batch.
                 *
                 * `aggregateId` is not copied from the row. PRD 13.4 gives the account one
                 * profile and the contract pins its identifier as a literal, so the DTO's own
                 * default is the only value that can appear — an outbox row that somehow held
                 * another one cannot smuggle it onto the wire.
                 */
                SyncAggregateStateEntity.TYPE_HEALTH_PROFILE -> HealthProfileUpsertMutationDto(
                    mutationId = mutation.mutationId,
                    baseRevision = baseRevision,
                    payloadSchemaVersion = mutation.payloadSchemaVersion,
                    payload = decode(HealthProfilePayloadV1Dto.serializer(), mutation),
                    origin = origin,
                    clientOccurredAt = clientOccurredAt,
                )

                SyncAggregateStateEntity.TYPE_ACTIVITY_SESSION -> ActivitySessionUpsertMutationDto(
                    mutationId = mutation.mutationId,
                    aggregateId = mutation.aggregateId,
                    baseRevision = baseRevision,
                    payloadSchemaVersion = mutation.payloadSchemaVersion,
                    payload = decode(ActivitySessionPayloadV1Dto.serializer(), mutation),
                    origin = origin,
                    clientOccurredAt = clientOccurredAt,
                )

                SyncAggregateStateEntity.TYPE_CUSTOM_EXERCISE -> CustomExerciseUpsertMutationDto(
                    mutationId = mutation.mutationId,
                    aggregateId = mutation.aggregateId,
                    baseRevision = baseRevision,
                    payloadSchemaVersion = mutation.payloadSchemaVersion,
                    payload = decode(CustomExerciseDefinitionPayloadV1Dto.serializer(), mutation),
                    origin = origin,
                    clientOccurredAt = clientOccurredAt,
                )

                FoodAggregates.TYPE_FOOD -> FoodUpsertMutationDto(
                    mutationId = mutation.mutationId,
                    aggregateId = mutation.aggregateId,
                    baseRevision = baseRevision,
                    payloadSchemaVersion = mutation.payloadSchemaVersion,
                    payload = decode(FoodPayloadV1Dto.serializer(), mutation),
                    origin = origin,
                    clientOccurredAt = clientOccurredAt,
                )

                FoodAggregates.TYPE_RECIPE -> RecipeUpsertMutationDto(
                    mutationId = mutation.mutationId,
                    aggregateId = mutation.aggregateId,
                    baseRevision = baseRevision,
                    payloadSchemaVersion = mutation.payloadSchemaVersion,
                    payload = decode(RecipePayloadV1Dto.serializer(), mutation),
                    origin = origin,
                    clientOccurredAt = clientOccurredAt,
                )

                /*
                 * `fromPlan` is normalised rather than repaired.
                 *
                 * A line logged from a proposal carries that proposal's identifier, and every one
                 * written before the separator changed spells it with a `/`. Rewriting it here
                 * costs one parse and leaves the stored payload — the user's own record of what
                 * they ate — exactly as it was written, which is the line FR-SYNC-007 draws around
                 * repairing local data to fix a protocol problem.
                 */
                FoodAggregates.TYPE_FOOD_LOG_ENTRY -> {
                    val payload = decode(FoodLogEntryPayloadV1Dto.serializer(), mutation)
                    FoodLogEntryUpsertMutationDto(
                        mutationId = mutation.mutationId,
                        aggregateId = mutation.aggregateId,
                        baseRevision = baseRevision,
                        payloadSchemaVersion = mutation.payloadSchemaVersion,
                        payload = payload.copy(
                            fromPlan = payload.fromPlan?.let(::canonicalMealPlanId),
                        ),
                        origin = origin,
                        clientOccurredAt = clientOccurredAt,
                    )
                }

                /*
                 * The identifier is rebuilt from the payload rather than copied from the row.
                 *
                 * The server refuses a mutation whose `aggregateId` does not equal
                 * `<payload.plannedOn>:<payload.slot>`, so deriving it is the only way the two can
                 * never disagree — and it makes this branch correct for a row the repair pass has
                 * not reached, which is what a phone upgrading mid-queue actually has.
                 */
                FoodAggregates.TYPE_MEAL_PLAN_ENTRY -> {
                    val payload = decode(MealPlanEntryPayloadV1Dto.serializer(), mutation)
                    MealPlanEntryUpsertMutationDto(
                        mutationId = mutation.mutationId,
                        aggregateId = "${payload.plannedOn}${MealPlanKey.SEPARATOR}${payload.slot}",
                        baseRevision = baseRevision,
                        payloadSchemaVersion = mutation.payloadSchemaVersion,
                        payload = payload,
                        origin = origin,
                        clientOccurredAt = clientOccurredAt,
                    )
                }

                else -> null
            }

            else -> null
        }
    }

    /**
     * A stored payload, read back through the wire DTO that will carry it.
     *
     * The message names the aggregate rather than the column, because that is what the caller
     * turns into a `Sync issue` a person reads.
     */
    private fun <T> decode(serializer: KSerializer<T>, mutation: SyncMutationEntity): T =
        SyncJson.instance.decodeFromString(
            serializer,
            mutation.payload
                ?: throw SerializationException(
                    "an upsert of ${mutation.aggregateId} carries no payload",
                ),
        )

    /**
     * A meal plan identifier as the contract spells it, from either spelling.
     *
     * An identifier that parses as neither is returned unchanged: it will be refused by the
     * server with a message naming it, which is more useful than this file silently inventing a
     * value for a row it does not understand.
     */
    fun canonicalMealPlanId(stored: String): String =
        MealPlanKey.parseOrNull(stored)?.aggregateId ?: stored

    /**
     * A canonical decimal counter as a [Long], or null when it does not fit.
     *
     * The contract sizes `Revision` and `Sequence` as unsigned 64-bit, and `sync_aggregate_state`
     * stores a revision in a signed SQLite integer. Everything below 2^63 round-trips exactly;
     * above it there is no truthful local representation, so this returns null and the caller
     * treats the response as unapplicable rather than silently storing a truncated revision that
     * every later mutation would quote back as its base.
     */
    fun counterOrNull(value: String): Long? =
        if (value.isEmpty() || !value.all { it in '0'..'9' }) null else value.toLongOrNull()

    /** Epoch milliseconds as the ISO-8601 UTC instant the contract's `Instant` describes. */
    fun toInstantText(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).toString()

    /**
     * An ISO-8601 instant as epoch milliseconds, or null when it is not one.
     *
     * Sub-millisecond precision is truncated, which is the honest conversion into a store whose
     * every instant is a millisecond count. The value is used for display and audit — PRD 12.3
     * forbids it deciding order — so the lost microseconds decide nothing.
     */
    fun toEpochMillisOrNull(text: String?): Long? {
        if (text == null) return null
        return try {
            Instant.parse(text).toEpochMilli()
        } catch (_: DateTimeException) {
            null
        }
    }

    /**
     * The `sync_aggregate_state.aggregate_type` a wire aggregate type is stored under, or null
     * when this build has no local home for it.
     *
     * The two vocabularies happen to agree today — `"measurement"` on both sides, and the same
     * for the other seven — and they are translated anyway, because they are owned by different
     * repositories and a change to `AGGREGATE_TYPES` must not be able to silently repoint a Room
     * column.
     */
    fun localAggregateType(wireType: String): String? = when (wireType) {
        WIRE_AGGREGATE_MEASUREMENT -> SyncAggregateStateEntity.TYPE_MEASUREMENT
        WIRE_AGGREGATE_HEALTH_PROFILE -> SyncAggregateStateEntity.TYPE_HEALTH_PROFILE
        WIRE_AGGREGATE_ACTIVITY_SESSION -> SyncAggregateStateEntity.TYPE_ACTIVITY_SESSION
        WIRE_AGGREGATE_CUSTOM_EXERCISE -> SyncAggregateStateEntity.TYPE_CUSTOM_EXERCISE
        WIRE_AGGREGATE_FOOD -> FoodAggregates.TYPE_FOOD
        WIRE_AGGREGATE_RECIPE -> FoodAggregates.TYPE_RECIPE
        WIRE_AGGREGATE_FOOD_LOG_ENTRY -> FoodAggregates.TYPE_FOOD_LOG_ENTRY
        WIRE_AGGREGATE_MEAL_PLAN_ENTRY -> FoodAggregates.TYPE_MEAL_PLAN_ENTRY
        else -> null
    }

    /**
     * The payload of a health profile upsert, as the outbox stores it.
     *
     * It exists so `SyncOutbox`'s [HealthProfilePayload] and the wire's
     * [HealthProfilePayloadV1Dto] have one crossing point instead of two, and so a test can
     * feed a real height and a real birth date through it rather than assert a shape.
     *
     * [HealthProfilePayload.sex] traverse ici depuis PRD_SCALE 22. Il ne traversait pas : le DTO
     * ne déclarait pas le champ, `SyncJson` a `ignoreUnknownKeys = true`, et relire le payload
     * stocké à travers un DTO qui ignore une clé **la retire**. Le sexe était journalisé à chaque
     * enregistrement de profil et perdu à la sortie du téléphone, sans erreur nulle part.
     */
    fun healthProfilePayload(
        payload: HealthProfilePayload,
    ): HealthProfilePayloadV1Dto = HealthProfilePayloadV1Dto(
        heightCm = payload.heightCm,
        birthDate = payload.birthDate,
        sex = payload.sex,
    )

    /**
     * The local row a received health profile becomes.
     *
     * [HealthProfileEntity.ROW_ID] is written rather than `change.aggregateId`, and that is the
     * client half of "un agrégat unique" (PRD 13.4): the entity's primary key is a constant, so
     * a change from a second device updates the one row instead of inserting beside it. There is
     * no branch in which two profile rows can exist locally.
     */
    fun healthProfileEntity(
        payload: HealthProfilePayloadV1Dto,
    ): HealthProfileEntity = HealthProfileEntity(
        id = HealthProfileEntity.ROW_ID,
        heightCm = payload.heightCm,
        birthDate = payload.birthDate,
        sex = payload.sex,
    )

    /**
     * The payload of a measurement upsert, as the outbox stores it — the measurement's half of
     * the crossing point [healthProfilePayload] is the profile's.
     *
     * Les cinq champs traversent, et `sourceScaleId` n'existe ni d'un côté ni de l'autre :
     * `MeasurementPayload` ne le lit déjà pas de la mesure (PRD_SCALE 16.2 et 22), et
     * [MeasurementPayloadV1Dto] n'a pas de champ pour le recevoir. La règle est tenue deux fois,
     * par deux absences, plutôt que par une ligne qu'il faudrait se souvenir de ne pas écrire.
     */
    fun measurementPayload(
        payload: MeasurementPayload,
    ): MeasurementPayloadV1Dto = MeasurementPayloadV1Dto(
        date = payload.date,
        weightCg = payload.weightCg,
        sourceType = payload.sourceType,
        impedanceOhm = payload.impedanceOhm,
        bodyComposition = payload.bodyComposition?.let {
            BodyCompositionV1Dto(
                formulaId = it.formulaId,
                formulaVersion = it.formulaVersion,
                inputWeightCg = it.inputWeightCg,
                inputHeightCm = it.inputHeightCm,
                inputAgeYears = it.inputAgeYears,
                inputSex = it.inputSex,
                bodyFatDeciPercent = it.bodyFatDeciPercent,
                fatFreeMassCg = it.fatFreeMassCg,
                bodyWaterDeciPercent = it.bodyWaterDeciPercent,
                restingEnergyKcal = it.restingEnergyKcal,
            )
        },
    )

    /**
     * The local row a received measurement becomes — poids, provenance et impédance.
     *
     * **[MeasurementEntity.sourceScaleId] est écrit `null`, toujours.** L'identifiant de la
     * balance ne traverse jamais le fil (PRD_SCALE 16.2 et 22), donc un changement descendu ne
     * peut rien en dire ; le conserver reviendrait à rattacher la version du serveur à un appareil
     * que cette version ne mentionne pas. La perte est bornée par construction : c'est la colonne
     * que BR-SCALE-010 rend déjà annulable — oublier une balance l'annule aussi — et
     * `source_type = 'scale'`, lui, est le fait métier et il est synchronisé.
     *
     * Une provenance absente ou illisible devient [MeasurementSource.SERVER] et non `manual`.
     * `manual` affirmerait une saisie à la main que personne n'a faite, alors que la seule chose
     * que ce build sache d'un payload muet sur sa provenance, c'est qu'il est descendu du serveur
     * — ce dont [MeasurementSource] a la constante exactement pour ce cas.
     */
    fun measurementEntity(payload: MeasurementPayloadV1Dto): MeasurementEntity {
        val source = payload.sourceType?.let(MeasurementSource::fromWire)
            ?: MeasurementSource.SERVER
        return MeasurementEntity(
            date = payload.date,
            weightCg = payload.weightCg,
            sourceType = source.wireValue,
            sourceScaleId = null,
            impedanceOhm = payload.impedanceOhm,
        )
    }

    /**
     * La composition d'un changement descendu, ou `null` — auquel cas
     * `MeasurementDao.upsertAggregate` retire celle qui existait (BR-SCALE-007).
     *
     * `date` et `inputWeightCg` sont repris **du parent** et non de l'objet imbriqué, exactement
     * comme le fait `Measurement.toCompositionEntity` pour une écriture locale. BR-SCALE-015 est
     * une contrainte du schéma Zod, or un DTO écrit à la main ne porte pas les `refine` du
     * contrat : les prendre du parent rend l'inégalité impossible à écrire ici plutôt que
     * détectable après coup, et il n'existe aucune requête qui la vérifierait après coup.
     */
    fun bodyCompositionEntity(
        payload: MeasurementPayloadV1Dto,
    ): BodyCompositionEntity? = payload.bodyComposition?.let {
        BodyCompositionEntity(
            date = payload.date,
            formulaId = it.formulaId,
            formulaVersion = it.formulaVersion,
            inputWeightCg = payload.weightCg,
            inputHeightCm = it.inputHeightCm,
            inputAgeYears = it.inputAgeYears,
            inputSex = it.inputSex,
            bodyFatDeciPercent = it.bodyFatDeciPercent,
            fatFreeMassCg = it.fatFreeMassCg,
            bodyWaterDeciPercent = it.bodyWaterDeciPercent,
            restingEnergyKcal = it.restingEnergyKcal,
        )
    }

    // --- the local rows a received change becomes ------------------------------------------

    /**
     * `created_at` and `updated_at` are the *local* audit stamps of a row, not business values.
     *
     * PRD_ACTIVITIES 8.2 is explicit that they are "métadonnées d'audit portées par le seul
     * stockage" which no display rule reads, and the server's own instants for the aggregate live
     * in `sync_aggregate_state` where a reader can find them. So a received row is stamped with
     * the moment it was applied here, and the DAOs that preserve an existing `created_at` on an
     * update keep the first one.
     */
    fun activitySessionEntity(
        payload: ActivitySessionPayloadV1Dto,
        at: Long,
    ): ActivitySessionEntity = ActivitySessionEntity(
        id = payload.id,
        movement = payload.movement,
        customMovementName = payload.customMovementName,
        environment = payload.environment,
        startedOn = payload.startedOn,
        startedAtTime = payload.startedAtTime,
        durationSeconds = payload.durationSeconds,
        perceivedEffort = payload.perceivedEffort,
        notes = payload.notes,
        source = payload.source,
        createdAt = at,
        updatedAt = at,
    )

    fun activityMetricEntities(
        payload: ActivitySessionPayloadV1Dto,
    ): List<ActivityMetricEntity> = payload.metrics.map { metric ->
        ActivityMetricEntity(
            sessionId = payload.id,
            kind = metric.kind,
            value = metric.value,
            source = metric.source,
        )
    }

    /**
     * The equipment rows, with a fresh identifier per item and the folded name computed here.
     *
     * `session_equipment.id` is minted locally on every save — `RoomActivityRepository.save` does
     * the same — so the payload carries none and this invents one. `custom_name_folded` is the
     * column the unique index compares and is a function of the name, so it is derived rather than
     * carried: a payload able to state a fold that did not match its own name would be a payload
     * able to defeat the index.
     */
    fun sessionEquipmentEntities(
        payload: ActivitySessionPayloadV1Dto,
        newRowId: () -> String,
    ): List<SessionEquipmentEntity> = payload.equipment.map { item ->
        SessionEquipmentEntity(
            id = newRowId(),
            sessionId = payload.id,
            equipmentType = item.equipmentType,
            customName = item.customName,
            customNameFolded = item.customName?.trim()?.lowercase(Locale.ROOT).orEmpty(),
            position = item.position,
        )
    }

    /** The definition snapshot inside an exercise, as the row it may have to become. */
    fun definitionSnapshotEntity(
        snapshot: ExerciseDefinitionSnapshotDto,
    ): ExerciseDefinitionEntity = ExerciseDefinitionEntity(
        id = snapshot.id,
        name = snapshot.name,
        nameFolded = ExerciseDefinition.fold(snapshot.name),
        trackingMode = snapshot.trackingMode,
        equipment = snapshot.equipment,
        isCustom = snapshot.isCustom,
    )

    fun strengthExerciseEntity(
        sessionId: String,
        exercise: StrengthExerciseDto,
        definitionId: String,
    ): StrengthExerciseEntity = StrengthExerciseEntity(
        id = exercise.id,
        sessionId = sessionId,
        exerciseDefinitionId = definitionId,
        position = exercise.position,
        notes = exercise.notes,
    )

    fun strengthSetEntities(exercise: StrengthExerciseDto): List<StrengthSetEntity> =
        exercise.sets.map { set ->
            StrengthSetEntity(
                id = set.id,
                strengthExerciseId = exercise.id,
                position = set.position,
                setType = set.setType,
                repetitions = set.repetitions,
                loadGrams = set.loadGrams,
                durationSeconds = set.durationSeconds,
                perceivedEffort = set.perceivedEffort,
            )
        }

    /**
     * A received personal definition.
     *
     * `is_custom` is `true` and is not read from the payload, because the payload has no such
     * field: PRD 10.1 does not synchronise the definitions Mue ships, so this aggregate can only
     * ever describe a personal one and the flag is a property of the type rather than of the
     * value. The fold is computed for the reason [definitionSnapshotEntity] gives.
     */
    fun customExerciseEntity(
        payload: CustomExerciseDefinitionPayloadV1Dto,
    ): ExerciseDefinitionEntity = ExerciseDefinitionEntity(
        id = payload.id,
        name = payload.name,
        nameFolded = ExerciseDefinition.fold(payload.name),
        trackingMode = payload.trackingMode,
        equipment = payload.equipment,
        isCustom = true,
    )

    /**
     * A received food.
     *
     * The two folded columns are derived, as every fold in this file is. `brand_folded` is null
     * exactly when the brand is, so an absent brand does not become an empty one a search would
     * match.
     */
    fun foodEntity(payload: FoodPayloadV1Dto, at: Long): FoodEntity = FoodEntity(
        id = payload.id,
        name = payload.name,
        nameFolded = Food.fold(payload.name),
        source = payload.source,
        referenceUnit = payload.referenceUnit,
        per100 = NutrientColumns(
            energyMilliKcal = payload.energyMilliKcal,
            proteinMilligrams = payload.proteinMilligrams,
            carbsMilligrams = payload.carbsMilligrams,
            fatMilligrams = payload.fatMilligrams,
            fibreMilligrams = payload.fibreMilligrams,
        ),
        brand = payload.brand,
        brandFolded = payload.brand?.let(Food::fold),
        barcode = payload.barcode,
        sourceId = payload.sourceId,
        sourceVersion = payload.sourceVersion,
        servingLabel = payload.servingLabel,
        servingThousandths = payload.servingThousandths,
        cookedRatioThousandths = payload.cookedRatioThousandths,
        rawLabel = payload.rawLabel,
        cookedLabel = payload.cookedLabel,
        imageRef = payload.imageRef,
        createdAt = at,
        updatedAt = at,
    )

    fun recipeEntity(payload: RecipePayloadV1Dto, at: Long): RecipeEntity = RecipeEntity(
        id = payload.id,
        name = payload.name,
        nameFolded = Food.fold(payload.name),
        type = payload.type,
        baseServings = payload.baseServings,
        description = payload.description,
        prepTimeMinutes = payload.prepTimeMinutes,
        steps = encodeSteps(payload.steps),
        imageRef = payload.imageRef,
        isFavourite = payload.isFavourite,
        createdAt = at,
        updatedAt = at,
    )

    fun recipeIngredientEntities(
        payload: RecipePayloadV1Dto,
    ): List<RecipeIngredientEntity> = payload.ingredients.map { ingredient ->
        RecipeIngredientEntity(
            id = ingredient.id,
            recipeId = payload.id,
            foodId = ingredient.foodId,
            quantityThousandths = ingredient.quantityThousandths,
            unit = ingredient.unit,
            position = ingredient.position,
            foodName = ingredient.foodName,
        )
    }

    /**
     * A received journal line.
     *
     * `fromPlan` is split back into the two columns the table holds — `planned_on` and
     * `plan_slot` — rather than stored as a composite. That is the same reason the meal plan's
     * own table is keyed by the pair: a composite identifier is a wire encoding, and keeping one
     * in a column is what made a separator change a repair pass instead of an edit.
     */
    fun foodLogEntryEntity(payload: FoodLogEntryPayloadV1Dto, at: Long): FoodLogEntryEntity {
        val plan = payload.fromPlan?.let(MealPlanKey::parseOrNull)
        return FoodLogEntryEntity(
            id = payload.id,
            consumedOn = payload.consumedOn,
            consumedAt = payload.consumedAt,
            slot = payload.slot,
            kind = payload.kind,
            title = payload.title,
            nutrients = NutrientColumns(
                energyMilliKcal = payload.energyMilliKcal,
                proteinMilligrams = payload.proteinMilligrams,
                carbsMilligrams = payload.carbsMilligrams,
                fatMilligrams = payload.fatMilligrams,
                fibreMilligrams = payload.fibreMilligrams,
            ),
            estimation = payload.estimation,
            sourceRef = payload.sourceRef,
            amountLabel = payload.amountLabel,
            quantityThousandths = payload.quantityThousandths,
            quantityUnit = payload.quantityUnit,
            portionsThousandths = payload.portionsThousandths,
            weighedCooked = payload.weighedCooked,
            plannedOn = plan?.plannedOn?.toString(),
            planSlot = plan?.slot?.id,
            createdAt = at,
            updatedAt = at,
        )
    }

    fun mealPlanEntryEntity(
        payload: MealPlanEntryPayloadV1Dto,
        at: Long,
    ): MealPlanEntryEntity = MealPlanEntryEntity(
        plannedOn = payload.plannedOn,
        slot = payload.slot,
        recipeId = payload.recipeId,
        plannedServingsThousandths = payload.plannedServingsThousandths,
        consumedLogEntryId = payload.consumedLogEntryId,
        createdAt = at,
        updatedAt = at,
    )

    /** The identity Android stamps its own mutations with (PRD 12.1). */
    fun androidOrigin(deviceId: String): OriginDto =
        OriginDto(type = OriginDto.TYPE_ANDROID, id = deviceId)
}
