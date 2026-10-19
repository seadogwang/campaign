import { useState, type ReactElement } from 'react'
import { effectivePreparationState, type PublishPreparationState } from '../contracts/publish'
import { riskCodeLabel } from '../contracts/publish'
import { formatInstant, hashSuffix, shortReference } from './format'
import { useWorkspace } from '../state/workspaceStore'
import { ConfirmDialog } from './ConfirmDialog'
import { StatusBadge } from './StatusBadge'

const STATE_REASON: Readonly<Record<PublishPreparationState, string | null>> = {
  PREPARED: null,
  CONSUMED: 'The preparation was already consumed by one successful publish.',
  EXPIRED: 'The preparation expired. Prepare publication again.',
  STALE: 'The Draft or a frozen input drifted. Prepare publication again.',
}

/**
 * Publish is a separate two-step flow.
 *
 * Step one requests and renders a PublishPreparation with its hash, expiry and
 * frozen summary. Step two requires an explicit confirmation dialog before the
 * exact preparation identity and hash are submitted.
 */
export function PublishFlow(): ReactElement {
  const workspace = useWorkspace()
  const preparation = workspace.state.preparation.value
  const prepareSlice = workspace.state.preparation
  const publishSlice = workspace.state.publish
  const draft = workspace.state.workspace.value?.draft ?? null
  const canPublish = workspace.permissions.has('campaign.publish')
  const [dialogOpen, setDialogOpen] = useState(false)

  const consumedByPublishedResult =
    preparation !== null
    && publishSlice.value !== null
    && publishSlice.value.preparationId === preparation.preparationId
    && publishSlice.value.preparationHash === preparation.preparationHash
  const durableState: PublishPreparationState | null =
    preparation === null
      ? null
      : consumedByPublishedResult
        ? 'CONSUMED'
        : effectivePreparationState(preparation, workspace.nowInstant)
  const stateReason = durableState === null ? null : STATE_REASON[durableState]

  const prepareDisabledReason = !canPublish
    ? 'Requires the campaign.publish permission.'
    : draft === null
      ? 'Save a Draft first.'
      : prepareSlice.status === 'LOADING'
        ? 'Preparing…'
        : null

  const confirmDisabledReason =
    stateReason ??
    (prepareSlice.status === 'LOADING' ? 'Preparing…' : null) ??
    (publishSlice.status === 'LOADING' ? 'Publishing…' : null)

  return (
    <section className="card" aria-label="Publish">
      <header className="card__header">
        <h3>Publish</h3>
        {preparation === null || durableState === null ? null : <StatusBadge status={durableState} />}
      </header>
      <p className="card__note">
        Preparing a Draft is deliberately not authority to publish it. Publish independently
        re-authorises the campaign.publish permission and re-checks every frozen input.
      </p>

      <h4>Step 1 — Prepare publication</h4>
      <button
        type="button"
        className="button"
        disabled={prepareDisabledReason !== null}
        aria-describedby={prepareDisabledReason === null ? undefined : 'prepare-disabled-reason'}
        onClick={() => {
          void workspace.preparePublish()
        }}
      >
        {prepareSlice.status === 'LOADING' ? 'Preparing…' : 'Prepare publication'}
      </button>
      {prepareDisabledReason === null ? null : (
        <p id="prepare-disabled-reason" className="card__reason">
          {prepareDisabledReason}
        </p>
      )}
      {prepareSlice.error === null ? null : (
        <p className="card__reason" role="alert">
          {prepareSlice.error.message} <code>{prepareSlice.error.code}</code>
        </p>
      )}
      {preparation === null ? (
        <p className="card__empty">No preparation yet.</p>
      ) : (
        <dl className="facts">
          <div>
            <dt>Preparation</dt>
            <dd>
              <code>{shortReference(preparation.preparationId)}</code>
            </dd>
          </div>
          <div>
            <dt>Preparation hash</dt>
            <dd data-testid="preparation-hash">
              <code>{hashSuffix(preparation.preparationHash)}</code>
            </dd>
          </div>
          <div>
            <dt>Frozen manifest</dt>
            <dd>
              <code>{hashSuffix(preparation.manifestHash)}</code>
            </dd>
          </div>
          <div>
            <dt>Draft</dt>
            <dd>
              revision {preparation.draftRevision} of campaign revision {preparation.campaignRevision}
            </dd>
          </div>
          <div>
            <dt>Expires</dt>
            <dd data-testid="preparation-expiry">
              <time dateTime={preparation.expiresAt}>{formatInstant(preparation.expiresAt).display}</time>
            </dd>
          </div>
          <div>
            <dt>Frozen preview cards</dt>
            <dd>{preparation.previewCards.length}</dd>
          </div>
          <div>
            <dt>Readiness</dt>
            <dd>
              <code>{preparation.readinessCapability}</code> <StatusBadge status={preparation.readinessState} />
            </dd>
          </div>
        </dl>
      )}

      <h4>Step 2 — Confirm and publish</h4>
      {preparation === null ? (
        <p className="card__empty">Prepare publication first.</p>
      ) : (
        <>
          <p className="card__note">
            Publishing requires acknowledging every required risk code.{' '}
            {preparation.requiredRiskCodes.map(riskCodeLabel).join(', ') || 'none'}.
          </p>
          <button
            type="button"
            className="button button--primary"
            disabled={confirmDisabledReason !== null}
            aria-describedby={confirmDisabledReason === null ? undefined : 'confirm-disabled-reason'}
            onClick={() => setDialogOpen(true)}
          >
            Confirm publish…
          </button>
          {confirmDisabledReason === null ? null : (
            <p id="confirm-disabled-reason" className="card__reason" data-testid="confirm-disabled-reason">
              {confirmDisabledReason}
            </p>
          )}
        </>
      )}

      {publishSlice.error === null ? null : (
        <p className="card__reason" role="alert">
          {publishSlice.error.message} <code>{publishSlice.error.code}</code>
        </p>
      )}
      {publishSlice.value === null ? null : (
        <p className="card__ok" data-testid="publish-result">
          Published <code>{shortReference(publishSlice.value.publishedIdentity.publishedId)}</code> at
          revision {publishSlice.value.publishedIdentity.publishedRevision} on{' '}
          <time dateTime={publishSlice.value.publishedAt}>
            {formatInstant(publishSlice.value.publishedAt).display}
          </time>
          .
        </p>
      )}

      <ConfirmDialog
        open={dialogOpen}
        title="Publish this preparation?"
        confirmLabel="Publish now"
        onCancel={() => setDialogOpen(false)}
        onConfirm={() => {
          setDialogOpen(false)
          if (preparation !== null) {
            void workspace.publishPrepared(preparation.requiredRiskCodes)
          }
        }}
      >
        <p>
          This submits the exact preparation <code>{preparation?.preparationId ?? ''}</code> and its
          hash <code>{preparation === null ? '' : hashSuffix(preparation.preparationHash)}</code>.
        </p>
        <p>The server re-checks the hash, expiry, Draft revision and every frozen input.</p>
      </ConfirmDialog>
    </section>
  )
}
