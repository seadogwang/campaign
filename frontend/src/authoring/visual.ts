/**
 * The Visual Builder surface.
 *
 * Typed forms for Campaign basics, Segment, Message, Promotion, Journey,
 * Schedule and Governance. Editing produces exactly the same `AuthoringIntent`
 * and therefore exactly the same `CampaignProposalPatch` as the AI Conversation
 * surface. The Visual Builder never constructs a `ChangeSet`.
 */

import {
  CAPABILITY_CODES,
  governancePayload,
  messagePayload,
  promotionPayload,
  schedulePayload,
  segmentPayload,
} from './capabilities'
import type { AuthoringField, AuthoringIntent, AuthoringUpsert } from './intent'
import type { ProposalMode } from '../contracts/proposal'
import type { ScheduleForm } from '../contracts/summaries'

export interface BasicsForm {
  /** Submitted through `clientContext.locale`. */
  readonly locale: string
  /** Used as the default zone for the Schedule form; not a submitted capability field. */
  readonly timezone: string
  /** Local editor state only. Never submitted: the accepted Proposal contract carries no display name. */
  readonly displayName: string
}

export interface SegmentFormModel {
  readonly dslWire: string
}

export interface MessageFormModel {
  readonly referenceCode: string
  readonly channel: string
  readonly variables: string
}

export interface PromotionFormModel {
  readonly referenceCode: string
  readonly quantity: string
}

export interface JourneyFormModel {
  readonly entryNodeLogicalId: string
  readonly nodeCount: string
}

export interface ScheduleFormModel {
  readonly logicalId: string
  readonly form: ScheduleForm
  readonly zoneId: string
  readonly runAt: string
  readonly localTime: string
  readonly startDate: string
  readonly endDate: string
}

export interface GovernanceFormModel {
  readonly maxRecipients: string
  readonly maxRewardGrants: string
  readonly maxRewardFaceValue: string
  readonly currency: string
}

export interface VisualBuilderState {
  readonly basics: BasicsForm
  readonly segment: SegmentFormModel
  readonly message: MessageFormModel
  readonly promotion: PromotionFormModel
  readonly journey: JourneyFormModel
  readonly schedule: ScheduleFormModel
  readonly governance: GovernanceFormModel
  /** Which capability forms participate in the next draft. */
  readonly included: Readonly<Record<AuthoringField, boolean>>
}

export const DEFAULT_VISUAL_STATE: VisualBuilderState = {
  basics: { locale: 'en-GB', timezone: 'UTC', displayName: 'Untitled campaign' },
  segment: { dslWire: '{"schemaVersion":"1.3"}' },
  message: { referenceCode: 'template-1', channel: 'EMAIL', variables: '' },
  promotion: { referenceCode: 'reward-1', quantity: '1' },
  journey: { entryNodeLogicalId: 'flow-start', nodeCount: '1' },
  schedule: {
    logicalId: 'schedule-1',
    form: 'IMMEDIATE',
    zoneId: 'UTC',
    runAt: '',
    localTime: '',
    startDate: '',
    endDate: '',
  },
  governance: { maxRecipients: '1000', maxRewardGrants: '100', maxRewardFaceValue: '5.00', currency: 'USD' },
  included: {
    basics: true,
    segment: true,
    message: true,
    promotion: true,
    // The Journey form is unavailable in this bounded surface, so it never participates in a draft.
    journey: false,
    schedule: true,
    governance: true,
  },
}

function positiveIntegerOrNull(text: string): number | null {
  const parsed = Number.parseInt(text, 10)
  return Number.isFinite(parsed) && parsed >= 1 ? parsed : null
}

function blankToNull(text: string): string | null {
  return text.trim().length === 0 ? null : text.trim()
}

/**
 * Build the authoring intent from the Visual Builder forms. Deterministic:
 * identical form state always yields an identical intent.
 */
export function buildVisualIntent(
  state: VisualBuilderState,
  mode: ProposalMode,
  campaignId: string | null,
): AuthoringIntent {
  const upserts: AuthoringUpsert[] = []

  if (state.included.segment) {
    upserts.push({
      capability: CAPABILITY_CODES.SEGMENT,
      logicalId: null,
      clientRef: 'segment-new',
      value: segmentPayload(state.segment.dslWire),
    })
  }
  if (state.included.message) {
    upserts.push({
      capability: CAPABILITY_CODES.MESSAGE,
      logicalId: null,
      clientRef: 'message-new',
      value: messagePayload({
        referenceCode: state.message.referenceCode,
        channel: state.message.channel,
        variables: state.message.variables
          .split(',')
          .map((variable) => variable.trim())
          .filter((variable) => variable.length > 0),
      }),
    })
  }
  if (state.included.promotion) {
    const quantity = positiveIntegerOrNull(state.promotion.quantity) ?? 1
    upserts.push({
      capability: CAPABILITY_CODES.PROMOTION,
      logicalId: null,
      clientRef: 'promotion-new',
      value: promotionPayload({ referenceCode: state.promotion.referenceCode, quantity }),
    })
  }
  // The Journey form is explicitly unavailable: a Journey is a bounded set of Flow node components plus one
  // separate topology, which this bounded form cannot declare. It therefore emits no mutation at all rather
  // than a fake component, and Journey authoring stays in the conversation surface.
  if (state.included.schedule) {
    const form = state.schedule.form
    upserts.push({
      capability: CAPABILITY_CODES.SCHEDULE,
      logicalId: null,
      clientRef: 'schedule-new',
      value: schedulePayload({
        form,
        zoneId: state.schedule.zoneId.trim().length === 0 ? state.basics.timezone : state.schedule.zoneId,
        runAt: form === 'ONCE' ? blankToNull(state.schedule.runAt) : null,
        localTime: form === 'DAILY' ? blankToNull(state.schedule.localTime) : null,
        startDate: form === 'DAILY' ? blankToNull(state.schedule.startDate) : null,
        endDate: form === 'DAILY' ? blankToNull(state.schedule.endDate) : null,
      }),
    })
  }
  if (state.included.governance) {
    upserts.push({
      capability: CAPABILITY_CODES.GOVERNANCE,
      logicalId: null,
      clientRef: 'governance-new',
      value: governancePayload({
        maxRecipients: positiveIntegerOrNull(state.governance.maxRecipients) ?? 1,
        maxRewardGrants: positiveIntegerOrNull(state.governance.maxRewardGrants) ?? 1,
        maxRewardFaceValue: state.governance.maxRewardFaceValue,
        currency: state.governance.currency,
      }),
    })
  }

  return { origin: 'VISUAL_BUILDER', mode, campaignId, upserts, deletes: [], moves: [] }
}