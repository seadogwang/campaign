import type { FormEvent, ReactElement, RefObject } from 'react'
import { useImeComposition } from './composition'

/**
 * The one composer.
 *
 * The user's text is sent exactly as typed: nothing here parses it, and there is
 * no directive grammar, no placeholder business scenario and no canned prompt.
 * Enter sends, Shift+Enter starts a new line, and sending is disabled while a
 * turn is in flight so a duplicate send is impossible.
 *
 * The Enter that confirms an IME candidate is not a send: `useImeComposition` owns
 * that distinction, so composing Chinese (or Japanese, Korean) text never submits a
 * half-finished message.
 */
export function ConversationComposer({
  value,
  onChange,
  onSend,
  pending,
  canEdit,
  blocked,
  textareaRef,
}: {
  readonly value: string
  readonly onChange: (text: string) => void
  readonly onSend: () => void
  readonly pending: boolean
  readonly canEdit: boolean
  /** True while the server is waiting for an answer to the question above. */
  readonly blocked: boolean
  readonly textareaRef: RefObject<HTMLTextAreaElement>
}): ReactElement {
  const ime = useImeComposition()
  const reasonId = 'conversation-composer-reason'
  const reason = !canEdit
    ? 'You do not have permission to change this Campaign.'
    : blocked
      ? 'Answer the question above to continue.'
      : null
  const sendDisabled = pending || blocked || !canEdit || value.trim().length === 0

  const submit = (event: FormEvent<HTMLFormElement>): void => {
    event.preventDefault()
    if (!sendDisabled) {
      onSend()
    }
  }

  return (
    <form className="conversation__composer" onSubmit={submit} aria-label="Message the Campaign Agent">
      <label className="field" htmlFor="conversation-composer">
        <span className="field__label">Message the Campaign assistant</span>
        <textarea
          id="conversation-composer"
          ref={textareaRef}
          className="field__input conversation__composer-input"
          rows={2}
          placeholder="Describe the campaign you want to build…"
          value={value}
          disabled={!canEdit}
          aria-describedby={reason === null ? 'conversation-composer-help' : `${reasonId} conversation-composer-help`}
          onChange={(event) => onChange(event.target.value)}
          onCompositionStart={ime.onCompositionStart}
          onCompositionEnd={ime.onCompositionEnd}
          onKeyDown={(event) => {
            // Shift+Enter is always a new line, and the Enter that an IME composition is
            // using to choose a candidate is never a send.
            if (
              event.key === 'Enter'
              && !event.shiftKey
              && !ime.confirmingComposition(event)
            ) {
              event.preventDefault()
              if (!sendDisabled) {
                onSend()
              }
            }
          }}
        />
      </label>
      <p id="conversation-composer-help" className="conversation__composer-help">
        Enter to send · Shift+Enter for a new line
      </p>
      {reason === null ? null : (
        <p
          id={reasonId}
          className="conversation__composer-reason"
          data-testid="conversation-composer-reason"
        >
          {reason}
        </p>
      )}
      <button
        type="submit"
        className="button button--primary"
        data-testid="conversation-send"
        disabled={sendDisabled}
        aria-label={pending ? 'Sending…' : 'Send message'}
      >
        {pending ? 'Sending…' : '↑'}
      </button>
    </form>
  )
}