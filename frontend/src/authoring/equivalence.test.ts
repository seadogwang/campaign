import { describe, expect, it } from 'vitest'
import { buildVisualIntent, DEFAULT_VISUAL_STATE } from './visual'
import { normalizeIntent, type TrustedBase } from './normalizer'
import { proposalFor, submissionCanonicalText, type AuthoringSubmission } from './submit'
import {
  encodeCampaignProposalPatch,
  proposalCanonicalText,
  decodeCampaignProposalPatch,
  PROPOSAL_SCHEMA_VERSION,
} from '../contracts/proposal'
import { contentHashOfCanonical } from '../contracts/canonical'
import { decodeConversationProjection } from '../contracts/conversation'
import type { CampaignProposalPatch } from '../contracts/proposal'
import type { ContentHash } from '../contracts/primitives'

const CATALOG_HASH = 'sha256:1111111111111111111111111111111111111111111111111111111111111111' as ContentHash
const POLICY_HASH = 'sha256:2222222222222222222222222222222222222222222222222222222222222222' as ContentHash
const INSPECTION_HASH =
  'sha256:3333333333333333333333333333333333333333333333333333333333333333' as ContentHash
const ARTIFACT_HASH =
  'sha256:4444444444444444444444444444444444444444444444444444444444444444' as ContentHash

const BASE: TrustedBase = {
  catalogVersion: 'catalog-1',
  catalogHash: CATALOG_HASH,
  policyVersion: 'policy-1',
  policyHash: POLICY_HASH,
  campaignRevision: 3,
  locale: 'en-GB',
}

function visualSubmission(): AuthoringSubmission {
  return {
    origin: 'VISUAL_BUILDER',
    intent: buildVisualIntent(DEFAULT_VISUAL_STATE, 'EDIT', 'campaign-1'),
    base: BASE,
  }
}

/** The bounded Proposal one host conversation turn may carry. */
function hostProposal(): CampaignProposalPatch {
  return {
    schemaVersion: PROPOSAL_SCHEMA_VERSION,
    mode: 'EDIT',
    campaignId: 'campaign-1',
    baseRevisions: {
      campaignRevision: 3,
      catalogVersion: 'catalog-1',
      catalogHash: CATALOG_HASH,
      policyVersion: 'policy-1',
      policyHash: POLICY_HASH,
    },
    operations: [
      {
        op: 'UPSERT',
        capability: 'message.template',
        logicalId: null,
        clientRef: 'campaign-message',
        value: { referenceCode: 'tpl-welcome', channel: 'EMAIL', variables: [] },
        before: null,
        after: null,
      },
    ],
    clientContext: { locale: null },
    journeyTopology: null,
  }
}

/** The wire projection the host returns; only these fields are ever rendered. */
function hostProjection(overrides: Record<string, unknown> = {}): unknown {
  const proposal = hostProposal()
  return {
    accepted: true,
    code: null,
    conversationId: 'conversation-1',
    runId: 'run-1',
    revision: 2,
    phase: 'PROPOSAL_READY',
    disposition: 'CAMPAIGN_AGENT_ACCEPTED',
    routeKind: 'FINALIZE_CAMPAIGN',
    turns: [
      { turnId: 'turn-1', role: 'USER', ordinal: 0, text: 'build a campaign', at: null },
      { turnId: 'turn-2', role: 'ASSISTANT', ordinal: 1, text: 'The Campaign Proposal is ready.', at: null },
    ],
    skills: [
      { skillId: 'segment.rule-based', state: 'READY', code: null, missingDependencyCodes: [] },
      { skillId: 'journey.flow', state: 'MISSING', code: null, missingDependencyCodes: [] },
    ],
    components: [
      {
        componentId: 'campaign-segment',
        skillId: 'segment.rule-based',
        capabilityCode: 'segment.rule-based',
        operationKind: 'UPSERT',
        artifactHash: ARTIFACT_HASH,
      },
    ],
    clarification: null,
    assistantMessage: 'The Campaign Proposal is ready.',
    audience: null,
    inspectionHash: INSPECTION_HASH,
    proposal: encodeCampaignProposalPatch(proposal),
    proposalHash: contentHashOfCanonical(encodeCampaignProposalPatch(proposal)),
    followUpPrompt: null,
    ...overrides,
  }
}

