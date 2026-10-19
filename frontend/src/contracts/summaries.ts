/**
 * Campaign, Draft, Message, Promotion, Journey, Schedule and Governance
 * summaries. Bounded projections of accepted capability contracts — never a
 * full capability payload and never an executable record.
 */

import { UI_ERROR_CODES, contractError } from './errors'
import {
  CONTENT_HASH_PATTERN,
  ISO_INSTANT_PATTERN,
  MACHINE_CODE_PATTERN,
  REFERENCE_ID_PATTERN,
  type ContentHash,
} from './primitives'
import {
  field,
  optionalField,
  optionalPattern,
  optionalString,
  requireEnumValue,
  requireExactFields,
  requireInteger,
  requireNonNegativeInteger,
  requireObject,
  requireObjectArray,
  requirePattern,
  requireString,
  requireStringArray,
  type JsonValue,
} from './validate'
import { READINESS_STATES, type ReadinessState } from './plan'

export const CAMPAIGN_STATUSES = ['DRAFT', 'ACTIVE', 'PAUSED', 'COMPLETED', 'ARCHIVED'] as const
export type CampaignStatus = (typeof CAMPAIGN_STATUSES)[number]

export const SCHEDULE_FORMS = ['IMMEDIATE', 'ONCE', 'DAILY'] as const
export type ScheduleForm = (typeof SCHEDULE_FORMS)[number]

export const SCHEDULE_STATUSES = ['ACTIVE', 'PAUSED'] as const
export type ScheduleStatus = (typeof SCHEDULE_STATUSES)[number]

export const MESSAGE_CHANNELS = ['EMAIL', 'SMS', 'PUSH', 'IN_APP'] as const
export type MessageChannel = (typeof MESSAGE_CHANNELS)[number]

export const GOVERNANCE_APPROVAL_STATES = ['NOT_REQUIRED', 'PENDING', 'APPROVED', 'REJECTED'] as const
export type GovernanceApprovalState = (typeof GOVERNANCE_APPROVAL_STATES)[number]

export interface CampaignSummary {
  readonly campaignId: string
  readonly name: string
  readonly status: CampaignStatus
  readonly revision: number
  readonly timezone: string
  readonly locale: string
}

export interface DraftSummary {
  readonly draftId: string
  readonly draftRevision: number
  readonly campaignRevision: number
  readonly manifestHash: ContentHash
  readonly savedAt: string
}

export interface MessageSummary {
  readonly logicalId: string
  readonly capabilityCode: string
  readonly channel: MessageChannel
  readonly locale: string
  readonly contentPresent: boolean
  readonly status: string
}

export interface PromotionSummary {
  readonly logicalId: string
  readonly capabilityCode: string
  readonly benefitKind: string
  readonly budgetState: string
  readonly status: string
}

export interface JourneySummary {
  readonly logicalId: string
  readonly capabilityCode: string
  readonly entryNodeLogicalId: string
  readonly nodeCount: number
  readonly maxStepsPerPath: number
  readonly status: string
}

export interface ScheduleSummary {
  readonly logicalId: string
  readonly capabilityCode: string
  readonly form: ScheduleForm
  readonly zoneId: string
  readonly runAt: string | null
  readonly localTime: string | null
  readonly startDate: string | null
  readonly endDate: string | null
  readonly status: ScheduleStatus
  readonly nextOccurrence: string | null
}

export interface CapabilityReadiness {
  readonly capabilityId: string
  readonly state: ReadinessState
  readonly reasonCodes: readonly string[]
}

export interface GovernanceSummary {
  readonly logicalId: string
  readonly capabilityCode: string
  readonly approvalState: GovernanceApprovalState
  readonly requiredRiskCodes: readonly string[]
  readonly capabilities: readonly CapabilityReadiness[]
}

const CAMPAIGN_FIELDS = ['campaignId', 'name', 'status', 'revision', 'timezone', 'locale'] as const
const DRAFT_FIELDS = ['draftId', 'draftRevision', 'campaignRevision', 'manifestHash', 'savedAt'] as const
const MESSAGE_FIELDS = ['logicalId', 'capabilityCode', 'channel', 'locale', 'contentPresent', 'status'] as const
const PROMOTION_FIELDS = ['logicalId', 'capabilityCode', 'benefitKind', 'budgetState', 'status'] as const
const JOURNEY_FIELDS = ['logicalId', 'capabilityCode', 'entryNodeLogicalId', 'nodeCount', 'maxStepsPerPath', 'status'] as const
const SCHEDULE_FIELDS = [
  'logicalId',
  'capabilityCode',
  'form',
  'zoneId',
  'runAt',
  'localTime',
  'startDate',
  'endDate',
  'status',
  'nextOccurrence',
] as const
const READINESS_FIELDS = ['capabilityId', 'state', 'reasonCodes'] as const
const GOVERNANCE_FIELDS = ['logicalId', 'capabilityCode', 'approvalState', 'requiredRiskCodes', 'capabilities'] as const

