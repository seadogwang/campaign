/**
 * Execution, Participant, Node and Action result projections (accepted T22/T23).
 *
 * Every accepted status is represented, including `UNKNOWN`, `VERIFYING` and
 * `NEEDS_REVIEW`. Reconcile and safe retry are distinct operations, and safe
 * retry is only offered when the server says definitive non-application
 * evidence exists and attempt limits permit it.
 */

import { UI_ERROR_CODES, contractError } from './errors'
import {
  ISO_INSTANT_PATTERN,
  MACHINE_CODE_PATTERN,
  REFERENCE_ID_PATTERN,
} from './primitives'
import {
  field,
  optionalField,
  requireBoolean,
  requireEnumValue,
  requireExactFields,
  requireNonNegativeInteger,
  requireObject,
  requirePattern,
  requireString,
  requireStringArray,
  type JsonValue,
} from './validate'

export const EXECUTION_STATUSES = [
  'CREATED',
  'EVALUATING',
  'RUNNING',
  'COMPLETED',
  'PARTIAL_FAILED',
  'FAILED',
  'NEEDS_REVIEW',
] as const
export type ExecutionStatus = (typeof EXECUTION_STATUSES)[number]

export const PARTICIPANT_STATUSES = [
  'CREATED',
  'RUNNING',
  'WAITING',
  'COMPLETED',
  'EXITED',
  'FAILED',
  'NEEDS_REVIEW',
] as const
export type ParticipantStatus = (typeof PARTICIPANT_STATUSES)[number]

/** The exact accepted action status set, including the three unresolved members. */
export const ACTION_STATUSES = [
  'RESERVED',
  'IN_FLIGHT',
  'UNKNOWN',
  'VERIFYING',
  'SUCCEEDED',
  'DEFINITELY_FAILED',
  'NEEDS_REVIEW',
] as const
export type ActionStatus = (typeof ACTION_STATUSES)[number]

export const ACTION_KINDS = ['MESSAGE', 'REWARD'] as const
export type ActionKind = (typeof ACTION_KINDS)[number]

export const PROVIDER_OUTCOMES = ['SUCCEEDED', 'DEFINITELY_NOT_APPLIED', 'PENDING', 'UNKNOWN'] as const
export type ProviderOutcome = (typeof PROVIDER_OUTCOMES)[number]

export const BUDGET_STATES = ['HELD', 'FINALIZED', 'RELEASED'] as const
export type BudgetState = (typeof BUDGET_STATES)[number]

export const ACTION_DISPOSITIONS = [
  'RESERVED',
  'DUPLICATE',
  'SUCCEEDED',
  'DEFINITELY_FAILED',
  'UNKNOWN_VERIFYING',
  'SAFE_RETRY_SCHEDULED',
  'VERIFICATION_SCHEDULED',
  'NEEDS_REVIEW',
  'DUPLICATE_COMPLETION',
] as const
export type ActionDisposition = (typeof ACTION_DISPOSITIONS)[number]

/** Statuses that must be surfaced as strong, unresolved callouts. */
export const UNRESOLVED_ACTION_STATUSES: readonly ActionStatus[] = ['UNKNOWN', 'VERIFYING', 'NEEDS_REVIEW']
export const TERMINAL_ACTION_STATUSES: readonly ActionStatus[] = ['SUCCEEDED', 'DEFINITELY_FAILED', 'NEEDS_REVIEW']

export interface ExecutionCountersSummary {
  readonly completedCount: number
  readonly exitedCount: number
  readonly failedCount: number
  readonly needsReviewCount: number
  readonly waitingCount: number
  readonly runningCount: number
}

export interface ExecutionSummary {
  readonly executionId: string
  readonly executionKey: string
  readonly campaignId: string
  readonly status: ExecutionStatus
  readonly candidateCount: number
  readonly enrolledCount: number
  readonly revision: number
  readonly nominalInstant: string
  readonly dueInstant: string
  readonly createdAt: string
  readonly updatedAt: string
  readonly counters: ExecutionCountersSummary
}

