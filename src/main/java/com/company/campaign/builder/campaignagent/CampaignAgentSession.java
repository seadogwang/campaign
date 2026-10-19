package com.company.campaign.builder.campaignagent;

import com.company.campaign.builder.assembly.CampaignAssemblyAcceptance;
import com.company.campaign.builder.assembly.CampaignAssemblyContext;
import com.company.campaign.builder.assembly.CampaignAssemblyWorkspace;
import com.company.campaign.builder.assembly.CampaignComponentState;
import com.company.campaign.builder.assembly.CampaignJourneyArtifact;
import com.company.campaign.builder.assembly.CampaignSkillArtifact;
import com.company.campaign.builder.assembly.CampaignSkillIds;
import com.company.campaign.builder.proposal.CampaignProposalPatch;

import java.util.ArrayList;
import java.util.List;

/**
 * The in-memory, request-scoped Campaign conversation session.
 *
 * <p>It is not a repository. It holds one explicit conversation identity, run identity, revision, phase and
 * the T27 {@link CampaignAssemblyWorkspace}, and it has no static store, no global registry, no persistence and
 * no clock. One host owns one instance per conversation; nothing here survives the request that created it.
 *
 * <p>Every accepted turn advances {@link #revision()} by exactly one. A stale, refused or failed turn changes
 * neither the revision nor the workspace: the agent plans a complete new step sequence, the session replays it
 * into a freshly constructed T27 workspace, and only a replay in which every T27 acceptance succeeded is
 * adopted. A refused acceptance therefore leaves the previous revision and the previous workspace untouched.
 */
public final class CampaignAgentSession {

    /** One planned T27 workspace step: an accepted artifact or a bounded non-READY observation. */
    public sealed interface Step {

        /** One artifact to accept into the workspace. */
        record Accept(CampaignSkillArtifact artifact) implements Step {
        }

        /**
         * One atomic Journey bundle to accept into the workspace.
         *
         * <p>It is a separate step because a Journey is staged as a bounded set of Flow nodes plus one topology
         * in a single transaction, so replaying it can never leave a partially accepted Journey behind.
         */
        record AcceptJourney(CampaignJourneyArtifact artifact) implements Step {
        }

        /** One bounded non-READY Skill state to record without staging a component. */
        record Observe(String skillId, CampaignComponentState state, String code) implements Step {
        }
    }

    private final CampaignAssemblyContext assemblyContext;
    private List<Step> steps = List.of();
    private CampaignAssemblyWorkspace workspace;

    private final List<CampaignConversationTurn> turns = new ArrayList<>();
    private long revision;
    private CampaignAgentPhase phase = CampaignAgentPhase.AWAITING_GOAL;
    private CampaignClarification pendingClarification;
    private CampaignClarificationAggregate pendingComponentClarifications = CampaignClarificationAggregate.empty();
    private CampaignSegmentContinuation segmentContinuation;
    private CampaignSegmentFreeTextRecovery segmentFreeTextRecovery;
    private CampaignProposalPatch proposal;
    private long proposalWorkspaceRevision = -1L;
    private CampaignAudienceSummary audience;
    private CampaignJourneySummary journeySummary;
    private String followUpPrompt;
    private String assistantCopy;
    private CampaignRouteKind lastRouteKind;
    private CampaignRequirementBrief requirementBrief = CampaignRequirementBrief.empty();
    private List<CampaignComponentManifest> componentManifests = List.of();

    public CampaignAgentSession(CampaignAssemblyContext assemblyContext) {
        this.assemblyContext = java.util.Objects.requireNonNull(assemblyContext, "assemblyContext");
        this.workspace = new CampaignAssemblyWorkspace(assemblyContext);
    }

    /**
     * A defensive deep copy of this session at its current revision.
     *
     * <p>The copy shares only immutable references — the assembly context, the current immutable workspace
     * snapshot, the transcript turns and the bounded records — and copies the one mutable step/transcript state,
     * so a caller that holds the copy cannot mutate this session and vice versa. A conversation store uses it so
     * a stored session is never handed out outside its own transaction: the caller works on the copy and commits
     * it back, and a failed transaction therefore leaves the stored session untouched.
     */
    public CampaignAgentSession copy() {
        CampaignAgentSession copy = new CampaignAgentSession(assemblyContext);
        copy.steps = List.copyOf(steps);
        copy.workspace = workspace;
        copy.turns.addAll(turns);
        copy.revision = revision;
        copy.phase = phase;
        copy.pendingClarification = pendingClarification;
        copy.pendingComponentClarifications = pendingComponentClarifications;
        copy.segmentContinuation = segmentContinuation;
        copy.segmentFreeTextRecovery = segmentFreeTextRecovery;
        copy.proposal = proposal;
        copy.proposalWorkspaceRevision = proposalWorkspaceRevision;
        copy.audience = audience;
        copy.journeySummary = journeySummary;
        copy.followUpPrompt = followUpPrompt;
        copy.assistantCopy = assistantCopy;
        copy.lastRouteKind = lastRouteKind;
        copy.requirementBrief = requirementBrief;
        copy.componentManifests = componentManifests;
        return copy;
    }

