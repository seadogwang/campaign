/**
 * Bounded client-side primitives that mirror the accepted Java contract vocabulary.
 *
 * These are deliberately narrower than the server contracts: they carry only the
 * fields the UI renders. They never replace a server contract and they never
 * carry a server-produced artifact (Manifest, ChangeSet, plan material) back up.
 */

/** A canonical content hash: the literal string `sha256:` plus 64 lowercase hex characters. */
export type ContentHash = string & { readonly __brand: 'ContentHash' }

/** A stable uppercase machine code, e.g. `SEGMENT_PREVIEW_DATA_STALE` or `TRANSPORT_FAILED`. */
export type MachineCode = string & { readonly __brand: 'MachineCode' }

export const CONTENT_HASH_PATTERN = /^sha256:[0-9a-f]{64}$/
export const REFERENCE_ID_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/
export const MACHINE_CODE_PATTERN = /^[A-Z][A-Z0-9_]{0,63}$/
export const ISO_INSTANT_PATTERN = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/
export const LOCALE_TAG_PATTERN = /^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*$/

/**
 * The global contract envelope version, mirroring the server's `ContractVersions.CURRENT`.
 * It is deliberately not the Proposal wire version: a Proposal carries its own version, see
 * `PROPOSAL_SCHEMA_VERSION` in `./proposal`, which is `1.4`.
 */
export const SCHEMA_VERSION = '1.3' as const
export const PLAN_FORMAT_VERSION = '1.0' as const
export const COMPILER_VERSION = 'campaign-proposal-compiler/1.0' as const

export function isContentHash(value: unknown): value is ContentHash {
  return typeof value === 'string' && CONTENT_HASH_PATTERN.test(value)
}

export function isReferenceId(value: unknown): value is string {
  return typeof value === 'string' && REFERENCE_ID_PATTERN.test(value)
}

export function isMachineCode(value: unknown): value is MachineCode {
  return typeof value === 'string' && MACHINE_CODE_PATTERN.test(value)
}

export function isIsoInstant(value: unknown): value is string {
  return typeof value === 'string' && ISO_INSTANT_PATTERN.test(value)
}

export function isLocaleTag(value: unknown): value is string {
  return typeof value === 'string' && LOCALE_TAG_PATTERN.test(value)
}

/** Brand a value that has already been checked with {@link isContentHash}. */
export function asContentHash(value: string): ContentHash {
  if (!isContentHash(value)) {
    throw new TypeError('Not a canonical content hash.')
  }
  return value
}