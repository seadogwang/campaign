/**
 * `CampaignProposalPatch` — the one authoring wire contract.
 *
 * Both authoring surfaces (AI Conversation and Visual Builder) produce this
 * exact shape and call the same gateway method with it. The client never
 * constructs a `ChangeSet`, a `Manifest` or a plan-material value; those are
 * server-only, non-forgeable compiler artifacts.
 *
 * Wire shape (canonical, absent optionals omitted):
 * {
 *   "schemaVersion": "1.4",               // legacy "1.3" is readable only without journeyTopology
 *   "mode": "CREATE" | "EDIT",
 *   "campaignId": "...",                  // EDIT only
 *   "baseRevisions": { campaignRevision?, catalogVersion, catalogHash, policyVersion, policyHash },
 *   "operations": [ { op, capability?, logicalId?, clientRef?, value?, before?, after? } ],
 *   "clientContext": { locale? },
 *   "journeyTopology": { action, topologyFormatVersion?, edges? }   // 1.4 only
 * }
 */

import { UI_ERROR_CODES, contractError } from './errors'
import {
  CONTENT_HASH_PATTERN,
  LOCALE_TAG_PATTERN,
  REFERENCE_ID_PATTERN,
  type ContentHash,
} from './primitives'
import {
  canonicalize,
} from './canonical'
import {
  field,
  optionalField,
  requireArray,
  requireEnumValue,
  requireExactFields,
  requireObject,
  requirePattern,
  requireString,
  type JsonObject,
  type JsonValue,
} from './validate'

export const PROPOSAL_MODES = ['CREATE', 'EDIT'] as const
export type ProposalMode = (typeof PROPOSAL_MODES)[number]

/**
 * The published pre-T31 Proposal wire version. It stays readable only for the exact
 * accepted shape, which has no `journeyTopology` member at all.
 */
export const LEGACY_PROPOSAL_SCHEMA_VERSION = '1.3' as const

/**
 * The current Proposal wire version. It is the only version that may carry the
 * optional `journeyTopology` member, and the version every top-level authoring surface emits.
 */
export const PROPOSAL_SCHEMA_VERSION = '1.4' as const

/** The two readable Proposal wire versions; writing always uses `PROPOSAL_SCHEMA_VERSION`. */
export type ProposalSchemaVersion =
  | typeof LEGACY_PROPOSAL_SCHEMA_VERSION
  | typeof PROPOSAL_SCHEMA_VERSION

export const PROPOSAL_OPERATION_KINDS = ['UPSERT', 'DELETE', 'MOVE'] as const
export type ProposalOperationKind = (typeof PROPOSAL_OPERATION_KINDS)[number]

export const MOVE_ANCHOR_KINDS = ['BEFORE', 'AFTER'] as const
export type MoveAnchorKind = (typeof MOVE_ANCHOR_KINDS)[number]

export const CAPABILITY_CODE_PATTERN = /^[a-z][a-z0-9.-]{0,63}$/

/** A bounded capability payload. Keys are capability fields, never server facts. */
export type CapabilityPayload = { readonly [key: string]: JsonValue }

export interface ProposalBaseRevisions {
  readonly campaignRevision: number | null
  readonly catalogVersion: string
  readonly catalogHash: ContentHash
  readonly policyVersion: string
  readonly policyHash: ContentHash
}

export interface ProposalOperation {
  readonly op: ProposalOperationKind
  readonly capability: string | null
  readonly logicalId: string | null
  readonly clientRef: string | null
  readonly value: CapabilityPayload | null
  readonly before: string | null
  readonly after: string | null
}

export interface ProposalClientContext {
  readonly locale: string | null
}

/**
 * The one closed Journey-topology verb. Omission of the whole mutation is the
 * inherit path; there is deliberately no partial-edit verb.
 */
export const JOURNEY_TOPOLOGY_ACTIONS = ['REPLACE', 'REMOVE'] as const
export type JourneyTopologyAction = (typeof JOURNEY_TOPOLOGY_ACTIONS)[number]

/** The closed T20 branch kind one topology edge may carry. */
export const JOURNEY_BRANCHES = ['DEFAULT', 'TRUE', 'FALSE'] as const
export type JourneyBranchKind = (typeof JOURNEY_BRANCHES)[number]

