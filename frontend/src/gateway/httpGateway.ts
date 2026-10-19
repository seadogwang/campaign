/**
 * The documented `/api/v1` HTTP gateway.
 *
 * It speaks the documented route paths and the documented
 * `{success,data|error,requestId}` envelope, decodes every body strictly,
 * applies a bounded timeout, honours the caller's abort signal, sends an
 * `Idempotency-Key` on every mutation and never logs or renders a raw response
 * body.
 *
 * Only the Segment Preview slice is exposed by the accepted backend today, and
 * it is exposed at `POST /ai/segments/preview` rather than under `/api/v1`.
 * Nothing in this class claims the other routes are wired.
 */

import { UI_ERROR_CODES, UiError, contractError, fixedMessage, fromServerErrorCode } from '../contracts/errors'
import { decodeEnvelope } from '../contracts/envelope'
import {
  decodeConversationProjection,
  encodeCampaignTurnRequest,
  type CampaignTurnRequest,
  type ConversationProjection,
} from '../contracts/conversation'
import type { CampaignPlanSummary, PlanRef } from '../contracts/plan'
import { decodeCampaignPlanSummary, decodePlanRef } from '../contracts/plan'
import type { SegmentPreviewSummary } from '../contracts/preview'
import { decodeSegmentPreviewResponse, projectSegmentPreview } from '../contracts/preview'
import {
  encodeCampaignProposalPatch,
  type CampaignProposalPatch,
} from '../contracts/proposal'
import {
  decodeApplyResult,
  decodePublishPreparation,
  decodePublishResult,
  type ApplyCommand,
  type ApplyResult,
  type PreparePublishCommand,
  type PublishCommand,
  type PublishPreparation,
  type PublishResult,
} from '../contracts/publish'
import { httpEnvironmentEvidence } from '../contracts/readiness'
import { decodeSimulationSummary, type SimulationSummary } from '../contracts/simulation'
import {
  decodeActionOperationResult,
  decodeActionSummary,
  decodeExecutionSummary,
  decodeParticipantSummary,
  decodeScheduleStateResult,
  type ActionOperationResult,
  type ActionSummary,
  type ExecutionSummary,
  type ParticipantSummary,
  type ScheduleStateResult,
} from '../contracts/operations'
import {
  decodeCampaignSummary,
  decodeDraftSummary,
  decodeGovernanceSummary,
  decodeJourneySummary,
  decodeMessageSummary,
  decodePromotionSummary,
  decodeScheduleSummary,
  type SummaryCards,
} from '../contracts/summaries'
import { toPermissionSet } from '../contracts/permissions'
import type { CapabilityReadiness } from '../contracts/summaries'
import { CONTENT_HASH_PATTERN, REFERENCE_ID_PATTERN, type ContentHash } from '../contracts/primitives'
import {
  field,
  optionalField,
  requireBoolean,
  requireExactFields,
  requireNonNegativeInteger,
  requireObject,
  requireObjectArray,
  requirePattern,
  requireString,
  requireStringArray,
} from '../contracts/validate'
import { decodeCapabilityReadiness } from '../contracts/summaries'
import type {
  CampaignConversationIdentity,
  CampaignWorkspaceGateway,
  ConversationSummary,
  GatewayCallOptions,
  WorkspaceSnapshot,
} from './types'

// A single builder turn can make several bounded model calls before returning. The backend permits one
// model call to take longer than ten seconds, so the browser must not abandon a successful turn first and
// then show a false failure while the server commits it in the background.
export const DEFAULT_TIMEOUT_MS = 60_000
export const ACCEPTED_PREVIEW_ROUTE = '/ai/segments/preview'
/** The documented server-issued conversation-create route. */
export const CONVERSATION_CREATE_ROUTE = '/api/v1/campaign-agent/conversations'
/** The documented read-only conversation-history route base. */
export const CONVERSATION_HISTORY_ROUTE = '/api/v1/campaign-agent/history'
/** The bounded number of history entries this gateway requests and accepts. */
export const CONVERSATION_HISTORY_LIMIT = 50

