package fr.kristenjestin.mue.data.sync

import fr.kristenjestin.mue.data.local.database.HealthProfileEntity
import fr.kristenjestin.mue.data.local.database.InMemoryHealthProfileDao
import fr.kristenjestin.mue.data.local.database.InMemoryJournal
import fr.kristenjestin.mue.data.local.database.SyncAggregateStateEntity
import fr.kristenjestin.mue.data.local.datastore.FakePreferencesDataStore
import fr.kristenjestin.mue.data.remote.sync.HealthProfileUpsertMutationDto
import fr.kristenjestin.mue.data.remote.sync.SyncJson
import fr.kristenjestin.mue.data.remote.sync.SyncWire
import fr.kristenjestin.mue.data.repository.DataStoreUserProfileRepository
import fr.kristenjestin.mue.domain.model.UserProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * What a phone actually puts on the wire when it saves a profile, and the one field that says
 * whether the payload is a person's statement or a fresh install's silence.
 *
 * ## The defect this file is the client half of
 *
 * `mue_app.sync_journal` for the owner's account holds three entries of
 * `{"heightCm":null,"birthDate":null}` from three different `origin.id`s, each written a few
 * minutes after a *clear app data → pair → open Profile → Save*. Each replaced a row that held
 * `171 / 1998-11-18`.
 *
 * PRD 12.2 makes an upsert state the **complete** aggregate — that is what lets the server merge
 * field by field under 13.4 — so a phone with an empty local profile has no choice but to send
 * `{"heightCm":null,"birthDate":null}`. A person who deletes both fields sends the identical
 * payload. The two cannot be told apart by looking at the payload, and no amount of care in
 * `SyncOutbox` changes that: **the difference is not in what is stated, it is in what the
 * statement is about.**
 *
 * ## The field that carries the difference, and where it comes from
 *
 * `baseRevision`. `HealthProfileDao.upsertWithMutation` fills it inside the writing transaction
 * from `sync_aggregate_state.revision` — a column that is written in exactly two places, both of
 * which mean *this phone has the server's aggregate*: `RoomSyncStore.applyChange` after a pull,
 * and `RoomSyncStore.acknowledge` after the server accepted a push. A phone that has neither
 * pulled nor pushed the profile has no row, `revisionOf` answers null, and the mutation quotes
 * nothing.
 *
 * So it is not a heuristic and not a new field: it is already on the wire, PRD 12.2 already
 * requires it ("si elle existe"), and it is *evidence* rather than a claim, because the client
 * cannot forge it — nothing writes that column but a completed exchange with the server.
 *
 * ## Why this is a test and not a guard
 *
 * The phone is not where the decision can be made. Deciding needs the base, the stored aggregate
 * and the payload at once, and the phone has only the last. What the phone owes is that its
 * `baseRevision` be *truthful*, which is what the two tests below pin: the two envelopes differ
 * in that field and in nothing else, so the server's rule in
 * `packages/domain/src/sync/health-profile.ts` has something real to decide on.
 *
 * ## The two files, and why they are files
 *
 * The two JSON files under `src/test/resources/first-push` hold the exact bytes each save
 * produces. This suite asserts the phone emits them; `health-profile.test.ts` in
 * `packages/domain` reads *the same two files* and submits them to PostgreSQL. Neither side can
 * be edited into agreement with itself: a change to what Room writes fails here, and a change to
 * what the server does with it fails there, on the same bytes.
 *
 * That join is the point. Four defects this week were the right shape carrying the wrong value —
 * a UUIDv4 where v7 was required, `origin.type: "web"`, a weight off the 5-centigram step, a
 * profile of nulls — and a comparison of *shapes* at the `SyncWire` seam saw none of them,
 * because every one of them was well-formed. A real value carried from the repository call to the
 * `health_profile` row is the only check that would have.
 */
class FirstPushBaseRevisionTest {

    private val deviceId = "device-7f3c1a04"
    private val emptySave = SyncFixtures.profileMutationId(0x100)
    private val clearingSave = SyncFixtures.profileMutationId(0x101)

    /** The owner's own values, as the server holds them at the moment each save is made. */
    private val height = 171
    private val birthDate = LocalDate.of(1998, 11, 18)

    /**
     * A phone that has just been paired, has not received the profile yet, and whose owner opens
     * `Profile` and saves.
     *
     * The state is the one *after* `clear app data`: `health_profile` is empty and
     * `sync_aggregate_state` has no `healthProfile` row at all. Nothing is stubbed — the save
     * goes through the shipped repository, the shipped `@Transaction` default method and the
     * shipped wire mapper.
     */
    @Test
    fun aPhoneThatHasNeverReceivedTheProfileQuotesNoBaseAndStatesTwoNulls() = runTest {
        val journal = InMemoryJournal()
        val repository = repository(journal, mutationId = emptySave)

        repository.save(UserProfile(displayName = "Kris", heightCm = null, birthDate = null))

        val row = checkNotNull(journal.mutation(emptySave))
        assertNull(
            row.baseRevision,
            "a phone with no sync_aggregate_state row has no revision to quote",
        )

        assertEquals(
            sharedBody("freshly-paired-empty-save.json"),
            wireBody(journal, emptySave),
            "this is the body that emptied the owner's profile three times, and the one " +
                "health-profile.test.ts submits to PostgreSQL",
        )
    }