/** The frozen T20 branch ranks used for the deterministic canonical edge order. */
export const JOURNEY_BRANCH_RANKS: Readonly<Record<JourneyBranchKind, number>> = {
  DEFAULT: 0,
  TRUE: 1,
  FALSE: 2,
}

/** The one supported Journey-topology format version. */
export const JOURNEY_TOPOLOGY_FORMAT_VERSION = '1.0'

/** The accepted hard bound on one Journey topology's declared edges. */
export const MAX_JOURNEY_TOPOLOGY_EDGES = 512

/** One edge endpoint, addressed in exactly one of the two accepted identity forms. */
export interface JourneyTopologyEndpoint {
  readonly logicalId: string | null
  readonly clientRef: string | null
}

/** One strictly typed declared topology edge. */
export interface JourneyTopologyEdgeModel {
  readonly edgeId: string
  readonly from: JourneyTopologyEndpoint
  readonly to: JourneyTopologyEndpoint
  readonly branch: JourneyBranchKind
}

/**
 * The one strict Journey-topology mutation of an authoring request. `null` means
 * "inherit the current topology, never clear it"; `REMOVE` is the only clear path.
 *
 * The `null` is the in-memory form of an absent wire member only: a present JSON
 * `null` is invalid under both readable Proposal versions and is never decoded to
 * this value.
 */
export interface JourneyTopologyMutation {
  readonly action: JourneyTopologyAction
  readonly topologyFormatVersion: string | null
  readonly edges: readonly JourneyTopologyEdgeModel[]
}

export interface CampaignProposalPatch {
  readonly schemaVersion: ProposalSchemaVersion
  readonly mode: ProposalMode
  readonly campaignId: string | null
  readonly baseRevisions: ProposalBaseRevisions
  readonly operations: readonly ProposalOperation[]
  readonly clientContext: ProposalClientContext
  readonly journeyTopology: JourneyTopologyMutation | null
}

export const MAX_PROPOSAL_OPERATIONS = 128

/**
 * Field names a capability payload may never carry. Server-owned facts and any
 * second mutation path are refused at the boundary, mirroring the accepted
 * reserved-field rule.
 */
export const RESERVED_PAYLOAD_FIELDS: readonly string[] = [
  'tenantId',
  'programId',
  'userId',
  'dataScopeId',
  'planId',
  'planHash',
  'manifest',
  'manifestHash',
  'changeSet',
  'changeSetNodes',
  'diff',
  'semanticDiff',
  'revision',
  'campaignRevision',
  'draftId',
  'draftRevision',
  'publishedId',
  'publishedRevision',
  'preparationId',
  'preparationHash',
  'approvedDecisionHash',
  'gateInputsHash',
  'capabilityPins',
  'catalogVersion',
  'catalogHash',
  'policyVersion',
  'policyHash',
  'status',
  'apply',
  'publish',
  'simulate',
  'reconcile',
  'retry',
  'sql',
  'parameters',
  'fixtureId',
  'memberIds',
  'memberId',
  'recipient',
  'recipientTarget',
  'providerReference',
  'providerOutcome',
  'secret',
  'credential',
  'idempotencyKey',
  'baseline',
  'trusted',
]

const RESERVED_SET = new Set(RESERVED_PAYLOAD_FIELDS)
const PROPOSAL_FIELDS = [
  'schemaVersion',
  'mode',
  'campaignId',
  'baseRevisions',
  'operations',
  'clientContext',
  'journeyTopology',
] as const
const BASE_REVISION_FIELDS = ['campaignRevision', 'catalogVersion', 'catalogHash', 'policyVersion', 'policyHash'] as const
const OPERATION_FIELDS = ['op', 'capability', 'logicalId', 'clientRef', 'value', 'before', 'after'] as const
const CLIENT_CONTEXT_FIELDS = ['locale'] as const
const TOPOLOGY_FIELDS = ['action', 'topologyFormatVersion', 'edges'] as const
const TOPOLOGY_EDGE_FIELDS = ['edgeId', 'from', 'to', 'branch'] as const
const ENDPOINT_FIELDS = ['logicalId', 'clientRef'] as const

