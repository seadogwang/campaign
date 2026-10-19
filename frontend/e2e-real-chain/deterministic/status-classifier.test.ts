/**
 * Deterministic tests for the status classifier.
 */
import { describe, it, expect } from 'vitest'
import { classify, isLegitimateTerminal, isPass, terminalLabel } from '../status-classifier'
import type { ClassificationInput } from '../status-classifier'

function baseInput(overrides: Partial<ClassificationInput> = {}): ClassificationInput {
  return {
    phase: null,
    proposalReady: false,
    statusText: null,
    completeComponents: 0,
    totalComponents: 5,
    awaitingClarification: false,
    refusalVisible: false,
    errorVisible: false,
    outOfScopeIndicator: false,
    turnCount: 1,
    maxTurns: 12,
    finalized: false,
    ...overrides,
  }
}

describe('Status classifier', () => {
  it('classifies PROPOSAL_READY with all components and finalized', () => {
    const result = classify(
      baseInput({
        phase: 'PROPOSAL_READY',
        proposalReady: true,
        completeComponents: 5,
        totalComponents: 5,
        finalized: true,
      }),
    )
    expect(result).toBe('PROPOSAL_READY')
  })

  it('does NOT classify PROPOSAL_READY without FINALIZE even if 5/5', () => {
    const result = classify(
      baseInput({
        phase: 'READY_TO_FINALIZE',
        proposalReady: false,
        completeComponents: 5,
        totalComponents: 5,
        finalized: false,
      }),
    )
    expect(result).not.toBe('PROPOSAL_READY')
  })

  it('classifies OUT_OF_SCOPE', () => {
    const result = classify(
      baseInput({ outOfScopeIndicator: true }),
    )
    expect(result).toBe('OUT_OF_SCOPE')
  })

  it('classifies FAILED for errors', () => {
    const result = classify(
      baseInput({ errorVisible: true }),
    )
    expect(result).toBe('FAILED')
  })

  it('classifies FAILED for TERMINAL_INVALID', () => {
    const result = classify(
      baseInput({ phase: 'TERMINAL_INVALID' }),
    )
    expect(result).toBe('FAILED')
  })

  it('classifies NEEDS_INPUT when awaiting clarification', () => {
    const result = classify(
      baseInput({ awaitingClarification: true }),
    )
    expect(result).toBe('NEEDS_INPUT')
  })

  it('classifies BLOCKED when refusal is visible', () => {
    const result = classify(
      baseInput({ refusalVisible: true }),
    )
    expect(result).toBe('BLOCKED')
  })

  it('classifies MAX_TURNS when turn count equals max', () => {
    const result = classify(
      baseInput({ turnCount: 12, maxTurns: 12 }),
    )
    expect(result).toBe('MAX_TURNS')
  })

  it('classifies MAX_TURNS when turn count exceeds max', () => {
    const result = classify(
      baseInput({ turnCount: 13, maxTurns: 12 }),
    )
    expect(result).toBe('MAX_TURNS')
  })

  it('classifies IN_PROGRESS for normal building state', () => {
    const result = classify(
      baseInput({
        phase: 'BUILDING',
        completeComponents: 2,
        totalComponents: 5,
      }),
    )
    expect(result).toBe('IN_PROGRESS')
  })

  it('classifies IN_PROGRESS before FINALIZE even with READY_TO_FINALIZE', () => {
    const result = classify(
      baseInput({
        phase: 'READY_TO_FINALIZE',
        completeComponents: 5,
        totalComponents: 5,
        finalized: false,
      }),
    )
    expect(result).toBe('IN_PROGRESS')
  })
})

describe('Terminal result utilities', () => {
  it('isLegitimateTerminal recognizes legitimate results', () => {
    expect(isLegitimateTerminal('PROPOSAL_READY')).toBe(true)
    expect(isLegitimateTerminal('OUT_OF_SCOPE')).toBe(true)
    expect(isLegitimateTerminal('BLOCKED')).toBe(true)
    expect(isLegitimateTerminal('FAILED')).toBe(false)
    expect(isLegitimateTerminal('MAX_TURNS')).toBe(false)
    expect(isLegitimateTerminal('IN_PROGRESS')).toBe(false)
  })

  it('isPass recognizes passing results', () => {
    expect(isPass('PROPOSAL_READY')).toBe(true)
    expect(isPass('OUT_OF_SCOPE')).toBe(true)
    expect(isPass('FAILED')).toBe(false)
    expect(isPass('BLOCKED')).toBe(false)
    expect(isPass('MAX_TURNS')).toBe(false)
    expect(isPass('IN_PROGRESS')).toBe(false)
  })

  it('terminalLabel returns human-readable labels', () => {
    expect(terminalLabel('PROPOSAL_READY')).toContain('complete')
    expect(terminalLabel('OUT_OF_SCOPE')).toContain('scope')
    expect(terminalLabel('BLOCKED')).toContain('Blocked')
    expect(terminalLabel('FAILED')).toContain('Failed')
    expect(terminalLabel('MAX_TURNS')).toContain('Max turns')
    expect(terminalLabel('IN_PROGRESS')).toContain('progress')
    expect(terminalLabel('NEEDS_INPUT')).toContain('Needs input')
  })
})