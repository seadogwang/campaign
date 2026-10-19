/**
 * The one normaliser from authoring intent to the wire Proposal.
 *
 * Normalisation is deterministic and semantics-preserving: it sorts the
 * operation list, drops nothing and rewrites nothing. Two semantically
 * equivalent intents — one typed in the Visual Builder, one produced from the
 * AI Conversation surface — normalise to byte-identical Proposal payloads.
 */

import { UI_ERROR_CODES, contractError } from '../contracts/errors'
import {
  CAPABILITY_CODE_PATTERN,
  MAX_PROPOSAL_OPERATIONS,
  deleteOperation,
  moveOperation,
  normalizeCampaignProposalPatch,
  upsertOperation,
  PROPOSAL_SCHEMA_VERSION,
  type CampaignProposalPatch,
  type ProposalBaseRevisions,
} from '../contracts/proposal'
import { REFERENCE_ID_PATTERN } from '../contracts/primitives'
import type { AuthoringIntent } from './intent'

export interface TrustedBase {
  readonly catalogVersion: string
  readonly catalogHash: ProposalBaseRevisions['catalogHash']
  readonly policyVersion: string
  readonly policyHash: ProposalBaseRevisions['policyHash']
  readonly campaignRevision: number | null
  readonly locale: string | null
}

function requireReferenceId(value: string, path: string): string {
  if (!REFERENCE_ID_PATTERN.test(value)) {
    throw contractError(UI_ERROR_CODES.AUTHORING_NORMALIZATION_INVALID, path, 'reference-id')
  }
  return value
}

function requireCapability(value: string, path: string): string {
  if (!CAPABILITY_CODE_PATTERN.test(value)) {
    throw contractError(UI_ERROR_CODES.AUTHORING_NORMALIZATION_INVALID, path, 'capability-code')
  }
  return value
}

export function normalizeIntent(intent: AuthoringIntent, base: TrustedBase): CampaignProposalPatch {
  if (intent.mode === 'CREATE' && intent.campaignId !== null) {
    throw contractError(UI_ERROR_CODES.AUTHORING_NORMALIZATION_INVALID, '$.campaignId', 'create-campaign-id')
  }
  if (intent.mode === 'EDIT' && intent.campaignId === null) {
    throw contractError(UI_ERROR_CODES.AUTHORING_NORMALIZATION_INVALID, '$.campaignId', 'edit-campaign-id')
  }
  if (intent.mode === 'EDIT' && base.campaignRevision === null) {
    throw contractError(UI_ERROR_CODES.AUTHORING_NORMALIZATION_INVALID, '$.baseRevisions', 'edit-revision')
  }

  const operations = [
    ...intent.upserts.map((upsert, index) => {
      const path = `$.upserts[${index}]`
      if ((upsert.logicalId === null) === (upsert.clientRef === null)) {
        throw contractError(UI_ERROR_CODES.AUTHORING_NORMALIZATION_INVALID, path, 'identity')
      }
      return upsertOperation(
        requireCapability(upsert.capability, `${path}.capability`),
        upsert.logicalId === null ? null : requireReferenceId(upsert.logicalId, `${path}.logicalId`),
        upsert.clientRef === null ? null : requireReferenceId(upsert.clientRef, `${path}.clientRef`),
        upsert.value,
      )
    }),
    ...intent.deletes.map((logicalId, index) =>
      deleteOperation(requireReferenceId(logicalId, `$.deletes[${index}]`)),
    ),
    ...intent.moves.map((move, index) => {
      const path = `$.moves[${index}]`
      return moveOperation(
        requireReferenceId(move.logicalId, `${path}.logicalId`),
        move.anchor,
        requireReferenceId(move.anchorLogicalId, `${path}.anchorLogicalId`),
      )
    }),
  ]

  if (operations.length === 0 || operations.length > MAX_PROPOSAL_OPERATIONS) {
    throw contractError(UI_ERROR_CODES.AUTHORING_NORMALIZATION_INVALID, '$.operations', 'operation-count')
  }

  const patch: CampaignProposalPatch = {
    schemaVersion: PROPOSAL_SCHEMA_VERSION,
    mode: intent.mode,
    campaignId: intent.campaignId === null ? null : requireReferenceId(intent.campaignId, '$.campaignId'),
    baseRevisions: {
      campaignRevision: intent.mode === 'CREATE' ? null : base.campaignRevision,
      catalogVersion: requireReferenceId(base.catalogVersion, '$.baseRevisions.catalogVersion'),
      catalogHash: base.catalogHash,
      policyVersion: requireReferenceId(base.policyVersion, '$.baseRevisions.policyVersion'),
      policyHash: base.policyHash,
    },
    operations,
    clientContext: { locale: base.locale },
    journeyTopology: null,
  }

  return normalizeCampaignProposalPatch(patch)
}
