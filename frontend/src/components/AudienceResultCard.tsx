import type { ReactElement } from 'react'
import type { ConversationAudience } from '../contracts/conversation'
import { formatCount, formatInstant } from './format'

/**
 * The audience result the host returned for this conversation.
 *
 * It renders safe semantics, the match count, freshness, the data watermark and
 * the timezone only. No member identifier, contact detail or raw sample leaves the
 * server, and none is representable here.
 */
export function AudienceResultCard({
  audience,
  followUp,
}: {
  readonly audience: ConversationAudience
  readonly followUp: string | null
}): ReactElement {
  const asOf = formatInstant(audience.asOf)
  const watermark = formatInstant(audience.dataWatermark)
  return (
    <section className="card conversation__result" data-testid="audience-card" aria-label="Audience result">
      <h3>Your audience</h3>
      <p className="conversation__result-headline" data-testid="audience-count">
        {formatCount(audience.matchedCount)} members match
      </p>
      <dl className="facts">
        <div>
          <dt>Status</dt>
          <dd>{audience.status}</dd>
        </div>
        <div>
          <dt>As of</dt>
          <dd>
            <time dateTime={asOf.exact} data-testid="audience-as-of">
              {asOf.display}
            </time>
          </dd>
        </div>
        <div>
          <dt>Data watermark</dt>
          <dd>
            <time dateTime={watermark.exact} data-testid="audience-watermark">
              {watermark.display}
            </time>
          </dd>
        </div>
        <div>
          <dt>Timezone</dt>
          <dd>{audience.timezone}</dd>
        </div>
      </dl>
      <p className="card__note">
        A count and its freshness only — no member, contact or sample detail leaves the server.
      </p>
      {followUp === null ? null : (
        <p className="conversation__follow-up" data-testid="audience-follow-up">
          {followUp}
        </p>
      )}
    </section>
  )
}