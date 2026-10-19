/**
 * Workspace state.
 *
 * Server resources, the last server Plan, the saved Draft and local unsaved
 * edits are four distinct slices. Nothing is persisted: the store holds no
 * Campaign payload, result or identifier in local storage. A stale asynchronous
 * response is discarded by request identity, and a failed call never clears
 * unsaved edits.
 */

import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useReducer,
  useRef,
  type ReactNode,
} from 'react'
import { UI_ERROR_CODES, UiError, fixedMessage, isUiError } from '../contracts/errors'
import type { CampaignPlanSummary } from '../contracts/plan'
import type { SegmentPreviewSummary } from '../contracts/preview'
import type { SimulationSummary } from '../contracts/simulation'
import type {
  ActionOperationResult,
  ActionSummary,
  ExecutionSummary,
  ParticipantSummary,
  ScheduleStateResult,
} from '../contracts/operations'
import type {
  ApplyResult,
  PublishPreparation,
  PublishResult,
} from '../contracts/publish'
import type { ProposalMode } from '../contracts/proposal'
import type { CampaignPermission } from '../contracts/permissions'
import { buildVisualIntent, DEFAULT_VISUAL_STATE, type VisualBuilderState } from '../authoring/visual'
import { submitAuthoring, type AuthoringSubmission } from '../authoring/submit'
import type { TrustedBase } from '../authoring/normalizer'
import type {
  CampaignClarificationAnswer,
  CampaignTurnRequest,
  ComponentIssue,
  ConversationClarification,
  ConversationProjection,
} from '../contracts/conversation'
import {
  isFixtureGateway,
  type CampaignConversationIdentity,
  type CampaignWorkspaceGateway,
  type ConversationSummary,
  type FixtureScenario,
  type GatewayCallOptions,
  type WorkspaceSnapshot,
} from '../gateway/types'

export type AsyncStatus = 'IDLE' | 'LOADING' | 'READY' | 'ERROR'

export interface AsyncSlice<T> {
  readonly status: AsyncStatus
  readonly value: T | null
  readonly error: UiError | null
  readonly seq: number
}

function idle<T>(): AsyncSlice<T> {
  return { status: 'IDLE', value: null, error: null, seq: 0 }
}

export type AuthoringOrigin = 'AI_CONVERSATION' | 'VISUAL_BUILDER'

export interface WorkspaceState {
  readonly workspace: AsyncSlice<WorkspaceSnapshot>
  readonly plan: AsyncSlice<CampaignPlanSummary>
  readonly preview: AsyncSlice<SegmentPreviewSummary>
  readonly simulation: AsyncSlice<SimulationSummary>
  readonly executions: AsyncSlice<readonly ExecutionSummary[]>
  readonly participants: AsyncSlice<readonly ParticipantSummary[]>
  readonly actions: AsyncSlice<readonly ActionSummary[]>
  readonly preparation: AsyncSlice<PublishPreparation>
  readonly publish: AsyncSlice<PublishResult>
  readonly apply: AsyncSlice<ApplyResult>
  readonly scheduleState: AsyncSlice<ScheduleStateResult>
  readonly actionOperation: AsyncSlice<ActionOperationResult>
  /** The host's bounded conversation projection. It is not saved Campaign state. */
  readonly conversation: AsyncSlice<ConversationProjection>
  /** The host-owned conversation history list of this trusted boundary. */
  readonly history: AsyncSlice<readonly ConversationSummary[]>
  /**
   * The identity key (`conversationId::runId`) of the conversation this page is attached to, or
   * null before one has been resolved. It exists so the history list can mark the active entry
   * honestly even while the conversation has no accepted turn yet; it is never persisted.
   */
  readonly activeConversationKey: string | null
  readonly notice: UiError | null
  /** The unsent composer text. It is preserved across every failed turn. */
  readonly composerText: string
  readonly visual: VisualBuilderState
  readonly lastOrigin: AuthoringOrigin
  readonly unsavedNonce: number
  /** True while the editor holds changes that are not in the saved Draft. */
  readonly dirty: boolean
}

