import type { ReactElement } from 'react'
import { UI_ERROR_CODES } from '../contracts/errors'
import type { UiError } from '../contracts/errors'

const KEEP_EDITS_CODES: readonly string[] = [
  UI_ERROR_CODES.GATEWAY_STALE_REVISION,
  UI_ERROR_CODES.GATEWAY_PLAN_EXPIRED,
  UI_ERROR_CODES.GATEWAY_PLAN_MISMATCH,
  UI_ERROR_CODES.GATEWAY_PREPARATION_EXPIRED,
  UI_ERROR_CODES.GATEWAY_PREPARATION_CONSUMED,
  UI_ERROR_CODES.GATEWAY_PREPARATION_STALE,
  UI_ERROR_CODES.GATEWAY_IDEMPOTENCY_CONFLICT,
  UI_ERROR_CODES.GATEWAY_TRANSPORT_FAILED,
  UI_ERROR_CODES.GATEWAY_TIMEOUT,
  UI_ERROR_CODES.GATEWAY_PERMISSION_DENIED,
  UI_ERROR_CODES.CONTRACT_VALUE_INVALID,
]

export function editsPreserved(code: string): boolean {
  return KEEP_EDITS_CODES.includes(code)
}

/**
 * A bounded failure. It renders only the stable code, the fixed message and an
 * optional bounded reason — never a stack trace, a raw response body or a
 * payload value.
 */
export function NoticeBanner({
  error,
  onDismiss,
  onRefresh,
}: {
  readonly error: UiError
  readonly onDismiss: () => void
  readonly onRefresh?: () => void
}): ReactElement {
  const preserved = editsPreserved(error.code)
  return (
    <div className="notice notice--error" role="alert" data-testid="notice-banner">
      <p className="notice__message">{error.message}</p>
      <p className="notice__code">
        Code <code>{error.code}</code>
        {error.path === null ? null : (
          <>
            {' '}at <code>{error.path}</code>
          </>
        )}
      </p>
      {preserved ? (
        <p className="notice__hint">
          Your unsaved edits were kept. Refresh or plan again before retrying.
        </p>
      ) : null}
      <div className="notice__actions">
        {onRefresh === undefined ? null : (
          <button type="button" className="button" onClick={onRefresh}>
            Refresh from server
          </button>
        )}
        <button type="button" className="button" onClick={onDismiss}>
          Dismiss
        </button>
      </div>
    </div>
  )
}