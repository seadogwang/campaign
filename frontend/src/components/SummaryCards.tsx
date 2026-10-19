import type { ReactElement } from 'react'
import type { SummaryCards as SummaryCardsModel } from '../contracts/summaries'
import { StatusBadge } from './StatusBadge'
import { formatInstant } from './format'

/** Message, Promotion, Journey, Schedule and Governance summary cards. */
export function SummaryCards({ cards }: { readonly cards: SummaryCardsModel | null }): ReactElement {
  if (cards === null) {
    return (
      <section className="card" aria-label="Capability summaries">
        <h3>Capability summaries</h3>
        <p className="card__empty">No workspace has been loaded yet.</p>
      </section>
    )
  }
  return (
    <div className="summary-grid">
      <section className="card" aria-label="Message summary">
        <h3>Message</h3>
        {cards.messages.length === 0 ? (
          <p className="card__empty">No message component.</p>
        ) : (
          <ul className="summary-list">
            {cards.messages.map((message) => (
              <li key={message.logicalId}>
                <code>{message.logicalId}</code>
                <span>{message.channel}</span>
                <span>{message.locale}</span>
                <span>template {message.capabilityCode}</span>
                <span data-testid={`message-content-${message.logicalId}`}>
                  {message.contentPresent ? 'content present' : 'no content carried'}
                </span>
                <StatusBadge status={message.status} />
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="card" aria-label="Promotion summary">
        <h3>Promotion</h3>
        {cards.promotions.length === 0 ? (
          <p className="card__empty">No promotion component.</p>
        ) : (
          <ul className="summary-list">
            {cards.promotions.map((promotion) => (
              <li key={promotion.logicalId}>
                <code>{promotion.logicalId}</code>
                <span>{promotion.benefitKind}</span>
                <span data-testid={`promotion-budget-${promotion.logicalId}`}>
                  budget {promotion.budgetState}
                </span>
                <StatusBadge status={promotion.budgetState} labelOverride={`Budget ${promotion.budgetState.toLowerCase()}`} />
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="card" aria-label="Journey summary">
        <h3>Journey</h3>
        {cards.journeys.length === 0 ? (
          <p className="card__empty">No journey component.</p>
        ) : (
          <ul className="summary-list">
            {cards.journeys.map((journey) => (
              <li key={journey.logicalId}>
                <code>{journey.logicalId}</code>
                <span>entry {journey.entryNodeLogicalId}</span>
                <span>{journey.nodeCount} nodes</span>
                <span>max {journey.maxStepsPerPath} steps per path</span>
                <StatusBadge status={journey.status} />
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="card" aria-label="Schedule summary">
        <h3>Schedule</h3>
        {cards.schedules.length === 0 ? (
          <p className="card__empty">No schedule component.</p>
        ) : (
          <ul className="summary-list">
            {cards.schedules.map((schedule) => (
              <li key={schedule.logicalId}>
                <code>{schedule.logicalId}</code>
                <span>{schedule.form}</span>
                <span>{schedule.zoneId}</span>
                {schedule.runAt === null ? null : (
                  <span>
                    at <time dateTime={schedule.runAt}>{formatInstant(schedule.runAt).display}</time>
                  </span>
                )}
                {schedule.localTime === null ? null : (
                  <span>
                    {schedule.localTime} {schedule.startDate} → {schedule.endDate}
                  </span>
                )}
                {schedule.nextOccurrence === null ? null : (
                  <span>
                    next <time dateTime={schedule.nextOccurrence}>{formatInstant(schedule.nextOccurrence).display}</time>
                  </span>
                )}
                <StatusBadge status={schedule.status} />
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="card" aria-label="Governance summary">
        <h3>Governance</h3>
        {cards.governance.length === 0 ? (
          <p className="card__empty">No governance component.</p>
        ) : (
          <ul className="summary-list">
            {cards.governance.map((governance) => (
              <li key={governance.logicalId}>
                <code>{governance.logicalId}</code>
                <StatusBadge status={governance.approvalState} labelOverride={`Approval ${governance.approvalState.toLowerCase()}`} />
                <span>required risk codes: {governance.requiredRiskCodes.join(', ') || 'none'}</span>
                <ul className="readiness-list">
                  {governance.capabilities.map((capability) => (
                    <li key={capability.capabilityId}>
                      <code>{capability.capabilityId}</code>
                      <StatusBadge status={capability.state} />
                      <span>{capability.reasonCodes.join(', ') || 'no reason codes'}</span>
                    </li>
                  ))}
                </ul>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  )
}