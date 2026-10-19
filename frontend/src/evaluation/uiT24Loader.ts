/**
 * Strict loader for the `evaluation/ui/t24-v1` corpus.
 *
 * It mirrors the accepted T20/T21/T22/T23 loader discipline:
 *  - the asset directory must contain exactly `manifest.json` and `cases.json`;
 *  - each file's bytes must equal its own canonical serialisation (so reviewed
 *    bytes and reviewed value cannot differ);
 *  - the manifest pins the cases file by SHA-256 and carries its own self-hash
 *    computed over the manifest with `manifestHash` removed;
 *  - unknown fields, duplicate keys, wrong shapes and hash tampering are
 *    refused with a bounded stable code;
 *  - `productionGateEvidence` must be `false` in both documents, so a T24 asset
 *    can never be relabelled as production Gate evidence.
 *
 * The loader takes its filesystem through a small port, so it stays free of any
 * Node-only import and can run in a browser, Node or jsdom unchanged.
 */

import { contentHashOfBytes, contentHashOfCanonical, canonicalize } from '../contracts/canonical'
import type { ContentHash } from '../contracts/primitives'
import type { JsonObject, JsonValue } from '../contracts/validate'

export const T24_SCHEMA_VERSION = '1.0'
export const T24_ASSET_VERSION = 't24-v1'
export const T24_MANIFEST_ASSET_ID = 'ui-t24-manifest'
export const T24_CASES_ASSET_ID = 'ui-t24-cases'
export const T24_CASES_REFERENCE = 'evaluation/ui/t24-v1/cases.json'
export const T24_MANIFEST_REFERENCE = 'evaluation/ui/t24-v1/manifest.json'
export const T24_EVALUATION_ROOT = 'evaluation/ui/t24-v1'
export const T24_EXPECTED_FILES: readonly string[] = ['cases.json', 'manifest.json']
export const T24_MAX_CASES = 64
export const T24_MAX_ASSERTIONS = 16

export const T24_EVALUATION_ERROR_CODES = {
  ASSET_SET_INVALID: 'T24_EVALUATION_ASSET_SET_INVALID',
  ASSET_UNREADABLE: 'T24_EVALUATION_ASSET_UNREADABLE',
  ASSET_NON_CANONICAL: 'T24_EVALUATION_ASSET_NON_CANONICAL',
  ASSET_HASH_MISMATCH: 'T24_EVALUATION_ASSET_HASH_MISMATCH',
  MANIFEST_HASH_MISMATCH: 'T24_EVALUATION_MANIFEST_HASH_MISMATCH',
  ASSET_IDENTITY_MISMATCH: 'T24_EVALUATION_ASSET_IDENTITY_MISMATCH',
  CORPUS_INVALID: 'T24_EVALUATION_CORPUS_INVALID',
} as const

export type T24EvaluationErrorCode =
  (typeof T24_EVALUATION_ERROR_CODES)[keyof typeof T24_EVALUATION_ERROR_CODES]

const FIXED_MESSAGES: Readonly<Record<T24EvaluationErrorCode, string>> = {
  T24_EVALUATION_ASSET_SET_INVALID: 'The T24 evaluation asset set is not exactly the expected two files.',
  T24_EVALUATION_ASSET_UNREADABLE: 'A T24 evaluation asset could not be read.',
  T24_EVALUATION_ASSET_NON_CANONICAL: 'A T24 evaluation asset is not its own canonical form.',
  T24_EVALUATION_ASSET_HASH_MISMATCH: 'A T24 evaluation asset does not match its pinned hash.',
  T24_EVALUATION_MANIFEST_HASH_MISMATCH: 'The T24 evaluation manifest self-hash is wrong.',
  T24_EVALUATION_ASSET_IDENTITY_MISMATCH: 'The T24 evaluation asset identities disagree.',
  T24_EVALUATION_CORPUS_INVALID: 'The T24 evaluation corpus is invalid.',
}

export class T24EvaluationError extends Error {
  readonly code: T24EvaluationErrorCode

  constructor(code: T24EvaluationErrorCode) {
    super(FIXED_MESSAGES[code])
    this.name = 'T24EvaluationError'
    this.code = code
  }
}

function fail(code: T24EvaluationErrorCode): never {
  throw new T24EvaluationError(code)
}