export interface HttpGatewayOptions {
  /** Origin only, e.g. `https://host`. Documented routes already include `/api/v1`. */
  readonly baseUrl: string
  readonly campaignId: string
  readonly timeoutMs?: number
  /** Send Segment Preview to the accepted `POST /ai/segments/preview` slice. */
  readonly acceptedPreviewSlice?: boolean
  readonly fetchImpl?: typeof fetch
}

interface RequestSpec {
  readonly method: 'GET' | 'POST'
  readonly route: string
  readonly body?: unknown
  readonly idempotencyKey?: string
  readonly signal?: AbortSignal
}

const WORKSPACE_FIELDS = [
  'campaign',
  'draft',
  'lastPlan',
  'summaries',
  'permissions',
  'readiness',
  'pins',
] as const
const PIN_FIELDS = ['catalogVersion', 'catalogHash', 'policyVersion', 'policyHash'] as const
const SUMMARY_FIELDS = ['messages', 'promotions', 'journeys', 'schedules', 'governance'] as const

function summaryCards(raw: unknown, path: string): SummaryCards {
  const obj = requireObject(raw, path)
  requireExactFields(obj, SUMMARY_FIELDS, path)
  return {
    messages: requireObjectArray(obj['messages'], `${path}.messages`, 32).map(({ item, path: itemPath }) =>
      decodeMessageSummary(item, itemPath),
    ),
    promotions: requireObjectArray(obj['promotions'], `${path}.promotions`, 32).map(({ item, path: itemPath }) =>
      decodePromotionSummary(item, itemPath),
    ),
    journeys: requireObjectArray(obj['journeys'], `${path}.journeys`, 32).map(({ item, path: itemPath }) =>
      decodeJourneySummary(item, itemPath),
    ),
    schedules: requireObjectArray(obj['schedules'], `${path}.schedules`, 32).map(({ item, path: itemPath }) =>
      decodeScheduleSummary(item, itemPath),
    ),
    governance: requireObjectArray(obj['governance'], `${path}.governance`, 32).map(({ item, path: itemPath }) =>
      decodeGovernanceSummary(item, itemPath),
    ),
  }
}

function readinessList(raw: unknown, path: string): CapabilityReadiness[] {
  return requireObjectArray(raw, path, 16).map(({ item, path: itemPath }) =>
    decodeCapabilityReadiness(item, itemPath),
  )
}

const CONVERSATION_FIELDS = ['conversationId', 'runId', 'revision'] as const

/**
 * Strictly decodes the data of one server-issued conversation identity.
 *
 * It requires exactly the three declared fields, a bounded reference identifier for each identity component
 * and the fresh revision zero. An unknown field, a wrong primitive or a non-zero revision is refused, so a
 * malformed issue can never become a usable identity, and no trusted value is ever read from it.
 */
function decodeConversationIdentity(raw: unknown, path: string): CampaignConversationIdentity {
  const obj = requireObject(raw, path)
  requireExactFields(obj, CONVERSATION_FIELDS, path)
  const conversationId = requirePattern(
    field(obj, 'conversationId', path),
    REFERENCE_ID_PATTERN,
    `${path}.conversationId`,
    'reference-id',
  )
  const runId = requirePattern(
    field(obj, 'runId', path),
    REFERENCE_ID_PATTERN,
    `${path}.runId`,
    'reference-id',
  )
  const revision = requireNonNegativeInteger(field(obj, 'revision', path), `${path}.revision`)
  if (revision !== 0) {
    throw contractError(
      UI_ERROR_CODES.CONTRACT_VALUE_INVALID,
      `${path}.revision`,
      'conversation-revision-not-zero',
    )
  }
  return { conversationId, runId }
}

const HISTORY_LIST_FIELDS = ['conversations'] as const
const HISTORY_ENTRY_FIELDS = [
  'conversationId',
  'runId',
  'revision',
  'hasSession',
  'title',
  'turnCount',
  'createdAt',
  'updatedAt',
] as const

/**
 * Strictly decodes one server-owned conversation-history entry.
 *
 * It requires the declared fields, a bounded reference identity and non-negative counts. The
 * nullable title is the only optional member, and it is accepted exactly when the host omits it (a
 * reserved conversation with no accepted turn); a present non-string title is refused.
 */