    /**
     * The same phone, one pull later, whose owner really does delete his height.
     *
     * The pull is applied exactly as `RoomSyncStore.applyChange` applies one: the profile row is
     * replaced with the server's, and `sync_aggregate_state` is written with the revision the
     * change carried. Then the height is cleared and the birth date left alone — the ordinary
     * edit that must keep working, and the one a blunt "refuse empty profiles" rule would break.
     */
    @Test
    fun aPhoneThatPulledTheProfileQuotesTheRevisionItIsClearing() = runTest {
        val journal = InMemoryJournal()
        val profiles = InMemoryHealthProfileDao(journal)
        applyPulledProfile(journal, profiles, revision = PULLED_REVISION)

        val repository = repository(journal, profiles, mutationId = clearingSave)
        repository.save(UserProfile(displayName = "Kris", heightCm = null, birthDate = birthDate))

        val row = checkNotNull(journal.mutation(clearingSave))
        assertEquals(
            PULLED_REVISION,
            row.baseRevision,
            "the revision the pull brought is the base of the edit",
        )

        assertEquals(
            sharedBody("cleared-height-save.json"),
            wireBody(journal, clearingSave),
            "clearing a height must stay expressible, and it is an edit of a known version",
        )
    }

    /**
     * The thesis, stated mechanically.
     *
     * A person clearing *both* fields and a fresh install that never had either send payloads
     * that are equal key for key. Every other field of the envelope is equal too, once the two
     * values that are unique per row are set aside. The whole distinction rests on
     * `baseRevision`, so this asserts that it is the *only* difference — if a future change made
     * some other field carry the difference as well, the server's rule would silently be
     * deciding on something else.
     */
    @Test
    fun theTwoEnvelopesDifferInBaseRevisionAndInNothingElse() = runTest {
        val fresh = InMemoryJournal()
        repository(fresh, mutationId = emptySave)
            .save(UserProfile(displayName = "Kris", heightCm = null, birthDate = null))

        val cleared = InMemoryJournal()
        val profiles = InMemoryHealthProfileDao(cleared)
        applyPulledProfile(cleared, profiles, revision = PULLED_REVISION)
        repository(cleared, profiles, mutationId = clearingSave)
            .save(UserProfile(displayName = "Kris", heightCm = null, birthDate = null))

        val freshFields = fields(wireBody(fresh, emptySave)) - "mutationId"
        val clearedFields = fields(wireBody(cleared, clearingSave)) - "mutationId"

        assertEquals(
            listOf("baseRevision"),
            freshFields.keys.filter { freshFields[it] != clearedFields[it] },
            "the payloads are identical; only the base the author quoted is not",
        )
        assertEquals(JsonNull, freshFields["baseRevision"])
        assertEquals(JsonPrimitive(PULLED_REVISION.toString()), clearedFields["baseRevision"])
        assertEquals(
            """{"heightCm":null,"birthDate":null}""",
            clearedFields["payload"].toString(),
            "a person really did clear both fields, and the bytes say nothing more than that",
        )
    }

    // --- the path, assembled exactly as the application assembles it -----------------------

    private fun repository(
        journal: InMemoryJournal,
        profiles: InMemoryHealthProfileDao = InMemoryHealthProfileDao(journal),
        mutationId: String,
    ) = DataStoreUserProfileRepository(
        FakePreferencesDataStore(),
        profiles,
        SyncOutbox(newMutationId = { mutationId }, now = { CLIENT_OCCURRED_AT }),
        Dispatchers.Unconfined,
    )

    /**
     * What `RoomSyncStore.applyChange` does for a `healthProfile` change, on the two stores that
     * exist here: the one local row becomes the server's, and the aggregate's revision is
     * recorded so the next local edit can quote it.
     */
    private suspend fun applyPulledProfile(
        journal: InMemoryJournal,
        profiles: InMemoryHealthProfileDao,
        revision: Long,
    ) {
        profiles.upsert(
            HealthProfileEntity(heightCm = height, birthDate = birthDate.toString()),
        )
        journal.insertStateIfAbsent(
            SyncAggregateStateEntity(
                aggregateType = SyncAggregateStateEntity.TYPE_HEALTH_PROFILE,
                aggregateId = HealthProfileEntity.ROW_ID,
                revision = revision,
                originType = SyncAggregateStateEntity.ORIGIN_AGENT,
                originId = "instrumented-second-client",
            ),
        )
    }

    /** The outbox row as `SyncEngine.push` would send it: the shipped mapper, the shipped `Json`. */
    private fun wireBody(journal: InMemoryJournal, mutationId: String): String {
        val row = checkNotNull(journal.mutation(mutationId))
        val envelope = assertIs<HealthProfileUpsertMutationDto>(
            SyncWire.toEnvelope(row, SyncWire.androidOrigin(deviceId)),
        )
        return SyncJson.instance.encodeToString(
            serializer<HealthProfileUpsertMutationDto>(),
            envelope,
        )
    }

    /** The body as a flat map, so a difference can be named rather than eyeballed in a diff. */
    private fun fields(body: String) =
        Json.parseToJsonElement(body).let { it as JsonObject }.toMap() - "clientOccurredAt"

    /**
     * One of the two bodies `packages/domain` submits, read from the file both suites share.
     *
     * Not a constant in this file. A constant would let somebody make this test green by
     * editing the expectation, and the server-side test would go on submitting the old bytes.
     */
    private fun sharedBody(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("first-push/$name")) {
            "src/test/resources/first-push/$name is missing; packages/domain reads it too"
        }.use { it.readBytes().decodeToString().trim() }

    private companion object {
        /**
         * A fixed clock, so the body is a constant. `SyncOutbox` proposes it and
         * `SyncJournalDao.sequenced` floors it at one past the highest stamp in the outbox —
         * which is empty here, so the proposal stands and the instant is exact.
         */
        const val CLIENT_OCCURRED_AT = 1_770_000_100_000L

        /**
         * The revision the pull brought. Three, not one: the server's `health-profile.test.ts`
         * builds this base by applying three real mutations, so the number is one a journal
         * actually holds and not a value that could be a default in disguise.
         */
        const val PULLED_REVISION = 3L
    }
}
