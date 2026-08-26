package fr.kristenjestin.mue.data.sync

import fr.kristenjestin.mue.domain.model.Measurement
import fr.kristenjestin.mue.domain.model.Weight
import java.time.LocalDate
import java.util.Random
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The regression test for the bug that made every push fail.
 *
 * `packages/contracts/src/primitives.ts` declares `mutationIdSchema = z.uuidv7()`, and
 * `packages/domain/src/sync/push.ts` answers anything else with `sync.invalid_payload` and the
 * sentence "Every mutation needs a readable UUIDv7 `mutationId`." The outbox minted a v4, so no
 * weight this application ever recorded could reach a server — and nothing said so out loud:
 * pairing succeeded, `Data & sync` read `Sync issue`, and the explanation lived only in
 * `sync_state.last_error_message` on the phone.
 *
 * `ContractDrift` cannot catch this class of fault by construction: it compares a fixture's
 * *shape* against the DTOs, and a v4 and a v7 have the same shape. So the format is asserted
 * here, against the same rule Zod applies.
 */
class MutationIdsTest {

    /**
     * `z.uuidv7()`, restated. Eight-four-four-four-twelve lower-case hex, the version nibble
     * pinned to `7` and the variant nibble to one of `8`, `9`, `a`, `b`.
     */
    private val uuidV7 = Regex(
        "^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
    )

    @Test
    fun `mints a UUIDv7`() {
        repeat(200) {
            val id = MutationIds.random()
            assertTrue(uuidV7.matches(id), "not a UUIDv7: $id")
        }
    }

    @Test
    fun `java agrees it is version 7, variant 2`() {
        val parsed = UUID.fromString(MutationIds.random())
        assertEquals(7, parsed.version())
        // RFC 9562's variant `10x`, which `java.util.UUID` reports as 2.
        assertEquals(2, parsed.variant())
    }

    @Test
    fun `carries the minting instant in its leading 48 bits`() {
        // The whole point of a v7 over a v4: the identifier sorts by creation time, which is
        // what `primitives.ts` says it is for -- "so the outbox drains in order".
        val instant = 1_756_240_000_000L
        val id = UUID.fromString(MutationIds.at(instant, Random(1)))
        assertEquals(instant, id.mostSignificantBits ushr 16)
    }

    @Test
    fun `later rows sort after earlier ones as text`() {
        // Sorted as strings, because that is how they are compared once they are JSON.
        val first = MutationIds.at(1_756_240_000_000L, Random(7))
        val second = MutationIds.at(1_756_240_000_001L, Random(7))
        val muchLater = MutationIds.at(1_856_240_000_000L, Random(7))
        assertTrue(first < second, "$first should sort before $second")
        assertTrue(second < muchLater, "$second should sort before $muchLater")
    }

    @Test
    fun `two identifiers minted in the same millisecond differ`() {
        // 74 random bits behind one timestamp. A collision would make the second write a
        // duplicate of the first, silently, because the identifier is the idempotency key.
        val ids = (1..500).map { MutationIds.at(1_756_240_000_000L, Random(it.toLong())) }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `an epoch of zero still produces a well-formed identifier`() {
        // A phone whose clock has not been set yet still has to journal its writes.
        val id = MutationIds.at(0L, Random(3))
        assertTrue(uuidV7.matches(id), "not a UUIDv7: $id")
    }

    @Test
    fun `the outbox uses it, which is where the bug actually was`() {
        // Not `MutationIds` in isolation: the default argument of `SyncOutbox` is the thing that
        // shipped a v4, so that default is what this asserts.
        val row = SyncOutbox().measurementUpsert(
            Measurement(LocalDate.of(2019, 3, 14), requireNotNull(Weight.ofHundredthsOrNull(7_345))),
        )
        assertTrue(uuidV7.matches(row.mutationId), "outbox minted a non-v7: ${row.mutationId}")
    }

    @Test
    fun `the outbox still lets a test pin the identifier`() {
        // The injected generator is how every other test in this package asserts an exact row;
        // changing the default must not have taken that away.
        val row = SyncOutbox(newMutationId = { "0198f0a1-9e8d-7c6b-b5a4-938271605f4e" })
            .measurementDelete(LocalDate.of(2019, 3, 14))
        assertEquals("0198f0a1-9e8d-7c6b-b5a4-938271605f4e", row.mutationId)
        assertNotEquals(row.mutationId, SyncOutbox().measurementDelete(LocalDate.EPOCH).mutationId)
    }
}
