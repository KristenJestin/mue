package fr.kristenjestin.mue.data.remote.sync

import kotlinx.serialization.json.Json

/**
 * The one [Json] every sync body is read and written with — the client, the contract test and
 * nothing else.
 *
 * There is a single instance on purpose. A test that parsed fixtures with a laxer configuration
 * than the client uses would be green about a client that is broken, which is the one failure a
 * contract test may not have.
 *
 * ## `ignoreUnknownKeys = true`
 *
 * A private self-hosted deployment has no ordering guarantee between a phone update and a server
 * update, so a phone will meet a server that answers with fields it has never heard of. Refusing
 * them would turn every additive server change into a client that cannot synchronise at all —
 * the opposite of PRD 12.4's "les migrations restent additives autant que possible".
 *
 * This does not weaken drift detection, it *is* how drift detection works: the contract test
 * re-serialises what it parsed and compares JSON trees, so a field the server added and these
 * DTOs dropped comes back as a visible diff rather than as an exception nobody can read.
 *
 * ## `encodeDefaults = false`
 *
 * `PullRequest.limit` is `.optional()` in Zod and not `.nullable()`: it must be *omitted* when
 * unset, and a `"limit": null` is rejected. The two places where a default must nevertheless be
 * written — the `"payload": null` of a delete — say so with `@EncodeDefault(ALWAYS)`, which is
 * why the exception is annotated at the property and not loosened here for everything.
 *
 * ## `explicitNulls = true` (the default, restated because it matters)
 *
 * `deletedAt`, `cursor` and `lastAndroidSyncAt` are required-and-nullable. Dropping their keys
 * when null would invent a third state — absent — that the protocol does not have.
 *
 * ## `isLenient = false` (the default, restated for the same reason)
 *
 * A counter arrives as a canonical decimal string. Lenient parsing would happily read an
 * unquoted `9007199254740993` into a `String` field and hide precisely the drift this file
 * exists to catch.
 */
object SyncJson {

    val instance: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = true
        isLenient = false
        prettyPrint = false
    }
}
