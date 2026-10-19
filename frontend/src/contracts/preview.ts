/**
 * Segment Preview projection.
 *
 * The decode target mirrors the currently exposed read-only preview body
 * (`status`, `canonicalAstHash`, `queryPlanId`, `queryPlanHash`, `matchedCount`,
 * `conditionStats`, `sample`, `executionContext`). The UI projection adds only
 * plain-language semantics, freshness and the redaction policy marker.
 */

import { UI_ERROR_CODES, contractError } from './errors'
import { CONTENT_HASH_PATTERN, ISO_INSTANT_PATTERN } from './primitives'
import {
  field,
  optionalField,
  requireExactFields,
  requireNonNegativeInteger,
  requireObject,
  requireObjectArray,
  requirePattern,
  requireString,
  requireStringArray,
  type JsonValue,
} from './validate'

export const PREVIEW_SEMANTICS = 'EXACT_MATCH_COUNT' as const
export const SAMPLE_POLICY = 'REDACTED_KEYED_HASHES' as const
export const MAX_SAMPLE_SIZE = 64
export const MAX_CONDITION_STATS = 256

export interface PreviewConditionStatistic {
  readonly path: string
  readonly trueCount: number
  readonly falseCount: number
  readonly unknownCount: number
}

export interface PreviewExecutionContext {
  readonly metadataSnapshotId: string
  readonly asOf: string
  readonly timezone: string
  readonly dataWatermark: string
}

export interface SegmentPreviewResponse {
  readonly status: string
  readonly canonicalAstHash: string
  readonly queryPlanId: string
  readonly queryPlanHash: string
  readonly matchedCount: number
  readonly conditionStats: readonly PreviewConditionStatistic[]
  readonly sample: readonly string[] | null
  readonly executionContext: PreviewExecutionContext
}

export interface SegmentPreviewFreshness {
  readonly stale: boolean
  readonly observedAgeSeconds: number
  readonly maxDataAgeSeconds: number
}

export interface SegmentPreviewSummary {
  readonly semantics: typeof PREVIEW_SEMANTICS
  readonly status: string
  readonly matchedCount: number
  readonly asOf: string
  readonly timezone: string
  readonly dataWatermark: string
  readonly metadataSnapshotId: string
  readonly canonicalAstHash: string
  readonly queryPlanId: string
  readonly queryPlanHash: string
  readonly conditionStatistics: readonly PreviewConditionStatistic[]
  readonly freshness: SegmentPreviewFreshness
  readonly sample: {
    readonly policy: typeof SAMPLE_POLICY
    readonly included: boolean
    readonly maxSampleSize: number
    readonly tokens: readonly string[]
  }
}

const RESPONSE_FIELDS = [
  'status',
  'canonicalAstHash',
  'queryPlanId',
  'queryPlanHash',
  'matchedCount',
  'conditionStats',
  'sample',
  'executionContext',
] as const
const CONTEXT_FIELDS = ['metadataSnapshotId', 'asOf', 'timezone', 'dataWatermark'] as const
const STATISTIC_FIELDS = ['path', 'trueCount', 'falseCount', 'unknownCount'] as const

function decodeStatistic(raw: JsonValue, path: string): PreviewConditionStatistic {
  const obj = requireObject(raw, path)
  requireExactFields(obj, STATISTIC_FIELDS, path)
  return {
    path: requireString(field(obj, 'path', path), `${path}.path`),
    trueCount: requireNonNegativeInteger(field(obj, 'trueCount', path), `${path}.trueCount`),
    falseCount: requireNonNegativeInteger(field(obj, 'falseCount', path), `${path}.falseCount`),
    unknownCount: requireNonNegativeInteger(field(obj, 'unknownCount', path), `${path}.unknownCount`),
  }
}

function decodeContext(raw: JsonValue, path: string): PreviewExecutionContext {
  const obj = requireObject(raw, path)
  requireExactFields(obj, CONTEXT_FIELDS, path)
  return {
    metadataSnapshotId: requireString(field(obj, 'metadataSnapshotId', path), `${path}.metadataSnapshotId`),
    asOf: requirePattern(field(obj, 'asOf', path), ISO_INSTANT_PATTERN, `${path}.asOf`, 'instant'),
    timezone: requireString(field(obj, 'timezone', path), `${path}.timezone`),
    dataWatermark: requirePattern(field(obj, 'dataWatermark', path), ISO_INSTANT_PATTERN, `${path}.dataWatermark`, 'instant'),
  }
}

export function decodeSegmentPreviewResponse(raw: unknown, path: string): SegmentPreviewResponse {
  const obj = requireObject(raw, path)
  requireExactFields(obj, RESPONSE_FIELDS, path)
  const sampleRaw = optionalField(obj, 'sample')
  const sample = sampleRaw === undefined || sampleRaw === null
    ? null
    : requireStringArray(sampleRaw, `${path}.sample`, MAX_SAMPLE_SIZE).map((token, index) =>
        requirePattern(token, CONTENT_HASH_PATTERN, `${path}.sample[${index}]`, 'content-hash'),
      )
  return {
    status: requireString(field(obj, 'status', path), `${path}.status`),
    canonicalAstHash: requirePattern(
      field(obj, 'canonicalAstHash', path),
      CONTENT_HASH_PATTERN,
      `${path}.canonicalAstHash`,
      'content-hash',
    ),
    queryPlanId: requireString(field(obj, 'queryPlanId', path), `${path}.queryPlanId`),
    queryPlanHash: requirePattern(field(obj, 'queryPlanHash', path), CONTENT_HASH_PATTERN, `${path}.queryPlanHash`, 'content-hash'),
    matchedCount: requireNonNegativeInteger(field(obj, 'matchedCount', path), `${path}.matchedCount`),
    conditionStats: requireObjectArray(field(obj, 'conditionStats', path), `${path}.conditionStats`, MAX_CONDITION_STATS)
      .map(({ item, path: itemPath }) => decodeStatistic(item, itemPath)),
    sample,
    executionContext: decodeContext(field(obj, 'executionContext', path), `${path}.executionContext`),
  }
}

function instantMs(instant: string): number {
  const parsed = Date.parse(instant)
  if (Number.isNaN(parsed)) {
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, '$.asOf', 'instant-parse')
  }
  return parsed
}

export function projectSegmentPreview(
  response: SegmentPreviewResponse,
  maxDataAgeSeconds: number,
): SegmentPreviewSummary {
  const observedAgeSeconds = Math.max(
    0,
    Math.round((instantMs(response.executionContext.asOf) - instantMs(response.executionContext.dataWatermark)) / 1000),
  )
  return {
    semantics: PREVIEW_SEMANTICS,
    status: response.status,
    matchedCount: response.matchedCount,
    asOf: response.executionContext.asOf,
    timezone: response.executionContext.timezone,
    dataWatermark: response.executionContext.dataWatermark,
    metadataSnapshotId: response.executionContext.metadataSnapshotId,
    canonicalAstHash: response.canonicalAstHash,
    queryPlanId: response.queryPlanId,
    queryPlanHash: response.queryPlanHash,
    conditionStatistics: response.conditionStats,
    freshness: {
      stale: observedAgeSeconds > maxDataAgeSeconds,
      observedAgeSeconds,
      maxDataAgeSeconds,
    },
    sample: {
      policy: SAMPLE_POLICY,
      included: response.sample !== null,
      maxSampleSize: MAX_SAMPLE_SIZE,
      tokens: response.sample ?? [],
    },
  }
}