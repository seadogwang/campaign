import type { ReactElement } from 'react'
import type { ExecutionSummary } from '../contracts/operations'
import { executionAggregate } from '../contracts/operations'
import { StatusBadge } from './StatusBadge'
import { formatCount, formatInstant } from './format'

/** Execution list with aggregate counters. */
export function ExecutionList({
  executions,
  selectedId,
  onSelect,
}: {
  readonly executions: readonly ExecutionSummary[]
  readonly selectedId: string | null
  readonly onSelect: (executionId: string) => void
}): ReactElement {
  return (
    <section className="card" aria-label="Executions">
      <header className="card__header">
        <h3>Executions</h3>
        <span className="card__state">{formatCount(executions.length)}</span>
      </header>
      {executions.length === 0 ? (
        <p className="card__empty">No executions.</p>
      ) : (
        <table className="data-table">
          <caption>Campaign executions and aggregate counters</caption>
          <thead>
            <tr>
              <th scope="col">Execution</th>
              <th scope="col">Status</th>
              <th scope="col">Candidates</th>
              <th scope="col">Enrolled</th>
              <th scope="col">Completed</th>
              <th scope="col">Exited</th>
              <th scope="col">Failed</th>
              <th scope="col">Needs review</th>
              <th scope="col">Waiting</th>
              <th scope="col">Running</th>
              <th scope="col">Unresolved</th>
              <th scope="col">Due</th>
              <th scope="col">Select</th>
            </tr>
          </thead>
          <tbody>
            {executions.map((execution) => {
              const aggregate = executionAggregate(execution)
              return (
                <tr key={execution.executionId} data-status={execution.status}>
                  <td>
                    <code>{execution.executionId}</code>
                  </td>
                  <td>
                    <StatusBadge status={execution.status} />
                  </td>
                  <td>{formatCount(execution.candidateCount)}</td>
                  <td>{formatCount(execution.enrolledCount)}</td>
                  <td>{formatCount(execution.counters.completedCount)}</td>
                  <td>{formatCount(execution.counters.exitedCount)}</td>
                  <td>{formatCount(execution.counters.failedCount)}</td>
                  <td>{formatCount(execution.counters.needsReviewCount)}</td>
                  <td>{formatCount(execution.counters.waitingCount)}</td>
                  <td>{formatCount(execution.counters.runningCount)}</td>
                  <td>{formatCount(aggregate.unresolved)}</td>
                  <td>
                    <time dateTime={execution.dueInstant}>{formatInstant(execution.dueInstant).display}</time>
                  </td>
                  <td>
                    <button
                      type="button"
                      className="button"
                      aria-pressed={selectedId === execution.executionId}
                      onClick={() => onSelect(execution.executionId)}
                    >
                      Inspect
                    </button>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      )}
    </section>
  )
}