describe('one Proposal contract for both surfaces', () => {
  it('produces a Proposal that survives the strict wire decoder unchanged', () => {
    const submission = visualSubmission()
    const decoded = decodeCampaignProposalPatch(encodeCampaignProposalPatch(proposalFor(submission)))
    expect(decoded.mode).toBe('EDIT')
    expect(decoded.campaignId).toBe('campaign-1')
    expect(decoded.operations.length).toBeGreaterThan(0)
    expect(proposalCanonicalText(decoded)).toBe(submissionCanonicalText(submission))
  })

  it('carries no ChangeSet, Manifest or plan material in the Visual Builder payload', () => {
    const text = submissionCanonicalText(visualSubmission())
    expect(text).not.toContain('changeSet')
    expect(text).not.toContain('manifest')
    expect(text).not.toContain('planHash')
    expect(text).not.toContain('approvedDecisionHash')
  })

  it('accepts the host conversation Proposal through the same decoder and hash rule', () => {
    const projection = decodeConversationProjection(hostProjection(), '$')
    expect(projection.proposal).not.toBeNull()
    const proposal = projection.proposal
    if (proposal === null) {
      throw new Error('the host Proposal should have been decoded')
    }
    expect(proposal.operations.map((operation) => operation.capability)).toEqual(['message.template'])
    expect(contentHashOfCanonical(encodeCampaignProposalPatch(proposal))).toBe(projection.proposalHash)
    expect(proposalCanonicalText(proposal)).not.toContain('changeSet')
    expect(proposalCanonicalText(proposal)).not.toContain('manifest')
  })

  it('refuses a Proposal whose own bytes do not hash to the published hash', () => {
    const tampered = hostProjection({
      proposalHash: 'sha256:4444444444444444444444444444444444444444444444444444444444444444',
    })
    expect(() => decodeConversationProjection(tampered, '$')).toThrow()
  })

  it('refuses a projection that carries a Proposal without its hash and the reverse', () => {
    expect(() => decodeConversationProjection(hostProjection({ proposalHash: null }), '$')).toThrow()
    expect(() => decodeConversationProjection(hostProjection({ proposal: null }), '$')).toThrow()
  })
})

describe('assistant prose can never change saved state', () => {
  it('reports no Proposal when the host produced none, whatever the prose says', () => {
    const projection = decodeConversationProjection(
      hostProjection({
        proposal: null,
        proposalHash: null,
        phase: 'READY_TO_FINALIZE',
        assistantMessage: 'I applied and published the Campaign for you.',
      }),
      '$',
    )
    expect(projection.proposal).toBeNull()
    expect(projection.proposalHash).toBeNull()
    expect(projection.assistantMessage).toContain('published')
    // The projection is inert data: it has no gateway, no store and no side effect.
    expect(Object.keys(projection).sort()).toEqual([
      'accepted',
      'assistantMessage',
      'audience',
      'clarification',
      'code',
      'componentIssues',
      'componentManifest',
      'components',
      'conversationId',
      'disposition',
      'followUpPrompt',
      'inspectionHash',
      'journey',
      'phase',
      'proposal',
      'proposalHash',
      'requirements',
      'revision',
      'routeKind',
      'runId',
      'skills',
      'turns',
    ])
  })

  it('refuses an accepted flag and a refusal code that disagree', () => {
    expect(() => decodeConversationProjection(hostProjection({ accepted: true, code: 'CAMPAIGN_AGENT_STALE_REVISION' }), '$'))
      .toThrow()
  })
})

describe('normalisation invariants', () => {
  it('refuses an EDIT intent without a base revision', () => {
    const intent = buildVisualIntent(DEFAULT_VISUAL_STATE, 'EDIT', 'campaign-1')
    expect(() => normalizeIntent(intent, { ...BASE, campaignRevision: null })).toThrow()
  })

  it('refuses a CREATE intent that names a campaign id', () => {
    const intent = buildVisualIntent(DEFAULT_VISUAL_STATE, 'CREATE', 'campaign-1')
    expect(() => normalizeIntent(intent, { ...BASE, campaignRevision: null })).toThrow()
  })
})

describe('the Visual Builder Journey form', () => {
  it('never emits a fake Journey capability, a Flow node or a topology mutation', () => {
    const text = submissionCanonicalText(visualSubmission())
    expect(text).not.toContain('journey')
    expect(text).not.toContain('flow.')
    expect(text).not.toContain('journeyTopology')
    const proposal = proposalFor(visualSubmission())
    expect(proposal.journeyTopology).toBeNull()
    expect(proposal.operations.every((operation) => operation.capability !== 'journey.flow')).toBe(true)
  })

  it('stays unavailable even when a caller asks for the Journey form explicitly', () => {
    const included = { ...DEFAULT_VISUAL_STATE.included, journey: true }
    const intent = buildVisualIntent({ ...DEFAULT_VISUAL_STATE, included }, 'EDIT', 'campaign-1')
    expect(intent.upserts.some((upsert) => upsert.clientRef === 'journey-new')).toBe(false)
    const text = submissionCanonicalText({ origin: 'VISUAL_BUILDER', intent, base: BASE })
    expect(text).not.toContain('journey')
  })
})
