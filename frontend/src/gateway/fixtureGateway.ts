/**
 * Deterministic development fixture gateway.
 *
 * It performs no network call, reads no clock and holds no real member,
 * contact or provider data. Every identifier is derived from a fixed seed, so
 * the same scenario always produces byte-identical output. Its environment
 * marker is permanent and states that fixture identity, data and providers are
 * not production evidence.
 */

import { UI_ERROR_CODES, UiError, fixedMessage, permissionDenied, refused } from '../contracts/errors'
import { contentHashOfCanonical, contentHashOfText } from '../contracts/canonical'
import {
  encodeCampaignProposalPatch,
  upsertOperation,
  JOURNEY_TOPOLOGY_FORMAT_VERSION,
  PROPOSAL_SCHEMA_VERSION,
  type CampaignProposalPatch,
  type JourneyTopologyMutation,
  type ProposalOperation,
} from '../contracts/proposal'
import { EMPTY_FLOW_PAYLOAD, flowEndPayload } from '../authoring/capabilities'
import type { ConversationJourneySummary } from '../contracts/conversation'
import type { CampaignPlanSummary, DiffEntry, PlanIssue, PlanPreviewCard, PlanRef } from '../contracts/plan'
import {
  COMPILER_VERSION,
  PLAN_FORMAT_VERSION,
  type ContentHash,
} from '../contracts/primitives'
import { projectSegmentPreview, type SegmentPreviewResponse } from '../contracts/preview'
import type { SegmentPreviewSummary } from '../contracts/preview'
import {
  effectivePreparationState,
  confirmationsMatch,
  type ApplyCommand,
  type ApplyResult,
  type PreparePublishCommand,
  type PublishCommand,
  type PublishPreparation,
  type PublishResult,
} from '../contracts/publish'
import { fixtureEnvironmentEvidence } from '../contracts/readiness'
import { decodeSimulationSummary, SIMULATED_ENVIRONMENT, type SimulationSummary } from '../contracts/simulation'
import type {
  ActionOperationResult,
  ActionSummary,
  ExecutionCountersSummary,
  ExecutionSummary,
  ParticipantSummary,
  ScheduleStateResult,
} from '../contracts/operations'
import { decodeParticipantSummary } from '../contracts/operations'
import type { JsonValue } from '../contracts/validate'
import type {
  CapabilityReadiness,
  CampaignSummary,
  DraftSummary,
  GovernanceSummary,
  JourneySummary,
  MessageSummary,
  PromotionSummary,
  ScheduleSummary,
  SummaryCards,
} from '../contracts/summaries'
import type { CampaignPermission } from '../contracts/permissions'
import {
  type CampaignTurnRequest,
  type CampaignComponentTodo,
  type CampaignRequirementProgress,
  type CampaignScheduleEvidence,
  type ClarificationOption,
  type ComponentProgress,
  type ConversationAudience,
  type ConversationClarification,
  type ConversationPhase,
  type ConversationProjection,
  type ConversationRouteKind,
  type ConversationTurn,
  type SkillProgress,
} from '../contracts/conversation'
import {
  governancePayload,
  messagePayload,
  promotionPayload,
  schedulePayload,
  segmentPayload,
} from '../authoring/capabilities'
import {
  type CampaignConversationIdentity,
  type ConversationSummary,
  type FixtureGateway,
  type FixtureScenario,
  type GatewayCallOptions,
  type WorkspaceSnapshot,
} from './types'

/** The one frozen instant every fixture timestamp is derived from. */
export const FIXTURE_NOW = '2026-10-08T09:00:00Z'
export const FIXTURE_PLAN_EXPIRY = '2026-10-08T09:15:00Z'
export const FIXTURE_PREPARATION_EXPIRY = '2026-10-08T09:15:00Z'
export const FIXTURE_MAX_DATA_AGE_SECONDS = 900

const FIXTURE_CAMPAIGN_ID = 'campaign-fixture-1'
const FIXTURE_DRAFT_ID = 'draft-fixture-1'

/** The one fixture conversation identity; a reset starts a fresh run under it. */
export const FIXTURE_CONVERSATION_ID = 'campaign-conversation-fixture-1'

/** The canonical Skill order the fixture reports progress in. */
const FIXTURE_SKILL_ORDER: readonly string[] = [
  'segment.rule-based',
  'message.template',
  'promotion.reference',
  'journey.flow',
  'schedule.basic',
  'governance.limit',
]

/**
 * The deterministic fixture conversation.
 *
 * It stands in for the host's conversation service and is deliberately not a
 * model: the route is chosen by a small, documented set of fixture phrases, so
 * the same transcript always produces byte-identical output. The UI never runs
 * this logic — it sends the user's own words to `sendCampaignTurn`.
 */
interface FixtureConversationState {
  runId: string
  revision: number
  turns: readonly ConversationTurn[]
  pending: ConversationClarification | null
  audience: ConversationAudience | null
  phase: ConversationPhase
  routeKind: ConversationRouteKind | null
  proposal: CampaignProposalPatch | null
  assistantMessage: string | null
  followUpPrompt: string | null
  /** What the simulated Skills have assembled so far. */
  segment: boolean
  message: boolean
  promotion: boolean
  schedule: boolean
  governance: boolean
  journey: boolean
}

const FIXTURE_AUDIENCE_FOLLOW_UP =
  'Audience ready. Add message, promotion, journey, schedule or governance requirements, '
  + 'or ask to finish the Campaign.'

const FIXTURE_BUILD_COPY =
  'I accepted the requirements you described. Add another requirement or ask to finish.'

const FIXTURE_FINALIZED_COPY =
  'The Campaign Proposal is ready. Create the Plan when you are ready.'

const FIXTURE_CLARIFICATION_OPTIONS: readonly ClarificationOption[] = [
  { optionId: 'option-audience-active', label: 'Members active in the last 30 days' },
  { optionId: 'option-audience-lapsed', label: 'Members with no recent purchase' },
]

const FIXTURE_CLARIFICATION: ConversationClarification = {
  skillId: null,
  code: 'CAMPAIGN_GOAL_AMBIGUOUS',
  prompt: 'Which audience should the Campaign use?',
  questionCodes: ['CAMPAIGN_GOAL_AMBIGUOUS'],
  options: FIXTURE_CLARIFICATION_OPTIONS,
  freeTextAllowed: true,
}

const FIXTURE_AUDIENCE: ConversationAudience = {
  status: 'READY',
  matchedCount: 1284,
  asOf: FIXTURE_NOW,
  timezone: 'Asia/Shanghai',
  dataWatermark: FIXTURE_NOW,
  previewHash: fixtureHash('conversation-audience-1'),
}

const FIXTURE_MISSING_GOVERNANCE_LIMIT = 'CAMPAIGN_ASSEMBLY_MISSING_GOVERNANCE_LIMIT'

/** The one schedule evidence the fixture confirms when a Schedule is staged; it mirrors the Schedule operation. */
const FIXTURE_SCHEDULE_EVIDENCE: CampaignScheduleEvidence = {
  timeZone: 'Asia/Shanghai',
  runAt: null,
  localTime: '09:00',
  startDate: '2026-11-01',
  endDate: '2026-11-30',
}

const DEFAULT_PERMISSIONS: readonly CampaignPermission[] = [
  'campaign.read',
  'campaign.edit',
  'audience.preview',
  'campaign.publish',
  'campaign.operate',
]

export function fixtureHash(seed: string): ContentHash {
  return contentHashOfText(`campaign-builder-fixture/${seed}`)
}

/**
 * The one canonical minimal Journey topology the fixture stages: a single DEFAULT edge from the declared
 * Start node to the declared End node. No edge is ever embedded in a Flow node payload.
 */
function fixtureJourneyTopology(): JourneyTopologyMutation {
  return {
    action: 'REPLACE',
    topologyFormatVersion: JOURNEY_TOPOLOGY_FORMAT_VERSION,
    edges: [
      {
        edgeId: 'edge-start-end',
        from: { logicalId: null, clientRef: 'journey-start' },
        to: { logicalId: null, clientRef: 'journey-end' },
        branch: 'DEFAULT',
      },
    ],
  }
}

