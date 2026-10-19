import type { ReactElement } from 'react'
import { useWorkspace } from '../state/workspaceStore'
import { CampaignDiffCard } from './CampaignDiffCard'
import { ValidationIssuesCard } from './ValidationIssuesCard'
import { SegmentPreviewCard } from './SegmentPreviewCard'
import { SummaryCards } from './SummaryCards'
import { SimulationCard } from './SimulationCard'
import { ReadinessEvidenceCard } from './ReadinessEvidenceCard'
import { SaveControls } from './SaveControls'
import { PublishFlow } from './PublishFlow'
import { UnsavedIndicator } from './UnsavedIndicator'

/** The primary review experience: diff, issues, preview, summaries, simulation, evidence, save and publish. */
export function ReviewWorkspace(): ReactElement {
  const workspace = useWorkspace()
  const snapshot = workspace.state.workspace.value
  const plan = workspace.state.plan.value

  const previewDisabledReason = !workspace.permissions.has('audience.preview')
    ? 'Requires the audience.preview permission.'
    : plan === null
      ? 'Submit a Proposal first.'
      : plan.planRef.expiresAt <= workspace.nowInstant
        ? 'The Plan expired. Refresh and plan again.'
        : workspace.state.preview.status === 'LOADING'
          ? 'A preview is already in flight.'
          : null

  const simulationDisabledReason = plan === null
    ? 'Submit a Proposal first.'
    : plan.planRef.expiresAt <= workspace.nowInstant
      ? 'The Plan expired. Refresh and plan again.'
      : workspace.state.simulation.status === 'LOADING'
        ? 'A simulation is already in flight.'
        : null

  return (
    <div className="review">
      <UnsavedIndicator
        hasUnsavedEdits={workspace.hasUnsavedEdits}
        lastPlan={plan}
        savedDraft={snapshot?.draft ?? null}
        onDiscard={workspace.discardUnsaved}
      />
      <CampaignDiffCard plan={plan} hasUnsavedEdits={workspace.hasUnsavedEdits} />
      <ValidationIssuesCard plan={plan} />
      <SegmentPreviewCard
        preview={workspace.state.preview.value}
        error={workspace.state.preview.error}
        onRequest={() => {
          void workspace.previewSegment()
        }}
        disabledReason={previewDisabledReason}
      />
      <SummaryCards cards={snapshot?.summaries ?? null} />
      <SimulationCard
        simulation={workspace.state.simulation.value}
        error={workspace.state.simulation.error}
        onRun={() => {
          void workspace.simulate()
        }}
        disabledReason={simulationDisabledReason}
      />
      <ReadinessEvidenceCard readiness={snapshot?.readiness ?? []} />
      <SaveControls />
      <PublishFlow />
    </div>
  )
}