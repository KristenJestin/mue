package fr.kristenjestin.mue.data.remote.sync

/**
 * The live half of PRD 9.4: one long-lived connection that says *pull* and nothing else.
 *
 * PRD 9.4 lists the moments the phone attempts a synchronisation and ends the list with
 * "périodiquement par le mécanisme Android approprié, sans promesse d'heure exacte". Between two
 * of them the phone is stale and the owner has no way to know it, which is what makes him press
 * `Sync now` to answer a question the application should answer for him. This is the channel that
 * answers it, and it exists only while somebody is looking (see `LiveSyncChannel`).
 *
 * ## What a hint is, and what it is not
 *
 * A hint is one word: *pull*. It carries no sequence, no cursor, no aggregate and no payload.
 *
 * PRD 12.3 makes the sequence the server's to assign and the client cursor opaque, and
 * [SyncEngine][fr.kristenjestin.mue.data.sync.SyncEngine] already refuses to read one. The wire
 * keeps that honest: `data:` is always `{}`, this parser discards it without looking, and the
 * only thing a hint can ever cause is `POST /api/v1/sync/pull` with the cursor already in
 * `sync_state`. A channel that carried a position would be a second, weaker source of truth about
 * how far behind the phone is, and the first bug in it would be a silently skipped change.
 *
 * ## Opening is itself a hint
 *
 * [connect] reports a hint as soon as the server greets it. A phone whose connection dropped
 * cannot know whether the journal moved while it was away, and must not be told — that would be a
 * position again. So it pulls once per successful connection, and its own cursor decides whether
 * there was anything to fetch. The cost of being wrong is one empty page.
 *
 * ## Failure is not an event
 *
 * Every failure is a [SyncTransportException] thrown out of [connect], for the caller to swallow.
 * Nothing here records a failure in `sync_state` and nothing here notifies: FR-SYNC-008 and PRD
 * 9.1 make an unreachable server the normal state away from home, and a channel that tried to
 * hold a connection open all day would otherwise turn every walk to the shops into a red screen.
 */
interface SyncEventStream {

    /**
     * Opens one connection and calls [onHint] once per hint, until the server closes the stream
     * or the caller is cancelled.
     *
     * Returns normally when the server ended the stream — which it does on purpose, so a
     * credential goes back through the guard from time to time. Reopening is the caller's
     * decision, under the caller's backoff.
     *
     * @throws SyncTransportException when the connection could not be opened, was refused, or was
     * cut. Never anything else, and never for a reason the user is shown.
     */
    suspend fun connect(onHint: suspend () -> Unit)
}
