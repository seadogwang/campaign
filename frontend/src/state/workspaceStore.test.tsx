import { describe, expect, it } from 'vitest'
import {
  buildComponentIssueAnswers,
  clarificationAnswers,
  conversationKeyOf,
  createInitialState,
  formatMutationKey,
  generateInstanceNamespace,
  owningQuestionCode,
  reducer,
  type WorkspaceState,
} from './workspaceStore'
import { UI_ERROR_CODES, UiError, fixedMessage } from '../contracts/errors'
import type { ComponentIssue } from '../contracts/conversation'
import type { ConversationClarification } from '../contracts/conversation'
import { REFERENCE_ID_PATTERN } from '../contracts/primitives'

/** One aggregate clarification as the host projects it: the option owner's code is first by contract. */
function aggregate(
  skillId: string | null,
  questionCodes: readonly string[],
  options: readonly { readonly optionId: string; readonly label: string }[],
): ConversationClarification {
  return {
    skillId,
    code: 'CAMPAIGN_COMPONENT_CLARIFICATION',
    prompt: 'More Campaign details are needed before I can continue.',
    questionCodes,
    options,
    freeTextAllowed: true,
  }
}

const STALE_ERROR = new UiError({
  code: UI_ERROR_CODES.GATEWAY_STALE_REVISION,
  message: fixedMessage(UI_ERROR_CODES.GATEWAY_STALE_REVISION),
})

describe('workspace reducer: four distinct slices', () => {
  it('keeps server resources, the last Plan and the saved Draft separate', () => {
    const initial = createInitialState()
    expect(initial.workspace.value).toBeNull()
    expect(initial.plan.value).toBeNull()
    expect(initial.dirty).toBe(false)
    expect(initial.composerText).toBe('')
    expect(initial.conversation.value).toBeNull()
    expect(initial.conversation.status).toBe('IDLE')
  })

  it('marks the editor dirty on any Visual Builder edit and clears it on discard', () => {
    let state: WorkspaceState = createInitialState()
    state = reducer(state, { type: 'SET_VISUAL', visual: createInitialState().visual })
    expect(state.dirty).toBe(true)
    state = reducer(state, { type: 'CLEAR_UNSAVED' })
    expect(state.dirty).toBe(false)
  })

  it('never marks Campaign state dirty just because the composer holds text', () => {
    let state: WorkspaceState = createInitialState()
    state = reducer(state, { type: 'SET_COMPOSER', text: 'build a welcome campaign' })
    expect(state.composerText).toBe('build a welcome campaign')
    expect(state.dirty).toBe(false)
    state = reducer(state, { type: 'CLEAR_COMPOSER' })
    expect(state.composerText).toBe('')
  })

  it('keeps the conversation slice separate from the Draft, the last Plan and the visual edits', () => {
    let state: WorkspaceState = createInitialState()
    state = reducer(state, { type: 'SET_VISUAL', visual: createInitialState().visual })
    state = reducer(state, { type: 'BEGIN', key: 'conversation', seq: 1 })
    state = reducer(state, { type: 'FAILURE', key: 'conversation', seq: 1, error: STALE_ERROR })
    expect(state.conversation.status).toBe('ERROR')
    expect(state.conversation.error?.code).toBe(UI_ERROR_CODES.GATEWAY_STALE_REVISION)
    expect(state.dirty).toBe(true)
    state = reducer(state, { type: 'RESET_CONVERSATION' })
    expect(state.conversation.status).toBe('IDLE')
    expect(state.conversation.value).toBeNull()
    expect(state.composerText).toBe('')
    // Only the conversation slice was discarded; the visual edits survive.
    expect(state.dirty).toBe(true)
  })

  it('clears the dirty flag only when the edits become the saved Draft', () => {
    let state: WorkspaceState = createInitialState()
    state = reducer(state, { type: 'SET_VISUAL', visual: createInitialState().visual })
    expect(state.dirty).toBe(true)
    state = reducer(state, { type: 'SUCCESS', key: 'plan', seq: 1, value: { marker: 'plan' } })
    expect(state.dirty).toBe(true)
    state = reducer(state, { type: 'SUCCESS', key: 'apply', seq: 2, value: { marker: 'draft' } })
    expect(state.dirty).toBe(false)
  })
})

