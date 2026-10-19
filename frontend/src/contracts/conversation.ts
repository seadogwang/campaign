/**
 * The Campaign conversation contract.
 *
 * This is the client mirror of the accepted top-level Campaign Agent turn
 * contract. A user turn is one bounded message plus typed clarification answers;
 * the answer is one bounded conversation projection. The UI never parses natural
 * language into directives and never builds a Proposal locally: the only
 * Proposal it can act on is the one the host put in the projection.
 *
 * Wire shape of one projection:
 * {
 *   accepted, code?, conversationId, runId, revision, phase, disposition,
 *   routeKind?, turns[], skills[], components[], clarification?, assistantMessage?,
 *   audience?, inspectionHash, proposal?, proposalHash?, followUpPrompt?, requirements?,
 *   componentManifest?
 * }
 */

import { UI_ERROR_CODES, contractError } from './errors'
import { contentHashOfCanonical } from './canonical'
import {
  CAPABILITY_CODE_PATTERN,
  PROPOSAL_OPERATION_KINDS,
  decodeCampaignProposalPatch,
  encodeCampaignProposalPatch,
  type CampaignProposalPatch,
  type ProposalOperationKind,
} from './proposal'
import {
  CONTENT_HASH_PATTERN,
  MACHINE_CODE_PATTERN,
  REFERENCE_ID_PATTERN,
  type ContentHash,
} from './primitives'
import {
  field,
  optionalField,
  requireArray,
  requireBoolean,
  requireEnumValue,
  requireExactFields,
  requireInteger,
  requireNonNegativeInteger,
  requireObject,
  requirePattern,
  requireString,
  requireStringArray,
  type JsonObject,
  type JsonValue,
} from './validate'

export const CONVERSATION_ROLES = ['USER', 'ASSISTANT'] as const
export type ConversationRole = (typeof CONVERSATION_ROLES)[number]

export const CONVERSATION_PHASES = [
  'AWAITING_GOAL',
  'AWAITING_CLARIFICATION',
  'BUILDING',
  'AUDIENCE_READY',
  'READY_TO_FINALIZE',
  'PROPOSAL_READY',
  'TERMINAL_INVALID',
] as const
export type ConversationPhase = (typeof CONVERSATION_PHASES)[number]

export const CONVERSATION_ROUTE_KINDS = [
  'CAMPAIGN_REQUEST',
  'AUDIENCE_REQUEST',
  'CONTINUE_CAMPAIGN',
  'FINALIZE_CAMPAIGN',
  'NEEDS_CLARIFICATION',
  'OUT_OF_SCOPE',
] as const
export type ConversationRouteKind = (typeof CONVERSATION_ROUTE_KINDS)[number]

/** The four Skill progress states the assembly reports. */
export const SKILL_PROGRESS_STATES = ['MISSING', 'NEEDS_INPUT', 'READY', 'INVALID'] as const
export type SkillProgressState = (typeof SKILL_PROGRESS_STATES)[number]

/** The visible current goal of one conversation. */
export const CAMPAIGN_GOALS = ['CAMPAIGN', 'AUDIENCE'] as const
export type CampaignGoal = (typeof CAMPAIGN_GOALS)[number]

export interface ConversationTurn {
  readonly turnId: string
  readonly role: ConversationRole
  readonly ordinal: number
  /** Interaction text only. Never rendered as saved Campaign state. */
  readonly text: string
  /** Host-supplied presentation instant, or null. The UI never invents one. */
  readonly at: string | null
}

export interface SkillProgress {
  readonly skillId: string
  readonly state: SkillProgressState
  readonly code: string | null
  readonly missingDependencyCodes: readonly string[]
}

/**
 * One bounded component projection fact of the T27 inspection.
 *
 * It carries only the accepted inspection facts: the component identity, the
 * owning Skill, the nullable capability code, the operation kind and the
 * accepted artifact hash. It can never carry a Proposal payload, a capability
 * payload, a template body, a variable, a reward value, a Tool result, a member
 * or contact identifier, Provider data or model output. It is read-only
 * presentation data and is never submitted as a mutation.
 */
export interface ComponentProgress {
  readonly componentId: string
  readonly skillId: string
  readonly capabilityCode: string | null
  readonly operationKind: ProposalOperationKind
  /** Integrity hash of the accepted artifact. Never a value the UI may submit. */
  readonly artifactHash: ContentHash
}

export interface ClarificationOption {
  readonly optionId: string
  readonly label: string
}

export interface ConversationClarification {
  readonly skillId: string | null
  readonly code: string
  readonly prompt: string
  readonly questionCodes: readonly string[]
  readonly options: readonly ClarificationOption[]
  readonly freeTextAllowed: boolean
}

export interface ConversationAudience {
  readonly status: string
  readonly matchedCount: number
  readonly asOf: string
  readonly timezone: string
  readonly dataWatermark: string
  /** Pins the card; the UI shows no member identifier and no raw sample. */
  readonly previewHash: ContentHash
}

export const CAMPAIGN_REQUIREMENT_TYPES = ['AD_HOC', 'LIFECYCLE', 'EVENT'] as const
export type CampaignRequirementType = (typeof CAMPAIGN_REQUIREMENT_TYPES)[number]
export const OFFER_DECISIONS = ['UNKNOWN', 'NONE', 'DEFINED'] as const
export type OfferDecision = (typeof OFFER_DECISIONS)[number]
export const BRANCH_DECISIONS = ['UNKNOWN', 'NONE', 'REQUIRED'] as const
export type BranchDecision = (typeof BRANCH_DECISIONS)[number]
export const CAMPAIGN_CADENCES = ['ONE_TIME', 'DAILY'] as const
export type CampaignCadence = (typeof CAMPAIGN_CADENCES)[number]