export interface T24FileSystem {
  /** Regular file names directly inside `directory`, in any order. */
  listFiles(directory: string): readonly string[]
  /** Raw bytes of one repository-relative path. */
  readFile(path: string): Uint8Array
}

export interface T24EvaluationCase {
  readonly id: string
  readonly category: string
  readonly expectedOutcome: string
  readonly assertions: readonly string[]
}

export interface T24EvaluationManifest {
  readonly schemaVersion: string
  readonly assetId: string
  readonly assetVersion: string
  readonly productionGateEvidence: false
  readonly casesPath: string
  readonly casesHash: ContentHash
  readonly caseCount: number
  readonly manifestHash: ContentHash
}

export interface T24EvaluationCases {
  readonly schemaVersion: string
  readonly assetId: string
  readonly assetVersion: string
  readonly productionGateEvidence: false
  readonly cases: readonly T24EvaluationCase[]
}

export interface T24EvaluationCorpus {
  readonly manifest: T24EvaluationManifest
  readonly cases: T24EvaluationCases
}

export interface T24EvaluationLoaderOptions {
  /** Repository root: every asset path is resolved beneath it. */
  readonly repositoryRoot: string
}

const CASE_ID_PATTERN = /^[a-z][a-z0-9-]{0,63}$/
const MACHINE_CODE_PATTERN = /^[A-Z][A-Z0-9_]{0,63}$/
const MANIFEST_FIELDS = [
  'schemaVersion',
  'assetId',
  'assetVersion',
  'productionGateEvidence',
  'casesPath',
  'casesHash',
  'caseCount',
  'manifestHash',
] as const
const CASES_FIELDS = ['schemaVersion', 'assetId', 'assetVersion', 'productionGateEvidence', 'cases'] as const
const CASE_FIELDS = ['id', 'category', 'expectedOutcome', 'assertions'] as const

function requireJsonValue(value: unknown, code: T24EvaluationErrorCode): JsonValue {
  if (value === null || typeof value === 'string' || typeof value === 'boolean') {
    return value
  }
  if (typeof value === 'number') {
    if (!Number.isFinite(value)) {
      fail(code)
    }
    return value
  }
  if (Array.isArray(value)) {
    return value.map((item) => requireJsonValue(item, code))
  }
  if (typeof value === 'object') {
    const out: Record<string, JsonValue> = {}
    for (const [key, member] of Object.entries(value as Record<string, unknown>)) {
      out[key] = requireJsonValue(member, code)
    }
    return out
  }
  return fail(code)
}

function requireJsonObject(value: unknown, code: T24EvaluationErrorCode): JsonObject {
  const parsed = requireJsonValue(value, code)
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    fail(code)
  }
  return parsed
}

function exactFields(obj: JsonObject, allowed: readonly string[], code: T24EvaluationErrorCode): void {
  const allowedSet = new Set(allowed)
  for (const key of Object.keys(obj)) {
    if (!allowedSet.has(key)) {
      // Unknown fields are refused: they are not silently ignored.
      fail(code)
    }
  }
}

function requireText(obj: JsonObject, name: string, code: T24EvaluationErrorCode): string {
  const value = obj[name]
  if (typeof value !== 'string') {
    fail(code)
  }
  return value
}

function requireFalse(obj: JsonObject, name: string, code: T24EvaluationErrorCode): false {
  if (obj[name] !== false) {
    fail(code)
  }
  return false
}

export class T24EvaluationLoader {
  private readonly fileSystem: T24FileSystem
  private readonly repositoryRoot: string

  constructor(fileSystem: T24FileSystem, options: T24EvaluationLoaderOptions) {
    this.fileSystem = fileSystem
    this.repositoryRoot = options.repositoryRoot.replace(/[\\/]+$/, '')
  }

  private resolve(reference: string): string {
    return `${this.repositoryRoot}/${reference}`
  }

  private decodeUtf8(bytes: Uint8Array): string {
    try {
      return new TextDecoder('utf-8', { fatal: true }).decode(bytes)
    } catch {
      return fail(T24_EVALUATION_ERROR_CODES.ASSET_UNREADABLE)
    }
  }

