package com.company.campaign.conversation;

import com.company.campaign.builder.assembly.CampaignAssemblyContext;
import com.company.campaign.builder.campaignagent.CampaignAgentSession;

import java.util.Objects;

/**
 * One closed, versioned durable snapshot of a Campaign conversation session.
 *
 * <p>It is the only shape that crosses the storage boundary of {@link CampaignAgentSession}: the trusted key,
 * the exact host-supplied assembly context pins, the assembled workspace revision and — when a turn has already
 * been accepted — the narrow {@link CampaignAgentSession.Restore} memento. It deliberately carries no derived
 * {@link com.company.campaign.builder.assembly.CampaignAssemblyWorkspace}, no canonical hash, no prompt, no
 * model output, no Tool payload beyond the accepted artifacts and no trusted fact that the key does not
 * already hold.
 *
 * <p>A conversation that was reserved but has not yet accepted a turn has a {@code null} session and a zero
 * workspace revision; the snapshot still round-trips, because "reserved, no turn yet" is real stored state.
 *
 * <p>It is a plain immutable value: the canonical bytes and the SHA-256 content hash are produced by
 * {@link CampaignConversationSnapshotCodec}, never by this record, so nothing here can invent a hash of its own.
 */
public record CampaignConversationSnapshot(
        CampaignConversationKey key,
        CampaignAssemblyContext assemblyContext,
        long workspaceRevision,
        CampaignAgentSession.Restore session
) {

    public CampaignConversationSnapshot {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(assemblyContext, "assemblyContext");
        if (workspaceRevision < 0L) {
            throw ConversationStoreException.invalid(
                    ConversationStoreErrorCodes.SNAPSHOT_INVALID, "workspaceRevision",
                    "A stored snapshot workspace revision must not be negative.");
        }
        if (session != null && workspaceRevision != sessionWorkspaceRevision(session)) {
            // The workspace revision is derived from the accepted steps and cannot disagree with them, so an
            // incoherent pair is refused when the value is built rather than when it is read back.
            throw ConversationStoreException.invalid(
                    ConversationStoreErrorCodes.SNAPSHOT_INVALID, "workspaceRevision",
                    "A stored snapshot workspace revision must match its accepted steps.");
        }
        if (!key.conversationId().equals(assemblyContext.conversationId())
                || !key.runId().equals(assemblyContext.runId())) {
            throw ConversationStoreException.invalid(
                    ConversationStoreErrorCodes.SNAPSHOT_INVALID, "identity",
                    "A stored snapshot must describe the conversation and run of its own key.");
        }
    }

    /** The complete snapshot of one live session, at the workspace revision the session has assembled. */
    public static CampaignConversationSnapshot of(
            CampaignConversationKey key, CampaignAgentSession session) {
        Objects.requireNonNull(session, "session");
        return new CampaignConversationSnapshot(
                key, session.assemblyContext(), session.workspace().revision(), session.memento());
    }

    /** The snapshot of a reserved conversation that has not accepted a turn yet. */
    public static CampaignConversationSnapshot reserved(
            CampaignConversationKey key, CampaignAssemblyContext assemblyContext) {
        return new CampaignConversationSnapshot(key, assemblyContext, 0L, null);
    }

    /** True when this snapshot holds an accepted session rather than a bare reservation. */
    public boolean hasSession() {
        return session != null;
    }

    /**
     * The exact revision {@link CampaignAgentSession#restore} will rebuild to.
     *
     * <p>It is deliberately computed here rather than trusted from a caller: a workspace advances by exactly one
     * per accepted step, so the revision a restored session reports is fixed by the step count alone.
     */
    private static long sessionWorkspaceRevision(CampaignAgentSession.Restore session) {
        return session.steps().size();
    }
}