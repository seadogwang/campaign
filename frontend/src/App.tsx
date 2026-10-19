import { useEffect, useRef, useState, type ReactElement } from 'react'
import type { CampaignWorkspaceGateway } from './gateway/types'
import type { UiError } from './contracts/errors'
import { countUnresolvedActions } from './contracts/operations'
import { useWorkspace, WorkspaceProvider } from './state/workspaceStore'
import { AuthoringPanel } from './components/AuthoringPanel'
import { CampaignConversation } from './components/CampaignConversation'
import { EnvironmentBanner } from './components/EnvironmentBanner'
import { FixtureControls } from './components/FixtureControls'
import { Navigation, type WorkspaceView } from './components/Navigation'
import { NoticeBanner } from './components/NoticeBanner'
import { OperationsView } from './components/OperationsView'
import { ReviewWorkspace } from './components/ReviewWorkspace'

/**
 * The application shell.
 *
 * The normal URL renders the user-facing product and nothing else: one active
 * Campaign conversation in two columns. The engineering surfaces — the campaign
 * canvas, the component inspector, the visual builder, the review workspace, the
 * operations workspace and the fixture controls — are reachable only through the
 * explicit `?workspace=internal` mode, which is a URL an engineer or an internal
 * test asks for, not something the product offers.
 */
export function App({ gateway }: { readonly gateway: CampaignWorkspaceGateway }): ReactElement {
  return (
    <WorkspaceProvider gateway={gateway}>
      <WorkspaceShell gateway={gateway} />
    </WorkspaceProvider>
  )
}

function isInternalMode(): boolean {
  return new URLSearchParams(window.location.search).get('workspace') === 'internal'
}

function WorkspaceShell({ gateway }: { readonly gateway: CampaignWorkspaceGateway }): ReactElement {
  return isInternalMode() ? <InternalWorkspace gateway={gateway} /> : <UserWorkspace />
}

/** The product: exactly one Campaign conversation, in two columns. */
function UserWorkspace(): ReactElement {
  const workspace = useWorkspace()
  const refreshRef = useRef(workspace.refreshWorkspace)
  refreshRef.current = workspace.refreshWorkspace

  // Fixture mode carries its permission scenarios in the legacy workspace projection. The real
  // Campaign Agent UI deliberately does not call that route because it is not exposed by the
  // conversation deployment; its endpoints enforce trusted permissions server-side.
  useEffect(() => {
    if (workspace.gateway.mode === 'FIXTURE') {
      void refreshRef.current()
    }
  }, [workspace.gateway.mode])

  return (
    <div className="app">
      <a className="skip-link" href="#main">
        Skip to main content
      </a>
      <main id="main" className="app-main">
        <CampaignConversation />
      </main>
    </div>
  )
}

/** The engineering workbench. Reachable only through `?workspace=internal`. */
function InternalWorkspace({ gateway }: { readonly gateway: CampaignWorkspaceGateway }): ReactElement {
  const workspace = useWorkspace()
  const [view, setView] = useState<WorkspaceView>('AUTHORING')
  const refreshRef = useRef(workspace.refreshWorkspace)
  refreshRef.current = workspace.refreshWorkspace

  useEffect(() => {
    void refreshRef.current()
  }, [])

  const snapshot = workspace.state.workspace.value
  const actionErrors: (UiError | null)[] = [
    workspace.state.plan.error,
    workspace.state.apply.error,
    workspace.state.preparation.error,
    workspace.state.publish.error,
    workspace.state.workspace.error,
  ]
  const notice = actionErrors.find((error): error is UiError => error !== null) ?? null
  const unresolved = countUnresolvedActions(workspace.state.actions.value ?? [])

  return (
    <div className="app app--internal">
      <a className="skip-link" href="#main">
        Skip to main content
      </a>
      <header className="app-header">
        <div className="app-header__title">
          <span className="app-header__eyebrow">Internal engineering workspace</span>
          <h1>Campaign Agent</h1>
          <p className="app-header__subtitle">
            Not part of the product. This mode exists for engineering workflows and internal tests and
            is reached only through <code>?workspace=internal</code>.
          </p>
        </div>
        <EnvironmentBanner evidence={gateway.environment} detailed />
        {snapshot === null ? null : (
          <dl className="app-header__campaign">
            <div>
              <dt>Campaign</dt>
              <dd>
                {snapshot.campaign.name} <code>{snapshot.campaign.campaignId}</code>
              </dd>
            </div>
            <div>
              <dt>Status</dt>
              <dd>{snapshot.campaign.status}</dd>
            </div>
            <div>
              <dt>Revision</dt>
              <dd>{snapshot.campaign.revision}</dd>
            </div>
            <div>
              <dt>Permissions</dt>
              <dd className="app-header__permissions">
                {[...workspace.permissions].sort().join(', ') || 'none'}
              </dd>
            </div>
          </dl>
        )}
      </header>

      {notice === null ? null : (
        <NoticeBanner
          error={notice}
          onDismiss={workspace.dismissNotice}
          onRefresh={() => {
            void workspace.refreshWorkspace()
          }}
        />
      )}

      {workspace.state.workspace.status === 'LOADING' && snapshot === null ? (
        <p role="status" aria-live="polite">
          Loading workspace…
        </p>
      ) : null}

      <div className="workspace-toolbar">
        <Navigation view={view} onChange={setView} unresolvedCount={unresolved} />
      </div>

      <main id="main" className="app-main">
        {view === 'AUTHORING' ? <AuthoringPanel /> : null}
        {view === 'REVIEW' ? <ReviewWorkspace /> : null}
        {view === 'OPERATIONS' ? <OperationsView /> : null}
      </main>

      <FixtureControls gateway={gateway} />
    </div>
  )
}
