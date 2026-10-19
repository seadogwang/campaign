import { useEffect, useRef, type KeyboardEvent as ReactKeyboardEvent } from 'react'

/**
 * The Enter that belongs to an in-progress IME composition, so it confirms a candidate
 * instead of submitting the form.
 *
 * A Chinese (or Japanese, Korean) IME composes inside the text control: the candidate
 * window is open and the Enter the user presses chooses a candidate. That Enter is a
 * real `keydown`, so without this guard it would send half-finished text and throw away
 * the candidate the user was choosing.
 */
export interface ImeComposition {
  readonly onCompositionStart: () => void
  readonly onCompositionEnd: () => void
  /** True when this Enter is the composition's own key press, not a submit. */
  readonly confirmingComposition: (event: ReactKeyboardEvent<HTMLElement>) => boolean
}

/**
 * Tracks the composition window of one text control.
 *
 * Three independent signals are consulted because engines disagree about which they set:
 * the standard `isComposing` flag, the legacy `keyCode 229` marker that older engines and
 * some IMEs still emit, and the composition window this hook observes directly from
 * `compositionstart` / `compositionend`.
 *
 * The window also absorbs the keydown that immediately follows `compositionend`, because
 * WebKit can fire `compositionend` *before* the `keydown` that closes the composition —
 * the confirming press would otherwise look like a plain Enter on that engine. The window
 * stays open until the next task, which is a separate user action by then, so the ordinary
 * "type, then press Enter to send" flow is untouched.
 */
export function useImeComposition(): ImeComposition {
  const composing = useRef(false)
  const justEnded = useRef(false)
  const endTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => {
    return () => {
      if (endTimer.current !== null) {
        clearTimeout(endTimer.current)
      }
    }
  }, [])

  return {
    onCompositionStart: () => {
      if (endTimer.current !== null) {
        clearTimeout(endTimer.current)
        endTimer.current = null
      }
      composing.current = true
      justEnded.current = false
    },
    onCompositionEnd: () => {
      composing.current = false
      justEnded.current = true
      if (endTimer.current !== null) {
        clearTimeout(endTimer.current)
      }
      endTimer.current = setTimeout(() => {
        endTimer.current = null
        justEnded.current = false
      }, 0)
    },
    confirmingComposition: (event) => {
      const native = event.nativeEvent
      return composing.current || justEnded.current || native.isComposing || native.keyCode === 229
    },
  }
}