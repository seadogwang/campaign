import { useCallback, useEffect, useMemo, useState, type ReactElement } from 'react'
import type {
  CampaignClarificationAnswer,
  ComponentIssue,
  ConversationClarification,
} from '../contracts/conversation'
import { clarificationKindOf, type ClarificationKind } from '../contracts/conversation'
import { buildComponentIssueAnswers } from '../state/workspaceStore'
import { useImeComposition } from './composition'

/**
 * The closed, user-facing business label of one Campaign component group.
 *
 * It labels host-derived facts and never names a Skill identifier, a Tool identifier or a machine
 * code. The map is exhaustive over the closed component set, so an internal identifier can never
 * leak into the user-facing surface.
 */
const COMPONENT_LABELS: Readonly<Record<string, string>> = {
  SEGMENT: 'Audience',
  OFFER: 'Offer',
  COMMUNICATION: 'Messages',
  JOURNEY: 'Journey',
  EXECUTION_SCHEDULE: 'Schedule',
}

function componentLabel(component: string): string {
  return COMPONENT_LABELS[component] ?? component
}

/**
 * The stable key of one pending component issue for per-issue state lookups.
 *
 * It pairs the closed component group with the stable agency-owned error/question code, so the
 * key survives a re-render and a refused turn without referencing a Skill or a Tool identifier.
 */
function issueKey(issue: ComponentIssue): string {
  return `${issue.component}-${issue.code}`
}

/**
 * The clarification kind of one component issue, or null when the code does not
 * match a known kind. Mirrors {@link clarificationKindOf} for `ComponentIssue`.
 */
function issueClarificationKind(issue: ComponentIssue): ClarificationKind | null {
  const code = issue.code
  for (const kind of ['COMPONENT_DECISION', 'RESOURCE_SELECTION', 'RESOURCE_BLOCKED'] as const) {
    if (code === kind || code.startsWith(`${kind}_`)) {
      return kind as ClarificationKind
    }
  }
  return null
}

/** The user's current answer for one pending component issue. */
interface IssueAnswerState {
  optionId: string | null
  freeText: string
}

function initialIssueAnswers(issues: readonly ComponentIssue[]): Record<string, IssueAnswerState> {
  const record: Record<string, IssueAnswerState> = {}
  for (const issue of issues) {
    record[issueKey(issue)] = { optionId: null, freeText: '' }
  }
  return record
}

/**
 * The question the server is currently waiting on.
 *
 * It renders the server's own prompt, the options the server offered and the
 * free-text path when the server allows one. The UI derives no missing field and
 * invents no option: an answer is either one of the offered option identities or
 * the user's own words.
 *
 * When the host provides a per-component issue list ({@code componentIssues}) with more than one
 * entry, each pending component is rendered with its own set of offered options and its own
 * free-text input under its business-area label — so the user can tell which part needs an answer
 * and no component's text ever leaks into another component's answer. One submit button below all
 * issues dispatches one {@code CampaignClarificationAnswer} per answered issue.
 *
 * The existing single-question rendering is preserved when componentIssues is absent, empty, or
 * contains only one issue.
 *
 * The free-text draft is reset when a different question arrives, and it survives
 * a refused turn because the projection is unchanged then. Its Enter is guarded the
 * same way as the composer, so an IME candidate is never submitted as an answer.
 */
