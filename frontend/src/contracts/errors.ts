/**
 * One bounded error type for every client-side failure.
 *
 * A UI error carries a stable machine code, a fixed user-safe message and an
 * optional bounded field path. It never carries a stack trace, a raw response
 * body, a proposal value, a member identifier, a recipient target or a secret.
 */

export const UI_ERROR_CODES = {
  /** A response or asset carried a field outside the closed contract. */
  CONTRACT_FIELD_UNKNOWN: 'UI_CONTRACT_FIELD_UNKNOWN',
  /** A value had the wrong JSON shape (not an object, not an array, wrong primitive). */
  CONTRACT_SHAPE_INVALID: 'UI_CONTRACT_SHAPE_INVALID',
  /** A value had the right shape but violated a contract invariant. */
  CONTRACT_VALUE_INVALID: 'UI_CONTRACT_VALUE_INVALID',
  /** A success/error envelope was not decodable at all. */
  CONTRACT_ENVELOPE_INVALID: 'UI_CONTRACT_ENVELOPE_INVALID',
  /** An authoring intent could not be normalised into a Proposal. */
  AUTHORING_NORMALIZATION_INVALID: 'UI_AUTHORING_NORMALIZATION_INVALID',
  /** The gateway call exceeded its bounded timeout. */
  GATEWAY_TIMEOUT: 'UI_GATEWAY_TIMEOUT',
  /** The gateway call was aborted by request identity (a newer request superseded it). */
  GATEWAY_ABORTED: 'UI_GATEWAY_ABORTED',
  /** The transport failed (offline, DNS, reset) without disclosing a raw body. */
  GATEWAY_TRANSPORT_FAILED: 'UI_GATEWAY_TRANSPORT_FAILED',
  /** The server replied with a body that is not a valid envelope. */
  GATEWAY_RESPONSE_INVALID: 'UI_GATEWAY_RESPONSE_INVALID',
  /** The trusted identity lacks the required permission. */
  GATEWAY_PERMISSION_DENIED: 'UI_GATEWAY_PERMISSION_DENIED',
  /** A revision/expected-revision conflict; local edits must be preserved. */
  GATEWAY_STALE_REVISION: 'UI_GATEWAY_STALE_REVISION',
  /** The stored Plan is expired; the user must re-plan. */
  GATEWAY_PLAN_EXPIRED: 'UI_GATEWAY_PLAN_EXPIRED',
  /** The Plan hash no longer matches the stored Plan. */
  GATEWAY_PLAN_MISMATCH: 'UI_GATEWAY_PLAN_MISMATCH',
  /** The PublishPreparation is at or past its expiry. */
  GATEWAY_PREPARATION_EXPIRED: 'UI_GATEWAY_PREPARATION_EXPIRED',
  /** The PublishPreparation was already consumed by one successful publish. */
  GATEWAY_PREPARATION_CONSUMED: 'UI_GATEWAY_PREPARATION_CONSUMED',
  /** The saved Draft or a frozen input drifted after the preparation was made. */
  GATEWAY_PREPARATION_STALE: 'UI_GATEWAY_PREPARATION_STALE',
  /** The preparation identity/hash does not match the stored preparation. */
  GATEWAY_PREPARATION_MISMATCH: 'UI_GATEWAY_PREPARATION_MISMATCH',
  /** The same Idempotency-Key was reused with a different request hash. */
  GATEWAY_IDEMPOTENCY_CONFLICT: 'UI_GATEWAY_IDEMPOTENCY_CONFLICT',
  /** The accepted backend does not expose this route at all. */
  GATEWAY_ROUTE_NOT_EXPOSED: 'UI_GATEWAY_ROUTE_NOT_EXPOSED',
  /** An action operation (reconcile/retry/pause/resume) was refused by eligibility. */
  GATEWAY_OPERATION_REFUSED: 'UI_GATEWAY_OPERATION_REFUSED',
  /** A resource is absent or outside the caller's boundary (non-disclosing). */
  GATEWAY_NOT_FOUND: 'UI_GATEWAY_NOT_FOUND',
  /** A bounded, unexpected failure. */
  GATEWAY_UNEXPECTED: 'UI_GATEWAY_UNEXPECTED',
  /** The campaign configuration is not valid and cannot be compiled. */
  GATEWAY_COMPILER_CONTRACT_INVALID: 'UI_GATEWAY_COMPILER_CONTRACT_INVALID',
} as const