function requireReservedFree(value: JsonValue, path: string): void {
  if (Array.isArray(value)) {
    value.forEach((item, index) => requireReservedFree(item, `${path}[${index}]`))
    return
  }
  if (typeof value !== 'object' || value === null) {
    return
  }
  for (const key of Object.keys(value)) {
    if (RESERVED_SET.has(key)) {
      throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, `${path}.${key}`, 'reserved-field')
    }
    const member = value[key]
    if (member !== undefined) {
      requireReservedFree(member, `${path}.${key}`)
    }
  }
}

function requireCapabilityPayload(value: JsonValue, path: string): CapabilityPayload {
  requireObject(value, path)
  requireReservedFree(value, path)
  return value as CapabilityPayload
}

function decodeBaseRevisions(raw: JsonValue, path: string): ProposalBaseRevisions {
  const obj = requireObject(raw, path)
  requireExactFields(obj, BASE_REVISION_FIELDS, path)
  const campaignRevisionRaw = optionalField(obj, 'campaignRevision')
  let campaignRevision: number | null = null
  if (campaignRevisionRaw !== undefined && campaignRevisionRaw !== null) {
    if (
      typeof campaignRevisionRaw !== 'number'
      || !Number.isInteger(campaignRevisionRaw)
      || campaignRevisionRaw < 1
      || campaignRevisionRaw > 1_000_000_000
    ) {
      throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, `${path}.campaignRevision`, 'revision-bounds')
    }
    campaignRevision = campaignRevisionRaw
  }
  return {
    campaignRevision,
    catalogVersion: requirePattern(field(obj, 'catalogVersion', path), REFERENCE_ID_PATTERN, `${path}.catalogVersion`, 'reference-id'),
    catalogHash: requirePattern(field(obj, 'catalogHash', path), CONTENT_HASH_PATTERN, `${path}.catalogHash`, 'content-hash') as ContentHash,
    policyVersion: requirePattern(field(obj, 'policyVersion', path), REFERENCE_ID_PATTERN, `${path}.policyVersion`, 'reference-id'),
    policyHash: requirePattern(field(obj, 'policyHash', path), CONTENT_HASH_PATTERN, `${path}.policyHash`, 'content-hash') as ContentHash,
  }
}

function decodeClientContext(raw: JsonValue, path: string): ProposalClientContext {
  const obj = requireObject(raw, path)
  requireExactFields(obj, CLIENT_CONTEXT_FIELDS, path)
  const localeRaw = optionalField(obj, 'locale')
  return {
    locale: localeRaw === undefined || localeRaw === null
      ? null
      : requirePattern(localeRaw, LOCALE_TAG_PATTERN, `${path}.locale`, 'locale-tag'),
  }
}

