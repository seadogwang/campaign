import type { ReactElement } from 'react'
import type { ParticipantSummary } from '../contracts/operations'
import { participantLabel } from '../contracts/operations'
import { StatusBadge } from './StatusBadge'
import { formatInstant } from './format'

/**
 * Participant drill-down: current node, wait reason and terminal/unresolved
 * state. Raw member identifiers are never rendered.
 */
export function ParticipantDrilldown({
  participants,
}: {
  readonly participants: readonly ParticipantSummary[]
}): ReactElement {
  return (
    <section className="card" aria-label="Participants">
      <header className="card__header">
        <h3>Participants</h3>
        <span className="card__state">{participants.length}</span>
      </header>
      {participants.length === 0 ? (
        <p className="card__empty">No participants for this execution.</p>
      ) : (
        <table className="data-table">
          <caption>Participant state</caption>
          <thead>
            <tr>
              <th scope="col">Participant</th>
              <th scope="col">Current node</th>
              <th scope="col">Status</th>
              <th scope="col">Wait reason</th>
              <th scope="col">Wait deadline</th>
              <th scope="col">Last accepted action</th>
              <th scope="col">State</th>
            </tr>
          </thead>
          <tbody>
            {participants.map((participant) => (
              <tr key={participant.participantId} data-terminal={participant.terminal ? 'true' : 'false'}>
                <td>{participantLabel(participant.participantId)}</td>
                <td>
                  <code>{participant.currentNodeLogicalId}</code>
                </td>
                <td>
                  <StatusBadge status={participant.status} />
                </td>
                <td>{participant.waitingReason ?? '—'}</td>
                <td>
                  {participant.waitingDeadline === null ? (
                    '—'
                  ) : (
                    <time dateTime={participant.waitingDeadline}>
                      {formatInstant(participant.waitingDeadline).display}
                    </time>
                  )}
                </td>
                <td>
                  {participant.lastAcceptedActionInstant === null ? (
                    '—'
                  ) : (
                    <time dateTime={participant.lastAcceptedActionInstant}>
                      {formatInstant(participant.lastAcceptedActionInstant).display}
                    </time>
                  )}
                </td>
                <td>{participant.terminal ? 'Terminal' : 'Unresolved'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  )
}