describe('workspace reducer: stale response suppression', () => {
  it('ignores a superseded success', () => {
    let state: WorkspaceState = createInitialState()
    state = reducer(state, { type: 'BEGIN', key: 'plan', seq: 1 })
    state = reducer(state, { type: 'BEGIN', key: 'plan', seq: 2 })
    state = reducer(state, { type: 'SUCCESS', key: 'plan', seq: 1, value: { superseded: true } })
    expect(state.plan.value).toBeNull()
    expect(state.plan.status).toBe('LOADING')
    state = reducer(state, { type: 'SUCCESS', key: 'plan', seq: 2, value: { fresh: true } })
    expect(state.plan.status).toBe('READY')
  })

  it('ignores a superseded failure', () => {
    let state: WorkspaceState = createInitialState()
    state = reducer(state, { type: 'BEGIN', key: 'preview', seq: 1 })
    state = reducer(state, { type: 'BEGIN', key: 'preview', seq: 2 })
    state = reducer(state, { type: 'FAILURE', key: 'preview', seq: 1, error: STALE_ERROR })
    expect(state.preview.error).toBeNull()
  })

  it('keeps the previous value when a call fails, so local context survives', () => {
    let state: WorkspaceState = createInitialState()
    state = reducer(state, { type: 'BEGIN', key: 'workspace', seq: 1 })
    state = reducer(state, { type: 'SUCCESS', key: 'workspace', seq: 1, value: { marker: 'kept' } })
    state = reducer(state, { type: 'BEGIN', key: 'workspace', seq: 2 })
    state = reducer(state, { type: 'FAILURE', key: 'workspace', seq: 2, error: STALE_ERROR })
    expect(state.workspace.value).toEqual({ marker: 'kept' })
    expect(state.workspace.status).toBe('ERROR')
    expect(state.workspace.error?.code).toBe(UI_ERROR_CODES.GATEWAY_STALE_REVISION)
    // A failure never touches the authoring slices.
    expect(state.dirty).toBe(false)
  })

  it('returns to the initial state on an explicit reset', () => {
    let state: WorkspaceState = createInitialState()
    state = reducer(state, { type: 'SET_COMPOSER', text: 'anything' })
    state = reducer(state, { type: 'RESET', state: createInitialState() })
    expect(state.composerText).toBe('')
    expect(state.dirty).toBe(false)
  })
})

describe('the active conversation identity', () => {
  it('starts with no active conversation and records the server-issued identity', () => {
    let state: WorkspaceState = createInitialState()
    expect(state.activeConversationKey).toBeNull()

    const key = conversationKeyOf('conversation-1', 'run-1')
    expect(key).toBe('conversation-1::run-1')
    state = reducer(state, { type: 'SET_ACTIVE_CONVERSATION', key })
    expect(state.activeConversationKey).toBe(key)
    // Marking the active conversation never counts as an edit.
    expect(state.dirty).toBe(false)
  })

  it('forgets the active conversation when the conversation slice is reset', () => {
    let state: WorkspaceState = createInitialState()
    state = reducer(state, { type: 'SET_ACTIVE_CONVERSATION', key: 'conversation-1::run-1' })
    state = reducer(state, { type: 'RESET_CONVERSATION' })
    expect(state.activeConversationKey).toBeNull()
  })
})

