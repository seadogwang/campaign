import { useEffect, useRef, useState, type ReactElement } from 'react'
import { useWorkspace, type WorkspaceApi } from '../state/workspaceStore'
import { ActionDrilldown } from './ActionDrilldown'
import { ExecutionList } from './ExecutionList'
import { ParticipantDrilldown } from './ParticipantDrilldown'
import { ConfirmDialog } from './ConfirmDialog'
import { StatusBadge } from './StatusBadge'
import { countUnresolvedActions } from '../contracts/operations'

type PendingControl = 'PAUSE' | 'RESUME' | null

/**
 * The Operations view: execution list and counters, participant and action
 * drill-downs, unresolved callouts, and permission-guarded pause/resume and
 * reconcile/safe-retry controls.
 */
export function OperationsView(): ReactElement {
  const workspace = useWorkspace()
  const apiRef = useRef<WorkspaceApi>(workspace)
  apiRef.current = workspace

  const snapshot = workspace.state.workspace.value
  const campaignId = snapshot?.campaign.campaignId ?? null
  const canOperate = workspace.permissions.has('campaign.operate')
  const canRead = workspace.permissions.has('campaign.read')
  const [selectedExecution, setSelectedExecution] = useState<string | null>(null)
  const [stateRevision, setStateRevision] = useState(1)
  const [scheduleStatus, setScheduleStatus] = useState('ACTIVE')
  const [pendingControl, setPendingControl] = useState<PendingControl>(null)

  useEffect(() => {
    if (campaignId !== null) {
      void apiRef.current.loadExecutions(campaignId)
    }
  }, [campaignId])

  useEffect(() => {
    if (selectedExecution !== null) {
      void apiRef.current.loadParticipants(selectedExecution)
      void apiRef.current.loadActions(selectedExecution)
    }
  }, [selectedExecution])

  const executions = workspace.state.executions.value ?? []
  const actions = workspace.state.actions.value ?? []
  const unresolved = countUnresolvedActions(actions)

  return (
    <div className="operations">
      {canRead ? null : (
        <p className="card__reason" role="alert">
          Requires the campaign.read permission to inspect executions.
        </p>
      )}

      <ExecutionList executions={executions} selectedId={selectedExecution} onSelect={setSelectedExecution} />

      {workspace.state.executions.error === null ? null : (
        <p className="card__reason" role="alert">
          {workspace.state.executions.error.message}{' '}
          <code>{workspace.state.executions.error.code}</code>
        </p>
      )}

      <ParticipantDrilldown participants={workspace.state.participants.value ?? []} />

      <ActionDrilldown
        actions={actions}
        canOperate={canOperate}
        busy={workspace.state.actionOperation.status === 'LOADING'}
        onReconcile={(action) => {
          void (async () => {
            const result = await apiRef.current.reconcileAction(action.actionId, action.revision)
            if (result !== null && selectedExecution !== null) {
              await apiRef.current.loadActions(selectedExecution)
            }
          })()
        }}
        onSafeRetry={(action) => {
          void (async () => {
            const result = await apiRef.current.safeRetryAction(action.actionId, action.revision)
            if (result !== null && selectedExecution !== null) {
              await apiRef.current.loadActions(selectedExecution)
            }
          })()
        }}
      />

      {workspace.state.actionOperation.error === null ? null : (
        <p className="card__reason" role="alert">
          {workspace.state.actionOperation.error.message}{' '}
          <code>{workspace.state.actionOperation.error.code}</code>
        </p>
      )}

      <section className="card" aria-label="Campaign controls">
        <header className="card__header">
          <h3>Campaign controls</h3>
          <StatusBadge status={scheduleStatus} />
        </header>
        <p className="card__note">
          Pause and resume are guarded by the campaign.operate permission and an expected state
          revision. A conflict keeps your view unchanged and asks you to reload.
        </p>
        <button
          type="button"
          className="button"
          disabled={!canOperate || pendingControl !== null}
          aria-describedby="pause-reason"
          onClick={() => setPendingControl('PAUSE')}
        >
          Pause campaign
        </button>
        <button
          type="button"
          className="button"
          disabled={!canOperate || pendingControl !== null}
          aria-describedby="resume-reason"
          onClick={() => setPendingControl('RESUME')}
        >
          Resume campaign
        </button>
        <p id="pause-reason" className="card__reason">
          {canOperate
            ? `Submits expected state revision ${stateRevision}.`
            : 'Requires the campaign.operate permission.'}
        </p>
        <p id="resume-reason" className="card__reason">
          {canOperate
            ? `Submits expected state revision ${stateRevision}.`
            : 'Requires the campaign.operate permission.'}
        </p>
        {workspace.state.scheduleState.error === null ? null : (
          <p className="card__reason" role="alert">
            {workspace.state.scheduleState.error.message}{' '}
            <code>{workspace.state.scheduleState.error.code}</code>
          </p>
        )}
        {workspace.state.scheduleState.value === null ? null : (
          <p className="card__ok" data-testid="schedule-state-result">
            Schedule is now {workspace.state.scheduleState.value.status} at state revision{' '}
            {workspace.state.scheduleState.value.stateRevision}.
          </p>
        )}

        <ConfirmDialog
          open={pendingControl !== null}
          title={pendingControl === 'PAUSE' ? 'Pause the campaign?' : 'Resume the campaign?'}
          confirmLabel={pendingControl === 'PAUSE' ? 'Pause now' : 'Resume now'}
          onCancel={() => setPendingControl(null)}
          onConfirm={() => {
            const control = pendingControl
            setPendingControl(null)
            if (control === null || campaignId === null) {
              return
            }
            void (async () => {
              const result =
                control === 'PAUSE'
                  ? await apiRef.current.pauseCampaign(campaignId, stateRevision)
                  : await apiRef.current.resumeCampaign(campaignId, stateRevision)
              if (result !== null) {
                setStateRevision(result.stateRevision)
                setScheduleStatus(result.status)
              }
            })()
          }}
        >
          <p>
            {pendingControl === 'PAUSE'
              ? 'No new due occurrence may be committed while the schedule is paused. In-flight work finishes.'
              : 'Resuming uses most-recent-due catch-up rather than replaying a backlog.'}
          </p>
          <p>
            Expected state revision <code>{stateRevision}</code>.
          </p>
        </ConfirmDialog>
      </section>

      <p className="operations__summary" data-testid="operations-unresolved">
        {unresolved === 0
          ? 'No unresolved actions in the selected execution.'
          : `${unresolved} unresolved action(s) require attention.`}
      </p>
    </div>
  )
}