/** The bounded, non-secret summary of the staged Journey bundle. */
function fixtureJourneySummary(): ConversationJourneySummary {
  return {
    nodeCount: 2,
    edgeCount: 1,
    entryComponentId: 'journey-start',
    terminalCount: 1,
    topologyHash: fixtureHash('conversation-journey-topology'),
    artifactHash: fixtureHash('conversation-component-journey-start'),
  }
}

function checkAborted(options: GatewayCallOptions): void {
  if (options.signal?.aborted === true) {
    throw new UiError({
      code: UI_ERROR_CODES.GATEWAY_ABORTED,
      message: fixedMessage(UI_ERROR_CODES.GATEWAY_ABORTED),
    })
  }
}

interface MutableState {
  scenario: FixtureScenario
  permissions: ReadonlySet<CampaignPermission>
  plan: CampaignPlanSummary | null
  draft: DraftSummary | null
  preparation: PublishPreparation | null
  published: PublishResult | null
  publishRequestHash: string | null
  scheduleStateRevision: number
  scheduleStatus: string
  /** Operator results are durable, so a reload keeps the reconciled status. */
  actionOverrides: ReadonlyMap<string, { readonly status: ActionSummary['status']; readonly revision: number }>
  conversation: FixtureConversationState
}

export class FixtureCampaignWorkspaceGateway implements FixtureGateway {
  readonly mode = 'FIXTURE' as const
  readonly environment = fixtureEnvironmentEvidence()
  readonly nowInstant = FIXTURE_NOW
  private state: MutableState = FixtureCampaignWorkspaceGateway.initialState()
  private conversationRunCounter = 1

  private static initialState(): MutableState {
    return {
      scenario: 'READY',
      permissions: new Set(DEFAULT_PERMISSIONS),
      plan: null,
      draft: FixtureCampaignWorkspaceGateway.initialDraft(),
      preparation: null,
      published: null,
      publishRequestHash: null,
      scheduleStateRevision: 1,
      scheduleStatus: 'ACTIVE',
      actionOverrides: new Map(),
      conversation: FixtureCampaignWorkspaceGateway.initialConversation('campaign-run-fixture-1'),
    }
  }

  private static initialConversation(runId: string): FixtureConversationState {
    return {
      runId,
      revision: 0,
      turns: [],
      pending: null,
      audience: null,
      phase: 'AWAITING_GOAL',
      routeKind: null,
      proposal: null,
      assistantMessage: null,
      followUpPrompt: null,
      segment: false,
      message: false,
      promotion: false,
      schedule: false,
      governance: false,
      journey: false,
    }
  }

  private static initialDraft(): DraftSummary {
    return {
      draftId: FIXTURE_DRAFT_ID,
      draftRevision: 2,
      campaignRevision: 3,
      manifestHash: fixtureHash('manifest-1'),
      savedAt: FIXTURE_NOW,
    }
  }

  get scenario(): FixtureScenario {
    return this.state.scenario
  }

  setScenario(scenario: FixtureScenario): void {
    const base = FixtureCampaignWorkspaceGateway.initialState()
    this.state = {
      ...base,
      scenario,
      // The PERMISSION_DENIED scenario really denies: only campaign.read remains.
      permissions:
        scenario === 'PERMISSION_DENIED'
          ? new Set<CampaignPermission>(['campaign.read'])
          : base.permissions,
    }
  }

  setPermissions(permissions: readonly CampaignPermission[]): void {
    this.state = { ...this.state, permissions: new Set(permissions) }
  }

  resetFixture(): void {
    this.state = FixtureCampaignWorkspaceGateway.initialState()
  }

  async conversationIdentity(): Promise<CampaignConversationIdentity> {
    return { conversationId: FIXTURE_CONVERSATION_ID, runId: this.state.conversation.runId }
  }

  /**
   * Starts a new conversation under a fresh run identity.
   *
   * Fixture-only and explicit: it discards the fixture transcript and never
   * touches the saved Draft, the last Plan or any local Visual Builder edit.
   */
  resetConversation(): void {
    this.conversationRunCounter += 1
    this.state = {
      ...this.state,
      conversation: FixtureCampaignWorkspaceGateway.initialConversation(
        `campaign-run-fixture-${this.conversationRunCounter}`,
      ),
    }
  }

  /**
   * Starts a fresh fixture conversation under a new run identity.
   *
   * It is the fixture stand-in for the server-owned create route: it discards the fixture transcript
   * and returns the new identity, exactly as `POST /api/v1/campaign-agent/conversations` would.
   */
  async startNewConversation(options: GatewayCallOptions): Promise<CampaignConversationIdentity> {
    checkAborted(options)
    this.resetConversation()
    return { conversationId: FIXTURE_CONVERSATION_ID, runId: this.state.conversation.runId }
  }

  /**
   * Lists the one deterministic fixture conversation.
   *
   * The fixture is a single in-memory conversation, so the history is one honest entry: a reserved
   * conversation with no accepted turn reports `hasSession=false` and a null title, exactly as the
   * durable host does.
   */
  async listConversations(options: GatewayCallOptions): Promise<readonly ConversationSummary[]> {
    checkAborted(options)
    this.require('campaign.edit')
    return [this.summary()]
  }

  /**
   * Reads the fixture conversation projection, or refuses an unknown identity.
   *
   * A mismatched identity is the same bounded refusal `sendCampaignTurn` returns for an unknown
   * conversation, so the fixture never invents a second conversation.
   */
  async readConversation(
    conversationId: string,
    runId: string,
    options: GatewayCallOptions,
  ): Promise<ConversationProjection> {
    checkAborted(options)
    this.require('campaign.edit')
    if (conversationId !== FIXTURE_CONVERSATION_ID || runId !== this.state.conversation.runId) {
      throw refused('conversation-unknown')
    }
    return this.projection(true, null)
  }

  /** The bounded history entry of the current fixture conversation. */
  private summary(): ConversationSummary {
    const current = this.state.conversation
    const firstUser = current.turns.find((turn) => turn.role === 'USER') ?? null
    return {
      conversationId: FIXTURE_CONVERSATION_ID,
      runId: current.runId,
      revision: current.revision,
      hasSession: current.turns.length > 0,
      title: firstUser === null ? null : firstUser.text,
      turnCount: current.turns.length,
      createdAt: FIXTURE_NOW,
      updatedAt: FIXTURE_NOW,
    }
  }

  /**
   * Simulates one bounded conversation turn.
   *
   * The route is chosen by a small documented set of fixture phrases rather than
   * a model, so the same transcript always produces byte-identical output. It is
   * a fixture stand-in for the host conversation service, never logic the UI
   * runs: the UI sends the user's own words and renders the returned projection.
   */
  async sendCampaignTurn(
    request: CampaignTurnRequest,
    options: GatewayCallOptions,
  ): Promise<ConversationProjection> {
    checkAborted(options)
    this.require('campaign.edit')
    const current = this.state.conversation
    if (
      request.conversationId !== FIXTURE_CONVERSATION_ID
      || request.runId !== current.runId
    ) {
      throw refused('conversation-unknown')
    }
    if (request.expectedRevision !== current.revision) {
      return this.projection(false, 'CAMPAIGN_AGENT_STALE_REVISION')
    }
    if (current.pending !== null && request.answers.length > 0) {
      const offered = new Set(current.pending.options.map((option) => option.optionId))
      const unoffered = request.answers.some(
        (answer) => answer.optionId !== null && !offered.has(answer.optionId),
      )
      if (unoffered) {
        return this.projection(false, 'CAMPAIGN_AGENT_CLARIFICATION_MISMATCH')
      }
    }
    return this.simulateTurn(request)
  }

