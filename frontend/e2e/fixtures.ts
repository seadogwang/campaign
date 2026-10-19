import { expect, test as base, type Page } from '@playwright/test'

/**
 * The shared browser fixture.
 *
 * Every test that uses this `test` is guaranteed to fail if the page logged a console error, threw, or failed
 * a request the application actually needs. Only two things are excluded, and both are the browser's own
 * behaviour rather than the application's: a request the browser aborts while navigating, and the optional
 * `/favicon.ico` probe, which the fixture document does not reference at all. Every other failure counts.
 *
 * The fixture is automatic, so a spec cannot accidentally opt out of the assertion.
 */
const IGNORED_REQUEST_FAILURES = ['net::ERR_ABORTED', 'net::ERR_ABORTED_']

function isFaviconProbe(url: string | undefined): boolean {
  return url !== undefined && url.includes('favicon')
}

export const test = base.extend<{ browserProblems: void }>({
  browserProblems: [
    async ({ page }: { page: Page }, use: () => Promise<void>) => {
      const problems: string[] = []

      page.on('console', (message) => {
        if (message.type() !== 'error') {
          return
        }
        if (isFaviconProbe(message.location()?.url)) {
          return
        }
        problems.push(`console error: ${message.text()} (${message.location()?.url ?? 'unknown'})`)
      })
      page.on('pageerror', (error) => {
        problems.push(`page error: ${error.message}`)
      })
      page.on('requestfailed', (request) => {
        const failure = request.failure()?.errorText ?? ''
        if (isFaviconProbe(request.url())) {
          return
        }
        if (IGNORED_REQUEST_FAILURES.some((ignored) => failure.startsWith(ignored))) {
          return
        }
        problems.push(`failed request: ${request.method()} ${request.url()} (${failure})`)
      })

      await use()

      expect(problems, `The page reported browser problems:\n${problems.join('\n')}`).toEqual([])
    },
    { auto: true },
  ],
})

export { expect }

/** Waits for the user-facing application to be interactive. */
export async function openWorkspace(page: Page): Promise<void> {
  await page.goto('/')
  await expect(page.getByRole('region', { name: 'Campaign conversation' })).toBeVisible()
  await expect(page.getByTestId('conversation-history-item')).toBeVisible()
  await expect(page.getByLabel('Message the Campaign assistant')).toBeEnabled()
}

/**
 * Opens the engineering workbench.
 *
 * The campaign canvas, the component inspector, the visual builder, the review workspace, the
 * operations workspace and the fixture controls exist only behind `?workspace=internal`, which the
 * product never links to. Every spec that drives one of them must say so explicitly.
 */
export async function openInternalWorkspace(page: Page): Promise<void> {
  await page.goto('/?workspace=internal')
  await expect(page.getByRole('note', { name: 'Environment and evidence marker' })).toBeVisible()
  await expect(page.getByText('Fixture Campaign')).toBeVisible()
}

/**
 * Submits the default Visual Builder proposal through the shared authoring action.
 *
 * The Visual Builder is the secondary surface, so the tab must be selected first.
 */
export async function submitProposal(page: Page): Promise<void> {
  await page.getByRole('tab', { name: 'Visual Builder' }).click()
  const propose = page.getByRole('button', { name: 'Propose to server' })
  await expect(propose).toBeEnabled()
  await propose.click()
}

/** Sends one natural-language turn on the user-facing conversation surface. */
export async function sendTurn(page: Page, message: string): Promise<void> {
  const composer = page.getByLabel('Message the Campaign assistant')
  await composer.fill(message)
  await page.getByTestId('conversation-send').click()
}

/** Sends one turn on the user-facing surface and waits for the projection to advance. */
export async function sendTurnAndWait(page: Page, message: string, turns: number): Promise<void> {
  await sendTurn(page, message)
  await expect(page.getByTestId('conversation-turn')).toHaveCount(turns)
}

/** Sends one turn on the engineering workbench, which has its own composer action. */
export async function sendWorkbenchTurn(page: Page, message: string): Promise<void> {
  const composer = page.getByLabel('Message the Campaign assistant')
  await composer.fill(message)
  await page.getByRole('button', { name: 'Send turn' }).click()
}

/** The workbench counterpart of {@link sendTurnAndWait}. */
export async function sendWorkbenchTurnAndWait(
  page: Page,
  message: string,
  turns: number,
): Promise<void> {
  await sendWorkbenchTurn(page, message)
  await expect(page.getByTestId('conversation-turn')).toHaveCount(turns)
}

/** Submits the default proposal and waits for the resulting Plan on the Review surface. */
export async function submitProposalAndReview(page: Page): Promise<void> {
  await submitProposal(page)
  await openReview(page)
  await expect(page.getByTestId('plan-state')).toContainText('Plan ')
}

/** Moves to the Review surface and waits for it to render. */
export async function openReview(page: Page): Promise<void> {
  await page.getByRole('button', { name: /^Review/ }).click()
  await expect(page.getByRole('region', { name: 'Editor and server state' })).toBeVisible()
}

/** Moves to the Operations surface and waits for the execution list. */
export async function openOperations(page: Page): Promise<void> {
  await page.getByRole('button', { name: /^Operations/ }).click()
  await expect(page.getByRole('region', { name: 'Executions' })).toBeVisible()
}
