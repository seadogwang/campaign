/**
 * Deterministic formatters.
 *
 * Large counts and timestamps are formatted consistently, and the exact
 * machine value is never discarded: every formatted value is accompanied by the
 * exact original in `dateTime`/`title`/accessible text.
 */

export function formatCount(value: number): string {
  if (!Number.isFinite(value)) {
    return '—'
  }
  const negative = value < 0
  const digits = Math.trunc(Math.abs(value)).toString()
  let grouped = ''
  for (let index = 0; index < digits.length; index += 1) {
    const fromEnd = digits.length - index
    grouped += digits[index]
    if (fromEnd > 1 && (fromEnd - 1) % 3 === 0) {
      grouped += ','
    }
  }
  return negative ? `-${grouped}` : grouped
}

export interface FormattedInstant {
  /** A stable, locale-independent display value. */
  readonly display: string
  /** The exact machine value, never truncated. */
  readonly exact: string
}

export function formatInstant(instant: string): FormattedInstant {
  const match = /^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:\d{2})$/.exec(instant)
  if (match === null) {
    return { display: instant, exact: instant }
  }
  const date = match[1] ?? ''
  const time = match[2] ?? ''
  const fraction = match[3]
  const zone = match[4] === 'Z' ? 'Z' : match[4] ?? ''
  const suffix = fraction === undefined ? '' : `.${fraction}`
  return { display: `${date} ${time}${suffix}${zone}`, exact: instant }
}

export function formatSeconds(seconds: number): string {
  if (seconds < 60) {
    return `${formatCount(seconds)}s`
  }
  if (seconds < 3600) {
    return `${formatCount(Math.round(seconds / 60))}min`
  }
  return `${formatCount(Math.round(seconds / 3600))}h`
}

/** A short, non-identifying reference for a long opaque identifier. */
export function shortReference(value: string, length = 12): string {
  return value.length <= length ? value : `${value.slice(0, length)}…`
}

export function hashSuffix(hash: string, length = 12): string {
  const body = hash.startsWith('sha256:') ? hash.slice('sha256:'.length) : hash
  return body.slice(0, length)
}