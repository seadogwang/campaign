/**
 * Accepted T15/T16 Plan projection: immutable identity/hash/status, semantic
 * diff, validation issues and preview cards. Read-only: the UI never builds a
 * Manifest, a ChangeSet or any plan material.
 */

import { UI_ERROR_CODES, contractError } from './errors'
import {
  CONTENT_HASH_PATTERN,
  ISO_INSTANT_PATTERN,
  PLAN_FORMAT_VERSION,
  COMPILER_VERSION,
  REFERENCE_ID_PATTERN,
  type ContentHash,
} from './primitives'
import { canonicalize } from './canonical'
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

export const CHANGE_KINDS = ['ADD', 'UPDATE', 'DELETE', 'MOVE'] as const
export type ChangeKind = (typeof CHANGE_KINDS)[number]

export const ISSUE_SEVERITIES = ['ERROR', 'WARNING', 'INFO'] as const
export type IssueSeverity = (typeof ISSUE_SEVERITIES)[number]

export const PLAN_STATUSES = ['NEEDS_INPUT', 'INVALID', 'READY', 'STALE', 'APPLIED', 'EXPIRED', 'REJECTED'] as const
export type PlanStatus = (typeof PLAN_STATUSES)[number]

export const READINESS_STATES = ['ACTIVE', 'EXPERIMENTAL', 'EVALUATION_REQUIRED'] as const
export type ReadinessState = (typeof READINESS_STATES)[number]

export const REFERENCE_READINESS_STATES = ['EXPERIMENTAL', 'DEVELOPMENT_ONLY', 'CANDIDATE_COMPLETE'] as const
export type ReferenceReadinessState = (typeof REFERENCE_READINESS_STATES)[number]

/** The only handle the browser may ever present for a stored Plan. */
export interface PlanRef {
  readonly planId: string
  readonly planHash: ContentHash
  readonly expiresAt: string
}

export interface DiffEntry {
  readonly kind: ChangeKind
  readonly logicalId: string
  readonly capabilityCode: string
  readonly fieldPaths: readonly string[]
}

export interface PlanIssue {
  readonly code: string
  readonly severity: IssueSeverity
  readonly path: string | null
  readonly nodeId: string | null
  readonly message: string
}

export interface PlanPreviewStatistic {
  readonly path: string
  readonly trueCount: number
  readonly falseCount: number
  readonly unknownCount: number
}

export interface ReferencePlanEvidence {
  readonly capabilityId: string
  readonly referenceDependenciesHash: ContentHash
  readonly referenceInputsHash: ContentHash
  readonly readinessState: ReferenceReadinessState
}

export interface PlanPreviewCard {
  readonly cardKind: string
  readonly componentLogicalId: string
  readonly matchedCount: number
  readonly conditionStatistics: readonly PlanPreviewStatistic[]
  readonly sampleTokens: readonly string[]
  readonly metadataSnapshotId: string
  readonly asOf: string
  readonly timezone: string
  readonly dataWatermark: string
  readonly readinessState: ReadinessState
  readonly approvedDecisionHash: ContentHash | null
  readonly referenceEvidence: ReferencePlanEvidence | null
}

export interface CampaignPlanSummary {
  readonly planRef: PlanRef
  readonly planFormatVersion: string
  readonly compilerVersion: string
  readonly mode: 'CREATE' | 'EDIT'
  readonly status: PlanStatus
  readonly compiledAt: string
  readonly campaignId: string | null
  readonly baseDraftId: string | null
  readonly baseDraftRevision: number | null
  readonly proposalHash: ContentHash
  readonly manifestHash: ContentHash
  readonly validationIssues: readonly PlanIssue[]
  readonly semanticDiff: readonly DiffEntry[]
  readonly previewCards: readonly PlanPreviewCard[]
  readonly riskCodes: readonly string[]
}

const MAX_DIFF_ENTRIES = 256
const MAX_ISSUES = 128
const MAX_PREVIEW_CARDS = 32
const MAX_CONDITION_STATS = 256
const MAX_SAMPLE_TOKENS = 64
const MAX_RISK_CODES = 32
const MAX_FIELD_PATHS = 16

