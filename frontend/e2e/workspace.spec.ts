import {
  expect,
  openInternalWorkspace,
  openOperations,
  openReview,
  sendWorkbenchTurn,
  sendWorkbenchTurnAndWait,
  submitProposal,
  submitProposalAndReview,
  test,
} from './fixtures'

/**
 * The T26 real-browser end-to-end suite for the engineering workbench.
 *
 * It drives the accepted internal surfaces in an installed Microsoft Edge against a real Vite server.
 * The workbench, the review workspace and the operations workspace live behind `?workspace=internal`,
 * so every test here asks for that mode explicitly; the user-facing product is covered by
 * `conversation.spec.ts`. Selectors are accessibility roles and names with deterministic assertions
 * only — no arbitrary sleeps, no CSS-class coupling and no retry loops.
 */
test.describe('campaign builder workspace', () => {
  test('keeps the permanent SIMULATED marker in default fixture mode', async ({ page }) => {
    await openInternalWorkspace(page)

    const marker = page.getByRole('note', { name: 'Environment and evidence marker' })
    await expect(marker).toHaveAttribute('data-simulated', 'true')
    await expect(marker).toHaveAttribute('data-mode', 'FIXTURE')
    await expect(marker.getByText('SIMULATED', { exact: true })).toBeVisible()
    await expect(marker).toContainText('not production evidence')
    await expect(marker).toContainText('cannot satisfy LOCAL_READY, AI_READY or LIVE_READY')
    await expect(marker.getByText('false', { exact: true }).first()).toBeVisible()
  })

  test('navigates between the authoring, review and operations surfaces', async ({ page }) => {
    await openInternalWorkspace(page)

    await expect(page.getByRole('region', { name: 'Authoring' })).toBeVisible()
    await expect(page.getByRole('button', { name: /^Authoring/ })).toHaveAttribute('aria-current', 'page')

    await openReview(page)
    await expect(page.getByRole('region', { name: 'Authoring' })).toHaveCount(0)
    await expect(page.getByRole('button', { name: /^Review/ })).toHaveAttribute('aria-current', 'page')

    await openOperations(page)
    await expect(page.getByRole('region', { name: 'Executions' })).toBeVisible()
    await expect(page.getByRole('button', { name: /^Operations/ })).toHaveAttribute('aria-current', 'page')

    await page.getByRole('button', { name: /^Authoring/ }).click()
    await expect(page.getByRole('region', { name: 'Authoring' })).toBeVisible()
  })

  test('a conversation turn never saves anything implicitly', async ({ page }) => {
    await openInternalWorkspace(page)

    await expect(page.getByRole('tab', { name: 'AI Conversation' })).toHaveAttribute(
      'aria-selected',
      'true',
    )
    await sendWorkbenchTurnAndWait(page, 'build a welcome campaign for our gold tier shoppers', 2)
    await expect(page.getByTestId('conversation-simulated')).toContainText('SIMULATED')
    await expect(page.getByTestId('conversation-goal')).toContainText('Current goal: Campaign')
    await expect(page.getByTestId('conversation-session')).toContainText('READY_TO_FINALIZE')

    await openReview(page)

    await expect(page.getByTestId('plan-state')).toContainText('No Plan has been returned yet.')
    // The conversation is interaction state: the saved Draft is still the original revision and no Apply
    // result exists.
    await expect(page.getByTestId('draft-state')).toContainText('revision 2')
    await expect(page.getByTestId('apply-result')).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Apply Plan' })).toBeDisabled()
  })

  test('builds a Campaign conversationally and creates the Plan only from the host Proposal', async ({ page }) => {
    await openInternalWorkspace(page)

    await sendWorkbenchTurnAndWait(page, 'who are our best members', 2)
    await expect(page.getByTestId('audience-card')).toBeVisible()
    await expect(page.getByTestId('audience-count')).toContainText('1,284')
    await expect(page.getByTestId('audience-follow-up')).toContainText('promotion')
    // Assistant prose alone can never create a Plan.
    await expect(page.getByTestId('create-plan')).toHaveCount(0)

    await sendWorkbenchTurnAndWait(page, 'add a welcome message', 4)
    await expect(page.getByTestId('conversation-session')).toContainText('READY_TO_FINALIZE')

    await sendWorkbenchTurnAndWait(page, 'finish the campaign', 6)
    await expect(page.getByTestId('proposal-ready')).toBeVisible()

    await page.getByTestId('create-plan').click()
    await openReview(page)
    await expect(page.getByTestId('plan-state')).toContainText('Plan ')
    await expect(page.getByRole('button', { name: 'Apply Plan' })).toBeEnabled()
  })

  test('selects a canvas component, prefills a conversational change and finishes explicitly', async ({
    page,
  }) => {
    await openInternalWorkspace(page)
    await sendWorkbenchTurnAndWait(page, 'build a welcome campaign for our gold tier shoppers', 2)

    // Exactly the six closed Skills, in canonical order, with user-facing labels.
    const cards = page.getByTestId('canvas-card')
    await expect(cards).toHaveCount(6)
    await expect(cards.nth(0)).toContainText('Audience')
    await expect(cards.nth(5)).toContainText('Governance')
    await expect(cards.nth(0)).toContainText('Accepted component exists.')
    await expect(cards.nth(2)).toContainText('No accepted component yet.')

    // Selecting the Message card opens its inspector with bounded safe details only.
    await cards.nth(1).click()
    await expect(cards.nth(1)).toHaveAttribute('aria-pressed', 'true')
    const inspector = page.getByTestId('component-inspector')
    await expect(inspector).toContainText('Message')
    await expect(inspector.getByTestId('inspector-component')).toHaveText('campaign-message')

    // The contextual action only pre-fills the composer; it never sends.
    await page.getByTestId('inspector-action').click()
    const composer = page.getByLabel('Message the Campaign assistant')
    await expect(composer).toHaveValue('Change the message of this Campaign to ')
    await expect(page.getByTestId('conversation-turn')).toHaveCount(2)

    // The user edits the starter and sends it explicitly.
    await composer.fill('change the welcome message to mention our new benefits')
    await page.getByRole('button', { name: 'Send turn' }).click()
    await expect(page.getByTestId('conversation-turn')).toHaveCount(4)
    await expect(cards.nth(1)).toContainText('Accepted component exists.')

    // Only the explicit completion card enables the Plan handoff.
    await sendWorkbenchTurnAndWait(page, 'finish the campaign', 6)
    await expect(page.getByTestId('proposal-ready')).toBeVisible()
    await page.getByTestId('create-plan').click()
    await openReview(page)
    await expect(page.getByTestId('plan-state')).toContainText('Plan ')
  })

  test('adds a promotion, observes the Governance dependency, adds Governance and finishes', async ({
    page,
  }) => {
    await openInternalWorkspace(page)
    await sendWorkbenchTurnAndWait(page, 'build a welcome campaign for our gold tier shoppers', 2)

    // The Promotion stages, and its Governance dependency becomes a visible, real blocker.
    await sendWorkbenchTurnAndWait(page, 'add a promotion for members', 4)
    const cards = page.getByTestId('canvas-card')
    await expect(cards.nth(2)).toContainText('Accepted component exists.')
    await expect(page.getByTestId('completion-blockers')).toContainText(
      'CAMPAIGN_ASSEMBLY_MISSING_GOVERNANCE_LIMIT',
    )

    // Finishing is refused while the real dependency remains open; a refused turn advances no revision.
    await sendWorkbenchTurn(page, 'finish the campaign')
    await expect(page.getByTestId('conversation-refusal')).toContainText(
      'CAMPAIGN_AGENT_FINALIZE_NOT_READY',
    )
    await expect(page.getByTestId('proposal-ready')).toHaveCount(0)
    await expect(page.getByTestId('conversation-turn')).toHaveCount(4)

    // Staging the Governance component clears the blocker honestly.
    await sendWorkbenchTurnAndWait(page, 'add governance limits for this campaign', 6)
    await expect(cards.nth(5)).toContainText('Accepted component exists.')
    await expect(cards.nth(4)).toContainText('No accepted component yet.')
    await expect(page.getByTestId('completion-blockers')).toHaveCount(0)

    // Only the explicit completion card enables the Plan handoff, and only after the real dependency clears.
    await sendWorkbenchTurnAndWait(page, 'finish the campaign', 8)
    await expect(page.getByTestId('proposal-ready')).toBeVisible()
    await expect(page.getByTestId('proposal-summary')).toContainText('4 operation(s)')

    await page.getByTestId('create-plan').click()
    await openReview(page)
    await expect(page.getByTestId('plan-state')).toContainText('Plan ')
    await expect(page.getByRole('button', { name: 'Apply Plan' })).toBeEnabled()
  })

  test('requests a Journey, observes READY with real Flow components and creates the Plan explicitly', async ({
    page,
  }) => {
    await openInternalWorkspace(page)
    await sendWorkbenchTurnAndWait(page, 'build a welcome campaign for our gold tier shoppers', 2)

    const cards = page.getByTestId('canvas-card')
    await expect(cards.nth(3)).toContainText('Journey')
    await expect(cards.nth(3)).toContainText('No accepted component yet.')

    // A clear Journey request stages the canonical minimal bundle instead of reporting an unavailable Skill.
    await sendWorkbenchTurnAndWait(page, 'add a journey with a start and an end', 4)
    await expect(cards.nth(3)).toContainText('Ready')
    await expect(cards.nth(3)).toContainText('Accepted components: 2.')

    await cards.nth(3).click()
    const inspector = page.getByTestId('component-inspector')
    await expect(inspector.getByTestId('inspector-journey-nodes')).toHaveText('2')
    await expect(inspector.getByTestId('inspector-journey-edges')).toHaveText('1')
    await expect(inspector.getByTestId('inspector-journey-entry')).toHaveText('journey-start')
    await expect(inspector.getByTestId('inspector-journey-terminals')).toHaveText('1')

    // The multi-component Skill exposes its real Flow node components, never a capability of its own name.
    const components = inspector.getByTestId('inspector-components')
    await expect(components).toContainText('flow.start')
    await expect(components).toContainText('flow.end')
    await expect(components).not.toContainText('journey.flow')

    // Integrity hashes stay collapsed inside the technical details.
    await expect(inspector.getByTestId('inspector-journey')).not.toContainText('sha256:')
    await expect(inspector.getByTestId('inspector-journey-topology-hash')).toContainText('sha256:')

    // The contextual action only pre-fills the composer; it never sends or compiles.
    await page.getByTestId('inspector-action').click()
    await expect(page.getByLabel('Message the Campaign assistant'))
      .toHaveValue('Change the journey of this Campaign to ')
    await expect(page.getByTestId('conversation-turn')).toHaveCount(4)

    await sendWorkbenchTurnAndWait(page, 'finish the campaign', 6)
    await expect(page.getByTestId('proposal-ready')).toBeVisible()
    await page.getByTestId('create-plan').click()
    await openReview(page)
    await expect(page.getByTestId('plan-state')).toContainText('Plan ')
    await expect(page.getByRole('button', { name: 'Apply Plan' })).toBeEnabled()
  })

  test('keeps the builder usable on a narrow viewport without horizontal scrolling', async ({ page }) => {
    await page.setViewportSize({ width: 480, height: 900 })
    await openInternalWorkspace(page)
    await sendWorkbenchTurnAndWait(page, 'build a welcome campaign', 2)

    await expect(page.getByTestId('canvas-card')).toHaveCount(6)
    await expect(page.getByLabel('Message the Campaign assistant')).toBeVisible()

    // Selecting a card on a narrow screen still opens the inspector and its action.
    await page.getByTestId('canvas-card').nth(1).click()
    const inspector = page.getByTestId('component-inspector')
    await expect(inspector).toContainText('Message')
    await expect(page.getByTestId('inspector-action')).toBeVisible()

    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - document.documentElement.clientWidth,
    )
    expect(overflow).toBeLessThanOrEqual(1)
  })

  test('applies only through the explicit save action', async ({ page }) => {
    await openInternalWorkspace(page)
    await submitProposalAndReview(page)

    const apply = page.getByRole('button', { name: 'Apply Plan' })
    await expect(apply).toBeEnabled()
    await expect(page.getByTestId('apply-result')).toHaveCount(0)
    await apply.click()
    await expect(page.getByTestId('apply-result')).toContainText('Saved Draft')
  })

  test('refuses authoring when the fixture denies campaign.edit', async ({ page }) => {
    await openInternalWorkspace(page)

    await page.getByLabel('Scenario').selectOption('PERMISSION_DENIED')

    await expect(page.getByLabel('Message the Campaign assistant')).toBeDisabled()
    await expect(page.getByRole('button', { name: 'Send turn' })).toBeDisabled()
    await page.getByRole('tab', { name: 'Visual Builder' }).click()
    await expect(page.getByRole('button', { name: 'Propose to server' })).toBeDisabled()
    await expect(page.getByText('Requires the campaign.edit permission.')).toBeVisible()

    await openReview(page)
    await expect(page.getByRole('button', { name: 'Apply Plan' })).toBeDisabled()
    await expect(page.getByRole('button', { name: 'Prepare publication' })).toBeDisabled()
  })

  test('surfaces a bounded stale-revision failure and keeps the edits', async ({ page }) => {
    await openInternalWorkspace(page)

    await page.getByLabel('Scenario').selectOption('STALE')
    await page.getByRole('tab', { name: 'Visual Builder' }).click()
    await expect(page.getByRole('button', { name: 'Propose to server' })).toBeEnabled()
    await page.getByRole('button', { name: 'Propose to server' }).click()

    const notice = page.getByTestId('notice-banner')
    await expect(notice).toBeVisible()
    await expect(notice).toContainText('GATEWAY_STALE_REVISION')
    await expect(notice).toContainText('Your unsaved edits were kept.')
    await expect(notice).not.toContainText('Exception')
    await expect(notice).not.toContainText('stack')
  })

  test('publishes only after an explicit confirmation', async ({ page }) => {
    await openInternalWorkspace(page)
    await submitProposalAndReview(page)
    await page.getByRole('button', { name: 'Apply Plan' }).click()
    await expect(page.getByTestId('apply-result')).toBeVisible()

    await page.getByRole('button', { name: 'Prepare publication' }).click()
    await expect(page.getByTestId('preparation-hash')).toBeVisible()

    // The confirmation dialog is mandatory: cancelling publishes nothing.
    await page.getByRole('button', { name: /Confirm publish/ }).click()
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()
    await expect(dialog).toHaveAttribute('aria-modal', 'true')
    await expect(dialog).toContainText('submits the exact preparation')
    await dialog.getByRole('button', { name: 'Cancel' }).click()
    await expect(page.getByRole('dialog')).toHaveCount(0)
    await expect(page.getByTestId('publish-result')).toHaveCount(0)

    await page.getByRole('button', { name: /Confirm publish/ }).click()
    await page.getByRole('dialog').getByRole('button', { name: 'Publish now' }).click()
    await expect(page.getByTestId('publish-result')).toContainText('Published')
    await expect(page.getByTestId('confirm-disabled-reason')).toContainText(
      'The preparation was already consumed by one successful publish.',
    )
  })

  test('presents unresolved operations states without a blind resend', async ({ page }) => {
    await openInternalWorkspace(page)
    await openOperations(page)

    await page.getByRole('button', { name: 'Inspect' }).first().click()
    await expect(page.getByRole('region', { name: 'Actions' })).toBeVisible()

    const callout = page.getByTestId('unresolved-callout')
    await expect(callout).toContainText('Unknown')
    await expect(callout).toContainText('Verifying')
    await expect(callout).toContainText('Needs review')
    await expect(page.getByTestId('operations-unresolved')).toContainText('unresolved action')

    await expect(page.getByRole('button', { name: /send again/i })).toHaveCount(0)
    await expect(page.getByRole('button', { name: /resend/i })).toHaveCount(0)
    await expect(page.getByTestId('operations-unresolved')).toBeVisible()
  })

  test('guards campaign controls behind campaign.operate', async ({ page }) => {
    await openInternalWorkspace(page)
    await openOperations(page)

    await page.getByRole('button', { name: 'Pause campaign' }).click()
    const dialog = page.getByTestId('confirm-dialog')
    await expect(dialog).toBeVisible()
    await dialog.getByRole('button', { name: 'Pause now' }).click()
    await expect(page.getByTestId('schedule-state-result')).toContainText('Schedule is now PAUSED')
  })
})