/**
 * The closed set of Campaign component groups the TODO view is grouped by.
 *
 * This mirrors the server-owned `CampaignComponentCode`. A component is product schema, not user
 * copy: the client branches on the exact code to render one stable business label, so an unknown
 * group is refused rather than rendered.
 */
export const CAMPAIGN_COMPONENTS = [
  'SEGMENT',
  'OFFER',
  'COMMUNICATION',
  'JOURNEY',
  'EXECUTION_SCHEDULE',
] as const
export type CampaignComponent = (typeof CAMPAIGN_COMPONENTS)[number]

/**
 * The closed, host-derived status of one component TODO.
 *
 * Every status is a deterministic function of the accepted Brief and the genuine workspace state, so
 * the client never derives completion from model prose. `COMPLETE` is the only closed state.
 */
export const CAMPAIGN_TODO_STATUSES = [
  'NOT_STARTED',
  'IN_PROGRESS',
  'NEEDS_INPUT',
  'VALIDATING',
  'COMPLETE',
  'BLOCKED',
] as const
export type CampaignTodoStatus = (typeof CAMPAIGN_TODO_STATUSES)[number]

/**
 * The closed set of capabilities the component manifest tracks.
 *
 * Mirrors the server-owned capability domain. The client branches on the exact
 * code to render one stable business label. An unknown capability is refused.
 */
export const MANIFEST_CAPABILITY_CODES = [
  'SEGMENT',
  'EVENT',
  'MESSAGE',
  'PROMOTION',
  'FLOW',
  'SCHEDULE',
] as const
export type ManifestCapabilityCode = (typeof MANIFEST_CAPABILITY_CODES)[number]

/** The host-owned decision about whether a capability is needed. */
export const USAGE_DECISIONS = ['REQUIRED', 'NOT_REQUIRED', 'UNCERTAIN'] as const
export type UsageDecision = (typeof USAGE_DECISIONS)[number]

/** Who made the usage decision. MODEL is the initial model call; USER overrides via answer. */
export const DECISION_SOURCES = ['MODEL', 'USER', 'SYSTEM'] as const
export type DecisionSource = (typeof DECISION_SOURCES)[number]

/**
 * One capability-level decision in the current Campaign's confirmed component facts.
 *
 * It carries the capability identity, the host-derived usage decision, an optional
 * reason code and evidence, the selected resource codes when applicable, and the
 * decision source. It is read-only presentation data and is never submitted as a
 * mutation. It is not part of the existing {@link CampaignComponentTodo}.
 */
export interface ComponentManifestEntry {
  readonly capabilityCode: ManifestCapabilityCode
  readonly usageDecision: UsageDecision
  readonly reasonCode: string | null
  readonly evidence: string | null
  readonly selectedResourceCodes: readonly string[]
  readonly decisionSource: DecisionSource
}

/**
 * The closed set of clarification kinds the server may ask about.
 *
 * Hosts report these through the existing `code` field on a clarification or
 * component issue. The frontend uses the kind to render the appropriate answer
 * controls without hardcoding any business-scenario answer text.
 */
export const CLARIFICATION_KINDS = [
  'COMPONENT_DECISION',
  'RESOURCE_SELECTION',
  'RESOURCE_BLOCKED',
] as const
export type ClarificationKind = (typeof CLARIFICATION_KINDS)[number]

/**
 * One component-grouped TODO fact of the host projection.
 *
 * It carries only the stable component code, the closed status, the outstanding requirement codes
 * of that component and the bounded validation/blocker codes. It never carries a Skill identifier,
 * a Tool identifier, a model value or prose: the component code and the requirement codes are the
 * only vocabulary the user-facing progress may branch on, and the UI renders neither verbatim.
 */
export interface CampaignComponentTodo {
  readonly component: CampaignComponent
  readonly status: CampaignTodoStatus
  readonly requirements: readonly string[]
  readonly validationCodes: readonly string[]
}

/**
 * The accepted execution-schedule evidence of a Campaign requirement.
 *
 * Every field is a bounded accepted fact and every field is nullable: an absent field is a fact the
 * user has not supplied yet, never a default. It is the `schedule.basic/1.0` vocabulary in wire
 * form and carries no computed occurrence and no scheduling claim.
 */
export interface CampaignScheduleEvidence {
  readonly timeZone: string | null
  readonly runAt: string | null
  readonly localTime: string | null
  readonly startDate: string | null
  readonly endDate: string | null
}

export interface CampaignBranchRule {
  readonly condition: string
  readonly whenTrue: string
  readonly whenFalse: string
}

/** The safe, non-sensitive status of a confirmed Campaign business objective. */
export interface CampaignObjectiveStatus {
  readonly confirmed: boolean
  readonly evidenceLength: number
  readonly acceptedAtRevision: number
}

/** The server-owned accumulated requirement facts and remaining gaps. */
export interface CampaignRequirementProgress {
  readonly type: CampaignRequirementType | null
  readonly audience: string | null
  readonly offerDecision: OfferDecision
  readonly offerDetail: string | null
  readonly channels: readonly string[]
  readonly message: string | null
  readonly branchDecision: BranchDecision
  readonly branchDetail: string | null
  readonly branchRule: CampaignBranchRule | null
  readonly cadence: CampaignCadence | null
  /** The accepted execution-schedule facts, or null when the Brief holds none. */
  readonly schedule: CampaignScheduleEvidence | null
  /** The registered business event bound to an event-triggered Journey Start node. */
  readonly triggerEventCode: string | null
  readonly missing: readonly string[]
  readonly complete: boolean
  readonly unsupported: boolean
  /** The host-derived TODO of every component group, in the server-owned canonical order. */
  readonly componentTodo: readonly CampaignComponentTodo[]
  /** The safe objective status derived from the host-validated confirmed business objective. */
  readonly objective: CampaignObjectiveStatus
}

