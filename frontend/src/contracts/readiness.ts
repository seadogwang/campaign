/**
 * Environment and readiness evidence.
 *
 * T24 proves the UI/contract boundary only. The fixture environment must stay
 * visibly marked, and no fixture identity, data or provider may be presented as
 * production evidence. `productionGateEvidence` is always `false` here: a
 * development asset can never satisfy `LOCAL_READY`, `AI_READY` or `LIVE_READY`.
 */

import { UI_ERROR_CODES, contractError } from './errors'

export const ENVIRONMENT_MODES = ['FIXTURE', 'HTTP'] as const
export type EnvironmentMode = (typeof ENVIRONMENT_MODES)[number]

export const READINESS_BADGES = ['LOCAL_READY', 'AI_READY', 'LIVE_READY'] as const
export type ReadinessBadge = (typeof READINESS_BADGES)[number]

export interface EnvironmentEvidence {
  readonly mode: EnvironmentMode
  readonly simulated: boolean
  readonly identitySource: string
  readonly dataSource: string
  readonly providerSource: string
  /** Always false in T24: no T24 asset is production Gate evidence. */
  readonly productionGateEvidence: false
  readonly statement: string
}

export interface CapabilityRoute {
  readonly method: 'GET' | 'POST'
  readonly route: string
  readonly exposedByAcceptedBackend: boolean
  readonly note: string
}

/**
 * The documented `/api/v1` surface from the Implementation Specification and
 * what the accepted backend actually exposes today. The UI must not invent
 * successful production wiring for a route that is not exposed.
 */
export const DOCUMENTED_ROUTES: readonly CapabilityRoute[] = [
  { method: 'POST', route: '/api/v1/campaign-plans', exposedByAcceptedBackend: true, note: 'live local HTTP plan-compile adapter using CampaignCompiler + development catalog/policy' },
  { method: 'GET', route: '/api/v1/campaign-plans/{planId}', exposedByAcceptedBackend: false, note: 'documented; no host adapter in the accepted build' },
  { method: 'POST', route: '/api/v1/campaign-plans/{planId}/preview', exposedByAcceptedBackend: false, note: 'documented; the accepted preview slice is POST /ai/segments/preview' },
  { method: 'POST', route: '/api/v1/campaign-plans/{planId}/simulate', exposedByAcceptedBackend: false, note: 'documented; no host adapter in the accepted build' },
  { method: 'POST', route: '/api/v1/campaign-plans/{planId}/apply', exposedByAcceptedBackend: false, note: 'documented; ApplyService is host-only and not HTTP reachable' },
  { method: 'GET', route: '/api/v1/campaigns/{id}/draft', exposedByAcceptedBackend: false, note: 'documented; no host adapter in the accepted build' },
  { method: 'POST', route: '/api/v1/campaigns/{id}/publish-preparations', exposedByAcceptedBackend: false, note: 'documented; PublishPreparationService is host-only' },
  { method: 'POST', route: '/api/v1/publish-preparations/{id}/publish', exposedByAcceptedBackend: false, note: 'documented; PublishService is host-only' },
  { method: 'POST', route: '/api/v1/campaigns/{id}/pause', exposedByAcceptedBackend: false, note: 'documented; no host adapter in the accepted build' },
  { method: 'POST', route: '/api/v1/campaigns/{id}/resume', exposedByAcceptedBackend: false, note: 'documented; no host adapter in the accepted build' },
  { method: 'GET', route: '/api/v1/campaigns/{id}/executions', exposedByAcceptedBackend: false, note: 'documented; no host adapter in the accepted build' },
  { method: 'GET', route: '/api/v1/executions/{id}/participants', exposedByAcceptedBackend: false, note: 'documented; no host adapter in the accepted build' },
  { method: 'GET', route: '/api/v1/executions/{id}/actions', exposedByAcceptedBackend: false, note: 'documented; no host adapter in the accepted build' },
  { method: 'POST', route: '/api/v1/actions/{id}/reconcile', exposedByAcceptedBackend: false, note: 'documented; no host adapter in the accepted build' },
  { method: 'POST', route: '/api/v1/actions/{id}/retry', exposedByAcceptedBackend: false, note: 'documented; no host adapter in the accepted build' },
  { method: 'POST', route: '/ai/segments/preview', exposedByAcceptedBackend: true, note: 'the only currently exposed read-only HTTP slice' },
]

export function fixtureEnvironmentEvidence(): EnvironmentEvidence {
  return {
    mode: 'FIXTURE',
    simulated: true,
    identitySource: 'development fixture identity',
    dataSource: 'development fixture data',
    providerSource: 'development fixture provider (no Provider is called)',
    productionGateEvidence: false,
    statement:
      'SIMULATED development environment. Fixture identity, data and providers are not production evidence and cannot satisfy LOCAL_READY, AI_READY or LIVE_READY.',
  }
}

export function httpEnvironmentEvidence(baseUrl: string): EnvironmentEvidence {
  return {
    mode: 'HTTP',
    simulated: false,
    identitySource: `host-supplied trusted identity via ${baseUrl}`,
    dataSource: `host-supplied projections via ${baseUrl}`,
    providerSource: 'host-supplied; the browser never observes a Provider directly',
    productionGateEvidence: false,
    statement:
      'HTTP mode. Production OIDC, API persistence and browser/API end-to-end evidence are deferred to T25; this build makes no readiness claim.',
  }
}

/**
 * T24 evidence never satisfies a readiness badge. The flag is `false` by
 * construction in every T24 environment, whichever mode is active.
 */
export function readinessClaimAllowed(evidence: EnvironmentEvidence): boolean {
  return evidence.productionGateEvidence
}

export function describeRoute(route: string): CapabilityRoute {
  const found = DOCUMENTED_ROUTES.find((candidate) => candidate.route === route)
  if (found === undefined) {
    throw contractError(UI_ERROR_CODES.GATEWAY_ROUTE_NOT_EXPOSED, '$', 'unknown-route')
  }
  return found
}