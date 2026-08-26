import type { AggregateType, MueError, MueErrorCode } from "@mue/contracts";

/**
 * Every business error the sync services raise is built here, so the codes of
 * `MUE_ERROR_CODES` have exactly one construction site and a client never sees
 * two different messages for the same situation.
 */

export interface ErrorContext {
  readonly aggregateType?: AggregateType | undefined;
  readonly aggregateId?: string | undefined;
  /** Dotted path of the offending field, for `sync.missing_required_field`. */
  readonly field?: string | undefined;
  /** The revision the server actually holds, so the author can rebase. */
  readonly currentRevision?: string | undefined;
}

export function mueError(
  code: MueErrorCode,
  message: string,
  retryable: boolean,
  context: ErrorContext = {},
): MueError {
  // Built key by key rather than spread: `exactOptionalPropertyTypes` makes
  // `{ field: undefined }` a different value from an absent `field`, and the
  // absent one is what the wire schema describes.
  const error: MueError = { code, message, retryable };
  return {
    ...error,
    ...(context.aggregateType === undefined ? {} : { aggregateType: context.aggregateType }),
    ...(context.aggregateId === undefined ? {} : { aggregateId: context.aggregateId }),
    ...(context.field === undefined ? {} : { field: context.field }),
    ...(context.currentRevision === undefined ? {} : { currentRevision: context.currentRevision }),
  };
}

/**
 * A failure of the request itself rather than of one mutation: an unreadable
 * body, an unreadable cursor, a missing session. It carries the HTTP status the
 * route answers with, because PRD section 20.4 fixes those in `openapi.json`
 * and the transport must not invent a different one.
 *
 * A *rejected mutation* is deliberately not one of these. It is a business
 * result carried inside a 200 (FR-SYNC-007): a non-2xx makes a default Ktor
 * client throw before its actionable `MueError` is ever parsed.
 */
export class SyncRequestError extends Error {
  readonly mueError: MueError;
  readonly status: 400 | 401 | 500;

  constructor(mueError: MueError, status: 400 | 401 | 500) {
    super(mueError.message);
    this.name = "SyncRequestError";
    this.mueError = mueError;
    this.status = status;
  }
}

export function invalidRequest(message: string, context: ErrorContext = {}): SyncRequestError {
  return new SyncRequestError(mueError("sync.invalid_payload", message, false, context), 400);
}

export function invalidCursor(message: string): SyncRequestError {
  return new SyncRequestError(mueError("sync.invalid_cursor", message, false), 400);
}

export function unauthenticated(message: string): SyncRequestError {
  return new SyncRequestError(mueError("auth.unauthenticated", message, false), 401);
}