/**
 * One per-component pending issue in wire form.
 *
 * It carries the originating component's business-area code, its owning Skill, a stable
 * error/question code, the validated user-facing clarification question, the offending
 * question codes as safe evidence, the bounded answer options and the recoverable flag.
 * It is the per-component complement to the flattened {@link ConversationClarification}
 * — a frontend may use this list when it is present and falls back to the single
 * clarification otherwise. It never carries a stack trace, a physical table/column name,
 * a raw model output or an internal identifier.
 */
export interface ComponentIssue {
  readonly component: CampaignComponent
  readonly skillId: string
  readonly code: string
  readonly prompt: string
  readonly questionCodes: readonly string[]
  readonly options: readonly ClarificationOption[]
  readonly freeTextAllowed: boolean
}

export interface ConversationProjection {
  readonly accepted: boolean
  /** One bounded machine code when the turn was refused; the projection is unchanged then. */
  readonly code: string | null
  readonly conversationId: string
  readonly runId: string
  readonly revision: number
  readonly phase: ConversationPhase
  readonly disposition: string
  readonly routeKind: ConversationRouteKind | null
  readonly turns: readonly ConversationTurn[]
  readonly skills: readonly SkillProgress[]
  /**
   * The bounded ordered component views of the T27 inspection, in the server's
   * canonical identity order. Read-only presentation data.
   */
  readonly components: readonly ComponentProgress[]
  readonly clarification: ConversationClarification | null
  readonly assistantMessage: string | null
  readonly audience: ConversationAudience | null
  readonly inspectionHash: ContentHash
  /** Present only when the host produced one; assistant prose can never construct it. */
  readonly proposal: CampaignProposalPatch | null
  readonly proposalHash: ContentHash | null
  readonly followUpPrompt: string | null
  /**
   * The bounded Journey summary of the staged bundle, or null when no Journey is staged. It carries only
   * counts, the entry component identity and integrity hashes: never a node payload or a secret.
   */
  readonly journey: ConversationJourneySummary | null
  /** Present on hosts that expose accumulated Campaign requirement progress. */
  readonly requirements?: CampaignRequirementProgress | null
  /**
   * The per-component pending issue list, or null when the host has no per-component
   * issues to report. When present and non-empty it carries structured component
   * attribution for each pending clarification. Absent on older hosts or when the
   * single {@link clarification} field is the only question pending.
   */
  readonly componentIssues?: readonly ComponentIssue[] | null
  /**
   * The structured component manifest, or null when the host has not yet produced
   * one. Absent on older hosts or on the very first turn before the model has
   * returned a structured capability decision. Empty manifest is a valid present
   * state: it means the host has produced a manifest with no entries yet.
   */
  readonly componentManifest?: readonly ComponentManifestEntry[] | null
}

/** The bounded, non-secret Journey summary a host projection may carry. */
export interface ConversationJourneySummary {
  readonly nodeCount: number
  readonly edgeCount: number
  readonly entryComponentId: string
  readonly terminalCount: number
  readonly topologyHash: ContentHash
  readonly artifactHash: ContentHash
}

export interface CampaignClarificationAnswer {
  readonly code: string | null
  readonly optionId: string | null
  readonly freeText: string | null
  /**
   * The owning component group when multiple component issues are pending.
   *
   * It is required for every answer in a multi-component clarification submission.
   * Omitted only in legacy single-question answers where the owning component is
   * unambiguous. The server rejects a multi-component answer without it.
   */
  readonly component?: CampaignComponent | null
}

export interface CampaignTurnRequest {
  readonly conversationId: string
  readonly runId: string
  readonly expectedRevision: number
  readonly message: string
  readonly answers: readonly CampaignClarificationAnswer[]
  readonly at: string | null
}

const PROJECTION_FIELDS = [
  'accepted',
  'code',
  'conversationId',
  'runId',
  'revision',
  'phase',
  'disposition',
  'routeKind',
  'turns',
  'skills',
  'components',
  'clarification',
  'assistantMessage',
  'audience',
  'inspectionHash',
  'proposal',
  'proposalHash',
  'followUpPrompt',
  'journey',
  'requirements',
  'componentIssues',
  'componentManifest',
] as const

const TURN_FIELDS = ['turnId', 'role', 'ordinal', 'text', 'at'] as const
const SKILL_FIELDS = ['skillId', 'state', 'code', 'missingDependencyCodes'] as const
const COMPONENT_FIELDS = [
  'componentId',
  'skillId',
  'capabilityCode',
  'operationKind',
  'artifactHash',
] as const
const CLARIFICATION_FIELDS = [
  'skillId',
  'code',
  'prompt',
  'questionCodes',
  'options',
  'freeTextAllowed',
] as const
const OPTION_FIELDS = ['optionId', 'label'] as const
const AUDIENCE_FIELDS = ['status', 'matchedCount', 'asOf', 'timezone', 'dataWatermark', 'previewHash'] as const
const JOURNEY_FIELDS = [
  'nodeCount',
  'edgeCount',
  'entryComponentId',
  'terminalCount',
  'topologyHash',
  'artifactHash',
] as const
const REQUIREMENTS_FIELDS = [
  'type',
  'audience',
  'offerDecision',
  'offerDetail',
  'channels',
  'message',
  'branchDecision',
  'branchDetail',
  'branchRule',
  'cadence',
  'schedule',
  'triggerEventCode',
  'missing',
  'complete',
  'unsupported',
  'componentTodo',
  'objective',
] as const
const BRANCH_RULE_FIELDS = ['condition', 'whenTrue', 'whenFalse'] as const
const SCHEDULE_FIELDS = ['timeZone', 'runAt', 'localTime', 'startDate', 'endDate'] as const
const COMPONENT_TODO_FIELDS = ['component', 'status', 'requirements', 'validationCodes'] as const
const OBJECTIVE_FIELDS = ['confirmed', 'evidenceLength', 'acceptedAtRevision'] as const
const COMPONENT_ISSUE_FIELDS = [
  'component',
  'skillId',
  'code',
  'prompt',
  'questionCodes',
  'options',
  'freeTextAllowed',
] as const
const MANIFEST_ENTRY_FIELDS = [
  'capabilityCode',
  'usageDecision',
  'reasonCode',
  'evidence',
  'selectedResourceCodes',
  'decisionSource',
] as const