const PLAN_REF_FIELDS = ['planId', 'planHash', 'expiresAt'] as const
const DIFF_ENTRY_FIELDS = ['kind', 'logicalId', 'capabilityCode', 'fieldPaths'] as const
const ISSUE_FIELDS = ['code', 'severity', 'path', 'nodeId', 'message', 'details'] as const
const PREVIEW_CARD_FIELDS = [
  'cardKind',
  'componentLogicalId',
  'matchedCount',
  'conditionStatistics',
  'sampleTokens',
  'metadataSnapshotId',
  'asOf',
  'timezone',
  'dataWatermark',
  'readinessState',
  'approvedDecisionHash',
  'referenceEvidence',
] as const
const STATISTIC_FIELDS = ['path', 'trueCount', 'falseCount', 'unknownCount'] as const
const REFERENCE_EVIDENCE_FIELDS = [
  'capabilityId',
  'referenceDependenciesHash',
  'referenceInputsHash',
  'readinessState',
] as const

export function decodePlanRef(raw: unknown, path: string): PlanRef {
  const obj = requireObject(raw, path)
  requireExactFields(obj, PLAN_REF_FIELDS, path)
  return {
    planId: requirePattern(field(obj, 'planId', path), REFERENCE_ID_PATTERN, `${path}.planId`, 'reference-id'),
    planHash: requirePattern(field(obj, 'planHash', path), CONTENT_HASH_PATTERN, `${path}.planHash`, 'content-hash') as ContentHash,
    expiresAt: requirePattern(field(obj, 'expiresAt', path), ISO_INSTANT_PATTERN, `${path}.expiresAt`, 'instant'),
  }
}

export function planRefCanonicalText(ref: PlanRef): string {
  return canonicalize({ expiresAt: ref.expiresAt, planHash: ref.planHash, planId: ref.planId })
}

function decodeDiffEntry(raw: JsonValue, path: string): DiffEntry {
  const obj = requireObject(raw, path)
  requireExactFields(obj, DIFF_ENTRY_FIELDS, path)
  return {
    kind: requireEnumValue(field(obj, 'kind', path), CHANGE_KINDS, `${path}.kind`),
    logicalId: requirePattern(field(obj, 'logicalId', path), REFERENCE_ID_PATTERN, `${path}.logicalId`, 'reference-id'),
    capabilityCode: requireString(field(obj, 'capabilityCode', path), `${path}.capabilityCode`),
    fieldPaths: requireStringArray(field(obj, 'fieldPaths', path), `${path}.fieldPaths`, MAX_FIELD_PATHS),
  }
}

export function decodeIssue(raw: JsonValue, path: string): PlanIssue {
  const obj = requireObject(raw, path)
  requireExactFields(obj, ISSUE_FIELDS, path)
  return {
    code: requireString(field(obj, 'code', path), `${path}.code`),
    severity: requireEnumValue(field(obj, 'severity', path), ISSUE_SEVERITIES, `${path}.severity`),
    path: optionalString(optionalField(obj, 'path'), `${path}.path`),
    nodeId: optionalString(optionalField(obj, 'nodeId'), `${path}.nodeId`),
    message: requireString(field(obj, 'message', path), `${path}.message`),
  }
}

function decodeStatistic(raw: JsonValue, path: string): PlanPreviewStatistic {
  const obj = requireObject(raw, path)
  requireExactFields(obj, STATISTIC_FIELDS, path)
  return {
    path: requireString(field(obj, 'path', path), `${path}.path`),
    trueCount: requireNonNegativeInteger(field(obj, 'trueCount', path), `${path}.trueCount`),
    falseCount: requireNonNegativeInteger(field(obj, 'falseCount', path), `${path}.falseCount`),
    unknownCount: requireNonNegativeInteger(field(obj, 'unknownCount', path), `${path}.unknownCount`),
  }
}

