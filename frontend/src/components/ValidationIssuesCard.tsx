import type { ReactElement } from 'react'
import { ISSUE_SEVERITIES, type CampaignPlanSummary, type IssueSeverity, type PlanIssue } from '../contracts/plan'
import { StatusBadge } from './StatusBadge'
import { formatCount } from './format'

const SEVERITY_STATUS: Readonly<Record<IssueSeverity, string>> = {
  ERROR: 'INVALID',
  WARNING: 'STALE',
  INFO: 'EVALUATING',
}

/** Validation issues grouped by severity and then by stable code. */
export function ValidationIssuesCard({ plan }: { readonly plan: CampaignPlanSummary | null }): ReactElement {
  const issues = plan?.validationIssues ?? []
  return (
    <section className="card" aria-label="Validation issues">
      <header className="card__header">
        <h3>Validation issues</h3>
        <span className="card__state">{formatCount(issues.length)} total</span>
      </header>
      {issues.length === 0 ? (
        <p className="card__empty">{plan === null ? 'No Plan yet.' : 'No validation issues.'}</p>
      ) : (
        ISSUE_SEVERITIES.map((severity) => {
          const ofSeverity = issues.filter((issue) => issue.severity === severity)
          if (ofSeverity.length === 0) {
            return null
          }
          const byCode = new Map<string, PlanIssue[]>()
          for (const issue of ofSeverity) {
            const bucket = byCode.get(issue.code) ?? []
            bucket.push(issue)
            byCode.set(issue.code, bucket)
          }
          return (
            <div className="issue-group" key={severity} data-severity={severity}>
              <h4>
                <StatusBadge status={SEVERITY_STATUS[severity]} labelOverride={severity} />
                <span className="issue-group__count">{formatCount(ofSeverity.length)}</span>
              </h4>
              <ul className="issue-list">
                {[...byCode.entries()].map(([code, group]) => (
                  <li key={code}>
                    <code className="issue-list__code">{code}</code>
                    <span className="issue-list__message">{group[0]?.message ?? ''}</span>
                    <span className="issue-list__count">
                      {formatCount(group.length)} occurrence{group.length === 1 ? '' : 's'}
                    </span>
                  </li>
                ))}
              </ul>
            </div>
          )
        })
      )}
    </section>
  )
}