const MAX_TURNS = 64
const MAX_SKILLS = 16
const MAX_OPTIONS = 8
/** Mirrors the server-owned `CampaignAssemblyWorkspace.MAX_COMPONENTS` bound. */
const MAX_COMPONENTS = 256
/** Mirrors the closed component-group bound: one TODO per component group. */
const MAX_COMPONENT_TODOS = 16
/** The largest accepted outstanding-requirement or validation-code list of one component TODO. */
const MAX_TODO_CODES = 16
/** The largest accepted component manifest entry count. Mirrors server bound. */
const MAX_MANIFEST_ENTRIES = 16
/** The largest accepted selected resource code list per manifest entry. */
const MAX_MANIFEST_RESOURCES = 8

function nullable(value: unknown): JsonValue | undefined {
  return value === undefined || value === null ? undefined : (value as JsonValue)
}

function decodeTurn(raw: unknown, path: string): ConversationTurn {
  const obj = requireObject(raw, path)
  requireExactFields(obj, TURN_FIELDS, path)
  const atRaw = nullable(optionalField(obj, 'at'))
  return {
    turnId: requirePattern(field(obj, 'turnId', path), REFERENCE_ID_PATTERN, `${path}.turnId`, 'reference-id'),
    role: requireEnumValue(field(obj, 'role', path), CONVERSATION_ROLES, `${path}.role`),
    ordinal: requireNonNegativeInteger(field(obj, 'ordinal', path), `${path}.ordinal`),
    text: requireString(field(obj, 'text', path), `${path}.text`),
    at: atRaw === undefined ? null : requireString(atRaw, `${path}.at`),
  }
}

function decodeSkill(raw: unknown, path: string): SkillProgress {
  const obj = requireObject(raw, path)
  requireExactFields(obj, SKILL_FIELDS, path)
  const codeRaw = nullable(optionalField(obj, 'code'))
  return {
    skillId: requirePattern(field(obj, 'skillId', path), REFERENCE_ID_PATTERN, `${path}.skillId`, 'reference-id'),
    state: requireEnumValue(field(obj, 'state', path), SKILL_PROGRESS_STATES, `${path}.state`),
    code: codeRaw === undefined ? null : requireString(codeRaw, `${path}.code`),
    missingDependencyCodes: requireStringArray(
      field(obj, 'missingDependencyCodes', path),
      `${path}.missingDependencyCodes`,
      16,
    ),
  }
}

function decodeComponent(raw: unknown, path: string): ComponentProgress {
  const obj = requireObject(raw, path)
  requireExactFields(obj, COMPONENT_FIELDS, path)
  const capabilityRaw = nullable(optionalField(obj, 'capabilityCode'))
  return {
    componentId: requirePattern(
      field(obj, 'componentId', path),
      REFERENCE_ID_PATTERN,
      `${path}.componentId`,
      'reference-id',
    ),
    skillId: requirePattern(field(obj, 'skillId', path), REFERENCE_ID_PATTERN, `${path}.skillId`, 'reference-id'),
    capabilityCode:
      capabilityRaw === undefined
        ? null
        : requirePattern(capabilityRaw, CAPABILITY_CODE_PATTERN, `${path}.capabilityCode`, 'capability-code'),
    operationKind: requireEnumValue(
      field(obj, 'operationKind', path),
      PROPOSAL_OPERATION_KINDS,
      `${path}.operationKind`,
    ),
    artifactHash: requirePattern(
      field(obj, 'artifactHash', path),
      CONTENT_HASH_PATTERN,
      `${path}.artifactHash`,
      'content-hash',
    ) as ContentHash,
  }
}

function compareComponents(prior: ComponentProgress, next: ComponentProgress): number {
  if (prior.componentId !== next.componentId) {
    return prior.componentId < next.componentId ? -1 : 1
  }
  if (prior.operationKind !== next.operationKind) {
    return prior.operationKind < next.operationKind ? -1 : 1
  }
  return 0
}

/**
 * Rejects a duplicate identity or a non-canonical component order.
 *
 * The server owns the order (component identity, then operation kind), so a
 * reordered or repeated entry is refused rather than silently rendered.
 */
function requireCanonicalComponents(components: readonly ComponentProgress[], path: string): void {
  for (let index = 1; index < components.length; index += 1) {
    const order = compareComponents(components[index - 1] as ComponentProgress, components[index] as ComponentProgress)
    if (order === 0) {
      throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, `${path}[${index}]`, 'duplicate-component')
    }
    if (order > 0) {
      throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, `${path}[${index}]`, 'non-canonical-order')
    }
  }
}

function decodeOption(raw: unknown, path: string): ClarificationOption {
  const obj = requireObject(raw, path)
  requireExactFields(obj, OPTION_FIELDS, path)
  return {
    optionId: requireString(field(obj, 'optionId', path), `${path}.optionId`),
    label: requireString(field(obj, 'label', path), `${path}.label`),
  }
}