    /**
     * The validated restorable facts of one stored session.
     *
     * <p>It is the one narrow memento a storage boundary may hand back: the accepted workspace steps, the
     * transcript, the session revision and phase, the pending clarification and its Segment continuation, the
     * emitted Proposal and the bounded summaries. It carries no derived workspace, no canonical hash, no prompt
     * and no model output, and it can only be produced by {@link CampaignAgentSession#memento()} or accepted by
     * this constructor, so a caller cannot invent a session state the agent could never have reached.
     */
    public record Restore(
            List<Step> steps,
            long revision,
            CampaignAgentPhase phase,
            List<CampaignConversationTurn> turns,
            CampaignClarification pendingClarification,
            CampaignSegmentContinuation segmentContinuation,
            CampaignSegmentFreeTextRecovery segmentFreeTextRecovery,
            CampaignProposalPatch proposal,
            long proposalWorkspaceRevision,
            CampaignAudienceSummary audience,
            CampaignJourneySummary journeySummary,
            String followUpPrompt,
            String assistantCopy,
            CampaignRouteKind lastRouteKind,
            CampaignRequirementBrief requirementBrief,
            List<CampaignComponentClarification> componentClarifications,
            List<CampaignComponentManifest> componentManifests
    ) {

        public Restore(
                List<Step> steps,
                long revision,
                CampaignAgentPhase phase,
                List<CampaignConversationTurn> turns,
                CampaignClarification pendingClarification,
                CampaignSegmentContinuation segmentContinuation,
                CampaignProposalPatch proposal,
                long proposalWorkspaceRevision,
                CampaignAudienceSummary audience,
                CampaignJourneySummary journeySummary,
                String followUpPrompt,
                String assistantCopy,
                CampaignRouteKind lastRouteKind) {
            this(steps, revision, phase, turns, pendingClarification, segmentContinuation, null, proposal,
                    proposalWorkspaceRevision, audience, journeySummary, followUpPrompt, assistantCopy,
                    lastRouteKind, CampaignRequirementBrief.empty(), List.of(), List.of());
        }

        public Restore(
                List<Step> steps,
                long revision,
                CampaignAgentPhase phase,
                List<CampaignConversationTurn> turns,
                CampaignClarification pendingClarification,
                CampaignSegmentContinuation segmentContinuation,
                CampaignProposalPatch proposal,
                long proposalWorkspaceRevision,
                CampaignAudienceSummary audience,
                CampaignJourneySummary journeySummary,
                String followUpPrompt,
                String assistantCopy,
                CampaignRouteKind lastRouteKind,
                CampaignRequirementBrief requirementBrief) {
            this(steps, revision, phase, turns, pendingClarification, segmentContinuation, null, proposal,
                    proposalWorkspaceRevision, audience, journeySummary, followUpPrompt, assistantCopy,
                    lastRouteKind, requirementBrief, List.of(), List.of());
        }

        public Restore {
            steps = steps == null ? List.of() : List.copyOf(steps);
            turns = turns == null ? List.of() : List.copyOf(turns);
            java.util.Objects.requireNonNull(phase, "phase");
            requirementBrief = requirementBrief == null ? CampaignRequirementBrief.empty() : requirementBrief;
            componentClarifications = componentClarifications == null
                    ? List.of()
                    : List.copyOf(componentClarifications);
            componentManifests = componentManifests == null
                    ? List.of()
                    : List.copyOf(componentManifests);
            CampaignClarificationAggregate.of(componentClarifications);
            if (revision < 0) {
                throw CampaignAgentException.invalid(
                        CampaignAgentErrorCodes.REQUEST_INVALID, "revision",
                        "A restored session revision must not be negative.");
            }
            if ((proposal == null) != (proposalWorkspaceRevision < 0L)) {
                throw CampaignAgentException.invalid(
                        CampaignAgentErrorCodes.REQUEST_INVALID, "proposal",
                        "A restored Proposal and its workspace revision must be present together.");
            }
        }
    }

