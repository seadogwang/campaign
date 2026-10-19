import { describe, expect, it } from 'vitest'
import {
  decodeCampaignProposalPatch,
  encodeCampaignProposalPatch,
  encodeJourneyTopology,
  JOURNEY_TOPOLOGY_FORMAT_VERSION,
  LEGACY_PROPOSAL_SCHEMA_VERSION,
  proposalCanonicalText,
  PROPOSAL_SCHEMA_VERSION,
  type CampaignProposalPatch,
  type JourneyTopologyMutation,
} from './proposal'
import { contentHashOfCanonical, contentHashOfText } from './canonical'
import type { ContentHash } from './primitives'

const CATALOG_HASH = 'sha256:1111111111111111111111111111111111111111111111111111111111111111' as ContentHash
const POLICY_HASH = 'sha256:2222222222222222222222222222222222222222222222222222222222222222' as ContentHash

function patch(topology: JourneyTopologyMutation | null): CampaignProposalPatch {
  return {
    schemaVersion: PROPOSAL_SCHEMA_VERSION,
    mode: 'CREATE',
    campaignId: null,
    baseRevisions: {
      campaignRevision: null,
      catalogVersion: 'catalog-1',
      catalogHash: CATALOG_HASH,
      policyVersion: 'policy-1',
      policyHash: POLICY_HASH,
    },
    operations: [
      {
        op: 'UPSERT',
        capability: 'flow.start',
        logicalId: null,
        clientRef: 'n-start',
        value: {},
        before: null,
        after: null,
      },
    ],
    clientContext: { locale: null },
    journeyTopology: topology,
  }
}

const REPLACE: JourneyTopologyMutation = {
  action: 'REPLACE',
  topologyFormatVersion: JOURNEY_TOPOLOGY_FORMAT_VERSION,
  edges: [
    {
      edgeId: 'edge-start-end',
      from: { logicalId: null, clientRef: 'n-start' },
      to: { logicalId: 'flow-end-1', clientRef: null },
      branch: 'DEFAULT',
    },
  ],
}