  private simulateTurn(request: CampaignTurnRequest): ConversationProjection {
    const current = this.state.conversation
    const text = request.message.toLowerCase()
    const first = current.revision === 0

    let routeKind: ConversationRouteKind
    let selected: readonly string[]
    let pending = current.pending
    if (first) {
      pending = null
      if (/audience|members|who|people/.test(text)) {
        routeKind = 'AUDIENCE_REQUEST'
        selected = ['segment.rule-based']
      } else if (/clarify|which|unsure/.test(text)) {
        routeKind = 'NEEDS_CLARIFICATION'
        selected = []
        pending = FIXTURE_CLARIFICATION
      } else if (/weather|unrelated|nothing/.test(text)) {
        routeKind = 'OUT_OF_SCOPE'
        selected = []
      } else if (/schedule|timing|recurring/.test(text)) {
        // A clear Schedule-only first turn stages its own Skill, exactly as the continuation route does:
        // it never falls through to the default Segment + Message pair. Audience, clarification and
        // out-of-scope keep their precedence above it.
        routeKind = 'CAMPAIGN_REQUEST'
        selected = ['schedule.basic']
      } else if (/governance|limits|guardrail/.test(text)) {
        // A clear Governance-only first turn stages its own Skill for the same reason.
        routeKind = 'CAMPAIGN_REQUEST'
        selected = ['governance.limit']
      } else if (/journey/.test(text)) {
        // A clear Journey-only first turn stages its canonical minimal bundle for the same reason.
        routeKind = 'CAMPAIGN_REQUEST'
        selected = ['journey.flow']
      } else {
        routeKind = 'CAMPAIGN_REQUEST'
        selected = ['segment.rule-based', 'message.template']
      }
    } else if (current.pending !== null && request.answers.length > 0) {
      routeKind = 'CONTINUE_CAMPAIGN'
      selected = ['segment.rule-based']
      pending = null
    } else if (/finish|plan|done|ready/.test(text)) {
      routeKind = 'FINALIZE_CAMPAIGN'
      selected = []
    } else if (/promotion|reward|points/.test(text)) {
      routeKind = 'CONTINUE_CAMPAIGN'
      selected = ['promotion.reference']
    } else if (/journey/.test(text)) {
      // A clear Journey request stages the canonical minimal Start-to-End bundle and never contaminates
      // Schedule or Governance, which keep their own canonical components.
      routeKind = 'CONTINUE_CAMPAIGN'
      selected = ['journey.flow']
    } else if (/schedule|timing|recurring/.test(text)) {
      routeKind = 'CONTINUE_CAMPAIGN'
      selected = ['schedule.basic']
    } else if (/governance|limits|guardrail/.test(text)) {
      routeKind = 'CONTINUE_CAMPAIGN'
      selected = ['governance.limit']
    } else {
      routeKind = 'CONTINUE_CAMPAIGN'
      selected = ['message.template']
    }

    let segment = current.segment
    let message = current.message
    let promotion = current.promotion
    let schedule = current.schedule
    let governance = current.governance
    let journey = current.journey
    let audience = current.audience
    for (const skill of selected) {
      if (skill === 'segment.rule-based') {
        segment = true
        audience = FIXTURE_AUDIENCE
      } else if (skill === 'message.template') {
        message = true
      } else if (skill === 'promotion.reference') {
        promotion = true
      } else if (skill === 'schedule.basic') {
        schedule = true
      } else if (skill === 'governance.limit') {
        governance = true
      } else if (skill === 'journey.flow') {
        journey = true
      }
    }

    const staged = [segment, message, promotion, journey, schedule, governance].filter(Boolean).length
    if (
      routeKind === 'FINALIZE_CAMPAIGN'
      // A Promotion without its Governance limit is the one real dependency the assembly reports, and it
      // refuses finalization; it is never special-cased in the UI.
      && (staged === 0 || (promotion && !governance))
    ) {
      return this.projection(false, 'CAMPAIGN_AGENT_FINALIZE_NOT_READY')
    }
    let phase: ConversationPhase
    let assistantMessage: string
    let followUpPrompt: string | null = null
    let proposal = current.proposal
    if (pending !== null) {
      phase = 'AWAITING_CLARIFICATION'
      assistantMessage = pending.prompt
    } else if (routeKind === 'OUT_OF_SCOPE') {
      phase = 'AWAITING_GOAL'
      assistantMessage =
        'This builder cannot act on that request. Describe the Campaign or its audience instead.'
    } else if (routeKind === 'FINALIZE_CAMPAIGN') {
      proposal = this.conversationProposal(segment, message, promotion, journey, schedule, governance)
      phase = 'PROPOSAL_READY'
      assistantMessage = FIXTURE_FINALIZED_COPY
    } else if (staged === 1 && segment && routeKind !== 'CONTINUE_CAMPAIGN') {
      phase = 'AUDIENCE_READY'
      followUpPrompt = FIXTURE_AUDIENCE_FOLLOW_UP
      assistantMessage = FIXTURE_AUDIENCE_FOLLOW_UP
    } else if (staged > 0) {
      phase = 'READY_TO_FINALIZE'
      assistantMessage = FIXTURE_BUILD_COPY
    } else {
      phase = 'AWAITING_GOAL'
      assistantMessage = 'I could not turn that into a Campaign requirement yet. Describe the goal again.'
    }

    const at = request.at ?? FIXTURE_NOW
    const turns: readonly ConversationTurn[] = [
      ...current.turns,
      {
        turnId: `turn-${current.turns.length + 1}`,
        role: 'USER',
        ordinal: current.turns.length,
        text: request.message,
        at,
      },
      {
        turnId: `turn-${current.turns.length + 2}`,
        role: 'ASSISTANT',
        ordinal: current.turns.length + 1,
        text: assistantMessage,
        at,
      },
    ]

    this.state = {
      ...this.state,
      conversation: {
        runId: current.runId,
        revision: current.revision + 1,
        turns,
        pending,
        audience,
        phase,
        routeKind,
        proposal,
        assistantMessage,
        followUpPrompt,
        segment,
        message,
        promotion,
        schedule,
        governance,
        journey,
      },
    }
    return this.projection(true, null)
  }

  /** The one Proposal the fixture host emits, built only from what the Skills assembled. */
  private conversationProposal(
    segment: boolean,
    message: boolean,
    promotion: boolean,
    journey: boolean,
    schedule: boolean,
    governance: boolean,
  ): CampaignProposalPatch {
    const pins = this.pins()
    const operations: ProposalOperation[] = []
    if (segment) {
      operations.push(
        upsertOperation(
          'segment.rule-based',
          null,
          'campaign-segment',
          segmentPayload('{"schemaVersion":"1.3"}'),
        ),
      )
    }
    if (message) {
      operations.push(
        upsertOperation(
          'message.template',
          null,
          'campaign-message',
          messagePayload({ referenceCode: 'tpl-welcome', channel: 'EMAIL', variables: [] }),
        ),
      )
    }
    if (promotion) {
      operations.push(
        upsertOperation(
          'promotion.reference',
          null,
          'campaign-promotion',
          promotionPayload({ referenceCode: 'rwd-points', quantity: 1 }),
        ),
      )
    }
    if (schedule) {
      operations.push(
        upsertOperation(
          'schedule.basic',
          null,
          'campaign-schedule',
          schedulePayload({
            form: 'DAILY',
            zoneId: 'Asia/Shanghai',
            runAt: null,
            localTime: '09:00',
            startDate: '2026-11-01',
            endDate: '2026-11-30',
          }),
        ),
      )
    }
    if (governance) {
      operations.push(
        upsertOperation(
          'governance.limit',
          null,
          'campaign-governance',
          governancePayload({
            maxRecipients: 1000,
            maxRewardGrants: 100,
            maxRewardFaceValue: '25.00',
            currency: 'CNY',
          }),
        ),
      )
    }
    if (journey) {
      // The Journey Skill stages real Flow node components plus one separate topology; no synthetic
      // capability named after the Skill is ever emitted.
      operations.push(
        upsertOperation('flow.start', null, 'journey-start', EMPTY_FLOW_PAYLOAD),
        upsertOperation('flow.end', null, 'journey-end', flowEndPayload('END')),
      )
    }
    return {
      schemaVersion: PROPOSAL_SCHEMA_VERSION,
      mode: 'EDIT',
      campaignId: FIXTURE_CAMPAIGN_ID,
      baseRevisions: {
        campaignRevision: 3,
        catalogVersion: pins.catalogVersion,
        catalogHash: pins.catalogHash,
        policyVersion: pins.policyVersion,
        policyHash: pins.policyHash,
      },
      operations,
      clientContext: { locale: null },
      journeyTopology: journey ? fixtureJourneyTopology() : null,
    }
  }

