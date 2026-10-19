import { useState, type ReactElement } from 'react'
import type { ActionSummary } from '../contracts/operations'
import { UNRESOLVED_ACTION_STATUSES, countUnresolvedActions, isUnresolvedActionStatus } from '../contracts/operations'
import { StatusBadge } from './StatusBadge'
import { ConfirmDialog } from './ConfirmDialog'
import { formatCount, formatInstant } from './format'

/**
 * Action drill-down.
 *
 * Reconcile and safe retry are distinct. There is deliberately no generic
 * "send again" action: an UNKNOWN or NEEDS_REVIEW action can never be blindly
 * resent. Safe retry is offered only when the server's eligibility says
 * definitive non-application evidence exists and attempt limits permit it.
 */
export function ActionDrilldown({
  actions,
  canOperate,
  busy,
  onReconcile,
  onSafeRetry,
}: {
  readonly actions: readonly ActionSummary[]
  readonly canOperate: boolean
  readonly busy: boolean
  readonly onReconcile: (action: ActionSummary) => void
  readonly onSafeRetry: (action: ActionSummary) => void
}): ReactElement {
  const [reconcileTarget, setReconcileTarget] = useState<ActionSummary | null>(null)
  const [retryTarget, setRetryTarget] = useState<ActionSummary | null>(null)
  const unresolved = actions.filter((action) => isUnresolvedActionStatus(action.status))

  return (
    <section className="card" aria-label="Actions">
      <header className="card__header">
        <h3>Actions</h3>
        <span className="card__state">{formatCount(actions.length)}</span>
      </header>

      {unresolved.length === 0 ? null : (
        <div className="callout callout--unresolved" role="alert" data-testid="unresolved-callout">
          <h4>Unresolved actions</h4>
          <p>
            {formatCount(unresolved.length)} action{unresolved.length === 1 ? '' : 's'} in{' '}
            {UNRESOLVED_ACTION_STATUSES.join(', ')}. A remote effect may exist and cannot be settled
            by resending. Reconcile queries the original business key; retry is a separate, guarded
            action.
          </p>
          <ul>
            {unresolved.map((action) => (
              <li key={action.actionId}>
                <code>{action.actionId}</code> <StatusBadge status={action.status} />{' '}
                {action.reasonCode ?? 'no reason code'}
              </li>
            ))}
          </ul>
        </div>
      )}

      {actions.length === 0 ? (
        <p className="card__empty">No actions for this execution.</p>
      ) : (
        <table className="data-table">
          <caption>Action execution state</caption>
          <thead>
            <tr>
              <th scope="col">Action</th>
              <th scope="col">Kind</th>
              <th scope="col">Node</th>
              <th scope="col">Status</th>
              <th scope="col">Idempotency</th>
              <th scope="col">Submissions</th>
              <th scope="col">Verifications</th>
              <th scope="col">Accepted</th>
              <th scope="col">Budget</th>
              <th scope="col">Provider outcome</th>
              <th scope="col">Evidence</th>
              <th scope="col">Controls</th>
            </tr>
          </thead>
          <tbody>
            {actions.map((action) => {
              const reconcilable = action.status === 'UNKNOWN' || action.status === 'VERIFYING'
              const retryable = action.retryEligibility.safeRetryPermitted
              const reconcileDisabled = !canOperate || busy || !reconcilable
              const retryDisabled = !canOperate || busy || !retryable
              return (
                <tr key={action.actionId} data-status={action.status}>
                  <td>
                    <code>{action.actionId}</code>
                  </td>
                  <td>{action.actionKind}</td>
                  <td>
                    <code>{action.nodeLogicalId}</code>
                  </td>
                  <td>
                    <StatusBadge status={action.status} detail={action.reasonCode} />
                  </td>
                  <td>{action.idempotencySafe ? 'stable key' : 'unstable'}</td>
                  <td>{formatCount(action.submissionCount)}</td>
                  <td>{formatCount(action.verificationCount)}</td>
                  <td>
                    {action.acceptedInstant === null ? (
                      '—'
                    ) : (
                      <time dateTime={action.acceptedInstant}>
                        {formatInstant(action.acceptedInstant).display}
                      </time>
                    )}
                  </td>
                  <td>
                    <StatusBadge status={action.budgetState} labelOverride={`Budget ${action.budgetState.toLowerCase()}`} />
                  </td>
                  <td>{action.providerOutcome ?? '—'}</td>
                  <td data-testid={`evidence-${action.actionId}`}>
                    {action.providerEvidence.referencePresent
                      ? 'bounded provider reference present'
                      : 'no provider reference'}
                    {action.providerEvidence.reasonCode === null ? '' : ` (${action.providerEvidence.reasonCode})`}
                  </td>
                  <td>
                    <div className="action-controls">
                      <button
                        type="button"
                        className="button"
                        disabled={reconcileDisabled}
                        aria-describedby={`reconcile-reason-${action.actionId}`}
                        onClick={() => setReconcileTarget(action)}
                      >
                        Reconcile
                      </button>
                      <button
                        type="button"
                        className="button"
                        disabled={retryDisabled}
                        aria-describedby={`retry-reason-${action.actionId}`}
                        onClick={() => setRetryTarget(action)}
                      >
                        Safe retry
                      </button>
                      <span className="action-controls__reason" id={`reconcile-reason-${action.actionId}`}>
                        {canOperate
                          ? reconcilable
                            ? 'Reconcile queries the original business key.'
                            : 'Only an UNKNOWN or VERIFYING action can be reconciled.'
                          : 'Requires the campaign.operate permission.'}
                      </span>
                      <span className="action-controls__reason" id={`retry-reason-${action.actionId}`}>
                        {canOperate
                          ? retryable
                            ? 'Definitive non-application evidence and attempts remain.'
                            : `Safe retry not permitted: ${action.retryEligibility.reasonCodes.join(', ') || 'no eligibility evidence'}.`
                          : 'Requires the campaign.operate permission.'}
                      </span>
                    </div>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      )}

      <ConfirmDialog
        open={reconcileTarget !== null}
        title="Reconcile this action?"
        confirmLabel="Reconcile now"
        onCancel={() => setReconcileTarget(null)}
        onConfirm={() => {
          const target = reconcileTarget
          setReconcileTarget(null)
          if (target !== null) {
            onReconcile(target)
          }
        }}
      >
        <p>
          Reconciliation queries the original business key <code>{reconcileTarget?.actionId ?? ''}</code> with
          the original idempotency key. It does <strong>not</strong> create a second remote effect and
          does not resend the action.
        </p>
      </ConfirmDialog>

      <ConfirmDialog
        open={retryTarget !== null}
        title="Safe retry this action?"
        confirmLabel="Safe retry now"
        onCancel={() => setRetryTarget(null)}
        onConfirm={() => {
          const target = retryTarget
          setRetryTarget(null)
          if (target !== null) {
            onSafeRetry(target)
          }
        }}
      >
        <p>
          Safe retry reuses the original action identity, idempotency key and budget reservation. It is
          offered only because the server reported definitive non-application evidence and attempts
          remain.
        </p>
      </ConfirmDialog>
    </section>
  )
}

export function unresolvedActionCount(actions: readonly ActionSummary[]): number {
  return countUnresolvedActions(actions)
}