import type { ReactElement } from 'react'
import {
  FIXTURE_SCENARIOS,
  isFixtureGateway,
  type CampaignWorkspaceGateway,
  type FixtureScenario,
} from '../gateway/types'
import { useWorkspace } from '../state/workspaceStore'

/**
 * Explicit, fixture-only controls.
 *
 * They exist only in fixture mode, are never persisted, and never share storage
 * with the production HTTP mode. Both actions go through the workspace store, so
 * the view is re-read and reset rather than silently left stale.
 */
export function FixtureControls({ gateway }: { readonly gateway: CampaignWorkspaceGateway }): ReactElement | null {
  const workspace = useWorkspace()
  if (!isFixtureGateway(gateway)) {
    return null
  }
  return (
    <details className="fixture-controls" open>
      <summary>Development fixture controls</summary>
      <div className="fixture-controls__body" aria-label="Development fixture controls">
        <p>
          Deterministic, offline and simulation-only. It shares no storage with HTTP mode and produces
          no production evidence.
        </p>
        <label className="field field--inline" htmlFor="fixture-scenario">
          <span className="field__label">Scenario</span>
          <select
            id="fixture-scenario"
            className="field__input"
            value={gateway.scenario}
            onChange={(event) => workspace.setScenario(event.target.value as FixtureScenario)}
          >
            {FIXTURE_SCENARIOS.map((scenario) => (
              <option key={scenario} value={scenario}>
                {scenario}
              </option>
            ))}
          </select>
        </label>
        <p className="field__label">
          PERMISSION_DENIED keeps only campaign.read, so edit, publish and operate controls are denied.
        </p>
        <button type="button" className="button" onClick={() => workspace.resetFixture()}>
          Reset fixture
        </button>
      </div>
    </details>
  )
}
