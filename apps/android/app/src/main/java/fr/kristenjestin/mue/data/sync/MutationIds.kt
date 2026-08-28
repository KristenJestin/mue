package fr.kristenjestin.mue.data.sync

import java.security.SecureRandom
import java.util.Random
import java.util.UUID

/**
 * The identifier of an outbox row, as a UUIDv7.
 *
 * `packages/contracts/src/primitives.ts` declares `mutationIdSchema = z.uuidv7()`, and
 * `packages/domain/src/sync/push.ts` refuses anything else before it looks at the payload:
 *
 * > Every mutation needs a readable UUIDv7 `mutationId`.
 *
 * This existed as `UUID.randomUUID()` — a v4 — which meant **every push a phone has ever made
 * was rejected with `sync.invalid_payload`**. Nothing about it was visible: pairing reported
 * success, the outbox filled, `Data & sync` said `Sync issue`, and not one weight ever reached
 * the server. It was found by pairing an emulator with a real Mue Platform and reading
 * `sync_state.last_error_message` off the phone, which is the only place the sentence appeared.
 *
 * The drift detector in `ContractDrift` could not have caught it: it compares the *shape* of a
 * fixture against the DTOs, and a v4 and a v7 are the same shape. This is a value format, and
 * the only thing that tests it is a server.
 *
 * ## The layout, from RFC 9562 section 5.7
 *
 * ```
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                           unix_ts_ms                          |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |          unix_ts_ms           |  ver  |       rand_a          |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |var|                        rand_b                             |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                            rand_b                             |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * ```
 *
 * 48 bits of Unix milliseconds, big-endian, then the version nibble `7`, 12 random bits, the
 * two-bit variant `10`, and 62 more random bits. Java 17 has no `UUID.randomUUIDv7()` — the
 * factory arrived in a later release — so it is assembled here from the two longs
 * `java.util.UUID` is made of, which is exact and needs no byte array.
 *
 * Written by hand rather than pulled from a library on purpose: it is nine lines, a dependency
 * added to the app is a dependency added to the APK, and the version and variant bits are
 * asserted by `MutationIdsTest` against the same regex Zod applies.
 */
object MutationIds {

    /**
     * Cryptographically strong, and it matters. A mutation identifier is the idempotency key of
     * PRD 12.1: two phones that guessed the same one would have the second write silently
     * treated as a duplicate of the first. `SecureRandom` is seeded by the platform and is not
     * the reproducible generator `java.util.Random(seed)` is.
     */
    private val secureRandom = SecureRandom()

    /** A fresh identifier for a row being minted now. */
    fun random(): String = at(System.currentTimeMillis(), secureRandom)

    /**
     * Whether the server's front door would read [candidate] as a `mutationId` at all.
     *
     * This is the *acceptance* test, not the minting rule, and the difference is deliberate.
     * [random] emits lower case because `java.util.UUID.toString` does; `mutationIdSchema` is
     * `z.uuidv7()`, whose regex is
     *
     * ```
     * ^([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-7[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12})$
     * ```
     *
     * — hex in either case, and the variant nibble in either case too. So this accepts strictly
     * more than [random] produces, and that is the safe direction: `OutboxRepair` re-mints the
     * id of a row **because** returning false here proves the server never accepted it, so a
     * predicate stricter than the server's would re-mint a row the server may have taken, and
     * that is the FR-SYNC-006 replay guarantee broken. Erring the other way costs nothing: an
     * id this accepts and the server refuses is simply refused again, per mutation, as before.
     *
     * Not `UUID.fromString(...).version() == 7`: `java.util.UUID` parses `1-2-3-4-5` and pads
     * it, so it would call a string the wire rejects well formed.
     */
    fun isMutationId(candidate: String?): Boolean =
        candidate != null && MUTATION_ID.matches(candidate)

    /**
     * The identifier a row minted at [unixMillis] would carry, with [random] supplying the
     * 74 random bits. Both are parameters so the test can pin an exact string.
     */
    fun at(unixMillis: Long, random: Random): String {
        // 48 bits of timestamp, then the version nibble, then 12 random bits.
        val mostSignificant =
            (unixMillis and TIMESTAMP_MASK shl 16) or
                (VERSION_7 shl 12) or
                (random.nextLong() and RAND_A_MASK)

        // The two variant bits `10`, then 62 random bits.
        val leastSignificant = VARIANT_RFC_9562 or (random.nextLong() and RAND_B_MASK)

        return UUID(mostSignificant, leastSignificant).toString()
    }

    /**
     * `z.uuidv7()`'s own regex, transcribed from `zod/v4/core/regexes.js`. It is written out
     * rather than derived from anything, because the one thing it must never do is drift
     * towards what this file happens to emit — see [isMutationId].
     */
    private val MUTATION_ID = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-7[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$",
    )

    /** The low 48 bits: the milliseconds a v7 carries, which run out in the year 10889. */
    private const val TIMESTAMP_MASK = 0x0000_FFFF_FFFF_FFFFL
    private const val VERSION_7 = 0x7L
    private const val RAND_A_MASK = 0x0FFFL
    /**
     * The variant bits `10` in the top two positions of the low half.
     *
     * Written as `Long.MIN_VALUE` because that *is* `0x8000_0000_0000_0000` — the sign bit set
     * and nothing else — and Kotlin has no literal for it: `-0x8000_0000_0000_0000L` is parsed
     * as the negation of a value one past `Long.MAX_VALUE` and refused as out of range.
     * [RAND_B_MASK] clears both bits before this is OR-ed in, so the second one stays `0`.
     */
    private const val VARIANT_RFC_9562 = Long.MIN_VALUE
    private const val RAND_B_MASK = 0x3FFF_FFFF_FFFF_FFFFL
}
