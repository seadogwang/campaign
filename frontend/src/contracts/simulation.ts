/**
 * Journey Simulation projection (accepted T20 contract).
 *
 * The `SIMULATED` marker is part of the contract, not a presentation choice:
 * a simulation report that does not declare it is rejected here too. A
 * simulation never creates an Execution, Participant, Action, budget
 * reservation, runtime task or publish confirmation.
 */

import { UI_ERROR_CODES, contractError } from './errors'
import { ISO_INSTANT_PATTERN, MACHINE_CODE_PATTERN, REFERENCE_ID_PATTERN } from './primitives'
import {
  field,
  optionalField,
  requireEnumValue,
  requireExactFields,
  requireObject,
  requireObjectArray,
  requirePattern,
  requireString,
  requireStringArray,
  type JsonValue,
} from './validate'
import { decodeIssue, type PlanIssue } from './plan'

export const SIMULATED_ENVIRONMENT = 'SIMULATED' as const
export const SIMULATED_PATH_STATUSES = ['COMPLETED', 'EXITED', 'ERRORED'] as const
export type SimulatedPathStatus = (typeof SIMULATED_PATH_STATUSES)[number]
export const SIMULATED_ACTION_KINDS = ['MESSAGE', 'REWARD'] as const
export type SimulatedActionKind = (typeof SIMULATED_ACTION_KINDS)[number]
export const BRANCH_KINDS = ['TRUE', 'FALSE'] as const
export type BranchKind = (typeof BRANCH_KINDS)[number]

export const MAX_PATHS = 64
export const MAX_STEPS_PER_PATH = 256
export const MAX_ACTIONS = 256
export const MAX_ASSUMPTIONS = 256
export const MAX_SIMULATION_ISSUES = 64

export interface SimulatedAssumption {
  readonly nodeLogicalId: string
  readonly factCode: string
  readonly assumedBranch: BranchKind
}

export interface SimulatedAction {
  readonly kind: SimulatedActionKind
  readonly nodeLogicalId: string
  readonly virtualInstant: string
}

export interface SimulatedPath {
  readonly status: SimulatedPathStatus
  readonly terminalNodeLogicalId: string
  readonly visitedNodeLogicalIds: readonly string[]
  readonly entryVirtualInstants: readonly string[]
  readonly actions: readonly SimulatedAction[]
  readonly assumptions: readonly SimulatedAssumption[]
  readonly errorReasonCode: string | null
}

export interface SimulationSummary {
  readonly environment: typeof SIMULATED_ENVIRONMENT
  readonly simulationFormatVersion: string
  readonly simulatorVersion: string
  readonly runtimeSchemaVersion: string
  readonly virtualStartInstant: string
  readonly virtualTimezone: string
  readonly paths: readonly SimulatedPath[]
  readonly issues: readonly PlanIssue[]
  readonly assumptions: readonly SimulatedAssumption[]
  readonly unknownBranchCount: number
  readonly virtualActionCount: number
}

const ASSUMPTION_FIELDS = ['nodeLogicalId', 'factCode', 'assumedBranch'] as const
const ACTION_FIELDS = ['kind', 'nodeLogicalId', 'virtualInstant'] as const
const PATH_FIELDS = [
  'status',
  'terminalNodeLogicalId',
  'visitedNodeLogicalIds',
  'entryVirtualInstants',
  'actions',
  'assumptions',
  'errorReasonCode',
] as const
const SIMULATION_FIELDS = [
  'environment',
  'simulationFormatVersion',
  'simulatorVersion',
  'runtimeSchemaVersion',
  'virtualStartInstant',
  'virtualTimezone',
  'paths',
  'issues',
] as const

function decodeAssumption(raw: JsonValue, path: string): SimulatedAssumption {
  const obj = requireObject(raw, path)
  requireExactFields(obj, ASSUMPTION_FIELDS, path)
  return {
    nodeLogicalId: requirePattern(field(obj, 'nodeLogicalId', path), REFERENCE_ID_PATTERN, `${path}.nodeLogicalId`, 'reference-id'),
    factCode: requirePattern(field(obj, 'factCode', path), MACHINE_CODE_PATTERN, `${path}.factCode`, 'machine-code'),
    assumedBranch: requireEnumValue(field(obj, 'assumedBranch', path), BRANCH_KINDS, `${path}.assumedBranch`),
  }
}

function decodeAction(raw: JsonValue, path: string): SimulatedAction {
  const obj = requireObject(raw, path)
  requireExactFields(obj, ACTION_FIELDS, path)
  return {
    kind: requireEnumValue(field(obj, 'kind', path), SIMULATED_ACTION_KINDS, `${path}.kind`),
    nodeLogicalId: requirePattern(field(obj, 'nodeLogicalId', path), REFERENCE_ID_PATTERN, `${path}.nodeLogicalId`, 'reference-id'),
    virtualInstant: requirePattern(field(obj, 'virtualInstant', path), ISO_INSTANT_PATTERN, `${path}.virtualInstant`, 'instant'),
  }
}