function decodeReferenceEvidence(raw: JsonValue, path: string): ReferencePlanEvidence {
  const obj = requireObject(raw, path)
  requireExactFields(obj, REFERENCE_EVIDENCE_FIELDS, path)
  return {
    capabilityId: requireString(field(obj, 'capabilityId', path), `${path}.capabilityId`),
    referenceDependenciesHash: requirePattern(
      field(obj, 'referenceDependenciesHash', path),
      CONTENT_HASH_PATTERN,
      `${path}.referenceDependenciesHash`,
      'content-hash',
    ) as ContentHash,
    referenceInputsHash: requirePattern(
      field(obj, 'referenceInputsHash', path),
      CONTENT_HASH_PATTERN,
      `${path}.referenceInputsHash`,
      'content-hash',
    ) as ContentHash,
    readinessState: requireEnumValue(
      field(obj, 'readinessState', path),
      REFERENCE_READINESS_STATES,
      `${path}.readinessState`,
    ),
  }
}

export function decodePlanPreviewCard(raw: JsonValue, path: string): PlanPreviewCard {
  const obj = requireObject(raw, path)
  requireExactFields(obj, PREVIEW_CARD_FIELDS, path)
  const cardKind = requireString(field(obj, 'cardKind', path), `${path}.cardKind`)
  if (cardKind !== 'SEGMENT_PREVIEW') {
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, `${path}.cardKind`, 'card-kind')
  }
  const readinessState = requireEnumValue(
    field(obj, 'readinessState', path),
    READINESS_STATES,
    `${path}.readinessState`,
  )
  const approvedDecisionHash = optionalPattern(
    optionalField(obj, 'approvedDecisionHash'),
    CONTENT_HASH_PATTERN,
    `${path}.approvedDecisionHash`,
    'content-hash',
  ) as ContentHash | null
  if (readinessState === 'ACTIVE' && approvedDecisionHash === null) {
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, `${path}.approvedDecisionHash`, 'active-requires-decision')
  }
  const evidenceRaw = optionalField(obj, 'referenceEvidence')
  return {
    cardKind,
    componentLogicalId: requirePattern(
      field(obj, 'componentLogicalId', path),
      REFERENCE_ID_PATTERN,
      `${path}.componentLogicalId`,
      'reference-id',
    ),
    matchedCount: requireNonNegativeInteger(field(obj, 'matchedCount', path), `${path}.matchedCount`),
    conditionStatistics: requireObjectArray(
      field(obj, 'conditionStatistics', path),
      `${path}.conditionStatistics`,
      MAX_CONDITION_STATS,
    ).map(({ item, path: itemPath }) => decodeStatistic(item, itemPath)),
    sampleTokens: requireStringArray(
      field(obj, 'sampleTokens', path),
      `${path}.sampleTokens`,
      MAX_SAMPLE_TOKENS,
    ).map((token, index) =>
      requirePattern(token, CONTENT_HASH_PATTERN, `${path}.sampleTokens[${index}]`, 'content-hash'),
    ),
    metadataSnapshotId: requireString(field(obj, 'metadataSnapshotId', path), `${path}.metadataSnapshotId`),
    asOf: requirePattern(field(obj, 'asOf', path), ISO_INSTANT_PATTERN, `${path}.asOf`, 'instant'),
    timezone: requireString(field(obj, 'timezone', path), `${path}.timezone`),
    dataWatermark: requirePattern(field(obj, 'dataWatermark', path), ISO_INSTANT_PATTERN, `${path}.dataWatermark`, 'instant'),
    readinessState,
    approvedDecisionHash,
    referenceEvidence: evidenceRaw === undefined || evidenceRaw === null
      ? null
      : decodeReferenceEvidence(evidenceRaw, `${path}.referenceEvidence`),
  }
}

const PLAN_SUMMARY_FIELDS = [
  'planRef',
  'planFormatVersion',
  'compilerVersion',
  'mode',
  'status',
  'compiledAt',
  'campaignId',
  'baseDraftId',
  'baseDraftRevision',
  'proposalHash',
  'manifestHash',
  'validationIssues',
  'semanticDiff',
  'previewCards',
  'riskCodes',
] as const

