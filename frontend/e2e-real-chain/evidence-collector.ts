import { type Page } from '@playwright/test'
import { mkdirSync, writeFileSync } from 'fs'
import { resolve } from 'path'
import type { Scenario } from './scenario-loader'

/**
 * Evidence captured from the browser for one scenario turn.
 *
 * Never records endpoint URLs, credentials, raw provider error bodies,
 * auth tokens, or secrets.
 */
export interface TurnEvidence {
  turnIndex: number
  /** Whether this turn was accepted by the server. */
  accepted: boolean
  /** The visible conversation phase after this turn. */
  phase: string | null
  /** The conversation status text. */
  status: string | null
  /** Number of conversation turns visible. */
  visibleTurnCount: number
  /** Component progress summary text. */
  progressSummary: string | null
  /** Open component todos with their status. */
  componentTodos: Array<{ component: string; status: string }>
  /** Clarification present after this turn. */
  hasClarification: boolean
  /** Clarification question codes if present (redacted of sensitive content). */
  clarificationCodes: string[]
  /** Clarification option IDs if present. */
  clarificationOptions: string[]
  /** How the clarification was answered. */
  clarificationAnswer: string | null
  /** Whether proposal-ready is visible. */
  proposalReady: boolean
  /** Whether a refusal is visible. */
  refusalVisible: boolean
}

/** Full evidence record for one scenario. */
export interface ScenarioEvidence {
  /** Scenario label like "C-01" or "SS-01". */
  scenario: string
  /** Source description text. */
  description: string
  /** The conversation ID from the host. */
  conversationId: string | null
  /** The run ID from the host. */
  runId: string | null
  /** Terminal revision. */
  revision: number | null
  /** Terminal phase. */
  phase: string | null
  /** Terminal result classification. */
  result: string
  /** All turn evidence records. */
  turns: TurnEvidence[]
  /** Screenshot path relative to evidence directory. */
  screenshotFile: string | null
  /** Duration in milliseconds. */
  durationMs: number
  /** Count of real model calls observed. */
  modelCallCount: number
  /** Whether the run was resumed from a prior completion. */
  resumed: boolean
  /** Error message if any. */
  errorMessage: string | null
  /** Timestamp of evidence collection. */
  collectedAt: string
}

/** Redacted secrets that must never appear in evidence. */
const SECRET_PATTERNS = [
  /Bearer\s+[A-Za-z0-9\-._~+/]+=*/gi,
  /api[_-]?key[=:]\s*[A-Za-z0-9\-._]+/gi,
  /password[=:]\s*\S+/gi,
  /token[=:]\s*[A-Za-z0-9\-._~+/]+=*/gi,
  /Authorization[=:]\s*\S+/gi,
  /jdbc:[^;]+/gi,
  /postgres:\/\/[^@\s]+@/gi,
]

/**
 * Redacts secrets from a text string.
 */
export function redactSecrets(text: string): string {
  let result = text
  for (const pattern of SECRET_PATTERNS) {
    result = result.replace(pattern, '[REDACTED]')
  }
  return result
}

/**
 * Collects current evidence from the browser page.
 */
export async function collectEvidence(
  page: Page,
  scenario: Scenario,
  label: string,
  turnIndex: number,
  answerInfo: string | null,
): Promise<TurnEvidence> {
  // Collect component todos from the progress card
  const componentTodos: Array<{ component: string; status: string }> = []
  const openItems = page.getByTestId('progress-open-item')
  const openCount = await openItems.count()
  for (let i = 0; i < openCount; i++) {
    const item = openItems.nth(i)
    const component = (await item.getAttribute('data-component')) ?? 'unknown'
    const text = await item.textContent()
    const status = text?.includes('Not started') ? 'NOT_STARTED'
      : text?.includes('Partly') ? 'IN_PROGRESS'
      : text?.includes('Waiting for') ? 'NEEDS_INPUT'
      : text?.includes('checking') ? 'VALIDATING'
      : text?.includes('Waiting on') ? 'BLOCKED'
      : 'UNKNOWN'
    componentTodos.push({ component, status })
  }

  // Also collect complete components from the progress summary
  const progressSummary = await page.getByTestId('progress-summary').textContent().catch(() => null)

  // Collect clarification info
  const hasClarification = await page.getByTestId('clarification-card').isVisible().catch(() => false)
  const clarificationCodes: string[] = []
  const clarificationOptions: string[] = []

  if (hasClarification) {
    const options = page.getByTestId('clarification-option')
    const optionCount = await options.count()
    for (let i = 0; i < optionCount; i++) {
      const text = await options.nth(i).textContent().catch(() => null)
      if (text) {
        clarificationOptions.push(text.trim())
      }
    }
    // Collect component-issue codes
    const issues = page.getByTestId('clarification-component-issue')
    const issueCount = await issues.count()
    for (let i = 0; i < issueCount; i++) {
      const component = (await issues.nth(i).getAttribute('data-component')) ?? ''
      const prompt = await issues.nth(i).textContent().catch(() => '')
      const code = `${component}:${prompt?.slice(0, 30)}`
      clarificationCodes.push(code)
    }
  }

  return {
    turnIndex,
    accepted: !(await page.getByTestId('conversation-refusal').isVisible().catch(() => false)),
    phase: await page.getByTestId('conversation-status').textContent().catch(() => null),
    status: await page.getByTestId('conversation-status').textContent().catch(() => null),
    visibleTurnCount: await page.getByTestId('conversation-turn').count().catch(() => 0),
    progressSummary: redactSecrets(progressSummary?.trim() ?? null) as string | null,
    componentTodos,
    hasClarification,
    clarificationCodes,
    clarificationOptions,
    clarificationAnswer: answerInfo,
    proposalReady: await page.getByTestId('proposal-ready').isVisible().catch(() => false),
    refusalVisible: await page.getByTestId('conversation-refusal').isVisible().catch(() => false),
  }
}

/**
 * Takes a screenshot and saves it to the evidence directory.
 */
export async function captureScreenshot(
  page: Page,
  evidenceDir: string,
  scenarioLabel: string,
): Promise<string> {
  mkdirSync(resolve(evidenceDir, 'screenshots'), { recursive: true })
  const filename = `${scenarioLabel}-${Date.now()}.png`
  const filepath = resolve(evidenceDir, 'screenshots', filename)
  await page.screenshot({ path: filepath, fullPage: true })
  return `screenshots/${filename}`
}

/**
 * Appends a result line to the JSONL evidence file atomically.
 *
 * We write a single line and flush/sync so interruption does not destroy prior evidence.
 */
export function appendResult(evidenceDir: string, evidence: ScenarioEvidence): void {
  const filepath = resolve(evidenceDir, 'results.jsonl')
  const line = JSON.stringify(evidence) + '\n'
  writeFileSync(filepath, line, { flag: 'a' })
}