/**
 * Explicit Save (Apply) and the two-step Publish flow.
 *
 * Apply carries only the server-stored `(planId, planHash)` pair. Publish
 * carries only the exact `(preparationId, preparationHash)` pair plus its own
 * bounded idempotency key and the exact confirmation set. The UI never uploads
 * a ChangeSet, a Manifest, a component or a trusted fact.
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
import { decodePlanPreviewCard, READINESS_STATES, type PlanPreviewCard, type ReadinessState } from './plan'

export const PUBLISH_PREPARATION_STATES = ['PREPARED', 'CONSUMED', 'EXPIRED', 'STALE'] as const
export type PublishPreparationState = (typeof PUBLISH_PREPARATION_STATES)[number]

export const MAX_CONFIRMATIONS = 32
export const MAX_CLIENT_REF_BINDINGS = 256
export const MAX_PREPARATION_CARDS = 32

export interface ApplyCommand {
  readonly planId: string
  readonly planHash: ContentHash
}

export interface ClientRefBinding {
  readonly clientRef: string
  readonly logicalId: string
}

export interface ApplyResult {
  readonly campaignId: string
  readonly campaignRevision: number
  readonly draftId: string
  readonly draftRevision: number
  readonly manifestHash: ContentHash
  readonly draftHash: ContentHash
  readonly appliedAt: string
  readonly clientRefBindings: readonly ClientRefBinding[]
}

export interface PreparePublishCommand {
  readonly campaignId: string
  readonly draftId: string
  readonly draftRevision: number
}

export interface PublishPreparation {
  readonly preparationId: string
  readonly preparationHash: ContentHash
  readonly campaignId: string
  readonly campaignRevision: number
  readonly draftId: string
  readonly draftRevision: number
  readonly planId: string
  readonly planHash: ContentHash
  readonly manifestHash: ContentHash
  readonly draftHash: ContentHash
  readonly createdAt: string
  readonly expiresAt: string
  readonly state: PublishPreparationState
  readonly requiredRiskCodes: readonly string[]
  readonly readinessCapability: string
  readonly readinessState: ReadinessState
  readonly previewCards: readonly PlanPreviewCard[]
}

export interface PublishCommand {
  readonly preparationId: string
  readonly preparationHash: ContentHash
  readonly idempotencyKey: string
  readonly confirmations: readonly string[]
}

export interface PublishedIdentity {
  readonly publishedId: string
  readonly publishedRevision: number
  readonly contentChecksum: ContentHash
}

export interface DraftIdentity {
  readonly draftId: string
  readonly draftRevision: number
}

export interface PublishResult {
  readonly preparationId: string
  readonly preparationHash: ContentHash
  readonly idempotencyKey: string
  readonly publishedIdentity: PublishedIdentity
  readonly draftIdentity: DraftIdentity
  readonly manifestHash: ContentHash
  readonly confirmedRiskCodes: readonly string[]
  readonly publishedAt: string
}

const BINDING_FIELDS = ['clientRef', 'logicalId'] as const
const APPLY_RESULT_FIELDS = [
  'campaignId',
  'campaignRevision',
  'draftId',
  'draftRevision',
  'manifestHash',
  'draftHash',
  'appliedAt',
  'clientRefBindings',
] as const
const PREPARATION_FIELDS = [
  'preparationId',
  'preparationHash',
  'campaignId',
  'campaignRevision',
  'draftId',
  'draftRevision',
  'planId',
  'planHash',
  'manifestHash',
  'draftHash',
  'createdAt',
  'expiresAt',
  'state',
  'requiredRiskCodes',
  'readinessCapability',
  'readinessState',
  'previewCards',
] as const
const PUBLISHED_IDENTITY_FIELDS = ['publishedId', 'publishedRevision', 'contentChecksum'] as const
const DRAFT_IDENTITY_FIELDS = ['draftId', 'draftRevision'] as const
const PUBLISH_RESULT_FIELDS = [
  'preparationId',
  'preparationHash',
  'idempotencyKey',
  'publishedIdentity',
  'draftIdentity',
  'manifestHash',
  'confirmedRiskCodes',
  'publishedAt',
] as const

function requireHash(value: JsonValue, path: string): ContentHash {
  return requirePattern(value, CONTENT_HASH_PATTERN, path, 'content-hash') as ContentHash
}

export function decodeApplyResult(raw: unknown, path: string): ApplyResult {
  const obj = requireObject(raw, path)
  requireExactFields(obj, APPLY_RESULT_FIELDS, path)
  const campaignRevision = requireInteger(field(obj, 'campaignRevision', path), `${path}.campaignRevision`)
  const draftRevision = requireInteger(field(obj, 'draftRevision', path), `${path}.draftRevision`)
  if (campaignRevision < 1 || draftRevision < 1) {
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, path, 'revision-bounds')
  }
  return {
    campaignId: requirePattern(field(obj, 'campaignId', path), REFERENCE_ID_PATTERN, `${path}.campaignId`, 'reference-id'),
    campaignRevision,
    draftId: requirePattern(field(obj, 'draftId', path), REFERENCE_ID_PATTERN, `${path}.draftId`, 'reference-id'),
    draftRevision,
    manifestHash: requireHash(field(obj, 'manifestHash', path), `${path}.manifestHash`),
    draftHash: requireHash(field(obj, 'draftHash', path), `${path}.draftHash`),
    appliedAt: requirePattern(field(obj, 'appliedAt', path), ISO_INSTANT_PATTERN, `${path}.appliedAt`, 'instant'),
    clientRefBindings: requireObjectArray(
      field(obj, 'clientRefBindings', path),
      `${path}.clientRefBindings`,
      MAX_CLIENT_REF_BINDINGS,
    ).map(({ item, path: itemPath }) => {
      requireExactFields(item, BINDING_FIELDS, itemPath)
      return {
        clientRef: requirePattern(field(item, 'clientRef', itemPath), REFERENCE_ID_PATTERN, `${itemPath}.clientRef`, 'reference-id'),
        logicalId: requirePattern(field(item, 'logicalId', itemPath), REFERENCE_ID_PATTERN, `${itemPath}.logicalId`, 'reference-id'),
      }
    }),
  }
}

export function decodePublishPreparation(raw: unknown, path: string): PublishPreparation {
  const obj = requireObject(raw, path)
  requireExactFields(obj, PREPARATION_FIELDS, path)
  return {
    preparationId: requirePattern(
      field(obj, 'preparationId', path),
      REFERENCE_ID_PATTERN,
      `${path}.preparationId`,
      'reference-id',
    ),
    preparationHash: requireHash(field(obj, 'preparationHash', path), `${path}.preparationHash`),
    campaignId: requirePattern(field(obj, 'campaignId', path), REFERENCE_ID_PATTERN, `${path}.campaignId`, 'reference-id'),
    campaignRevision: requireNonNegativeInteger(field(obj, 'campaignRevision', path), `${path}.campaignRevision`),
    draftId: requirePattern(field(obj, 'draftId', path), REFERENCE_ID_PATTERN, `${path}.draftId`, 'reference-id'),
    draftRevision: requireNonNegativeInteger(field(obj, 'draftRevision', path), `${path}.draftRevision`),
    planId: requirePattern(field(obj, 'planId', path), REFERENCE_ID_PATTERN, `${path}.planId`, 'reference-id'),
    planHash: requireHash(field(obj, 'planHash', path), `${path}.planHash`),
    manifestHash: requireHash(field(obj, 'manifestHash', path), `${path}.manifestHash`),
    draftHash: requireHash(field(obj, 'draftHash', path), `${path}.draftHash`),
    createdAt: requirePattern(field(obj, 'createdAt', path), ISO_INSTANT_PATTERN, `${path}.createdAt`, 'instant'),
    expiresAt: requirePattern(field(obj, 'expiresAt', path), ISO_INSTANT_PATTERN, `${path}.expiresAt`, 'instant'),
    state: requireEnumValue(field(obj, 'state', path), PUBLISH_PREPARATION_STATES, `${path}.state`),
    requiredRiskCodes: requireStringArray(
      field(obj, 'requiredRiskCodes', path),
      `${path}.requiredRiskCodes`,
      MAX_CONFIRMATIONS,
    ),
    readinessCapability: requireString(field(obj, 'readinessCapability', path), `${path}.readinessCapability`),
    readinessState: requireEnumValue(field(obj, 'readinessState', path), READINESS_STATES, `${path}.readinessState`),
    previewCards: requireObjectArray(field(obj, 'previewCards', path), `${path}.previewCards`, MAX_PREPARATION_CARDS)
      .map(({ item, path: itemPath }) => decodePlanPreviewCard(item, itemPath)),
  }
}

export function decodePublishResult(raw: unknown, path: string): PublishResult {
  const obj = requireObject(raw, path)
  requireExactFields(obj, PUBLISH_RESULT_FIELDS, path)
  const publishedRaw = requireObject(field(obj, 'publishedIdentity', path), `${path}.publishedIdentity`)
  requireExactFields(publishedRaw, PUBLISHED_IDENTITY_FIELDS, `${path}.publishedIdentity`)
  const draftRaw = requireObject(field(obj, 'draftIdentity', path), `${path}.draftIdentity`)
  requireExactFields(draftRaw, DRAFT_IDENTITY_FIELDS, `${path}.draftIdentity`)
  const publishedRevision = requireInteger(publishedRaw['publishedRevision'], `${path}.publishedIdentity.publishedRevision`)
  if (publishedRevision < 1) {
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, `${path}.publishedIdentity.publishedRevision`, 'revision-bounds')
  }
  return {
    preparationId: requirePattern(
      field(obj, 'preparationId', path),
      REFERENCE_ID_PATTERN,
      `${path}.preparationId`,
      'reference-id',
    ),
    preparationHash: requireHash(field(obj, 'preparationHash', path), `${path}.preparationHash`),
    idempotencyKey: requirePattern(
      field(obj, 'idempotencyKey', path),
      REFERENCE_ID_PATTERN,
      `${path}.idempotencyKey`,
      'reference-id',
    ),
    publishedIdentity: {
      publishedId: requirePattern(
        field(publishedRaw, 'publishedId', `${path}.publishedIdentity`),
        REFERENCE_ID_PATTERN,
        `${path}.publishedIdentity.publishedId`,
        'reference-id',
      ),
      publishedRevision,
      contentChecksum: requireHash(
        field(publishedRaw, 'contentChecksum', `${path}.publishedIdentity`),
        `${path}.publishedIdentity.contentChecksum`,
      ),
    },
    draftIdentity: {
      draftId: requirePattern(
        field(draftRaw, 'draftId', `${path}.draftIdentity`),
        REFERENCE_ID_PATTERN,
        `${path}.draftIdentity.draftId`,
        'reference-id',
      ),
      draftRevision: requireNonNegativeInteger(
        field(draftRaw, 'draftRevision', `${path}.draftIdentity`),
        `${path}.draftIdentity.draftRevision`,
      ),
    },
    manifestHash: requireHash(field(obj, 'manifestHash', path), `${path}.manifestHash`),
    confirmedRiskCodes: requireStringArray(
      field(obj, 'confirmedRiskCodes', path),
      `${path}.confirmedRiskCodes`,
      MAX_CONFIRMATIONS,
    ),
    publishedAt: requirePattern(field(obj, 'publishedAt', path), ISO_INSTANT_PATTERN, `${path}.publishedAt`, 'instant'),
  }
}

/**
 * The durable preparation state. Consumed outranks expiry; anything else at or
 * past the expiry instant is expired; only an unexpired, unconsumed, still
 * consistent preparation is PREPARED.
 */
export function effectivePreparationState(
  preparation: PublishPreparation,
  nowInstant: string,
): PublishPreparationState {
  if (preparation.state === 'CONSUMED') {
    return 'CONSUMED'
  }
  if (preparation.state === 'STALE') {
    return 'STALE'
  }
  if (preparation.expiresAt <= nowInstant) {
    return 'EXPIRED'
  }
  return preparation.state
}

export function preparationPublishable(preparation: PublishPreparation, nowInstant: string): boolean {
  return effectivePreparationState(preparation, nowInstant) === 'PREPARED'
}

export function confirmationsMatch(preparation: PublishPreparation, confirmations: readonly string[]): boolean {
  const required = new Set(preparation.requiredRiskCodes)
  const supplied = new Set(confirmations)
  if (required.size !== confirmations.length || supplied.size !== confirmations.length) {
    return false
  }
  for (const code of required) {
    if (!supplied.has(code)) {
      return false
    }
  }
  return true
}

export function isMachineCodeList(values: readonly string[]): boolean {
  return values.every((value) => MACHINE_CODE_PATTERN.test(value))
}

export function riskCodeLabel(code: string): string {
  return code.split('_').join(' ').toLowerCase()
}