export interface ParticipantSummary {
  readonly participantId: string
  readonly executionId: string
  readonly currentNodeLogicalId: string
  readonly status: ParticipantStatus
  readonly waitingReason: string | null
  readonly waitingDeadline: string | null
  readonly lastAcceptedActionInstant: string | null
  readonly revision: number
  readonly updatedAt: string
  readonly terminal: boolean
  readonly unresolved: boolean
}

export interface ActionEvidence {
  readonly referencePresent: boolean
  readonly reasonCode: string | null
}

export interface RetryEligibility {
  readonly safeRetryPermitted: boolean
  readonly definitiveNonApplication: boolean
  readonly submissionAttemptsRemain: boolean
  readonly reasonCodes: readonly string[]
}

export interface ActionSummary {
  readonly actionId: string
  readonly executionId: string
  readonly participantId: string
  readonly nodeLogicalId: string
  readonly actionKind: ActionKind
  readonly status: ActionStatus
  readonly reasonCode: string | null
  readonly submissionCount: number
  readonly verificationCount: number
  readonly acceptedInstant: string | null
  readonly revision: number
  readonly updatedAt: string
  readonly idempotencySafe: boolean
  readonly providerOutcome: ProviderOutcome | null
  readonly providerEvidence: ActionEvidence
  readonly budgetState: BudgetState
  readonly retryEligibility: RetryEligibility
}

export interface ScheduleStateResult {
  readonly campaignId: string
  readonly stateRevision: number
  readonly status: string
  readonly updatedAt: string
}

export interface ActionOperationResult {
  readonly actionId: string
  readonly status: ActionStatus
  readonly disposition: ActionDisposition
  readonly revision: number
  readonly updatedAt: string
}

export const MAX_EXECUTIONS = 100
export const MAX_PARTICIPANTS = 500
export const MAX_ACTION_ROWS = 500
export const MAX_REASON_CODES = 8

const EXECUTION_FIELDS = [
  'executionId',
  'executionKey',
  'campaignId',
  'status',
  'candidateCount',
  'enrolledCount',
  'revision',
  'nominalInstant',
  'dueInstant',
  'createdAt',
  'updatedAt',
  'counters',
] as const
const COUNTER_FIELDS = [
  'completedCount',
  'exitedCount',
  'failedCount',
  'needsReviewCount',
  'waitingCount',
  'runningCount',
] as const
const PARTICIPANT_FIELDS = [
  'participantId',
  'executionId',
  'currentNodeLogicalId',
  'status',
  'waitingReason',
  'waitingDeadline',
  'lastAcceptedActionInstant',
  'revision',
  'updatedAt',
] as const
const EVIDENCE_FIELDS = ['referencePresent', 'reasonCode'] as const
const RETRY_FIELDS = [
  'safeRetryPermitted',
  'definitiveNonApplication',
  'submissionAttemptsRemain',
  'reasonCodes',
] as const
const ACTION_FIELDS = [
  'actionId',
  'executionId',
  'participantId',
  'nodeLogicalId',
  'actionKind',
  'status',
  'reasonCode',
  'submissionCount',
  'verificationCount',
  'acceptedInstant',
  'revision',
  'updatedAt',
  'idempotencySafe',
  'providerOutcome',
  'providerEvidence',
  'budgetState',
  'retryEligibility',
] as const
const SCHEDULE_STATE_FIELDS = ['campaignId', 'stateRevision', 'status', 'updatedAt'] as const
const OPERATION_RESULT_FIELDS = ['actionId', 'status', 'disposition', 'revision', 'updatedAt'] as const

export function isTerminalActionStatus(status: ActionStatus): boolean {
  return TERMINAL_ACTION_STATUSES.includes(status)
}

export function isUnresolvedActionStatus(status: ActionStatus): boolean {
  return UNRESOLVED_ACTION_STATUSES.includes(status)
}

