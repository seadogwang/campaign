/**
 * The one gateway.
 *
 * Every screen reaches the backend through exactly this interface. Two
 * implementations exist: a documented `/api/v1` HTTP gateway and a
 * deterministic development fixture gateway. No screen talks to `fetch`, to a
 * fixture store or to a server contract directly.
 */

import type { CampaignPlanSummary, PlanRef, ReadinessState } from '../contracts/plan'
import type {
  CampaignTurnRequest,
  ConversationProjection,
} from '../contracts/conversation'
import type { ContentHash } from '../contracts/primitives'
import type { CampaignPermission } from '../contracts/permissions'
import type { SegmentPreviewSummary } from '../contracts/preview'
import type { CampaignProposalPatch } from '../contracts/proposal'
import type {
  ApplyCommand,
  ApplyResult,
  PreparePublishCommand,
  PublishCommand,
  PublishPreparation,
  PublishResult,
} from '../contracts/publish'
import type { EnvironmentEvidence, EnvironmentMode } from '../contracts/readiness'
import type { SimulationSummary } from '../contracts/simulation'
import type {
  ActionOperationResult,
  ActionSummary,
  ExecutionSummary,
  ParticipantSummary,
  ScheduleStateResult,
} from '../contracts/operations'
import type { CapabilityReadiness, CampaignSummary, DraftSummary, SummaryCards } from '../contracts/summaries'

export interface GatewayCallOptions {
  /** Caller-owned abort signal. An aborted call rejects with `GATEWAY_ABORTED`. */
  readonly signal?: AbortSignal
  /** Required on mutations. Never logged and never rendered. */
  readonly idempotencyKey?: string
}

/** The frozen catalog/policy pins every EDIT Proposal is written against. */
export interface WorkspacePins {
  readonly catalogVersion: string
  readonly catalogHash: ContentHash
  readonly policyVersion: string
  readonly policyHash: ContentHash
}

export interface WorkspaceSnapshot {
  readonly environment: EnvironmentEvidence
  readonly campaign: CampaignSummary
  readonly draft: DraftSummary | null
  readonly lastPlan: CampaignPlanSummary | null
  readonly summaries: SummaryCards
  readonly permissions: ReadonlySet<CampaignPermission>
  readonly readiness: readonly CapabilityReadiness[]
  readonly pins: WorkspacePins
}

/** The development states the fixture gateway can be asked to reproduce. */
export const FIXTURE_SCENARIOS = [
  'READY',
  'INVALID',
  'UNKNOWN',
  'NEEDS_REVIEW',
  'STALE',
  'CONFLICT',
  'PERMISSION_DENIED',
] as const
export type FixtureScenario = (typeof FIXTURE_SCENARIOS)[number]

