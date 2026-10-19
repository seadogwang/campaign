import type { ReactElement } from 'react'
import type {
  CampaignComponent,
  CampaignComponentTodo,
  CampaignScheduleEvidence,
  ComponentManifestEntry,
  ConversationProjection,
  ManifestCapabilityCode,
} from '../contracts/conversation'
import { StatusBadge } from './StatusBadge'

/**
 * The closed, user-facing business label of one Campaign component group.
 *
 * It labels host-derived facts and never names a Skill identifier, a Tool identifier or a machine
 * code. The map is exhaustive over the closed component set, so an internal identifier can never
 * leak into the user-facing surface.
 */
const COMPONENT_LABELS: Readonly<Record<CampaignComponent, string>> = {
  SEGMENT: 'Audience',
  OFFER: 'Offer',
  COMMUNICATION: 'Messages',
  JOURNEY: 'Journey',
  EXECUTION_SCHEDULE: 'Schedule',
}

/** The user-facing labels for manifest capability codes that aren't in CAMPAIGN_COMPONENTS. */
const MANIFEST_LABELS: Readonly<Record<string, string>> = {
  SEGMENT: 'Audience',
  EVENT: 'Event trigger',
  MESSAGE: 'Messages',
  PROMOTION: 'Promotion',
  FLOW: 'Journey',
  SCHEDULE: 'Schedule',
}

function manifestCapabilityLabel(code: string): string {
  return MANIFEST_LABELS[code] ?? code
}

/**
 * The compact user-facing decision status of one component manifest entry.
 *
 * Capabilities that can bind resources (EVENT, MESSAGE, PROMOTION) distinguish
 * "已绑定" when selectedResourceCodes is non-empty. Capabilities that do not bind
 * resources (SEGMENT, FLOW, SCHEDULE) show only "需要" when REQUIRED.
 */
function manifestStatusLabel(
  entry: ComponentManifestEntry,
  blockedComponents: ReadonlySet<string>,
): string {
  if (entry.usageDecision === 'NOT_REQUIRED') return '不需要'
  if (entry.usageDecision === 'UNCERTAIN') return '待确认'
  const resourceCapabilities = new Set<ManifestCapabilityCode>(['EVENT', 'MESSAGE', 'PROMOTION'])
  if (resourceCapabilities.has(entry.capabilityCode)) {
    if (entry.selectedResourceCodes.length > 0) return '已绑定'
    // Map manifest capability to the component group that would be blocked.
    const blockedComp = manifestToBlockedComponent(entry.capabilityCode)
    if (blockedComp !== null && blockedComponents.has(blockedComp)) return '阻塞'
    return '需要'
  }
  return '需要'
}

/**
 * Maps a manifest capability code to the CampaignComponent that would carry a
 * RESOURCE_BLOCKED issue, or null when the capability has no resource binding.
 */
function manifestToBlockedComponent(code: ManifestCapabilityCode): CampaignComponent | null {
  switch (code) {
    case 'EVENT': return 'JOURNEY'
    case 'MESSAGE': return 'COMMUNICATION'
    case 'PROMOTION': return 'OFFER'
    default: return null
  }
}

/** The dismissable next-step text for one manifest entry, or null when nothing is pending. */
function manifestNextStep(
  entry: ComponentManifestEntry,
  blockedComponents: ReadonlySet<string>,
): string | null {
  if (entry.usageDecision === 'NOT_REQUIRED') return 'Not needed for this Campaign.'
  if (entry.usageDecision === 'UNCERTAIN') return 'Waiting for your answer — is this needed?'
  const resourceCapabilities = new Set<ManifestCapabilityCode>(['EVENT', 'MESSAGE', 'PROMOTION'])
  if (resourceCapabilities.has(entry.capabilityCode)) {
    if (entry.selectedResourceCodes.length > 0) return null
    const blockedComp = manifestToBlockedComponent(entry.capabilityCode)
    if (blockedComp !== null && blockedComponents.has(blockedComp)) {
      return 'Blocked — no available resources.'
    }
    return 'Waiting for a resource to be chosen.'
  }
  return null
}

/** A plain-language next step of one open component. It never names an internal code or identifier. */
function openReason(todo: CampaignComponentTodo): string {
  switch (todo.status) {
    case 'NOT_STARTED':
      return 'Not started yet.'
    case 'IN_PROGRESS':
      return 'Partly set up — a few details are still missing.'
    case 'NEEDS_INPUT':
      return 'Waiting for an answer from you.'
    case 'VALIDATING':
      return 'The server is checking this part.'
    case 'BLOCKED':
      return 'Waiting on another part to be sorted out first.'
    default:
      return 'Not set up yet.'
  }
}

/**
 * A compact, plain-language summary of the confirmed execution plan, or null when the Brief holds
 * no schedule facts.
 *
 * It reports only the facts the host confirmed — no computed occurrence, no default form and no
 * scheduling claim — so an unconfirmed time is never presented as a plan.
 */