export function createInitialState(): WorkspaceState {
  return {
    workspace: idle<WorkspaceSnapshot>(),
    plan: idle<CampaignPlanSummary>(),
    preview: idle<SegmentPreviewSummary>(),
    simulation: idle<SimulationSummary>(),
    executions: idle<readonly ExecutionSummary[]>(),
    participants: idle<readonly ParticipantSummary[]>(),
    actions: idle<readonly ActionSummary[]>(),
    preparation: idle<PublishPreparation>(),
    publish: idle<PublishResult>(),
    apply: idle<ApplyResult>(),
    scheduleState: idle<ScheduleStateResult>(),
    actionOperation: idle<ActionOperationResult>(),
    conversation: idle<ConversationProjection>(),
    history: idle<readonly ConversationSummary[]>(),
    activeConversationKey: null,
    notice: null,
    composerText: '',
    visual: DEFAULT_VISUAL_STATE,
    lastOrigin: 'VISUAL_BUILDER',
    unsavedNonce: 0,
    dirty: false,
  }
}

export type SliceKey =
  | 'workspace'
  | 'plan'
  | 'preview'
  | 'simulation'
  | 'executions'
  | 'participants'
  | 'actions'
  | 'preparation'
  | 'publish'
  | 'apply'
  | 'scheduleState'
  | 'actionOperation'
  | 'conversation'
  | 'history'

type Action =
  | { readonly type: 'BEGIN'; readonly key: SliceKey; readonly seq: number }
  | { readonly type: 'SUCCESS'; readonly key: SliceKey; readonly seq: number; readonly value: unknown }
  | { readonly type: 'FAILURE'; readonly key: SliceKey; readonly seq: number; readonly error: UiError }
  | { readonly type: 'SET_COMPOSER'; readonly text: string }
  | { readonly type: 'CLEAR_COMPOSER' }
  | { readonly type: 'RESET_CONVERSATION' }
  | { readonly type: 'SET_ACTIVE_CONVERSATION'; readonly key: string | null }
  | { readonly type: 'SET_VISUAL'; readonly visual: VisualBuilderState }
  | { readonly type: 'SET_ORIGIN'; readonly origin: AuthoringOrigin }
  | { readonly type: 'SET_NOTICE'; readonly notice: UiError | null }
  | { readonly type: 'CLEAR_UNSAVED' }
  | { readonly type: 'RESET'; readonly state: WorkspaceState }

function applySlice<T>(current: AsyncSlice<T>, action: { key: SliceKey; seq: number }): AsyncSlice<T> {
  if (action.seq < current.seq) {
    // A superseded response is ignored outright.
    return current
  }
  return { status: 'LOADING', value: current.value, error: null, seq: action.seq }
}

export function reducer(state: WorkspaceState, action: Action): WorkspaceState {
  switch (action.type) {
    case 'BEGIN': {
      const next: Record<SliceKey, AsyncSlice<unknown>> = {
        workspace: applySlice(state.workspace, action),
        plan: applySlice(state.plan, action),
        preview: applySlice(state.preview, action),
        simulation: applySlice(state.simulation, action),
        executions: applySlice(state.executions, action),
        participants: applySlice(state.participants, action),
        actions: applySlice(state.actions, action),
        preparation: applySlice(state.preparation, action),
        publish: applySlice(state.publish, action),
        apply: applySlice(state.apply, action),
        scheduleState: applySlice(state.scheduleState, action),
        actionOperation: applySlice(state.actionOperation, action),
        conversation: applySlice(state.conversation, action),
        history: applySlice(state.history, action),
      }
      return { ...state, [action.key]: next[action.key] } as WorkspaceState
    }
    case 'SUCCESS': {
      const current = state[action.key] as AsyncSlice<unknown>
      if (action.seq < current.seq) {
        return state
      }
      return {
        ...state,
        // A successful Apply means the edits are now the saved Draft.
        ...(action.key === 'apply' ? { dirty: false } : {}),
        [action.key]: { status: 'READY', value: action.value, error: null, seq: action.seq },
      } as WorkspaceState
    }
    case 'FAILURE': {
      const current = state[action.key] as AsyncSlice<unknown>
      if (action.seq < current.seq) {
        return state
      }
      // The previous value is kept so a failure never destroys local context.
      return {
        ...state,
        [action.key]: { status: 'ERROR', value: current.value, error: action.error, seq: action.seq },
      } as WorkspaceState
    }
    case 'SET_COMPOSER':
      // Typing is draft interaction state only: it never marks Campaign state dirty.
      return { ...state, composerText: action.text }
    case 'CLEAR_COMPOSER':
      return { ...state, composerText: '' }
    case 'RESET_CONVERSATION':
      // Only the conversation slice is discarded; the saved Draft, the last Plan
      // and the local Visual Builder edits are untouched.
      return {
        ...state,
        conversation: idle<ConversationProjection>(),
        activeConversationKey: null,
        composerText: '',
      }
    case 'SET_ACTIVE_CONVERSATION':
      return { ...state, activeConversationKey: action.key }
    case 'SET_VISUAL':
      return { ...state, visual: action.visual, dirty: true }
    case 'SET_ORIGIN':
      return { ...state, lastOrigin: action.origin, dirty: true }
    case 'SET_NOTICE':
      return { ...state, notice: action.notice }
    case 'CLEAR_UNSAVED':
      return {
        ...state,
        visual: DEFAULT_VISUAL_STATE,
        unsavedNonce: state.unsavedNonce + 1,
        dirty: false,
      }
    case 'RESET':
      return action.state
    default:
      return state
  }
}