function decodeConversationSummary(raw: unknown, path: string): ConversationSummary {
  const obj = requireObject(raw, path)
  requireExactFields(obj, HISTORY_ENTRY_FIELDS, path)
  const titleRaw = optionalField(obj, 'title')
  return {
    conversationId: requirePattern(
      field(obj, 'conversationId', path),
      REFERENCE_ID_PATTERN,
      `${path}.conversationId`,
      'reference-id',
    ),
    runId: requirePattern(field(obj, 'runId', path), REFERENCE_ID_PATTERN, `${path}.runId`, 'reference-id'),
    revision: requireNonNegativeInteger(field(obj, 'revision', path), `${path}.revision`),
    hasSession: requireBoolean(field(obj, 'hasSession', path), `${path}.hasSession`),
    title: titleRaw === undefined || titleRaw === null ? null : requireString(titleRaw, `${path}.title`),
    turnCount: requireNonNegativeInteger(field(obj, 'turnCount', path), `${path}.turnCount`),
    createdAt: requireString(field(obj, 'createdAt', path), `${path}.createdAt`),
    updatedAt: requireString(field(obj, 'updatedAt', path), `${path}.updatedAt`),
  }
}

export class HttpCampaignWorkspaceGateway implements CampaignWorkspaceGateway {
  readonly mode = 'HTTP' as const
  readonly environment
  get nowInstant(): string {
    return new Date().toISOString()
  }
  private readonly baseUrl: string
  private readonly campaignId: string
  private readonly timeoutMs: number
  private readonly acceptedPreviewSlice: boolean
  private readonly fetchImpl: typeof fetch
  /** The one in-flight or resolved page-session bootstrap, or null before the first send. */
  private bootstrap: Promise<CampaignConversationIdentity> | null = null
  /** The stable Idempotency-Key that makes a retried bootstrap re-issue the same conversation. */
  private readonly bootstrapKey: string
  /** A per-instance counter that makes every explicit "New Campaign" key distinct. */
  private newConversationCount = 0

  constructor(options: HttpGatewayOptions) {
    this.baseUrl = options.baseUrl.replace(/\/+$/, '')
    this.campaignId = options.campaignId
    this.bootstrapKey = `bootstrap-${Date.now().toString(36)}`
    this.timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS
    this.acceptedPreviewSlice = options.acceptedPreviewSlice ?? false
    this.environment = httpEnvironmentEvidence(this.baseUrl)
    const impl = options.fetchImpl ?? globalThis.fetch
    if (typeof impl !== 'function') {
      throw new UiError({
        code: UI_ERROR_CODES.GATEWAY_TRANSPORT_FAILED,
        message: fixedMessage(UI_ERROR_CODES.GATEWAY_TRANSPORT_FAILED),
        details: { reason: 'fetch-unavailable' },
      })
    }
    // Browser-native `fetch` is a Web API function and must retain the global object as its
    // receiver. Unit-test doubles do not require that binding, which is why an unbound native
    // function can pass gateway tests yet fail before issuing any request in a real browser.
    this.fetchImpl = options.fetchImpl ?? impl.bind(globalThis)
  }

  private requireIdempotencyKey(options: GatewayCallOptions): string {
    const key = options.idempotencyKey
    if (key === undefined || key.length === 0) {
      throw new UiError({
        code: UI_ERROR_CODES.CONTRACT_VALUE_INVALID,
        message: fixedMessage(UI_ERROR_CODES.CONTRACT_VALUE_INVALID),
        details: { reason: 'mutation-requires-idempotency-key' },
      })
    }
    return key
  }

  /**
   * Resolves the server-issued identity of this page session, bootstrapping it exactly once.
   *
   * The first caller starts one `POST /api/v1/campaign-agent/conversations`; every later caller awaits the
   * same promise, so a burst of concurrent sends can never create more than one conversation. A failed
   * bootstrap clears the promise, so the next send retries with the same idempotency key rather than caching a
   * permanent failure. The identity lives only in this instance: it is never written to `localStorage`, a
   * cookie or a client-generated UUID, and it disappears with the page session.
   */
  conversationIdentity(): Promise<CampaignConversationIdentity> {
    if (this.bootstrap === null) {
      this.bootstrap = this.bootstrapConversation()
    }
    return this.bootstrap
  }

