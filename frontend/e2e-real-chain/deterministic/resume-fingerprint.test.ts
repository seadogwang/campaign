/**
 * Deterministic tests for resume fingerprint support.
 */
import { describe, it, expect, beforeEach } from 'vitest'
import { existsSync, mkdirSync, rmSync, writeFileSync } from 'fs'
import { resolve } from 'path'
import {
  setFingerprint,
  getFingerprint,
  fingerprintHash,
  saveFingerprint,
  loadFingerprint,
  markCompleted,
  loadCompleted,
  canResume,
  computeAppBuildHash,
  computeResourceCatalogHash,
  type RunFingerprint,
} from '../resume-store'

const TEST_DIR = resolve(__dirname, '..', 'test-tmp-resume')

function makeFingerprint(overrides: Partial<RunFingerprint> = {}): RunFingerprint {
  return {
    modelAlias: 'test-model',
    corpusHash: 'abc123',
    appBuildHash: 'build456',
    resourceCatalogHash: 'cat789',
    runnerVersion: '1.0.0',
    createdAt: new Date().toISOString(),
    ...overrides,
  }
}

describe('Resume fingerprint', () => {
  beforeEach(() => {
    if (existsSync(TEST_DIR)) {
      rmSync(TEST_DIR, { recursive: true })
    }
    mkdirSync(TEST_DIR, { recursive: true })
  })

  it('stores and retrieves the run fingerprint', () => {
    const fp = makeFingerprint()
    setFingerprint(fp)
    const retrieved = getFingerprint()
    expect(retrieved?.modelAlias).toBe('test-model')
    expect(retrieved?.corpusHash).toBe('abc123')
  })

  it('produces a stable fingerprint hash', () => {
    const fp = makeFingerprint()
    const hash1 = fingerprintHash(fp)
    const hash2 = fingerprintHash(fp)
    expect(hash1).toBe(hash2)
    expect(hash1.length).toBe(64) // SHA-256 hex
  })

  it('produces different hashes for different fingerprints', () => {
    const fp1 = makeFingerprint()
    const fp2 = makeFingerprint({ modelAlias: 'different-model' })
    const hash1 = fingerprintHash(fp1)
    const hash2 = fingerprintHash(fp2)
    expect(hash1).not.toBe(hash2)
  })

  it('saves and loads fingerprint from disk', () => {
    const fp = makeFingerprint()
    saveFingerprint(TEST_DIR, fp)
    const loaded = loadFingerprint(TEST_DIR)
    expect(loaded?.modelAlias).toBe('test-model')
    expect(loaded?.corpusHash).toBe('abc123')
  })

  it('returns null when no fingerprint file exists', () => {
    const loaded = loadFingerprint(TEST_DIR)
    expect(loaded).toBeNull()
  })

  it('marks and resumes completed scenarios', () => {
    const fp = makeFingerprint()
    setFingerprint(fp)
    saveFingerprint(TEST_DIR, fp)
    loadCompleted(TEST_DIR)

    markCompleted(TEST_DIR, 'C-01', 'PROPOSAL_READY')
    markCompleted(TEST_DIR, 'SS-05', 'OUT_OF_SCOPE')

    // Can resume with same fingerprint
    expect(canResume('C-01')).toBeTruthy()
    expect(canResume('C-01')?.result).toBe('PROPOSAL_READY')
    expect(canResume('SS-05')).toBeTruthy()

    // Cannot resume unknown scenario
    expect(canResume('C-99')).toBeNull()
  })

  it('refuses resume when fingerprint changes', () => {
    // First run
    const fp1 = makeFingerprint()
    setFingerprint(fp1)
    markCompleted(TEST_DIR, 'C-01', 'PROPOSAL_READY')

    // Second run with different model
    const fp2 = makeFingerprint({ modelAlias: 'different-model' })
    setFingerprint(fp2)

    // Should not resume because fingerprint mismatch
    expect(canResume('C-01')).toBeNull()
  })

  it('refuses resume when corpus hash changes', () => {
    const fp1 = makeFingerprint()
    setFingerprint(fp1)
    markCompleted(TEST_DIR, 'C-01', 'PROPOSAL_READY')

    const fp2 = makeFingerprint({ corpusHash: 'changed-corpus' })
    setFingerprint(fp2)

    expect(canResume('C-01')).toBeNull()
  })
})