/**
 * One status vocabulary shared by every card.
 *
 * Every status carries a tone (colour), a shape and a glyph. Critical status is
 * therefore never conveyed by colour alone: the text label, the shape and the
 * glyph all distinguish it, and each is asserted by the accessibility tests.
 */

export type StatusTone = 'NEUTRAL' | 'GOOD' | 'WARN' | 'BAD' | 'UNKNOWN' | 'INFO'
export type StatusShape = 'CIRCLE' | 'SQUARE' | 'TRIANGLE' | 'DIAMOND' | 'HEXAGON' | 'OCTAGON'

export interface StatusPresentation {
  readonly tone: StatusTone
  readonly shape: StatusShape
  readonly glyph: string
  readonly label: string
  /** True when the status must be called out strongly as unresolved. */
  readonly unresolved: boolean
}

const PRESENTATION: Readonly<Record<string, StatusPresentation>> = {
  // Component manifest usage decisions
  REQUIRED: { tone: 'INFO', shape: 'HEXAGON', glyph: '⬡', label: 'Needed', unresolved: true },
  NOT_REQUIRED: { tone: 'NEUTRAL', shape: 'SQUARE', glyph: '■', label: 'Not needed', unresolved: false },
  UNCERTAIN: { tone: 'WARN', shape: 'DIAMOND', glyph: '◆', label: 'Pending', unresolved: true },

  // Skill / component assembly
  MISSING: { tone: 'NEUTRAL', shape: 'SQUARE', glyph: '■', label: 'Missing', unresolved: false },

  // Component TODO groups
  NOT_STARTED: { tone: 'NEUTRAL', shape: 'SQUARE', glyph: '■', label: 'Not started', unresolved: false },
  IN_PROGRESS: { tone: 'INFO', shape: 'HEXAGON', glyph: '⬡', label: 'In progress', unresolved: true },
  VALIDATING: { tone: 'UNKNOWN', shape: 'DIAMOND', glyph: '◆', label: 'Checking', unresolved: true },
  COMPLETE: { tone: 'GOOD', shape: 'CIRCLE', glyph: '✓', label: 'Complete', unresolved: false },
  BLOCKED: { tone: 'BAD', shape: 'OCTAGON', glyph: '✕', label: 'Blocked', unresolved: true },

  // Plan / preparation
  READY: { tone: 'GOOD', shape: 'CIRCLE', glyph: '✓', label: 'Ready', unresolved: false },
  NEEDS_INPUT: { tone: 'WARN', shape: 'DIAMOND', glyph: '◆', label: 'Needs input', unresolved: false },
  INVALID: { tone: 'BAD', shape: 'OCTAGON', glyph: '✕', label: 'Invalid', unresolved: false },
  STALE: { tone: 'WARN', shape: 'TRIANGLE', glyph: '▲', label: 'Stale', unresolved: false },
  APPLIED: { tone: 'GOOD', shape: 'CIRCLE', glyph: '✓', label: 'Applied', unresolved: false },
  EXPIRED: { tone: 'BAD', shape: 'TRIANGLE', glyph: '▲', label: 'Expired', unresolved: false },
  REJECTED: { tone: 'BAD', shape: 'OCTAGON', glyph: '✕', label: 'Rejected', unresolved: false },
  PREPARED: { tone: 'GOOD', shape: 'CIRCLE', glyph: '✓', label: 'Prepared', unresolved: false },
  CONSUMED: { tone: 'NEUTRAL', shape: 'SQUARE', glyph: '■', label: 'Consumed', unresolved: false },

  // Execution / participant
  CREATED: { tone: 'NEUTRAL', shape: 'SQUARE', glyph: '■', label: 'Created', unresolved: true },
  EVALUATING: { tone: 'INFO', shape: 'HEXAGON', glyph: '⬡', label: 'Evaluating', unresolved: true },
  RUNNING: { tone: 'INFO', shape: 'HEXAGON', glyph: '⬡', label: 'Running', unresolved: true },
  COMPLETED: { tone: 'GOOD', shape: 'CIRCLE', glyph: '✓', label: 'Completed', unresolved: false },
  PARTIAL_FAILED: { tone: 'WARN', shape: 'TRIANGLE', glyph: '▲', label: 'Partially failed', unresolved: true },
  FAILED: { tone: 'BAD', shape: 'OCTAGON', glyph: '✕', label: 'Failed', unresolved: false },
  WAITING: { tone: 'INFO', shape: 'DIAMOND', glyph: '◆', label: 'Waiting', unresolved: true },
  EXITED: { tone: 'NEUTRAL', shape: 'SQUARE', glyph: '■', label: 'Exited', unresolved: false },

  // Action
  RESERVED: { tone: 'NEUTRAL', shape: 'SQUARE', glyph: '■', label: 'Reserved', unresolved: true },
  IN_FLIGHT: { tone: 'INFO', shape: 'HEXAGON', glyph: '⬡', label: 'In flight', unresolved: true },
  UNKNOWN: { tone: 'UNKNOWN', shape: 'TRIANGLE', glyph: '?', label: 'Unknown', unresolved: true },
  VERIFYING: { tone: 'UNKNOWN', shape: 'DIAMOND', glyph: '◆', label: 'Verifying', unresolved: true },
  SUCCEEDED: { tone: 'GOOD', shape: 'CIRCLE', glyph: '✓', label: 'Succeeded', unresolved: false },
  DEFINITELY_FAILED: { tone: 'BAD', shape: 'OCTAGON', glyph: '✕', label: 'Definitely failed', unresolved: false },
  NEEDS_REVIEW: { tone: 'BAD', shape: 'OCTAGON', glyph: '!', label: 'Needs review', unresolved: true },

  // Readiness
  ACTIVE: { tone: 'GOOD', shape: 'CIRCLE', glyph: '✓', label: 'Active', unresolved: false },
  EXPERIMENTAL: { tone: 'WARN', shape: 'TRIANGLE', glyph: '▲', label: 'Experimental', unresolved: false },
  EVALUATION_REQUIRED: { tone: 'WARN', shape: 'DIAMOND', glyph: '◆', label: 'Evaluation required', unresolved: false },
  DEVELOPMENT_ONLY: { tone: 'WARN', shape: 'TRIANGLE', glyph: '▲', label: 'Development only', unresolved: false },
  CANDIDATE_COMPLETE: { tone: 'INFO', shape: 'HEXAGON', glyph: '⬡', label: 'Candidate complete', unresolved: false },

  // Budget / schedule
  HELD: { tone: 'INFO', shape: 'HEXAGON', glyph: '⬡', label: 'Held', unresolved: true },
  FINALIZED: { tone: 'GOOD', shape: 'CIRCLE', glyph: '✓', label: 'Finalized', unresolved: false },
  RELEASED: { tone: 'NEUTRAL', shape: 'SQUARE', glyph: '■', label: 'Released', unresolved: false },
  PAUSED: { tone: 'WARN', shape: 'TRIANGLE', glyph: '▲', label: 'Paused', unresolved: false },
}

const FALLBACK: StatusPresentation = {
  tone: 'NEUTRAL',
  shape: 'SQUARE',
  glyph: '■',
  label: 'Unknown status',
  unresolved: false,
}

export function statusPresentation(status: string): StatusPresentation {
  return PRESENTATION[status] ?? { ...FALLBACK, label: status }
}

export function isUnresolvedStatus(status: string): boolean {
  return statusPresentation(status).unresolved
}