export function decodeProposalOperation(raw: JsonValue, path: string): ProposalOperation {
  const obj = requireObject(raw, path)
  requireExactFields(obj, OPERATION_FIELDS, path)
  const op = requireEnumValue(field(obj, 'op', path), PROPOSAL_OPERATION_KINDS, `${path}.op`)
  const capabilityRaw = optionalField(obj, 'capability')
  const logicalIdRaw = optionalField(obj, 'logicalId')
  const clientRefRaw = optionalField(obj, 'clientRef')
  const valueRaw = optionalField(obj, 'value')
  const beforeRaw = optionalField(obj, 'before')
  const afterRaw = optionalField(obj, 'after')

  const hasLogicalId = logicalIdRaw !== undefined && logicalIdRaw !== null
  const hasClientRef = clientRefRaw !== undefined && clientRefRaw !== null
  if (hasLogicalId === hasClientRef) {
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, path, hasLogicalId ? 'identity-both' : 'identity-missing')
  }
  const logicalId = hasLogicalId
    ? requirePattern(logicalIdRaw, REFERENCE_ID_PATTERN, `${path}.logicalId`, 'reference-id')
    : null
  const clientRef = hasClientRef
    ? requirePattern(clientRefRaw, REFERENCE_ID_PATTERN, `${path}.clientRef`, 'reference-id')
    : null
  const before = beforeRaw === undefined || beforeRaw === null
    ? null
    : requirePattern(beforeRaw, REFERENCE_ID_PATTERN, `${path}.before`, 'reference-id')
  const after = afterRaw === undefined || afterRaw === null
    ? null
    : requirePattern(afterRaw, REFERENCE_ID_PATTERN, `${path}.after`, 'reference-id')

  switch (op) {
    case 'UPSERT': {
      if (capabilityRaw === undefined || capabilityRaw === null || valueRaw === undefined) {
        throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, path, 'upsert-fields')
      }
      if (before !== null || after !== null) {
        throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, path, 'upsert-anchor')
      }
      return {
        op,
        capability: requirePattern(capabilityRaw, CAPABILITY_CODE_PATTERN, `${path}.capability`, 'capability-code'),
        logicalId,
        clientRef,
        value: requireCapabilityPayload(valueRaw, `${path}.value`),
        before: null,
        after: null,
      }
    }
    case 'DELETE': {
      if (capabilityRaw !== undefined && capabilityRaw !== null) {
        throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, path, 'delete-capability')
      }
      if (valueRaw !== undefined || before !== null || after !== null) {
        throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, path, 'delete-payload')
      }
      if (clientRef !== null) {
        throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, path, 'delete-client-ref')
      }
      return { op, capability: null, logicalId, clientRef: null, value: null, before: null, after: null }
    }
    case 'MOVE': {
      if (capabilityRaw !== undefined && capabilityRaw !== null) {
        throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, path, 'move-capability')
      }
      if (valueRaw !== undefined) {
        throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, path, 'move-payload')
      }
      if ((before === null) === (after === null)) {
        throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, path, 'move-anchor')
      }
      return { op, capability: null, logicalId, clientRef, value: null, before, after }
    }
    default:
      throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, `${path}.op`, 'unsupported-op')
  }
}

function decodeEndpoint(raw: JsonValue, path: string): JourneyTopologyEndpoint {
  const obj = requireObject(raw, path)
  requireExactFields(obj, ENDPOINT_FIELDS, path)
  const logicalIdRaw = optionalField(obj, 'logicalId')
  const clientRefRaw = optionalField(obj, 'clientRef')
  const hasLogicalId = logicalIdRaw !== undefined && logicalIdRaw !== null
  const hasClientRef = clientRefRaw !== undefined && clientRefRaw !== null
  if (hasLogicalId === hasClientRef) {
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, path, 'endpoint-identity')
  }
  return {
    logicalId: hasLogicalId
      ? requirePattern(logicalIdRaw, REFERENCE_ID_PATTERN, `${path}.logicalId`, 'reference-id')
      : null,
    clientRef: hasClientRef
      ? requirePattern(clientRefRaw, REFERENCE_ID_PATTERN, `${path}.clientRef`, 'reference-id')
      : null,
  }
}

/**
 * The one strict Journey-topology mutation decoder. The replacement edge set is
 * duplicate-checked first and then normalised into the documented canonical order,
 * so the decoded in-memory model matches the Java mutation exactly.
 */
function decodeJourneyTopology(raw: JsonValue, path: string): JourneyTopologyMutation {
  const obj = requireObject(raw, path)
  requireExactFields(obj, TOPOLOGY_FIELDS, path)
  const action = requireEnumValue(field(obj, 'action', path), JOURNEY_TOPOLOGY_ACTIONS, `${path}.action`)
  if (action === 'REMOVE') {
    // A removal names no version and no edge: anything else is contradictory material.
    if (optionalField(obj, 'topologyFormatVersion') !== undefined || optionalField(obj, 'edges') !== undefined) {
      throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, path, 'remove-fields')
    }
    return { action, topologyFormatVersion: null, edges: [] }
  }
  const versionRaw = field(obj, 'topologyFormatVersion', path)
  const version = requireString(versionRaw, `${path}.topologyFormatVersion`)
  if (version !== JOURNEY_TOPOLOGY_FORMAT_VERSION) {
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, `${path}.topologyFormatVersion`, 'topology-version')
  }
  const edgesRaw = requireArray(field(obj, 'edges', path), `${path}.edges`)
  if (edgesRaw.length === 0 || edgesRaw.length > MAX_JOURNEY_TOPOLOGY_EDGES) {
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, `${path}.edges`, 'edge-count')
  }
  const edges = edgesRaw.map((item, index) => decodeJourneyTopologyEdge(item, `${path}.edges[${index}]`))
  const identities = new Set<string>()
  const semantics = new Set<string>()
  for (const edge of edges) {
    if (identities.has(edge.edgeId)) {
      throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, `${path}.edges`, 'duplicate-edge')
    }
    identities.add(edge.edgeId)
    const key = `${endpointKey(edge.from)}|${endpointKey(edge.to)}|${edge.branch}`
    if (semantics.has(key)) {
      throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, `${path}.edges`, 'duplicate-semantic-edge')
    }
    semantics.add(key)
  }
  return { action, topologyFormatVersion: version, edges: canonicalJourneyTopologyEdges(edges) }
}

