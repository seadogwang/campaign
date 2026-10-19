import type { ReactElement } from 'react'
import type { UiError } from '../contracts/errors'
import type { EnvironmentEvidence } from '../contracts/readiness'
import type { ConversationSummary } from '../gateway/types'
import type { AsyncStatus } from '../state/workspaceStore'
import { EnvironmentBanner } from './EnvironmentBanner'
import { formatInstant } from './format'

function historyKey(entry: ConversationSummary): string {
  return `${entry.conversationId}::${entry.runId}`
}

/**
 * The product sidebar.
 *
 * It carries the product identity, a workspace section showing the current Campaign workspace,
 * the one prominent `New configuration` action, the server-owned configuration history, and a
 * compact environment footer. The history is read from the host only: there is no browser-stored
 * transcript, and a conversation that cannot be listed is reported honestly rather than filled in.
 *
 * The workspace section shows the single Campaign workspace — it is informational only and never
 * fakes a create-workspace flow because no API exists for it.
 */
export function ConversationSidebar({
  history,
  historyStatus,
  historyError,
  activeKey,
  starting,
  canEdit,
  environment,
  onNewCampaign,
  onOpen,
  onReloadHistory,
}: {
  readonly history: readonly ConversationSummary[] | null
  readonly historyStatus: AsyncStatus
  readonly historyError: UiError | null
  readonly activeKey: string | null
  readonly starting: boolean
  readonly canEdit: boolean
  readonly environment: EnvironmentEvidence
  readonly onNewCampaign: () => void
  readonly onOpen: (conversationId: string, runId: string) => void
  readonly onReloadHistory: () => void
}): ReactElement {
  const loading = historyStatus === 'LOADING' && history === null
  const entries = history ?? []

  return (
    <aside className="conversation__sidebar" aria-label="Campaign conversations">
      <div className="conversation__sidebar-brand">
        <span className="conversation__agent-mark" aria-hidden="true">C</span>
        <div>
          <h1 className="conversation__brand-title">Campaign Agent</h1>
          <small>AI campaign studio</small>
        </div>
      </div>

      <div className="conversation__workspace">
        <h2 className="conversation__workspace-label">Workspace</h2>
        <div className="conversation__workspace-item">
          <span className="conversation__workspace-dot" aria-hidden="true" />
          Campaign workspace
        </div>
      </div>

      <button
        type="button"
        className="button button--primary conversation__new"
        data-testid="new-configuration"
        disabled={starting || !canEdit}
        onClick={onNewCampaign}
      >
        <span aria-hidden="true">＋</span>
        {starting ? 'Starting…' : 'New configuration'}
      </button>

      <nav className="conversation__history" aria-label="Configuration history">
        <h2>Configuration history</h2>

        {loading ? (
          <p className="conversation__history-note" data-testid="history-loading" role="status">
            Loading your Campaigns…
          </p>
        ) : null}

        {historyError === null ? null : (
          <div className="conversation__history-error" data-testid="history-error" role="alert">
            <p className="conversation__history-note">
              Your Campaigns could not be loaded. {historyError.message} <code>{historyError.code}</code>
            </p>
            <button type="button" className="button" data-testid="history-retry" onClick={onReloadHistory}>
              Try again
            </button>
          </div>
        )}

        <ul>
          {entries.map((entry) => {
            const active = activeKey !== null && historyKey(entry) === activeKey
            const updated = formatInstant(entry.updatedAt)
            return (
              <li key={historyKey(entry)}>
                <button
                  type="button"
                  className="conversation__history-item"
                  data-testid={active ? 'conversation-history-current' : 'conversation-history-item'}
                  aria-current={active ? 'page' : undefined}
                  onClick={() => onOpen(entry.conversationId, entry.runId)}
                >
                  <span className="conversation__history-title">
                    {entry.title ?? 'New configuration'}
                  </span>
                  <span className="conversation__history-meta">
                    {entry.turnCount === 0 ? 'Not started yet' : `${entry.turnCount} messages`}
                    {' · '}
                    <time dateTime={updated.exact}>{updated.display}</time>
                  </span>
                </button>
              </li>
            )
          })}
        </ul>

        {!loading && historyError === null && entries.length === 0 ? (
          <p className="conversation__history-note" data-testid="history-empty">
            Configurations you start will be listed here.
          </p>
        ) : null}
      </nav>

      <EnvironmentBanner evidence={environment} />
    </aside>
  )
}
