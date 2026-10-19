import { useEffect, useRef, useState, type FormEvent, type ReactElement } from 'react'
import {
  goalOf,
  isAwaitingClarification,
  type ComponentProgress,
  type ConversationProjection,
  type ConversationTurn,
  type SkillProgressState,
} from '../contracts/conversation'
import { isFixtureGateway } from '../gateway/types'
import { useWorkspace } from '../state/workspaceStore'
import { StatusBadge } from './StatusBadge'

/**
 * The closed, UI-owned Skill presentation map.
 *
 * It holds the user-facing label, a description and the bounded natural-language
 * prompt starters for each of the six T27 Skills. It is presentation only: it
 * performs no routing, infers no intent from user prose and can never build a
 * Proposal or call a Tool. A Skill identifier that is not one of the six closed
 * identifiers has no entry and is not rendered.
 */
interface SkillPresentation {
  readonly label: string
  readonly description: string
  readonly addStarter: string
  readonly resolveStarter: string
  readonly changeStarter: string
}

const SKILL_PRESENTATION: Readonly<Record<string, SkillPresentation>> = {
  'segment.rule-based': {
    label: 'Audience',
    description: 'The rule-based segment that decides which members the Campaign reaches.',
    addStarter: 'Build the audience for this Campaign: members who ',
    resolveStarter: 'Fix the audience requirement so the builder can accept it: ',
    changeStarter: 'Change the audience of this Campaign to ',
  },
  'message.template': {
    label: 'Message',
    description: 'The message template and channel used to contact the audience.',
    addStarter: 'Add a message for this Campaign using the ',
    resolveStarter: 'Fix the message requirement so the builder can accept it: ',
    changeStarter: 'Change the message of this Campaign to ',
  },
  'promotion.reference': {
    label: 'Promotion',
    description: 'The reward or points the Campaign offers the audience.',
    addStarter: 'Add a promotion for this Campaign that offers ',
    resolveStarter: 'Fix the promotion requirement so the builder can accept it: ',
    changeStarter: 'Change the promotion of this Campaign to ',
  },
  'journey.flow': {
    label: 'Journey',
    description: 'The cross-step flow the Campaign runs after the audience is selected.',
    addStarter: 'Add a journey for this Campaign that ',
    resolveStarter: 'Fix the journey requirement so the builder can accept it: ',
    changeStarter: 'Change the journey of this Campaign to ',
  },
  'schedule.basic': {
    label: 'Schedule',
    description: 'When and how often the Campaign should run.',
    addStarter: 'Add a schedule for this Campaign that starts ',
    resolveStarter: 'Fix the schedule requirement so the builder can accept it: ',
    changeStarter: 'Change the schedule of this Campaign to ',
  },
  'governance.limit': {
    label: 'Governance',
    description: 'The limits and guardrails that bound the Campaign.',
    addStarter: 'Add a governance limit for this Campaign that ',
    resolveStarter: 'Fix the governance requirement so the builder can accept it: ',
    changeStarter: 'Change the governance limit of this Campaign to ',
  },
}

/** The six T27 Skills in the canonical server-owned order the canvas must render. */
const CANONICAL_SKILLS: readonly string[] = [
  'segment.rule-based',
  'message.template',
  'promotion.reference',
  'journey.flow',
  'schedule.basic',
  'governance.limit',
]

/** The phases in which a completion claim would be misleading. */
const BLOCKING_PHASES: ReadonlySet<string> = new Set([
  'BUILDING',
  'AWAITING_CLARIFICATION',
  'TERMINAL_INVALID',
])

/** The goal label the user sees; it comes from the host projection, never from their wording. */
function goalLabel(projection: ConversationProjection | null): string {
  return projection !== null && goalOf(projection) === 'AUDIENCE'
    ? 'Audience selection'
    : 'Campaign'
}

/** A readable presentation instant; the exact machine value stays in the accessible label. */
function turnStamp(at: string | null): string | null {
  if (at === null) {
    return null
  }
  return at.length >= 16 ? `${at.slice(0, 10)} ${at.slice(11, 16)}Z` : at
}

