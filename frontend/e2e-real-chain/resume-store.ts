import { existsSync, readFileSync, writeFileSync } from 'fs'
import { resolve } from 'path'
import { createHash } from 'crypto'

/**
 * Configuration fingerprint for resume support.
 *
 * Completed scenario results are safely reusable only when the recorded
 * run configuration fingerprint is identical. Otherwise rerun.
 */
export interface RunFingerprint {
  /** Model alias used for this run. */
  modelAlias: string
  /** Hash of all scenario descriptions (corpus hash). */
  corpusHash: string
  /** Application build hash. */
  appBuildHash: string
  /** Resource catalog hash. */
  resourceCatalogHash: string
  /** Runner version (from package.json or hardcoded). */
  runnerVersion: string
  /** Timestamp when the fingerprint was created. */
  createdAt: string
}

/** A completed scenario result stored for resume. */
export interface CompletedResult {
  scenario: string
  result: string
  fingerprintHash: string
  completedAt: string
}

/** In-memory resume store. */
interface ResumeStore {
  fingerprint: RunFingerprint | null
  completed: Map<string, CompletedResult>
}

let store: ResumeStore = { fingerprint: null, completed: new Map() }

/**
 * Computes the SHA-256 hash of the run fingerprint.
 */
export function fingerprintHash(fp: RunFingerprint): string {
  const hash = createHash('sha256')
  hash.update(fp.modelAlias)
  hash.update(fp.corpusHash)
  hash.update(fp.appBuildHash)
  hash.update(fp.resourceCatalogHash)
  hash.update(fp.runnerVersion)
  return hash.digest('hex')
}

/**
 * Sets the current run fingerprint.
 */
export function setFingerprint(fp: RunFingerprint): void {
  store.fingerprint = fp
}

/**
 * Gets the current run fingerprint.
 */
export function getFingerprint(): RunFingerprint | null {
  return store.fingerprint
}

/**
 * Saves the fingerprint to disk.
 */
export function saveFingerprint(evidenceDir: string, fp: RunFingerprint): void {
  const filepath = resolve(evidenceDir, 'fingerprint.json')
  writeFileSync(filepath, JSON.stringify(fp, null, 2))
}

/**
 * Loads a previously saved fingerprint.
 */
export function loadFingerprint(evidenceDir: string): RunFingerprint | null {
  const filepath = resolve(evidenceDir, 'fingerprint.json')
  if (!existsSync(filepath)) return null
  try {
    return JSON.parse(readFileSync(filepath, 'utf-8')) as RunFingerprint
  } catch {
    return null
  }
}

/**
 * Marks a scenario as completed for resume purposes.
 */
export function markCompleted(
  evidenceDir: string,
  scenario: string,
  result: string,
): void {
  const fp = store.fingerprint
  if (!fp) return

  const hash = fingerprintHash(fp)
  const completed: CompletedResult = {
    scenario,
    result,
    fingerprintHash: hash,
    completedAt: new Date().toISOString(),
  }
  store.completed.set(scenario, completed)

  // Persist to disk
  const filepath = resolve(evidenceDir, 'completed.json')
  const all = Object.fromEntries(
    Array.from(store.completed.entries()).map(([k, v]) => [k, v]),
  )
  writeFileSync(filepath, JSON.stringify(all, null, 2))
}

/**
 * Loads previously completed results from disk.
 */
export function loadCompleted(evidenceDir: string): void {
  const filepath = resolve(evidenceDir, 'completed.json')
  if (!existsSync(filepath)) return
  try {
    const data = JSON.parse(readFileSync(filepath, 'utf-8')) as Record<string, CompletedResult>
    store.completed = new Map(Object.entries(data))
  } catch {
    store.completed = new Map()
  }
}

/**
 * Checks whether a scenario can be skipped (already completed with matching fingerprint).
 *
 * Returns the cached result if safe to reuse, null otherwise.
 */
export function canResume(scenario: string): CompletedResult | null {
  const fp = store.fingerprint
  if (!fp) return null

  const completed = store.completed.get(scenario)
  if (!completed) return null

  const currentHash = fingerprintHash(fp)
  if (completed.fingerprintHash !== currentHash) return null

  return completed
}

/**
 * Returns all completed results.
 */
export function getCompleted(): CompletedResult[] {
  return Array.from(store.completed.values())
}

/**
 * Computes an app build hash from the dist/index.html or jar metadata.
 */
export function computeAppBuildHash(appDir: string): string {
  const distIndexPath = resolve(appDir, 'frontend', 'dist', 'index.html')
  if (existsSync(distIndexPath)) {
    const content = readFileSync(distIndexPath, 'utf-8')
    const hash = createHash('sha256')
    hash.update(content)
    return hash.digest('hex').slice(0, 16)
  }
  return 'unknown'
}

/**
 * Computes a resource catalog hash from the seed SQL file.
 */
export function computeResourceCatalogHash(appDir: string): string {
  const seedPath = resolve(
    appDir,
    'src/main/resources/db/campaign-conversation-dev-seed/campaign-business-resources.sql',
  )
  if (existsSync(seedPath)) {
    const content = readFileSync(seedPath, 'utf-8')
    const hash = createHash('sha256')
    hash.update(content)
    return hash.digest('hex').slice(0, 16)
  }
  return 'unknown'
}