package fr.kristenjestin.mue.data.sync

import fr.kristenjestin.mue.data.local.database.SyncAggregateStateEntity
import fr.kristenjestin.mue.data.local.database.SyncMutationEntity
import fr.kristenjestin.mue.data.remote.sync.AggregateMetaDto
import fr.kristenjestin.mue.data.remote.sync.DeleteChangeDto
import fr.kristenjestin.mue.data.remote.sync.HealthProfilePayloadV1Dto
import fr.kristenjestin.mue.data.remote.sync.HealthProfileUpsertChangeDto
import fr.kristenjestin.mue.data.remote.sync.MeasurementPayloadV1Dto
import fr.kristenjestin.mue.data.remote.sync.MeasurementUpsertChangeDto
import fr.kristenjestin.mue.data.remote.sync.MueErrorDto
import fr.kristenjestin.mue.data.remote.sync.MutationAppliedDto
import fr.kristenjestin.mue.data.remote.sync.MutationDuplicateDto
import fr.kristenjestin.mue.data.remote.sync.MutationRejectedDto
import fr.kristenjestin.mue.data.remote.sync.MutationResultDto
import fr.kristenjestin.mue.data.remote.sync.OriginDto
import fr.kristenjestin.mue.data.remote.sync.PullPageDto
import fr.kristenjestin.mue.data.remote.sync.PullRequestDto
import fr.kristenjestin.mue.data.remote.sync.PullResponseDto
import fr.kristenjestin.mue.data.remote.sync.PullUpgradeRequiredDto
import fr.kristenjestin.mue.data.remote.sync.PushRequestDto
import fr.kristenjestin.mue.data.remote.sync.PushResponseDto
import fr.kristenjestin.mue.data.remote.sync.SyncApi
import fr.kristenjestin.mue.data.remote.sync.SyncChangeDto
import fr.kristenjestin.mue.data.remote.sync.SyncErrorCodes
import fr.kristenjestin.mue.data.remote.sync.SyncTransportException
import fr.kristenjestin.mue.data.remote.sync.WIRE_AGGREGATE_MEASUREMENT
import fr.kristenjestin.mue.domain.model.FoodAggregates

/** Outbox rows, wire values and a scripted [SyncApi], so the engine tests read as scenarios. */
object SyncFixtures {

    const val CURSOR_A: String = "eyJ2IjoxLCJzZXEiOiI0MSJ9"
    const val CURSOR_B: String = "eyJ2IjoxLCJzZXEiOiI0MiJ9"
    const val CURSOR_C: String = "eyJ2IjoxLCJzZXEiOiI0MyJ9"

    const val SERVER_TIME: String = "2026-08-25T06:12:06.000Z"

    fun measurementUpsert(
        mutationId: String,
        date: String = "2026-08-25",
        weightCg: Int = 7_845,
        createdAt: Long = 1_770_000_000_000L,
        baseRevision: Long? = null,
        state: String = SyncMutationEntity.STATE_PENDING,
    ): SyncMutationEntity = SyncMutationEntity(
        mutationId = mutationId,
        aggregateType = SyncAggregateStateEntity.TYPE_MEASUREMENT,
        aggregateId = date,
        op = SyncMutationEntity.OP_UPSERT,
        baseRevision = baseRevision,
        payload = """{"date":"$date","weightCg":$weightCg}""",
        payloadSchemaVersion = PAYLOAD_SCHEMA_VERSION,
        createdAt = createdAt,
        state = state,
        attemptCount = 0,
        lastErrorCode = null,
        lastErrorMessage = null,
    )

    fun measurementDelete(
        mutationId: String,
        date: String = "2026-08-24",
        createdAt: Long = 1_770_000_000_000L,
        state: String = SyncMutationEntity.STATE_PENDING,
    ): SyncMutationEntity = SyncMutationEntity(
        mutationId = mutationId,
        aggregateType = SyncAggregateStateEntity.TYPE_MEASUREMENT,
        aggregateId = date,
        op = SyncMutationEntity.OP_DELETE,
        baseRevision = null,
        payload = null,
        payloadSchemaVersion = PAYLOAD_SCHEMA_VERSION,
        createdAt = createdAt,
        state = state,
        attemptCount = 0,
        lastErrorCode = null,
        lastErrorMessage = null,
    )