  private async bootstrapConversation(): Promise<CampaignConversationIdentity> {
    try {
      return await this.request(
        {
          method: 'POST',
          route: CONVERSATION_CREATE_ROUTE,
          body: {},
          idempotencyKey: this.bootstrapKey,
        },
        decodeConversationIdentity,
      )
    } catch (thrown) {
      this.bootstrap = null
      throw thrown
    }
  }

  /**
   * Starts a fresh server-owned conversation for the explicit "New Campaign" action.
   *
   * The idempotency key is generated per call, so a repeated click always mints a new server-owned
   * conversation rather than replaying an earlier one. A failed create leaves the previous identity
   * in place, so a transient failure never strands the page without a usable conversation.
   */
  async startNewConversation(options: GatewayCallOptions): Promise<CampaignConversationIdentity> {
    this.newConversationCount += 1
    const identity = await this.request(
      {
        method: 'POST',
        route: CONVERSATION_CREATE_ROUTE,
        body: {},
        idempotencyKey: `new-${Date.now().toString(36)}-${this.newConversationCount}`,
        ...this.signalOf(options),
      },
      decodeConversationIdentity,
    )
    this.bootstrap = Promise.resolve(identity)
    return identity
  }

  /**
   * Lists the caller's own server-owned conversations from `GET /api/v1/campaign-agent/history`.
   *
   * The list is decoded strictly: an unknown field, a malformed identity, a negative count or a
   * non-string title is refused rather than rendered.
   */
  async listConversations(options: GatewayCallOptions): Promise<readonly ConversationSummary[]> {
    return this.request(
      {
        method: 'GET',
        route: `${CONVERSATION_HISTORY_ROUTE}/conversations?limit=${CONVERSATION_HISTORY_LIMIT}`,
        ...this.signalOf(options),
      },
      (value, path) => {
        const obj = requireObject(value, path)
        requireExactFields(obj, HISTORY_LIST_FIELDS, path)
        return requireObjectArray(obj['conversations'], `${path}.conversations`, CONVERSATION_HISTORY_LIMIT).map(
          ({ item, path: itemPath }) => decodeConversationSummary(item, itemPath),
        )
      },
    )
  }

  /**
   * Loads one stored conversation projection from the read-only history route.
   *
   * The decoded projection must belong to the exact requested conversation and run identity: a
   * mismatched identity is refused with a bounded contract error.
   */
  async readConversation(
    conversationId: string,
    runId: string,
    options: GatewayCallOptions,
  ): Promise<ConversationProjection> {
    const projection = await this.request(
      {
        method: 'GET',
        route: `${CONVERSATION_HISTORY_ROUTE}/conversations/${encodeURIComponent(conversationId)}`
          + `?runId=${encodeURIComponent(runId)}`,
        ...this.signalOf(options),
      },
      decodeConversationProjection,
    )
    if (projection.conversationId !== conversationId || projection.runId !== runId) {
      throw new UiError({
        code: UI_ERROR_CODES.CONTRACT_VALUE_INVALID,
        message: fixedMessage(UI_ERROR_CODES.CONTRACT_VALUE_INVALID),
        details: { reason: 'conversation-identity-mismatch' },
      })
    }
    // Adopt the selected conversation, so every later send continues it rather than the bootstrap one.
    this.bootstrap = Promise.resolve({ conversationId, runId })
    return projection
  }

