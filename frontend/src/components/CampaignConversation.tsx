import { useCallback, useEffect, useRef, useState, type ReactElement } from 'react'
import {
  goalOf,
  isAwaitingClarification,
  type ConversationProjection,
} from '../contracts/conversation'
import { useWorkspace } from '../state/workspaceStore'
import { AudienceResultCard } from './AudienceResultCard'
import { CampaignProgressCard } from './CampaignProgressCard'
import { ClarificationCard } from './ClarificationCard'
import { ConversationComposer } from './ConversationComposer'
import { ConversationSidebar } from './ConversationSidebar'
import { ConversationTranscript } from './ConversationTranscript'
import { NoticeBanner } from './NoticeBanner'
import { ProposalReadyCard } from './ProposalReadyCard'

/** The goal the user sees. It comes from the host projection, never from their wording. */
function goalLabel(projection: ConversationProjection | null): string {
  return projection !== null && goalOf(projection) === 'AUDIENCE'
    ? 'Audience selection'
    : 'Campaign'
}

/**
 * A plain-language name for where the conversation is.
 *
 * The server's phase codes are internal vocabulary, so the user sees a sentence.
 * The mapping adds no state of its own: it only names what the host already
 * reported, and an unknown phase falls back to a neutral phrase.
 */
function phaseLabel(projection: ConversationProjection | null): string {
  if (projection === null) {
    return 'Waiting for your idea'
  }
  switch (projection.phase) {
    case 'AWAITING_GOAL':
      return 'Waiting for your idea'
    case 'AWAITING_CLARIFICATION':
      return 'Waiting for your answer'
    case 'BUILDING':
      return 'Assembling your Campaign'
    case 'AUDIENCE_READY':
      return 'Audience ready'
    case 'READY_TO_FINALIZE':
      return 'Ready to finish'
    case 'PROPOSAL_READY':
      return 'Campaign configured'
    case 'TERMINAL_INVALID':
      return 'Last change refused'
    default:
      return 'In progress'
  }
}

/**
 * The user-facing Campaign Builder: one conversation, two columns.
 *
 * The left column is the product identity, the workspace section, the one
 * `New configuration` action and the real server-owned configuration history.
 * The right column is exactly one active Campaign conversation — a compact header,
 * the scrollable transcript with ChatGPT-like styling, the contextual server-result
 * cards, the question the server is waiting on, and a large rounded composer pinned
 * to the bottom.
 *
 * Everything rendered here is a server projection. The user's own words are sent
 * through the one gateway unchanged; nothing parses them into directives, nothing
 * derives a missing field, and the only Proposal that can be submitted is the one
 * the host put in the projection.
 *
 * The engineering surfaces this product does not show — the campaign canvas, the
 * component inspector, the visual builder, review and operations — are reachable
 * only through the explicit `?workspace=internal` mode.
 */