function decodePath(raw: JsonValue, path: string): SimulatedPath {
  const obj = requireObject(raw, path)
  requireExactFields(obj, PATH_FIELDS, path)
  const status = requireEnumValue(field(obj, 'status', path), SIMULATED_PATH_STATUSES, `${path}.status`)
  const errorReasonCodeRaw = optionalField(obj, 'errorReasonCode')
  const errorReasonCode = errorReasonCodeRaw === undefined || errorReasonCodeRaw === null
    ? null
    : requirePattern(errorReasonCodeRaw, MACHINE_CODE_PATTERN, `${path}.errorReasonCode`, 'machine-code')
  if (status === 'ERRORED' && errorReasonCode === null) {
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, `${path}.errorReasonCode`, 'errored-requires-reason')
  }
  if (status !== 'ERRORED' && errorReasonCode !== null) {
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, `${path}.errorReasonCode`, 'non-errored-reason')
  }
  const visitedNodeLogicalIds = requireStringArray(
    field(obj, 'visitedNodeLogicalIds', path),
    `${path}.visitedNodeLogicalIds`,
    MAX_STEPS_PER_PATH,
  )
  const entryVirtualInstants = requireStringArray(
    field(obj, 'entryVirtualInstants', path),
    `${path}.entryVirtualInstants`,
    MAX_STEPS_PER_PATH,
  ).map((instant, index) =>
    requirePattern(instant, ISO_INSTANT_PATTERN, `${path}.entryVirtualInstants[${index}]`, 'instant'),
  )
  if (visitedNodeLogicalIds.length !== entryVirtualInstants.length) {
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, path, 'virtual-clock-parallel')
  }
  return {
    status,
    terminalNodeLogicalId: requirePattern(
      field(obj, 'terminalNodeLogicalId', path),
      REFERENCE_ID_PATTERN,
      `${path}.terminalNodeLogicalId`,
      'reference-id',
    ),
    visitedNodeLogicalIds,
    entryVirtualInstants,
    actions: requireObjectArray(field(obj, 'actions', path), `${path}.actions`, MAX_ACTIONS)
      .map(({ item, path: itemPath }) => decodeAction(item, itemPath)),
    assumptions: requireObjectArray(field(obj, 'assumptions', path), `${path}.assumptions`, MAX_ASSUMPTIONS)
      .map(({ item, path: itemPath }) => decodeAssumption(item, itemPath)),
    errorReasonCode,
  }
}

export function decodeSimulationSummary(raw: unknown, path: string): SimulationSummary {
  const obj = requireObject(raw, path)
  requireExactFields(obj, SIMULATION_FIELDS, path)
  const environment = requireString(field(obj, 'environment', path), `${path}.environment`)
  if (environment !== SIMULATED_ENVIRONMENT) {
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, `${path}.environment`, 'simulated-marker')
  }
  const paths = requireObjectArray(field(obj, 'paths', path), `${path}.paths`, MAX_PATHS)
    .map(({ item, path: itemPath }) => decodePath(item, itemPath))
  const issues = requireObjectArray(field(obj, 'issues', path), `${path}.issues`, MAX_SIMULATION_ISSUES)
    .map(({ item, path: itemPath }) => decodeIssue(item, itemPath))
  const assumptions = paths.flatMap((item) => item.assumptions)
  const virtualActionCount = paths.reduce((total, item) => total + item.actions.length, 0)
  if (virtualActionCount > MAX_ACTIONS) {
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, path, 'action-limit')
  }
  return {
    environment: SIMULATED_ENVIRONMENT,
    simulationFormatVersion: requireString(
      field(obj, 'simulationFormatVersion', path),
      `${path}.simulationFormatVersion`,
    ),
    simulatorVersion: requireString(field(obj, 'simulatorVersion', path), `${path}.simulatorVersion`),
    runtimeSchemaVersion: requireString(field(obj, 'runtimeSchemaVersion', path), `${path}.runtimeSchemaVersion`),
    virtualStartInstant: requirePattern(
      field(obj, 'virtualStartInstant', path),
      ISO_INSTANT_PATTERN,
      `${path}.virtualStartInstant`,
      'instant',
    ),
    virtualTimezone: requireString(field(obj, 'virtualTimezone', path), `${path}.virtualTimezone`),
    paths,
    issues,
    assumptions,
    unknownBranchCount: assumptions.length,
    virtualActionCount,
  }
}

export function simulationIssueCount(summary: SimulationSummary): number {
  return summary.issues.length + summary.paths.filter((item) => item.status === 'ERRORED').length
}