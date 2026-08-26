package fr.kristenjestin.mue.data.remote.sync

/**
 * The two calls of PRD 20.4, and everything the engine is allowed to know about the network.
 *
 * It is an interface so the engine's tests are JVM tests: FR-SYNC-002's ordering, FR-SYNC-006's
 * idempotence and FR-SYNC-007's partial failure are all decided in the engine, and none of them
 * should need a socket, an emulator or a running Postgres to be proved. [KtorSyncApi] is the one
 * implementation that speaks HTTP, and it is tested for what it alone decides: the path, the
 * bearer, the body and how a non-2xx becomes a [MueErrorDto].
 */
interface SyncApi {

    /**
     * Sends a batch, in outbox order, and gets exactly one result per mutation back.
     *
     * @throws SyncTransportException when the server could not be reached or answered something
     * that is not a [PushResponseDto]. Every other outcome — including a mutation the server
     * refused — is a value in the response, because FR-SYNC-007 makes a rejection a business
     * result rather than a transport failure.
     */
    suspend fun push(request: PushRequestDto): PushResponseDto

    /**
     * Reads the journal after the request's cursor.
     *
     * @throws SyncTransportException on the same terms as [push]. `upgrade_required` is *not*
     * an exception: it is the second branch of [PullResponseDto], and it carries the instant and
     * the error the caller has to record.
     */
    suspend fun pull(request: PullRequestDto): PullResponseDto
}

/**
 * The server was not reached, or did not answer the contract.
 *
 * [code] is a [SyncErrorCodes] value so `Data & sync` renders one vocabulary whether the failure
 * came from the server's own [MueErrorDto] or from the client's transport. [retryable] carries
 * the server's own judgement when there is one, and the client's otherwise — a socket that
 * timed out is worth another attempt, a body that does not parse is not, and retrying the
 * unretryable is how a phone flattens its own battery against a broken server.
 */
class SyncTransportException(
    val code: String,
    override val message: String,
    val retryable: Boolean,
    cause: Throwable? = null,
) : Exception(message, cause)