  private async request<T>(
    spec: RequestSpec,
    decode: (value: unknown, path: string) => T,
  ): Promise<T> {
    const callerSignal = spec.signal
    if (callerSignal?.aborted === true) {
      throw this.transportError(false, callerSignal)
    }
    const controller = new AbortController()
    let timedOut = false
    const timer = setTimeout(() => {
      timedOut = true
      controller.abort()
    }, this.timeoutMs)
    const onCallerAbort = (): void => controller.abort()
    callerSignal?.addEventListener('abort', onCallerAbort)

    try {
      let response: Response
      try {
        const headers: Record<string, string> = { accept: 'application/json' }
        if (spec.body !== undefined) {
          headers['content-type'] = 'application/json'
        }
        if (spec.idempotencyKey !== undefined) {
          headers['Idempotency-Key'] = spec.idempotencyKey
        }
        response = await this.fetchImpl(`${this.baseUrl}${spec.route}`, {
          method: spec.method,
          headers,
          ...(spec.body === undefined ? {} : { body: JSON.stringify(spec.body) }),
          signal: controller.signal,
        })
      } catch {
        throw this.transportError(timedOut, callerSignal)
      }

      let parsed: unknown
      try {
        parsed = JSON.parse(await response.text())
      } catch {
        throw new UiError({
          code: UI_ERROR_CODES.GATEWAY_RESPONSE_INVALID,
          message: fixedMessage(UI_ERROR_CODES.GATEWAY_RESPONSE_INVALID),
          details: { reason: 'unreadable-body' },
        })
      }

      const envelope = decodeEnvelope(parsed, (value, path) => decode(value, path))
      if (envelope.success) {
        return envelope.data
      }
      throw fromServerErrorCode(envelope.error.code)
    } finally {
      clearTimeout(timer)
      callerSignal?.removeEventListener('abort', onCallerAbort)
    }
  }

  private transportError(timedOut: boolean, callerSignal: AbortSignal | undefined): UiError {
    if (timedOut) {
      return new UiError({
        code: UI_ERROR_CODES.GATEWAY_TIMEOUT,
        message: fixedMessage(UI_ERROR_CODES.GATEWAY_TIMEOUT),
      })
    }
    if (callerSignal?.aborted === true) {
      return new UiError({
        code: UI_ERROR_CODES.GATEWAY_ABORTED,
        message: fixedMessage(UI_ERROR_CODES.GATEWAY_ABORTED),
      })
    }
    return new UiError({
      code: UI_ERROR_CODES.GATEWAY_TRANSPORT_FAILED,
      message: fixedMessage(UI_ERROR_CODES.GATEWAY_TRANSPORT_FAILED),
    })
  }

  async getWorkspace(options: GatewayCallOptions): Promise<WorkspaceSnapshot> {
    return this.request(
      { method: 'GET', route: `/api/v1/campaigns/${this.campaignId}/draft`, ...this.signalOf(options) },
      (value, path) => {
        const obj = requireObject(value, path)
        requireExactFields(obj, WORKSPACE_FIELDS, path)
        const draftRaw = optionalField(obj, 'draft')
        const planRaw = optionalField(obj, 'lastPlan')
        return {
          environment: this.environment,
          campaign: decodeCampaignSummary(field(obj, 'campaign', path), `${path}.campaign`),
          draft: draftRaw === undefined || draftRaw === null ? null : decodeDraftSummary(draftRaw, `${path}.draft`),
          lastPlan:
            planRaw === undefined || planRaw === null
              ? null
              : decodeCampaignPlanSummary(planRaw, `${path}.lastPlan`),
          summaries: summaryCards(field(obj, 'summaries', path), `${path}.summaries`),
          permissions: toPermissionSet(
            requireStringArray(field(obj, 'permissions', path), `${path}.permissions`, 16),
            `${path}.permissions`,
          ),
          readiness: readinessList(field(obj, 'readiness', path), `${path}.readiness`),
          pins: (() => {
            const pinsPath = `${path}.pins`
            const pins = requireObject(field(obj, 'pins', path), pinsPath)
            requireExactFields(pins, PIN_FIELDS, pinsPath)
            return {
              catalogVersion: requirePattern(
                field(pins, 'catalogVersion', pinsPath),
                REFERENCE_ID_PATTERN,
                `${pinsPath}.catalogVersion`,
                'reference-id',
              ),
              catalogHash: requirePattern(
                field(pins, 'catalogHash', pinsPath),
                CONTENT_HASH_PATTERN,
                `${pinsPath}.catalogHash`,
                'content-hash',
              ) as ContentHash,
              policyVersion: requirePattern(
                field(pins, 'policyVersion', pinsPath),
                REFERENCE_ID_PATTERN,
                `${pinsPath}.policyVersion`,
                'reference-id',
              ),
              policyHash: requirePattern(
                field(pins, 'policyHash', pinsPath),
                CONTENT_HASH_PATTERN,
                `${pinsPath}.policyHash`,
                'content-hash',
              ) as ContentHash,
            }
          })(),
        }
      },
    )
  }

