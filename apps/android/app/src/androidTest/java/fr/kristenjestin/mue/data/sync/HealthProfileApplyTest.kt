package fr.kristenjestin.mue.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.kristenjestin.mue.data.local.database.HealthProfileEntity
import fr.kristenjestin.mue.data.local.database.MueDatabase
import fr.kristenjestin.mue.data.local.database.SyncAggregateStateEntity
import fr.kristenjestin.mue.data.local.database.SyncDao
import fr.kristenjestin.mue.data.remote.sync.AggregateMetaDto
import fr.kristenjestin.mue.data.remote.sync.HealthProfilePayloadV1Dto
import fr.kristenjestin.mue.data.remote.sync.HealthProfileUpsertChangeDto
import fr.kristenjestin.mue.data.remote.sync.OriginDto
import fr.kristenjestin.mue.data.remote.sync.SyncChangeDto
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Applying a received health profile, against real SQLite.
 *
 * Two claims can only be made here, because they are claims about the *table*:
 *
 * 1. **A second device updates the row and never inserts a rival one.** PRD 13.4 makes the
 *    profile "un agrégat unique", and on this side that is a primary key of one constant value
 *    rather than a rule the apply path remembers. A test against an in-memory fake holding a
 *    single nullable field could not tell the difference.
 * 2. **§13.4's merge converges here.** The server merges field by field and journals the
 *    merged result, so what arrives can differ from what this device pushed. Applying it is
 *    what makes the two devices agree; the assertion below is that the row ends up holding the
 *    merge and not the submission.
 */
@RunWith(AndroidJUnit4::class)
class HealthProfileApplyTest {

    private lateinit var database: MueDatabase
    private lateinit var syncDao: SyncDao
    private lateinit var store: RoomSyncStore

    private val at = 1_770_000_100_000L

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MueDatabase::class.java,
        ).build()
        syncDao = database.syncDao()
        store = RoomSyncStore(database)
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    /** The owner's own values, arriving from the server and landing in `health_profile`. */
    @Test
    fun aReceivedProfileIsWrittenToTheOneRowWithItsMetadata() = runTest {
        store.applyPage(listOf(profileChange("1", 171, "1998-11-18", revision = "1")), CURSOR, at)

        val stored = database.healthProfileDao().get()
        assertEquals(HealthProfileEntity.ROW_ID, stored?.id)
        assertEquals(171, stored?.heightCm)
        assertEquals("1998-11-18", stored?.birthDate)
        assertEquals(CURSOR, syncDao.syncState()?.cursor)

        val state = syncDao.aggregateState(SyncAggregateStateEntity.TYPE_HEALTH_PROFILE, "me")
        assertEquals(1L, state?.revision)
        assertNull("a profile is never a tombstone (PRD 13.4)", state?.deletedAt)
    }

    /**
     * A second device's change updates the row. `health_profile` holds one row afterwards, and
     * it is the one keyed `me` — there is no second profile to choose between.
     */
    @Test
    fun aSecondDeviceUpdatesTheRowRatherThanOpeningARivalToIt() = runTest {
        database.healthProfileDao().upsert(HealthProfileEntity(heightCm = 180, birthDate = null))

        store.applyPage(listOf(profileChange("2", 171, "1998-11-18", revision = "2")), CURSOR, at)

        assertEquals(1, countProfiles())
        val stored = database.healthProfileDao().get()
        assertEquals(HealthProfileEntity.ROW_ID, stored?.id)
        assertEquals(171, stored?.heightCm)
        assertEquals("1998-11-18", stored?.birthDate)
    }

    /**
     * PRD 13.4, from this side.
     *
     * This device changed the height and knew nothing of a birth date; another origin had set
     * one. The server merged the two — it keeps a field this author did not touch — and
     * journalled the merged aggregate. Applying that page is what makes this device converge on
     * a profile it never submitted, which is the whole point of the rule.
     */
    @Test
    fun theMergedProfileTheServerReturnsIsWhatTheDeviceConvergesOn() = runTest {
        database.healthProfileDao().upsert(
            HealthProfileEntity(heightCm = 172, birthDate = null),
        )

        store.applyPage(listOf(profileChange("3", 172, "1998-11-18", revision = "3")), CURSOR, at)

        val stored = database.healthProfileDao().get()
        assertEquals(172, stored?.heightCm)
        assertEquals(
            "the field this device never touched arrives with the one it changed",
            "1998-11-18",
            stored?.birthDate,
        )
        assertEquals(1, countProfiles())
    }

    /** Two pages in a row: the second replaces the first, still in one row. */
    @Test
    fun asuccessionOfProfilesLeavesOneRowHoldingTheLatest() = runTest {
        store.applyPage(listOf(profileChange("1", 171, "1998-11-18", revision = "1")), CURSOR, at)
        store.applyPage(listOf(profileChange("2", null, "1998-11-18", revision = "2")), CURSOR_B, at)

        assertEquals(1, countProfiles())
        assertEquals(null, database.healthProfileDao().get()?.heightCm)
        assertEquals("1998-11-18", database.healthProfileDao().get()?.birthDate)
        assertEquals(
            2L,
            syncDao.aggregateState(SyncAggregateStateEntity.TYPE_HEALTH_PROFILE, "me")?.revision,
        )
    }

    private suspend fun countProfiles(): Int {
        val query = androidx.sqlite.db.SimpleSQLiteQuery("SELECT COUNT(*) FROM health_profile")
        database.openHelper.readableDatabase.query(query).use { cursor ->
            cursor.moveToFirst()
            return cursor.getInt(0)
        }
    }

    private fun profileChange(
        sequence: String,
        heightCm: Int?,
        birthDate: String?,
        revision: String,
    ): SyncChangeDto = HealthProfileUpsertChangeDto(
        sequence = sequence,
        payloadSchemaVersion = 1,
        payload = HealthProfilePayloadV1Dto(heightCm = heightCm, birthDate = birthDate),
        meta = AggregateMetaDto(
            id = HealthProfileEntity.ROW_ID,
            revision = revision,
            createdAt = "2026-08-25T06:12:04.500Z",
            updatedAt = "2026-08-25T06:12:04.900Z",
            deletedAt = null,
            originType = OriginDto.TYPE_ANDROID,
            originId = "device-laptop",
            lastMutationId = "0198f0a2-4d5e-7f60-9a1b-2c3d4e5f6071",
        ),
    )

    private companion object {
        const val CURSOR = "eyJ2IjoxLCJzZXEiOiI0MSJ9"
        const val CURSOR_B = "eyJ2IjoxLCJzZXEiOiI0MiJ9"
    }
}