describe('the strict Journey-topology mutation', () => {
  it('omits the field entirely when the topology is inherited', () => {
    const encoded = encodeCampaignProposalPatch(patch(null))
    expect(Object.prototype.hasOwnProperty.call(encoded, 'journeyTopology')).toBe(false)
    expect(decodeCampaignProposalPatch(encoded).journeyTopology).toBeNull()
  })

  it('round-trips a replacement through the canonical wire form', () => {
    const decoded = decodeCampaignProposalPatch(encodeCampaignProposalPatch(patch(REPLACE)))
    expect(decoded.journeyTopology).toEqual(REPLACE)
    expect(
      contentHashOfCanonical(encodeCampaignProposalPatch(decoded)),
    ).toBe(contentHashOfCanonical(encodeCampaignProposalPatch(patch(REPLACE))))
  })

  it('distinguishes omission, replacement and removal in the canonical bytes and hash', () => {
    const omitted = contentHashOfCanonical(encodeCampaignProposalPatch(patch(null)))
    const replaced = contentHashOfCanonical(encodeCampaignProposalPatch(patch(REPLACE)))
    const removed = contentHashOfCanonical(
      encodeCampaignProposalPatch(patch({ action: 'REMOVE', topologyFormatVersion: null, edges: [] })),
    )
    expect(new Set([omitted, replaced, removed]).size).toBe(3)
    expect(encodeCampaignProposalPatch(patch({ action: 'REMOVE', topologyFormatVersion: null, edges: [] }))
      ['journeyTopology']).toEqual({ action: 'REMOVE' })
  })

  it('refuses unknown, alternate-cased, over-bound and contradictory topology material', () => {
    const base = encodeCampaignProposalPatch(patch(null))
    const withTopology = (topology: unknown): unknown => ({ ...base, journeyTopology: topology })

    expect(() => decodeCampaignProposalPatch(withTopology({ action: 'MERGE' }))).toThrow()
    expect(() => decodeCampaignProposalPatch(withTopology({ action: 'replace' }))).toThrow()
    expect(() => decodeCampaignProposalPatch(withTopology({
      action: 'REPLACE',
      topologyFormatVersion: '2.0',
      edges: REPLACE.edges,
    }))).toThrow()
    expect(() => decodeCampaignProposalPatch(withTopology({
      action: 'REPLACE',
      topologyFormatVersion: JOURNEY_TOPOLOGY_FORMAT_VERSION,
      edges: [],
    }))).toThrow()
    expect(() => decodeCampaignProposalPatch(withTopology({
      action: 'REMOVE',
      edges: REPLACE.edges,
    }))).toThrow()
    expect(() => decodeCampaignProposalPatch(withTopology({
      action: 'REPLACE',
      topologyFormatVersion: JOURNEY_TOPOLOGY_FORMAT_VERSION,
      edges: [
        ...REPLACE.edges,
        { edgeId: 'edge-start-end', from: { logicalId: 'a', clientRef: null }, to: { logicalId: 'b', clientRef: null }, branch: 'TRUE' },
      ],
    }))).toThrow()
    expect(() => decodeCampaignProposalPatch(withTopology({
      action: 'REPLACE',
      topologyFormatVersion: JOURNEY_TOPOLOGY_FORMAT_VERSION,
      edges: [{
        edgeId: 'e1',
        from: { logicalId: null, clientRef: 'n-start' },
        to: { logicalId: null, clientRef: 'n-start' },
        branch: 'DEFAULT',
      }],
    }))).toThrow()
    expect(() => decodeCampaignProposalPatch(withTopology({
      action: 'REPLACE',
      topologyFormatVersion: JOURNEY_TOPOLOGY_FORMAT_VERSION,
      edges: [{
        edgeId: 'e1',
        from: { logicalId: 'a', clientRef: 'a' },
        to: { logicalId: 'b', clientRef: null },
        branch: 'DEFAULT',
      }],
    }))).toThrow()
    expect(() => decodeCampaignProposalPatch(withTopology({
      action: 'REPLACE',
      topologyFormatVersion: JOURNEY_TOPOLOGY_FORMAT_VERSION,
      edges: REPLACE.edges,
      topologyHash: 'sha256:0000',
    }))).toThrow()
  })

  it('refuses a topology hash, a compiler identity or an invented branch', () => {
    const replaced = encodeCampaignProposalPatch(patch(REPLACE))
    const topology = replaced['journeyTopology'] as unknown as { edges: readonly unknown[] }
    expect(topology.edges).toHaveLength(1)
    expect(() => decodeCampaignProposalPatch({
      ...replaced,
      journeyTopology: { ...topology, edges: [{ ...REPLACE.edges[0], branch: 'default' }] },
    })).toThrow()
  })
})