  /**
   * Sends one bounded conversation turn to `POST /api/v1/campaign-agent/turns`.
   *
   * The message is sent exactly as the user typed it: nothing here parses it into
   * a directive, and nothing here builds a Proposal. The response is decoded
   * strictly, so an unknown field, a malformed hash or a malformed revision is
   * refused rather than rendered.
   *
   * A decoded projection must belong to the exact requested conversation and run
   * identity. A mismatched identity is refused with a bounded contract error, so a
   * response for another conversation can never replace the accepted projection.
   */
  async sendCampaignTurn(
    request: CampaignTurnRequest,
    options: GatewayCallOptions,
  ): Promise<ConversationProjection> {
    const projection = await this.request(
      {
        method: 'POST',
        route: '/api/v1/campaign-agent/turns',
        body: encodeCampaignTurnRequest(request),
        idempotencyKey: this.requireIdempotencyKey(options),
        ...this.signalOf(options),
      },
      decodeConversationProjection,
    )
    if (projection.conversationId !== request.conversationId || projection.runId !== request.runId) {
      throw new UiError({
        code: UI_ERROR_CODES.CONTRACT_VALUE_INVALID,
        message: fixedMessage(UI_ERROR_CODES.CONTRACT_VALUE_INVALID),
        details: { reason: 'conversation-identity-mismatch' },
      })
    }
    return projection
  }

  async compileProposal(
    patch: CampaignProposalPatch,
    options: GatewayCallOptions,
  ): Promise<CampaignPlanSummary> {
    return this.request(
      {
        method: 'POST',
        route: '/api/v1/campaign-plans',
        body: encodeCampaignProposalPatch(patch),
        idempotencyKey: this.requireIdempotencyKey(options),
        ...this.signalOf(options),
      },
      decodeCampaignPlanSummary,
    )
  }

  async previewSegment(planRef: PlanRef, options: GatewayCallOptions): Promise<SegmentPreviewSummary> {
    // The accepted backend exposes only the read-only `POST /ai/segments/preview`
    // slice, and it needs the authored Segment DSL rather than a Plan handle.
    // Selecting that route here proves routing only: threading the authored DSL
    // into the preview request is T25 host wiring.
    const route = this.acceptedPreviewSlice
      ? ACCEPTED_PREVIEW_ROUTE
      : `/api/v1/campaign-plans/${planRef.planId}/preview`
    const body = this.acceptedPreviewSlice
      ? { presentation: { includeSample: true, includeConditionStats: true } }
      : { planId: planRef.planId, planHash: planRef.planHash }
    return this.request(
      {
        method: 'POST',
        route,
        body,
        idempotencyKey: this.requireIdempotencyKey(options),
        ...this.signalOf(options),
      },
      (value, path) => {
        const response = decodeSegmentPreviewResponse(value, path)
        return projectSegmentPreview(response, 900)
      },
    )
  }

  async simulate(planRef: PlanRef, options: GatewayCallOptions): Promise<SimulationSummary> {
    return this.request(
      {
        method: 'POST',
        route: `/api/v1/campaign-plans/${planRef.planId}/simulate`,
        body: { planId: planRef.planId, planHash: planRef.planHash },
        idempotencyKey: this.requireIdempotencyKey(options),
        ...this.signalOf(options),
      },
      decodeSimulationSummary,
    )
  }

  async applyPlan(command: ApplyCommand, options: GatewayCallOptions): Promise<ApplyResult> {
    return this.request(
      {
        method: 'POST',
        route: `/api/v1/campaign-plans/${command.planId}/apply`,
        body: { planId: command.planId, planHash: command.planHash },
        idempotencyKey: this.requireIdempotencyKey(options),
        ...this.signalOf(options),
      },
      decodeApplyResult,
    )
  }

  async preparePublish(
    command: PreparePublishCommand,
    options: GatewayCallOptions,
  ): Promise<PublishPreparation> {
    return this.request(
      {
        method: 'POST',
        route: `/api/v1/campaigns/${command.campaignId}/publish-preparations`,
        body: {
          campaignId: command.campaignId,
          draftId: command.draftId,
          draftRevision: command.draftRevision,
        },
        idempotencyKey: this.requireIdempotencyKey(options),
        ...this.signalOf(options),
      },
      decodePublishPreparation,
    )
  }

