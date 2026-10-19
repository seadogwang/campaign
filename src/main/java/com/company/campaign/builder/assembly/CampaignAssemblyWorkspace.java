package com.company.campaign.builder.assembly;

import com.company.campaign.builder.proposal.CampaignProposalPatch;
import com.company.campaign.builder.proposal.JourneyTopologyMutation;
import com.company.campaign.builder.proposal.ProposalLimits;
import com.company.campaign.builder.proposal.ProposalOperation;
import com.company.campaign.builder.proposal.ProposalOperationKind;
import com.company.campaign.builder.proposal.ProposalSchemaVersions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The ephemeral, in-memory, request-scoped Campaign assembly workspace.
 *
 * <p>It is not a repository and holds no Spring, JPA, JDBC or Flyway dependency. It starts from an explicit
 * host-supplied {@link CampaignAssemblyContext}, accepts only a successfully admitted
 * {@link CampaignSkillArtifact} that is bound to the exact conversation/run, Skill ID/version, Tool/catalog
 * hashes and expected revision, and emits exactly one strict {@link CampaignProposalPatch} through
 * {@link #patch()}. Nothing here persists a Draft, applies a Plan, publishes, schedules runtime work or calls a
 * Provider; emitting a Proposal is side-effect-free and is not Save.
 *
 * <p>State is an immutable snapshot plus one revision counter. An acceptance replaces exactly one component
 * atomically: the new snapshot is fully built before any field changes, so a refused acceptance changes neither
 * the revision nor the snapshot. The workspace never duplicates the accepted Capability Pack, composition
 * validator or the accepted compiler's validation: it stages accepted operations and reports bounded
 * missing-dependency codes, and the compiler remains authoritative.
 */
public final class CampaignAssemblyWorkspace {

    /** The bounded maximum number of staged components; the accepted Proposal bound. */
    public static final int MAX_COMPONENTS = ProposalLimits.MAX_OPERATIONS;

    private final CampaignAssemblyContext context;
    private Map<String, CampaignStagedComponent> components = new LinkedHashMap<>();
    private Map<String, CampaignSkillState> observations = new LinkedHashMap<>();
    private JourneyTopologyMutation journeyTopology;
    private long revision;

    public CampaignAssemblyWorkspace(CampaignAssemblyContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    /** The explicit host-supplied context of this workspace. */
    public CampaignAssemblyContext context() {
        return context;
    }

    /** The current revision; it advances by exactly one per accepted step. */
    public long revision() {
        return revision;
    }

    /** The current immutable snapshot of the staged components. */
    public CampaignAssemblySnapshot snapshot() {
        return snapshotOf(revision);
    }

    /**
     * The one topology mutation of the staged Journey bundle, or null when no Journey is staged.
     *
     * <p>It travels with the bundle: accepting a new Journey replaces it in the same atomic step, so an emitted
     * Proposal can never carry a topology that does not belong to the nodes it lists.
     */
    public JourneyTopologyMutation journeyTopology() {
        return journeyTopology;
    }

    /**
     * The current bounded inspection view: Skill/component state, codes, hashes and missing-dependency codes
     * only, with no capability payload and no raw model or Provider output.
     */
    public CampaignAssemblyInspection inspection() {
        List<CampaignStagedComponent> ordered = orderedComponents();
        List<String> missing = CampaignDependencyInspector.inspect(ordered);
        return CampaignAssemblyInspection.of(revision, ordered, skillsOf(ordered, missing), missing);
    }

    /**
     * Emits exactly one strict CampaignProposalPatch for the current snapshot.
     *
     * <p>The operations are ordered by component identity and then by kind, so an identical set of staged
     * artifacts always emits byte-identical Proposal bytes and an identical hash, independent of the order the
     * artifacts were accepted in. A MOVE's meaning is relative, so ordering the operation list cannot change its
     * semantics.
     */
    public CampaignProposalPatch patch() {
        List<ProposalOperation> operations = new ArrayList<>(components.size());
        for (CampaignStagedComponent component : orderedComponents()) {
            operations.add(component.operation());
        }
        return new CampaignProposalPatch(
                ProposalSchemaVersions.CURRENT,
                context.mode(),
                context.campaignId(),
                context.baseRevisions(),
                operations,
                context.clientContext(),
                journeyTopology);
    }

    /**
     * Accepts one admitted Journey bundle as the whole staged Journey of this workspace.
     *
     * <p>Acceptance is one revision-guarded atomic transaction: every Flow node of the bundle and the one
     * topology mutation replace the previous Journey bundle together, or nothing changes at all. Components
     * owned by any other Skill — Message, Promotion, Segment, Schedule and Governance — are never touched, and
     * the previous Journey's own components are removed so a replacement leaves no orphan node behind.
     */
    public CampaignAssemblyAcceptance acceptJourney(
            CampaignJourneyArtifact artifact, long expectedRevision) {
        Objects.requireNonNull(artifact, "artifact");
        String refusal = journeyRefusalFor(artifact, expectedRevision);
        if (refusal != null) {
            return refused(refusal);
        }
        Map<String, CampaignStagedComponent> next = new LinkedHashMap<>();
        for (CampaignStagedComponent component : components.values()) {
            if (!CampaignSkillIds.JOURNEY_FLOW.equals(component.skillId())) {
                next.put(component.componentId(), component);
            }
        }
        for (ProposalOperation operation : artifact.operations()) {
            String componentId = operation.target().value();
            next.put(componentId, new CampaignStagedComponent(
                    componentId, artifact.skillId(), operation.capabilityCode(), operation,
                    List.of(), artifact.artifactHash()));
        }
        Map<String, CampaignSkillState> nextObservations = new LinkedHashMap<>(observations);
        nextObservations.remove(CampaignSkillIds.JOURNEY_FLOW);
        components = next;
        observations = nextObservations;
        journeyTopology = artifact.topologyMutation();
        revision++;
        return accepted();
    }

    /**
     * The refusal rules of one Journey bundle acceptance.
     *
     * <p>Every rule is decided before any state changes, so a stale, foreign, wrong-owner, wrong-tool,
     * wrong-catalog, wrong-version or over-bound bundle changes neither the revision nor the snapshot.
     */
    private String journeyRefusalFor(CampaignJourneyArtifact artifact, long expectedRevision) {
        if (expectedRevision != revision) {
            return CampaignAssemblyErrorCodes.STALE_REVISION;
        }
        if (!context.conversationId().equals(artifact.conversationId())
                || !context.runId().equals(artifact.runId())) {
            return CampaignAssemblyErrorCodes.ARTIFACT_WRONG_RUN;
        }
        if (!context.toolCatalog().catalogHash().equals(artifact.toolCatalogHash())) {
            return CampaignAssemblyErrorCodes.ARTIFACT_CATALOG_MISMATCH;
        }
        if (!CampaignSkillIds.JOURNEY_FLOW.equals(artifact.skillId())
                || !CampaignSkillIds.CURRENT_VERSION.equals(artifact.skillVersion())) {
            return CampaignAssemblyErrorCodes.ARTIFACT_WRONG_SKILL;
        }
        CampaignToolDescriptor descriptor = context.toolCatalog().descriptor(artifact.toolId());
        if (descriptor == null || !CampaignSkillIds.JOURNEY_FLOW.equals(descriptor.owningSkillId())) {
            return CampaignAssemblyErrorCodes.ARTIFACT_WRONG_SKILL;
        }
        if (!descriptor.toolVersion().equals(artifact.toolVersion())) {
            return CampaignAssemblyErrorCodes.TOOL_VERSION_MISMATCH;
        }
        if (!CampaignToolIds.isStageableArtifact(artifact.skillId(), artifact.toolId())) {
            return CampaignAssemblyErrorCodes.ARTIFACT_TOOL_NOT_FINAL;
        }
        if (!artifact.topologyMutation().replaces()) {
            return CampaignAssemblyErrorCodes.ARTIFACT_INVALID;
        }
        Set<String> existing = new LinkedHashSet<>(components.keySet());
        int journeyOnly = 0;
        for (CampaignStagedComponent component : components.values()) {
            if (CampaignSkillIds.JOURNEY_FLOW.equals(component.skillId())) {
                journeyOnly++;
            }
        }
        if (existing.size() - journeyOnly + artifact.operations().size()
                > CampaignAssemblyWorkspace.MAX_COMPONENTS) {
            return CampaignAssemblyErrorCodes.COMPONENT_LIMIT_EXCEEDED;
        }
        Set<String> bundleIds = new LinkedHashSet<>();
        for (ProposalOperation operation : artifact.operations()) {
            if (!CampaignSkillIds.ownsCapability(artifact.skillId(), operation.capabilityCode())) {
                return CampaignAssemblyErrorCodes.ARTIFACT_OWNERSHIP_INVALID;
            }
            if (!bundleIds.add(operation.target().value())) {
                return CampaignAssemblyErrorCodes.IDENTITY_CONFLICT;
            }
            CampaignStagedComponent existingComponent = components.get(operation.target().value());
            if (existingComponent != null
                    && !CampaignSkillIds.JOURNEY_FLOW.equals(existingComponent.skillId())) {
                // A Journey bundle must not silently overwrite a component another Skill owns.
                return CampaignAssemblyErrorCodes.IDENTITY_CONFLICT;
            }
        }
        return null;
    }

    /**
     * Accepts one admitted Skill artifact as the staged component for its identity.
     *
     * <p>An artifact from a different run or catalog, an unknown or mismatched Skill, a capability the Skill
     * does not own, a stale expected revision, a conflicting identity, an unresolved delete/move target, a
     * contradictory operation combination and an over-bound component count are all refused before any state
     * changes.
     */
    public CampaignAssemblyAcceptance accept(CampaignSkillArtifact artifact, long expectedRevision) {
        Objects.requireNonNull(artifact, "artifact");
        String refusal = refusalFor(artifact, expectedRevision);
        if (refusal != null) {
            return refused(refusal);
        }
        String componentId = artifact.componentId();
        Map<String, CampaignStagedComponent> next = new LinkedHashMap<>(components);
        next.put(componentId, new CampaignStagedComponent(
                componentId, artifact.skillId(), artifact.capabilityCode(), artifact.operation(),
                artifact.references(), artifact.artifactHash()));
        Map<String, CampaignSkillState> nextObservations = new LinkedHashMap<>(observations);
        nextObservations.remove(artifact.skillId());
        components = next;
        observations = nextObservations;
        revision++;
        return accepted();
    }

    /**
     * Records a bounded non-READY state for one Skill without staging a component.
     *
     * <p>It exists so a genuine {@code NEEDS_INPUT} or {@code INVALID} terminal is preserved honestly for
     * presentation, and a {@code MISSING} observation clears a previously recorded state. It is revision-guarded
     * exactly like an acceptance, so an optimistic caller cannot record against a stale revision.
     */
    public CampaignAssemblyAcceptance observe(
            String skillId, CampaignComponentState state, String code, long expectedRevision) {
        if (!CampaignSkillIds.isKnown(skillId)) {
            return refused(CampaignAssemblyErrorCodes.ARTIFACT_WRONG_SKILL);
        }
        if (state == null || state == CampaignComponentState.READY) {
            return refused(CampaignAssemblyErrorCodes.REQUEST_INVALID);
        }
        if (code != null && !code.matches("^[A-Z][A-Z0-9_]{0,63}$")) {
            return refused(CampaignAssemblyErrorCodes.REQUEST_INVALID);
        }
        if (expectedRevision != revision) {
            return refused(CampaignAssemblyErrorCodes.STALE_REVISION);
        }
        Map<String, CampaignSkillState> next = new LinkedHashMap<>(observations);
        if (state == CampaignComponentState.MISSING) {
            next.remove(skillId);
        } else {
            next.put(skillId, new CampaignSkillState(skillId, state, code, List.of()));
        }
        observations = next;
        revision++;
        return accepted();
    }

    // ------------------------------------------------------------------ refusal rules

    private String refusalFor(CampaignSkillArtifact artifact, long expectedRevision) {
        if (expectedRevision != revision) {
            return CampaignAssemblyErrorCodes.STALE_REVISION;
        }
        if (!context.conversationId().equals(artifact.conversationId())
                || !context.runId().equals(artifact.runId())) {
            return CampaignAssemblyErrorCodes.ARTIFACT_WRONG_RUN;
        }
        if (!context.toolCatalog().catalogHash().equals(artifact.toolCatalogHash())) {
            return CampaignAssemblyErrorCodes.ARTIFACT_CATALOG_MISMATCH;
        }
        if (!CampaignSkillIds.isKnown(artifact.skillId())
                || !CampaignSkillIds.CURRENT_VERSION.equals(artifact.skillVersion())) {
            return CampaignAssemblyErrorCodes.ARTIFACT_WRONG_SKILL;
        }
        CampaignToolDescriptor descriptor = context.toolCatalog().descriptor(artifact.toolId());
        if (descriptor == null || !descriptor.owningSkillId().equals(artifact.skillId())) {
            return CampaignAssemblyErrorCodes.ARTIFACT_WRONG_SKILL;
        }
        if (!descriptor.toolVersion().equals(artifact.toolVersion())) {
            return CampaignAssemblyErrorCodes.TOOL_VERSION_MISMATCH;
        }
        if (!CampaignToolIds.isStageableArtifact(artifact.skillId(), artifact.toolId())) {
            return CampaignAssemblyErrorCodes.ARTIFACT_TOOL_NOT_FINAL;
        }
        if (CampaignSkillIds.JOURNEY_FLOW.equals(artifact.skillId())) {
            // A Journey is one atomic multi-component bundle plus its topology, so it can never be staged one
            // component at a time: the whole bundle is accepted through its own transaction or not at all.
            return CampaignAssemblyErrorCodes.ARTIFACT_TOOL_NOT_FINAL;
        }

        String capabilityCode = artifact.capabilityCode();
        String componentId = artifact.componentId();
        CampaignStagedComponent existing = components.get(componentId);
        if (existing != null && CampaignSkillIds.JOURNEY_FLOW.equals(existing.skillId())) {
            return CampaignAssemblyErrorCodes.IDENTITY_CONFLICT;
        }

        if (capabilityCode != null) {
            if (!CampaignSkillIds.ownsCapability(artifact.skillId(), capabilityCode)) {
                return CampaignAssemblyErrorCodes.ARTIFACT_OWNERSHIP_INVALID;
            }
            if (existing != null && !Objects.equals(existing.capabilityCode(), capabilityCode)) {
                return CampaignAssemblyErrorCodes.IDENTITY_CONFLICT;
            }
        } else {
            // A delete or a move addresses an object this workspace already holds under the same Skill.
            if (existing == null || !existing.skillId().equals(artifact.skillId())) {
                return CampaignAssemblyErrorCodes.UNRESOLVED_TARGET;
            }
        }

        if (existing != null && contradicts(existing.operationKind(), artifact.operationKind())) {
            return CampaignAssemblyErrorCodes.OPERATION_CONFLICT;
        }
        if (existing == null && components.size() >= MAX_COMPONENTS) {
            return CampaignAssemblyErrorCodes.COMPONENT_LIMIT_EXCEEDED;
        }
        return null;
    }

    private static boolean contradicts(ProposalOperationKind existing, ProposalOperationKind incoming) {
        return (existing == ProposalOperationKind.DELETE && incoming == ProposalOperationKind.MOVE)
                || (existing == ProposalOperationKind.MOVE && incoming == ProposalOperationKind.DELETE);
    }

    // ------------------------------------------------------------------ views

    private CampaignAssemblyAcceptance accepted() {
        return new CampaignAssemblyAcceptance(true, revision, null, snapshot());
    }

    private CampaignAssemblyAcceptance refused(String code) {
        return new CampaignAssemblyAcceptance(false, revision, code, snapshot());
    }

    private CampaignAssemblySnapshot snapshotOf(long revisionOfSnapshot) {
        List<CampaignStagedComponent> ordered = orderedComponents();
        List<String> missing = CampaignDependencyInspector.inspect(ordered);
        return CampaignAssemblySnapshot.of(revisionOfSnapshot, ordered, skillsOf(ordered, missing), missing);
    }

    /** The per-Skill inspection states of one staged component set. */
    private List<CampaignSkillState> skillsOf(
            List<CampaignStagedComponent> ordered, List<String> missingDependencyCodes) {
        List<CampaignSkillState> skills = new ArrayList<>();
        for (String skillId : CampaignSkillIds.ordered()) {
            CampaignSkillState observed = observations.get(skillId);
            boolean ready = ordered.stream().anyMatch(component -> component.skillId().equals(skillId));
            if (ready) {
                skills.add(new CampaignSkillState(
                        skillId, CampaignComponentState.READY, null, missingDependencyCodes));
            } else if (observed != null) {
                skills.add(new CampaignSkillState(
                        skillId, observed.state(), observed.code(), missingDependencyCodes));
            } else {
                skills.add(new CampaignSkillState(
                        skillId, CampaignComponentState.MISSING, null, missingDependencyCodes));
            }
        }
        return List.copyOf(skills);
    }

    private List<CampaignStagedComponent> orderedComponents() {
        List<CampaignStagedComponent> ordered = new ArrayList<>(components.values());
        ordered.sort(Comparator
                .comparing(CampaignStagedComponent::componentId)
                .thenComparing(component -> component.operationKind().name()));
        return List.copyOf(ordered);
    }
}
