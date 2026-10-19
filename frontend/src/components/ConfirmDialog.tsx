import { useEffect, useRef, type ReactElement, type ReactNode } from 'react'

/**
 * A minimal, accessible modal confirmation.
 *
 * Focus moves to the dialog on open and returns to the trigger on close, Escape
 * cancels, and the confirm button carries the exact consequence in its label.
 */
export function ConfirmDialog({
  open,
  title,
  confirmLabel,
  cancelLabel = 'Cancel',
  onConfirm,
  onCancel,
  children,
}: {
  readonly open: boolean
  readonly title: string
  readonly confirmLabel: string
  readonly cancelLabel?: string
  readonly onConfirm: () => void
  readonly onCancel: () => void
  readonly children: ReactNode
}): ReactElement | null {
  const dialogRef = useRef<HTMLDivElement | null>(null)
  const confirmRef = useRef<HTMLButtonElement | null>(null)

  useEffect(() => {
    if (!open) {
      return
    }
    confirmRef.current?.focus()
    const onKeyDown = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') {
        event.preventDefault()
        onCancel()
      }
    }
    const node = dialogRef.current
    node?.addEventListener('keydown', onKeyDown)
    return () => node?.removeEventListener('keydown', onKeyDown)
  }, [open, onCancel])

  if (!open) {
    return null
  }

  return (
    <div className="dialog-scrim" data-testid="confirm-dialog">
      <div
        className="dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="confirm-dialog-title"
        ref={dialogRef}
      >
        <h2 id="confirm-dialog-title">{title}</h2>
        <div className="dialog__body">{children}</div>
        <div className="dialog__actions">
          <button type="button" className="button" onClick={onCancel}>
            {cancelLabel}
          </button>
          <button type="button" className="button button--primary" ref={confirmRef} onClick={onConfirm}>
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  )
}