  private skillProgress(): readonly SkillProgress[] {
    const current = this.state.conversation
    return FIXTURE_SKILL_ORDER.map((skillId): SkillProgress => {
      if (skillId === 'journey.flow') {
        // A requested Journey stages its canonical minimal bundle, so its state is genuinely READY and it
        // leaves Schedule and Governance entirely alone.
        return current.journey
          ? { skillId, state: 'READY', code: null, missingDependencyCodes: [] }
          : { skillId, state: 'MISSING', code: null, missingDependencyCodes: [] }
      }
      const staged =
        skillId === 'segment.rule-based'
          ? current.segment
          : skillId === 'message.template'
            ? current.message
            : skillId === 'promotion.reference'
              ? current.promotion
              : skillId === 'schedule.basic'
                ? current.schedule
                : current.governance
      if (!staged) {
        return { skillId, state: 'MISSING', code: null, missingDependencyCodes: [] }
      }
      return {
        skillId,
        state: 'READY',
        code: null,
        missingDependencyCodes:
          // A staged Promotion needs the Governance limit; staging Governance clears the code truthfully.
          skillId === 'promotion.reference' && !current.governance
            ? [FIXTURE_MISSING_GOVERNANCE_LIMIT]
            : [],
      }
    })
  }

  /**
   * The bounded component projection of the fixture assembly.
   *
   * It is sourced from exactly the accepted components the fixture has staged, in
   * the server's canonical identity order (component identity, then operation
   * kind). A Skill that is only `MISSING`, `NEEDS_INPUT` or `INVALID` contributes
   * no component: a non-ready observation never fabricates one.
   */
  private componentProgress(): readonly ComponentProgress[] {
    const current = this.state.conversation
    const staged: readonly {
      readonly componentId: string
      readonly skillId: string
      readonly capabilityCode: string
      readonly present: boolean
    }[] = [
      { componentId: 'campaign-governance', skillId: 'governance.limit', capabilityCode: 'governance.limit', present: current.governance },
      { componentId: 'campaign-message', skillId: 'message.template', capabilityCode: 'message.template', present: current.message },
      { componentId: 'campaign-promotion', skillId: 'promotion.reference', capabilityCode: 'promotion.reference', present: current.promotion },
      { componentId: 'campaign-schedule', skillId: 'schedule.basic', capabilityCode: 'schedule.basic', present: current.schedule },
      { componentId: 'campaign-segment', skillId: 'segment.rule-based', capabilityCode: 'segment.rule-based', present: current.segment },
      { componentId: 'journey-end', skillId: 'journey.flow', capabilityCode: 'flow.end', present: current.journey },
      { componentId: 'journey-start', skillId: 'journey.flow', capabilityCode: 'flow.start', present: current.journey },
    ]
    return staged
      .filter((entry) => entry.present)
      .map((entry): ComponentProgress => ({
        componentId: entry.componentId,
        skillId: entry.skillId,
        capabilityCode: entry.capabilityCode,
        operationKind: 'UPSERT',
        artifactHash: fixtureHash(`conversation-component-${entry.componentId}`),
      }))
  }

  /**
   * The bounded component TODO view of the fixture assembly.
   *
   * It mirrors the host-derived `requirements.componentTodo`: one TODO per closed component group in
   * the server-owned canonical order, closed against exactly the Skills the fixture has staged. It
   * is a fixture stand-in for the host's derivation, never logic the UI runs.
   */
  private componentTodo(): readonly CampaignComponentTodo[] {
    const current = this.state.conversation
    const blockedByGovernance = current.promotion && !current.governance
    return [
      current.segment
        ? { component: 'SEGMENT', status: 'COMPLETE', requirements: [], validationCodes: [] }
        : { component: 'SEGMENT', status: 'NOT_STARTED', requirements: ['AUDIENCE_REQUIRED'], validationCodes: [] },
      blockedByGovernance
        ? {
            component: 'OFFER',
            status: 'BLOCKED',
            requirements: [],
            validationCodes: [FIXTURE_MISSING_GOVERNANCE_LIMIT],
          }
        : current.promotion
          ? { component: 'OFFER', status: 'COMPLETE', requirements: [], validationCodes: [] }
          : {
              component: 'OFFER',
              status: 'NOT_STARTED',
              requirements: ['OFFER_DECISION_REQUIRED'],
              validationCodes: [],
            },
      current.message
        ? { component: 'COMMUNICATION', status: 'COMPLETE', requirements: [], validationCodes: [] }
        : {
            component: 'COMMUNICATION',
            status: 'NOT_STARTED',
            requirements: ['CHANNEL_REQUIRED', 'MESSAGE_REQUIRED'],
            validationCodes: [],
          },
      current.journey
        ? { component: 'JOURNEY', status: 'COMPLETE', requirements: [], validationCodes: [] }
        : {
            component: 'JOURNEY',
            status: 'NOT_STARTED',
            requirements: ['BRANCH_DECISION_REQUIRED'],
            validationCodes: [],
          },
      current.schedule
        ? { component: 'EXECUTION_SCHEDULE', status: 'COMPLETE', requirements: [], validationCodes: [] }
        : {
            component: 'EXECUTION_SCHEDULE',
            status: 'NOT_STARTED',
            requirements: ['CADENCE_REQUIRED', 'EXECUTION_TIME_REQUIRED'],
            validationCodes: [],
          },
    ]
  }

  /**
   * The bounded requirement progress of the fixture assembly.
   *
   * It carries the accumulated facts plus the host-derived component TODO view. The facts are
   * fixture-owned stand-ins and are never rendered as engineering detail by the product surface; the
   * user-facing progress card branches only on the component TODO and the confirmed schedule.
   */
  private requirementProgress(): CampaignRequirementProgress {
    const current = this.state.conversation
    const componentTodo = this.componentTodo()
    return {
      type: null,
      audience: current.segment ? 'Server-selected audience' : null,
      offerDecision: current.promotion ? 'DEFINED' : 'UNKNOWN',
      offerDetail: current.promotion ? 'Server-selected benefit' : null,
      channels: current.message ? ['EMAIL'] : [],
      message: current.message ? 'Server-provided message requirements' : null,
      branchDecision: current.journey ? 'NONE' : 'UNKNOWN',
      branchDetail: null,
      branchRule: null,
      cadence: current.schedule ? 'DAILY' : null,
      schedule: current.schedule ? FIXTURE_SCHEDULE_EVIDENCE : null,
      triggerEventCode: null,
      missing: componentTodo.flatMap((todo) => todo.requirements),
      complete: componentTodo.every((todo) => todo.status === 'COMPLETE'),
      unsupported: false,
      componentTodo,
      objective: { confirmed: false, evidenceLength: 0, acceptedAtRevision: -1 },
    }
  }

  private projection(accepted: boolean, code: string | null): ConversationProjection {
    const current = this.state.conversation
    return {
      accepted,
      code,
      conversationId: FIXTURE_CONVERSATION_ID,
      runId: current.runId,
      revision: current.revision,
      phase: current.phase,
      disposition: accepted ? 'CAMPAIGN_AGENT_ACCEPTED' : 'CAMPAIGN_AGENT_REFUSED',
      routeKind: current.routeKind,
      turns: current.turns,
      skills: this.skillProgress(),
      components: this.componentProgress(),
      clarification: current.pending,
      assistantMessage: current.assistantMessage,
      audience: current.audience,
      inspectionHash: fixtureHash(`conversation-inspection-${current.runId}-${current.revision}`),
      proposal: current.proposal,
      proposalHash:
        current.proposal === null
          ? null
          : contentHashOfCanonical(encodeCampaignProposalPatch(current.proposal)),
      followUpPrompt: current.followUpPrompt,
      journey: current.journey ? fixtureJourneySummary() : null,
      requirements: this.requirementProgress(),
    }
  }

