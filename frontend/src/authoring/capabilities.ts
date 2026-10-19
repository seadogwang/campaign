/**
 * Capability codes and bounded authoring payload field sets.
 *
 * Field names are mirrored from the accepted packs where the pack fixes them:
 *  - `message.template`      -> `referenceCode`, `variables`, `channel`
 *  - `promotion.reference`   -> `referenceCode`, `quantity`
 *  - `governance.limit`      -> `maxRecipients`, `maxRewardGrants`, `maxRewardFaceValue`, `currency`
 *  - `schedule.basic`        -> `form`, `zoneId`, `runAt`, `localTime`, `startDate`, `endDate`
 *  - `segment.rule-based`    -> the `segment-authoring-envelope/1.0` fields, of which this development
 *    surface authors `envelopeVersion` and `dslWire`; the derived `canonicalAstHash`, `semanticPlanHash`,
 *    `previewHash` and `skillHash` are server-owned and are never produced by the browser.
 *
 * The Flow node packs are owned by T19/T20. The Journey authoring surface is deliberately unavailable in
 * the Visual Builder, because a Journey is a bounded set of Flow node components plus a separate topology
 * and this form cannot declare one; it therefore emits no mutation at all rather than a fake component.
 */

import type { CapabilityPayload } from '../contracts/proposal'

export const CAPABILITY_CODES = {
  SEGMENT: 'segment.rule-based',
  MESSAGE: 'message.template',
  PROMOTION: 'promotion.reference',
  SCHEDULE: 'schedule.basic',
  GOVERNANCE: 'governance.limit',
  FLOW_START: 'flow.start',
  FLOW_MESSAGE: 'flow.message',
  FLOW_WAIT: 'flow.wait',
  FLOW_CONDITION: 'flow.condition',
  FLOW_REWARD: 'flow.reward',
  FLOW_END: 'flow.end',
} as const

export const FLOW_SLOT_CODE = 'campaign.flow'
export const SEGMENT_SLOT_CODE = 'campaign.audience.segment'
export const SEGMENT_ENVELOPE_VERSION = 'segment-authoring-envelope/1.0'

export const FLOW_NODE_CAPABILITIES: readonly string[] = [
  CAPABILITY_CODES.FLOW_START,
  CAPABILITY_CODES.FLOW_MESSAGE,
  CAPABILITY_CODES.FLOW_WAIT,
  CAPABILITY_CODES.FLOW_CONDITION,
  CAPABILITY_CODES.FLOW_REWARD,
  CAPABILITY_CODES.FLOW_END,
]

/** The capability each summary card is rendered from. */
export const SUMMARY_CAPABILITIES = {
  message: CAPABILITY_CODES.MESSAGE,
  promotion: CAPABILITY_CODES.PROMOTION,
  journey: FLOW_SLOT_CODE,
  schedule: CAPABILITY_CODES.SCHEDULE,
  governance: CAPABILITY_CODES.GOVERNANCE,
} as const

export interface MessagePayloadInput {
  readonly referenceCode: string
  readonly channel: string
  readonly variables: readonly string[]
}

export function messagePayload(input: MessagePayloadInput): CapabilityPayload {
  return {
    referenceCode: input.referenceCode,
    channel: input.channel,
    variables: input.variables.map((variable) => variable),
  }
}

export interface PromotionPayloadInput {
  readonly referenceCode: string
  readonly quantity: number
}

export function promotionPayload(input: PromotionPayloadInput): CapabilityPayload {
  return { referenceCode: input.referenceCode, quantity: input.quantity }
}

export interface GovernancePayloadInput {
  readonly maxRecipients: number
  readonly maxRewardGrants: number
  readonly maxRewardFaceValue: string
  readonly currency: string
}

export function governancePayload(input: GovernancePayloadInput): CapabilityPayload {
  return {
    maxRecipients: input.maxRecipients,
    maxRewardGrants: input.maxRewardGrants,
    maxRewardFaceValue: input.maxRewardFaceValue,
    currency: input.currency,
  }
}

export interface SchedulePayloadInput {
  readonly form: string
  readonly zoneId: string
  readonly runAt: string | null
  readonly localTime: string | null
  readonly startDate: string | null
  readonly endDate: string | null
}

export function schedulePayload(input: SchedulePayloadInput): CapabilityPayload {
  const payload: { [key: string]: string } = { form: input.form, zoneId: input.zoneId }
  if (input.runAt !== null) {
    payload['runAt'] = input.runAt
  }
  if (input.localTime !== null) {
    payload['localTime'] = input.localTime
  }
  if (input.startDate !== null) {
    payload['startDate'] = input.startDate
  }
  if (input.endDate !== null) {
    payload['endDate'] = input.endDate
  }
  return payload
}

/**
 * The declared Segment DSL wire document. The four derived hashes are
 * server-owned: this surface never fabricates one.
 */
export function segmentPayload(dslWire: string): CapabilityPayload {
  return { envelopeVersion: SEGMENT_ENVELOPE_VERSION, dslWire }
}

export const EMPTY_FLOW_PAYLOAD: CapabilityPayload = {}

/** The two closed terminal codes the accepted `flow.end` contract declares. */
export const FLOW_TERMINALS = ['END', 'EXIT'] as const

/** The one declared field of the accepted `flow.end` contract. */
export function flowEndPayload(terminal: string): CapabilityPayload {
  return { terminal }
}