function decodeCounters(raw: JsonValue, path: string): ExecutionCountersSummary {
  const obj = requireObject(raw, path)
  requireExactFields(obj, COUNTER_FIELDS, path)
  return {
    completedCount: requireNonNegativeInteger(field(obj, 'completedCount', path), `${path}.completedCount`),
    exitedCount: requireNonNegativeInteger(field(obj, 'exitedCount', path), `${path}.exitedCount`),
    failedCount: requireNonNegativeInteger(field(obj, 'failedCount', path), `${path}.failedCount`),
    needsReviewCount: requireNonNegativeInteger(field(obj, 'needsReviewCount', path), `${path}.needsReviewCount`),
    waitingCount: requireNonNegativeInteger(field(obj, 'waitingCount', path), `${path}.waitingCount`),
    runningCount: requireNonNegativeInteger(field(obj, 'runningCount', path), `${path}.runningCount`),
  }
}

export function decodeExecutionSummary(raw: JsonValue, path: string): ExecutionSummary {
  const obj = requireObject(raw, path)
  requireExactFields(obj, EXECUTION_FIELDS, path)
  const counters = decodeCounters(field(obj, 'counters', path), `${path}.counters`)
  const enrolledCount = requireNonNegativeInteger(field(obj, 'enrolledCount', path), `${path}.enrolledCount`)
  const terminalTotal = counters.completedCount + counters.exitedCount + counters.failedCount + counters.needsReviewCount
  if (terminalTotal > enrolledCount || counters.waitingCount + counters.runningCount > enrolledCount) {
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, `${path}.counters`, 'counter-invariant')
  }
  return {
    executionId: requirePattern(field(obj, 'executionId', path), REFERENCE_ID_PATTERN, `${path}.executionId`, 'reference-id'),
    executionKey: requireString(field(obj, 'executionKey', path), `${path}.executionKey`),
    campaignId: requirePattern(field(obj, 'campaignId', path), REFERENCE_ID_PATTERN, `${path}.campaignId`, 'reference-id'),
    status: requireEnumValue(field(obj, 'status', path), EXECUTION_STATUSES, `${path}.status`),
    candidateCount: requireNonNegativeInteger(field(obj, 'candidateCount', path), `${path}.candidateCount`),
    enrolledCount,
    revision: requireNonNegativeInteger(field(obj, 'revision', path), `${path}.revision`),
    nominalInstant: requirePattern(field(obj, 'nominalInstant', path), ISO_INSTANT_PATTERN, `${path}.nominalInstant`, 'instant'),
    dueInstant: requirePattern(field(obj, 'dueInstant', path), ISO_INSTANT_PATTERN, `${path}.dueInstant`, 'instant'),
    createdAt: requirePattern(field(obj, 'createdAt', path), ISO_INSTANT_PATTERN, `${path}.createdAt`, 'instant'),
    updatedAt: requirePattern(field(obj, 'updatedAt', path), ISO_INSTANT_PATTERN, `${path}.updatedAt`, 'instant'),
    counters,
  }
}

export function decodeParticipantSummary(raw: JsonValue, path: string): ParticipantSummary {
  const obj = requireObject(raw, path)
  requireExactFields(obj, PARTICIPANT_FIELDS, path)
  const status = requireEnumValue(field(obj, 'status', path), PARTICIPANT_STATUSES, `${path}.status`)
  const waitingReason = optionalPatternOrNull(optionalField(obj, 'waitingReason'), `${path}.waitingReason`)
  const waitingDeadline = optionalInstant(optionalField(obj, 'waitingDeadline'), `${path}.waitingDeadline`)
  if ((waitingReason === null) !== (waitingDeadline === null)) {
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, path, 'wait-pair')
  }
  if (status === 'WAITING' && waitingReason === null) {
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, `${path}.waitingReason`, 'waiting-requires-reason')
  }
  return {
    participantId: requirePattern(field(obj, 'participantId', path), REFERENCE_ID_PATTERN, `${path}.participantId`, 'reference-id'),
    executionId: requirePattern(field(obj, 'executionId', path), REFERENCE_ID_PATTERN, `${path}.executionId`, 'reference-id'),
    currentNodeLogicalId: requirePattern(
      field(obj, 'currentNodeLogicalId', path),
      REFERENCE_ID_PATTERN,
      `${path}.currentNodeLogicalId`,
      'reference-id',
    ),
    status,
    waitingReason,
    waitingDeadline,
    lastAcceptedActionInstant: optionalInstant(
      optionalField(obj, 'lastAcceptedActionInstant'),
      `${path}.lastAcceptedActionInstant`,
    ),
    revision: requireNonNegativeInteger(field(obj, 'revision', path), `${path}.revision`),
    updatedAt: requirePattern(field(obj, 'updatedAt', path), ISO_INSTANT_PATTERN, `${path}.updatedAt`, 'instant'),
    terminal: isTerminalParticipantStatus(status),
    unresolved: !isTerminalParticipantStatus(status),
  }
}