export interface CampaignWorkspaceGateway {
  readonly mode: EnvironmentMode
  readonly environment: EnvironmentEvidence
  /**
   * The host-supplied "now" used for expiry comparisons. The fixture gateway
   * returns a frozen instant so the UI stays deterministic; the HTTP gateway
   * returns the real clock. Expiry is still re-proved by the server.
   */
  readonly nowInstant: string
  /**
   * Resolves the conversation identity every turn of this workspace belongs to.
   *
   * The fixture gateway resolves its fixed development identity immediately; the
   * HTTP gateway bootstraps once from `POST /api/v1/campaign-agent/conversations`
   * and keeps the server-issued identity in memory for the current page session
   * only — never in `localStorage`, a cookie or a client-generated UUID. All
   * concurrent callers share the one in-flight bootstrap, so a burst of sends can
   * never create more than one conversation, and a failed bootstrap stays
   * retryable rather than being cached as a permanent failure.
   *
   * A turn is a bounded user message plus typed clarification answers; the
   * response is the host's bounded conversation projection. The UI never parses
   * the message and never builds a Proposal from assistant prose.
   */
  conversationIdentity(): Promise<CampaignConversationIdentity>
  /**
   * Starts a fresh server-owned conversation for the explicit "New Campaign" action.
   *
   * It issues a new identity from `POST /api/v1/campaign-agent/conversations` and replaces the
   * page-session identity. The fixture gateway starts a fresh fixture run. Nothing is persisted
   * in browser storage in either mode.
   */
  startNewConversation(options: GatewayCallOptions): Promise<CampaignConversationIdentity>
  /**
   * Lists the caller's own server-owned conversations, most recently updated first.
   *
   * The list comes only from the host: no browser storage and no client-side transcript are read
   * or written. A host that cannot serve history answers with a bounded error.
   */
  listConversations(options: GatewayCallOptions): Promise<readonly ConversationSummary[]>
  /**
   * Loads one stored server-owned conversation projection by its bounded identity and adopts it as
   * the active page-session conversation.
   *
   * The returned projection is the same shape a turn returns, so selecting history renders the
   * real transcript and canvas rather than a client-side copy. After a successful load every later
   * send uses this identity, exactly as if it had been bootstrapped on the first send.
   */
  readConversation(
    conversationId: string,
    runId: string,
    options: GatewayCallOptions,
  ): Promise<ConversationProjection>
  getWorkspace(options: GatewayCallOptions): Promise<WorkspaceSnapshot>
  sendCampaignTurn(
    request: CampaignTurnRequest,
    options: GatewayCallOptions,
  ): Promise<ConversationProjection>
  compileProposal(patch: CampaignProposalPatch, options: GatewayCallOptions): Promise<CampaignPlanSummary>
  previewSegment(planRef: PlanRef, options: GatewayCallOptions): Promise<SegmentPreviewSummary>
  simulate(planRef: PlanRef, options: GatewayCallOptions): Promise<SimulationSummary>
  applyPlan(command: ApplyCommand, options: GatewayCallOptions): Promise<ApplyResult>
  preparePublish(command: PreparePublishCommand, options: GatewayCallOptions): Promise<PublishPreparation>
  publish(command: PublishCommand, options: GatewayCallOptions): Promise<PublishResult>
  listExecutions(campaignId: string, options: GatewayCallOptions): Promise<readonly ExecutionSummary[]>
  listParticipants(executionId: string, options: GatewayCallOptions): Promise<readonly ParticipantSummary[]>
  listActions(executionId: string, options: GatewayCallOptions): Promise<readonly ActionSummary[]>
  reconcileAction(
    actionId: string,
    expectedRevision: number,
    options: GatewayCallOptions,
  ): Promise<ActionOperationResult>
  safeRetryAction(
    actionId: string,
    expectedRevision: number,
    options: GatewayCallOptions,
  ): Promise<ActionOperationResult>
  pauseCampaign(campaignId: string, expectedStateRevision: number, options: GatewayCallOptions): Promise<ScheduleStateResult>
  resumeCampaign(campaignId: string, expectedStateRevision: number, options: GatewayCallOptions): Promise<ScheduleStateResult>
}

/** The conversation and run identity of one workspace. */
export interface CampaignConversationIdentity {
  readonly conversationId: string
  readonly runId: string
}

/**
 * One bounded entry of the server-owned conversation history.
 *
 * It is a server fact list, not presentation: the title is the first user turn of the stored
 * conversation (or null when the conversation was reserved but has not accepted a turn), and
 * `hasSession` distinguishes a stored conversation from a bare reservation.
 */
export interface ConversationSummary {
  readonly conversationId: string
  readonly runId: string
  readonly revision: number
  readonly hasSession: boolean
  readonly title: string | null
  readonly turnCount: number
  readonly createdAt: string
  readonly updatedAt: string
}

/** A gateway that additionally exposes explicit, fixture-only controls. */
export interface FixtureGateway extends CampaignWorkspaceGateway {
  readonly mode: 'FIXTURE'
  readonly scenario: FixtureScenario
  setScenario(scenario: FixtureScenario): void
  /** Explicit, user-triggered reset. Never shares storage with HTTP mode. */
  resetFixture(): void
  /**
   * Starts a new conversation under a fresh run identity. Fixture-only: it
   * discards the fixture transcript and never touches saved state.
   */
  resetConversation(): void
  /** Grant or revoke a permission to demonstrate guarded controls. */
  setPermissions(permissions: readonly CampaignPermission[]): void
}

export function isFixtureGateway(gateway: CampaignWorkspaceGateway): gateway is FixtureGateway {
  return gateway.mode === 'FIXTURE'
}

export type { ReadinessState }
