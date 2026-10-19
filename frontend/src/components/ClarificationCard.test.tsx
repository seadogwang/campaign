/**
 * Component tests for ClarificationCard multi-component mode.
 *
 * Deterministic, invariant-only: no real scenario, no acceptance corpus phrases.
 * Focused on proving Segment and Journey get distinct answers and one component's
 * text never leaks into the other's submitted answer.
 */

import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type {
  CampaignClarificationAnswer,
  ComponentIssue,
  ConversationClarification,
} from '../contracts/conversation'
import { ClarificationCard } from './ClarificationCard'

function singleClarification(overrides?: Partial<ConversationClarification>): ConversationClarification {
  return {
    skillId: 'segment.rule-based',
    code: 'CAMPAIGN_AGENT_NEEDS_INPUT',
    prompt: 'What kind of Campaign do you need?',
    questionCodes: ['GENERAL_INPUT_REQUIRED'],
    options: [],
    freeTextAllowed: true,
    ...overrides,
  }
}

function segmentIssue(overrides?: Partial<ComponentIssue>): ComponentIssue {
  return {
    component: 'SEGMENT',
    skillId: 'segment.rule-based',
    code: 'SEGMENT_NEEDS_INPUT',
    prompt: 'Please clarify the audience description.',
    questionCodes: ['SEGMENT_AUDIENCE_REPHRASE_REQUIRED'],
    options: [],
    freeTextAllowed: true,
    ...overrides,
  }
}

function journeyIssue(overrides?: Partial<ComponentIssue>): ComponentIssue {
  return {
    component: 'JOURNEY',
    skillId: 'journey.flow',
    code: 'JOURNEY_NEEDS_INPUT',
    prompt: 'Please describe the user journey.',
    questionCodes: ['JOURNEY_INPUT_REQUIRED'],
    options: [],
    freeTextAllowed: true,
    ...overrides,
  }
}

describe('ClarificationCard single-question mode (preserved)', () => {
  it('renders the shared prompt and free-text input when no componentIssues', () => {
    const onAnswer = vi.fn()
    const clarification = singleClarification({ prompt: 'What kind of Campaign?' })

    render(
      <ClarificationCard
        clarification={clarification}
        componentIssues={null}
        pending={false}
        onAnswer={onAnswer}
      />,
    )

    expect(screen.getByText('What kind of Campaign?')).toBeInTheDocument()
    expect(screen.getByTestId('clarification-answer')).toBeInTheDocument()
  })

  it('renders shared options when the clarification offers them', () => {
    const onAnswer = vi.fn()
    const clarification = singleClarification({
      prompt: 'Choose one.',
      options: [{ optionId: 'opt:a', label: 'Option A' }],
      freeTextAllowed: false,
    })

    render(
      <ClarificationCard
        clarification={clarification}
        pending={false}
        onAnswer={onAnswer}
      />,
    )

    expect(screen.getByText('Option A')).toBeInTheDocument()
  })

  it('does not render multi-component submit in single-question mode', () => {
    const onAnswer = vi.fn()
    const clarification = singleClarification({ prompt: 'Any question?' })

    render(
      <ClarificationCard
        clarification={clarification}
        pending={false}
        onAnswer={onAnswer}
      />,
    )

    expect(screen.queryByTestId('clarification-submit-all')).not.toBeInTheDocument()
  })
})