  private require(permission: CampaignPermission): void {
    if (!this.state.permissions.has(permission)) {
      throw permissionDenied(permission)
    }
  }

  async getWorkspace(options: GatewayCallOptions): Promise<WorkspaceSnapshot> {
    checkAborted(options)
    this.require('campaign.read')
    return {
      environment: this.environment,
      campaign: this.campaign(),
      draft: this.state.draft,
      lastPlan: this.state.plan,
      summaries: this.summaries(),
      permissions: this.state.permissions,
      readiness: this.readiness(),
      pins: this.pins(),
    }
  }

  /** The frozen catalog/policy pins every fixture Proposal is written against. */
  private pins(): WorkspaceSnapshot['pins'] {
    return {
      catalogVersion: 'catalog-fixture-1',
      catalogHash: fixtureHash('catalog-1'),
      policyVersion: 'policy-fixture-1',
      policyHash: fixtureHash('policy-1'),
    }
  }

  async compileProposal(patch: CampaignProposalPatch, options: GatewayCallOptions): Promise<CampaignPlanSummary> {
    checkAborted(options)
    this.require('campaign.edit')
    if (this.state.scenario === 'STALE') {
      throw new UiError({
        code: UI_ERROR_CODES.GATEWAY_STALE_REVISION,
        message: fixedMessage(UI_ERROR_CODES.GATEWAY_STALE_REVISION),
        details: { serverCode: 'LIFECYCLE_DRAFT_STALE' },
      })
    }
    const planHash = contentHashOfCanonical(encodeCampaignProposalPatch(patch))
    const invalid = this.state.scenario === 'INVALID'
    const plan: CampaignPlanSummary = {
      planRef: {
        planId: planHash.slice('sha256:'.length),
        planHash,
        expiresAt: FIXTURE_PLAN_EXPIRY,
      },
      planFormatVersion: PLAN_FORMAT_VERSION,
      compilerVersion: COMPILER_VERSION,
      mode: patch.mode,
      status: invalid ? 'INVALID' : 'READY',
      compiledAt: FIXTURE_NOW,
      campaignId: patch.campaignId,
      baseDraftId: patch.mode === 'EDIT' ? FIXTURE_DRAFT_ID : null,
      baseDraftRevision: patch.mode === 'EDIT' ? 2 : null,
      proposalHash: contentHashOfCanonical(encodeCampaignProposalPatch(patch)),
      manifestHash: fixtureHash(`manifest-${planHash.slice(7, 19)}`),
      validationIssues: invalid ? this.invalidIssues() : [],
      semanticDiff: this.diffFor(patch),
      previewCards: [this.previewCard()],
      riskCodes: ['RUNTIME_UNAVAILABLE', 'SCHEDULE_WORK_DEFERRED'],
    }
    this.state = { ...this.state, plan }
    return plan
  }

  private invalidIssues(): readonly PlanIssue[] {
    return [
      {
        code: 'CAMPAIGN_PROPOSAL_SHAPE_INVALID',
        severity: 'ERROR',
        path: '$.operations[0].value',
        nodeId: null,
        message: 'A capability payload field had an unsupported shape.',
      },
      {
        code: 'CAMPAIGN_GOVERNANCE_LIMIT_FIELD_INVALID',
        severity: 'WARNING',
        path: '$.operations[3].value.maxRecipients',
        nodeId: 'governance-new',
        message: 'The recipient limit is below the recommended floor.',
      },
      {
        code: 'CAMPAIGN_SCHEDULE_BASIC_FIELD_INVALID',
        severity: 'INFO',
        path: null,
        nodeId: 'schedule-new',
        message: 'The schedule uses the campaign default timezone.',
      },
    ]
  }

  private diffFor(patch: CampaignProposalPatch): readonly DiffEntry[] {
    const entries: DiffEntry[] = []
    patch.operations.forEach((operation, index) => {
      const logicalId = operation.logicalId ?? operation.clientRef ?? `component-${index}`
      const capabilityCode = operation.capability ?? 'campaign.flow'
      switch (operation.op) {
        case 'UPSERT':
          entries.push({ kind: 'ADD', logicalId, capabilityCode, fieldPaths: ['component', 'value'] })
          break
        case 'DELETE':
          entries.push({ kind: 'DELETE', logicalId, capabilityCode, fieldPaths: ['component'] })
          break
        case 'MOVE':
          entries.push({ kind: 'MOVE', logicalId, capabilityCode, fieldPaths: ['order'] })
          break
        default:
          break
      }
    })
    return entries
  }

  private previewCard(): PlanPreviewCard {
    return {
      cardKind: 'SEGMENT_PREVIEW',
      componentLogicalId: 'segment-new',
      matchedCount: 1284,
      conditionStatistics: [
        { path: 'r', trueCount: 1284, falseCount: 716, unknownCount: 12 },
        { path: 'r.0', trueCount: 1284, falseCount: 0, unknownCount: 0 },
      ],
      sampleTokens: [],
      metadataSnapshotId: 'metadata-fixture-1',
      asOf: FIXTURE_NOW,
      timezone: 'Asia/Shanghai',
      dataWatermark: '2026-10-08T08:55:00Z',
      readinessState: 'EXPERIMENTAL',
      approvedDecisionHash: null,
      referenceEvidence: null,
    }
  }

  async previewSegment(planRef: PlanRef, options: GatewayCallOptions): Promise<SegmentPreviewSummary> {
    checkAborted(options)
    this.require('audience.preview')
    if (planRef.expiresAt <= FIXTURE_NOW) {
      throw new UiError({
        code: UI_ERROR_CODES.GATEWAY_PLAN_EXPIRED,
        message: fixedMessage(UI_ERROR_CODES.GATEWAY_PLAN_EXPIRED),
      })
    }
    const stale = this.state.scenario === 'STALE'
    const response: SegmentPreviewResponse = {
      status: 'SUCCESS',
      canonicalAstHash: fixtureHash('ast-1'),
      queryPlanId: `qp-${fixtureHash('plan-1').slice(7)}`,
      queryPlanHash: fixtureHash('plan-1'),
      matchedCount: 1284,
      conditionStats: [
        { path: 'r', trueCount: 1284, falseCount: 716, unknownCount: 12 },
        { path: 'r.0', trueCount: 1284, falseCount: 0, unknownCount: 0 },
      ],
      sample: this.state.permissions.has('audience.preview') ? [fixtureHash('sample-1'), fixtureHash('sample-2')] : null,
      executionContext: {
        metadataSnapshotId: 'metadata-fixture-1',
        asOf: FIXTURE_NOW,
        timezone: 'Asia/Shanghai',
        dataWatermark: stale ? '2026-10-08T06:00:00Z' : '2026-10-08T08:55:00Z',
      },
    }
    return projectSegmentPreview(response, FIXTURE_MAX_DATA_AGE_SECONDS)
  }