export function CampaignConversation(): ReactElement {
  const workspace = useWorkspace()
  const projection = workspace.conversation
  const composerRef = useRef<HTMLTextAreaElement>(null)
  const flowRef = useRef<HTMLDivElement | null>(null)
  const [startingNew, setStartingNew] = useState(false)

  const sending = workspace.state.conversation.status === 'LOADING'
  // The real Campaign Agent endpoints enforce the caller's trusted permission themselves. The
  // legacy workspace/Draft route is not part of the user-facing deployment, so HTTP conversation
  // input must not be disabled merely because that unrelated projection was never loaded.
  // Fixture mode still uses its explicit permission projection for permission-state tests.
  const canEdit = workspace.gateway.mode === 'HTTP' || workspace.permissions.has('campaign.edit')
  const clarification = projection?.clarification ?? null
  const awaiting = projection !== null && isAwaitingClarification(projection)
  const proposal = projection?.proposal ?? null
  const audience = projection?.audience ?? null
  const followUp = projection?.followUpPrompt ?? null
  const empty = projection === null || projection.turns.length === 0
  const turnCount = projection?.turns.length ?? 0

  // The history belongs to the host. It is read on mount and again after every accepted turn,
  // never from browser storage.
  const loadHistoryRef = useRef(workspace.loadConversationHistory)
  loadHistoryRef.current = workspace.loadConversationHistory
  useEffect(() => {
    void loadHistoryRef.current()
  }, [])

  // A new turn scrolls into view without taking focus out of the composer.
  useEffect(() => {
    const flow = flowRef.current
    if (flow !== null) {
      flow.scrollTop = flow.scrollHeight
    }
  }, [turnCount, sending])

  const startNew = (): void => {
    setStartingNew(true)
    void workspace.startNewConversation().finally(() => {
      setStartingNew(false)
    })
    // The user asked for a new Campaign, so focusing the composer is not focus theft.
    composerRef.current?.focus()
  }

  /**
   * Prefills the composer with a starter suggestion without auto-submitting.
   * Moves focus back to the composer so the user can review and send.
   */
  const handleSuggestion = useCallback(
    (text: string): void => {
      workspace.setComposerText(text)
      composerRef.current?.focus()
    },
    [workspace],
  )

  const title = projection?.turns.find((turn) => turn.role === 'USER')?.text ?? 'New configuration'
  const complete =
    projection !== null && proposal !== null && projection.phase === 'PROPOSAL_READY'

  return (
    <section className="conversation conversation--product" aria-label="Campaign conversation">
      <ConversationSidebar
        history={workspace.history}
        historyStatus={workspace.state.history.status}
        historyError={workspace.state.history.error}
        activeKey={workspace.activeConversationKey}
        starting={startingNew}
        canEdit={canEdit}
        environment={workspace.gateway.environment}
        onNewCampaign={startNew}
        onOpen={(conversationId, runId) => {
          void workspace.openConversation(conversationId, runId)
        }}
        onReloadHistory={() => {
          void workspace.loadConversationHistory()
        }}
      />

      <div className="conversation__main">
        <header className="conversation__header">
          <div className="conversation__intro">
            <h2 className="conversation__title" data-testid="conversation-title">
              {title}
            </h2>
            <p className="conversation__goal" data-testid="conversation-goal">
              Current goal: <strong>{goalLabel(projection)}</strong>
            </p>
          </div>
          <p className="conversation__status" data-testid="conversation-status" role="status">
            {phaseLabel(projection)}
          </p>
        </header>

        {workspace.state.notice === null ? null : (
          <NoticeBanner error={workspace.state.notice} onDismiss={workspace.dismissNotice} />
        )}

        <div className="conversation__flow" ref={flowRef}>
          <ConversationTranscript
            turns={projection?.turns ?? []}
            empty={empty}
            pending={sending}
            error={workspace.state.conversation.error}
            refusalCode={projection !== null && !projection.accepted ? projection.code : null}
            onRetry={() => {
              void workspace.retryLastTurn()
            }}
            onSuggestion={handleSuggestion}
          />

          {awaiting && clarification !== null ? (
            <ClarificationCard
              clarification={clarification}
              componentIssues={projection?.componentIssues ?? null}
              pending={sending}
              onAnswer={(optionId, freeText) => {
                void workspace.answerClarification(optionId, freeText)
              }}
              onSubmitAnswers={(answers) => {
                void workspace.answerComponentClarifications(answers)
              }}
            />
          ) : null}

          {audience === null ? null : <AudienceResultCard audience={audience} followUp={followUp} />}

          {projection === null || empty ? null : <CampaignProgressCard projection={projection} />}

          {complete && proposal !== null ? (
            <ProposalReadyCard
              proposal={proposal}
              plan={workspace.state.plan}
              canEdit={canEdit}
              onCreate={() => {
                void workspace.createPlanFromConversation()
              }}
            />
          ) : null}
        </div>

        <ConversationComposer
          value={workspace.state.composerText}
          onChange={workspace.setComposerText}
          onSend={() => {
            void workspace.sendTurn(workspace.state.composerText)
          }}
          pending={sending}
          canEdit={canEdit}
          blocked={awaiting}
          textareaRef={composerRef}
        />
      </div>
    </section>
  )
}