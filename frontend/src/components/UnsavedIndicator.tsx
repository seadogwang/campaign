import type { ReactElement } from 'react'
import type { CampaignPlanSummary } from '../contracts/plan'
import type { DraftSummary } from '../contracts/summaries'
import { hashSuffix, shortReference } from './format'

/**
 * Keeps the three state slices visibly distinct: local unsaved editor state,
 * the last server Plan, and the saved Draft.
 */
export function UnsavedIndicator({
  hasUnsavedEdits,
  lastPlan,
  savedDraft,
  onDiscard,
}: {
  readonly hasUnsavedEdits: boolean
  readonly lastPlan: CampaignPlanSummary | null
  readonly savedDraft: DraftSummary | null
  readonly onDiscard: () => void
}): ReactElement {
  return (
    <section className="state-panel" aria-label="Editor and server state">
      <div className="state-panel__row" data-state="unsaved">
        <h3>Unsaved editor state</h3>
        <p data-testid="unsaved-state">
          {hasUnsavedEdits ? 'You have unsaved edits in this browser.' : 'No unsaved edits.'}
        </p>
        {hasUnsavedEdits ? (
          <button type="button" className="button" onClick={onDiscard}>
            Discard unsaved edits
          </button>
        ) : null}
      </div>
      <div className="state-panel__row" data-state="plan">
        <h3>Last server Plan</h3>
        {lastPlan === null ? (
          <p data-testid="plan-state">No Plan has been returned yet.</p>
        ) : (
          <p data-testid="plan-state">
            Plan <code>{shortReference(lastPlan.planRef.planId)}</code> · hash{' '}
            <code>{hashSuffix(lastPlan.planRef.planHash)}</code> · expires{' '}
            <time dateTime={lastPlan.planRef.expiresAt}>{lastPlan.planRef.expiresAt}</time>
          </p>
        )}
      </div>
      <div className="state-panel__row" data-state="draft">
        <h3>Saved Draft</h3>
        {savedDraft === null ? (
          <p data-testid="draft-state">No Draft has been saved yet.</p>
        ) : (
          <p data-testid="draft-state">
            Draft <code>{shortReference(savedDraft.draftId)}</code> · revision {savedDraft.draftRevision} ·
            campaign revision {savedDraft.campaignRevision}
          </p>
        )}
      </div>
    </section>
  )
}