import type { ReactElement } from 'react'
import { planPresentable } from '../contracts/plan'
import { formatInstant, hashSuffix, shortReference } from './format'
import { useWorkspace } from '../state/workspaceStore'
import { StatusBadge } from './StatusBadge'

/**
 * Save is an explicit Apply.
 *
 * The request carries only the server-stored `planId` and `planHash`. The UI
 * never uploads a ChangeSet, a Manifest, a component, a capability pin or a
 * trusted revision fact.
 */
export function SaveControls(): ReactElement {
  const workspace = useWorkspace()
  const plan = workspace.state.plan.value
  const applySlice = workspace.state.apply
  const canEdit = workspace.permissions.has('campaign.edit')
  const expired = plan !== null && plan.planRef.expiresAt <= workspace.nowInstant
  const blocking = plan?.validationIssues.filter((issue) => issue.severity === 'ERROR').length ?? 0
  const presentable = plan !== null && planPresentable(plan, workspace.nowInstant)

  const disabledReason = !canEdit
    ? 'Requires the campaign.edit permission.'
    : plan === null
      ? 'Submit a Proposal first.'
      : expired
        ? 'The Plan expired. Refresh and plan again.'
        : blocking > 0
          ? 'The Plan has blocking validation errors.'
          : plan.status !== 'READY'
            ? `The Plan status is ${plan.status}.`
            : null

  return (
    <section className="card" aria-label="Save">
      <header className="card__header">
        <h3>Save (Apply)</h3>
        {plan === null ? null : <StatusBadge status={expired ? 'EXPIRED' : plan.status} />}
      </header>
      <p className="card__note">
        Apply re-loads the server-stored Plan by identifier and re-checks its hash, ownership, expiry
        and every base revision. Only these two values are sent:
      </p>
      <pre className="payload-preview" data-testid="apply-payload">
        {plan === null
          ? 'No Plan to apply.'
          : JSON.stringify({ planId: plan.planRef.planId, planHash: plan.planRef.planHash }, null, 2)}
      </pre>
      {plan === null ? null : (
        <dl className="facts">
          <div>
            <dt>Plan</dt>
            <dd>
              <code>{shortReference(plan.planRef.planId)}</code>
            </dd>
          </div>
          <div>
            <dt>Hash</dt>
            <dd>
              <code>{hashSuffix(plan.planRef.planHash)}</code>
            </dd>
          </div>
          <div>
            <dt>Expires</dt>
            <dd>
              <time dateTime={plan.planRef.expiresAt}>{formatInstant(plan.planRef.expiresAt).display}</time>
            </dd>
          </div>
        </dl>
      )}
      <button
        type="button"
        className="button button--primary"
        disabled={!presentable || applySlice.status === 'LOADING'}
        aria-describedby={disabledReason === null ? undefined : 'apply-disabled-reason'}
        onClick={() => {
          void workspace.applyPlan()
        }}
      >
        {applySlice.status === 'LOADING' ? 'Applying…' : 'Apply Plan'}
      </button>
      {disabledReason === null ? null : (
        <p id="apply-disabled-reason" className="card__reason">
          {disabledReason}
        </p>
      )}
      {applySlice.error === null ? null : (
        <p className="card__reason" role="alert">
          {applySlice.error.message} <code>{applySlice.error.code}</code>
        </p>
      )}
      {applySlice.value === null ? null : (
        <p className="card__ok" data-testid="apply-result">
          Saved Draft <code>{shortReference(applySlice.value.draftId)}</code> at revision{' '}
          {applySlice.value.draftRevision} (campaign revision {applySlice.value.campaignRevision}).
        </p>
      )}
    </section>
  )
}