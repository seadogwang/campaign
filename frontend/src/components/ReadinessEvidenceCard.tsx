import type { ReactElement } from 'react'
import { DOCUMENTED_ROUTES } from '../contracts/readiness'
import type { CapabilityReadiness } from '../contracts/summaries'
import { StatusBadge } from './StatusBadge'

/**
 * Source and readiness evidence.
 *
 * Shows that the Segment capability remains EXPERIMENTAL or that a Gate review
 * is pending, and which documented routes the accepted backend actually
 * exposes. It never presents a development asset as production evidence.
 */
export function ReadinessEvidenceCard({
  readiness,
}: {
  readonly readiness: readonly CapabilityReadiness[]
}): ReactElement {
  const exposed = DOCUMENTED_ROUTES.filter((route) => route.exposedByAcceptedBackend)
  const pending = DOCUMENTED_ROUTES.filter((route) => !route.exposedByAcceptedBackend)
  return (
    <section className="card" aria-label="Readiness evidence">
      <header className="card__header">
        <h3>Source and readiness evidence</h3>
      </header>
      <h4>Capability readiness</h4>
      {readiness.length === 0 ? (
        <p className="card__empty">No readiness projection is available.</p>
      ) : (
        <ul className="readiness-list" data-testid="readiness-list">
          {readiness.map((capability) => (
            <li key={capability.capabilityId}>
              <code>{capability.capabilityId}</code>
              <StatusBadge status={capability.state} />
              <span>
                {capability.reasonCodes.includes('REFERENCE_APPROVAL_PENDING')
                  ? 'Gate review pending'
                  : capability.state === 'EXPERIMENTAL'
                    ? 'No approved PASSED Gate decision covers this capability'
                    : capability.reasonCodes.join(', ') || 'no reason codes'}
              </span>
            </li>
          ))}
        </ul>
      )}

      <h4>Backend route exposure</h4>
      <p className="card__note">
        The UI implements the documented <code>/api/v1</code> contract behind one gateway. It does not
        claim that a route which the accepted backend does not expose is wired.
      </p>
      <table className="route-table">
        <caption>Documented Platform API routes and current exposure</caption>
        <thead>
          <tr>
            <th scope="col">Method</th>
            <th scope="col">Route</th>
            <th scope="col">Exposed today</th>
            <th scope="col">Note</th>
          </tr>
        </thead>
        <tbody>
          {[...exposed, ...pending].map((route) => (
            <tr key={`${route.method}-${route.route}`} data-exposed={route.exposedByAcceptedBackend ? 'true' : 'false'}>
              <td>{route.method}</td>
              <td>
                <code>{route.route}</code>
              </td>
              <td>{route.exposedByAcceptedBackend ? 'yes' : 'no'}</td>
              <td>{route.note}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  )
}