function endpointKey(endpoint: JourneyTopologyEndpoint): string {
  return endpoint.logicalId !== null ? `L:${endpoint.logicalId}` : `C:${endpoint.clientRef ?? ''}`
}

function compareEndpointKeys(
  left: JourneyTopologyEndpoint,
  right: JourneyTopologyEndpoint,
): number {
  const leftKey = endpointKey(left)
  const rightKey = endpointKey(right)
  if (leftKey === rightKey) {
    return 0
  }
  return leftKey < rightKey ? -1 : 1
}

/**
 * The one documented total order of a replacement edge set: the typed identity of
 * the source endpoint, the canonical branch rank, the typed identity of the
 * destination endpoint, then the edge identity.
 *
 * Edge collection order carries no semantic meaning, so two replacements that
 * describe the same typed edges in a different input order normalise to the same
 * order here and thereby produce byte-identical canonical Proposal text and hash.
 * It mirrors the accepted T20 topology order (source, branch rank, destination,
 * edge identity), extended with the typed endpoint form because an authoring
 * endpoint is either an existing `logicalId` or a declared `clientRef`.
 *
 * Duplicate detection runs independently of this sort and always before it, so the
 * fail-closed rule never depends on the order a caller supplied.
 */
export function canonicalJourneyTopologyEdges(
  edges: readonly JourneyTopologyEdgeModel[],
): JourneyTopologyEdgeModel[] {
  return [...edges].sort((left, right) => {
    const from = compareEndpointKeys(left.from, right.from)
    if (from !== 0) {
      return from
    }
    const branch = JOURNEY_BRANCH_RANKS[left.branch] - JOURNEY_BRANCH_RANKS[right.branch]
    if (branch !== 0) {
      return branch
    }
    const to = compareEndpointKeys(left.to, right.to)
    if (to !== 0) {
      return to
    }
    if (left.edgeId === right.edgeId) {
      return 0
    }
    return left.edgeId < right.edgeId ? -1 : 1
  })
}

function decodeJourneyTopologyEdge(raw: JsonValue, path: string): JourneyTopologyEdgeModel {
  const obj = requireObject(raw, path)
  requireExactFields(obj, TOPOLOGY_EDGE_FIELDS, path)
  const from = decodeEndpoint(field(obj, 'from', path), `${path}.from`)
  const to = decodeEndpoint(field(obj, 'to', path), `${path}.to`)
  if (endpointKey(from) === endpointKey(to)) {
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, path, 'self-edge')
  }
  return {
    edgeId: requirePattern(field(obj, 'edgeId', path), REFERENCE_ID_PATTERN, `${path}.edgeId`, 'reference-id'),
    from,
    to,
    branch: requireEnumValue(field(obj, 'branch', path), JOURNEY_BRANCHES, `${path}.branch`),
  }
}