export function ClarificationCard({
  clarification,
  componentIssues,
  pending,
  onAnswer,
  onSubmitAnswers,
}: {
  readonly clarification: ConversationClarification
  readonly componentIssues?: readonly ComponentIssue[] | null
  readonly pending: boolean
  readonly onAnswer: (optionId: string | null, freeText: string | null) => void
  /** Required when multiple componentIssues are present; dispatches per-issue answers. */
  readonly onSubmitAnswers?: (answers: readonly CampaignClarificationAnswer[]) => void
}): ReactElement {
  const [freeText, setFreeText] = useState('')
  const ime = useImeComposition()

  useEffect(() => {
    setFreeText('')
  }, [clarification])

  const hasComponentIssues = componentIssues !== null
    && componentIssues !== undefined
    && componentIssues.length > 0

  const isMultiComponent = hasComponentIssues && componentIssues.length > 1

  // ── per-issue controlled state for multi-component mode ──
  const [issueAnswers, setIssueAnswers] = useState<Record<string, IssueAnswerState>>({})

  // Reset per-issue state when the set of pending issues changes.
  useEffect(() => {
    if (isMultiComponent) {
      setIssueAnswers(initialIssueAnswers(componentIssues))
    }
  }, [isMultiComponent, componentIssues])

  const selectIssueOption = useCallback((key: string, optionId: string) => {
    setIssueAnswers((prev) => ({
      ...prev,
      [key]: { optionId, freeText: '' },
    }))
  }, [])

  const setIssueFreeText = useCallback((key: string, text: string) => {
    setIssueAnswers((prev) => ({
      ...prev,
      [key]: { optionId: null, freeText: text },
    }))
  }, [])

  const perIssueStateMap = useMemo(
    (): ReadonlyMap<string, IssueAnswerState> =>
      new Map(Object.entries(issueAnswers)),
    [issueAnswers],
  )

  const handleMultiSubmit = useCallback(() => {
    if (!isMultiComponent || onSubmitAnswers === undefined) {
      return
    }
    const answers = buildComponentIssueAnswers(componentIssues, perIssueStateMap)
    if (answers.length > 0) {
      onSubmitAnswers(answers)
    }
  }, [isMultiComponent, componentIssues, perIssueStateMap, onSubmitAnswers])

  // The multi-component submit is enabled when at least one issue has a non-empty answer.
  const multiSubmitEnabled = useMemo((): boolean => {
    if (!isMultiComponent) {
      return false
    }
    return Object.values(issueAnswers).some(
      (a) => a.optionId !== null || a.freeText.trim().length > 0,
    )
  }, [isMultiComponent, issueAnswers])

  // ── single-question / fallback rendering (preserved byte-compatible) ──
  if (!isMultiComponent) {
    return (
      <section
        className="card conversation__clarification"
        data-testid="clarification-card"
        aria-label="Question from the Campaign agent"
      >
        <h3>One more thing</h3>

        {hasComponentIssues ? (
          <ul
            className="conversation__component-issues"
            data-testid="clarification-component-issues"
            aria-label="Questions by business area"
          >
            {componentIssues.map((issue) => (
              <li
                key={issueKey(issue)}
                className="conversation__component-issue"
                data-testid="clarification-component-issue"
                data-component={issue.component}
              >
                <span className="conversation__component-issue-area">
                  {componentLabel(issue.component)}
                </span>
                <p className="conversation__component-issue-prompt">{issue.prompt}</p>
              </li>
            ))}
          </ul>
        ) : (
          <p className="conversation__clarification-prompt">{clarification.prompt}</p>
        )}

        {clarification.options.length === 0 ? null : (
          <ul className="conversation__options">
            {clarification.options.map((option) => (
              <li key={option.optionId}>
                <button
                  type="button"
                  className="button"
                  data-testid="clarification-option"
                  disabled={pending}
                  onClick={() => onAnswer(option.optionId, null)}
                >
                  {option.label}
                </button>
              </li>
            ))}
          </ul>
        )}
        {clarification.freeTextAllowed ? (
          <div className="conversation__clarification-free">
            <label className="field" htmlFor="clarification-text">
              <span className="field__label">Answer in your own words</span>
              <input
                id="clarification-text"
                className="field__input"
                type="text"
                value={freeText}
                disabled={pending}
                onChange={(event) => setFreeText(event.target.value)}
                onCompositionStart={ime.onCompositionStart}
                onCompositionEnd={ime.onCompositionEnd}
                onKeyDown={(event) => {
                  if (
                    event.key === 'Enter'
                    && freeText.trim().length > 0
                    && !ime.confirmingComposition(event)
                  ) {
                    event.preventDefault()
                    onAnswer(null, freeText.trim())
                  }
                }}
              />
            </label>
            <button
              type="button"
              className="button button--primary"
              data-testid="clarification-answer"
              disabled={pending || freeText.trim().length === 0}
              onClick={() => onAnswer(null, freeText.trim())}
            >
              Answer
            </button>
          </div>
        ) : clarification.options.length === 0 ? (
          clarificationKindOf(clarification) === 'RESOURCE_BLOCKED' ? (
            // RESOURCE_BLOCKED: no available resources. The server has already said which type is
            // missing, so the card only states it; no fake option or free-text control is offered.
            <p className="card__note card__note--blocked">
              No available resources of this type exist. Your administrator can register them.
            </p>
          ) : (
            // The free composer is blocked while the server waits, so the note never sends the
            // user to a control that is disabled. It states the block instead of naming one.
            <p className="card__note">This has to be answered before the Campaign can continue.</p>
          )
        ) : (
          <p className="card__note">Choose one of the answers above.</p>
        )}
      </section>
    )
  }

  // ── multi-component rendering: per-issue answer controls ──
  return (
    <section
      className="card conversation__clarification"
      data-testid="clarification-card"
      aria-label="Questions from the Campaign agent"
    >
      <h3>One more thing</h3>

      <ul
        className="conversation__component-issues"
        data-testid="clarification-component-issues"
        aria-label="Questions by business area"
      >
        {componentIssues.map((issue) => {
          const key = issueKey(issue)
          const answer = issueAnswers[key] ?? { optionId: null, freeText: '' }

          return (
            <li
              key={key}
              className="conversation__component-issue"
              data-testid="clarification-component-issue"
              data-component={issue.component}
            >
              <span className="conversation__component-issue-area">
                {componentLabel(issue.component)}
              </span>
              <p className="conversation__component-issue-prompt">{issue.prompt}</p>

              {issue.options.length > 0 && (
                <ul className="conversation__options">
                  {issue.options.map((option) => (
                    <li key={option.optionId}>
                      <button
                        type="button"
                        className={`button${answer.optionId === option.optionId ? ' button--selected' : ''}`}
                        data-testid={`clarification-option-${issue.component}`}
                        disabled={pending}
                        onClick={() => selectIssueOption(key, option.optionId)}
                      >
                        {option.label}
                      </button>
                    </li>
                  ))}
                </ul>
              )}

              {issue.freeTextAllowed && (
                <div className="conversation__clarification-free">
                  <label className="field" htmlFor={`clarification-text-${key}`}>
                    <span className="field__label">Answer in your own words</span>
                    <input
                      id={`clarification-text-${key}`}
                      className="field__input"
                      type="text"
                      data-testid={`clarification-free-text-${issue.component}`}
                      value={answer.freeText}
                      disabled={pending}
                      onChange={(event) => setIssueFreeText(key, event.target.value)}
                      onCompositionStart={ime.onCompositionStart}
                      onCompositionEnd={ime.onCompositionEnd}
                      onKeyDown={(event) => {
                        if (
                          event.key === 'Enter'
                          && answer.freeText.trim().length > 0
                          && !ime.confirmingComposition(event)
                        ) {
                          event.preventDefault()
                          handleMultiSubmit()
                        }
                      }}
                    />
                  </label>
                </div>
              )}

              {issue.options.length === 0 && !issue.freeTextAllowed ? (
                issueClarificationKind(issue) === 'RESOURCE_BLOCKED' ? (
                  <p className="card__note card__note--blocked">
                    No available resources of this type for {componentLabel(issue.component)}. Your administrator can register them.
                  </p>
                ) : (
                  <p className="card__note">This has to be answered before the Campaign can continue.</p>
                )
              ) : null}
            </li>
          )
        })}
      </ul>

      <button
        type="button"
        className="button button--primary"
        data-testid="clarification-submit-all"
        disabled={pending || !multiSubmitEnabled}
        onClick={handleMultiSubmit}
      >
        Submit all answers
      </button>
    </section>
  )
}