function decodeClarification(raw: unknown, path: string): ConversationClarification {
  const obj = requireObject(raw, path)
  requireExactFields(obj, CLARIFICATION_FIELDS, path)
  const skillRaw = nullable(optionalField(obj, 'skillId'))
  const options = requireArray(field(obj, 'options', path), `${path}.options`)
  if (options.length > MAX_OPTIONS) {
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, `${path}.options`, 'array-too-long')
  }
  return {
    skillId: skillRaw === undefined ? null : requireString(skillRaw, `${path}.skillId`),
    code: requirePattern(field(obj, 'code', path), MACHINE_CODE_PATTERN, `${path}.code`, 'machine-code'),
    prompt: requireString(field(obj, 'prompt', path), `${path}.prompt`),
    questionCodes: requireStringArray(field(obj, 'questionCodes', path), `${path}.questionCodes`, 4),
    options: options.map((item, index) => decodeOption(item, `${path}.options[${index}]`)),
    freeTextAllowed: requireBoolean(field(obj, 'freeTextAllowed', path), `${path}.freeTextAllowed`),
  }
}

function decodeAudience(raw: unknown, path: string): ConversationAudience {
  const obj = requireObject(raw, path)
  requireExactFields(obj, AUDIENCE_FIELDS, path)
  return {
    status: requireString(field(obj, 'status', path), `${path}.status`),
    matchedCount: requireNonNegativeInteger(field(obj, 'matchedCount', path), `${path}.matchedCount`),
    asOf: requireString(field(obj, 'asOf', path), `${path}.asOf`),
    timezone: requireString(field(obj, 'timezone', path), `${path}.timezone`),
    dataWatermark: requireString(field(obj, 'dataWatermark', path), `${path}.dataWatermark`),
    previewHash: requirePattern(
      field(obj, 'previewHash', path),
      CONTENT_HASH_PATTERN,
      `${path}.previewHash`,
      'content-hash',
    ) as ContentHash,
  }
}

function decodeScheduleEvidence(raw: unknown, path: string): CampaignScheduleEvidence {
  const obj = requireObject(raw, path)
  requireExactFields(obj, SCHEDULE_FIELDS, path)
  const optionalBoundedText = (name: string): string | null => {
    const value = nullable(optionalField(obj, name))
    return value === undefined ? null : requireString(value, `${path}.${name}`)
  }
  return {
    timeZone: optionalBoundedText('timeZone'),
    runAt: optionalBoundedText('runAt'),
    localTime: optionalBoundedText('localTime'),
    startDate: optionalBoundedText('startDate'),
    endDate: optionalBoundedText('endDate'),
  }
}

function decodeComponentTodo(raw: unknown, path: string): CampaignComponentTodo {
  const obj = requireObject(raw, path)
  requireExactFields(obj, COMPONENT_TODO_FIELDS, path)
  return {
    component: requireEnumValue(field(obj, 'component', path), CAMPAIGN_COMPONENTS, `${path}.component`),
    status: requireEnumValue(field(obj, 'status', path), CAMPAIGN_TODO_STATUSES, `${path}.status`),
    requirements: requireStringArray(field(obj, 'requirements', path), `${path}.requirements`, MAX_TODO_CODES)
      .map((code, index) => requirePattern(code, MACHINE_CODE_PATTERN, `${path}.requirements[${index}]`, 'machine-code')),
    validationCodes: requireStringArray(
      field(obj, 'validationCodes', path),
      `${path}.validationCodes`,
      MAX_TODO_CODES,
    ).map((code, index) =>
      requirePattern(code, MACHINE_CODE_PATTERN, `${path}.validationCodes[${index}]`, 'machine-code'),
    ),
  }
}

function decodeComponentIssue(raw: unknown, path: string): ComponentIssue {
  const obj = requireObject(raw, path)
  requireExactFields(obj, COMPONENT_ISSUE_FIELDS, path)
  const options = requireArray(field(obj, 'options', path), `${path}.options`)
  if (options.length > MAX_OPTIONS) {
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, `${path}.options`, 'array-too-long')
  }
  return {
    component: requireEnumValue(field(obj, 'component', path), CAMPAIGN_COMPONENTS, `${path}.component`),
    skillId: requirePattern(
      field(obj, 'skillId', path),
      REFERENCE_ID_PATTERN,
      `${path}.skillId`,
      'reference-id',
    ),
    code: requirePattern(field(obj, 'code', path), MACHINE_CODE_PATTERN, `${path}.code`, 'machine-code'),
    prompt: requireString(field(obj, 'prompt', path), `${path}.prompt`),
    questionCodes: requireStringArray(field(obj, 'questionCodes', path), `${path}.questionCodes`, MAX_TODO_CODES)
      .map((code, index) =>
        requirePattern(code, MACHINE_CODE_PATTERN, `${path}.questionCodes[${index}]`, 'machine-code'),
      ),
    options: options.map((item, index) => decodeOption(item, `${path}.options[${index}]`)),
    freeTextAllowed: requireBoolean(field(obj, 'freeTextAllowed', path), `${path}.freeTextAllowed`),
  }
}

function decodeComponentManifestEntry(raw: unknown, path: string): ComponentManifestEntry {
  const obj = requireObject(raw, path)
  requireExactFields(obj, MANIFEST_ENTRY_FIELDS, path)
  const reasonCodeRaw = nullable(optionalField(obj, 'reasonCode'))
  const evidenceRaw = nullable(optionalField(obj, 'evidence'))
  return {
    capabilityCode: requireEnumValue(
      field(obj, 'capabilityCode', path),
      MANIFEST_CAPABILITY_CODES,
      `${path}.capabilityCode`,
    ),
    usageDecision: requireEnumValue(
      field(obj, 'usageDecision', path),
      USAGE_DECISIONS,
      `${path}.usageDecision`,
    ),
    reasonCode:
      reasonCodeRaw === undefined
        ? null
        : requirePattern(reasonCodeRaw, MACHINE_CODE_PATTERN, `${path}.reasonCode`, 'machine-code'),
    evidence: evidenceRaw === undefined ? null : requireString(evidenceRaw, `${path}.evidence`),
    selectedResourceCodes: requireStringArray(
      field(obj, 'selectedResourceCodes', path),
      `${path}.selectedResourceCodes`,
      MAX_MANIFEST_RESOURCES,
    ).map((code, index) =>
      requirePattern(code, MACHINE_CODE_PATTERN, `${path}.selectedResourceCodes[${index}]`, 'machine-code'),
    ),
    decisionSource: requireEnumValue(
      field(obj, 'decisionSource', path),
      DECISION_SOURCES,
      `${path}.decisionSource`,
    ),
  }
}