export function decodeCampaignProposalPatch(raw: unknown): CampaignProposalPatch {
  const obj = requireObject(raw, '$')
  requireExactFields(obj, PROPOSAL_FIELDS, '$')
  const schemaVersion = requireString(field(obj, 'schemaVersion', '$'), '$.schemaVersion')
  if (
    schemaVersion !== LEGACY_PROPOSAL_SCHEMA_VERSION
    && schemaVersion !== PROPOSAL_SCHEMA_VERSION
  ) {
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, '$.schemaVersion', 'schema-version')
  }
  if (
    schemaVersion === LEGACY_PROPOSAL_SCHEMA_VERSION
    && Object.prototype.hasOwnProperty.call(obj, 'journeyTopology')
  ) {
    // The published 1.3 shape has no journeyTopology member in any form, including an
    // explicit JSON null: reading one under 1.3 would silently reinterpret an accepted
    // strict version.
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, '$.journeyTopology', 'schema-version-topology')
  }
  const mode = requireEnumValue(field(obj, 'mode', '$'), PROPOSAL_MODES, '$.mode')
  const campaignIdRaw = optionalField(obj, 'campaignId')
  const campaignId = campaignIdRaw === undefined || campaignIdRaw === null
    ? null
    : requirePattern(campaignIdRaw, REFERENCE_ID_PATTERN, '$.campaignId', 'reference-id')

  if (mode === 'CREATE' && campaignId !== null) {
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, '$.campaignId', 'create-campaign-id')
  }
  if (mode === 'EDIT' && campaignId === null) {
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, '$.campaignId', 'edit-campaign-id')
  }

  const baseRevisions = decodeBaseRevisions(field(obj, 'baseRevisions', '$'), '$.baseRevisions')
  if (mode === 'CREATE' && baseRevisions.campaignRevision !== null) {
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, '$.baseRevisions.campaignRevision', 'create-revision')
  }
  if (mode === 'EDIT' && baseRevisions.campaignRevision === null) {
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, '$.baseRevisions.campaignRevision', 'edit-revision')
  }

  const operationsRaw = requireArray(field(obj, 'operations', '$'), '$.operations')
  if (operationsRaw.length === 0 || operationsRaw.length > MAX_PROPOSAL_OPERATIONS) {
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, '$.operations', 'operation-count')
  }
  const operations = operationsRaw.map((item, index) => decodeProposalOperation(item, `$.operations[${index}]`))

  const contextRaw = optionalField(obj, 'clientContext')
  const clientContext = contextRaw === undefined || contextRaw === null
    ? { locale: null }
    : decodeClientContext(contextRaw, '$.clientContext')

  const topologyRaw = optionalField(obj, 'journeyTopology')
  if (topologyRaw === null) {
    // A missing member means inherit; a present JSON null is invalid, never collapsed into omission.
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, '$.journeyTopology', 'null-field')
  }
  const journeyTopology = topologyRaw === undefined
    ? null
    : decodeJourneyTopology(topologyRaw, '$.journeyTopology')

  return {
    schemaVersion,
    mode,
    campaignId,
    baseRevisions,
    operations,
    clientContext,
    journeyTopology,
  }
}

export function encodeProposalOperation(operation: ProposalOperation): JsonObject {
  const encoded: JsonObject = { op: operation.op }
  if (operation.capability !== null) {
    encoded['capability'] = operation.capability
  }
  if (operation.logicalId !== null) {
    encoded['logicalId'] = operation.logicalId
  }
  if (operation.clientRef !== null) {
    encoded['clientRef'] = operation.clientRef
  }
  if (operation.value !== null) {
    encoded['value'] = operation.value as JsonValue
  }
  if (operation.before !== null) {
    encoded['before'] = operation.before
  }
  if (operation.after !== null) {
    encoded['after'] = operation.after
  }
  return encoded
}

