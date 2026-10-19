import { expect, openWorkspace, sendTurn, sendTurnAndWait, test } from './fixtures'

/**
 * The real-browser end-to-end suite for the user-facing product.
 *
 * It drives the normal application URL in an installed Microsoft Edge against a real Vite server:
 * exactly one Campaign conversation in two columns, and none of the engineering surfaces.
 */
test.describe('the user-facing Campaign conversation', () => {
  test('renders only the two-column conversation on the normal URL', async ({ page }) => {
    await openWorkspace(page)

    await expect(page.getByRole('complementary', { name: 'Campaign conversations' })).toBeVisible()
    await expect(page.getByRole('heading', { level: 1, name: 'Campaign Agent' })).toBeVisible()
    await expect(page.getByTestId('new-configuration')).toBeVisible()
    await expect(page.getByRole('navigation', { name: 'Configuration history' })).toBeVisible()
    await expect(page.getByRole('region', { name: 'Campaign conversation' })).toBeVisible()

    // None of the engineering surfaces is reachable from the product URL.
    await expect(page.getByRole('navigation', { name: 'Workspace sections' })).toHaveCount(0)
    await expect(page.getByTestId('canvas-card')).toHaveCount(0)
    await expect(page.getByTestId('component-inspector')).toHaveCount(0)
    await expect(page.getByRole('tab')).toHaveCount(0)
    await expect(page.getByLabel('Scenario')).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Propose to server' })).toHaveCount(0)

    // The environment marker keeps stating the truth in the product.
    const marker = page.getByRole('note', { name: 'Environment and evidence marker' })
    await expect(marker).toContainText('SIMULATED')
    await expect(marker).toContainText('not production evidence')
  })

  test('opens on a general empty state and sends nothing on the user behalf', async ({ page }) => {
    await openWorkspace(page)

    await expect(page.getByTestId('conversation-transcript')).toContainText(
      'Tell me the campaign you want to build',
    )
    await expect(page.getByTestId('conversation-turn')).toHaveCount(0)
    await expect(page.getByTestId('conversation-title')).toHaveText('New configuration')
    await expect(page.getByTestId('conversation-status')).toHaveText('Waiting for your idea')
    await expect(page.getByTestId('conversation-send')).toBeDisabled()

    // The stored conversation is listed by the server and can be reopened.
    await page.getByTestId('conversation-history-item').click()
    await expect(page.getByTestId('conversation-history-current')).toHaveAttribute('aria-current', 'page')
  })

  test('builds an audience result and the Campaign progress from server facts', async ({ page }) => {
    await openWorkspace(page)

    await sendTurnAndWait(page, 'who are our best members', 2)
    await expect(page.getByTestId('audience-card')).toBeVisible()
    await expect(page.getByTestId('audience-count')).toContainText('1,284 members match')
    await expect(page.getByTestId('audience-as-of')).toContainText('2026-10-08')
    await expect(page.getByTestId('audience-watermark')).toContainText('2026-10-08')
    await expect(page.getByTestId('conversation-goal')).toContainText('Audience selection')
    await expect(page.getByTestId('conversation-status')).toContainText('Audience ready')
    await expect(page.getByTestId('progress-summary')).toContainText('of 5 parts complete.')
    await expect(page.getByTestId('audience-follow-up')).toContainText('promotion')

    // The engineering vocabulary of the same state is not rendered in the product.
    await expect(page.getByText('AUDIENCE_READY')).toHaveCount(0)
    await expect(page.getByTestId('campaign-progress')).not.toContainText('sha256:')
  })

  test('answers a clarification and continues the same conversation', async ({ page }) => {
    await openWorkspace(page)

    await sendTurnAndWait(page, 'please clarify which one to use', 2)
    await expect(page.getByTestId('clarification-card')).toBeVisible()
    // The free composer is blocked while the server waits for the answer.
    await expect(page.getByTestId('conversation-composer-reason')).toContainText(
      'Answer the question above to continue.',
    )

    await page.getByRole('button', { name: 'Members active in the last 30 days' }).click()
    await expect(page.getByTestId('conversation-turn')).toHaveCount(4)
    await expect(page.getByTestId('clarification-card')).toHaveCount(0)
  })

  test('treats a configured Campaign as complete and offers only the real next action', async ({
    page,
  }) => {
    await openWorkspace(page)

    await sendTurnAndWait(page, 'build a welcome campaign', 2)
    await expect(page.getByTestId('campaign-progress')).toBeVisible()
    await expect(page.getByTestId('progress-summary')).toContainText('2 of 5 parts complete.')
    // Assistant prose alone never creates a Plan.
    await expect(page.getByTestId('create-plan')).toHaveCount(0)

    await sendTurnAndWait(page, 'finish the campaign', 4)
    await expect(page.getByTestId('proposal-ready')).toBeVisible()
    await expect(page.getByTestId('conversation-status')).toContainText('Campaign configured')
    await expect(page.getByTestId('proposal-summary')).toContainText('configured part(s).')

    await page.getByTestId('create-plan').click()
    await expect(page.getByTestId('plan-ready')).toContainText('The server created the Campaign plan.')
    // The product never claims the Campaign was published or started.
    await expect(page.getByTestId('plan-ready')).toContainText(
      'Creating the plan does not publish the Campaign and does not start it running.',
    )
  })

  test('refuses a completion the server has not accepted and keeps the open areas', async ({ page }) => {
    await openWorkspace(page)

    await sendTurnAndWait(page, 'build a welcome campaign', 2)
    await sendTurnAndWait(page, 'add a promotion for members', 4)
    await expect(page.getByTestId('progress-open-item')).toHaveCount(3)
    await expect(page.getByTestId('progress-open')).toContainText('Waiting on another part')

    await sendTurn(page, 'finish the campaign')
    await expect(page.getByTestId('conversation-refusal')).toContainText('Your conversation is unchanged')
    await expect(page.getByTestId('proposal-ready')).toHaveCount(0)
    // A refused turn adds nothing to the host transcript, so the conversation is exactly as long
    // as it was before the attempt.
    await expect(page.getByTestId('conversation-turn')).toHaveCount(4)
  })

  test('stays usable on a narrow viewport without horizontal scrolling', async ({ page }) => {
    await page.setViewportSize({ width: 420, height: 900 })
    await openWorkspace(page)
    await sendTurnAndWait(page, 'build a welcome campaign', 2)

    await expect(page.getByTestId('new-configuration')).toBeVisible()
    await expect(page.getByRole('navigation', { name: 'Configuration history' })).toBeVisible()
    await expect(page.getByLabel('Message the Campaign assistant')).toBeVisible()
    await expect(page.getByTestId('conversation-turn')).toHaveCount(2)

    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    )
    expect(overflow).toBeLessThanOrEqual(1)
  })

  test('starts a new Campaign from the explicit action', async ({ page }) => {
    await openWorkspace(page)
    await sendTurnAndWait(page, 'build a welcome campaign', 2)

    await page.getByTestId('new-configuration').click()
    await expect(page.getByTestId('conversation-turn')).toHaveCount(0)
    await expect(page.getByTestId('conversation-title')).toHaveText('New configuration')
    await expect(page.getByLabel('Message the Campaign assistant')).toHaveValue('')
  })

  test('does not send the Enter an IME composition is using to choose a candidate', async ({
    page,
  }) => {
    await openWorkspace(page)
    const composer = page.getByLabel('Message the Campaign assistant')
    await composer.fill('members active last month')

    // The real sequence a Chinese IME produces while the candidate window is open. The keydown
    // the composition owns is dispatched on the element, so it reaches the component exactly as
    // a real one would; only the composition flag marks it as belonging to the IME.
    await composer.evaluate((element) => {
      element.dispatchEvent(new CompositionEvent('compositionstart', { bubbles: true }))
      element.dispatchEvent(
        new KeyboardEvent('keydown', {
          key: 'Enter',
          code: 'Enter',
          bubbles: true,
          cancelable: true,
          isComposing: true,
        }),
      )
      element.dispatchEvent(new CompositionEvent('compositionend', { bubbles: true }))
    })

    await expect(page.getByTestId('conversation-turn')).toHaveCount(0)
    await expect(composer).toHaveValue('members active last month')
    // Nothing was sent, so the campaign is still waiting for the user's own words.
    await expect(page.getByTestId('conversation-status')).toHaveText('Waiting for your idea')

    // A second engine that reports the composition window but omits the flag on the key event:
    // the window alone has to hold the Enter back.
    await composer.evaluate((element) => {
      element.dispatchEvent(new CompositionEvent('compositionstart', { bubbles: true }))
      element.dispatchEvent(
        new KeyboardEvent('keydown', {
          key: 'Enter',
          code: 'Enter',
          bubbles: true,
          cancelable: true,
        }),
      )
    })
    await expect(page.getByTestId('conversation-turn')).toHaveCount(0)
    await expect(composer).toHaveValue('members active last month')
    await composer.evaluate((element) => {
      element.dispatchEvent(new CompositionEvent('compositionend', { bubbles: true }))
    })

    // The Enter after the composition has settled is a real send.
    await composer.press('Enter')
    await expect(page.getByTestId('conversation-turn')).toHaveCount(2)
  })

  test('puts the server question above the result cards in the conversation flow', async ({
    page,
  }) => {
    await openWorkspace(page)
    await sendTurnAndWait(page, 'please clarify which one to use', 2)

    const question = page.getByTestId('clarification-card')
    const progress = page.getByTestId('campaign-progress')
    await expect(question).toBeVisible()
    await expect(progress).toBeVisible()

    // The question the server is waiting on is the next thing after the transcript, so the flow
    // is not reordered and the progress summary never buries it.
    const order = await question.evaluate((element) => getComputedStyle(element).order)
    expect(order).toBe('0')
    expect(await progress.evaluate((element) => getComputedStyle(element).order)).toBe('0')

    const questionTop = await question.evaluate((element) => element.getBoundingClientRect().top)
    const progressTop = await progress.evaluate((element) => element.getBoundingClientRect().top)
    expect(questionTop).toBeLessThan(progressTop)
  })

  test('keeps the composer and its blocked reason inside a short narrow viewport', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 360, height: 640 })
    await openWorkspace(page)
    await sendTurnAndWait(page, 'please clarify which one to use', 2)

    // The reason is stated in words beside the disabled action, not only by the disabled styling.
    const reason = page.getByTestId('conversation-composer-reason')
    await expect(reason).toBeVisible()
    await expect(reason).toContainText('Answer the question above to continue.')
    await expect(page.getByTestId('conversation-send')).toBeDisabled()

    // The composer is reachable without horizontal scrolling, and it stays pinned while the
    // conversation scrolls past it.
    await expect(page.getByLabel('Message the Campaign assistant')).toBeVisible()
    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    )
    expect(overflow).toBeLessThanOrEqual(1)

    await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight))
    const composerBottom = await page
      .getByRole('form', { name: 'Message the Campaign Agent' })
      .evaluate((element) => Math.round(element.getBoundingClientRect().bottom))
    expect(composerBottom).toBeLessThanOrEqual(640)
    expect(composerBottom).toBeGreaterThan(320)
  })
})