  async simulate(planRef: PlanRef, options: GatewayCallOptions): Promise<SimulationSummary> {
    checkAborted(options)
    this.require('campaign.edit')
    if (planRef.expiresAt <= FIXTURE_NOW) {
      throw new UiError({
        code: UI_ERROR_CODES.GATEWAY_PLAN_EXPIRED,
        message: fixedMessage(UI_ERROR_CODES.GATEWAY_PLAN_EXPIRED),
      })
    }
    return decodeSimulationSummary({
      environment: SIMULATED_ENVIRONMENT,
      simulationFormatVersion: '1.0',
      simulatorVersion: 'journey-simulator/1.0',
      runtimeSchemaVersion: 'campaign-runtime/1.0',
      virtualStartInstant: FIXTURE_NOW,
      virtualTimezone: 'Asia/Shanghai',
      paths: [
        {
          status: 'COMPLETED',
          terminalNodeLogicalId: 'flow-end',
          visitedNodeLogicalIds: ['flow-start', 'flow-message', 'flow-end'],
          entryVirtualInstants: [FIXTURE_NOW, FIXTURE_NOW, FIXTURE_NOW],
          actions: [
            { kind: 'MESSAGE', nodeLogicalId: 'flow-message', virtualInstant: FIXTURE_NOW },
            { kind: 'REWARD', nodeLogicalId: 'flow-end', virtualInstant: FIXTURE_NOW },
          ],
          assumptions: [],
          errorReasonCode: null,
        },
        {
          status: 'ERRORED',
          terminalNodeLogicalId: 'flow-condition',
          visitedNodeLogicalIds: ['flow-start', 'flow-condition'],
          entryVirtualInstants: [FIXTURE_NOW, FIXTURE_NOW],
          actions: [],
          assumptions: [
            { nodeLogicalId: 'flow-condition', factCode: 'CONDITION_FACT_UNKNOWN', assumedBranch: 'TRUE' },
            { nodeLogicalId: 'flow-condition', factCode: 'CONDITION_FACT_UNKNOWN', assumedBranch: 'FALSE' },
          ],
          errorReasonCode: 'JOURNEY_SIMULATION_STEP_LIMIT_EXCEEDED',
        },
      ],
      issues: [
        {
          code: 'JOURNEY_SIMULATION_UNKNOWN_FACT_ASSUMPTION',
          severity: 'WARNING',
          path: '$.paths[1]',
          nodeId: 'flow-condition',
          message: 'An unknown condition fact produced both branches as assumptions.',
        },
      ],
    }, '$')
  }

  async applyPlan(command: ApplyCommand, options: GatewayCallOptions): Promise<ApplyResult> {
    checkAborted(options)
    this.require('campaign.edit')
    if (this.state.scenario === 'STALE') {
      throw new UiError({
        code: UI_ERROR_CODES.GATEWAY_STALE_REVISION,
        message: fixedMessage(UI_ERROR_CODES.GATEWAY_STALE_REVISION),
        details: { serverCode: 'LIFECYCLE_DRAFT_STALE' },
      })
    }
    const plan = this.state.plan
    if (plan === null || plan.planRef.planId !== command.planId) {
      throw new UiError({
        code: UI_ERROR_CODES.GATEWAY_PLAN_MISMATCH,
        message: fixedMessage(UI_ERROR_CODES.GATEWAY_PLAN_MISMATCH),
      })
    }
    if (plan.planRef.planHash !== command.planHash) {
      throw new UiError({
        code: UI_ERROR_CODES.GATEWAY_PLAN_MISMATCH,
        message: fixedMessage(UI_ERROR_CODES.GATEWAY_PLAN_MISMATCH),
      })
    }
    if (plan.planRef.expiresAt <= FIXTURE_NOW) {
      throw new UiError({
        code: UI_ERROR_CODES.GATEWAY_PLAN_EXPIRED,
        message: fixedMessage(UI_ERROR_CODES.GATEWAY_PLAN_EXPIRED),
      })
    }
    const draftRevision = (this.state.draft?.draftRevision ?? 0) + 1
    const result: ApplyResult = {
      campaignId: FIXTURE_CAMPAIGN_ID,
      campaignRevision: (this.state.draft?.campaignRevision ?? 0) + 1,
      draftId: FIXTURE_DRAFT_ID,
      draftRevision,
      manifestHash: plan.manifestHash,
      draftHash: fixtureHash(`draft-${draftRevision}`),
      appliedAt: FIXTURE_NOW,
      clientRefBindings: [{ clientRef: 'segment-new', logicalId: 'segment-1' }],
    }
    this.state = {
      ...this.state,
      plan: { ...plan, status: 'APPLIED' },
      draft: {
        draftId: FIXTURE_DRAFT_ID,
        draftRevision,
        campaignRevision: result.campaignRevision,
        manifestHash: result.manifestHash,
        savedAt: result.appliedAt,
      },
    }
    return result
  }

  async preparePublish(command: PreparePublishCommand, options: GatewayCallOptions): Promise<PublishPreparation> {
    checkAborted(options)
    this.require('campaign.publish')
    const draft = this.state.draft
    if (draft === null) {
      throw new UiError({
        code: UI_ERROR_CODES.GATEWAY_NOT_FOUND,
        message: fixedMessage(UI_ERROR_CODES.GATEWAY_NOT_FOUND),
      })
    }
    if (command.draftRevision !== draft.draftRevision) {
      throw new UiError({
        code: UI_ERROR_CODES.GATEWAY_STALE_REVISION,
        message: fixedMessage(UI_ERROR_CODES.GATEWAY_STALE_REVISION),
        details: { serverCode: 'LIFECYCLE_DRAFT_STALE' },
      })
    }
    if (this.state.scenario === 'STALE') {
      throw new UiError({
        code: UI_ERROR_CODES.GATEWAY_PREPARATION_STALE,
        message: fixedMessage(UI_ERROR_CODES.GATEWAY_PREPARATION_STALE),
        details: { serverCode: 'LIFECYCLE_PREPARATION_STALE' },
      })
    }
    const preparationHash = contentHashOfText(`fixture-preparation/${draft.draftRevision}`)
    const preparation: PublishPreparation = {
      preparationId: `prep-${preparationHash.slice(7, 19)}`,
      preparationHash,
      campaignId: command.campaignId,
      campaignRevision: draft.campaignRevision,
      draftId: draft.draftId,
      draftRevision: draft.draftRevision,
      planId: this.state.plan?.planRef.planId ?? fixtureHash('no-plan').slice(7),
      planHash: this.state.plan?.planRef.planHash ?? fixtureHash('no-plan'),
      manifestHash: draft.manifestHash,
      draftHash: fixtureHash(`draft-${draft.draftRevision}`),
      createdAt: FIXTURE_NOW,
      expiresAt: FIXTURE_PREPARATION_EXPIRY,
      state: 'PREPARED',
      requiredRiskCodes: ['RUNTIME_UNAVAILABLE', 'SCHEDULE_WORK_DEFERRED'],
      readinessCapability: 'segment.rule-based',
      readinessState: 'EXPERIMENTAL',
      previewCards: [this.previewCard()],
    }
    this.state = { ...this.state, preparation }
    return preparation
  }