describe('clarification answer construction: option ownership', () => {
  const SEGMENT_OPTION = { optionId: 'option:age', label: 'Age' }
  const PROMOTION_OPTION = { optionId: 'rwd-points', label: 'Points reward' }

  it('binds a Promotion option to the owning question code, never to the Segment question', () => {
    // The contract's owning code is first even though MISSING_METADATA_DEFINITION sorts before
    // PROMOTION_INPUT_REQUIRED; the old alphabetical union projected the Segment code first and made a
    // client mis-attribute the Promotion option to the Segment question.
    const pending = aggregate(
      'promotion.reference',
      ['PROMOTION_INPUT_REQUIRED', 'MISSING_METADATA_DEFINITION'],
      [PROMOTION_OPTION],
    )

    expect(owningQuestionCode(pending)).toBe('PROMOTION_INPUT_REQUIRED')
    expect(clarificationAnswers(pending, PROMOTION_OPTION.optionId, null)).toEqual([
      { code: 'PROMOTION_INPUT_REQUIRED', optionId: 'rwd-points', freeText: null },
    ])
  })

  it('binds a Segment option to the owning question code, never to the Message question', () => {
    const pending = aggregate(
      'segment.rule-based',
      ['SELECT_DEFINITION', 'MESSAGE_TEMPLATE_REQUIRED'],
      [SEGMENT_OPTION],
    )

    expect(owningQuestionCode(pending)).toBe('SELECT_DEFINITION')
    const answers = clarificationAnswers(pending, SEGMENT_OPTION.optionId, null)
    expect(answers).toEqual([{ code: 'SELECT_DEFINITION', optionId: 'option:age', freeText: null }])
    expect(answers.some((answer) => answer.code === 'MESSAGE_TEMPLATE_REQUIRED')).toBe(false)
  })

  it('answers every other outstanding question when free text answers an option-bearing aggregate', () => {
    const promotion = aggregate(
      'promotion.reference',
      ['PROMOTION_INPUT_REQUIRED', 'MISSING_METADATA_DEFINITION'],
      [PROMOTION_OPTION],
    )
    expect(clarificationAnswers(promotion, null, 'members in the gold tier')).toEqual([
      { code: 'MISSING_METADATA_DEFINITION', optionId: null, freeText: 'members in the gold tier' },
    ])

    const segment = aggregate(
      'segment.rule-based',
      ['SELECT_DEFINITION', 'MESSAGE_TEMPLATE_REQUIRED'],
      [SEGMENT_OPTION],
    )
    expect(clarificationAnswers(segment, null, 'SMS reminder')).toEqual([
      { code: 'MESSAGE_TEMPLATE_REQUIRED', optionId: null, freeText: 'SMS reminder' },
    ])
  })

  it('answers every outstanding question of a no-option aggregate instead of dropping the others', () => {
    const pending = aggregate(null, ['JOURNEY_INPUT_REQUIRED', 'MESSAGE_TEMPLATE_REQUIRED'], [])

    expect(owningQuestionCode(pending)).toBeNull()
    expect(clarificationAnswers(pending, null, 'straight through by SMS')).toEqual([
      { code: 'JOURNEY_INPUT_REQUIRED', optionId: null, freeText: 'straight through by SMS' },
      { code: 'MESSAGE_TEMPLATE_REQUIRED', optionId: null, freeText: 'straight through by SMS' },
    ])
  })

  it('carries the option and the other questions together when the user supplies both', () => {
    const pending = aggregate(
      'segment.rule-based',
      ['SELECT_DEFINITION', 'MESSAGE_TEMPLATE_REQUIRED'],
      [SEGMENT_OPTION],
    )

    expect(clarificationAnswers(pending, SEGMENT_OPTION.optionId, 'by SMS')).toEqual([
      { code: 'SELECT_DEFINITION', optionId: 'option:age', freeText: null },
      { code: 'MESSAGE_TEMPLATE_REQUIRED', optionId: null, freeText: 'by SMS' },
    ])
  })

  it('keeps the single-question wire answer byte-compatible', () => {
    const withOptions: ConversationClarification = {
      skillId: 'segment.rule-based',
      code: 'CAMPAIGN_AGENT_SEGMENT_NEEDS_INPUT',
      prompt: 'Which definition should apply?',
      questionCodes: ['SELECT_DEFINITION'],
      options: [SEGMENT_OPTION],
      freeTextAllowed: false,
    }
    expect(clarificationAnswers(withOptions, SEGMENT_OPTION.optionId, null)).toEqual([
      { code: 'SELECT_DEFINITION', optionId: 'option:age', freeText: null },
    ])
    expect(clarificationAnswers(withOptions, null, 'free words')).toEqual([
      { code: 'SELECT_DEFINITION', optionId: null, freeText: 'free words' },
    ])

    const freeText: ConversationClarification = {
      skillId: 'message.template',
      code: 'CAMPAIGN_AGENT_MESSAGE_INPUT_REQUIRED',
      prompt: 'Which message template should the Campaign use?',
      questionCodes: ['MESSAGE_TEMPLATE_REQUIRED'],
      options: [],
      freeTextAllowed: true,
    }
    expect(owningQuestionCode(freeText)).toBeNull()
    expect(clarificationAnswers(freeText, null, 'SMS reminder')).toEqual([
      { code: 'MESSAGE_TEMPLATE_REQUIRED', optionId: null, freeText: 'SMS reminder' },
    ])
  })
})