describe('ClarificationCard multi-component mode', () => {
  it('renders per-issue free-text inputs for Segment and Journey under their business labels', () => {
    const onAnswer = vi.fn()
    const onSubmitAnswers = vi.fn()
    const clarification = singleClarification()

    const issues: readonly ComponentIssue[] = [
      segmentIssue(),
      journeyIssue(),
    ]

    render(
      <ClarificationCard
        clarification={clarification}
        componentIssues={issues}
        pending={false}
        onAnswer={onAnswer}
        onSubmitAnswers={onSubmitAnswers}
      />,
    )

    // Business labels are rendered
    expect(screen.getByText('Audience')).toBeInTheDocument()
    expect(screen.getByText('Journey')).toBeInTheDocument()

    // Each component gets its own free-text input
    expect(screen.getByTestId('clarification-free-text-SEGMENT')).toBeInTheDocument()
    expect(screen.getByTestId('clarification-free-text-JOURNEY')).toBeInTheDocument()

    // The submit-all button is present
    expect(screen.getByTestId('clarification-submit-all')).toBeInTheDocument()
  })

  it('submit-all is disabled when no issue has an answer', () => {
    const onAnswer = vi.fn()
    const onSubmitAnswers = vi.fn()
    const clarification = singleClarification()

    const issues: readonly ComponentIssue[] = [
      segmentIssue(),
      journeyIssue(),
    ]

    render(
      <ClarificationCard
        clarification={clarification}
        componentIssues={issues}
        pending={false}
        onAnswer={onAnswer}
        onSubmitAnswers={onSubmitAnswers}
      />,
    )

    expect(screen.getByTestId('clarification-submit-all')).toBeDisabled()
  })

  it('submits distinct answers: Segment text never appears in the Journey answer', async () => {
    const user = userEvent.setup()
    const onAnswer = vi.fn()
    const onSubmitAnswers = vi.fn()
    const clarification = singleClarification()

    const issues: readonly ComponentIssue[] = [
      segmentIssue(),
      journeyIssue(),
    ]

    render(
      <ClarificationCard
        clarification={clarification}
        componentIssues={issues}
        pending={false}
        onAnswer={onAnswer}
        onSubmitAnswers={onSubmitAnswers}
      />,
    )

    const segmentInput = screen.getByTestId('clarification-free-text-SEGMENT')
    const journeyInput = screen.getByTestId('clarification-free-text-JOURNEY')

    await user.type(segmentInput, 'gold tier members')
    await user.type(journeyInput, 'linear onboarding flow')

    await user.click(screen.getByTestId('clarification-submit-all'))

    expect(onSubmitAnswers).toHaveBeenCalledTimes(1)

    const submitted: readonly CampaignClarificationAnswer[] =
      onSubmitAnswers.mock.calls[0]![0]

    expect(submitted).toHaveLength(2)

    const segAnswer = submitted.find((a) => a.code === 'SEGMENT_AUDIENCE_REPHRASE_REQUIRED')
    const jouAnswer = submitted.find((a) => a.code === 'JOURNEY_INPUT_REQUIRED')

    expect(segAnswer).toBeDefined()
    expect(jouAnswer).toBeDefined()

    // Segment answer is its own text
    expect(segAnswer!.freeText).toBe('gold tier members')
    expect(segAnswer!.optionId).toBeNull()

    // Journey answer is its own text, never the Segment text
    expect(jouAnswer!.freeText).toBe('linear onboarding flow')
    expect(jouAnswer!.optionId).toBeNull()

    // Cross-check: Journey answer does not contain Segment text
    expect(jouAnswer!.freeText).not.toContain('gold tier')
    // Segment answer does not contain Journey text
    expect(segAnswer!.freeText).not.toContain('onboarding')
  })

  it('per-issue option selection produces answers with the correct issue codes', async () => {
    const user = userEvent.setup()
    const onAnswer = vi.fn()
    const onSubmitAnswers = vi.fn()
    const clarification = singleClarification()

    const issues: readonly ComponentIssue[] = [
      segmentIssue({
        options: [{ optionId: 'opt:geo', label: 'Geography-based' }],
        freeTextAllowed: false,
      }),
      journeyIssue({
        options: [
          { optionId: 'opt:linear', label: 'Linear' },
          { optionId: 'opt:branch', label: 'Branching' },
        ],
        freeTextAllowed: false,
      }),
    ]

    render(
      <ClarificationCard
        clarification={clarification}
        componentIssues={issues}
        pending={false}
        onAnswer={onAnswer}
        onSubmitAnswers={onSubmitAnswers}
      />,
    )

    // Select Segment option
    await user.click(screen.getByText('Geography-based'))
    // Select Journey option
    await user.click(screen.getByText('Branching'))

    await user.click(screen.getByTestId('clarification-submit-all'))

    const submitted: readonly CampaignClarificationAnswer[] =
      onSubmitAnswers.mock.calls[0]![0]

    expect(submitted).toHaveLength(2)
    expect(submitted).toEqual(
      expect.arrayContaining([
        { code: 'SEGMENT_AUDIENCE_REPHRASE_REQUIRED', optionId: 'opt:geo', freeText: null, component: 'SEGMENT' },
        { code: 'JOURNEY_INPUT_REQUIRED', optionId: 'opt:branch', freeText: null, component: 'JOURNEY' },
      ]),
    )
  })

  it('mixed option and free-text across issues produce no cross-contamination', async () => {
    const user = userEvent.setup()
    const onAnswer = vi.fn()
    const onSubmitAnswers = vi.fn()
    const clarification = singleClarification()

    const issues: readonly ComponentIssue[] = [
      segmentIssue({
        options: [{ optionId: 'opt:geo', label: 'Geography-based' }],
        freeTextAllowed: false,
      }),
      journeyIssue({
        options: [],
        freeTextAllowed: true,
      }),
    ]

    render(
      <ClarificationCard
        clarification={clarification}
        componentIssues={issues}
        pending={false}
        onAnswer={onAnswer}
        onSubmitAnswers={onSubmitAnswers}
      />,
    )

    // Segment selects an option
    await user.click(screen.getByText('Geography-based'))
    // Journey types free text
    await user.type(screen.getByTestId('clarification-free-text-JOURNEY'), 'multi-step flow')

    await user.click(screen.getByTestId('clarification-submit-all'))

    const submitted: readonly CampaignClarificationAnswer[] =
      onSubmitAnswers.mock.calls[0]![0]

    expect(submitted).toHaveLength(2)

    const segAnswer = submitted.find((a) => a.code === 'SEGMENT_AUDIENCE_REPHRASE_REQUIRED')
    const jouAnswer = submitted.find((a) => a.code === 'JOURNEY_INPUT_REQUIRED')

    // Segment answer has the option, not the Journey free text
    expect(segAnswer!.optionId).toBe('opt:geo')
    expect(segAnswer!.freeText).toBeNull()

    // Journey answer has its own free text, not the Segment option
    expect(jouAnswer!.freeText).toBe('multi-step flow')
    expect(jouAnswer!.optionId).toBeNull()
  })

  it('does not call onSubmitAnswers when no issue has an answer', async () => {
    const user = userEvent.setup()
    const onAnswer = vi.fn()
    const onSubmitAnswers = vi.fn()
    const clarification = singleClarification()

    const issues: readonly ComponentIssue[] = [
      segmentIssue(),
      journeyIssue(),
    ]

    render(
      <ClarificationCard
        clarification={clarification}
        componentIssues={issues}
        pending={false}
        onAnswer={onAnswer}
        onSubmitAnswers={onSubmitAnswers}
      />,
    )

    // Submit button is disabled, but try clicking anyway
    await user.click(screen.getByTestId('clarification-submit-all'))

    expect(onSubmitAnswers).not.toHaveBeenCalled()
  })

  it('preserves single-question behavior when componentIssues has exactly one issue', () => {
    const onAnswer = vi.fn()
    const onSubmitAnswers = vi.fn()
    const clarification = singleClarification({
      prompt: 'One component question.',
      options: [{ optionId: 'opt:a', label: 'Option A' }],
      freeTextAllowed: false,
    })

    render(
      <ClarificationCard
        clarification={clarification}
        componentIssues={[segmentIssue()]}
        pending={false}
        onAnswer={onAnswer}
        onSubmitAnswers={onSubmitAnswers}
      />,
    )

    // Single-question mode: no per-issue free-text inputs
    expect(screen.queryByTestId('clarification-free-text-SEGMENT')).not.toBeInTheDocument()
    // No multi-component submit
    expect(screen.queryByTestId('clarification-submit-all')).not.toBeInTheDocument()
    // The shared option button is rendered
    expect(screen.getByTestId('clarification-option')).toBeInTheDocument()
  })

  it('typing in the Segment input does not appear in the Journey input', async () => {
    const user = userEvent.setup()
    const onAnswer = vi.fn()
    const onSubmitAnswers = vi.fn()
    const clarification = singleClarification()

    const issues: readonly ComponentIssue[] = [
      segmentIssue(),
      journeyIssue(),
    ]

    render(
      <ClarificationCard
        clarification={clarification}
        componentIssues={issues}
        pending={false}
        onAnswer={onAnswer}
        onSubmitAnswers={onSubmitAnswers}
      />,
    )

    const segmentInput = screen.getByTestId('clarification-free-text-SEGMENT') as HTMLInputElement
    const journeyInput = screen.getByTestId('clarification-free-text-JOURNEY') as HTMLInputElement

    await user.type(segmentInput, 'segment text only')

    // Journey input remains empty -- Segment text did not leak
    expect(journeyInput.value).toBe('')
    expect(segmentInput.value).toBe('segment text only')
  })
})

