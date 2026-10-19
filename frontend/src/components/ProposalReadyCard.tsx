import type { ReactElement } from 'react'
import type { CampaignPlanSummary } from '../contracts/plan'
import type { CampaignProposalPatch } from '../contracts/proposal'
import type { AsyncSlice } from '../state/workspaceStore'
import { StatusBadge } from './StatusBadge'

/**
 * A finished Campaign configuration.
 *
 * It is rendered only when the server itself returned `PROPOSAL_READY` together
 * with the Proposal it produced. The one action it offers is the existing, real
 * one: submit that host Proposal to create the server Plan. It never claims the
 * Campaign is saved, published or running, because none of that has happened.
 */
export function ProposalReadyCard({
  proposal,
  plan,
  canEdit,
  onCreate,
}: {
  readonly proposal: CampaignProposalPatch
  readonly plan: AsyncSlice<CampaignPlanSummary>
  readonly canEdit: boolean
  readonly onCreate: () => void
}): ReactElement {
  const creating = plan.status === 'LOADING'
  return (
    <section
      className="card conversation__complete"
      data-testid="proposal-ready"
      aria-label="Campaign configuration complete"
    >
      <h3>Your Campaign is configured</h3>
      <p>
        The server assembled a complete Campaign from everything you described — audience, message,
        offer, journey, timing and guardrails, as far as your Campaign needs them.
      </p>
      <p className="card__note" data-testid="proposal-summary">
        {`${proposal.operations.length} configured part(s).`}
      </p>

      <button
        type="button"
        className="button button--primary conversation__complete-action"
        data-testid="create-plan"
        disabled={!canEdit || creating}
        onClick={onCreate}
      >
        {creating ? 'Creating the plan…' : 'Create Campaign plan'}
      </button>

      {plan.error === null ? null : (
        <p className="conversation__error" data-testid="plan-error" role="alert">
          {plan.error.message} <code>{plan.error.code}</code>
        </p>
      )}

      {plan.value === null ? null : (
        <p className="conversation__plan-result" data-testid="plan-ready" role="status">
          The server created the Campaign plan. <StatusBadge status={plan.value.status} />
          <span className="card__note">
            Creating the plan does not publish the Campaign and does not start it running.
          </span>
        </p>
      )}

      {canEdit ? null : (
        <p className="card__note" data-testid="proposal-permission-note">
          You do not have permission to create the plan for this Campaign.
        </p>
      )}
    </section>
  )
}