export interface WorkspaceApi {
  readonly gateway: CampaignWorkspaceGateway
  readonly state: WorkspaceState
  readonly mode: ProposalMode
  readonly campaignId: string | null
  readonly permissions: ReadonlySet<CampaignPermission>
  readonly nowInstant: string
  readonly hasUnsavedEdits: boolean
  /** The current conversation projection, or null before the first turn. */
  readonly conversation: ConversationProjection | null
  /** The host-owned conversation history list, or null before it has been loaded. */
  readonly history: readonly ConversationSummary[] | null
  /** The identity key of the conversation this page is attached to, or null. */
  readonly activeConversationKey: string | null
  refreshWorkspace(): Promise<void>
  /** Loads the host-owned conversation history list of this trusted boundary. */
  loadConversationHistory(): Promise<readonly ConversationSummary[] | null>
  /** Opens one stored conversation and adopts it as the active page-session conversation. */
  openConversation(conversationId: string, runId: string): Promise<ConversationProjection | null>
  /** Starts a fresh server-owned conversation for the explicit New Campaign action. */
  startNewConversation(): Promise<CampaignConversationIdentity | null>
  compileVisualProposal(): Promise<CampaignPlanSummary | null>
  sendTurn(
    text: string,
    answers?: readonly CampaignClarificationAnswer[],
  ): Promise<ConversationProjection | null>
  answerClarification(
    optionId: string | null,
    freeText: string | null,
  ): Promise<ConversationProjection | null>
  /** Submits per-component clarification answers when multiple componentIssues are pending. */
  answerComponentClarifications(
    answers: readonly CampaignClarificationAnswer[],
  ): Promise<ConversationProjection | null>
  retryLastTurn(): Promise<ConversationProjection | null>
  createPlanFromConversation(): Promise<CampaignPlanSummary | null>
  resetConversation(): void
  setComposerText(text: string): void
  previewSegment(): Promise<SegmentPreviewSummary | null>
  simulate(): Promise<SimulationSummary | null>
  applyPlan(): Promise<ApplyResult | null>
  preparePublish(): Promise<PublishPreparation | null>
  publishPrepared(confirmations: readonly string[]): Promise<PublishResult | null>
  loadExecutions(campaignId: string): Promise<readonly ExecutionSummary[] | null>
  loadParticipants(executionId: string): Promise<readonly ParticipantSummary[] | null>
  loadActions(executionId: string): Promise<readonly ActionSummary[] | null>
  reconcileAction(actionId: string, expectedRevision: number): Promise<ActionOperationResult | null>
  safeRetryAction(actionId: string, expectedRevision: number): Promise<ActionOperationResult | null>
  pauseCampaign(campaignId: string, expectedStateRevision: number): Promise<ScheduleStateResult | null>
  resumeCampaign(campaignId: string, expectedStateRevision: number): Promise<ScheduleStateResult | null>
  setVisual(visual: VisualBuilderState): void
  discardUnsaved(): void
  resetFixture(): void
  setScenario(scenario: FixtureScenario): void
  dismissNotice(): void
}

