/**
 * The shared authoring intent.
 *
 * Both the AI Conversation surface and the Visual Builder surface produce this
 * one intent type and hand it to the same normaliser, which produces the same
 * `CampaignProposalPatch` and calls the same gateway method. There is exactly
 * one authoring contract.
 */

import type { CapabilityPayload, ProposalMode } from '../contracts/proposal'

export type AuthoringField =
  | 'basics'
  | 'segment'
  | 'message'
  | 'promotion'
  | 'journey'
  | 'schedule'
  | 'governance'

export interface AuthoringIntent {
  /** Which authoring surface produced the intent. Never a security fact. */
  readonly origin: 'AI_CONVERSATION' | 'VISUAL_BUILDER'
  readonly mode: ProposalMode
  readonly campaignId: string | null
  /** Keyed by capability slot; each value is a bounded capability payload. */
  readonly upserts: readonly AuthoringUpsert[]
  readonly deletes: readonly string[]
  readonly moves: readonly AuthoringMove[]
}

export interface AuthoringUpsert {
  readonly capability: string
  /** Server logical id for an existing resource; null for a new one. */
  readonly logicalId: string | null
  /** Stable client reference for a new resource, resolved by the server on Apply. */
  readonly clientRef: string | null
  readonly value: CapabilityPayload
}

export interface AuthoringMove {
  readonly logicalId: string
  readonly anchor: 'BEFORE' | 'AFTER'
  readonly anchorLogicalId: string
}

export function emptyIntent(origin: AuthoringIntent['origin'], mode: ProposalMode, campaignId: string | null): AuthoringIntent {
  return { origin, mode, campaignId, upserts: [], deletes: [], moves: [] }
}