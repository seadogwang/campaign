/**
 * The single authoring submission path.
 *
 * Both the AI Conversation surface and the Visual Builder surface call exactly
 * this function. It normalises the shared intent into the one wire Proposal,
 * sends that Proposal to the one gateway method and returns the same server
 * Plan shape. Neither surface builds or uploads a `ChangeSet`, a `Manifest` or
 * any plan material.
 */

import { normalizeIntent, type TrustedBase } from './normalizer'
import type { AuthoringIntent } from './intent'
import { proposalCanonicalText, type CampaignProposalPatch } from '../contracts/proposal'
import type { CampaignPlanSummary } from '../contracts/plan'
import type { CampaignWorkspaceGateway, GatewayCallOptions } from '../gateway/types'

export interface AuthoringSubmission {
  readonly origin: AuthoringIntent['origin']
  readonly intent: AuthoringIntent
  readonly base: TrustedBase
}

/** The one normalisation used by both surfaces, exposed for the equivalence test. */
export function proposalFor(submission: AuthoringSubmission): CampaignProposalPatch {
  return normalizeIntent(submission.intent, submission.base)
}

/** Canonical Proposal text for a submission: identical for equivalent surfaces. */
export function submissionCanonicalText(submission: AuthoringSubmission): string {
  return proposalCanonicalText(proposalFor(submission))
}

/** The one gateway call both authoring surfaces make. */
export async function submitAuthoring(
  gateway: CampaignWorkspaceGateway,
  submission: AuthoringSubmission,
  options: GatewayCallOptions,
): Promise<CampaignPlanSummary> {
  return gateway.compileProposal(proposalFor(submission), options)
}