    /**
     * The health profile of PRD 13.4 — a **sendable** row since `AGGREGATE_TYPES` grew its
     * branch. The values are the owner's own, which is also what the committed contract fixture
     * carries, so a payload that stops being expressible fails in both places at once.
     */
    fun healthProfileUpsert(
        mutationId: String,
        createdAt: Long = 1_770_000_000_000L,
        heightCm: Int? = 171,
        birthDate: String? = "1998-11-18",
        baseRevision: Long? = null,
    ): SyncMutationEntity = SyncMutationEntity(
        mutationId = mutationId,
        aggregateType = SyncAggregateStateEntity.TYPE_HEALTH_PROFILE,
        aggregateId = "me",
        op = SyncMutationEntity.OP_UPSERT,
        baseRevision = baseRevision,
        payload = """{"heightCm":${heightCm ?: "null"},"birthDate":${
            birthDate?.let { "\"" + it + "\"" } ?: "null"
        }}""",
        payloadSchemaVersion = PAYLOAD_SCHEMA_VERSION,
        createdAt = createdAt,
        state = SyncMutationEntity.STATE_PENDING,
        attemptCount = 0,
        lastErrorCode = null,
        lastErrorMessage = null,
    )

    /**
     * An aggregate this build really does journal and really cannot send: PRD 10.1 synchronises
     * the food journal, `SyncOutbox.foodLogUpsert` writes a row at every meal, and
     * `AGGREGATE_TYPES` has no branch for it.
     *
     * It replaced the health profile in every "deferred" test here, which is the point of
     * naming it: those tests are about the *queue*, not about which aggregate happens to be
     * stuck in it, and an aggregate that graduated should move the tests along rather than
     * delete them.
     */
    fun deferredUpsert(
        mutationId: String,
        createdAt: Long = 1_770_000_000_000L,
    ): SyncMutationEntity = SyncMutationEntity(
        mutationId = mutationId,
        aggregateType = FoodAggregates.TYPE_FOOD_LOG_ENTRY,
        aggregateId = "b7c1e2f0-0000-7000-8000-000000000001",
        op = SyncMutationEntity.OP_UPSERT,
        baseRevision = null,
        payload = """{"consumedOn":"2026-08-25","grams":120}""",
        payloadSchemaVersion = PAYLOAD_SCHEMA_VERSION,
        createdAt = createdAt,
        state = SyncMutationEntity.STATE_PENDING,
        attemptCount = 0,
        lastErrorCode = null,
        lastErrorMessage = null,
    )

    fun meta(
        id: String,
        revision: String = "4",
        deletedAt: String? = null,
        lastMutationId: String = "0198f0a1-2b3c-7d4e-8f90-a1b2c3d4e5f6",
    ): AggregateMetaDto = AggregateMetaDto(
        id = id,
        revision = revision,
        createdAt = "2026-08-25T06:12:04.500Z",
        updatedAt = "2026-08-25T06:12:04.500Z",
        deletedAt = deletedAt,
        originType = OriginDto.TYPE_AGENT,
        originId = "agent-claude",
        lastMutationId = lastMutationId,
    )

    fun upsertChange(
        sequence: String,
        date: String = "2026-08-25",
        weightCg: Int = 7_845,
        revision: String = "4",
        payloadSchemaVersion: Int = 1,
        aggregateType: String = WIRE_AGGREGATE_MEASUREMENT,
    ): SyncChangeDto = MeasurementUpsertChangeDto(
        sequence = sequence,
        aggregateType = aggregateType,
        aggregateId = date,
        payloadSchemaVersion = payloadSchemaVersion,
        payload = MeasurementPayloadV1Dto(date = date, weightCg = weightCg),
        meta = meta(date, revision),
    )

    /** The profile as the server hands it back — merged, so it may not echo what was sent. */
    fun healthProfileChange(
        sequence: String,
        heightCm: Int? = 171,
        birthDate: String? = "1998-11-18",
        revision: String = "1",
    ): SyncChangeDto = HealthProfileUpsertChangeDto(
        sequence = sequence,
        payloadSchemaVersion = 1,
        payload = HealthProfilePayloadV1Dto(heightCm = heightCm, birthDate = birthDate),
        meta = meta("me", revision),
    )