describe('the explicit Proposal wire-version boundary', () => {
  /** The exact canonical text of a legacy 1.3 Proposal with no topology member at all. */
  const LEGACY_CANONICAL_TEXT =
    '{"baseRevisions":{"catalogHash":"' + CATALOG_HASH + '","catalogVersion":"catalog-1",'
    + '"policyHash":"' + POLICY_HASH + '","policyVersion":"policy-1"},"mode":"CREATE",'
    + '"operations":[{"capability":"flow.start","clientRef":"n-start","op":"UPSERT","value":{}}],'
    + '"schemaVersion":"1.3"}'

  function legacyPatch(): Record<string, unknown> {
    // `patch(null)` already omits the topology member entirely; only the version is swapped back.
    return { ...encodeCampaignProposalPatch(patch(null)), schemaVersion: LEGACY_PROPOSAL_SCHEMA_VERSION }
  }

  it('keeps a legacy 1.3 Proposal without a topology member readable with its previous bytes', () => {
    const decoded = decodeCampaignProposalPatch(legacyPatch())
    expect(decoded.schemaVersion).toBe(LEGACY_PROPOSAL_SCHEMA_VERSION)
    expect(decoded.journeyTopology).toBeNull()
    expect(proposalCanonicalText(decoded)).toBe(LEGACY_CANONICAL_TEXT)
    expect(contentHashOfCanonical(encodeCampaignProposalPatch(decoded)))
      .toBe(contentHashOfText(LEGACY_CANONICAL_TEXT))
    expect(decodeCampaignProposalPatch(encodeCampaignProposalPatch(decoded)).journeyTopology).toBeNull()
  })

  it('refuses a legacy 1.3 Proposal that carries the topology member in any form', () => {
    expect(() => decodeCampaignProposalPatch({
      ...legacyPatch(),
      journeyTopology: { action: 'REMOVE' },
    })).toThrow()
    expect(() => decodeCampaignProposalPatch({
      ...legacyPatch(),
      journeyTopology: null,
    })).toThrow()
  })

  it('refuses to encode a hand-built legacy patch that carries a topology mutation', () => {
    const legacyWithTopology: CampaignProposalPatch = {
      ...patch(REPLACE),
      schemaVersion: LEGACY_PROPOSAL_SCHEMA_VERSION,
    }
    expect(() => encodeCampaignProposalPatch(legacyWithTopology)).toThrow()
  })

  it('rejects an explicit JSON null rather than collapsing it into omission', () => {
    const encoded = encodeCampaignProposalPatch(patch(null))
    expect(Object.prototype.hasOwnProperty.call(encoded, 'journeyTopology')).toBe(false)
    expect(() => decodeCampaignProposalPatch({ ...encoded, journeyTopology: null })).toThrow()
    expect(decodeCampaignProposalPatch(encoded).journeyTopology).toBeNull()
  })
})

describe('the canonical topology edge order', () => {
  const EDGE_ONE = {
    edgeId: 'e-1',
    from: { logicalId: null, clientRef: 'node-a' },
    to: { logicalId: null, clientRef: 'node-b' },
    branch: 'DEFAULT',
  } as const
  const EDGE_TWO = {
    edgeId: 'e-2',
    from: { logicalId: null, clientRef: 'node-a' },
    to: { logicalId: 'flow-old-1', clientRef: null },
    branch: 'TRUE',
  } as const
  const EDGE_THREE = {
    edgeId: 'e-3',
    from: { logicalId: 'flow-old-2', clientRef: null },
    to: { logicalId: null, clientRef: 'node-b' },
    branch: 'FALSE',
  } as const

  function replacement(edges: readonly unknown[]): JourneyTopologyMutation {
    return {
      action: 'REPLACE',
      topologyFormatVersion: JOURNEY_TOPOLOGY_FORMAT_VERSION,
      edges: edges as unknown as JourneyTopologyMutation['edges'],
    }
  }

  it('normalises a permuted replacement edge set into one canonical order', () => {
    const forward = decodeCampaignProposalPatch(
      encodeCampaignProposalPatch(patch(replacement([EDGE_ONE, EDGE_TWO, EDGE_THREE]))),
    )
    const permuted = decodeCampaignProposalPatch(
      encodeCampaignProposalPatch(patch(replacement([EDGE_THREE, EDGE_TWO, EDGE_ONE]))),
    )

    expect(permuted.journeyTopology?.edges.map((edge) => edge.edgeId)).toEqual(['e-1', 'e-2', 'e-3'])
    expect(proposalCanonicalText(permuted)).toBe(proposalCanonicalText(forward))
    expect(contentHashOfCanonical(encodeCampaignProposalPatch(permuted)))
      .toBe(contentHashOfCanonical(encodeCampaignProposalPatch(forward)))
  })

  it('canonicalises a hand-built mutation identically whichever order its edges arrive in', () => {
    const forward = encodeJourneyTopology(replacement([EDGE_ONE, EDGE_TWO, EDGE_THREE]))
    const permuted = encodeJourneyTopology(replacement([EDGE_TWO, EDGE_THREE, EDGE_ONE]))
    expect(permuted).toEqual(forward)
  })
})