/**
 * Rejects a duplicate capability or a non-canonical manifest order.
 *
 * The server owns the order, so a reordered or repeated capability is refused.
 */
function requireCanonicalManifest(
  entries: readonly ComponentManifestEntry[],
  path: string,
): void {
  const rank = (code: ManifestCapabilityCode): number => MANIFEST_CAPABILITY_CODES.indexOf(code)
  for (let index = 1; index < entries.length; index += 1) {
    const prior = rank((entries[index - 1] as ComponentManifestEntry).capabilityCode)
    const next = rank((entries[index] as ComponentManifestEntry).capabilityCode)
    if (next === prior) {
      throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, `${path}[${index}]`, 'duplicate-capability')
    }
    if (next < prior) {
      throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, `${path}[${index}]`, 'non-canonical-order')
    }
  }
}

/**
 * Rejects a duplicate component group or a non-canonical TODO order.
 *
 * The server owns the order (the declared component-group order), so a reordered or repeated group
 * is refused rather than rendered as progress the host did not report.
 */
function requireCanonicalComponentTodos(
  todos: readonly CampaignComponentTodo[],
  path: string,
): void {
  const rank = (component: CampaignComponent): number => CAMPAIGN_COMPONENTS.indexOf(component)
  for (let index = 1; index < todos.length; index += 1) {
    const prior = rank((todos[index - 1] as CampaignComponentTodo).component)
    const next = rank((todos[index] as CampaignComponentTodo).component)
    if (next === prior) {
      throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, `${path}[${index}]`, 'duplicate-component-group')
    }
    if (next < prior) {
      throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, `${path}[${index}]`, 'non-canonical-order')
    }
  }
}

function decodeObjective(raw: unknown, path: string): CampaignObjectiveStatus {
  const obj = requireObject(raw, path)
  requireExactFields(obj, OBJECTIVE_FIELDS, path)
  return {
    confirmed: requireBoolean(field(obj, 'confirmed', path), `${path}.confirmed`),
    evidenceLength: requireNonNegativeInteger(field(obj, 'evidenceLength', path), `${path}.evidenceLength`),
    acceptedAtRevision: requireInteger(field(obj, 'acceptedAtRevision', path), `${path}.acceptedAtRevision`),
  }
}

function decodeRequirementProgress(raw: unknown, path: string): CampaignRequirementProgress {
  const obj = requireObject(raw, path)
  requireExactFields(obj, REQUIREMENTS_FIELDS, path)
  const typeRaw = nullable(optionalField(obj, 'type'))
  const audienceRaw = nullable(optionalField(obj, 'audience'))
  const offerDetailRaw = nullable(optionalField(obj, 'offerDetail'))
  const messageRaw = nullable(optionalField(obj, 'message'))
  const branchDetailRaw = nullable(optionalField(obj, 'branchDetail'))
  const branchRuleRaw = nullable(optionalField(obj, 'branchRule'))
  const cadenceRaw = nullable(optionalField(obj, 'cadence'))
  const scheduleRaw = nullable(optionalField(obj, 'schedule'))
  const triggerEventCodeRaw = nullable(optionalField(obj, 'triggerEventCode'))
  const componentTodoRaw = nullable(optionalField(obj, 'componentTodo'))
  let branchRule: CampaignBranchRule | null = null
  if (branchRuleRaw !== undefined) {
    const rule = requireObject(branchRuleRaw, `${path}.branchRule`)
    requireExactFields(rule, BRANCH_RULE_FIELDS, `${path}.branchRule`)
    branchRule = {
      condition: requireString(field(rule, 'condition', `${path}.branchRule`), `${path}.branchRule.condition`),
      whenTrue: requireString(field(rule, 'whenTrue', `${path}.branchRule`), `${path}.branchRule.whenTrue`),
      whenFalse: requireString(field(rule, 'whenFalse', `${path}.branchRule`), `${path}.branchRule.whenFalse`),
    }
  }
  let componentTodo: readonly CampaignComponentTodo[] = []
  if (componentTodoRaw !== undefined) {
    const items = requireArray(componentTodoRaw, `${path}.componentTodo`)
    if (items.length > MAX_COMPONENT_TODOS) {
      throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, `${path}.componentTodo`, 'array-too-long')
    }
    componentTodo = items.map((item, index) =>
      decodeComponentTodo(item, `${path}.componentTodo[${index}]`),
    )
    requireCanonicalComponentTodos(componentTodo, `${path}.componentTodo`)
  }
  return {
    type: typeRaw === undefined
      ? null
      : requireEnumValue(typeRaw, CAMPAIGN_REQUIREMENT_TYPES, `${path}.type`),
    audience: audienceRaw === undefined ? null : requireString(audienceRaw, `${path}.audience`),
    offerDecision: requireEnumValue(
      field(obj, 'offerDecision', path),
      OFFER_DECISIONS,
      `${path}.offerDecision`,
    ),
    offerDetail: offerDetailRaw === undefined ? null : requireString(offerDetailRaw, `${path}.offerDetail`),
    channels: requireStringArray(field(obj, 'channels', path), `${path}.channels`, 16),
    message: messageRaw === undefined ? null : requireString(messageRaw, `${path}.message`),
    branchDecision: requireEnumValue(
      field(obj, 'branchDecision', path),
      BRANCH_DECISIONS,
      `${path}.branchDecision`,
    ),
    branchDetail: branchDetailRaw === undefined ? null : requireString(branchDetailRaw, `${path}.branchDetail`),
    branchRule,
    cadence: cadenceRaw === undefined
      ? null
      : requireEnumValue(cadenceRaw, CAMPAIGN_CADENCES, `${path}.cadence`),
    schedule: scheduleRaw === undefined ? null : decodeScheduleEvidence(scheduleRaw, `${path}.schedule`),
    triggerEventCode: triggerEventCodeRaw === undefined
      ? null
      : requireString(triggerEventCodeRaw, `${path}.triggerEventCode`),
    missing: requireStringArray(field(obj, 'missing', path), `${path}.missing`, 32),
    complete: requireBoolean(field(obj, 'complete', path), `${path}.complete`),
    unsupported: requireBoolean(field(obj, 'unsupported', path), `${path}.unsupported`),
    componentTodo,
    objective: decodeObjective(field(obj, 'objective', path), `${path}.objective`),
  }
}