const WorkspaceContext = createContext<WorkspaceApi | null>(null)

const EMPTY_PERMISSIONS: ReadonlySet<CampaignPermission> = new Set()

export function trustedBaseFor(snapshot: WorkspaceSnapshot, mode: ProposalMode): TrustedBase {
  return {
    catalogVersion: snapshot.pins.catalogVersion,
    catalogHash: snapshot.pins.catalogHash,
    policyVersion: snapshot.pins.policyVersion,
    policyHash: snapshot.pins.policyHash,
    campaignRevision: mode === 'EDIT' ? snapshot.campaign.revision : null,
    locale: snapshot.campaign.locale,
  }
}

/** The stable identity key of one server-owned conversation. */
export function conversationKeyOf(conversationId: string, runId: string): string {
  return `${conversationId}::${runId}`
}

export function proposalModeFor(snapshot: WorkspaceSnapshot | null): ProposalMode {
  return snapshot?.draft == null ? 'CREATE' : 'EDIT'
}

/**
 * The question code that owns a clarification's offered options, or null when it offers none.
 *
 * The wire contract has one flat option list and one flat question-code list, so it makes option
 * ownership explicit in the one place it can: whenever `options` is non-empty the first question
 * code is the owning question's code, and `skillId` is that same question's Skill. A clarification
 * with no options privileges no code and stays free-text answerable.
 */
export function owningQuestionCode(clarification: ConversationClarification): string | null {
  if (clarification.options.length === 0) {
    return null
  }
  return clarification.questionCodes[0] ?? null
}

/**
 * The bounded typed answers of one clarification submission.
 *
 * A chosen option is submitted under the owning question code only, never under another component's
 * question code, so an aggregate option can never be mis-attributed to the wrong component. Free
 * text is submitted under every outstanding question code that can accept it, which in a
 * multi-question aggregate excludes the option owner (it is answered by one of its own options) but
 * includes every other pending component question — so answering an aggregate never silently drops
 * the other outstanding component questions. A single-question clarification still produces exactly
 * the one answer it produced before: the single code with the option, the free text, or both.
 */
export function clarificationAnswers(
  clarification: ConversationClarification,
  optionId: string | null,
  freeText: string | null,
): readonly CampaignClarificationAnswer[] {
  const owner = owningQuestionCode(clarification)
  const multiple = clarification.questionCodes.length > 1
  const answers: CampaignClarificationAnswer[] = []
  if (optionId !== null && owner !== null) {
    answers.push({ code: owner, optionId, freeText: null })
  }
  if (freeText !== null) {
    for (const code of clarification.questionCodes) {
      if (code === owner && multiple) {
        continue
      }
      answers.push({ code, optionId: null, freeText })
    }
  }
  if (answers.length === 0) {
    // A defensive fallback for a submission that names neither an option nor free text: keep the
    // historical single-code answer instead of sending an empty answer list.
    answers.push({ code: clarification.questionCodes[0] ?? null, optionId, freeText })
  }
  return answers
}

/**
 * Builds one CampaignClarificationAnswer per outstanding ComponentIssue from the per-issue
 * selections a user made in the multi-component clarification UI.
 *
 * Each issue contributes exactly one answer — its first question code with the user's
 * chosen option or the user's own free text, together with the originating
 * `ComponentIssue.component` so the server can route the answer to exactly the
 * component that owns it. The caller never copies free text across component
 * boundaries: a Journey answer can never contain a Segment value and vice versa.
 * An issue that was not answered (no option selected and no non-empty free text) is
 * silently skipped.
 */
export function buildComponentIssueAnswers(
  issues: readonly ComponentIssue[],
  perIssue: ReadonlyMap<string, { optionId: string | null; freeText: string }>,
): readonly CampaignClarificationAnswer[] {
  const answers: CampaignClarificationAnswer[] = []
  for (const issue of issues) {
    const key = `${issue.component}-${issue.code}`
    const value = perIssue.get(key)
    if (value === undefined) {
      continue
    }
    const code = issue.questionCodes[0]
    if (code === undefined) {
      continue
    }
    if (value.optionId !== null) {
      answers.push({ code, optionId: value.optionId, freeText: null, component: issue.component })
    } else {
      const trimmed = value.freeText.trim()
      if (trimmed.length > 0) {
        answers.push({ code, optionId: null, freeText: trimmed, component: issue.component })
      }
    }
  }
  return answers
}