export type UiErrorCode = (typeof UI_ERROR_CODES)[keyof typeof UI_ERROR_CODES]

export interface UiErrorInit {
  readonly code: string
  readonly message: string
  readonly path?: string
  readonly details?: Readonly<Record<string, string>>
  readonly retryable?: boolean
}

/**
 * The single bounded failure type. `message` is always fixed prose chosen by
 * this module, never server text and never a payload echo.
 */
export class UiError extends Error {
  readonly code: string
  readonly path: string | null
  readonly details: Readonly<Record<string, string>>
  readonly retryable: boolean

  constructor(init: UiErrorInit) {
    super(init.message)
    this.name = 'UiError'
    this.code = init.code
    this.path = init.path ?? null
    this.details = init.details ?? {}
    this.retryable = init.retryable ?? false
  }
}

/** True for any bounded UI failure, including one that crossed a transport. */
export function isUiError(value: unknown): value is UiError {
  return value instanceof UiError
}

/**
 * Server error codes are mapped onto the bounded client vocabulary. The mapping
 * never invents a success and never re-uses a server message verbatim.
 */
const SERVER_CODE_MAP: Readonly<Record<string, UiErrorCode>> = {
  LIFECYCLE_PLAN_EXPIRED: UI_ERROR_CODES.GATEWAY_PLAN_EXPIRED,
  LIFECYCLE_PLAN_MISMATCH: UI_ERROR_CODES.GATEWAY_PLAN_MISMATCH,
  LIFECYCLE_PREPARATION_EXPIRED: UI_ERROR_CODES.GATEWAY_PREPARATION_EXPIRED,
  LIFECYCLE_PREPARATION_CONSUMED: UI_ERROR_CODES.GATEWAY_PREPARATION_CONSUMED,
  LIFECYCLE_PREPARATION_STALE: UI_ERROR_CODES.GATEWAY_PREPARATION_STALE,
  LIFECYCLE_PREPARATION_MISMATCH: UI_ERROR_CODES.GATEWAY_PREPARATION_MISMATCH,
  LIFECYCLE_FULL_PREPARATION_EXPIRED: UI_ERROR_CODES.GATEWAY_PREPARATION_EXPIRED,
  LIFECYCLE_FULL_PREPARATION_CONSUMED: UI_ERROR_CODES.GATEWAY_PREPARATION_CONSUMED,
  LIFECYCLE_FULL_PREPARATION_STALE: UI_ERROR_CODES.GATEWAY_PREPARATION_STALE,
  LIFECYCLE_FULL_PREPARATION_MISMATCH: UI_ERROR_CODES.GATEWAY_PREPARATION_MISMATCH,
  LIFECYCLE_DRAFT_STALE: UI_ERROR_CODES.GATEWAY_STALE_REVISION,
  LIFECYCLE_PUBLISH_CONFLICT: UI_ERROR_CODES.GATEWAY_IDEMPOTENCY_CONFLICT,
  LIFECYCLE_SCHEDULE_STATE_CONFLICT: UI_ERROR_CODES.GATEWAY_STALE_REVISION,
  LIFECYCLE_SCHEDULE_PAUSED: UI_ERROR_CODES.GATEWAY_OPERATION_REFUSED,
  ACTION_OPERATION_FORBIDDEN: UI_ERROR_CODES.GATEWAY_PERMISSION_DENIED,
  ACTION_RECONCILIATION_REQUIRED: UI_ERROR_CODES.GATEWAY_OPERATION_REFUSED,
  ACTION_RETRY_NOT_PERMITTED: UI_ERROR_CODES.GATEWAY_OPERATION_REFUSED,
  ACTION_TERMINAL_IMMUTABLE: UI_ERROR_CODES.GATEWAY_OPERATION_REFUSED,
  ACTION_SUBMISSION_ATTEMPTS_EXHAUSTED: UI_ERROR_CODES.GATEWAY_OPERATION_REFUSED,
  ACTION_VERIFICATION_EXHAUSTED: UI_ERROR_CODES.GATEWAY_OPERATION_REFUSED,
  ACTION_STORE_CONFLICT: UI_ERROR_CODES.GATEWAY_STALE_REVISION,
  ACTION_NOT_FOUND: UI_ERROR_CODES.GATEWAY_NOT_FOUND,
  SEGMENT_PREVIEW_DATA_STALE: UI_ERROR_CODES.GATEWAY_STALE_REVISION,
  PERMISSION_DENIED: UI_ERROR_CODES.GATEWAY_PERMISSION_DENIED,
  RESOURCE_NOT_FOUND: UI_ERROR_CODES.GATEWAY_NOT_FOUND,
  // Campaign Plan HTTP error codes — every CAMPAIGN_PLAN_HTTP_* code maps to a bounded UI response.
  CAMPAIGN_PLAN_HTTP_REQUEST_MALFORMED: UI_ERROR_CODES.GATEWAY_RESPONSE_INVALID,
  CAMPAIGN_PLAN_HTTP_REQUEST_LIMIT_EXCEEDED: UI_ERROR_CODES.GATEWAY_UNEXPECTED,
  CAMPAIGN_PLAN_HTTP_REQUEST_INVALID: UI_ERROR_CODES.GATEWAY_RESPONSE_INVALID,
  CAMPAIGN_PLAN_HTTP_REQUEST_UNREADABLE: UI_ERROR_CODES.GATEWAY_RESPONSE_INVALID,
  CAMPAIGN_PLAN_HTTP_METHOD_NOT_ALLOWED: UI_ERROR_CODES.GATEWAY_ROUTE_NOT_EXPOSED,
  CAMPAIGN_PLAN_HTTP_UNSUPPORTED_MEDIA_TYPE: UI_ERROR_CODES.GATEWAY_RESPONSE_INVALID,
  CAMPAIGN_PLAN_HTTP_INTERNAL_FAILURE: UI_ERROR_CODES.GATEWAY_UNEXPECTED,
  CAMPAIGN_PLAN_HTTP_IDEMPOTENCY_KEY_REQUIRED: UI_ERROR_CODES.GATEWAY_RESPONSE_INVALID,
  CAMPAIGN_PLAN_HTTP_IDEMPOTENCY_KEY_INVALID: UI_ERROR_CODES.GATEWAY_RESPONSE_INVALID,
  CAMPAIGN_PLAN_HTTP_UNAUTHENTICATED: UI_ERROR_CODES.GATEWAY_PERMISSION_DENIED,
  CAMPAIGN_PLAN_HTTP_FORBIDDEN: UI_ERROR_CODES.GATEWAY_PERMISSION_DENIED,
  CAMPAIGN_PLAN_HTTP_NOT_FOUND: UI_ERROR_CODES.GATEWAY_NOT_FOUND,
  CAMPAIGN_PLAN_HTTP_CONFLICT: UI_ERROR_CODES.GATEWAY_IDEMPOTENCY_CONFLICT,
  CAMPAIGN_PLAN_HTTP_COMPILER_BASE_REVISIONS_STALE: UI_ERROR_CODES.GATEWAY_STALE_REVISION,
  CAMPAIGN_PLAN_HTTP_COMPILER_TARGET_UNAVAILABLE: UI_ERROR_CODES.GATEWAY_NOT_FOUND,
  CAMPAIGN_PLAN_HTTP_COMPILER_AUTHORIZATION_DENIED: UI_ERROR_CODES.GATEWAY_PERMISSION_DENIED,
  CAMPAIGN_PLAN_HTTP_COMPILER_CONTRACT_INVALID: UI_ERROR_CODES.GATEWAY_COMPILER_CONTRACT_INVALID,
  CAMPAIGN_PLAN_HTTP_COMPILER_TARGET_NOT_FOUND: UI_ERROR_CODES.GATEWAY_NOT_FOUND,
  CAMPAIGN_PLAN_HTTP_PLAN_STORE_REJECTED: UI_ERROR_CODES.GATEWAY_STALE_REVISION,
  CAMPAIGN_PLAN_HTTP_SERVICE_UNAVAILABLE: UI_ERROR_CODES.GATEWAY_ROUTE_NOT_EXPOSED,
}

