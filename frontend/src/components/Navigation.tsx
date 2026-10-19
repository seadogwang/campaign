import type { ReactElement } from 'react'

export const WORKSPACE_VIEWS = ['AUTHORING', 'REVIEW', 'OPERATIONS'] as const
export type WorkspaceView = (typeof WORKSPACE_VIEWS)[number]

const LABELS: Readonly<Record<WorkspaceView, string>> = {
  AUTHORING: 'Authoring',
  REVIEW: 'Review',
  OPERATIONS: 'Operations',
}

export function Navigation({
  view,
  onChange,
  unresolvedCount,
}: {
  readonly view: WorkspaceView
  readonly onChange: (view: WorkspaceView) => void
  readonly unresolvedCount: number
}): ReactElement {
  return (
    <nav className="nav" aria-label="Workspace sections">
      <ul className="nav__list">
        {WORKSPACE_VIEWS.map((candidate) => (
          <li key={candidate}>
            <button
              type="button"
              className="nav__button"
              aria-current={view === candidate ? 'page' : undefined}
              onClick={() => onChange(candidate)}
            >
              {LABELS[candidate]}
              {candidate === 'OPERATIONS' && unresolvedCount > 0 ? (
                <span className="nav__count" aria-label={`${unresolvedCount} unresolved actions`}>
                  {unresolvedCount}
                </span>
              ) : null}
            </button>
          </li>
        ))}
      </ul>
    </nav>
  )
}