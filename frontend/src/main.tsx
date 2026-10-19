import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './App'
import { FixtureCampaignWorkspaceGateway, HttpCampaignWorkspaceGateway } from './gateway'
import type { CampaignWorkspaceGateway } from './gateway/types'
import './styles/theme.css'
import './styles/app.css'

/**
 * Mode selection.
 *
 * `VITE_GATEWAY_MODE=http` selects the documented `/api/v1` HTTP gateway;
 * anything else uses the deterministic development fixture. Fixture mode is
 * always visibly marked SIMULATED, and the two modes share no storage.
 *
 * HTTP mode no longer needs any committed conversation identity: it issues one
 * from `POST /api/v1/campaign-agent/conversations` on the first send and keeps
 * it in memory for the page session, so nothing is read from the environment and
 * nothing is persisted.
 */
function selectGateway(): CampaignWorkspaceGateway {
  const mode = import.meta.env.VITE_GATEWAY_MODE
  if (mode === 'http') {
    return new HttpCampaignWorkspaceGateway({
      baseUrl: import.meta.env.VITE_API_BASE_URL ?? '',
      campaignId: import.meta.env.VITE_CAMPAIGN_ID ?? 'campaign-1',
      acceptedPreviewSlice: import.meta.env.VITE_ACCEPTED_PREVIEW_SLICE === 'true',
    })
  }
  return new FixtureCampaignWorkspaceGateway()
}

const container = document.getElementById('root')
if (container === null) {
  throw new Error('Root container is missing.')
}

createRoot(container).render(
  <StrictMode>
    <App gateway={selectGateway()} />
  </StrictMode>,
)