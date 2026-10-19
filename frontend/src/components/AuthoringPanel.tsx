import { useMemo, useState, type ReactElement } from 'react'
import { buildVisualIntent } from '../authoring/visual'
import { normalizeIntent } from '../authoring/normalizer'
import { submissionCanonicalText, type AuthoringSubmission } from '../authoring/submit'
import { UI_ERROR_CODES, UiError, fixedMessage, isUiError } from '../contracts/errors'
import type { CampaignProposalPatch } from '../contracts/proposal'
import { proposalModeFor, trustedBaseFor, useWorkspace, type AuthoringOrigin } from '../state/workspaceStore'
import { CampaignWorkbench } from './CampaignWorkbench'
import { AuthoringVisualBuilder } from './AuthoringVisualBuilder'

const SURFACES: readonly { readonly id: AuthoringOrigin; readonly label: string }[] = [
  { id: 'AI_CONVERSATION', label: 'AI Conversation' },
  { id: 'VISUAL_BUILDER', label: 'Visual Builder' },
]

/**
 * Hosts the two engineering authoring surfaces.
 *
 * This is internal-mode only: the user-facing product renders the two-column
 * Campaign conversation instead, and reaches this panel never. The conversation
 * workbench sends natural language to the host and renders the host's bounded
 * projection alongside the campaign canvas and the component inspector; the Visual
 * Builder compiles locally through the very same `CampaignProposalPatch` boundary
 * and the same gateway method.
 */
export function AuthoringPanel(): ReactElement {
  const workspace = useWorkspace()
  const [surface, setSurface] = useState<AuthoringOrigin>('AI_CONVERSATION')
  const snapshot = workspace.state.workspace.value
  const mode = proposalModeFor(snapshot)
  const campaignId = mode === 'EDIT' ? snapshot?.campaign.campaignId ?? null : null

  const preview = useMemo((): {
    readonly patch: CampaignProposalPatch | null
    readonly error: UiError | null
  } => {
    if (snapshot == null) {
      return { patch: null, error: null }
    }
    const base = trustedBaseFor(snapshot, mode)
    const intent = buildVisualIntent(workspace.state.visual, mode, campaignId)
    try {
      const submission: AuthoringSubmission = { origin: 'VISUAL_BUILDER', intent, base }
      // The canonical text proves the payload the Visual Builder would submit; it
      // is never uploaded and never becomes a second patch language.
      submissionCanonicalText(submission)
      return { patch: normalizeIntent(intent, base), error: null }
    } catch (thrown) {
      return { patch: null, error: toError(thrown) }
    }
  }, [snapshot, mode, campaignId, workspace.state.visual])

  const canEdit = workspace.permissions.has('campaign.edit')
  const planPending = workspace.state.plan.status === 'LOADING'
  const submitDisabled = !canEdit || snapshot == null || preview.patch === null || planPending
  const disabledReason = !canEdit
    ? 'Requires the campaign.edit permission.'
    : snapshot == null
      ? 'Waiting for the workspace to load.'
      : preview.patch === null
        ? 'The current authoring input produces no valid Proposal.'
        : planPending
          ? 'A Proposal is already being planned.'
          : null

  return (
    <section className="panel authoring" aria-label="Authoring">
      <div className="panel__tabs" role="tablist" aria-label="Authoring surface">
        {SURFACES.map((candidate) => (
          <button
            key={candidate.id}
            type="button"
            role="tab"
            aria-selected={surface === candidate.id}
            className="panel__tab"
            onClick={() => setSurface(candidate.id)}
          >
            {candidate.label}
          </button>
        ))}
      </div>

      <p className="sr-only" data-testid="surface-equivalence">
        Both surfaces submit the same <code>CampaignProposalPatch</code> schema through the same
        gateway method. <strong>AI Conversation</strong> is the primary surface: it sends natural
        language to the host and can only submit the Proposal the host produced.{' '}
        <strong>Visual Builder</strong> remains available as an advanced, secondary surface and
        compiles locally through the same boundary.
      </p>
      <p className="sr-only proposal-preview__note">
        Conversation transcript and assistant prose are draft interaction state only. They are never
        saved Campaign state, and assistant prose alone never creates a Plan.
      </p>

      <div role="tabpanel" aria-label={surface === 'AI_CONVERSATION' ? 'AI Conversation' : 'Visual Builder'}>
        {surface === 'AI_CONVERSATION' ? (
          <CampaignWorkbench />
        ) : (
          <>
            <AuthoringVisualBuilder state={workspace.state.visual} onChange={workspace.setVisual} />
            <section className="proposal-preview" aria-label="Proposal preview">
              <h3>Proposal preview</h3>
              <p className="proposal-preview__note">
                The Visual Builder produces one <code>CampaignProposalPatch</code>. No ChangeSet or
                Manifest is ever built here.
              </p>
              {preview.error === null ? null : (
                <p className="proposal-preview__error" role="alert">
                  {preview.error.message} <code>{preview.error.code}</code>
                </p>
              )}
              {preview.patch === null ? (
                <p>No Proposal yet.</p>
              ) : (
                <table className="proposal-preview__table">
                  <caption>{`${preview.patch.operations.length} operation(s) for mode ${preview.patch.mode}`}</caption>
                  <thead>
                    <tr>
                      <th scope="col">Operation</th>
                      <th scope="col">Capability</th>
                      <th scope="col">Target</th>
                      <th scope="col">Fields</th>
                    </tr>
                  </thead>
                  <tbody>
                    {preview.patch.operations.map((operation, index) => (
                      <tr key={`${operation.op}-${index}`}>
                        <td>{operation.op}</td>
                        <td>{operation.capability ?? '—'}</td>
                        <td>{operation.logicalId ?? operation.clientRef ?? '—'}</td>
                        <td>
                          {operation.value === null
                            ? operation.before !== null
                              ? `before ${operation.before}`
                              : `after ${operation.after ?? ''}`
                            : Object.keys(operation.value).join(', ') || 'no fields'}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </section>
            <div className="panel__actions">
              <button
                type="button"
                className="button button--primary"
                disabled={submitDisabled}
                aria-describedby={disabledReason === null ? undefined : 'submit-disabled-reason'}
                onClick={() => {
                  void workspace.compileVisualProposal()
                }}
              >
                Propose to server
              </button>
              {disabledReason === null ? null : (
                <p id="submit-disabled-reason" className="panel__reason">
                  {disabledReason}
                </p>
              )}
            </div>
          </>
        )}
      </div>
    </section>
  )
}

function toError(thrown: unknown): UiError {
  if (isUiError(thrown)) {
    return thrown
  }
  return new UiError({
    code: UI_ERROR_CODES.AUTHORING_NORMALIZATION_INVALID,
    message: fixedMessage(UI_ERROR_CODES.AUTHORING_NORMALIZATION_INVALID),
  })
}
