/**
 * Strict, dependency-free decoders.
 *
 * Every decoder rejects an unknown field, a duplicate shape and a wrong
 * primitive, and reports a bounded `UiError` with a field path. Field paths are
 * structural (`$.operations[0].op`) and never contain a payload value.
 */

import { UI_ERROR_CODES, contractError } from './errors'

export type JsonPrimitive = string | number | boolean | null
export type JsonValue = JsonPrimitive | JsonValue[] | { [key: string]: JsonValue }
export type JsonObject = { [key: string]: JsonValue }

export function requireObject(value: unknown, path: string): JsonObject {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw contractError(UI_ERROR_CODES.CONTRACT_SHAPE_INVALID, path, 'expected-object')
  }
  return value as JsonObject
}

export function requireArray(value: unknown, path: string): JsonValue[] {
  if (!Array.isArray(value)) {
    throw contractError(UI_ERROR_CODES.CONTRACT_SHAPE_INVALID, path, 'expected-array')
  }
  return value
}

export function requireExactFields(
  value: JsonObject,
  allowed: readonly string[],
  path: string,
): void {
  const allowedSet = new Set(allowed)
  for (const key of Object.keys(value)) {
    if (!allowedSet.has(key)) {
      throw contractError(UI_ERROR_CODES.CONTRACT_FIELD_UNKNOWN, `${path}.${key}`, 'unknown-field')
    }
  }
}

export function field(obj: JsonObject, name: string, path: string): JsonValue {
  const value = obj[name]
  if (value === undefined) {
    throw contractError(UI_ERROR_CODES.CONTRACT_SHAPE_INVALID, `${path}.${name}`, 'missing-field')
  }
  return value
}

export function optionalField(obj: JsonObject, name: string): JsonValue | undefined {
  return obj[name]
}

export function requireString(value: unknown, path: string): string {
  if (typeof value !== 'string') {
    throw contractError(UI_ERROR_CODES.CONTRACT_SHAPE_INVALID, path, 'expected-string')
  }
  return value
}

export function requireBoolean(value: unknown, path: string): boolean {
  if (typeof value !== 'boolean') {
    throw contractError(UI_ERROR_CODES.CONTRACT_SHAPE_INVALID, path, 'expected-boolean')
  }
  return value
}

export function requireInteger(value: unknown, path: string): number {
  if (typeof value !== 'number' || !Number.isInteger(value)) {
    throw contractError(UI_ERROR_CODES.CONTRACT_SHAPE_INVALID, path, 'expected-integer')
  }
  return value
}

export function requireNonNegativeInteger(value: unknown, path: string): number {
  const parsed = requireInteger(value, path)
  if (parsed < 0) {
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, path, 'expected-non-negative')
  }
  return parsed
}

export function requirePattern(value: unknown, pattern: RegExp, path: string, reason: string): string {
  const text = requireString(value, path)
  if (!pattern.test(text)) {
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, path, reason)
  }
  return text
}

export function requireEnumValue<T extends string>(
  value: unknown,
  allowed: readonly T[],
  path: string,
): T {
  const text = requireString(value, path)
  if (!(allowed as readonly string[]).includes(text)) {
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, path, 'unsupported-enum-member')
  }
  return text as T
}

export function requireStringArray(value: unknown, path: string, maxLength: number): string[] {
  const items = requireArray(value, path)
  if (items.length > maxLength) {
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, path, 'array-too-long')
  }
  return items.map((item, index) => requireString(item, `${path}[${index}]`))
}

export function requireObjectArray(
  value: unknown,
  path: string,
  maxLength: number,
): { item: JsonObject; path: string }[] {
  const items = requireArray(value, path)
  if (items.length > maxLength) {
    throw contractError(UI_ERROR_CODES.CONTRACT_VALUE_INVALID, path, 'array-too-long')
  }
  return items.map((item, index) => ({
    item: requireObject(item, `${path}[${index}]`),
    path: `${path}[${index}]`,
  }))
}

export function optionalString(value: unknown, path: string): string | null {
  return value === undefined || value === null ? null : requireString(value, path)
}

export function optionalInteger(value: unknown, path: string): number | null {
  return value === undefined || value === null ? null : requireInteger(value, path)
}

export function optionalBoolean(value: unknown, path: string): boolean | null {
  return value === undefined || value === null ? null : requireBoolean(value, path)
}

export function optionalPattern(value: unknown, pattern: RegExp, path: string, reason: string): string | null {
  return value === undefined || value === null ? null : requirePattern(value, pattern, path, reason)
}

export function optionalEnumValue<T extends string>(
  value: unknown,
  allowed: readonly T[],
  path: string,
): T | null {
  return value === undefined || value === null ? null : requireEnumValue(value, allowed, path)
}