/**
 * Strictly decodes one conversation projection.
 *
 * It rejects an unknown field, a missing field, a wrong primitive, a malformed
 * hash, a malformed revision and a Proposal whose own canonical bytes do not
 * hash to the hash the host published alongside it.
 */
export function decodeConversationProjection(raw: unknown, path: string): ConversationProjection {
  const obj = requireObject(raw, path)
  requireExactFields(obj, PROJECTION_FIELDS, path)

  const accepted = requireBoolean(field(obj, 'accepted', path), `${path}.accepted`)
  const codeRaw = nullable(optionalField(obj, 'code'))
  const code = codeRaw === undefined
    ? null
    : requirePattern(codeRaw, MACHINE_CODE_PATTERN, `${path}.code`, 'machine-code')
  if (accepted === (code !== null)) {
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, `${path}.code`, 'accepted-code-disagree')
  }

  const routeRaw = nullable(optionalField(obj, 'routeKind'))
  const assistantRaw = nullable(optionalField(obj, 'assistantMessage'))
  const followUpRaw = nullable(optionalField(obj, 'followUpPrompt'))
  const proposalRaw = nullable(optionalField(obj, 'proposal'))
  const proposalHashRaw = nullable(optionalField(obj, 'proposalHash'))

  const proposal = proposalRaw === undefined
    ? null
    : decodeCampaignProposalPatch(proposalRaw)
  const proposalHash = proposalHashRaw === undefined
    ? null
    : (requirePattern(
        proposalHashRaw,
        CONTENT_HASH_PATTERN,
        `${path}.proposalHash`,
        'content-hash',
      ) as ContentHash)
  if ((proposal === null) !== (proposalHash === null)) {
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, `${path}.proposalHash`, 'proposal-pair')
  }
  if (proposal !== null && proposalHash !== null) {
    // The host-provided Proposal is the only Proposal the UI may submit. Its own
    // canonical bytes must hash to the published hash, so a tampered or
    // mis-stated Proposal is refused here rather than compiled later.
    const recomputed = contentHashOfCanonical(encodeCampaignProposalPatch(proposal))
    if (recomputed !== proposalHash) {
      throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, `${path}.proposalHash`, 'proposal-hash-mismatch')
    }
  }

  const turns = requireArray(field(obj, 'turns', path), `${path}.turns`)
  if (turns.length > MAX_TURNS) {
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, `${path}.turns`, 'array-too-long')
  }
  const skills = requireArray(field(obj, 'skills', path), `${path}.skills`)
  if (skills.length > MAX_SKILLS) {
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, `${path}.skills`, 'array-too-long')
  }
  const components = requireArray(field(obj, 'components', path), `${path}.components`)
  if (components.length > MAX_COMPONENTS) {
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, `${path}.components`, 'array-too-long')
  }
  const decodedComponents = components.map((item, index) =>
    decodeComponent(item, `${path}.components[${index}]`),
  )
  requireCanonicalComponents(decodedComponents, `${path}.components`)
  const clarificationRaw = nullable(optionalField(obj, 'clarification'))
  const audienceRaw = nullable(optionalField(obj, 'audience'))
  const journeyRaw = nullable(optionalField(obj, 'journey'))
  const requirementsRaw = nullable(optionalField(obj, 'requirements'))
  const componentIssuesRaw = nullable(optionalField(obj, 'componentIssues'))
  const componentManifestRaw = nullable(optionalField(obj, 'componentManifest'))
  const journey = journeyRaw === undefined
    ? null
    : decodeJourneySummary(journeyRaw, `${path}.journey`)
  if ((journey !== null) !== decodedComponents.some((component) => component.skillId === JOURNEY_SKILL_ID)) {
    // A Journey summary describes a staged bundle, so the two facts cannot disagree.
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, `${path}.journey`, 'journey-pair')
  }

  return {
    accepted,
    code,
    conversationId: requirePattern(
      field(obj, 'conversationId', path),
      REFERENCE_ID_PATTERN,
      `${path}.conversationId`,
      'reference-id',
    ),
    runId: requirePattern(field(obj, 'runId', path), REFERENCE_ID_PATTERN, `${path}.runId`, 'reference-id'),
    revision: requireNonNegativeInteger(field(obj, 'revision', path), `${path}.revision`),
    phase: requireEnumValue(field(obj, 'phase', path), CONVERSATION_PHASES, `${path}.phase`),
    disposition: requireString(field(obj, 'disposition', path), `${path}.disposition`),
    routeKind: routeRaw === undefined
      ? null
      : requireEnumValue(routeRaw, CONVERSATION_ROUTE_KINDS, `${path}.routeKind`),
    turns: turns.map((item, index) => decodeTurn(item, `${path}.turns[${index}]`)),
    skills: skills.map((item, index) => decodeSkill(item, `${path}.skills[${index}]`)),
    components: decodedComponents,
    clarification: clarificationRaw === undefined
      ? null
      : decodeClarification(clarificationRaw, `${path}.clarification`),
    assistantMessage: assistantRaw === undefined ? null : requireString(assistantRaw, `${path}.assistantMessage`),
    audience: audienceRaw === undefined ? null : decodeAudience(audienceRaw, `${path}.audience`),
    inspectionHash: requirePattern(
      field(obj, 'inspectionHash', path),
      CONTENT_HASH_PATTERN,
      `${path}.inspectionHash`,
      'content-hash',
    ) as ContentHash,
    proposal,
    proposalHash,
    followUpPrompt: followUpRaw === undefined ? null : requireString(followUpRaw, `${path}.followUpPrompt`),
    journey,
    requirements: requirementsRaw === undefined
      ? null
      : decodeRequirementProgress(requirementsRaw, `${path}.requirements`),
    componentIssues: decodeOptionalComponentIssues(componentIssuesRaw, `${path}.componentIssues`),
    componentManifest: decodeOptionalComponentManifest(componentManifestRaw, `${path}.componentManifest`),
  }
}