  async publish(command: PublishCommand, options: GatewayCallOptions): Promise<PublishResult> {
    checkAborted(options)
    this.require('campaign.publish')
    const preparation = this.state.preparation
    if (preparation === null || preparation.preparationId !== command.preparationId) {
      throw new UiError({
        code: UI_ERROR_CODES.GATEWAY_PREPARATION_MISMATCH,
        message: fixedMessage(UI_ERROR_CODES.GATEWAY_PREPARATION_MISMATCH),
      })
    }
    if (preparation.preparationHash !== command.preparationHash) {
      throw new UiError({
        code: UI_ERROR_CODES.GATEWAY_PREPARATION_MISMATCH,
        message: fixedMessage(UI_ERROR_CODES.GATEWAY_PREPARATION_MISMATCH),
      })
    }
    const requestHash = contentHashOfText(
      `${command.preparationId}|${command.preparationHash}|${command.idempotencyKey}|${[...command.confirmations].join(',')}`,
    )
    // Idempotency is proved before the durable state, so a reused key with a
    // different request is a bounded conflict rather than a state surprise.
    if (this.state.published !== null) {
      if (this.state.publishRequestHash === requestHash) {
        return this.state.published
      }
      if (this.state.scenario === 'CONFLICT') {
        throw new UiError({
          code: UI_ERROR_CODES.GATEWAY_IDEMPOTENCY_CONFLICT,
          message: fixedMessage(UI_ERROR_CODES.GATEWAY_IDEMPOTENCY_CONFLICT),
          details: { serverCode: 'LIFECYCLE_PUBLISH_CONFLICT' },
        })
      }
    }
    const state = effectivePreparationState(preparation, FIXTURE_NOW)
    if (state === 'CONSUMED') {
      throw new UiError({
        code: UI_ERROR_CODES.GATEWAY_PREPARATION_CONSUMED,
        message: fixedMessage(UI_ERROR_CODES.GATEWAY_PREPARATION_CONSUMED),
      })
    }
    if (state === 'EXPIRED') {
      throw new UiError({
        code: UI_ERROR_CODES.GATEWAY_PREPARATION_EXPIRED,
        message: fixedMessage(UI_ERROR_CODES.GATEWAY_PREPARATION_EXPIRED),
      })
    }
    if (state === 'STALE') {
      throw new UiError({
        code: UI_ERROR_CODES.GATEWAY_PREPARATION_STALE,
        message: fixedMessage(UI_ERROR_CODES.GATEWAY_PREPARATION_STALE),
      })
    }
    if (!confirmationsMatch(preparation, command.confirmations)) {
      throw refused('confirmation-set')
    }
    const publishedRevision = 1
    const result: PublishResult = {
      preparationId: preparation.preparationId,
      preparationHash: preparation.preparationHash,
      idempotencyKey: command.idempotencyKey,
      publishedIdentity: {
        publishedId: `published-${preparation.preparationHash.slice(7, 19)}`,
        publishedRevision,
        contentChecksum: fixtureHash(`published-${publishedRevision}`),
      },
      draftIdentity: { draftId: preparation.draftId, draftRevision: preparation.draftRevision },
      manifestHash: preparation.manifestHash,
      confirmedRiskCodes: [...command.confirmations],
      publishedAt: FIXTURE_NOW,
    }
    this.state = {
      ...this.state,
      preparation: { ...preparation, state: 'CONSUMED' },
      published: result,
      publishRequestHash: requestHash,
    }
    return result
  }

  async listExecutions(campaignId: string, options: GatewayCallOptions): Promise<readonly ExecutionSummary[]> {
    checkAborted(options)
    this.require('campaign.read')
    return this.executions().filter((execution) => execution.campaignId === campaignId)
  }

  async listParticipants(executionId: string, options: GatewayCallOptions): Promise<readonly ParticipantSummary[]> {
    checkAborted(options)
    this.require('campaign.read')
    return this.participants(executionId)
  }

  async listActions(executionId: string, options: GatewayCallOptions): Promise<readonly ActionSummary[]> {
    checkAborted(options)
    this.require('campaign.read')
    return this.actions(executionId)
  }

  async reconcileAction(
    actionId: string,
    expectedRevision: number,
    options: GatewayCallOptions,
  ): Promise<ActionOperationResult> {
    checkAborted(options)
    this.require('campaign.operate')
    const action = this.actions('execution-1').find((candidate) => candidate.actionId === actionId)
    if (action === undefined) {
      throw new UiError({
        code: UI_ERROR_CODES.GATEWAY_NOT_FOUND,
        message: fixedMessage(UI_ERROR_CODES.GATEWAY_NOT_FOUND),
      })
    }
    if (action.revision !== expectedRevision) {
      throw new UiError({
        code: UI_ERROR_CODES.GATEWAY_STALE_REVISION,
        message: fixedMessage(UI_ERROR_CODES.GATEWAY_STALE_REVISION),
        details: { serverCode: 'ACTION_STORE_CONFLICT' },
      })
    }
    if (action.status !== 'UNKNOWN' && action.status !== 'VERIFYING') {
      throw refused('ACTION_RECONCILIATION_REQUIRED')
    }
    const revision = action.revision + 1
    this.persistActionOverride(actionId, 'VERIFYING', revision)
    return {
      actionId,
      status: 'VERIFYING',
      disposition: 'VERIFICATION_SCHEDULED',
      revision,
      updatedAt: FIXTURE_NOW,
    }
  }

  private persistActionOverride(
    actionId: string,
    status: ActionSummary['status'],
    revision: number,
  ): void {
    const next = new Map(this.state.actionOverrides)
    next.set(actionId, { status, revision })
    this.state = { ...this.state, actionOverrides: next }
  }

  async safeRetryAction(
    actionId: string,
    expectedRevision: number,
    options: GatewayCallOptions,
  ): Promise<ActionOperationResult> {
    checkAborted(options)
    this.require('campaign.operate')
    const action = this.actions('execution-1').find((candidate) => candidate.actionId === actionId)
    if (action === undefined) {
      throw new UiError({
        code: UI_ERROR_CODES.GATEWAY_NOT_FOUND,
        message: fixedMessage(UI_ERROR_CODES.GATEWAY_NOT_FOUND),
      })
    }
    if (action.status === 'UNKNOWN' || action.status === 'NEEDS_REVIEW') {
      throw refused('ACTION_RETRY_NOT_PERMITTED')
    }
    if (action.revision !== expectedRevision) {
      throw new UiError({
        code: UI_ERROR_CODES.GATEWAY_STALE_REVISION,
        message: fixedMessage(UI_ERROR_CODES.GATEWAY_STALE_REVISION),
        details: { serverCode: 'ACTION_STORE_CONFLICT' },
      })
    }
    if (!action.retryEligibility.safeRetryPermitted) {
      throw refused('ACTION_RETRY_NOT_PERMITTED')
    }
    const revision = action.revision + 1
    this.persistActionOverride(actionId, 'RESERVED', revision)
    return {
      actionId,
      status: 'RESERVED',
      disposition: 'SAFE_RETRY_SCHEDULED',
      revision,
      updatedAt: FIXTURE_NOW,
    }
  }

  async pauseCampaign(
    campaignId: string,
    expectedStateRevision: number,
    options: GatewayCallOptions,
  ): Promise<ScheduleStateResult> {
    checkAborted(options)
    this.require('campaign.operate')
    return this.applyScheduleState(campaignId, expectedStateRevision, 'PAUSED')
  }

  async resumeCampaign(
    campaignId: string,
    expectedStateRevision: number,
    options: GatewayCallOptions,
  ): Promise<ScheduleStateResult> {
    checkAborted(options)
    this.require('campaign.operate')
    return this.applyScheduleState(campaignId, expectedStateRevision, 'ACTIVE')
  }

  private applyScheduleState(
    campaignId: string,
    expectedStateRevision: number,
    status: string,
  ): ScheduleStateResult {
    if (this.state.scenario === 'STALE' || expectedStateRevision !== this.state.scheduleStateRevision) {
      throw new UiError({
        code: UI_ERROR_CODES.GATEWAY_STALE_REVISION,
        message: fixedMessage(UI_ERROR_CODES.GATEWAY_STALE_REVISION),
        details: { serverCode: 'LIFECYCLE_SCHEDULE_STATE_CONFLICT' },
      })
    }
    const stateRevision = expectedStateRevision + 1
    this.state = { ...this.state, scheduleStateRevision: stateRevision, scheduleStatus: status }
    return { campaignId, stateRevision, status, updatedAt: FIXTURE_NOW }
  }

  private campaign(): CampaignSummary {
    return {
      campaignId: FIXTURE_CAMPAIGN_ID,
      name: 'Fixture Campaign',
      status: this.state.scenario === 'READY' ? 'ACTIVE' : 'DRAFT',
      revision: 3,
      timezone: 'Asia/Shanghai',
      locale: 'en-GB',
    }
  }

  private readiness(): readonly CapabilityReadiness[] {
    return [
      { capabilityId: 'segment.rule-based', state: 'EXPERIMENTAL', reasonCodes: ['GATE_APPROVAL_MISSING'] },
      {
        capabilityId: 'segment.existing-audience-reference',
        state: 'EXPERIMENTAL',
        reasonCodes: ['REFERENCE_APPROVAL_PENDING'],
      },
    ]
  }