function isTerminalParticipantStatus(status: ParticipantStatus): boolean {
  return status === 'COMPLETED' || status === 'EXITED' || status === 'FAILED' || status === 'NEEDS_REVIEW'
}

function optionalInstant(value: JsonValue | undefined, path: string): string | null {
  return value === undefined || value === null
    ? null
    : requirePattern(value, ISO_INSTANT_PATTERN, path, 'instant')
}

function optionalPatternOrNull(value: JsonValue | undefined, path: string): string | null {
  return value === undefined || value === null
    ? null
    : requirePattern(value, MACHINE_CODE_PATTERN, path, 'machine-code')
}

function decodeEvidence(raw: JsonValue, path: string): ActionEvidence {
  const obj = requireObject(raw, path)
  requireExactFields(obj, EVIDENCE_FIELDS, path)
  return {
    referencePresent: requireBoolean(field(obj, 'referencePresent', path), `${path}.referencePresent`),
    reasonCode: optionalPatternOrNull(optionalField(obj, 'reasonCode'), `${path}.reasonCode`),
  }
}

function decodeRetryEligibility(raw: JsonValue, path: string): RetryEligibility {
  const obj = requireObject(raw, path)
  requireExactFields(obj, RETRY_FIELDS, path)
  const reasonCodes = requireStringArray(field(obj, 'reasonCodes', path), `${path}.reasonCodes`, MAX_REASON_CODES)
  reasonCodes.forEach((code, index) =>
    requirePattern(code, MACHINE_CODE_PATTERN, `${path}.reasonCodes[${index}]`, 'machine-code'),
  )
  const safeRetryPermitted = requireBoolean(field(obj, 'safeRetryPermitted', path), `${path}.safeRetryPermitted`)
  const definitiveNonApplication = requireBoolean(
    field(obj, 'definitiveNonApplication', path),
    `${path}.definitiveNonApplication`,
  )
  const submissionAttemptsRemain = requireBoolean(
    field(obj, 'submissionAttemptsRemain', path),
    `${path}.submissionAttemptsRemain`,
  )
  if (safeRetryPermitted && (!definitiveNonApplication || !submissionAttemptsRemain)) {
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, `${path}.safeRetryPermitted`, 'safe-retry-eligibility')
  }
  return { safeRetryPermitted, definitiveNonApplication, submissionAttemptsRemain, reasonCodes }
}