const FIXED_MESSAGES: Readonly<Record<UiErrorCode, string>> = {
  UI_CONTRACT_FIELD_UNKNOWN: 'A response or asset carried a field outside the accepted contract.',
  UI_CONTRACT_SHAPE_INVALID: 'A value did not have the accepted JSON shape.',
  UI_CONTRACT_VALUE_INVALID: 'A value violated an accepted contract invariant.',
  UI_CONTRACT_ENVELOPE_INVALID: 'The response envelope could not be accepted.',
  UI_AUTHORING_NORMALIZATION_INVALID: 'The authoring intent could not be turned into a Proposal.',
  UI_GATEWAY_TIMEOUT: 'The request did not complete within its bounded timeout.',
  UI_GATEWAY_ABORTED: 'The request was superseded by a newer one.',
  UI_GATEWAY_TRANSPORT_FAILED: 'The request could not reach the server.',
  UI_GATEWAY_RESPONSE_INVALID: 'The server reply could not be accepted.',
  UI_GATEWAY_PERMISSION_DENIED: 'The current identity does not hold the required permission.',
  UI_GATEWAY_STALE_REVISION: 'The resource changed since it was read. Your edits were kept.',
  UI_GATEWAY_PLAN_EXPIRED: 'The Plan expired. Refresh and plan again.',
  UI_GATEWAY_PLAN_MISMATCH: 'The Plan identity no longer matches the stored Plan.',
  UI_GATEWAY_PREPARATION_EXPIRED: 'The Publish preparation expired. Prepare publication again.',
  UI_GATEWAY_PREPARATION_CONSUMED: 'The Publish preparation was already consumed.',
  UI_GATEWAY_PREPARATION_STALE: 'The Draft or a frozen input drifted. Prepare publication again.',
  UI_GATEWAY_PREPARATION_MISMATCH: 'The Publish preparation identity does not match.',
  UI_GATEWAY_IDEMPOTENCY_CONFLICT: 'That request key was already used with different content.',
  UI_GATEWAY_ROUTE_NOT_EXPOSED: 'The accepted backend does not expose this route yet.',
  UI_GATEWAY_OPERATION_REFUSED: 'The server refused this operation for the current state.',
  UI_GATEWAY_NOT_FOUND: 'The resource is not available.',
  UI_GATEWAY_UNEXPECTED: 'The request could not be completed.',
  UI_GATEWAY_COMPILER_CONTRACT_INVALID: 'The campaign configuration cannot be accepted. Check your campaign settings and try again.',
}

