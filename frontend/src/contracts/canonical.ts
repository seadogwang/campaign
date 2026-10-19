/**
 * Canonical JSON and content hashing.
 *
 * This mirrors the accepted server-side canonical rules closely enough to pin
 * evaluation assets and to prove authoring equivalence:
 *  - object keys are sorted by UTF-16 code unit (RFC 8785 ordering);
 *  - array order is preserved exactly;
 *  - numbers are written in plain decimal with no exponent and no negative zero;
 *  - the output is compact UTF-8 with no BOM and no insignificant whitespace.
 *
 * It is a client-side mirror only: it never produces a server artifact hash.
 */

import { CONTENT_HASH_PATTERN, type ContentHash } from './primitives'
import { UI_ERROR_CODES, contractError } from './errors'
import { sha256Hex } from './sha256'
import type { JsonValue } from './validate'

export function formatCanonicalNumber(value: number): string {
  if (!Number.isFinite(value)) {
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, '$', 'non-finite-number')
  }
  if (Object.is(value, -0)) {
    return '0'
  }
  if (Number.isInteger(value) && Math.abs(value) < 1e21) {
    return value.toFixed(0)
  }
  const plain = value.toString()
  if (!/[eE]/.test(plain)) {
    return plain
  }
  // Expand exponent notation into plain decimal so identical values have exactly one form.
  const match = /^(-?)(\d+)(?:\.(\d+))?[eE]([+-]?\d+)$/.exec(plain)
  if (match === null) {
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, '$', 'unsupported-number')
  }
  const sign = match[1] ?? ''
  const integerPart = match[2] ?? '0'
  const fractionPart = match[3] ?? ''
  const exponent = Number.parseInt(match[4] ?? '0', 10)
  const digits = `${integerPart}${fractionPart}`
  const pointIndex = integerPart.length + exponent
  let head: string
  let tail: string
  if (pointIndex <= 0) {
    head = '0'
    tail = `${'0'.repeat(-pointIndex)}${digits}`
  } else if (pointIndex >= digits.length) {
    head = `${digits}${'0'.repeat(pointIndex - digits.length)}`
    tail = ''
  } else {
    head = digits.slice(0, pointIndex)
    tail = digits.slice(pointIndex)
  }
  const trimmedTail = tail.replace(/0+$/, '')
  return trimmedTail.length === 0 ? `${sign}${head}` : `${sign}${head}.${trimmedTail}`
}

export function canonicalize(value: JsonValue): string {
  if (value === null) {
    return 'null'
  }
  if (typeof value === 'boolean') {
    return value ? 'true' : 'false'
  }
  if (typeof value === 'number') {
    return formatCanonicalNumber(value)
  }
  if (typeof value === 'string') {
    return JSON.stringify(value)
  }
  if (Array.isArray(value)) {
    return `[${value.map((item) => canonicalize(item)).join(',')}]`
  }
  const keys = Object.keys(value).sort((left, right) => {
    if (left === right) {
      return 0
    }
    return left < right ? -1 : 1
  })
  const members: string[] = []
  for (const key of keys) {
    const member = value[key]
    if (member === undefined) {
      continue
    }
    members.push(`${JSON.stringify(key)}:${canonicalize(member)}`)
  }
  return `{${members.join(',')}}`
}

export function utf8Bytes(text: string): Uint8Array {
  return new TextEncoder().encode(text)
}

export function contentHashOfText(text: string): ContentHash {
  const hash = `sha256:${sha256Hex(utf8Bytes(text))}`
  if (!CONTENT_HASH_PATTERN.test(hash)) {
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, '$', 'hash-format')
  }
  return hash as ContentHash
}

export function contentHashOfCanonical(value: JsonValue): ContentHash {
  return contentHashOfText(canonicalize(value))
}

export function contentHashOfBytes(bytes: Uint8Array): ContentHash {
  const hash = `sha256:${sha256Hex(bytes)}`
  if (!CONTENT_HASH_PATTERN.test(hash)) {
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, '$', 'hash-format')
  }
  return hash as ContentHash
}