export function encodeCampaignProposalPatch(patch: CampaignProposalPatch): JsonObject {
  if (
    patch.schemaVersion === LEGACY_PROPOSAL_SCHEMA_VERSION
    && patch.journeyTopology !== null
  ) {
    // The same Proposal-version/topology invariant the strict decoder and the Java model prove, so a
    // hand-constructed legacy patch cannot encode a topology its published shape has no member for.
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, '$.journeyTopology', 'schema-version-topology')
  }
  const baseRevisions: JsonObject = {
    catalogVersion: patch.baseRevisions.catalogVersion,
    catalogHash: patch.baseRevisions.catalogHash,
    policyVersion: patch.baseRevisions.policyVersion,
    policyHash: patch.baseRevisions.policyHash,
  }
  if (patch.baseRevisions.campaignRevision !== null) {
    baseRevisions['campaignRevision'] = patch.baseRevisions.campaignRevision
  }
  const encoded: JsonObject = {
    schemaVersion: patch.schemaVersion,
    mode: patch.mode,
    baseRevisions,
    operations: patch.operations.map((operation) => encodeProposalOperation(operation)),
  }
  if (patch.campaignId !== null) {
    encoded['campaignId'] = patch.campaignId
  }
  if (patch.clientContext.locale !== null) {
    encoded['clientContext'] = { locale: patch.clientContext.locale }
  }
  if (patch.journeyTopology !== null) {
    encoded['journeyTopology'] = encodeJourneyTopology(patch.journeyTopology)
  }
  return encoded
}

/**
 * The canonical wire form of one Journey-topology mutation. A removal contributes its
 * action alone, so the three cases (omit, replace, remove) have three distinct forms.
 * A replacement's edges are written in the documented canonical order, whichever
 * order the caller supplied them in.
 */
export function encodeJourneyTopology(mutation: JourneyTopologyMutation): JsonObject {
  if (mutation.action === 'REMOVE') {
    return { action: 'REMOVE' }
  }
  return {
    action: 'REPLACE',
    topologyFormatVersion: mutation.topologyFormatVersion ?? JOURNEY_TOPOLOGY_FORMAT_VERSION,
    edges: canonicalJourneyTopologyEdges(mutation.edges).map((edge) => ({
      edgeId: edge.edgeId,
      from: encodeEndpoint(edge.from),
      to: encodeEndpoint(edge.to),
      branch: edge.branch,
    })),
  }
}

function encodeEndpoint(endpoint: JourneyTopologyEndpoint): JsonObject {
  return endpoint.logicalId !== null
    ? { logicalId: endpoint.logicalId }
    : { clientRef: endpoint.clientRef ?? '' }
}

/** Stable canonical text for one Proposal, used to prove AI/Visual equivalence. */
export function proposalCanonicalText(patch: CampaignProposalPatch): string {
  return canonicalize(encodeCampaignProposalPatch(patch))
}

const OP_RANK: Readonly<Record<ProposalOperationKind, number>> = { UPSERT: 0, DELETE: 1, MOVE: 2 }

/**
 * Deterministic normalisation shared by both authoring surfaces: the operation
 * list is put in one canonical order and every capability payload is key-sorted
 * by the canonical encoder. It never adds, removes or rewrites an operation.
 */
export function normalizeCampaignProposalPatch(patch: CampaignProposalPatch): CampaignProposalPatch {
  const operations = [...patch.operations].sort((left, right) => {
    const rank = OP_RANK[left.op] - OP_RANK[right.op]
    if (rank !== 0) {
      return rank
    }
    const leftKey = `${left.capability ?? ''}|${left.logicalId ?? left.clientRef ?? ''}|${left.before ?? ''}|${left.after ?? ''}`
    const rightKey = `${right.capability ?? ''}|${right.logicalId ?? right.clientRef ?? ''}|${right.before ?? ''}|${right.after ?? ''}`
    if (leftKey === rightKey) {
      return 0
    }
    return leftKey < rightKey ? -1 : 1
  })
  return { ...patch, operations }
}

/** Build one UPSERT operation for a logical resource. */
export function upsertOperation(
  capability: string,
  logicalId: string | null,
  clientRef: string | null,
  value: CapabilityPayload,
): ProposalOperation {
  return { op: 'UPSERT', capability, logicalId, clientRef, value, before: null, after: null }
}

export function deleteOperation(logicalId: string): ProposalOperation {
  return { op: 'DELETE', capability: null, logicalId, clientRef: null, value: null, before: null, after: null }
}

export function moveOperation(
  logicalId: string,
  anchor: MoveAnchorKind,
  anchorLogicalId: string,
): ProposalOperation {
  return {
    op: 'MOVE',
    capability: null,
    logicalId,
    clientRef: null,
    value: null,
    before: anchor === 'BEFORE' ? anchorLogicalId : null,
    after: anchor === 'AFTER' ? anchorLogicalId : null,
  }
}