/** Decodes the optional per-component issue list, or null when absent or empty. */
function decodeOptionalComponentIssues(
  raw: unknown,
  path: string,
): readonly ComponentIssue[] | null {
  if (raw === undefined || raw === null) {
    return null
  }
  const items = requireArray(raw, path)
  if (items.length === 0) {
    return null
  }
  if (items.length > MAX_COMPONENT_TODOS) {
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, path, 'array-too-long')
  }
  return items.map((item, index) => decodeComponentIssue(item, `${path}[${index}]`))
}

/** Decodes the optional component manifest list, or null when absent or empty. */
function decodeOptionalComponentManifest(
  raw: unknown,
  path: string,
): readonly ComponentManifestEntry[] | null {
  if (raw === undefined || raw === null) {
    return null
  }
  const items = requireArray(raw, path)
  if (items.length === 0) {
    return null
  }
  if (items.length > MAX_MANIFEST_ENTRIES) {
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, path, 'array-too-long')
  }
  const entries = items.map((item, index) =>
    decodeComponentManifestEntry(item, `${path}[${index}]`),
  )
  requireCanonicalManifest(entries, path)
  return entries
}

/** The one public Skill identifier a Journey bundle is staged under; it is never a capability code. */
export const JOURNEY_SKILL_ID = 'journey.flow'

function decodeJourneySummary(raw: unknown, path: string): ConversationJourneySummary {
  const obj = requireObject(raw, path)
  requireExactFields(obj, JOURNEY_FIELDS, path)
  return {
    nodeCount: requireNonNegativeInteger(field(obj, 'nodeCount', path), `${path}.nodeCount`),
    edgeCount: requireNonNegativeInteger(field(obj, 'edgeCount', path), `${path}.edgeCount`),
    entryComponentId: requirePattern(
      field(obj, 'entryComponentId', path),
      REFERENCE_ID_PATTERN,
      `${path}.entryComponentId`,
      'reference-id',
    ),
    terminalCount: requireNonNegativeInteger(field(obj, 'terminalCount', path), `${path}.terminalCount`),
    topologyHash: requirePattern(
      field(obj, 'topologyHash', path),
      CONTENT_HASH_PATTERN,
      `${path}.topologyHash`,
      'content-hash',
    ) as ContentHash,
    artifactHash: requirePattern(
      field(obj, 'artifactHash', path),
      CONTENT_HASH_PATTERN,
      `${path}.artifactHash`,
      'content-hash',
    ) as ContentHash,
  }
}

/** Encodes one bounded turn request body. */
export function encodeCampaignTurnRequest(request: CampaignTurnRequest): JsonObject {
  const encoded: JsonObject = {
    conversationId: request.conversationId,
    runId: request.runId,
    expectedRevision: request.expectedRevision,
    message: request.message,
    answers: request.answers.map((answer): JsonValue => ({
      code: answer.code,
      optionId: answer.optionId,
      freeText: answer.freeText,
      component: answer.component ?? null,
    })),
    at: request.at,
  }
  return encoded
}

/**
 * The canonical clarification kind of a conversation clarification, or null when
 * the code does not match one of the known clarification-kind codes.
 *
 * A clarification may carry a legacy code that predates {@link CLARIFICATION_KINDS};
 * those return null here without failing the decoder.
 */
export function clarificationKindOf(
  clarification: ConversationClarification,
): ClarificationKind | null {
  const code = clarification.code
  for (const kind of CLARIFICATION_KINDS) {
    if (code === kind) {
      return kind
    }
    // Also match legacy compound-style codes like COMPONENT_DECISION_*
    if (code.startsWith(`${kind}_`)) {
      return kind
    }
  }
  return null
}

/**
 * The visible current goal.
 *
 * It is derived from the host-reported route and phase, never from the user's
 * wording, so the UI cannot disagree with the host about what is in progress.
 */
export function goalOf(projection: ConversationProjection): CampaignGoal {
  if (projection.phase === 'AUDIENCE_READY' || projection.routeKind === 'AUDIENCE_REQUEST') {
    return 'AUDIENCE'
  }
  return 'CAMPAIGN'
}

/** True when the conversation is waiting for a typed clarification. */
export function isAwaitingClarification(projection: ConversationProjection): boolean {
  return projection.phase === 'AWAITING_CLARIFICATION' && projection.clarification !== null
}