function scheduleSummary(schedule: CampaignScheduleEvidence | null): string | null {
  if (schedule === null) {
    return null
  }
  const parts: string[] = []
  if (schedule.runAt !== null) {
    parts.push(`runs once at ${schedule.runAt}`)
  } else if (schedule.localTime !== null) {
    parts.push(`runs daily at ${schedule.localTime}`)
  }
  if (schedule.startDate !== null) {
    parts.push(`from ${schedule.startDate}`)
  }
  if (schedule.endDate !== null) {
    parts.push(`until ${schedule.endDate}`)
  }
  if (parts.length === 0 && schedule.timeZone === null) {
    return null
  }
  const zone = schedule.timeZone === null ? '' : ` (${schedule.timeZone})`
  const body = parts.length === 0 ? 'timezone confirmed' : parts.join(', ')
  return `Confirmed execution plan: ${body}${zone}.`
}

/**
 * Progress toward a finished Campaign, in the conversation.
 *
 * It is a compact summary of the host-reported component TODO view — how many parts are complete,
 * which parts are still open and why, the confirmed execution plan when the host reported schedule
 * facts, and the Journey shape when the server reported one. It is deliberately not a canvas: it
 * lists what is still missing rather than rendering selectable components, and it never shows a
 * Skill identifier, a Tool identifier, a machine code, an artifact hash, a node payload or a
 * routing decision.
 *
 * The progress source is the host-derived `requirements.componentTodo` view. When the host exposes
 * no such view, no part is claimed and the card says so.
 */
export function CampaignProgressCard({
  projection,
}: {
  readonly projection: ConversationProjection
}): ReactElement {
  const todos = projection.requirements?.componentTodo ?? []
  const completeCount = todos.filter((todo) => todo.status === 'COMPLETE').length
  const open = todos.filter((todo) => todo.status !== 'COMPLETE')
  const schedule = scheduleSummary(projection.requirements?.schedule ?? null)
  const journey = projection.journey
  const manifest = projection.componentManifest ?? null
  const componentIssues = projection.componentIssues ?? null

  // Capabilities blocked by a RESOURCE_BLOCKED component issue.
  const resourceBlockedComponents: ReadonlySet<string> = new Set(
    (componentIssues ?? []).filter((issue) => {
      const code = issue.code
      return code === 'RESOURCE_BLOCKED' || code.startsWith('RESOURCE_BLOCKED_')
    }).map((issue) => issue.component),
  )

  return (
    <section
      className="card conversation__result"
      data-testid="campaign-progress"
      aria-label="Campaign progress"
    >
      <h3>Campaign progress</h3>

      {/* ── Component decision status (from manifest, when present) ── */}
      {manifest !== null && manifest.length > 0 ? (
        <div
          className="conversation__manifest"
          data-testid="campaign-manifest"
          aria-label="Component decisions"
        >
          <p className="conversation__result-headline">Component decisions:</p>
          <ul
            className="conversation__open"
            data-testid="manifest-items"
            aria-label="Component decision items"
          >
            {manifest.map((entry) => (
              <li
                key={entry.capabilityCode}
                data-testid="manifest-item"
                data-capability={entry.capabilityCode}
              >
                <StatusBadge
                  status={entry.usageDecision}
                  labelOverride={manifestStatusLabel(entry, resourceBlockedComponents)}
                />
                <span className="conversation__open-label">
                  {manifestCapabilityLabel(entry.capabilityCode)}
                </span>
                {manifestNextStep(entry, resourceBlockedComponents) !== null && (
                  <span className="conversation__open-next">
                    {manifestNextStep(entry, resourceBlockedComponents)}
                  </span>
                )}
              </li>
            ))}
          </ul>
        </div>
      ) : null}

      {/* ── TODO progress summary ── */}
      <p className="conversation__result-headline" data-testid="progress-summary">
        {todos.length === 0
          ? 'No Campaign part is set up yet.'
          : `${completeCount} of ${todos.length} parts complete.`}
      </p>

      {open.length === 0 ? (
        <p className="card__note">Every part that is in scope is complete.</p>
      ) : (
        <ul className="conversation__open" data-testid="progress-open" aria-label="Parts still open">
          {open.map((todo) => (
            <li key={todo.component} data-testid="progress-open-item" data-component={todo.component}>
              <StatusBadge status={todo.status} />
              <span className="conversation__open-label">{COMPONENT_LABELS[todo.component]}</span>
              <span className="conversation__open-next">{openReason(todo)}</span>
            </li>
          ))}
        </ul>
      )}

      {schedule === null ? null : (
        <p className="card__note conversation__schedule-plan" data-testid="progress-schedule">
          {schedule}
        </p>
      )}

      {journey === null ? null : (
        <p className="card__note conversation__journey-shape" data-testid="progress-journey">
          {`Journey shape: ${journey.nodeCount} step(s), ${journey.edgeCount} connection(s), `
            + `${journey.terminalCount} ending(s). The server reports these counts only.`}
        </p>
      )}
    </section>
  )
}