describe('ClarificationCard RESOURCE_BLOCKED kind', () => {
  it('shows blocked message instead of options or free-text when single-question is RESOURCE_BLOCKED', () => {
    const onAnswer = vi.fn()
    const clarification = singleClarification({
      code: 'RESOURCE_BLOCKED',
      prompt: 'No trigger events are registered for this program.',
      options: [],
      freeTextAllowed: false,
    })

    render(
      <ClarificationCard
        clarification={clarification}
        pending={false}
        onAnswer={onAnswer}
      />,
    )

    // No option buttons rendered
    expect(screen.queryByTestId('clarification-option')).not.toBeInTheDocument()
    // No free-text input rendered
    expect(screen.queryByTestId('clarification-answer')).not.toBeInTheDocument()
    // Blocked message is shown
    expect(screen.getByText(/No available resources/)).toBeInTheDocument()
  })

  it('shows blocked message for a RESOURCE_BLOCKED issue in multi-component mode', () => {
    const onAnswer = vi.fn()
    const onSubmitAnswers = vi.fn()
    const clarification = singleClarification({
      code: 'CAMPAIGN_COMPONENT_CLARIFICATION',
      prompt: 'Several parts need attention.',
      questionCodes: ['RESOURCE_BLOCKED_EVENT', 'JOURNEY_INPUT_REQUIRED'],
      options: [],
      freeTextAllowed: true,
    })

    const issues: readonly ComponentIssue[] = [
      {
        component: 'COMMUNICATION',
        skillId: 'message.template',
        code: 'RESOURCE_BLOCKED',
        prompt: 'No communication channels are registered.',
        questionCodes: ['RESOURCE_BLOCKED_EVENT'],
        options: [],
        freeTextAllowed: false,
      },
      journeyIssue(),
    ]

    render(
      <ClarificationCard
        clarification={clarification}
        componentIssues={issues}
        pending={false}
        onAnswer={onAnswer}
        onSubmitAnswers={onSubmitAnswers}
      />,
    )

    // Blocked message for the COMMUNICATION issue is shown
    expect(screen.getByText(/No available resources of this type for Messages/)).toBeInTheDocument()
    // Journey free-text input is still available
    expect(screen.getByTestId('clarification-free-text-JOURNEY')).toBeInTheDocument()
  })

  it('does not construct fake options for RESOURCE_BLOCKED', () => {
    const onAnswer = vi.fn()
    const clarification = singleClarification({
      code: 'RESOURCE_BLOCKED',
      prompt: 'No available resources.',
      options: [],
      freeTextAllowed: false,
    })

    render(
      <ClarificationCard
        clarification={clarification}
        pending={false}
        onAnswer={onAnswer}
      />,
    )

    // No option list at all
    const optionsContainer = screen.queryAllByRole('button')
    // Only buttons should be zero — no fake options constructed
    expect(optionsContainer.length).toBe(0)
  })
})

