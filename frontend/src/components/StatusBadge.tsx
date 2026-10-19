import type { ReactElement } from 'react'
import { statusPresentation, type StatusShape } from './status'

/**
 * A status badge always shows a text label, a distinct shape and a glyph, so a
 * critical status is never encoded by colour alone.
 */
export function StatusBadge({
  status,
  labelOverride,
  detail,
}: {
  readonly status: string
  readonly labelOverride?: string | null
  readonly detail?: string | null
}): ReactElement {
  const presentation = statusPresentation(status)
  const label = labelOverride ?? presentation.label
  return (
    <span
      className="status-badge"
      data-tone={presentation.tone}
      data-shape={presentation.shape}
      role="status"
      aria-label={`Status: ${label} (${presentation.shape.toLowerCase()})`}
    >
      <svg
        className="status-badge__glyph"
        viewBox="0 0 16 16"
        width="14"
        height="14"
        aria-hidden="true"
        focusable="false"
      >
        <ShapeGlyph shape={presentation.shape} />
      </svg>
      <span className="status-badge__text">{label}</span>
      {detail === null || detail === undefined ? null : (
        <span className="status-badge__detail">{detail}</span>
      )}
    </span>
  )
}

function ShapeGlyph({ shape }: { readonly shape: StatusShape }): ReactElement {
  switch (shape) {
    case 'CIRCLE':
      return <circle cx="8" cy="8" r="6" />
    case 'SQUARE':
      return <rect x="2" y="2" width="12" height="12" />
    case 'TRIANGLE':
      return <polygon points="8,2 15,14 1,14" />
    case 'DIAMOND':
      return <polygon points="8,1 15,8 8,15 1,8" />
    case 'HEXAGON':
      return <polygon points="8,1 14,4.5 14,11.5 8,15 2,11.5 2,4.5" />
    case 'OCTAGON':
      return <polygon points="5,1 11,1 15,5 15,11 11,15 5,15 1,11 1,5" />
    default:
      return <rect x="2" y="2" width="12" height="12" />
  }
}