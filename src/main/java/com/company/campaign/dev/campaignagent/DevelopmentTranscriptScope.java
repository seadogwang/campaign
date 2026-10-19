package com.company.campaign.dev.campaignagent;

/**
 * The run-local conversation correlation of the development transcript.
 *
 * <p>The development turn service opens exactly one scope for exactly the duration of one turn, so a raw-model
 * observer deep inside the routing model, the Skill input resolvers or the Segment Agent can attribute one model
 * response to the conversation that caused it — the neutral routing and Skill ports carry no conversation field
 * and must not grow one, because that would change an accepted contract.
 *
 * <p>It is deliberately <em>not</em> trusted execution state. Nothing reads it to choose a route, a Skill, an
 * input, a revision, a proposal or an output; it never reaches a model, a Tool, a permission check or a
 * persistence decision; and it is never written to the transcript file. It carries only the already-server-issued
 * opaque conversation/run identity of the request currently being served on this thread, and it is cleared when
 * the turn returns. The neutral turn service therefore keeps its promise that no identity is read from a
 * thread-local: the trusted identity still comes only from the resolved trusted context.
 *
 * <p>It nests safely: opening a scope inside an open scope restores the outer identity when the inner scope
 * closes, so a re-entrant call can never leak an inner conversation into an outer turn.
 */
final class DevelopmentTranscriptScope implements AutoCloseable {

    private static final ThreadLocal<Identity> CURRENT = new ThreadLocal<>();

    private final Identity previous;

    private DevelopmentTranscriptScope(Identity previous) {
        this.previous = previous;
    }

    /** Opens the scope of one turn, returning a handle that restores the previous identity when closed. */
    static DevelopmentTranscriptScope open(String conversationId, String runId) {
        Identity previous = CURRENT.get();
        CURRENT.set(conversationId == null ? null : new Identity(conversationId, runId));
        return new DevelopmentTranscriptScope(previous);
    }

    /** The identity of the conversation currently being served on this thread, or null when none is open. */
    static Identity current() {
        return CURRENT.get();
    }

    @Override
    public void close() {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }

    /** The opaque conversation/run identity one open scope carries; it holds no trusted tenant or user value. */
    record Identity(String conversationId, String runId) {

        Identity {
            if (conversationId == null) {
                throw new NullPointerException("conversationId");
            }
        }
    }
}