export function WorkspaceProvider({
  gateway,
  children,
}: {
  readonly gateway: CampaignWorkspaceGateway
  readonly children: ReactNode
}): JSX.Element {
  const [state, dispatch] = useReducer(reducer, undefined, createInitialState)
  const seqRef = useRef(0)
  const mutationRef = useRef(0)
  const lastTurnRef = useRef<{
    readonly message: string
    readonly answers: readonly CampaignClarificationAnswer[]
  } | null>(null)

  // A single per-provider-instance namespace so mutation keys are unique across
  // page reloads while staying stable for the lifetime of each call. The empty
  // deps array guarantees it runs once per mount in production; in Strict Mode
  // dev double-renders a second invocation is harmless — same prefix, different
  // namespace, no collision.
  const instanceNs = useMemo(() => generateInstanceNamespace(), [])

  const nextSeq = useCallback((): number => {
    seqRef.current += 1
    return seqRef.current
  }, [])

  const mutationKey = useCallback(
    (prefix: string): string => {
      mutationRef.current += 1
      return formatMutationKey(instanceNs, prefix, mutationRef.current)
    },
    [instanceNs],
  )

  const run = useCallback(
    async <T,>(
      key: SliceKey,
      call: (options: GatewayCallOptions) => Promise<T>,
      prefix: string,
      mutate: boolean,
    ): Promise<T | null> => {
      const seq = nextSeq()
      dispatch({ type: 'BEGIN', key, seq })
      const options: GatewayCallOptions = mutate
        ? { idempotencyKey: mutationKey(prefix) }
        : {}
      try {
        const value = await call(options)
        dispatch({ type: 'SUCCESS', key, seq, value })
        return value
      } catch (thrown) {
        dispatch({ type: 'FAILURE', key, seq, error: toUiError(thrown) })
        return null
      }
    },
    [nextSeq, mutationKey],
  )

  const mode = proposalModeFor(state.workspace.value)
  const campaignId = mode === 'EDIT' ? state.workspace.value?.campaign.campaignId ?? null : null

  const conversationRevision = state.conversation.value?.revision ?? 0

  /**
   * Sends one bounded conversation turn.
   *
   * The message is sent exactly as the user typed it. Nothing here parses it into
   * a directive and nothing here builds a Proposal. A failed turn dispatches a
   * FAILURE and keeps the composer text, so a transport, stale-revision,
   * permission or validation failure never loses the user's words.
   */
  const sendTurn = useCallback(
    async (
      text: string,
      answers: readonly CampaignClarificationAnswer[] = [],
    ): Promise<ConversationProjection | null> => {
      const trimmed = text.trim()
      if (trimmed.length === 0) {
        return null
      }
      // The first send waits for the one shared bootstrap; concurrent sends share it, so no
      // second conversation is ever created. A failed bootstrap stays retryable.
      const identity = await gateway.conversationIdentity()
      // The page is now attached to this server-owned conversation, so the history list can mark it
      // active even before the first turn is accepted.
      dispatch({
        type: 'SET_ACTIVE_CONVERSATION',
        key: conversationKeyOf(identity.conversationId, identity.runId),
      })
      const request: CampaignTurnRequest = {
        conversationId: identity.conversationId,
        runId: identity.runId,
        expectedRevision: conversationRevision,
        message: trimmed,
        answers,
        // Presentation only: the user's own instant stamps the transcript turn.
        at: new Date().toISOString(),
      }
      lastTurnRef.current = { message: trimmed, answers }
      const result = await run(
        'conversation',
        (options) => gateway.sendCampaignTurn(request, options),
        'turn',
        true,
      )
      if (result !== null && result.accepted) {
        dispatch({ type: 'CLEAR_COMPOSER' })
        // The host now holds an updated conversation, so the real history list is re-read.
        void run('history', (options) => gateway.listConversations(options), 'history', false)
      }
      return result
    },
    [gateway, conversationRevision, run],
  )

  const api = useMemo<WorkspaceApi>(() => {
    const refreshWorkspace = async (): Promise<void> => {
      await run('workspace', (options) => gateway.getWorkspace(options), 'workspace', false)
    }
    return {
      gateway,
      state,
      mode,
      campaignId,
      permissions: state.workspace.value?.permissions ?? EMPTY_PERMISSIONS,
      nowInstant: gateway.nowInstant,
      hasUnsavedEdits: state.dirty,
      conversation: state.conversation.value,
      history: state.history.value,
      activeConversationKey: state.activeConversationKey,
      refreshWorkspace,
      loadConversationHistory: async (): Promise<readonly ConversationSummary[] | null> => {
        return await run('history', (options) => gateway.listConversations(options), 'history', false)
      },
      openConversation: async (
        conversationId: string,
        runId: string,
      ): Promise<ConversationProjection | null> => {
        // The gateway adopts the selected identity, so a later turn continues this conversation.
        const projection = await run(
          'conversation',
          (options) => gateway.readConversation(conversationId, runId, options),
          'read',
          false,
        )
        if (projection !== null) {
          lastTurnRef.current = null
          dispatch({
            type: 'SET_ACTIVE_CONVERSATION',
            key: conversationKeyOf(projection.conversationId, projection.runId),
          })
          dispatch({ type: 'CLEAR_COMPOSER' })
        }
        return projection
      },
      startNewConversation: async (): Promise<CampaignConversationIdentity | null> => {
        try {
          const identity = await gateway.startNewConversation({})
          lastTurnRef.current = null
          dispatch({ type: 'RESET_CONVERSATION' })
          dispatch({
            type: 'SET_ACTIVE_CONVERSATION',
            key: conversationKeyOf(identity.conversationId, identity.runId),
          })
          // The new server-owned conversation must appear in the real history list.
          await run('history', (options) => gateway.listConversations(options), 'history', false)
          return identity
        } catch (thrown) {
          dispatch({ type: 'SET_NOTICE', notice: toUiError(thrown) })
          return null
        }
      },
      compileVisualProposal: async (): Promise<CampaignPlanSummary | null> => {
        const snapshot = state.workspace.value
        if (snapshot === null) {
          return null
        }
        const currentMode = proposalModeFor(snapshot)
        const currentCampaignId = currentMode === 'EDIT' ? snapshot.campaign.campaignId : null
        const intent = buildVisualIntent(state.visual, currentMode, currentCampaignId)
        const submission: AuthoringSubmission = {
          origin: 'VISUAL_BUILDER',
          intent,
          base: trustedBaseFor(snapshot, currentMode),
        }
        dispatch({ type: 'SET_ORIGIN', origin: 'VISUAL_BUILDER' })
        return await run(
          'plan',
          (options) => submitAuthoring(gateway, submission, options),
          'plan',
          true,
        )
      },
      sendTurn,
      answerClarification: async (
        optionId: string | null,
        freeText: string | null,
      ): Promise<ConversationProjection | null> => {
        const pending = state.conversation.value?.clarification ?? null
        if (pending === null) {
          return null
        }
        const option = pending.options.find((candidate) => candidate.optionId === optionId) ?? null
        const answers = clarificationAnswers(pending, optionId, freeText)
        const message = option?.label ?? freeText ?? 'Please use the offered choice.'
        return await sendTurn(message, answers)
      },
      answerComponentClarifications: async (
        answers: readonly CampaignClarificationAnswer[],
      ): Promise<ConversationProjection | null> => {
        if (answers.length === 0) {
          return null
        }
        // A readable transcript message from the per-component answers: each answer
        // contributes its own free text or selected option id, never another
        // component's value.
        const parts = answers
          .map((a) => a.freeText ?? a.optionId ?? '')
          .filter((s) => s.length > 0)
        const message =
          parts.length > 0 ? parts.join('; ') : 'Please use the offered choice.'
        return await sendTurn(message, answers)
      },
      retryLastTurn: async (): Promise<ConversationProjection | null> => {
        const last = lastTurnRef.current
        if (last === null) {
          return null
        }
        return await sendTurn(last.message, last.answers)
      },
      createPlanFromConversation: async (): Promise<CampaignPlanSummary | null> => {
        // Only a host-provided Proposal can reach the Plan action. Assistant prose
        // alone never calls compileProposal.
        const proposal = state.conversation.value?.proposal ?? null
        if (proposal === null) {
          return null
        }
        dispatch({ type: 'SET_ORIGIN', origin: 'AI_CONVERSATION' })
        return await run('plan', (options) => gateway.compileProposal(proposal, options), 'plan', true)
      },
      resetConversation: (): void => {
        if (isFixtureGateway(gateway)) {
          gateway.resetConversation()
        }
        lastTurnRef.current = null
        dispatch({ type: 'RESET_CONVERSATION' })
      },
      setComposerText: (text: string): void => {
        dispatch({ type: 'SET_COMPOSER', text })
      },
      previewSegment: async (): Promise<SegmentPreviewSummary | null> => {
        const plan = state.plan.value
        if (plan === null) {
          return null
        }
        return await run('preview', (options) => gateway.previewSegment(plan.planRef, options), 'preview', true)
      },
      simulate: async (): Promise<SimulationSummary | null> => {
        const plan = state.plan.value
        if (plan === null) {
          return null
        }
        return await run('simulation', (options) => gateway.simulate(plan.planRef, options), 'simulate', true)
      },
      applyPlan: async (): Promise<ApplyResult | null> => {
        const plan = state.plan.value
        if (plan === null) {
          return null
        }
        const result = await run('apply', (options) => gateway.applyPlan(plan.planRef, options), 'apply', true)
        if (result !== null) {
          // The saved Draft moved, so the workspace projection is re-read.
          await run('workspace', (options) => gateway.getWorkspace(options), 'workspace', false)
        }
        return result
      },
      preparePublish: async (): Promise<PublishPreparation | null> => {
        const snapshot = state.workspace.value
        const draft = snapshot?.draft
        if (snapshot == null || draft == null) {
          return null
        }
        return await run(
          'preparation',
          (options) =>
            gateway.preparePublish(
              {
                campaignId: snapshot.campaign.campaignId,
                draftId: draft.draftId,
                draftRevision: draft.draftRevision,
              },
              options,
            ),
          'prepare',
          true,
        )
      },
      publishPrepared: async (confirmations: readonly string[]): Promise<PublishResult | null> => {
        const preparation = state.preparation.value
        if (preparation === null) {
          return null
        }
        const result = await run(
          'publish',
          (options) => {
            const idempotencyKey = options.idempotencyKey
            if (idempotencyKey === undefined) {
              throw new UiError({
                code: UI_ERROR_CODES.CONTRACT_VALUE_INVALID,
                message: fixedMessage(UI_ERROR_CODES.CONTRACT_VALUE_INVALID),
                details: { reason: 'mutation-requires-idempotency-key' },
              })
            }
            return gateway.publish(
              {
                preparationId: preparation.preparationId,
                preparationHash: preparation.preparationHash,
                idempotencyKey,
                confirmations: [...confirmations],
              },
              options,
            )
          },
          'publish',
          true,
        )
        if (result !== null) {
          await run('workspace', (options) => gateway.getWorkspace(options), 'workspace', false)
        }
        return result
      },
      loadExecutions: async (id: string): Promise<readonly ExecutionSummary[] | null> => {
        return await run('executions', (options) => gateway.listExecutions(id, options), 'executions', false)
      },
      loadParticipants: async (executionId: string): Promise<readonly ParticipantSummary[] | null> => {
        return await run(
          'participants',
          (options) => gateway.listParticipants(executionId, options),
          'participants',
          false,
        )
      },
      loadActions: async (executionId: string): Promise<readonly ActionSummary[] | null> => {
        return await run('actions', (options) => gateway.listActions(executionId, options), 'actions', false)
      },
      reconcileAction: async (actionId: string, expectedRevision: number): Promise<ActionOperationResult | null> => {
        return await run(
          'actionOperation',
          (options) => gateway.reconcileAction(actionId, expectedRevision, options),
          'reconcile',
          true,
        )
      },
      safeRetryAction: async (actionId: string, expectedRevision: number): Promise<ActionOperationResult | null> => {
        return await run(
          'actionOperation',
          (options) => gateway.safeRetryAction(actionId, expectedRevision, options),
          'retry',
          true,
        )
      },
      pauseCampaign: async (id: string, expectedStateRevision: number): Promise<ScheduleStateResult | null> => {
        return await run(
          'scheduleState',
          (options) => gateway.pauseCampaign(id, expectedStateRevision, options),
          'pause',
          true,
        )
      },
      resumeCampaign: async (id: string, expectedStateRevision: number): Promise<ScheduleStateResult | null> => {
        return await run(
          'scheduleState',
          (options) => gateway.resumeCampaign(id, expectedStateRevision, options),
          'resume',
          true,
        )
      },
      setVisual: (visual: VisualBuilderState): void => {
        dispatch({ type: 'SET_VISUAL', visual })
      },
      discardUnsaved: (): void => {
        dispatch({ type: 'CLEAR_UNSAVED' })
      },
      resetFixture: (): void => {
        if (isFixtureGateway(gateway)) {
          gateway.resetFixture()
          dispatch({ type: 'RESET', state: createInitialState() })
          // The fixture changed underneath the view, so the projections are re-read.
          void refreshWorkspace()
        }
      },
      setScenario: (scenario: FixtureScenario): void => {
        if (isFixtureGateway(gateway)) {
          gateway.setScenario(scenario)
          dispatch({ type: 'RESET', state: createInitialState() })
          void refreshWorkspace()
        }
      },
      dismissNotice: (): void => {
        dispatch({ type: 'SET_NOTICE', notice: null })
      },
    }
  }, [gateway, state, mode, campaignId, run, mutationKey, sendTurn])

  return <WorkspaceContext.Provider value={api}>{children}</WorkspaceContext.Provider>
}