describe('buildComponentIssueAnswers: per-component answer isolation', () => {
  function segmentIssue(overrides?: Partial<ComponentIssue>): ComponentIssue {
    return {
      component: 'SEGMENT',
      skillId: 'segment.rule-based',
      code: 'SEGMENT_NEEDS_INPUT',
      prompt: 'Please clarify the audience.',
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

  function perIssue(
    entries: ReadonlyArray<readonly [ComponentIssue, { optionId: string | null; freeText: string }]>,
  ): ReadonlyMap<string, { optionId: string | null; freeText: string }> {
    const map = new Map<string, { optionId: string | null; freeText: string }>()
    for (const [issue, value] of entries) {
      map.set(`${issue.component}-${issue.code}`, value)
    }
    return map
  }

  it('Segment and Journey each produce their own distinct free-text answers', () => {
    const segment = segmentIssue()
    const journey = journeyIssue()

    const answers = buildComponentIssueAnswers(
      [segment, journey],
      perIssue([
        [segment, { optionId: null, freeText: 'members in the gold tier' }],
        [journey, { optionId: null, freeText: 'straight through by SMS' }],
      ]),
    )

    expect(answers).toHaveLength(2)

    const segAnswer = answers.find((a) => a.code === 'SEGMENT_AUDIENCE_REPHRASE_REQUIRED')
    const jouAnswer = answers.find((a) => a.code === 'JOURNEY_INPUT_REQUIRED')

    expect(segAnswer).toBeDefined()
    expect(jouAnswer).toBeDefined()

    // Segment gets its own text, not the Journey text
    expect(segAnswer!.freeText).toBe('members in the gold tier')
    expect(segAnswer!.optionId).toBeNull()

    // Journey gets its own text, not the Segment text
    expect(jouAnswer!.freeText).toBe('straight through by SMS')
    expect(jouAnswer!.optionId).toBeNull()
  })

  it('one component text never appears in the other answer', () => {
    const segment = segmentIssue()
    const journey = journeyIssue()

    const answers = buildComponentIssueAnswers(
      [segment, journey],
      perIssue([
        [segment, { optionId: null, freeText: 'SEGMENT_ONLY_TEXT' }],
        [journey, { optionId: null, freeText: 'JOURNEY_ONLY_TEXT' }],
      ]),
    )

    // Neither answer contains the other component's text
    for (const answer of answers) {
      if (answer.code === 'SEGMENT_AUDIENCE_REPHRASE_REQUIRED') {
        expect(answer.freeText).not.toContain('JOURNEY_ONLY_TEXT')
        expect(answer.freeText).toBe('SEGMENT_ONLY_TEXT')
      }
      if (answer.code === 'JOURNEY_INPUT_REQUIRED') {
        expect(answer.freeText).not.toContain('SEGMENT_ONLY_TEXT')
        expect(answer.freeText).toBe('JOURNEY_ONLY_TEXT')
      }
    }
  })

  it('per-issue option selection binds to the issue own code', () => {
    const segment = segmentIssue({
      options: [{ optionId: 'opt:geo', label: 'Geography' }],
      freeTextAllowed: false,
    })
    const journey = journeyIssue({
      options: [{ optionId: 'opt:linear', label: 'Linear journey' }],
      freeTextAllowed: false,
    })

    const answers = buildComponentIssueAnswers(
      [segment, journey],
      perIssue([
        [segment, { optionId: 'opt:geo', freeText: '' }],
        [journey, { optionId: 'opt:linear', freeText: '' }],
      ]),
    )

    expect(answers).toHaveLength(2)
    expect(answers).toEqual(
      expect.arrayContaining([
        { code: 'SEGMENT_AUDIENCE_REPHRASE_REQUIRED', optionId: 'opt:geo', freeText: null, component: 'SEGMENT' },
        { code: 'JOURNEY_INPUT_REQUIRED', optionId: 'opt:linear', freeText: null, component: 'JOURNEY' },
      ]),
    )
  })

  it('option and free text can coexist across different issues', () => {
    const segment = segmentIssue({
      options: [{ optionId: 'opt:geo', label: 'Geography' }],
      freeTextAllowed: false,
    })
    const journey = journeyIssue({
      options: [],
      freeTextAllowed: true,
    })

    const answers = buildComponentIssueAnswers(
      [segment, journey],
      perIssue([
        [segment, { optionId: 'opt:geo', freeText: '' }],
        [journey, { optionId: null, freeText: 'two-step onboarding' }],
      ]),
    )

    expect(answers).toHaveLength(2)
    expect(answers).toEqual(
      expect.arrayContaining([
        { code: 'SEGMENT_AUDIENCE_REPHRASE_REQUIRED', optionId: 'opt:geo', freeText: null, component: 'SEGMENT' },
        { code: 'JOURNEY_INPUT_REQUIRED', optionId: null, freeText: 'two-step onboarding', component: 'JOURNEY' },
      ]),
    )
  })

  it('skips an issue with no option selected and empty free text', () => {
    const segment = segmentIssue()
    const journey = journeyIssue()

    const answers = buildComponentIssueAnswers(
      [segment, journey],
      perIssue([
        [segment, { optionId: null, freeText: 'gold members' }],
        [journey, { optionId: null, freeText: '' }],
      ]),
    )

    // Journey is skipped because its free text is empty
    expect(answers).toHaveLength(1)
    expect(answers[0]!.code).toBe('SEGMENT_AUDIENCE_REPHRASE_REQUIRED')
    expect(answers[0]!.freeText).toBe('gold members')
  })

  it('skips an issue not present in the per-issue map', () => {
    const segment = segmentIssue()
    const journey = journeyIssue()

    const answers = buildComponentIssueAnswers(
      [segment, journey],
      perIssue([
        [segment, { optionId: null, freeText: 'gold members' }],
        // Journey not in map — skipped
      ]),
    )

    expect(answers).toHaveLength(1)
    expect(answers[0]!.code).toBe('SEGMENT_AUDIENCE_REPHRASE_REQUIRED')
  })

  it('returns empty when no issue has an answer', () => {
    const segment = segmentIssue()
    const journey = journeyIssue()

    const answers = buildComponentIssueAnswers(
      [segment, journey],
      perIssue([
        [segment, { optionId: null, freeText: '' }],
        [journey, { optionId: null, freeText: '' }],
      ]),
    )

    expect(answers).toHaveLength(0)
  })

  it('trims free text before including it in the answer', () => {
    const segment = segmentIssue()

    const answers = buildComponentIssueAnswers(
      [segment],
      perIssue([
        [segment, { optionId: null, freeText: '  gold members  ' }],
      ]),
    )

    expect(answers).toHaveLength(1)
    expect(answers[0]!.freeText).toBe('gold members')
  })
})

describe('generateInstanceNamespace: per-provider-instance uniqueness', () => {
  it('produces a namespace that matches REFERENCE_ID_PATTERN', () => {
    for (let i = 0; i < 128; i++) {
      const ns = generateInstanceNamespace()
      expect(ns).toMatch(REFERENCE_ID_PATTERN)
      // Bounded well within the 128-character contract limit.
      expect(ns.length).toBeLessThan(128)
      // Every UUID-derived namespace is 36 characters; fallbacks are shorter.
      expect(ns.length).toBeLessThanOrEqual(36)
    }
  })

  it('produces namespaces that are unique across calls — no two overlap', () => {
    const seen = new Set<string>()
    for (let i = 0; i < 512; i++) {
      const ns = generateInstanceNamespace()
      expect(seen.has(ns)).toBe(false)
      seen.add(ns)
    }
  })

  it('produces a non-empty, non-GUID-empty namespace', () => {
    const ns = generateInstanceNamespace()
    expect(ns.length).toBeGreaterThan(0)
    expect(ns).not.toBe('00000000-0000-0000-0000-000000000000')
  })
})

describe('formatMutationKey: key shape and bounds', () => {
  it('produces a key that matches REFERENCE_ID_PATTERN', () => {
    const ns = generateInstanceNamespace()
    const key = formatMutationKey(ns, 'plan', 4)
    expect(key).toMatch(REFERENCE_ID_PATTERN)
  })

  it('starts with the prefix, followed by the namespace and counter', () => {
    const ns = '00000000-0000-0000-0000-000000000000'
    const key = formatMutationKey(ns, 'apply', 7)
    expect(key).toBe('apply-00000000-0000-0000-0000-000000000000-7')
  })

  it('stays well within the 128-character contract bound', () => {
    const ns = generateInstanceNamespace()
    for (let c = 0; c < 256; c++) {
      const key = formatMutationKey(ns, 'scheduleState', c)
      expect(key.length).toBeLessThan(128)
    }
  })

  it('produces unique keys for different prefixes with the same namespace and counter', () => {
    const ns = generateInstanceNamespace()
    const a = formatMutationKey(ns, 'plan', 1)
    const b = formatMutationKey(ns, 'apply', 1)
    expect(a).not.toBe(b)
  })

  it('produces unique keys for different namespaces with the same prefix and counter', () => {
    const ns1 = generateInstanceNamespace()
    const ns2 = generateInstanceNamespace()
    const a = formatMutationKey(ns1, 'plan', 1)
    const b = formatMutationKey(ns2, 'plan', 1)
    expect(a).not.toBe(b)
  })

  it('produces distinct keys for each counter increment', () => {
    const ns = generateInstanceNamespace()
    const seen = new Set<string>()
    for (let c = 1; c <= 128; c++) {
      const key = formatMutationKey(ns, 'turn', c)
      expect(seen.has(key)).toBe(false)
      seen.add(key)
    }
  })
})