const MAX_SUMMARIES = 32
const MAX_CODES = 32

function requireSummaryString(obj: Record<string, JsonValue>, name: string, path: string): string {
  return requireString(field(obj, name, path), `${path}.${name}`)
}

export function decodeCampaignSummary(raw: unknown, path: string): CampaignSummary {
  const obj = requireObject(raw, path)
  requireExactFields(obj, CAMPAIGN_FIELDS, path)
  const revision = requireInteger(field(obj, 'revision', path), `${path}.revision`)
  if (revision < 0) {
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, `${path}.revision`, 'revision-bounds')
  }
  return {
    campaignId: requirePattern(field(obj, 'campaignId', path), REFERENCE_ID_PATTERN, `${path}.campaignId`, 'reference-id'),
    name: requireString(field(obj, 'name', path), `${path}.name`),
    status: requireEnumValue(field(obj, 'status', path), CAMPAIGN_STATUSES, `${path}.status`),
    revision,
    timezone: requireString(field(obj, 'timezone', path), `${path}.timezone`),
    locale: requireString(field(obj, 'locale', path), `${path}.locale`),
  }
}

export function decodeDraftSummary(raw: unknown, path: string): DraftSummary {
  const obj = requireObject(raw, path)
  requireExactFields(obj, DRAFT_FIELDS, path)
  return {
    draftId: requirePattern(field(obj, 'draftId', path), REFERENCE_ID_PATTERN, `${path}.draftId`, 'reference-id'),
    draftRevision: requireNonNegativeInteger(field(obj, 'draftRevision', path), `${path}.draftRevision`),
    campaignRevision: requireNonNegativeInteger(field(obj, 'campaignRevision', path), `${path}.campaignRevision`),
    manifestHash: requirePattern(field(obj, 'manifestHash', path), CONTENT_HASH_PATTERN, `${path}.manifestHash`, 'content-hash') as ContentHash,
    savedAt: requirePattern(field(obj, 'savedAt', path), ISO_INSTANT_PATTERN, `${path}.savedAt`, 'instant'),
  }
}

export function decodeMessageSummary(raw: JsonValue, path: string): MessageSummary {
  const obj = requireObject(raw, path)
  requireExactFields(obj, MESSAGE_FIELDS, path)
  const contentPresent = field(obj, 'contentPresent', path)
  if (typeof contentPresent !== 'boolean') {
    throw contractError(UI_ERROR_CODES.CONTRACT_SHAPE_INVALID, `${path}.contentPresent`, 'expected-boolean')
  }
  return {
    logicalId: requirePattern(field(obj, 'logicalId', path), REFERENCE_ID_PATTERN, `${path}.logicalId`, 'reference-id'),
    capabilityCode: requireSummaryString(obj, 'capabilityCode', path),
    channel: requireEnumValue(field(obj, 'channel', path), MESSAGE_CHANNELS, `${path}.channel`),
    locale: requireSummaryString(obj, 'locale', path),
    contentPresent,
    status: requireSummaryString(obj, 'status', path),
  }
}

export function decodePromotionSummary(raw: JsonValue, path: string): PromotionSummary {
  const obj = requireObject(raw, path)
  requireExactFields(obj, PROMOTION_FIELDS, path)
  return {
    logicalId: requirePattern(field(obj, 'logicalId', path), REFERENCE_ID_PATTERN, `${path}.logicalId`, 'reference-id'),
    capabilityCode: requireSummaryString(obj, 'capabilityCode', path),
    benefitKind: requireSummaryString(obj, 'benefitKind', path),
    budgetState: requireSummaryString(obj, 'budgetState', path),
    status: requireSummaryString(obj, 'status', path),
  }
}