export function decodeActionSummary(raw: JsonValue, path: string): ActionSummary {
  const obj = requireObject(raw, path)
  requireExactFields(obj, ACTION_FIELDS, path)
  const status = requireEnumValue(field(obj, 'status', path), ACTION_STATUSES, `${path}.status`)
  const providerOutcomeRaw = optionalField(obj, 'providerOutcome')
  const providerOutcome = providerOutcomeRaw === undefined || providerOutcomeRaw === null
    ? null
    : requireEnumValue(providerOutcomeRaw, PROVIDER_OUTCOMES, `${path}.providerOutcome`)
  const idempotencySafe = requireBoolean(field(obj, 'idempotencySafe', path), `${path}.idempotencySafe`)
  if (!idempotencySafe) {
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, `${path}.idempotencySafe`, 'idempotency-safe')
  }
  const retryEligibility = decodeRetryEligibility(
    field(obj, 'retryEligibility', path),
    `${path}.retryEligibility`,
  )
  if (retryEligibility.safeRetryPermitted && isUnresolvedActionStatus(status) && status !== 'VERIFYING') {
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, path, 'retry-not-permitted-unresolved')
  }
  return {
    actionId: requirePattern(field(obj, 'actionId', path), REFERENCE_ID_PATTERN, `${path}.actionId`, 'reference-id'),
    executionId: requirePattern(field(obj, 'executionId', path), REFERENCE_ID_PATTERN, `${path}.executionId`, 'reference-id'),
    participantId: requirePattern(field(obj, 'participantId', path), REFERENCE_ID_PATTERN, `${path}.participantId`, 'reference-id'),
    nodeLogicalId: requirePattern(field(obj, 'nodeLogicalId', path), REFERENCE_ID_PATTERN, `${path}.nodeLogicalId`, 'reference-id'),
    actionKind: requireEnumValue(field(obj, 'actionKind', path), ACTION_KINDS, `${path}.actionKind`),
    status,
    reasonCode: optionalPatternOrNull(optionalField(obj, 'reasonCode'), `${path}.reasonCode`),
    submissionCount: requireNonNegativeInteger(field(obj, 'submissionCount', path), `${path}.submissionCount`),
    verificationCount: requireNonNegativeInteger(field(obj, 'verificationCount', path), `${path}.verificationCount`),
    acceptedInstant: optionalInstant(optionalField(obj, 'acceptedInstant'), `${path}.acceptedInstant`),
    revision: requireNonNegativeInteger(field(obj, 'revision', path), `${path}.revision`),
    updatedAt: requirePattern(field(obj, 'updatedAt', path), ISO_INSTANT_PATTERN, `${path}.updatedAt`, 'instant'),
    idempotencySafe,
    providerOutcome,
    providerEvidence: decodeEvidence(field(obj, 'providerEvidence', path), `${path}.providerEvidence`),
    budgetState: requireEnumValue(field(obj, 'budgetState', path), BUDGET_STATES, `${path}.budgetState`),
    retryEligibility,
  }
}

export function decodeScheduleStateResult(raw: unknown, path: string): ScheduleStateResult {
  const obj = requireObject(raw, path)
  requireExactFields(obj, SCHEDULE_STATE_FIELDS, path)
  return {
    campaignId: requirePattern(field(obj, 'campaignId', path), REFERENCE_ID_PATTERN, `${path}.campaignId`, 'reference-id'),
    stateRevision: requireNonNegativeInteger(field(obj, 'stateRevision', path), `${path}.stateRevision`),
    status: requireString(field(obj, 'status', path), `${path}.status`),
    updatedAt: requirePattern(field(obj, 'updatedAt', path), ISO_INSTANT_PATTERN, `${path}.updatedAt`, 'instant'),
  }
}

export function decodeActionOperationResult(raw: unknown, path: string): ActionOperationResult {
  const obj = requireObject(raw, path)
  requireExactFields(obj, OPERATION_RESULT_FIELDS, path)
  const disposition = requireEnumValue(field(obj, 'disposition', path), ACTION_DISPOSITIONS, `${path}.disposition`)
  return {
    actionId: requirePattern(field(obj, 'actionId', path), REFERENCE_ID_PATTERN, `${path}.actionId`, 'reference-id'),
    status: requireEnumValue(field(obj, 'status', path), ACTION_STATUSES, `${path}.status`),
    disposition,
    revision: requireNonNegativeInteger(field(obj, 'revision', path), `${path}.revision`),
    updatedAt: requirePattern(field(obj, 'updatedAt', path), ISO_INSTANT_PATTERN, `${path}.updatedAt`, 'instant'),
  }
}

/** Human-safe label for a raw member identifier: the UI never renders one. */
export function participantLabel(participantId: string): string {
  return `participant ${participantId.slice(0, 8)}`
}

export interface ExecutionAggregate {
  readonly executed: number
  readonly unresolved: number
}

export function executionAggregate(execution: ExecutionSummary): ExecutionAggregate {
  const counters = execution.counters
  return {
    executed: counters.completedCount + counters.exitedCount + counters.failedCount,
    unresolved: counters.needsReviewCount + counters.waitingCount + counters.runningCount,
  }
}

export function countUnresolvedActions(actions: readonly ActionSummary[]): number {
  return actions.filter((action) => isUnresolvedActionStatus(action.status)).length
}