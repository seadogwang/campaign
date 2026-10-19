import { describe, expect, it } from 'vitest'
import {
  decodeCampaignProposalPatch,
  encodeCampaignProposalPatch,
  normalizeCampaignProposalPatch,
  proposalCanonicalText,
  PROPOSAL_SCHEMA_VERSION,
  type CampaignProposalPatch,
  type CapabilityPayload,
} from './proposal'
import { UiError } from './errors'
import type { ContentHash } from './primitives'

const CATALOG_HASH = 'sha256:1111111111111111111111111111111111111111111111111111111111111111' as ContentHash
const POLICY_HASH = 'sha256:2222222222222222222222222222222222222222222222222222222222222222' as ContentHash

function patch(overrides: Partial<Record<string, unknown>> = {}): Record<string, unknown> {
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
      { op: 'UPSERT', capability: 'message.template', clientRef: 'message-new', value: { referenceCode: 't-1' } },
    ],
    clientContext: { locale: 'en-GB' },
    ...overrides,
  }
}

function expectUiError(fn: () => unknown, code: string): UiError {
  try {
    fn()
  } catch (thrown) {
    expect(thrown).toBeInstanceOf(UiError)
    const error = thrown as UiError
    expect(error.code).toBe(code)
    return error
  }
  throw new Error('expected a bounded UiError')
}

describe('CampaignProposalPatch decoding', () => {
  it('accepts a well-formed EDIT proposal and round-trips it', () => {
    const decoded = decodeCampaignProposalPatch(patch())
    expect(decoded.mode).toBe('EDIT')
    expect(decoded.operations).toHaveLength(1)
    expect(decoded.baseRevisions.campaignRevision).toBe(3)
    const reencoded = encodeCampaignProposalPatch(decoded)
    expect(decodeCampaignProposalPatch(reencoded)).toEqual(decoded)
  })

  it('accepts a CREATE proposal without a campaign id or base revision', () => {
    const decoded = decodeCampaignProposalPatch(
      patch({
        mode: 'CREATE',
        campaignId: undefined,
        baseRevisions: {
          catalogVersion: 'catalog-1',
          catalogHash: CATALOG_HASH,
          policyVersion: 'policy-1',
          policyHash: POLICY_HASH,
        },
      }),
    )
    expect(decoded.mode).toBe('CREATE')
    expect(decoded.campaignId).toBeNull()
    expect(decoded.baseRevisions.campaignRevision).toBeNull()
  })

  it('rejects unknown, alternately cased and wrongly shaped fields', () => {
    expectUiError(() => decodeCampaignProposalPatch(patch({ extra: 1 })), 'UI_CONTRACT_FIELD_UNKNOWN')
    expectUiError(() => decodeCampaignProposalPatch(patch({ Mode: 'EDIT' })), 'UI_CONTRACT_FIELD_UNKNOWN')
    expectUiError(
      () => decodeCampaignProposalPatch(patch({ operations: [{ op: 'upsert', capability: 'x', clientRef: 'a', value: {} }] })),
      'UI_CONTRACT_VALUE_INVALID',
    )
    expectUiError(() => decodeCampaignProposalPatch(patch({ schemaVersion: '1.2' })), 'UI_CONTRACT_VALUE_INVALID')
  })

  it('rejects a MOVE with zero or two anchors and an anchor on a non-MOVE operation', () => {
    expectUiError(
      () => decodeCampaignProposalPatch(patch({ operations: [{ op: 'MOVE', logicalId: 'a' }] })),
      'UI_CONTRACT_VALUE_INVALID',
    )
    expectUiError(
      () =>
        decodeCampaignProposalPatch(
          patch({ operations: [{ op: 'MOVE', logicalId: 'a', before: 'b', after: 'c' }] }),
        ),
      'UI_CONTRACT_VALUE_INVALID',
    )
    expectUiError(
      () =>
        decodeCampaignProposalPatch(
          patch({
            operations: [
              { op: 'UPSERT', capability: 'message.template', clientRef: 'a', value: {}, before: 'b' },
            ],
          }),
        ),
      'UI_CONTRACT_VALUE_INVALID',
    )
  })

  it('rejects a DELETE that carries a value or a client reference', () => {
    expectUiError(
      () => decodeCampaignProposalPatch(patch({ operations: [{ op: 'DELETE', logicalId: 'a', value: {} }] })),
      'UI_CONTRACT_VALUE_INVALID',
    )
    expectUiError(
      () => decodeCampaignProposalPatch(patch({ operations: [{ op: 'DELETE', clientRef: 'a' }] })),
      'UI_CONTRACT_VALUE_INVALID',
    )
  })

  it('rejects every reserved field inside a capability payload, at any depth', () => {
    const reserved = [
      'tenantId',
      'planId',
      'planHash',
      'manifest',
      'changeSet',
      'revision',
      'status',
      'apply',
      'publish',
      'memberIds',
      'providerReference',
      'idempotencyKey',
      'sql',
    ]
    for (const field of reserved) {
      expectUiError(
        () =>
          decodeCampaignProposalPatch(
            patch({
              operations: [
                {
                  op: 'UPSERT',
                  capability: 'message.template',
                  clientRef: 'a',
                  value: { nested: { [field]: 'sentinel-value' } },
                },
              ],
            }),
          ),
        'UI_CONTRACT_VALUE_INVALID',
      )
    }
  })

  it('never echoes a payload value or field name in a failure message', () => {
    const error = expectUiError(
      () =>
        decodeCampaignProposalPatch(
          patch({
            operations: [
              { op: 'UPSERT', capability: 'message.template', clientRef: 'a', value: { tenantId: 'sentinel-value' } },
            ],
          }),
        ),
      'UI_CONTRACT_VALUE_INVALID',
    )
    expect(error.message).not.toContain('sentinel-value')
    expect(JSON.stringify(error.details)).not.toContain('sentinel-value')
  })
})

describe('proposal normalisation', () => {
  const base: CampaignProposalPatch = {
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
      { op: 'MOVE', capability: null, logicalId: 'z', clientRef: null, value: null, before: 'a', after: null },
      {
        op: 'UPSERT',
        capability: 'message.template',
        logicalId: 'm',
        clientRef: null,
        value: { b: 1, a: 2 } as CapabilityPayload,
        before: null,
        after: null,
      },
      { op: 'DELETE', capability: null, logicalId: 'd', clientRef: null, value: null, before: null, after: null },
    ],
    clientContext: { locale: 'en-GB' },
    journeyTopology: null,
  }

  it('orders operations deterministically without changing their meaning', () => {
    const normalized = normalizeCampaignProposalPatch(base)
    expect(normalized.operations.map((operation) => operation.op)).toEqual(['UPSERT', 'DELETE', 'MOVE'])
    expect(normalized.operations).toHaveLength(base.operations.length)
  })

  it('is idempotent and produces one canonical text', () => {
    const once = normalizeCampaignProposalPatch(base)
    const twice = normalizeCampaignProposalPatch(once)
    expect(proposalCanonicalText(once)).toBe(proposalCanonicalText(twice))
  })
})