export function decodeJourneySummary(raw: JsonValue, path: string): JourneySummary {
  const obj = requireObject(raw, path)
  requireExactFields(obj, JOURNEY_FIELDS, path)
  return {
    logicalId: requirePattern(field(obj, 'logicalId', path), REFERENCE_ID_PATTERN, `${path}.logicalId`, 'reference-id'),
    capabilityCode: requireSummaryString(obj, 'capabilityCode', path),
    entryNodeLogicalId: requirePattern(
      field(obj, 'entryNodeLogicalId', path),
      REFERENCE_ID_PATTERN,
      `${path}.entryNodeLogicalId`,
      'reference-id',
    ),
    nodeCount: requireNonNegativeInteger(field(obj, 'nodeCount', path), `${path}.nodeCount`),
    maxStepsPerPath: requireNonNegativeInteger(field(obj, 'maxStepsPerPath', path), `${path}.maxStepsPerPath`),
    status: requireSummaryString(obj, 'status', path),
  }
}

export function decodeScheduleSummary(raw: JsonValue, path: string): ScheduleSummary {
  const obj = requireObject(raw, path)
  requireExactFields(obj, SCHEDULE_FIELDS, path)
  const form = requireEnumValue(field(obj, 'form', path), SCHEDULE_FORMS, `${path}.form`)
  const runAt = optionalString(optionalField(obj, 'runAt'), `${path}.runAt`)
  const localTime = optionalString(optionalField(obj, 'localTime'), `${path}.localTime`)
  const startDate = optionalString(optionalField(obj, 'startDate'), `${path}.startDate`)
  const endDate = optionalString(optionalField(obj, 'endDate'), `${path}.endDate`)
  if (form === 'IMMEDIATE' && (runAt !== null || localTime !== null || startDate !== null || endDate !== null)) {
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, path, 'immediate-fields')
  }
  if (form === 'ONCE' && runAt === null) {
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, `${path}.runAt`, 'once-requires-run-at')
  }
  if (form === 'DAILY' && (localTime === null || startDate === null || endDate === null)) {
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, path, 'daily-requires-window')
  }
  return {
    logicalId: requirePattern(field(obj, 'logicalId', path), REFERENCE_ID_PATTERN, `${path}.logicalId`, 'reference-id'),
    capabilityCode: requireSummaryString(obj, 'capabilityCode', path),
    form,
    zoneId: requireSummaryString(obj, 'zoneId', path),
    runAt,
    localTime,
    startDate,
    endDate,
    status: requireEnumValue(field(obj, 'status', path), SCHEDULE_STATUSES, `${path}.status`),
    nextOccurrence: optionalPattern(optionalField(obj, 'nextOccurrence'), ISO_INSTANT_PATTERN, `${path}.nextOccurrence`, 'instant'),
  }
}

export function decodeCapabilityReadiness(raw: JsonValue, path: string): CapabilityReadiness {
  const obj = requireObject(raw, path)
  requireExactFields(obj, READINESS_FIELDS, path)
  const reasonCodes = requireStringArray(field(obj, 'reasonCodes', path), `${path}.reasonCodes`, MAX_CODES)
  reasonCodes.forEach((code, index) =>
    requirePattern(code, MACHINE_CODE_PATTERN, `${path}.reasonCodes[${index}]`, 'machine-code'),
  )
  return {
    capabilityId: requireSummaryString(obj, 'capabilityId', path),
    state: requireEnumValue(field(obj, 'state', path), READINESS_STATES, `${path}.state`),
    reasonCodes,
  }
}

export function decodeGovernanceSummary(raw: JsonValue, path: string): GovernanceSummary {
  const obj = requireObject(raw, path)
  requireExactFields(obj, GOVERNANCE_FIELDS, path)
  return {
    logicalId: requirePattern(field(obj, 'logicalId', path), REFERENCE_ID_PATTERN, `${path}.logicalId`, 'reference-id'),
    capabilityCode: requireSummaryString(obj, 'capabilityCode', path),
    approvalState: requireEnumValue(
      field(obj, 'approvalState', path),
      GOVERNANCE_APPROVAL_STATES,
      `${path}.approvalState`,
    ),
    requiredRiskCodes: requireStringArray(
      field(obj, 'requiredRiskCodes', path),
      `${path}.requiredRiskCodes`,
      MAX_CODES,
    ),
    capabilities: requireObjectArray(field(obj, 'capabilities', path), `${path}.capabilities`, MAX_SUMMARIES)
      .map(({ item, path: itemPath }) => decodeCapabilityReadiness(item, itemPath)),
  }
}

/** A bounded set of per-capability summary cards from the last server Plan. */
export interface SummaryCards {
  readonly messages: readonly MessageSummary[]
  readonly promotions: readonly PromotionSummary[]
  readonly journeys: readonly JourneySummary[]
  readonly schedules: readonly ScheduleSummary[]
  readonly governance: readonly GovernanceSummary[]
}
