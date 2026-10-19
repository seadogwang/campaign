import type { ReactElement } from 'react'
import type { SimulationSummary } from '../contracts/simulation'
import { SIMULATED_ENVIRONMENT } from '../contracts/simulation'
import { StatusBadge } from './StatusBadge'
import { formatCount, formatInstant } from './format'
import type { UiError } from '../contracts/errors'

/**
 * Simulation results. The `SIMULATED` marker is persistent, assumptions are
 * explicit, virtual actions are named as virtual, unknown branches are listed,
 * and nothing suggests a real Provider was called.
 */
export function SimulationCard({
  simulation,
  error,
  onRun,
  disabledReason,
}: {
  readonly simulation: SimulationSummary | null
  readonly error: UiError | null
  readonly onRun: () => void
  readonly disabledReason: string | null
}): ReactElement {
  return (
    <section className="card" aria-label="Simulation results">
      <header className="card__header">
        <h3>Simulation</h3>
        <span className="simulated-marker" data-testid="simulation-marker">
          {SIMULATED_ENVIRONMENT}
        </span>
      </header>
      <p className="card__note">
        A simulation calls no Provider, creates no Execution, Participant, Action, budget reservation
        or publish confirmation, and is never evidence that a message was sent or a reward granted.
      </p>
      <button
        type="button"
        className="button"
        onClick={onRun}
        disabled={disabledReason !== null}
        aria-describedby={disabledReason === null ? undefined : 'simulation-disabled-reason'}
      >
        Run simulation
      </button>
      {disabledReason === null ? null : (
        <p id="simulation-disabled-reason" className="card__reason">
          {disabledReason}
        </p>
      )}
      {error === null ? null : (
        <p className="card__reason" role="alert">
          {error.message} <code>{error.code}</code>
        </p>
      )}
      {simulation === null ? (
        <p className="card__empty">No simulation has been run yet.</p>
      ) : (
        <>
          <dl className="facts">
            <div>
              <dt>Environment</dt>
              <dd>{simulation.environment}</dd>
            </div>
            <div>
              <dt>Virtual clock start</dt>
              <dd>
                <time dateTime={simulation.virtualStartInstant}>
                  {formatInstant(simulation.virtualStartInstant).display}
                </time>
              </dd>
            </div>
            <div>
              <dt>Virtual timezone</dt>
              <dd>{simulation.virtualTimezone}</dd>
            </div>
            <div>
              <dt>Virtual paths</dt>
              <dd>{formatCount(simulation.paths.length)}</dd>
            </div>
            <div>
              <dt>Unknown branches</dt>
              <dd data-testid="simulation-unknown-branches">{formatCount(simulation.unknownBranchCount)}</dd>
            </div>
            <div>
              <dt>Virtual actions</dt>
              <dd data-testid="simulation-virtual-actions">{formatCount(simulation.virtualActionCount)}</dd>
            </div>
          </dl>
          <h4>Assumptions</h4>
          {simulation.assumptions.length === 0 ? (
            <p className="card__empty">No assumption was needed.</p>
          ) : (
            <ul className="assumption-list">
              {simulation.assumptions.map((assumption, index) => (
                <li key={`${assumption.nodeLogicalId}-${assumption.assumedBranch}-${index}`}>
                  <code>{assumption.factCode}</code> at <code>{assumption.nodeLogicalId}</code> was
                  unknown; the <strong>{assumption.assumedBranch}</strong> arm was explored.
                </li>
              ))}
            </ul>
          )}
          <h4>Paths</h4>
          <ul className="path-list">
            {simulation.paths.map((path, index) => (
              <li key={`${path.terminalNodeLogicalId}-${index}`}>
                <StatusBadge status={path.status} />
                <span>
                  terminal <code>{path.terminalNodeLogicalId}</code>,{' '}
                  {formatCount(path.visitedNodeLogicalIds.length)} steps,{' '}
                  {formatCount(path.actions.length)} virtual action
                  {path.actions.length === 1 ? '' : 's'}
                </span>
                {path.errorReasonCode === null ? null : (
                  <code className="path-list__reason">{path.errorReasonCode}</code>
                )}
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  )
}