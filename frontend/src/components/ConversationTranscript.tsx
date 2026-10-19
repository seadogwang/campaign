import type { ReactElement } from 'react'
import type { ConversationTurn } from '../contracts/conversation'
import type { UiError } from '../contracts/errors'
import { formatInstant } from './format'

/** Starter suggestions for the empty state. They only prefill the composer, never auto-submit. */
const STARTER_SUGGESTIONS: readonly string[] = [
  'Help me reach the right audience',
  'Design a seasonal promotion',
  'Create an onboarding campaign',
  'Set up customer re-engagement',
]

/** A readable presentation instant; the exact machine value stays in `dateTime`. */
function shortInstant(at: string): string {
  const instant = formatInstant(at)
  return instant.display.length >= 16
    ? `${instant.display.slice(0, 10)} ${instant.display.slice(11, 16)}Z`
    : instant.display
}

function roleLabel(turn: ConversationTurn): string {
  return turn.role === 'USER' ? 'You' : 'Campaign Agent'
}

/**
 * The conversation transcript.
 *
 * It renders exactly the turns the host returned, in the server's order, with a
 * visible role and the host-supplied presentation instant. It never writes a turn
 * of its own, never fakes streaming and never renders assistant prose as saved
 * Campaign state.
 *
 * The transcript is a polite live region, so a new turn is announced without
 * moving focus out of the composer. A pending turn, a bounded failure with its
 * retry, and a refused turn are announced separately with their own roles.
 *
 * When the transcript is empty, concise starter suggestions are shown. They only
 * prefill the composer and never auto-submit.
 */
export function ConversationTranscript({
  turns,
  empty,
  pending,
  error,
  refusalCode,
  onRetry,
  onSuggestion,
}: {
  readonly turns: readonly ConversationTurn[]
  readonly empty: boolean
  readonly pending: boolean
  readonly error: UiError | null
  readonly refusalCode: string | null
  readonly onRetry: () => void
  readonly onSuggestion?: (text: string) => void
}): ReactElement {
  return (
    <>
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
                Tell me the campaign you want to build — the outcome you are after, or the audience
                you need to reach. It does not have to be precise; I&apos;ll ask for anything that is
                missing and assemble the Campaign with you.
              </p>
            </div>
            {onSuggestion === undefined ? null : (
              <ul
                className="conversation__starter-suggestions"
                data-testid="starter-suggestions"
                aria-label="Starter suggestions"
              >
                {STARTER_SUGGESTIONS.map((suggestion) => (
                  <li key={suggestion}>
                    <button
                      type="button"
                      className="conversation__starter-suggestion"
                      data-testid="starter-suggestion"
                      onClick={() => onSuggestion(suggestion)}
                    >
                      {suggestion}
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </li>
        ) : (
          turns.map((turn) => (
            <li
              key={turn.turnId}
              className="conversation__turn"
              data-role={turn.role}
              data-testid="conversation-turn"
            >
              <p className="conversation__turn-meta">
                <span className="conversation__turn-role">{roleLabel(turn)}</span>
                {turn.at === null ? null : (
                  <time className="conversation__turn-time" dateTime={turn.at}>
                    {shortInstant(turn.at)}
                  </time>
                )}
              </p>
              <p className="conversation__turn-text">{turn.text}</p>
            </li>
          ))
        )}
      </ol>

      {pending ? (
        <p className="conversation__pending" data-testid="conversation-pending" role="status">
          Contacting the Campaign Agent. Nothing you typed is lost.
        </p>
      ) : null}

      {error === null ? null : (
        <div className="conversation__error" data-testid="conversation-error" role="alert">
          <p>
            Your message was not accepted and was kept. {error.message} <code>{error.code}</code>
          </p>
          <button
            type="button"
            className="button"
            data-testid="conversation-retry"
            disabled={pending}
            onClick={onRetry}
          >
            Try again
          </button>
        </div>
      )}

      {refusalCode === null ? null : (
        <p className="conversation__refusal" data-testid="conversation-refusal" role="alert">
          The server did not accept that message. <code>{refusalCode}</code> Your conversation is
          unchanged — rephrase it and send again.
        </p>
      )}
    </>
  )
}