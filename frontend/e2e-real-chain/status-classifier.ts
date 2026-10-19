/**
 * Classifies terminal states of a Campaign conversation.
 *
 * Each result is one of:
 *   PROPOSAL_READY  — proposal present, all components complete, FINALIZE accepted
 *   OUT_OF_SCOPE    — the system honestly cannot process this request
 *   BLOCKED         — system needs input the runner cannot provide
 *   NEEDS_INPUT     — clarifications remain unanswered
 *   FAILED          — system defect, contract error, timeout, or browser failure
 *   IN_PROGRESS     — conversation still building (not terminal)
 *   MAX_TURNS       — exceeded the maximum turn budget
 */

/** Terminal result types. */
export type TerminalResult =
  | 'PROPOSAL_READY'
  | 'OUT_OF_SCOPE'
  | 'BLOCKED'
  | 'NEEDS_INPUT'
  | 'FAILED'
  | 'IN_PROGRESS'
  | 'MAX_TURNS'

/** Classification evidence extracted from the browser page. */
export interface ClassificationInput {
  /** The conversation phase reported by the UI. */
  phase: string | null
  /** Whether the proposal-ready card is visible. */
  proposalReady: boolean
  /** The conversation status text. */
  statusText: string | null
  /** Number of complete components. */
  completeComponents: number
  /** Total components in scope. */
  totalComponents: number
  /** Whether clarification card is visible. */
  awaitingClarification: boolean
  /** Whether a refusal was shown (turn not accepted). */
  refusalVisible: boolean
  /** Whether a conversation error is visible. */
  errorVisible: boolean
  /** Whether an out-of-scope indicator is present. */
  outOfScopeIndicator: boolean
  /** Current turn count. */
  turnCount: number
  /** Maximum allowed turns. */
  maxTurns: number
  /** Whether FINALIZE turn has been sent. */
  finalized: boolean
}

/**
 * Classifies the current conversation state into a terminal result.
 */
export function classify(input: ClassificationInput): TerminalResult {
  // Max turns exceeded
  if (input.turnCount >= input.maxTurns) {
    return 'MAX_TURNS'
  }

  // Browser or API error
  if (input.errorVisible) {
    return 'FAILED'
  }

  // Honest out-of-scope
  if (input.outOfScopeIndicator) {
    return 'OUT_OF_SCOPE'
  }

  // Proposal ready with all components complete and finalized
  if (
    input.proposalReady &&
    input.phase === 'PROPOSAL_READY' &&
    input.completeComponents === input.totalComponents &&
    input.finalized
  ) {
    return 'PROPOSAL_READY'
  }

  // Terminal invalid — system refused
  if (input.phase === 'TERMINAL_INVALID') {
    return 'FAILED'
  }

  // Awaiting clarification but cannot answer
  if (input.awaitingClarification) {
    return 'NEEDS_INPUT'
  }

  // Refusal visible — the last turn was not accepted
  if (input.refusalVisible) {
    return 'BLOCKED'
  }

  // Still building
  return 'IN_PROGRESS'
}

/**
 * Determines if a result is a legitimate terminal (not a failure or in-progress).
 */
export function isLegitimateTerminal(result: TerminalResult): boolean {
  return result === 'PROPOSAL_READY' || result === 'OUT_OF_SCOPE' || result === 'BLOCKED'
}

/**
 * Determines if a result counts as a pass for the evaluation.
 */
export function isPass(result: TerminalResult): boolean {
  return result === 'PROPOSAL_READY' || result === 'OUT_OF_SCOPE'
}

/**
 * Returns a human-readable label for a terminal result.
 */
export function terminalLabel(result: TerminalResult): string {
  switch (result) {
    case 'PROPOSAL_READY':
      return 'Proposal ready (complete)'
    case 'OUT_OF_SCOPE':
      return 'Out of scope (honest)'
    case 'BLOCKED':
      return 'Blocked (needs real business input)'
    case 'NEEDS_INPUT':
      return 'Needs input (clarification unanswered)'
    case 'FAILED':
      return 'Failed (system defect)'
    case 'IN_PROGRESS':
      return 'In progress'
    case 'MAX_TURNS':
      return 'Max turns exceeded'
  }
}