  private readCanonical(reference: string): { bytes: Uint8Array; text: string; parsed: JsonObject } {
    let bytes: Uint8Array
    try {
      bytes = this.fileSystem.readFile(this.resolve(reference))
    } catch {
      return fail(T24_EVALUATION_ERROR_CODES.ASSET_UNREADABLE)
    }
    const text = this.decodeUtf8(bytes)
    let raw: unknown
    try {
      raw = JSON.parse(text)
    } catch {
      return fail(T24_EVALUATION_ERROR_CODES.ASSET_NON_CANONICAL)
    }
    const parsed = requireJsonObject(raw, T24_EVALUATION_ERROR_CODES.ASSET_NON_CANONICAL)
    if (canonicalize(parsed) !== text) {
      // Pretty-printing, stray whitespace or a duplicate key surfaces here.
      fail(T24_EVALUATION_ERROR_CODES.ASSET_NON_CANONICAL)
    }
    return { bytes, text, parsed }
  }

  private requireExactFileSet(): void {
    let entries: readonly string[]
    try {
      entries = this.fileSystem.listFiles(this.resolve(T24_EVALUATION_ROOT))
    } catch {
      return fail(T24_EVALUATION_ERROR_CODES.ASSET_SET_INVALID)
    }
    const actual = [...entries].sort()
    const expected = [...T24_EXPECTED_FILES].sort()
    if (actual.length !== expected.length || actual.some((name, index) => name !== expected[index])) {
      fail(T24_EVALUATION_ERROR_CODES.ASSET_SET_INVALID)
    }
  }

  private decodeManifest(parsed: JsonObject, text: string): T24EvaluationManifest {
    exactFields(parsed, MANIFEST_FIELDS, T24_EVALUATION_ERROR_CODES.ASSET_NON_CANONICAL)
    const material: JsonObject = { ...parsed }
    delete material['manifestHash']
    const manifestHash = requireText(parsed, 'manifestHash', T24_EVALUATION_ERROR_CODES.ASSET_NON_CANONICAL)
    if (contentHashOfCanonical(material) !== manifestHash) {
      fail(T24_EVALUATION_ERROR_CODES.MANIFEST_HASH_MISMATCH)
    }
    const caseCount = parsed['caseCount']
    if (typeof caseCount !== 'number' || !Number.isInteger(caseCount) || caseCount < 1 || caseCount > T24_MAX_CASES) {
      fail(T24_EVALUATION_ERROR_CODES.CORPUS_INVALID)
    }
    const manifest: T24EvaluationManifest = {
      schemaVersion: requireText(parsed, 'schemaVersion', T24_EVALUATION_ERROR_CODES.CORPUS_INVALID),
      assetId: requireText(parsed, 'assetId', T24_EVALUATION_ERROR_CODES.CORPUS_INVALID),
      assetVersion: requireText(parsed, 'assetVersion', T24_EVALUATION_ERROR_CODES.CORPUS_INVALID),
      productionGateEvidence: requireFalse(parsed, 'productionGateEvidence', T24_EVALUATION_ERROR_CODES.CORPUS_INVALID),
      casesPath: requireText(parsed, 'casesPath', T24_EVALUATION_ERROR_CODES.CORPUS_INVALID),
      casesHash: requireText(parsed, 'casesHash', T24_EVALUATION_ERROR_CODES.CORPUS_INVALID) as ContentHash,
      caseCount,
      manifestHash: manifestHash as ContentHash,
    }
    if (
      manifest.schemaVersion !== T24_SCHEMA_VERSION
      || manifest.assetId !== T24_MANIFEST_ASSET_ID
      || manifest.assetVersion !== T24_ASSET_VERSION
      || manifest.casesPath !== T24_CASES_REFERENCE
    ) {
      fail(T24_EVALUATION_ERROR_CODES.CORPUS_INVALID)
    }
    if (text.length === 0) {
      fail(T24_EVALUATION_ERROR_CODES.ASSET_NON_CANONICAL)
    }
    return manifest
  }