export function fixedMessage(code: UiErrorCode): string {
  return FIXED_MESSAGES[code]
}

export function contractError(code: UiErrorCode, path: string, detail?: string): UiError {
  return new UiError({
    code,
    message: fixedMessage(code),
    path,
    ...(detail === undefined ? {} : { details: { reason: detail } }),
  })
}

/** Decode a server error body into the bounded client vocabulary. */
export function fromServerErrorCode(code: string, path = '$'): UiError {
  const mapped = SERVER_CODE_MAP[code] ?? UI_ERROR_CODES.GATEWAY_UNEXPECTED
  return new UiError({ code: mapped, message: fixedMessage(mapped), path, details: { serverCode: code } })
}

export function permissionDenied(detail: string): UiError {
  return new UiError({
    code: UI_ERROR_CODES.GATEWAY_PERMISSION_DENIED,
    message: fixedMessage(UI_ERROR_CODES.GATEWAY_PERMISSION_DENIED),
    details: { requirement: detail },
  })
}

export function refused(reason: string): UiError {
  return new UiError({
    code: UI_ERROR_CODES.GATEWAY_OPERATION_REFUSED,
    message: fixedMessage(UI_ERROR_CODES.GATEWAY_OPERATION_REFUSED),
    details: { reason },
  })
}