  async publish(command: PublishCommand, options: GatewayCallOptions): Promise<PublishResult> {
    return this.request(
      {
        method: 'POST',
        route: `/api/v1/publish-preparations/${command.preparationId}/publish`,
        body: {
          preparationId: command.preparationId,
          preparationHash: command.preparationHash,
          idempotencyKey: command.idempotencyKey,
          confirmations: [...command.confirmations],
        },
        idempotencyKey: command.idempotencyKey,
        ...this.signalOf(options),
      },
      decodePublishResult,
    )
  }

  async listExecutions(campaignId: string, options: GatewayCallOptions): Promise<readonly ExecutionSummary[]> {
    return this.request(
      { method: 'GET', route: `/api/v1/campaigns/${campaignId}/executions`, ...this.signalOf(options) },
      (value, path) =>
        requireObjectArray(value, path, 100).map(({ item, path: itemPath }) =>
          decodeExecutionSummary(item, itemPath),
        ),
    )
  }

  async listParticipants(
    executionId: string,
    options: GatewayCallOptions,
  ): Promise<readonly ParticipantSummary[]> {
    return this.request(
      { method: 'GET', route: `/api/v1/executions/${executionId}/participants`, ...this.signalOf(options) },
      (value, path) =>
        requireObjectArray(value, path, 500).map(({ item, path: itemPath }) =>
          decodeParticipantSummary(item, itemPath),
        ),
    )
  }

  async listActions(executionId: string, options: GatewayCallOptions): Promise<readonly ActionSummary[]> {
    return this.request(
      { method: 'GET', route: `/api/v1/executions/${executionId}/actions`, ...this.signalOf(options) },
      (value, path) =>
        requireObjectArray(value, path, 500).map(({ item, path: itemPath }) =>
          decodeActionSummary(item, itemPath),
        ),
    )
  }

  async reconcileAction(
    actionId: string,
    expectedRevision: number,
    options: GatewayCallOptions,
  ): Promise<ActionOperationResult> {
    return this.request(
      {
        method: 'POST',
        route: `/api/v1/actions/${actionId}/reconcile`,
        body: { actionId, expectedRevision, reasonCode: 'OPERATOR_RECONCILE' },
        idempotencyKey: this.requireIdempotencyKey(options),
        ...this.signalOf(options),
      },
      decodeActionOperationResult,
    )
  }

  async safeRetryAction(
    actionId: string,
    expectedRevision: number,
    options: GatewayCallOptions,
  ): Promise<ActionOperationResult> {
    return this.request(
      {
        method: 'POST',
        route: `/api/v1/actions/${actionId}/retry`,
        body: { actionId, expectedRevision, reasonCode: 'OPERATOR_SAFE_RETRY' },
        idempotencyKey: this.requireIdempotencyKey(options),
        ...this.signalOf(options),
      },
      decodeActionOperationResult,
    )
  }

  async pauseCampaign(
    campaignId: string,
    expectedStateRevision: number,
    options: GatewayCallOptions,
  ): Promise<ScheduleStateResult> {
    return this.request(
      {
        method: 'POST',
        route: `/api/v1/campaigns/${campaignId}/pause`,
        body: { campaignId, expectedStateRevision },
        idempotencyKey: this.requireIdempotencyKey(options),
        ...this.signalOf(options),
      },
      decodeScheduleStateResult,
    )
  }

  async resumeCampaign(
    campaignId: string,
    expectedStateRevision: number,
    options: GatewayCallOptions,
  ): Promise<ScheduleStateResult> {
    return this.request(
      {
        method: 'POST',
        route: `/api/v1/campaigns/${campaignId}/resume`,
        body: { campaignId, expectedStateRevision },
        idempotencyKey: this.requireIdempotencyKey(options),
        ...this.signalOf(options),
      },
      decodeScheduleStateResult,
    )
  }

  private signalOf(options: GatewayCallOptions): { signal?: AbortSignal } {
    return options.signal === undefined ? {} : { signal: options.signal }
  }

  /** Exposed for the Plan-handle decode path used by hosts that store a PlanRef. */
  static decodePlanRef(raw: unknown): PlanRef {
    return decodePlanRef(raw, '$.planRef')
  }
}
