// @vitest-environment node
import { mkdirSync, mkdtempSync, readFileSync, readdirSync, writeFileSync, rmSync, unlinkSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { afterEach, describe, expect, it } from 'vitest'
import {
  T24_ASSET_VERSION,
  T24_EVALUATION_ERROR_CODES,
  T24EvaluationError,
  T24EvaluationLoader,
  categories,
  declares,
  type T24EvaluationCorpus,
  type T24FileSystem,
} from './uiT24Loader'

/** `frontend/src/evaluation/` -> the repository root, so the committed assets are read from disk. */
const REPOSITORY_ROOT = fileURLToPath(new URL('../../..', import.meta.url))
const ASSET_DIRECTORY = 'evaluation/ui/t24-v1'

function nodeFileSystem(): T24FileSystem {
  return {
    listFiles: (directory) => readdirSync(directory),
    readFile: (path) => new Uint8Array(readFileSync(path)),
  }
}

function loaderFor(root: string): T24EvaluationLoader {
  return new T24EvaluationLoader(nodeFileSystem(), { repositoryRoot: root })
}

function loadCommitted(): T24EvaluationCorpus {
  return loaderFor(REPOSITORY_ROOT).loadCorpus()
}

const temporaryRoots: string[] = []

const tempAssetDirectory = (root: string): string => join(root, ASSET_DIRECTORY)

function copyCommittedCorpus(): string {
  const root = mkdtempSync(join(tmpdir(), 't24-'))
  temporaryRoots.push(root)
  const target = tempAssetDirectory(root)
  mkdirSync(target, { recursive: true })
  for (const name of ['manifest.json', 'cases.json']) {
    writeFileSync(join(target, name), readFileSync(join(REPOSITORY_ROOT, ASSET_DIRECTORY, name)))
  }
  return root
}

afterEach(() => {
  while (temporaryRoots.length > 0) {
    const root = temporaryRoots.pop()
    if (root !== undefined) {
      rmSync(root, { recursive: true, force: true })
    }
  }
})

function expectCode(fn: () => unknown, code: string): void {
  try {
    fn()
  } catch (thrown) {
    expect(thrown).toBeInstanceOf(T24EvaluationError)
    expect((thrown as T24EvaluationError).code).toBe(code)
    return
  }
  throw new Error('expected a bounded T24EvaluationError')
}

describe('the committed t24-v1 corpus', () => {
  it('loads as frozen, canonical, development evidence', () => {
    const corpus = loadCommitted()
    expect(corpus.manifest.assetVersion).toBe(T24_ASSET_VERSION)
    expect(corpus.manifest.assetId).toBe('ui-t24-manifest')
    expect(corpus.cases.assetId).toBe('ui-t24-cases')
    // The records refuse to represent production Gate evidence.
    expect(corpus.manifest.productionGateEvidence).toBe(false)
    expect(corpus.cases.productionGateEvidence).toBe(false)
    expect(corpus.manifest.caseCount).toBe(corpus.cases.cases.length)
    expect(corpus.cases.cases.length).toBeGreaterThan(0)
    for (const item of corpus.cases.cases) {
      expect(item.assertions.length).toBeGreaterThan(0)
    }
  })

  it('covers every required requirement category', () => {
    const required = [
      'ACCESSIBILITY',
      'APPLY',
      'AUTHORING',
      'DIFF',
      'ENVIRONMENT',
      'IDENTITY',
      'OPERATIONS',
      'PERMISSIONS',
      'PUBLISH',
      'RECONCILE_RETRY',
      'RESILIENCE',
      'SEGMENT_PREVIEW',
      'SIMULATION',
      'SUMMARY_CARDS',
      'UNRESOLVED',
      'UNSAVED_STATE',
    ]
    expect(categories(loadCommitted())).toEqual(required)
  })

  it('declares the required proofs for each requirement', () => {
    const corpus = loadCommitted()
    expect(declares(corpus, 'ai-and-visual-builder-share-one-proposal-schema', 'SAME_PROPOSAL_SCHEMA')).toBe(true)
    expect(declares(corpus, 'model-prose-never-changes-saved-state', 'DRAFT_INTERACTION_ONLY')).toBe(true)
    expect(declares(corpus, 'apply-uses-exact-plan-identity', 'PLAN_ID_AND_HASH_ONLY')).toBe(true)
    expect(declares(corpus, 'apply-uses-exact-plan-identity', 'NO_CHANGESET_UPLOAD')).toBe(true)
    expect(declares(corpus, 'publish-is-two-step-and-cannot-skip-confirmation', 'EXPLICIT_CONFIRMATION_REQUIRED')).toBe(true)
    expect(declares(corpus, 'simulation-marker-is-persistent', 'SIMULATED_MARKER_PERSISTENT')).toBe(true)
    expect(
      declares(corpus, 'unknown-verifying-and-needs-review-are-strong-callouts', 'NO_BLIND_RESEND'),
    ).toBe(true)
    expect(declares(corpus, 'reconcile-and-safe-retry-are-distinct', 'RETRY_REQUIRES_ELIGIBILITY')).toBe(true)
    expect(declares(corpus, 'permission-disabled-actions-explain-why', 'OPERATE_GUARDED')).toBe(true)
    expect(
      declares(corpus, 'stale-responses-are-suppressed-and-edits-preserved', 'EDITS_RETAINED_ON_FAILURE'),
    ).toBe(true)
    expect(declares(corpus, 'status-is-never-conveyed-by-colour-alone', 'SHAPE_MARKER')).toBe(true)
    expect(
      declares(corpus, 'fixture-evidence-never-satisfies-production-readiness', 'READINESS_CLAIM_REFUSED'),
    ).toBe(true)
    expect(
      declares(corpus, 'evaluation-assets-are-not-production-evidence', 'PRODUCTION_GATE_EVIDENCE_FALSE'),
    ).toBe(true)
  })

  it('keeps both committed documents strictly canonical', () => {
    for (const name of ['manifest.json', 'cases.json']) {
      const text = readFileSync(join(REPOSITORY_ROOT, ASSET_DIRECTORY, name), 'utf8')
      expect(text).not.toContain('\n')
      expect(text).not.toContain('": ')
      expect(text).not.toContain(', ')
      expect(text.startsWith('{"assetId":')).toBe(true)
    }
  })
})

describe('the t24-v1 loader refuses tampering', () => {
  it('refuses an extra asset', () => {
    const root = copyCommittedCorpus()
    writeFileSync(join(tempAssetDirectory(root), 'extra.json'), '{}')
    expectCode(() => loaderFor(root).loadCorpus(), T24_EVALUATION_ERROR_CODES.ASSET_SET_INVALID)
  })

  it('refuses a missing asset', () => {
    const root = copyCommittedCorpus()
    unlinkSync(join(tempAssetDirectory(root), 'cases.json'))
    expectCode(() => loaderFor(root).loadCorpus(), T24_EVALUATION_ERROR_CODES.ASSET_SET_INVALID)
  })

  it('refuses a tampered cases document', () => {
    const root = copyCommittedCorpus()
    const path = join(tempAssetDirectory(root), 'cases.json')
    const text = readFileSync(path, 'utf8')
    writeFileSync(path, text.replace('ACCEPTED', 'ACCEPTEDX'))
    expectCode(() => loaderFor(root).loadCorpus(), T24_EVALUATION_ERROR_CODES.ASSET_HASH_MISMATCH)
  })

  it('refuses non-canonical whitespace', () => {
    const root = copyCommittedCorpus()
    const path = join(tempAssetDirectory(root), 'cases.json')
    writeFileSync(path, `${readFileSync(path, 'utf8')}\n`)
    expectCode(() => loaderFor(root).loadCorpus(), T24_EVALUATION_ERROR_CODES.ASSET_NON_CANONICAL)
  })

  it('refuses an unknown field', () => {
    const root = copyCommittedCorpus()
    const path = join(tempAssetDirectory(root), 'cases.json')
    const text = readFileSync(path, 'utf8')
    writeFileSync(path, text.replace('{"assetId":', '{"additional":true,"assetId":'))
    expectCode(() => loaderFor(root).loadCorpus(), T24_EVALUATION_ERROR_CODES.ASSET_NON_CANONICAL)
  })

  it('refuses a manifest whose self-hash was not updated', () => {
    const root = copyCommittedCorpus()
    const path = join(tempAssetDirectory(root), 'manifest.json')
    const text = readFileSync(path, 'utf8')
    writeFileSync(path, text.replace('"caseCount":20', '"caseCount":19'))
    expectCode(() => loaderFor(root).loadCorpus(), T24_EVALUATION_ERROR_CODES.MANIFEST_HASH_MISMATCH)
  })

  it('refuses a relabelled production Gate flag', () => {
    const root = copyCommittedCorpus()
    const path = join(tempAssetDirectory(root), 'cases.json')
    const text = readFileSync(path, 'utf8')
    writeFileSync(path, text.replace('"productionGateEvidence":false', '"productionGateEvidence":true'))
    // The typed record refuses to represent `true` before any hash could be recomputed.
    expectCode(() => loaderFor(root).loadCorpus(), T24_EVALUATION_ERROR_CODES.CORPUS_INVALID)
  })

  it('refuses a duplicate JSON key', () => {
    const root = copyCommittedCorpus()
    const path = join(tempAssetDirectory(root), 'cases.json')
    const text = readFileSync(path, 'utf8')
    writeFileSync(path, text.replace('"assetVersion":"t24-v1"', '"assetVersion":"t24-v1","assetVersion":"t24-v1"'))
    expectCode(() => loaderFor(root).loadCorpus(), T24_EVALUATION_ERROR_CODES.ASSET_NON_CANONICAL)
  })
})