  private decodeCase(raw: JsonValue): T24EvaluationCase {
    const obj = requireJsonObject(raw, T24_EVALUATION_ERROR_CODES.CORPUS_INVALID)
    exactFields(obj, CASE_FIELDS, T24_EVALUATION_ERROR_CODES.CORPUS_INVALID)
    const id = requireText(obj, 'id', T24_EVALUATION_ERROR_CODES.CORPUS_INVALID)
    const category = requireText(obj, 'category', T24_EVALUATION_ERROR_CODES.CORPUS_INVALID)
    const expectedOutcome = requireText(obj, 'expectedOutcome', T24_EVALUATION_ERROR_CODES.CORPUS_INVALID)
    if (!CASE_ID_PATTERN.test(id) || !MACHINE_CODE_PATTERN.test(category) || !MACHINE_CODE_PATTERN.test(expectedOutcome)) {
      fail(T24_EVALUATION_ERROR_CODES.CORPUS_INVALID)
    }
    const assertionsRaw = obj['assertions']
    if (!Array.isArray(assertionsRaw) || assertionsRaw.length === 0 || assertionsRaw.length > T24_MAX_ASSERTIONS) {
      fail(T24_EVALUATION_ERROR_CODES.CORPUS_INVALID)
    }
    const assertions: string[] = []
    for (const assertion of assertionsRaw) {
      if (typeof assertion !== 'string' || !MACHINE_CODE_PATTERN.test(assertion)) {
        fail(T24_EVALUATION_ERROR_CODES.CORPUS_INVALID)
      }
      if (assertions.includes(assertion)) {
        fail(T24_EVALUATION_ERROR_CODES.CORPUS_INVALID)
      }
      assertions.push(assertion)
    }
    return { id, category, expectedOutcome, assertions }
  }

  private decodeCases(parsed: JsonObject): T24EvaluationCases {
    exactFields(parsed, CASES_FIELDS, T24_EVALUATION_ERROR_CODES.ASSET_NON_CANONICAL)
    const rawCases = parsed['cases']
    if (!Array.isArray(rawCases) || rawCases.length === 0 || rawCases.length > T24_MAX_CASES) {
      fail(T24_EVALUATION_ERROR_CODES.CORPUS_INVALID)
    }
    const cases = rawCases.map((item) => this.decodeCase(item))
    const seen = new Set<string>()
    for (const item of cases) {
      if (seen.has(item.id)) {
        fail(T24_EVALUATION_ERROR_CODES.CORPUS_INVALID)
      }
      seen.add(item.id)
    }
    const document: T24EvaluationCases = {
      schemaVersion: requireText(parsed, 'schemaVersion', T24_EVALUATION_ERROR_CODES.CORPUS_INVALID),
      assetId: requireText(parsed, 'assetId', T24_EVALUATION_ERROR_CODES.CORPUS_INVALID),
      assetVersion: requireText(parsed, 'assetVersion', T24_EVALUATION_ERROR_CODES.CORPUS_INVALID),
      productionGateEvidence: requireFalse(parsed, 'productionGateEvidence', T24_EVALUATION_ERROR_CODES.CORPUS_INVALID),
      cases,
    }
    if (
      document.schemaVersion !== T24_SCHEMA_VERSION
      || document.assetId !== T24_CASES_ASSET_ID
      || document.assetVersion !== T24_ASSET_VERSION
    ) {
      fail(T24_EVALUATION_ERROR_CODES.CORPUS_INVALID)
    }
    return document
  }

  loadCorpus(): T24EvaluationCorpus {
    this.requireExactFileSet()
    const manifestFile = this.readCanonical(T24_MANIFEST_REFERENCE)
    const manifest = this.decodeManifest(manifestFile.parsed, manifestFile.text)
    const casesFile = this.readCanonical(T24_CASES_REFERENCE)
    const cases = this.decodeCases(casesFile.parsed)
    if (contentHashOfBytes(casesFile.bytes) !== manifest.casesHash) {
      fail(T24_EVALUATION_ERROR_CODES.ASSET_HASH_MISMATCH)
    }
    if (
      manifest.caseCount !== cases.cases.length
      || manifest.assetVersion !== cases.assetVersion
      || manifest.assetId !== T24_MANIFEST_ASSET_ID
    ) {
      fail(T24_EVALUATION_ERROR_CODES.ASSET_IDENTITY_MISMATCH)
    }
    return { manifest, cases }
  }
}

export function declares(corpus: T24EvaluationCorpus, caseId: string, assertion: string): boolean {
  const found = corpus.cases.cases.find((item) => item.id === caseId)
  return found !== undefined && found.assertions.includes(assertion)
}

export function categories(corpus: T24EvaluationCorpus): readonly string[] {
  return [...new Set(corpus.cases.cases.map((item) => item.category))].sort()
}