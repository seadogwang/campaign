import { describe, expect, it } from 'vitest'
import {
  confirmationsMatch,
  decodeApplyResult,
  decodePublishPreparation,
  effectivePreparationState,
  preparationPublishable,
  type PublishPreparation,
} from './publish'
import { UiError } from './errors'
import type { ContentHash } from './primitives'

const HASH = 'sha256:1111111111111111111111111111111111111111111111111111111111111111' as ContentHash
const NOW = '2026-10-08T09:00:00Z'
const LATER = '2026-10-08T09:10:00Z'

function preparation(overrides: Partial<PublishPreparation> = {}): PublishPreparation {
  return {
    preparationId: 'prep-1',
    preparationHash: HASH,
    campaignId: 'campaign-1',
    campaignRevision: 3,
    draftId: 'draft-1',
    draftRevision: 2,
    planId: 'plan-1',
    planHash: HASH,
    manifestHash: HASH,
    draftHash: HASH,
    createdAt: NOW,
    expiresAt: LATER,
    state: 'PREPARED',
    requiredRiskCodes: ['RUNTIME_UNAVAILABLE', 'SCHEDULE_WORK_DEFERRED'],
    readinessCapability: 'segment.rule-based',
    readinessState: 'EXPERIMENTAL',
    previewCards: [],
    ...overrides,
  }
}

describe('publish preparation state', () => {
  it('reports PREPARED only while unexpired, unconsumed and consistent', () => {
    expect(effectivePreparationState(preparation(), NOW)).toBe('PREPARED')
    expect(preparationPublishable(preparation(), NOW)).toBe(true)
  })

  it('treats the instant at or past the expiry as EXPIRED', () => {
    expect(effectivePreparationState(preparation(), LATER)).toBe('EXPIRED')
    expect(preparationPublishable(preparation(), LATER)).toBe(false)
  })

  it('lets CONSUMED outrank expiry', () => {
    expect(effectivePreparationState(preparation({ state: 'CONSUMED' }), LATER)).toBe('CONSUMED')
  })

  it('keeps a STALE preparation stale', () => {
    expect(effectivePreparationState(preparation({ state: 'STALE' }), NOW)).toBe('STALE')
    expect(preparationPublishable(preparation({ state: 'STALE' }), NOW)).toBe(false)
  })
})

describe('confirmation set semantics', () => {
  it('requires the exact set with no missing, extra or repeated code', () => {
    const value = preparation()
    expect(confirmationsMatch(value, ['RUNTIME_UNAVAILABLE', 'SCHEDULE_WORK_DEFERRED'])).toBe(true)
    expect(confirmationsMatch(value, ['RUNTIME_UNAVAILABLE'])).toBe(false)
    expect(confirmationsMatch(value, ['RUNTIME_UNAVAILABLE', 'SCHEDULE_WORK_DEFERRED', 'EXTRA'])).toBe(false)
    expect(confirmationsMatch(value, ['RUNTIME_UNAVAILABLE', 'RUNTIME_UNAVAILABLE'])).toBe(false)
  })
})

describe('publish decode strictness', () => {
  it('refuses an unknown field on a preparation', () => {
    const raw = { ...preparation(), unexpected: true }
    try {
      decodePublishPreparation(JSON.parse(JSON.stringify(raw)), '$')
    } catch (thrown) {
      expect(thrown).toBeInstanceOf(UiError)
      expect((thrown as UiError).code).toBe('UI_CONTRACT_FIELD_UNKNOWN')
      return
    }
    throw new Error('expected a bounded UiError')
  })

  it('refuses an unknown field on an apply result', () => {
    const raw = {
      campaignId: 'campaign-1',
      campaignRevision: 4,
      draftId: 'draft-1',
      draftRevision: 3,
      manifestHash: HASH,
      draftHash: HASH,
      appliedAt: NOW,
      clientRefBindings: [],
      changeSet: [],
    }
    try {
      decodeApplyResult(raw, '$')
    } catch (thrown) {
      expect((thrown as UiError).code).toBe('UI_CONTRACT_FIELD_UNKNOWN')
      return
    }
    throw new Error('expected a bounded UiError')
  })
})