function roleLabel(turn: ConversationTurn): string {
  return turn.role === 'USER' ? 'You' : 'Campaign assistant'
}

/** One rendered canvas card: the closed Skill plus its host-derived facts. */
interface SkillCard {
  readonly skillId: string
  readonly label: string
  readonly description: string
  readonly state: SkillProgressState
  readonly code: string | null
  readonly missingDependencyCodes: readonly string[]
  readonly component: ComponentProgress | null
  /** Every accepted component of this Skill, in the server's canonical identity order. */
  readonly components: readonly ComponentProgress[]
}

const STATE_ORDER: readonly SkillProgressState[] = ['READY', 'NEEDS_INPUT', 'INVALID', 'MISSING']

function nextStepFor(card: SkillCard): string {
  switch (card.state) {
    case 'READY':
      return `The ${card.label} component is accepted. Change it with the assistant if needed.`
    case 'NEEDS_INPUT':
      return `Answer the ${card.label} question so the builder can continue.`
    case 'INVALID':
      return `Revise the ${card.label} requirement: the builder cannot use it as-is.`
    default:
      return `Add the ${card.label} requirement with the assistant.`
  }
}

function actionLabelFor(state: SkillProgressState): string {
  switch (state) {
    case 'READY':
      return 'Change with AI'
    case 'NEEDS_INPUT':
      return 'Answer requirement'
    case 'INVALID':
      return 'Resolve with AI'
    default:
      return 'Add with AI'
  }
}

function starterFor(skillId: string, state: SkillProgressState): string {
  const presentation = SKILL_PRESENTATION[skillId]
  if (presentation === undefined) {
    return ''
  }
  switch (state) {
    case 'READY':
      return presentation.changeStarter
    case 'INVALID':
      return presentation.resolveStarter
    default:
      return presentation.addStarter
  }
}

/**
 * The engineering Campaign Builder workbench.
 *
 * This is the legacy T28/T29 surface: the conversation rail plus the six-Skill
 * canvas and the component inspector. It is **not** rendered in the user-facing
 * application — it is reachable only through the explicit `?workspace=internal`
 * mode, where engineering workflows and their tests still need it. The normal URL
 * renders the two-column Campaign conversation instead.
 *
 * The user's own words are sent through the gateway unchanged; nothing here
 * parses them into directives, and nothing here builds a Proposal. The canvas and
 * the inspector render only host-derived state, and the only Proposal the surface
 * can submit is the one the host put in the projection.
 *
 * The contextual actions are deliberately inert with respect to Campaign state:
 * they focus the existing typed clarification control or put a bounded starter
 * into the existing composer. They never send, never compile, never stage, never
 * clear, never Apply and never Publish, and the user must edit and explicitly
 * send the starter themselves.
 */