    fun deleteChange(
        sequence: String,
        date: String = "2026-08-24",
        revision: String = "10",
    ): SyncChangeDto = DeleteChangeDto(
        sequence = sequence,
        aggregateType = WIRE_AGGREGATE_MEASUREMENT,
        aggregateId = date,
        payloadSchemaVersion = 1,
        meta = meta(date, revision, deletedAt = "2026-08-25T06:12:05.310Z"),
    )

    fun page(
        changes: List<SyncChangeDto>,
        nextCursor: String,
        hasMore: Boolean = false,
    ): PullResponseDto = PullPageDto(
        changes = changes,
        nextCursor = nextCursor,
        hasMore = hasMore,
        serverTime = SERVER_TIME,
        lastAndroidSyncAt = SERVER_TIME,
    )

    fun upgradeRequired(
        message: String = "The server holds measurement payloads at schema version 2.",
    ): PullResponseDto = PullUpgradeRequiredDto(
        error = MueErrorDto(
            code = SyncErrorCodes.SYNC_UPGRADE_REQUIRED,
            message = message,
            retryable = false,
            aggregateType = WIRE_AGGREGATE_MEASUREMENT,
        ),
        serverTime = SERVER_TIME,
        lastAndroidSyncAt = null,
    )

    fun applied(mutationId: String, revision: String = "4", sequence: String = "41"):
        MutationResultDto = MutationAppliedDto(mutationId, revision, sequence)

    fun duplicate(mutationId: String, revision: String = "4", sequence: String = "41"):
        MutationResultDto = MutationDuplicateDto(mutationId, revision, sequence)

    fun rejected(
        mutationId: String,
        code: String = SyncErrorCodes.SYNC_REVISION_CONFLICT,
        message: String = "The measurement has moved on since baseRevision 3.",
    ): MutationResultDto = MutationRejectedDto(
        mutationId,
        MueErrorDto(code = code, message = message, retryable = false, currentRevision = "7"),
    )

    fun pushResponse(vararg results: MutationResultDto): PushResponseDto =
        PushResponseDto(results.toList(), SERVER_TIME)
}

/**
 * A [SyncApi] that answers from a script and records what it was asked.
 *
 * The recording is the point for two of the guarantees: FR-SYNC-006 is "the same mutation id on
 * every attempt", which is a property of the *requests*, and the opaque-cursor rule is "exactly
 * the bytes the server sent came back", which is a property of the second request.
 */
class ScriptedSyncApi(
    private val pushes: MutableList<Any> = mutableListOf(),
    private val pulls: MutableList<Any> = mutableListOf(),
) : SyncApi {

    val pushRequests = mutableListOf<PushRequestDto>()
    val pullRequests = mutableListOf<PullRequestDto>()

    fun onPush(response: PushResponseDto) = apply { pushes += response }

    fun onPushFail(failure: SyncTransportException) = apply { pushes += failure }

    fun onPull(response: PullResponseDto) = apply { pulls += response }

    fun onPullFail(failure: SyncTransportException) = apply { pulls += failure }

    override suspend fun push(request: PushRequestDto): PushResponseDto {
        pushRequests += request
        val next = pushes.takeIf { it.isNotEmpty() }?.removeAt(0)
            ?: throw IllegalStateException("the script has no answer for push #${pushRequests.size}")
        return when (next) {
            is PushResponseDto -> next
            is SyncTransportException -> throw next
            else -> throw IllegalStateException("push #${pushRequests.size} was scripted with $next")
        }
    }

    override suspend fun pull(request: PullRequestDto): PullResponseDto {
        pullRequests += request
        val next = pulls.takeIf { it.isNotEmpty() }?.removeAt(0)
            ?: throw IllegalStateException("the script has no answer for pull #${pullRequests.size}")
        return when (next) {
            is PullResponseDto -> next
            is SyncTransportException -> throw next
            else -> throw IllegalStateException("pull #${pullRequests.size} was scripted with $next")
        }
    }
}
