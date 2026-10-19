import type { ReactElement } from 'react'
import { SAMPLE_POLICY, type SegmentPreviewSummary } from '../contracts/preview'
import { StatusBadge } from './StatusBadge'
import { formatCount, formatSeconds, formatInstant, hashSuffix } from './format'
import type { UiError } from '../contracts/errors'

const SEMANTICS_TEXT: Readonly<Record<SegmentPreviewSummary['semantics'], string>> = {
  EXACT_MATCH_COUNT:
    'The exact number of members who satisfy the current Segment at the stated as-of instant.',
}

/**
 * Segment Preview: plain-language semantics, the exact `asOf`, source
 * freshness/watermark, the count, and the redacted sample policy.
 */
export function SegmentPreviewCard({
  preview,
  error,
  onRequest,
  disabledReason,
}: {
  readonly preview: SegmentPreviewSummary | null
  readonly error: UiError | null
  readonly onRequest: () => void
  readonly disabledReason: string | null
}): ReactElement {
  return (
    <section className="card" aria-label="Segment preview">
      <header className="card__header">
        <h3>Segment Preview</h3>
        {preview === null ? null : <StatusBadge status={preview.freshness.stale ? 'STALE' : 'READY'} />}
      </header>
      <p className="card__note">{SEMANTICS_TEXT.EXACT_MATCH_COUNT}</p>
      <button
        type="button"
        className="button"
        onClick={onRequest}
        disabled={disabledReason !== null}
        aria-describedby={disabledReason === null ? undefined : 'preview-disabled-reason'}
      >
        Request preview
      </button>
      {disabledReason === null ? null : (
        <p id="preview-disabled-reason" className="card__reason">
          {disabledReason}
        </p>
      )}
      {error === null ? null : (
        <p className="card__reason" role="alert">
          {error.message} <code>{error.code}</code>
        </p>
      )}
      {preview === null ? (
        <p className="card__empty">No preview has been requested yet.</p>
      ) : (
        <dl className="facts">
          <div>
            <dt>Matched members</dt>
            <dd data-testid="preview-count">{formatCount(preview.matchedCount)}</dd>
          </div>
          <div>
            <dt>Exact as of</dt>
            <dd>
              <time dateTime={preview.asOf}>{formatInstant(preview.asOf).display}</time>
            </dd>
          </div>
          <div>
            <dt>Data watermark</dt>
            <dd>
              <time dateTime={preview.dataWatermark}>{formatInstant(preview.dataWatermark).display}</time>
            </dd>
          </div>
          <div>
            <dt>Freshness</dt>
            <dd data-testid="preview-freshness">
              {preview.freshness.stale
                ? `Stale: data is ${formatSeconds(preview.freshness.observedAgeSeconds)} old, bound is ${formatSeconds(preview.freshness.maxDataAgeSeconds)}`
                : `Fresh: data is ${formatSeconds(preview.freshness.observedAgeSeconds)} old, bound is ${formatSeconds(preview.freshness.maxDataAgeSeconds)}`}
            </dd>
          </div>
          <div>
            <dt>Timezone</dt>
            <dd>{preview.timezone}</dd>
          </div>
          <div>
            <dt>Metadata snapshot</dt>
            <dd>
              <code>{preview.metadataSnapshotId}</code>
            </dd>
          </div>
          <div>
            <dt>Sample policy</dt>
            <dd data-testid="preview-sample-policy">
              {preview.sample.included
                ? `${SAMPLE_POLICY}: ${formatCount(preview.sample.tokens.length)} of at most ${formatCount(preview.sample.maxSampleSize)} opaque keyed hashes; no member identifier leaves the server`
                : `${SAMPLE_POLICY}: no sample requested`}
            </dd>
          </div>
          <div>
            <dt>Canonical AST</dt>
            <dd>
              <code>{hashSuffix(preview.canonicalAstHash)}</code>
            </dd>
          </div>
        </dl>
      )}
    </section>
  )
}