export function CampaignWorkbench(): ReactElement {
  const workspace = useWorkspace()
  const projection = workspace.conversation
  const fixture = isFixtureGateway(workspace.gateway)
  const composerRef = useRef<HTMLTextAreaElement | null>(null)
  const clarificationCardRef = useRef<HTMLElement | null>(null)
  const clarificationInputRef = useRef<HTMLInputElement | null>(null)
  const [freeText, setFreeText] = useState('')
  const [selectedSkill, setSelectedSkill] = useState<string | null>(null)
  /** True when the user picked a card after the pending request started. */
  const userPickedRef = useRef(false)
  const previousStatesRef = useRef<Map<string, SkillProgressState> | null>(null)

  const sending = workspace.state.conversation.status === 'LOADING'
  const error = workspace.state.conversation.error
  const canEdit = workspace.permissions.has('campaign.edit')
  const clarification = projection === null ? null : projection.clarification
  const awaiting = projection !== null && isAwaitingClarification(projection)
  const proposal = projection?.proposal ?? null
  const audience = projection?.audience ?? null
  const followUp = projection?.followUpPrompt ?? null

  // A new turn is announced by the live region, so focus stays in the composer.
  useEffect(() => {
    if (!sending) {
      composerRef.current?.focus()
    }
  }, [sending, projection?.revision])

  // The left sidebar shows the host-owned history, never a browser-stored transcript.
  const loadHistoryRef = useRef(workspace.loadConversationHistory)
  loadHistoryRef.current = workspace.loadConversationHistory
  useEffect(() => {
    void loadHistoryRef.current()
  }, [])

  /**
   * Automatically select the Skill that just became NEEDS_INPUT or INVALID.
   *
   * A Skill that was already non-ready does not steal the selection, and an
   * explicit user choice during the pending request always wins.
   */
  useEffect(() => {
    const skills = projection?.skills ?? null
    if (skills === null) {
      previousStatesRef.current = null
      return
    }
    // Before the first projection every Skill is implicitly MISSING, so a Skill that
    // first appears as NEEDS_INPUT or INVALID counts as having just become it.
    const previous = previousStatesRef.current ?? new Map<string, SkillProgressState>()
    const next = new Map(skills.map((skill) => [skill.skillId, skill.state]))
    previousStatesRef.current = next
    if (userPickedRef.current) {
      userPickedRef.current = false
      return
    }
    const attention = skills.find(
      (skill) =>
        (skill.state === 'NEEDS_INPUT' || skill.state === 'INVALID')
        && previous.get(skill.skillId) !== skill.state,
    )
    if (attention !== undefined) {
      setSelectedSkill(attention.skillId)
    }
  }, [projection])

  const send = (): void => {
    userPickedRef.current = false
    void workspace.sendTurn(workspace.state.composerText)
  }

  const submit = (event: FormEvent<HTMLFormElement>): void => {
    event.preventDefault()
    if (sending || awaiting) {
      return
    }
    send()
  }

  const answer = (optionId: string | null, text: string | null): void => {
    userPickedRef.current = false
    void workspace.answerClarification(optionId, text).then((result) => {
      if (result?.accepted) {
        setFreeText('')
      }
    })
  }

  const prefill = (text: string): void => {
    workspace.setComposerText(text)
    composerRef.current?.focus()
  }

  const cards: readonly SkillCard[] = CANONICAL_SKILLS.map((skillId): SkillCard => {
    const progress = projection?.skills.find((skill) => skill.skillId === skillId) ?? null
    // The multi-component Journey Skill contributes several real Flow node components, so a card keeps the
    // whole accepted list and never reduces it to one.
    const components = projection?.components.filter((candidate) => candidate.skillId === skillId) ?? []
    return {
      skillId,
      label: SKILL_PRESENTATION[skillId]?.label ?? skillId,
      description: SKILL_PRESENTATION[skillId]?.description ?? '',
      state: progress?.state ?? 'MISSING',
      code: progress?.code ?? null,
      missingDependencyCodes: progress?.missingDependencyCodes ?? [],
      component: components.length === 0 ? null : (components[0] ?? null),
      components,
    }
  })

  const counts = STATE_ORDER.map((state) => ({
    state,
    count: cards.filter((card) => card.state === state).length,
  }))

  const blockers: readonly string[] = ((): readonly string[] => {
    if (projection === null) {
      return ['No Campaign requirement has been described yet.']
    }
    const found: string[] = []
    if (projection.phase === 'TERMINAL_INVALID') {
      found.push('The last requirement was refused, so the Campaign cannot continue with it.')
    }
    if (projection.phase === 'BUILDING') {
      found.push('Some Campaign requirements are unavailable or invalid. Revise them before finishing.')
    }
    if (awaiting) {
      found.push('A clarification is waiting for your answer.')
    }
    const invalid = cards.filter((card) => card.state === 'INVALID')
    if (invalid.length > 0) {
      found.push(`Invalid Skill: ${invalid.map((card) => card.label).join(', ')}.`)
    }
    const dependencies = [...new Set(cards.flatMap((card) => card.missingDependencyCodes))]
    if (dependencies.length > 0) {
      found.push(`Missing dependency: ${dependencies.join(', ')}.`)
    }
    return found
  })()

  const phase = projection?.phase ?? null
  const readyForHandoff =
    projection !== null
    && proposal !== null
    && (phase === null || !BLOCKING_PHASES.has(phase))
    && blockers.length === 0

  const selected = selectedSkill === null ? null : cards.find((card) => card.skillId === selectedSkill) ?? null

  const selectSkill = (skillId: string): void => {
    if (sending) {
      userPickedRef.current = true
    }
    setSelectedSkill(skillId)
  }

  /**
   * The only effect a contextual action may have: focus the typed clarification
   * control when one belongs to the Skill, or put a bounded starter into the
   * composer and focus it. It never sends anything.
   */
  const requirementAction = (card: SkillCard): void => {
    if (!canEdit) {
      return
    }
    if (card.state === 'NEEDS_INPUT' && clarification !== null && clarification.skillId === card.skillId) {
      if (clarification.freeTextAllowed && clarificationInputRef.current !== null) {
        clarificationInputRef.current.focus()
        return
      }
      const option = clarificationCardRef.current?.querySelector<HTMLButtonElement>(
        '[data-testid="clarification-option"]',
      )
      if (option !== null && option !== undefined) {
        option.focus()
        return
      }
    }
    prefill(starterFor(card.skillId, card.state))
  }

  const goals: readonly string[] = ['Campaign', 'Audience selection']
  const empty = projection === null || projection.turns.length === 0
  const firstUserTurn = projection?.turns.find((turn) => turn.role === 'USER') ?? null
  const conversationTitle = firstUserTurn?.text ?? 'New Campaign'
  const history = workspace.history ?? []
  const currentKey =
    projection === null ? null : `${projection.conversationId}::${projection.runId}`
  const otherConversations = history.filter(
    (entry) => `${entry.conversationId}::${entry.runId}` !== currentKey,
  )

  return (
    <section className="conversation" aria-label="Campaign conversation">
      <aside className="conversation__sidebar" aria-label="Campaign conversations">
        <div className="conversation__sidebar-brand">
          <span className="conversation__agent-mark" aria-hidden="true">C</span>
          <div>
            <span>Campaign Agent</span>
            <small>AI campaign workspace</small>
          </div>
        </div>
        <button
          type="button"
          className="button button--primary conversation__new-campaign"
          data-testid="conversation-reset"
          onClick={() => {
            void workspace.startNewConversation()
          }}
        >
          <span aria-hidden="true">＋</span>
          New Campaign
        </button>
        <nav className="conversation__history" aria-label="Campaign history">
          <h2>Recent</h2>
          <ul>
            <li>
              <button
                type="button"
                className="conversation__history-item"
                data-testid="conversation-history-current"
                aria-current="page"
              >
                <span className="conversation__history-title">{conversationTitle}</span>
                <span className="conversation__history-meta">
                  {empty ? 'Start a conversation' : `${projection?.turns.length ?? 0} messages`}
                </span>
              </button>
            </li>
            {otherConversations.map((entry) => (
              <li key={`${entry.conversationId}::${entry.runId}`}>
                <button
                  type="button"
                  className="conversation__history-item"
                  data-testid="conversation-history-item"
                  onClick={() => {
                    void workspace.openConversation(entry.conversationId, entry.runId)
                  }}
                >
                  <span className="conversation__history-title">
                    {entry.title ?? 'New Campaign'}
                  </span>
                  <span className="conversation__history-meta">
                    {entry.turnCount === 0 ? 'Start a conversation' : `${entry.turnCount} messages`}
                  </span>
                </button>
              </li>
            ))}
          </ul>
          {otherConversations.length === 0 ? (
            <p className="conversation__history-note">
              Previous Campaign conversations will appear here.
            </p>
          ) : null}
        </nav>
      </aside>

      <div className="conversation__main">
        <header className="conversation__header">
        <div className="conversation__intro">
          <span className="conversation__agent-mark" aria-hidden="true">C</span>
          <div>
            <p className="conversation__agent-label">Campaign Agent</p>
            <h2>What would you like to build?</h2>
            <p className="conversation__intro-copy">
              Describe a campaign or the audience you need. I&apos;ll ask the right questions and
              assemble it with you.
            </p>
          </div>
        </div>
        <p className="conversation__goal" data-testid="conversation-goal">
          Current goal: <strong>{goalLabel(projection)}</strong>
        </p>
        <ul className="conversation__goals" aria-label="Available goals">
          {goals.map((goal) => (
            <li key={goal} aria-current={goalLabel(projection) === goal ? 'true' : undefined}>
              {goal}
            </li>
          ))}
        </ul>
        {fixture ? (
          <p className="simulated-marker" data-testid="conversation-simulated">
            SIMULATED development conversation. No model provider is called and no fixture identity or
            data is production evidence.
          </p>
        ) : null}
        </header>

        <div className="conversation__workbench">
        <section className="conversation__rail" aria-label="Conversation rail">
          <ol
            className="conversation__transcript"
            data-testid="conversation-transcript"
            role="log"
            aria-live="polite"
            aria-label="Conversation transcript"
          >
            {empty ? (
              <li className="conversation__empty">
                <span className="conversation__avatar" aria-hidden="true">C</span>
                <div className="conversation__empty-message">
                  <strong>Hi, I&apos;m your Campaign Agent.</strong>
                  <p>
                    Tell me the outcome you want, even if the idea is still rough. I can help define
                    the audience, message, offer, journey, timing, and guardrails.
                  </p>
                </div>
              </li>
            ) : (
              projection?.turns.map((turn) => (
                <li
                  key={turn.turnId}
                  className="conversation__turn"
                  data-role={turn.role}
                  data-testid="conversation-turn"
                >
                  <p className="conversation__turn-meta">
                    <span className="conversation__turn-role">{roleLabel(turn)}</span>
                    <span className="conversation__turn-label">{`Turn ${turn.ordinal + 1}`}</span>
                    {turnStamp(turn.at) === null ? null : (
                      <time className="conversation__turn-time" dateTime={turn.at ?? undefined}>
                        {turnStamp(turn.at)}
                      </time>
                    )}
                  </p>
                  <p className="conversation__turn-text">{turn.text}</p>
                </li>
              ))
            )}
          </ol>

          {sending ? (
            <p className="conversation__pending" data-testid="conversation-pending" role="status">
              Sending your turn. The transcript is kept.
            </p>
          ) : null}

          {error === null ? null : (
            <div className="conversation__error" data-testid="conversation-error" role="alert">
              <p>
                The turn was not accepted. Your message was kept. <code>{error.code}</code>
              </p>
              <button
                type="button"
                className="button"
                data-testid="conversation-retry"
                disabled={sending}
                onClick={() => {
                  userPickedRef.current = false
                  void workspace.retryLastTurn()
                }}
              >
                Retry turn
              </button>
            </div>
          )}

          {projection !== null && !projection.accepted && projection.code !== null ? (
            <p className="conversation__refusal" data-testid="conversation-refusal" role="alert">
              The host refused this turn with the bounded code <code>{projection.code}</code>. The
              transcript and the accepted state are unchanged.
            </p>
          ) : null}

          {empty ? (
            <div className="conversation__suggestions" aria-label="Suggested starting points">
              <p className="conversation__suggestions-label">Try one of these</p>
              <button
                type="button"
                className="button"
                data-testid="suggestion-build-campaign"
                disabled={!canEdit || sending}
                onClick={() => prefill('Build a Campaign that welcomes our best members with ')}
              >
                Build a Campaign
              </button>
              <button
                type="button"
                className="button"
                data-testid="suggestion-select-audience"
                disabled={!canEdit || sending}
                onClick={() => prefill('Select an audience of members who ')}
              >
                Select an audience
              </button>
            </div>
          ) : null}

          <form className="conversation__composer" onSubmit={submit} aria-label="Compose a Campaign turn">
            <label className="field" htmlFor="conversation-composer">
              <span className="field__label">Message the Campaign assistant</span>
              <textarea
                id="conversation-composer"
                ref={composerRef}
                className="field__input conversation__composer-input"
                rows={3}
                placeholder="Describe your campaign goal or the audience you want to reach…"
                value={workspace.state.composerText}
                disabled={!canEdit}
                aria-describedby="conversation-composer-help"
                onChange={(event) => workspace.setComposerText(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === 'Enter' && !event.shiftKey) {
                    event.preventDefault()
                    if (!sending && !awaiting && workspace.state.composerText.trim().length > 0) {
                      send()
                    }
                  }
                }}
              />
            </label>
            <p id="conversation-composer-help" className="field__label">
              Enter to send · Shift+Enter for a new line
            </p>
            <button
              type="submit"
              className="button button--primary"
              disabled={sending || awaiting || !canEdit || workspace.state.composerText.trim().length === 0}
            >
              Send turn
            </button>
          </form>

          {awaiting && clarification !== null ? (
            <section
              className="card conversation__clarification"
              data-testid="clarification-card"
              aria-label="Clarification"
              ref={clarificationCardRef}
            >
              <h3>Clarification needed</h3>
              <p className="conversation__clarification-prompt">{clarification.prompt}</p>
              <ul className="conversation__clarification-codes">
                {clarification.questionCodes.map((code) => (
                  <li key={code}>
                    <code>{code}</code>
                  </li>
                ))}
              </ul>
              {clarification.options.length === 0 ? null : (
                <ul className="conversation__options">
                  {clarification.options.map((option) => (
                    <li key={option.optionId}>
                      <button
                        type="button"
                        className="button"
                        data-testid="clarification-option"
                        disabled={sending}
                        onClick={() => answer(option.optionId, null)}
                      >
                        {option.label}
                      </button>
                    </li>
                  ))}
                </ul>
              )}
              {clarification.freeTextAllowed ? (
                <div className="field field--inline">
                  <label className="field" htmlFor="clarification-text">
                    <span className="field__label">Answer in your own words</span>
                    <input
                      id="clarification-text"
                      ref={clarificationInputRef}
                      className="field__input"
                      type="text"
                      value={freeText}
                      disabled={sending}
                      onChange={(event) => setFreeText(event.target.value)}
                    />
                  </label>
                  <button
                    type="button"
                    className="button"
                    data-testid="clarification-answer"
                    disabled={sending || freeText.trim().length === 0}
                    onClick={() => answer(null, freeText.trim())}
                  >
                    Answer
                  </button>
                </div>
              ) : (
                <p className="field__label">Choose one of the offered answers.</p>
              )}
            </section>
          ) : null}
        </section>

        <section className="conversation__builder" aria-label="Campaign builder">
          <section className="card conversation__canvas" aria-label="Campaign canvas">
            <h3>Campaign canvas</h3>
            <p className="card__note">
              Host-derived assembly state for the six closed Skills, in canonical order. It is never
              assistant prose and never saved Campaign state.
            </p>

            <div className="conversation__summary" data-testid="canvas-summary">
              <ul className="conversation__counts" aria-label="Completion summary">
                {counts.map((entry) => (
                  <li key={entry.state} data-state={entry.state} data-testid={`canvas-count-${entry.state}`}>
                    <StatusBadge status={entry.state} />
                    <span className="conversation__count">{entry.count}</span>
                  </li>
                ))}
              </ul>
              <p className="conversation__phase">
                Agent phase: <code data-testid="canvas-phase">{phase ?? 'Not started'}</code>
              </p>
              {projection === null ? null : (
                <p className="card__note" data-testid="conversation-session">
                  Session revision {projection.revision} · phase <code>{projection.phase}</code>
                </p>
              )}
            </div>

            <p className="conversation__completion" data-testid="canvas-completion">
              {readyForHandoff
                ? 'The host produced a Proposal. The Campaign is ready for the explicit Plan handoff.'
                : blockers.length === 0
                  ? 'No blocker is open. Ask the assistant to finish the Campaign to create the host Proposal.'
                  : `The Campaign is not ready yet. ${blockers.length} blocker(s) remain.`}
            </p>

            <ul className="conversation__skills" data-testid="skill-progress" aria-label="Skill components">
              {cards.map((card) => (
                <li key={card.skillId} className="conversation__skill" data-skill={card.skillId}>
                  <button
                    type="button"
                    className="conversation__card"
                    data-testid="canvas-card"
                    data-skill-id={card.skillId}
                    aria-pressed={selectedSkill === card.skillId}
                    aria-controls="component-inspector"
                    onClick={() => selectSkill(card.skillId)}
                  >
                    <span className="conversation__card-head">
                      <span className="conversation__card-label">{card.label}</span>
                      <StatusBadge status={card.state} />
                    </span>
                    <span className="conversation__skill-id">{card.skillId}</span>
                    <span className="conversation__card-component">
                      {card.components.length === 0
                        ? 'No accepted component yet.'
                        : card.components.length === 1
                          ? 'Accepted component exists.'
                          : `Accepted components: ${card.components.length}.`}
                    </span>
                    {card.code === null ? null : (
                      <span className="conversation__card-code">
                        Code: <code>{card.code}</code>
                      </span>
                    )}
                    {card.missingDependencyCodes.length === 0 ? null : (
                      <span className="conversation__skill-missing">
                        Missing:{' '}
                        {card.missingDependencyCodes.map((code) => (
                          <code key={code}>{code}</code>
                        ))}
                      </span>
                    )}
                    <span className="conversation__card-next">{nextStepFor(card)}</span>
                  </button>
                </li>
              ))}
            </ul>
          </section>

          <section
            className="card conversation__inspector"
            id="component-inspector"
            data-testid="component-inspector"
            aria-label="Component inspector"
          >
            <h3>Component inspector</h3>
            {selected === null ? (
              <p className="card__note">
                Select a Skill on the Campaign canvas to see its safe details and the next available
                user action.
              </p>
            ) : (
              <>
                <p className="conversation__inspector-title">
                  <strong>{selected.label}</strong> <StatusBadge status={selected.state} />
                </p>
                <p className="card__note">{selected.description}</p>
                <p className="conversation__inspector-next" data-testid="inspector-next-step">
                  {nextStepFor(selected)}
                </p>
                <dl className="facts">
                  <div>
                    <dt>Accepted component</dt>
                    <dd data-testid="inspector-component">
                      {selected.component === null ? 'None' : selected.component.componentId}
                    </dd>
                  </div>
                  <div>
                    <dt>Capability code</dt>
                    <dd data-testid="inspector-capability">
                      {selected.component?.capabilityCode ?? 'None'}
                    </dd>
                  </div>
                  <div>
                    <dt>Operation kind</dt>
                    <dd data-testid="inspector-operation">
                      {selected.component?.operationKind ?? 'None'}
                    </dd>
                  </div>
                </dl>
                {selected.components.length <= 1 ? null : (
                  <div className="conversation__inspector-components" data-testid="inspector-components">
                    <p className="card__note">
                      Accepted components of this Skill, in the server&apos;s canonical order:
                    </p>
                    <ul>
                      {selected.components.map((component) => (
                        <li key={component.componentId}>
                          <code>{component.componentId}</code> — <code>{component.capabilityCode}</code>
                        </li>
                      ))}
                    </ul>
                  </div>
                )}
                {selected.skillId !== 'journey.flow' || projection?.journey === null || projection === null ? null : (
                  <div className="conversation__inspector-journey" data-testid="inspector-journey">
                    <p className="card__note">
                      Accepted Journey topology summary. It carries counts, the entry component and
                      integrity hashes only.
                    </p>
                    <dl className="facts">
                      <div>
                        <dt>Nodes</dt>
                        <dd data-testid="inspector-journey-nodes">{projection.journey.nodeCount}</dd>
                      </div>
                      <div>
                        <dt>Edges</dt>
                        <dd data-testid="inspector-journey-edges">{projection.journey.edgeCount}</dd>
                      </div>
                      <div>
                        <dt>Entry component</dt>
                        <dd data-testid="inspector-journey-entry">{projection.journey.entryComponentId}</dd>
                      </div>
                      <div>
                        <dt>Terminals</dt>
                        <dd data-testid="inspector-journey-terminals">{projection.journey.terminalCount}</dd>
                      </div>
                    </dl>
                  </div>
                )}
                {selected.code === null ? null : (
                  <p className="conversation__inspector-code">
                    Bounded code: <code data-testid="inspector-code">{selected.code}</code>
                  </p>
                )}
                {selected.missingDependencyCodes.length === 0 ? null : (
                  <p className="conversation__skill-missing" data-testid="inspector-missing">
                    Missing dependency:{' '}
                    {selected.missingDependencyCodes.map((code) => (
                      <code key={code}>{code}</code>
                    ))}
                  </p>
                )}
                {selected.component === null ? null : (
                  <details className="conversation__technical">
                    <summary>Technical details</summary>
                    <p className="card__note">
                      Integrity hash of the accepted artifact. It is a non-secret integrity value, not
                      a credential, and the UI never submits it.
                    </p>
                    <p>
                      Artifact hash: <code data-testid="inspector-hash">{selected.component.artifactHash}</code>
                    </p>
                    {projection?.journey === null || projection === null || selected.skillId !== 'journey.flow' ? null : (
                      <p>
                        Topology hash:{' '}
                        <code data-testid="inspector-journey-topology-hash">
                          {projection.journey.topologyHash}
                        </code>
                      </p>
                    )}
                    <p>
                      Skill ID: <code>{selected.skillId}</code>
                    </p>
                  </details>
                )}
                <div className="conversation__actions">
                  <button
                    type="button"
                    className="button button--primary"
                    data-testid="inspector-action"
                    disabled={!canEdit}
                    onClick={() => requirementAction(selected)}
                  >
                    {actionLabelFor(selected.state)}
                  </button>
                  <p className="card__note" data-testid="inspector-action-note">
                    This only fills the composer or moves focus to the clarification answer. It never
                    sends, compiles, stages, applies or publishes anything. Edit the text and send it
                    yourself.
                  </p>
                </div>
              </>
            )}
          </section>

          {audience === null ? null : (
            <section className="card" data-testid="audience-card" aria-label="Audience result">
              <h3>Audience result</h3>
              <dl className="facts">
                <div>
                  <dt>Matched members</dt>
                  <dd data-testid="audience-count">{audience.matchedCount.toLocaleString('en-GB')}</dd>
                </div>
                <div>
                  <dt>Status</dt>
                  <dd>{audience.status}</dd>
                </div>
                <div>
                  <dt>Freshness (as of)</dt>
                  <dd data-testid="audience-as-of">{audience.asOf}</dd>
                </div>
                <div>
                  <dt>Data watermark</dt>
                  <dd data-testid="audience-watermark">{audience.dataWatermark}</dd>
                </div>
                <div>
                  <dt>Timezone</dt>
                  <dd>{audience.timezone}</dd>
                </div>
              </dl>
              <p className="card__note">
                Safe semantics, count and freshness only. No member identifier, contact detail or raw
                sample leaves the server.
              </p>
              {followUp === null ? null : (
                <p className="conversation__follow-up" data-testid="audience-follow-up">
                  {followUp}
                </p>
              )}
            </section>
          )}

          {blockers.length > 0 ? (
            <section className="card" data-testid="completion-blockers" aria-label="Completion blockers">
              <h3>The Campaign is not complete</h3>
              <ul>
                {blockers.map((blocker) => (
                  <li key={blocker}>{blocker}</li>
                ))}
              </ul>
            </section>
          ) : proposal === null ? (
            <section className="card" data-testid="completion-pending" aria-label="Campaign completion">
              <h3>Campaign not finished</h3>
              <p>
                No host Proposal exists yet. When the Skills you need are accepted, ask the assistant
                to finish the Campaign.
              </p>
              {blockers.length === 0 ? null : (
                <ul>
                  {blockers.map((blocker) => (
                    <li key={blocker}>{blocker}</li>
                  ))}
                </ul>
              )}
            </section>
          ) : (
            <section className="card" data-testid="proposal-ready" aria-label="Proposal ready">
              <h3>Proposal ready</h3>
              <p>
                The host produced one <code>CampaignProposalPatch</code> from the accepted assembly. It
                is not saved until you create the Plan and apply it.
              </p>
              <p className="card__note" data-testid="proposal-summary">
                {proposal.operations.length} operation(s) for mode <code>{proposal.mode}</code>
              </p>
              <button
                type="button"
                className="button button--primary"
                data-testid="create-plan"
                disabled={!canEdit || workspace.state.plan.status === 'LOADING'}
                onClick={() => {
                  void workspace.createPlanFromConversation()
                }}
              >
                Create Plan
              </button>
            </section>
          )}
        </section>
        </div>
      </div>
    </section>
  )
}