describe('ClarificationCard code-based submission guarantee', () => {
  it('submits optionId as the backend code, not the label', async () => {
    const user = userEvent.setup()
    const onAnswer = vi.fn()
    const clarification = singleClarification({
      code: 'COMPONENT_DECISION',
      prompt: 'Is a trigger event needed?',
      options: [
        { optionId: 'REQUIRED', label: '需要' },
        { optionId: 'NOT_REQUIRED', label: '不需要' },
      ],
      freeTextAllowed: false,
    })

    render(
      <ClarificationCard
        clarification={clarification}
        pending={false}
        onAnswer={onAnswer}
      />,
    )

    // Click on the label "需要"
    await user.click(screen.getByText('需要'))
    // onAnswer is called with optionId 'REQUIRED', not the label '需要'
    expect(onAnswer).toHaveBeenCalledWith('REQUIRED', null)
  })

  it('submits resource code as optionId in RESOURCE_SELECTION, not the label', async () => {
    const user = userEvent.setup()
    const onAnswer = vi.fn()
    const clarification = singleClarification({
      code: 'RESOURCE_SELECTION',
      prompt: 'Which channel should be used?',
      options: [
        { optionId: 'SMS', label: '短信' },
        { optionId: 'EMAIL', label: '邮件' },
      ],
      freeTextAllowed: false,
    })

    render(
      <ClarificationCard
        clarification={clarification}
        pending={false}
        onAnswer={onAnswer}
      />,
    )

    await user.click(screen.getByText('邮件'))
    expect(onAnswer).toHaveBeenCalledWith('EMAIL', null)
  })
})