  private summaries(): SummaryCards {
    const messages: MessageSummary[] = [
      {
        logicalId: 'message-1',
        capabilityCode: 'message.template',
        channel: 'EMAIL',
        locale: 'en-GB',
        contentPresent: false,
        status: 'RESOLVED',
      },
    ]
    const promotions: PromotionSummary[] = [
      {
        logicalId: 'promotion-1',
        capabilityCode: 'promotion.reference',
        benefitKind: 'POINTS',
        budgetState: 'HELD',
        status: 'RESOLVED',
      },
    ]
    const journeys: JourneySummary[] = [
      {
        logicalId: 'journey-1',
        capabilityCode: 'campaign.flow',
        entryNodeLogicalId: 'flow-start',
        nodeCount: 3,
        maxStepsPerPath: 64,
        status: 'EXPERIMENTAL',
      },
    ]
    const schedules: ScheduleSummary[] = [
      {
        logicalId: 'schedule-1',
        capabilityCode: 'schedule.basic',
        form: 'IMMEDIATE',
        zoneId: 'Asia/Shanghai',
        runAt: null,
        localTime: null,
        startDate: null,
        endDate: null,
        status: 'ACTIVE',
        nextOccurrence: null,
      },
    ]
    const governance: GovernanceSummary[] = [
      {
        logicalId: 'governance-1',
        capabilityCode: 'governance.limit',
        approvalState: 'PENDING',
        requiredRiskCodes: ['RUNTIME_UNAVAILABLE', 'SCHEDULE_WORK_DEFERRED'],
        capabilities: [...this.readiness()],
      },
    ]
    return { messages, promotions, journeys, schedules, governance }
  }

  private executions(): readonly ExecutionSummary[] {
    const counters: ExecutionCountersSummary = {
      completedCount: 1180,
      exitedCount: 64,
      failedCount: 8,
      needsReviewCount: this.state.scenario === 'NEEDS_REVIEW' ? 12 : 4,
      waitingCount: 20,
      runningCount: 12,
    }
    return [
      {
        executionId: 'execution-1',
        executionKey: 'execution-key-1',
        campaignId: FIXTURE_CAMPAIGN_ID,
        status: this.state.scenario === 'READY' ? 'RUNNING' : 'NEEDS_REVIEW',
        candidateCount: 2000,
        enrolledCount: 1288,
        revision: 5,
        nominalInstant: FIXTURE_NOW,
        dueInstant: FIXTURE_NOW,
        createdAt: FIXTURE_NOW,
        updatedAt: FIXTURE_NOW,
        counters,
      },
      {
        executionId: 'execution-2',
        executionKey: 'execution-key-2',
        campaignId: FIXTURE_CAMPAIGN_ID,
        status: 'COMPLETED',
        candidateCount: 500,
        enrolledCount: 480,
        revision: 3,
        nominalInstant: '2026-10-07T09:00:00Z',
        dueInstant: '2026-10-07T09:00:00Z',
        createdAt: '2026-10-07T09:00:00Z',
        updatedAt: '2026-10-07T10:00:00Z',
        counters: {
          completedCount: 470,
          exitedCount: 6,
          failedCount: 2,
          needsReviewCount: 2,
          waitingCount: 0,
          runningCount: 0,
        },
      },
    ]
  }

  private participants(executionId: string): readonly ParticipantSummary[] {
    if (executionId !== 'execution-1') {
      return []
    }
    const participants: readonly JsonValue[] = [
      {
        participantId: 'participant-0001',
        executionId,
        currentNodeLogicalId: 'flow-message',
        status: 'WAITING',
        waitingReason: 'ACTION_VERIFY',
        waitingDeadline: FIXTURE_NOW,
        lastAcceptedActionInstant: null,
        revision: 4,
        updatedAt: FIXTURE_NOW,
      },
      {
        participantId: 'participant-0002',
        executionId,
        currentNodeLogicalId: 'flow-end',
        status: 'COMPLETED',
        waitingReason: null,
        waitingDeadline: null,
        lastAcceptedActionInstant: FIXTURE_NOW,
        revision: 6,
        updatedAt: FIXTURE_NOW,
      },
      {
        participantId: 'participant-0003',
        executionId,
        currentNodeLogicalId: 'flow-condition',
        status: 'NEEDS_REVIEW',
        waitingReason: null,
        waitingDeadline: null,
        lastAcceptedActionInstant: null,
        revision: 7,
        updatedAt: FIXTURE_NOW,
      },
    ]
    return participants.map((participant, index) =>
      decodeParticipantSummary(participant, `$.participants[${index}]`),
    )
  }

  private actions(executionId: string): readonly ActionSummary[] {
    if (executionId !== 'execution-1') {
      return []
    }
    const base: readonly ActionSummary[] = [
      this.action(1, 'MESSAGE', 'SUCCEEDED', 'FINALIZED', null, {
        safeRetryPermitted: false,
        definitiveNonApplication: false,
        submissionAttemptsRemain: true,
        reasonCodes: [],
      }),
      this.action(2, 'MESSAGE', 'UNKNOWN', 'HELD', 'UNKNOWN', {
        safeRetryPermitted: false,
        definitiveNonApplication: false,
        submissionAttemptsRemain: true,
        reasonCodes: ['ACTION_RECONCILIATION_REQUIRED'],
      }),
      this.action(3, 'MESSAGE', 'VERIFYING', 'HELD', 'PENDING', {
        safeRetryPermitted: false,
        definitiveNonApplication: false,
        submissionAttemptsRemain: true,
        reasonCodes: ['ACTION_VERIFICATION_IN_PROGRESS'],
      }),
      this.action(4, 'REWARD', 'NEEDS_REVIEW', 'HELD', 'UNKNOWN', {
        safeRetryPermitted: false,
        definitiveNonApplication: false,
        submissionAttemptsRemain: false,
        reasonCodes: ['ACTION_VERIFICATION_EXHAUSTED', 'ACTION_RETRY_NOT_PERMITTED'],
      }),
      this.action(5, 'REWARD', 'DEFINITELY_FAILED', 'RELEASED', 'DEFINITELY_NOT_APPLIED', {
        safeRetryPermitted: true,
        definitiveNonApplication: true,
        submissionAttemptsRemain: true,
        reasonCodes: ['ACTION_SAFE_RETRY_ELIGIBLE'],
      }),
      this.action(6, 'MESSAGE', 'DEFINITELY_FAILED', 'RELEASED', 'DEFINITELY_NOT_APPLIED', {
        safeRetryPermitted: false,
        definitiveNonApplication: true,
        submissionAttemptsRemain: false,
        reasonCodes: ['ACTION_SUBMISSION_ATTEMPTS_EXHAUSTED'],
      }),
    ]
    return base.map((action) => {
      const override = this.state.actionOverrides.get(action.actionId)
      if (override === undefined) {
        return action
      }
      return { ...action, status: override.status, revision: override.revision }
    })
  }

  private action(
    index: number,
    actionKind: 'MESSAGE' | 'REWARD',
    status: ActionSummary['status'],
    budgetState: ActionSummary['budgetState'],
    providerOutcome: ActionSummary['providerOutcome'],
    retryEligibility: ActionSummary['retryEligibility'],
  ): ActionSummary {
    return {
      actionId: `action-${index}`,
      executionId: 'execution-1',
      participantId: `participant-000${index}`,
      nodeLogicalId: actionKind === 'MESSAGE' ? 'flow-message' : 'flow-reward',
      actionKind,
      status,
      reasonCode: status === 'SUCCEEDED' ? null : `ACTION_${status}`,
      submissionCount: status === 'RESERVED' ? 0 : 1,
      verificationCount: status === 'VERIFYING' ? 1 : 0,
      acceptedInstant: status === 'SUCCEEDED' ? FIXTURE_NOW : null,
      revision: index,
      updatedAt: FIXTURE_NOW,
      idempotencySafe: true,
      providerOutcome,
      providerEvidence: {
        referencePresent: providerOutcome === 'SUCCEEDED',
        reasonCode: providerOutcome === null ? null : `PROVIDER_${providerOutcome}`,
      },
      budgetState,
      retryEligibility,
    }
  }
}