    /**
     * The narrow memento of this session at its current revision, for an explicit storage boundary.
     *
     * <p>It is the only way a storage boundary reads a session: the returned record is immutable and carries the
     * accepted steps rather than the derived workspace, so a store rehydrates by replaying the genuine steps.
     */
    public Restore memento() {
        return new Restore(steps, revision, phase, turns, pendingClarification, segmentContinuation,
                segmentFreeTextRecovery, proposal, proposalWorkspaceRevision, audience,
                journeySummary, followUpPrompt, assistantCopy, lastRouteKind, requirementBrief,
                pendingComponentClarifications.requests(), componentManifests);
    }

    /**
     * Rebuilds a fresh session from a validated memento by replaying the genuine T27 workspace steps.
     *
     * <p>Every step is re-proved by {@link CampaignAssemblyWorkspace}, exactly as a live turn does, so a stored
     * step a workspace would refuse is refused here instead of being trusted. A refused step throws rather than
     * returning a partially restored session.
     */
    public static CampaignAgentSession restore(CampaignAssemblyContext assemblyContext, Restore memento) {
        java.util.Objects.requireNonNull(assemblyContext, "assemblyContext");
        java.util.Objects.requireNonNull(memento, "memento");
        CampaignAgentSession session = new CampaignAgentSession(assemblyContext);
        CampaignAssemblyWorkspace rebuilt = session.rebuild(memento.steps());
        if (rebuilt == null) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.REQUEST_INVALID, "restore",
                    "A stored workspace step is refused by the genuine assembly workspace.");
        }
        session.steps = List.copyOf(memento.steps());
        session.workspace = rebuilt;
        session.turns.addAll(memento.turns());
        session.revision = memento.revision();
        session.phase = memento.phase();
        session.pendingClarification = memento.pendingClarification();
        session.pendingComponentClarifications = CampaignClarificationAggregate.of(
                memento.componentClarifications());
        session.segmentContinuation = memento.segmentContinuation();
        session.segmentFreeTextRecovery = memento.segmentFreeTextRecovery();
        session.proposal = memento.proposal();
        session.proposalWorkspaceRevision = memento.proposalWorkspaceRevision();
        session.audience = memento.audience();
        session.journeySummary = memento.journeySummary();
        session.followUpPrompt = memento.followUpPrompt();
        session.assistantCopy = memento.assistantCopy();
        session.lastRouteKind = memento.lastRouteKind();
        session.requirementBrief = memento.requirementBrief();
        session.componentManifests = memento.componentManifests();
        return session;
    }

    // ------------------------------------------------------------------ read-only projection facts

    /** The explicit conversation identity of this session. */
    public String conversationId() {
        return assemblyContext.conversationId();
    }

    /** The explicit run identity of this session. */
    public String runId() {
        return assemblyContext.runId();
    }

    /** The host-supplied start facts this session's workspace is built from. */
    public CampaignAssemblyContext assemblyContext() {
        return assemblyContext;
    }

    /** The current session revision; it advances by exactly one per accepted turn. */
    public long revision() {
        return revision;
    }

    /** The current phase of the conversation. */
    public CampaignAgentPhase phase() {
        return phase;
    }

    /** The current immutable T27 assembly workspace of this session. */
    public CampaignAssemblyWorkspace workspace() {
        return workspace;
    }

    /** The accepted transcript, in order. */
    public List<CampaignConversationTurn> turns() {
        return List.copyOf(turns);
    }

    /** The pending typed clarification, or null when the conversation needs no answer. */
    public CampaignClarification pendingClarification() {
        return pendingClarification;
    }

    /**
     * The host-owned aggregate of every component clarification the current turn collected.
     *
     * <p>It retains the originating component and Skill of each request, so the answer turn resumes every pending
     * component Skill from host state rather than from a single projected {@code skillId}, a client field or a
     * model output. It is empty whenever the pending clarification is not a component question.
     */
    public CampaignClarificationAggregate pendingComponentClarifications() {
        return pendingComponentClarifications;
    }

    /** Every Skill the pending component clarifications bind to, in canonical order, or an empty list. */
    public List<String> pendingComponentSkills() {
        return pendingComponentClarifications.pendingSkillIds();
    }

    /**
     * The exact continuation evidence of the pending Segment clarification, or null when none is pending.
     *
     * <p>It is the genuine T10 resolution identity of the Segment question this session asked, so the next
     * Campaign turn can replay the same clarification through the accepted Segment Agent instead of asking the
     * routing model to recreate it. It travels with the session: {@link #copy()} carries it, a failed turn
     * leaves it untouched and it is cleared exactly when the clarification is resolved or the Skill is
     * abandoned.
     */
    public CampaignSegmentContinuation segmentContinuation() {
        return segmentContinuation;
    }

    /**
     * The free-text Segment recovery state for initial model-output rejection, or null when none is active.
     *
     * <p>It is the host-owned separate state that tracks recovery budget when the Segment model output was
     * rejected on an initial (non-replay) turn and no {@link CampaignSegmentContinuation} exists. It travels
     * with the session exactly as the continuation does: {@link #copy()} carries it, a failed turn leaves it
     * untouched, and it is cleared when the recovery is resolved or the budget is exhausted.
     */
    public CampaignSegmentFreeTextRecovery segmentFreeTextRecovery() {
        return segmentFreeTextRecovery;
    }

    /** The one host-produced Proposal of this session, or null when none was emitted. */
    public CampaignProposalPatch proposal() {
        return proposal;
    }

    /** The workspace revision the current Proposal was emitted from, or -1 when none was emitted. */
    public long proposalWorkspaceRevision() {
        return proposalWorkspaceRevision;
    }

    /** The bounded audience card of the most recent accepted Segment artifact, or null. */
    public CampaignAudienceSummary audience() {
        return audience;
    }

    /** The host-generated follow-up prompt shown after an audience result, or null. */
    public String followUpPrompt() {
        return followUpPrompt;
    }

    /** The bounded safe assistant copy of the most recent accepted turn, or null. */
    public String assistantCopy() {
        return assistantCopy;
    }

    /** The route kind of the most recent accepted turn, or null before the first turn. */
    public CampaignRouteKind lastRouteKind() {
        return lastRouteKind;
    }

    /** The accumulated typed Campaign requirements collected before Skill assembly. */
    public CampaignRequirementBrief requirementBrief() {
        return requirementBrief;
    }

    /** The accumulated component manifests recording per-capability decisions and resource bindings. */
    public List<CampaignComponentManifest> componentManifests() {
        return componentManifests;
    }

    /** Replaces the component manifests with a new list. */
    public void setComponentManifests(List<CampaignComponentManifest> manifests) {
        this.componentManifests = manifests == null ? List.of() : List.copyOf(manifests);
    }

    /**
     * The current manifest for one capability, or {@code null} when none exists.
     *
     * <p>The returned manifest is the most recent decision for that capability; a capability
     * that has never been decided returns null. An old snapshot with no manifests at all
     * returns null for every capability.
     */
    public CampaignComponentManifest manifestFor(CampaignCapability capability) {
        if (capability == null) {
            return null;
        }
        for (CampaignComponentManifest manifest : componentManifests) {
            if (manifest.capability() == capability) {
                return manifest;
            }
        }
        return null;
    }

    /**
     * Merges the given manifests into the session, replacing any existing manifest with a
     * matching capability when the new decision supersedes the existing one.
     *
     * <p>A USER decision supersedes MODEL; SYSTEM supersedes MODEL but not USER.
     * The merged list keeps at most one manifest per capability and is bounded by MAX_MANIFESTS.
     */
    public void mergeManifests(List<CampaignComponentManifest> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return;
        }
        List<CampaignComponentManifest> merged = new ArrayList<>(componentManifests);
        for (CampaignComponentManifest candidate : incoming) {
            if (candidate == null) {
                continue;
            }
            boolean replaced = false;
            for (int i = 0; i < merged.size(); i++) {
                if (merged.get(i).capability() == candidate.capability()) {
                    if (merged.get(i).supersededBy(candidate.usageDecision(), candidate.decisionSource())) {
                        merged.set(i, candidate);
                    }
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                merged.add(candidate);
            }
        }
        this.componentManifests = List.copyOf(merged);
    }

    /** The host-derived position of the next turn, given its bounded answers. */
    public CampaignTurnPosition position(List<CampaignClarificationAnswer> answers) {
        if (revision == 0) {
            return CampaignTurnPosition.INITIAL;
        }
        if (pendingClarification != null
                && ((answers != null && !answers.isEmpty()) || pendingClarification.freeTextAllowed())) {
            return CampaignTurnPosition.ANSWER;
        }
        return CampaignTurnPosition.FOLLOW_UP;
    }

    /** The bounded prior-turn summaries handed to the routing model, oldest first. */
    public List<String> priorSummaries() {
        List<String> summaries = new ArrayList<>();
        int from = Math.max(0, turns.size() - CampaignAgentLimits.MAX_PRIOR_TURNS);
        for (CampaignConversationTurn turn : turns.subList(from, turns.size())) {
            String summary = turn.role() + ": " + turn.text();
            summaries.add(summary.length() > CampaignAgentLimits.MAX_PRIOR_TURN_LENGTH
                    ? summary.substring(0, CampaignAgentLimits.MAX_PRIOR_TURN_LENGTH)
                    : summary);
        }
        return List.copyOf(summaries);
    }

    // ------------------------------------------------------------------ planning and committing

    /** The planned workspace steps of the current revision. */
    List<Step> steps() {
        return steps;
    }

    /** The planned steps with one accepted artifact added, replacing the component it re-stages. */
    static List<Step> withArtifact(List<Step> base, CampaignSkillArtifact artifact) {
        List<Step> planned = new ArrayList<>(base.size() + 1);
        String componentId = artifact.componentId();
        for (Step step : base) {
            if (step instanceof Step.Accept accept && accept.artifact().componentId().equals(componentId)) {
                continue;
            }
            if (step instanceof Step.Observe observe && observe.skillId().equals(artifact.skillId())) {
                continue;
            }
            planned.add(step);
        }
        planned.add(new Step.Accept(artifact));
        return List.copyOf(planned);
    }

    /** Replaces every prior component of one multi-instance Skill with the supplied accepted artifacts. */
    static List<Step> withArtifacts(List<Step> base, List<CampaignSkillArtifact> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) {
            throw new IllegalArgumentException("A multi-artifact replacement requires artifacts.");
        }
        String skillId = artifacts.get(0).skillId();
        List<Step> planned = new ArrayList<>(base.size() + artifacts.size());
        for (Step step : base) {
            if (step instanceof Step.Accept accept && accept.artifact().skillId().equals(skillId)) {
                continue;
            }
            if (step instanceof Step.Observe observe && observe.skillId().equals(skillId)) {
                continue;
            }
            planned.add(step);
        }
        for (CampaignSkillArtifact artifact : artifacts) {
            if (!artifact.skillId().equals(skillId)) {
                throw new IllegalArgumentException("All replacement artifacts must belong to one Skill.");
            }
            planned.add(new Step.Accept(artifact));
        }
        return List.copyOf(planned);
    }

    /** The planned steps with one bounded observation added, replacing the state of that Skill. */
    static List<Step> withObservation(
            List<Step> base, String skillId, CampaignComponentState state, String code) {
        List<Step> planned = new ArrayList<>(base.size() + 1);
        for (Step step : base) {
            if (step instanceof Step.Observe observe && observe.skillId().equals(skillId)) {
                continue;
            }
            planned.add(step);
        }
        planned.add(new Step.Observe(skillId, state, code));
        return List.copyOf(planned);
    }

    /** The planned steps with every component and observation owned by one Skill removed. */
    static List<Step> withoutSkill(List<Step> base, String skillId) {
        List<Step> planned = new ArrayList<>(base.size());
        for (Step step : base) {
            if (step instanceof Step.Accept accept && accept.artifact().skillId().equals(skillId)) {
                continue;
            }
            if (step instanceof Step.AcceptJourney
                    && CampaignSkillIds.JOURNEY_FLOW.equals(skillId)) {
                continue;
            }
            if (step instanceof Step.Observe observe && observe.skillId().equals(skillId)) {
                continue;
            }
            planned.add(step);
        }
        return List.copyOf(planned);
    }

    /**
     * The planned steps with one Journey bundle added, replacing the whole previous Journey.
     *
     * <p>A Campaign holds at most one Journey in this version, so the previous bundle step — and any previous
     * observation of the Journey Skill — is dropped rather than merged: the new bundle replaces it atomically.
     */
    static List<Step> withJourneyArtifact(List<Step> base, CampaignJourneyArtifact artifact) {
        List<Step> planned = new ArrayList<>(base.size() + 1);
        for (Step step : base) {
            if (step instanceof Step.AcceptJourney) {
                continue;
            }
            if (step instanceof Step.Observe observe
                    && observe.skillId().equals(CampaignSkillIds.JOURNEY_FLOW)) {
                continue;
            }
            planned.add(step);
        }
        planned.add(new Step.AcceptJourney(artifact));
        return List.copyOf(planned);
    }

    /**
     * Replays one complete planned step sequence into a freshly constructed T27 workspace.
     *
     * <p>Every step goes through the genuine {@link CampaignAssemblyWorkspace}, which re-verifies the run
     * identity, Skill, Tool, catalog hash, ownership, conflict and revision rules. A refused step returns
     * {@code null} and nothing is adopted, so the caller's session keeps its previous revision and workspace.
     */
    CampaignAssemblyWorkspace rebuild(List<Step> planned) {
        CampaignAssemblyWorkspace candidate = new CampaignAssemblyWorkspace(assemblyContext);
        long expected = candidate.revision();
        for (Step step : planned) {
            CampaignAssemblyAcceptance acceptance = switch (step) {
                case Step.Accept accept -> candidate.accept(accept.artifact(), expected);
                case Step.AcceptJourney accept -> candidate.acceptJourney(accept.artifact(), expected);
                case Step.Observe observe ->
                        candidate.observe(observe.skillId(), observe.state(), observe.code(), expected);
            };
            if (!acceptance.accepted()) {
                return null;
            }
            expected = candidate.revision();
        }
        return candidate;
    }

    /** Adopts a successfully rebuilt step sequence and workspace. */
    void adopt(List<Step> planned, CampaignAssemblyWorkspace rebuilt) {
        this.steps = List.copyOf(planned);
        this.workspace = rebuilt;
    }

    /** Appends one accepted transcript turn. */
    void appendTurn(CampaignConversationTurn turn) {
        turns.add(turn);
    }

    /** Advances the session revision by exactly one. */
    void advance() {
        revision++;
    }

    void setPhase(CampaignAgentPhase next) {
        this.phase = next;
    }

    /**
     * Sets the projected clarification and clears the component aggregate: a clarification that was not produced
     * by component agents owns no component question, so no stale resume state may survive it.
     */
    void setPendingClarification(CampaignClarification clarification) {
        setPendingClarifications(clarification, List.of());
    }

    /** Sets the projected clarification together with the host-owned aggregate it was projected from. */
    void setPendingClarifications(
            CampaignClarification clarification, List<CampaignComponentClarification> clarifications) {
        this.pendingClarification = clarification;
        this.pendingComponentClarifications = CampaignClarificationAggregate.of(clarifications);
    }

    /** Sets or clears the continuation evidence of the pending Segment clarification. */
    void setSegmentContinuation(CampaignSegmentContinuation continuation) {
        this.segmentContinuation = continuation;
    }

    void setSegmentFreeTextRecovery(CampaignSegmentFreeTextRecovery recovery) {
        this.segmentFreeTextRecovery = recovery;
    }

    void setProposal(CampaignProposalPatch emitted, long workspaceRevision) {
        this.proposal = emitted;
        this.proposalWorkspaceRevision = workspaceRevision;
    }

    /** Invalidates a previously emitted Proposal, because the workspace it was emitted from changed. */
    void clearProposal() {
        this.proposal = null;
        this.proposalWorkspaceRevision = -1L;
    }

    void setAudience(CampaignAudienceSummary summary) {
        this.audience = summary;
    }

    /** The bounded Journey summary of the most recently accepted Journey bundle, or null. */
    public CampaignJourneySummary journeySummary() {
        return journeySummary;
    }

    void setJourneySummary(CampaignJourneySummary summary) {
        this.journeySummary = summary;
    }

    void setFollowUpPrompt(String prompt) {
        this.followUpPrompt = prompt;
    }

    void setAssistantCopy(String copy) {
        this.assistantCopy = copy;
    }

    void setLastRouteKind(CampaignRouteKind routeKind) {
        this.lastRouteKind = routeKind;
    }

    void setRequirementBrief(CampaignRequirementBrief brief) {
        this.requirementBrief = java.util.Objects.requireNonNull(brief, "brief");
    }

    /** Builds the next transcript turn identity from the current size; deterministic and clock-free. */
    String nextTurnId() {
        return "turn-" + (turns.size() + 1);
    }

    /** A bounded diagnostic: the identities, the revision and the phase, never a value. */
    @Override
    public String toString() {
        return "CampaignAgentSession[conversationId=" + conversationId()
                + ", runId=" + runId()
                + ", revision=" + revision
                + ", phase=" + phase
                + ", turns=" + turns.size() + ']';
    }
}
