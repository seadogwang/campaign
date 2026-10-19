import type { ReactElement } from 'react'
import { CHANGE_KINDS, type CampaignPlanSummary, type ChangeKind, type DiffEntry } from '../contracts/plan'
import { StatusBadge } from './StatusBadge'
import { formatCount } from './format'

const KIND_ORDER: readonly ChangeKind[] = CHANGE_KINDS

const KIND_LABEL: Readonly<Record<ChangeKind, string>> = {
  ADD: 'Added',
  UPDATE: 'Changed',
  DELETE: 'Deleted',
  MOVE: 'Moved',
}

const KIND_STATUS: Readonly<Record<ChangeKind, string>> = {
  ADD: 'READY',
  UPDATE: 'WAITING',
  DELETE: 'DEFINITELY_FAILED',
  MOVE: 'IN_FLIGHT',
}

/**
 * Campaign Diff with added/changed/deleted/moved semantics and an explicit
 * unsaved-state indicator. Value-minimized: only field path codes are shown,
 * never a payload value.
 */
export function CampaignDiffCard({
  plan,
  hasUnsavedEdits,
}: {
  readonly plan: CampaignPlanSummary | null
  readonly hasUnsavedEdits: boolean
}): ReactElement {
  const grouped = new Map<ChangeKind, DiffEntry[]>()
  for (const kind of KIND_ORDER) {
    grouped.set(kind, [])
  }
  for (const entry of plan?.semanticDiff ?? []) {
    grouped.get(entry.kind)?.push(entry)
  }
  return (
    <section className="card" aria-label="Campaign diff">
      <header className="card__header">
        <h3>Campaign Diff</h3>
        <span className="card__state" data-testid="diff-unsaved">
          {hasUnsavedEdits ? 'Unsaved edits in this browser' : 'No unsaved edits'}
        </span>
      </header>
      {plan === null ? (
        <p className="card__empty">Submit a Proposal to see the semantic diff.</p>
      ) : (
        <>
          <p className="card__summary">
            {formatCount(plan.semanticDiff.length)} diff entr
            {plan.semanticDiff.length === 1 ? 'y' : 'ies'}
          </p>
          {KIND_ORDER.map((kind) => {
            const entries = grouped.get(kind) ?? []
            return (
              <div className="diff-group" key={kind} data-kind={kind}>
                <h4>
                  <StatusBadge status={KIND_STATUS[kind]} labelOverride={KIND_LABEL[kind]} />
                  <span className="diff-group__count">{formatCount(entries.length)}</span>
                </h4>
                {entries.length === 0 ? (
                  <p className="card__empty">None</p>
                ) : (
                  <ul className="diff-list">
                    {entries.map((entry) => (
                      <li key={`${kind}-${entry.logicalId}`}>
                        <code>{entry.logicalId}</code>
                        <span className="diff-list__capability">{entry.capabilityCode}</span>
                        <span className="diff-list__fields">{entry.fieldPaths.join(', ') || 'no fields'}</span>
                      </li>
                    ))}
                  </ul>
                )}
              </div>
            )
          })}
        </>
      )}
    </section>
  )
}