/**
 * Generates a per-provider-instance unique namespace so all mutation keys remain
 * unique across page reloads while staying stable for the lifetime of the
 * individual call.
 *
 * The primary path delegates to {@link crypto.randomUUID}, which produces a
 * standard hex-hyphen UUID. A safe fallback via `crypto.getRandomValues` is
 * available for environments that expose `crypto` without `randomUUID`, and a
 * last-resort fallback covers environments without `crypto` at all.
 *
 * The result conforms to {@link REFERENCE_ID_PATTERN}: it starts with an
 * alphanumeric character and contains only `[A-Za-z0-9._:-]`.
 */
export function generateInstanceNamespace(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  if (typeof crypto !== 'undefined' && typeof crypto.getRandomValues === 'function') {
    const array = new Uint32Array(4)
    crypto.getRandomValues(array)
    return Array.from(array, (n) => n.toString(16).padStart(8, '0')).join('-')
  }
  // Last-resort fallback: timestamp + Math.random.
  return `ns-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

/**
 * Formats a stable idempotency key from a provider-instance namespace, the
 * mutation prefix, and an incrementing call counter.
 *
 * The key is guaranteed to match {@link REFERENCE_ID_PATTERN} and is bounded
 * well within the 128-character limit.
 */
export function formatMutationKey(namespace: string, prefix: string, counter: number): string {
  return `${prefix}-${namespace}-${counter}`
}

// ---------------------------------------------------------------------------
// Internal helpers (not exported)
// ---------------------------------------------------------------------------

function toUiError(thrown: unknown): UiError {
  if (isUiError(thrown)) {
    return thrown
  }
  return new UiError({
    code: UI_ERROR_CODES.GATEWAY_UNEXPECTED,
    message: fixedMessage(UI_ERROR_CODES.GATEWAY_UNEXPECTED),
  })
}

export function useWorkspace(): WorkspaceApi {
  const api = useContext(WorkspaceContext)
  if (api === null) {
    throw new UiError({
      code: UI_ERROR_CODES.GATEWAY_UNEXPECTED,
      message: fixedMessage(UI_ERROR_CODES.GATEWAY_UNEXPECTED),
      details: { reason: 'workspace-provider-missing' },
    })
  }
  return api
}