export function decodeCampaignPlanSummary(raw: unknown, path: string): CampaignPlanSummary {
  const obj = requireObject(raw, path)
  requireExactFields(obj, PLAN_SUMMARY_FIELDS, path)
  const planFormatVersion = requireString(field(obj, 'planFormatVersion', path), `${path}.planFormatVersion`)
  const compilerVersion = requireString(field(obj, 'compilerVersion', path), `${path}.compilerVersion`)
  if (planFormatVersion !== PLAN_FORMAT_VERSION || compilerVersion !== COMPILER_VERSION) {
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, path, 'plan-versions')
  }
  const baseDraftRevisionRaw = optionalField(obj, 'baseDraftRevision')
  let baseDraftRevision: number | null = null
  if (baseDraftRevisionRaw !== undefined && baseDraftRevisionRaw !== null) {
    const parsed = requireInteger(baseDraftRevisionRaw, `${path}.baseDraftRevision`)
    if (parsed < 1) {
      throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, `${path}.baseDraftRevision`, 'revision-bounds')
    }
    baseDraftRevision = parsed
  }
  return {
    planRef: decodePlanRef(field(obj, 'planRef', path), `${path}.planRef`),
    planFormatVersion,
    compilerVersion,
    mode: requireEnumValue(field(obj, 'mode', path), ['CREATE', 'EDIT'] as const, `${path}.mode`),
    status: requireEnumValue(field(obj, 'status', path), PLAN_STATUSES, `${path}.status`),
    compiledAt: requirePattern(field(obj, 'compiledAt', path), ISO_INSTANT_PATTERN, `${path}.compiledAt`, 'instant'),
    campaignId: optionalString(optionalField(obj, 'campaignId'), `${path}.campaignId`),
    baseDraftId: optionalString(optionalField(obj, 'baseDraftId'), `${path}.baseDraftId`),
    baseDraftRevision,
    proposalHash: requirePattern(field(obj, 'proposalHash', path), CONTENT_HASH_PATTERN, `${path}.proposalHash`, 'content-hash') as ContentHash,
    manifestHash: requirePattern(field(obj, 'manifestHash', path), CONTENT_HASH_PATTERN, `${path}.manifestHash`, 'content-hash') as ContentHash,
    validationIssues: requireObjectArray(field(obj, 'validationIssues', path), `${path}.validationIssues`, MAX_ISSUES)
      .map(({ item, path: itemPath }) => decodeIssue(item, itemPath)),
    semanticDiff: requireObjectArray(field(obj, 'semanticDiff', path), `${path}.semanticDiff`, MAX_DIFF_ENTRIES)
      .map(({ item, path: itemPath }) => decodeDiffEntry(item, itemPath)),
    previewCards: requireObjectArray(field(obj, 'previewCards', path), `${path}.previewCards`, MAX_PREVIEW_CARDS)
      .map(({ item, path: itemPath }) => decodePlanPreviewCard(item, itemPath)),
    riskCodes: requireStringArray(field(obj, 'riskCodes', path), `${path}.riskCodes`, MAX_RISK_CODES),
  }
}

export function blockingIssues(plan: CampaignPlanSummary): readonly PlanIssue[] {
  return plan.validationIssues.filter((issue) => issue.severity === 'ERROR')
}

export function issuesBySeverity(plan: CampaignPlanSummary): Readonly<Record<IssueSeverity, readonly PlanIssue[]>> {
  return {
    ERROR: plan.validationIssues.filter((issue) => issue.severity === 'ERROR'),
    WARNING: plan.validationIssues.filter((issue) => issue.severity === 'WARNING'),
    INFO: plan.validationIssues.filter((issue) => issue.severity === 'INFO'),
  }
}

/** True when the plan identity can still be presented for Apply. */
export function planPresentable(plan: CampaignPlanSummary, nowInstant: string): boolean {
  return plan.status === 'READY' && plan.planRef.expiresAt > nowInstant && blockingIssues(plan).length === 0
}

