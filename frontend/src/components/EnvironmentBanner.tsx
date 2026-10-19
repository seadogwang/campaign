import type { ReactElement } from 'react'
import type { EnvironmentEvidence } from '../contracts/readiness'
import { readinessClaimAllowed } from '../contracts/readiness'

/**
 * The permanent environment marker.
 *
 * It is rendered in every workspace mode, and it always states in words what the
 * environment is. In fixture mode it says plainly that fixture identity, data and
 * providers are not production evidence and cannot satisfy a readiness level.
 *
 * `detailed` adds the identity/data/provider sources and the evidence facts. The
 * user-facing application renders the compact form; the internal engineering mode
 * renders the detailed one. Neither form ever allows a readiness claim that the
 * evidence does not support.
 */
export function EnvironmentBanner({
  evidence,
  detailed = false,
}: {
  readonly evidence: EnvironmentEvidence
  readonly detailed?: boolean
}): ReactElement {
  const claimAllowed = readinessClaimAllowed(evidence)
  return (
    <div
      className="environment-banner"
      data-mode={evidence.mode}
      data-simulated={evidence.simulated ? 'true' : 'false'}
      role="note"
      aria-label="Environment and evidence marker"
    >
      <span className="environment-banner__tag">
        {evidence.simulated ? 'SIMULATED' : 'HTTP'}
      </span>
      <span className="environment-banner__mode">{evidence.simulated ? 'Fixture mode' : 'Connected'}</span>
      <span className="environment-banner__statement">{evidence.statement}</span>
      {detailed ? (
        <dl className="environment-banner__sources">
          <div className="sr-only">
            <dt>Identity</dt>
            <dd>{evidence.identitySource}</dd>
          </div>
          <div className="sr-only">
            <dt>Data</dt>
            <dd>{evidence.dataSource}</dd>
          </div>
          <div className="sr-only">
            <dt>Providers</dt>
            <dd>{evidence.providerSource}</dd>
          </div>
          <div>
            <dt>Production Gate evidence</dt>
            <dd>{String(evidence.productionGateEvidence)}</dd>
          </div>
          <div>
            <dt>Readiness claim permitted</dt>
            <dd>{String(claimAllowed)}</dd>
          </div>
        </dl>
      ) : null}
    </div>
  )
}
