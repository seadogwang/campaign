package com.company.campaign.conversation;

import com.company.campaign.builder.assembly.CampaignAssemblyContext;
import com.company.campaign.builder.assembly.CampaignComponentState;
import com.company.campaign.builder.assembly.CampaignComponentView;
import com.company.campaign.builder.assembly.CampaignJourneyArtifact;
import com.company.campaign.builder.assembly.CampaignSkillArtifact;
import com.company.campaign.builder.assembly.CampaignSkillState;
import com.company.campaign.builder.assembly.CampaignToolCatalog;
import com.company.campaign.builder.campaignagent.CampaignAgentPhase;
import com.company.campaign.builder.campaignagent.CampaignAgentSession;
import com.company.campaign.builder.campaignagent.CampaignAgentTurnResult;
import com.company.campaign.builder.campaignagent.CampaignAnswerOption;
import com.company.campaign.builder.campaignagent.CampaignAudienceSummary;
import com.company.campaign.builder.campaignagent.CampaignBranchRule;
import com.company.campaign.builder.campaignagent.CampaignBusinessObjective;
import com.company.campaign.builder.campaignagent.CampaignClarification;
import com.company.campaign.builder.campaignagent.CampaignClarificationAggregate;
import com.company.campaign.builder.campaignagent.CampaignComponentClarification;
import com.company.campaign.builder.campaignagent.CampaignCapability;
import com.company.campaign.builder.campaignagent.CampaignComponentCode;
import com.company.campaign.builder.campaignagent.CampaignComponentManifest;
import com.company.campaign.builder.campaignagent.CampaignConversationProjection;
import com.company.campaign.builder.campaignagent.DecisionSource;
import com.company.campaign.builder.campaignagent.UsageDecision;
import com.company.campaign.builder.campaignagent.CampaignConversationTurn;
import com.company.campaign.builder.campaignagent.CampaignJourneySummary;
import com.company.campaign.builder.campaignagent.BranchDecision;
import com.company.campaign.builder.campaignagent.CampaignCadence;
import com.company.campaign.builder.campaignagent.CampaignComponentTodo;
import com.company.campaign.builder.campaignagent.CampaignExecutionSchedule;
import com.company.campaign.builder.campaignagent.CampaignMessageInputs;
import com.company.campaign.builder.campaignagent.CommunicationDecision;
import com.company.campaign.builder.campaignagent.CampaignRequirementBrief;
import com.company.campaign.builder.campaignagent.CampaignRequirementCode;
import com.company.campaign.builder.campaignagent.CampaignRequirementProgress;
import com.company.campaign.builder.campaignagent.CampaignType;
import com.company.campaign.builder.campaignagent.OfferDecision;
import com.company.campaign.builder.campaignagent.CampaignRouteKind;
import com.company.campaign.builder.campaignagent.CampaignSegmentContinuation;
import com.company.campaign.builder.campaignagent.CampaignSegmentFreeTextRecovery;
import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.builder.compiler.ResolvedCatalogReference;
import com.company.campaign.builder.proposal.CampaignProposalCodec;
import com.company.campaign.builder.proposal.CampaignProposalPatch;
import com.company.campaign.builder.proposal.JourneyTopologyAction;
import com.company.campaign.builder.proposal.JourneyTopologyMutation;
import com.company.campaign.builder.proposal.MoveAnchorKind;
import com.company.campaign.builder.proposal.ProposalBaseRevisions;
import com.company.campaign.builder.proposal.ProposalClientContext;
import com.company.campaign.builder.proposal.ProposalIdentity;
import com.company.campaign.builder.proposal.ProposalMode;
import com.company.campaign.builder.proposal.ProposalMoveAnchor;
import com.company.campaign.builder.proposal.ProposalOperation;
import com.company.campaign.builder.proposal.ProposalOperationKind;
import com.company.campaign.builder.proposal.ProposalTopologyEdge;
import com.company.campaign.contracts.LocaleTag;
import com.company.campaign.journey.graph.JourneyBranchKind;
import com.company.campaign.segment.resolution.ClarificationSelectionReceipt;
import com.company.campaign.segment.resolution.ResolutionId;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The one hand-written, versioned codec of the durable Campaign conversation boundary.
 *
 * <p>It writes a closed, explicit wire document — never a serialized Java object, never a class name and never
 * Jackson default typing — and reads it back through the strict canonical reader, which rejects duplicate object
 * keys at any depth, unknown or alternately-cased members, malformed hashes and malformed enums before any value
 * is bound. An absent optional member is omitted rather than written as JSON {@code null}, so "absent" has
 * exactly one representation and a stored document has exactly one canonical byte form.
 *
 * <p>Every document is sealed with the SHA-256 content hash of its own canonical body, and a decode re-proves
 * three things before it returns: the declared hash matches the body, the body is the document's canonical byte
 * form, and the rebuilt domain value re-encodes to exactly those bytes. The third proof is what makes a restored
 * session genuinely equal to the stored one: a lossy or inventive restore cannot reproduce the snapshot, so it
 * fails closed with {@link ConversationStoreErrorCodes#SNAPSHOT_INTEGRITY} rather than silently handing back a
 * different conversation.
 *
 * <p>Every failure is one stable, non-disclosing {@link ConversationStoreException}: no stored value, member
 * name, identifier, idempotency key or payload fragment is ever echoed, and no raw binder, driver or domain
 * exception text escapes.
 */
public final class CampaignConversationSnapshotCodec {

    /** The one accepted document version. An unknown version is refused rather than guessed. */
    public static final String FORMAT_VERSION = "campaign-conversation-snapshot/3";

    /** The absolute bound on one stored session snapshot document, in bytes. */
    public static final int MAX_SNAPSHOT_BYTES = 1_048_576;

    /** The absolute bound on one stored idempotency outcome document, in bytes. */
    public static final int MAX_OUTCOME_BYTES = 262_144;

    /** The largest number of accepted workspace steps one stored session may hold. */
    public static final int MAX_STEPS = 256;

    /** The largest number of transcript turns one stored session may hold. */
    public static final int MAX_TURNS = 64;

    /** The largest number of resolved catalog references one stored artifact may carry. */
    public static final int MAX_REFERENCES = 1_024;

    /** The largest number of operations one stored Journey bundle may carry. */
    public static final int MAX_OPERATIONS = 256;

    /** The largest number of topology edges one stored Journey bundle may carry. */
    public static final int MAX_TOPOLOGY_EDGES = 256;

    /** The largest number of answer options one stored clarification may carry. */
    public static final int MAX_OPTIONS = 8;

    /** The largest number of replayable receipts one stored Segment continuation may carry. */
    public static final int MAX_RECEIPTS = 16;

    /** The largest number of pending component clarifications one stored session may carry. */
    public static final int MAX_COMPONENT_CLARIFICATIONS = CampaignComponentCode.values().length;

    /** The largest number of component manifests one stored session may carry. */
    public static final int MAX_MANIFESTS = com.company.campaign.builder.campaignagent.CampaignAgentLimits.MAX_MANIFESTS;

    /** The largest accepted length of one free-form stored string. */
    public static final int MAX_TEXT_LENGTH = 1_024;

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();
    private static final String STEP_ACCEPT = "ACCEPT";
    private static final String STEP_ACCEPT_JOURNEY = "ACCEPT_JOURNEY";
    private static final String STEP_OBSERVE = "OBSERVE";

    private final CampaignProposalCodec proposals = new CampaignProposalCodec();

    // ------------------------------------------------------------------ session snapshot

    /** Seals one snapshot into its canonical, hash-pinned document bytes. */
    public byte[] encode(CampaignConversationSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        byte[] body = CANONICAL_JSON.write(toWire(snapshot));
        if (body.length > MAX_SNAPSHOT_BYTES) {
            throw limit("The conversation snapshot exceeds its declared byte bound.");
        }
        return CANONICAL_JSON.write(new WireEnvelope(ContentHash.sha256(body), tree(body)));
    }

    /** Reads one snapshot back, refusing anything that is not the canonical, hash-pinned form it wrote. */
    public CampaignConversationSnapshot decode(byte[] encoded) {
        JsonNode bodyNode = verifyEnvelope(encoded, MAX_SNAPSHOT_BYTES, true);
        byte[] body = CANONICAL_JSON.write(bodyNode);
        WireSnapshot wire = bind(body, WireSnapshot.class);
        if (!FORMAT_VERSION.equals(wire.formatVersion())) {
            throw invalid("The stored conversation snapshot has an unknown format version.");
        }
        enforceBounds(wire);
        CampaignConversationKey key = keyOf(wire.key());
        CampaignAssemblyContext context = contextOf(wire.context(), key);
        CampaignAgentSession.Restore memento = sessionOf(wire.session());
        CampaignConversationSnapshot snapshot =
                new CampaignConversationSnapshot(key, context, wire.workspaceRevision(), memento);
        if (memento != null) {
            reProve(snapshot, context, memento, body);
        }
        return snapshot;
    }

    /**
     * Replays the stored steps into a genuine session and proves the rebuilt snapshot re-encodes byte-for-byte.
     *
     * <p>The rebuilt session must also report exactly the stored workspace revision, so a step sequence that
     * would assemble to a different workspace is refused rather than accepted silently.
     */
    private void reProve(
            CampaignConversationSnapshot snapshot,
            CampaignAssemblyContext context,
            CampaignAgentSession.Restore memento,
            byte[] body) {
        CampaignAgentSession restored = CampaignAgentSession.restore(context, memento);
        if (restored.workspace().revision() != snapshot.workspaceRevision()) {
            throw invalid("A restored conversation workspace contradicts its stored revision.");
        }
        byte[] reencoded = CANONICAL_JSON.write(toWire(CampaignConversationSnapshot.of(snapshot.key(), restored)));
        if (!Arrays.equals(reencoded, body)) {
            throw integrity("A restored conversation session does not re-encode to its stored bytes.");
        }
    }

    // ------------------------------------------------------------------ stored idempotency outcome

    /** Seals one stored idempotency outcome into its canonical, hash-pinned document bytes. */
    public byte[] encodeOutcome(CampaignStoredOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        byte[] body = CANONICAL_JSON.write(new WireOutcome(
                FORMAT_VERSION, outcome.requestFingerprint(), turnResultWire(outcome.result())));
        if (body.length > MAX_OUTCOME_BYTES) {
            throw limit("The stored conversation outcome exceeds its declared byte bound.");
        }
        return CANONICAL_JSON.write(new WireOutcomeEnvelope(ContentHash.sha256(body), tree(body)));
    }

    /** Reads one stored idempotency outcome back, refusing anything but its own canonical form. */
    public CampaignStoredOutcome decodeOutcome(byte[] encoded) {
        JsonNode bodyNode = verifyEnvelope(encoded, MAX_OUTCOME_BYTES, false);
        byte[] body = CANONICAL_JSON.write(bodyNode);
        WireOutcome wire = bind(body, WireOutcome.class);
        if (!FORMAT_VERSION.equals(wire.formatVersion())) {
            throw invalid("The stored conversation outcome has an unknown format version.");
        }
        if (wire.requestFingerprint() == null || wire.result() == null) {
            throw invalid("A stored conversation outcome requires its fingerprint and result.");
        }
        CampaignStoredOutcome outcome =
                new CampaignStoredOutcome(wire.requestFingerprint(), turnResultOf(wire.result()));
        byte[] reencoded = CANONICAL_JSON.write(new WireOutcome(
                FORMAT_VERSION, outcome.requestFingerprint(), turnResultWire(outcome.result())));
        if (!Arrays.equals(reencoded, body)) {
            throw integrity("A restored conversation outcome does not re-encode to its stored bytes.");
        }
        return outcome;
    }

    // ------------------------------------------------------------------ envelope handling

    /** Verifies one sealed document's canonical form and its declared hash, and returns its body node. */
    private static JsonNode verifyEnvelope(byte[] encoded, int maxBytes, boolean snapshot) {
        if (encoded == null || encoded.length == 0) {
            throw invalid("An empty document is not a stored conversation record.");
        }
        if (encoded.length > maxBytes) {
            throw limit("A stored conversation document exceeds its declared byte bound.");
        }
        ContentHash declared;
        JsonNode body;
        if (snapshot) {
            WireEnvelope envelope = bind(encoded, WireEnvelope.class);
            if (!Arrays.equals(CANONICAL_JSON.write(envelope), encoded)) {
                throw integrity("A stored conversation document is not in its canonical byte form.");
            }
            declared = envelope.snapshotHash();
            body = present(envelope.body());
        } else {
            WireOutcomeEnvelope envelope = bind(encoded, WireOutcomeEnvelope.class);
            if (!Arrays.equals(CANONICAL_JSON.write(envelope), encoded)) {
                throw integrity("A stored conversation document is not in its canonical byte form.");
            }
            declared = envelope.outcomeHash();
            body = present(envelope.body());
        }
        if (body == null || !body.isObject()) {
            throw invalid("A stored conversation document requires an object body.");
        }
        if (declared == null) {
            throw invalid("A stored conversation document requires its content hash.");
        }
        if (!declared.matches(CANONICAL_JSON.write(body))) {
            throw integrity("A stored conversation document contradicts its own content hash.");
        }
        return body;
    }

    private static <T> T bind(byte[] body, Class<T> type) {
        try {
            return CANONICAL_JSON.read(new String(body, StandardCharsets.UTF_8), type);
        } catch (RuntimeException failure) {
            throw invalid("A stored conversation document is not a well-formed versioned document.");
        }
    }

    private static JsonNode tree(byte[] canonicalBody) {
        return CANONICAL_JSON.readTree(new String(canonicalBody, StandardCharsets.UTF_8));
    }

    private static JsonNode tree(String canonicalText) {
        return CANONICAL_JSON.readTree(canonicalText);
    }

    private static JsonNode present(JsonNode node) {
        return node == null || node.isNull() ? null : node;
    }

    // ------------------------------------------------------------------ bounds

    private static void enforceBounds(WireSnapshot wire) {
        if (wire.workspaceRevision() < 0 || wire.workspaceRevision() > MAX_STEPS) {
            throw limit("A stored conversation workspace revision is outside its declared bound.");
        }
        WireSession session = wire.session();
        if (session == null) {
            return;
        }
        requireCount(session.steps(), MAX_STEPS, "steps");
        requireCount(session.turns(), MAX_TURNS, "turns");
        if (session.revision() < 0 || session.revision() > MAX_STEPS) {
            throw limit("A stored conversation session revision is outside its declared bound.");
        }
        if (session.turns() != null && session.turns().size() != session.revision() * 2L) {
            throw invalid("A stored conversation transcript contradicts its own revision.");
        }
        requireText(session.followUpPrompt());
        requireText(session.assistantCopy());
        if (session.steps() != null) {
            for (WireStep step : session.steps()) {
                enforceStepBounds(step);
            }
        }
        if (session.clarification() != null) {
            requireCount(session.clarification().options(), MAX_OPTIONS, "clarification.options");
            requireText(session.clarification().prompt());
        }
        if (session.continuation() != null) {
            requireCount(session.continuation().options(), MAX_OPTIONS, "continuation.options");
            requireCount(session.continuation().receipts(), MAX_RECEIPTS, "continuation.receipts");
            requireText(session.continuation().utterance());
            requireText(session.continuation().selectionTarget());
        }
        if (session.segmentFreeTextRecovery() != null) {
            if (session.segmentFreeTextRecovery().recoveryAttempts()
                    < 0 || session.segmentFreeTextRecovery().recoveryAttempts()
                    > CampaignSegmentFreeTextRecovery.MAX_RECOVERY_ATTEMPTS) {
                throw limit("A stored Segment free-text recovery state exceeds its recovery attempt bound.");
            }
        }
        if (session.componentClarifications() != null) {
            requireCount(session.componentClarifications(), MAX_COMPONENT_CLARIFICATIONS,
                    "componentClarifications");
            for (WireComponentClarification clarification : session.componentClarifications()) {
                if (clarification == null) {
                    throw invalid("A stored component clarification is missing.");
                }
                requireCount(clarification.options(), MAX_OPTIONS, "componentClarification.options");
                requireText(clarification.prompt());
            }
        }
        if (session.componentManifests() != null) {
            requireCount(session.componentManifests(), MAX_MANIFESTS, "componentManifests");
            for (WireComponentManifest manifest : session.componentManifests()) {
                if (manifest == null || manifest.capability() == null
                        || manifest.usageDecision() == null || manifest.decisionSource() == null) {
                    throw invalid("A stored component manifest is missing a required member.");
                }
                requireText(manifest.reasonCode());
                requireText(manifest.evidence());
                if (manifest.selectedResourceCodes() != null) {
                    requireCount(manifest.selectedResourceCodes(),
                            com.company.campaign.builder.campaignagent.CampaignAgentLimits.MAX_SELECTED_RESOURCES,
                            "manifest.selectedResourceCodes");
                }
            }
        }
    }

    private static void enforceStepBounds(WireStep step) {
        if (step == null) {
            throw invalid("A stored conversation step is missing.");
        }
        if (step.artifact() != null) {
            requireCount(step.artifact().references(), MAX_REFERENCES, "artifact.references");
            if (step.artifact().operation() == null) {
                throw invalid("A stored artifact step requires its operation.");
            }
        }
        if (step.journey() != null) {
            requireCount(step.journey().references(), MAX_REFERENCES, "journey.references");
            requireCount(step.journey().operations(), MAX_OPERATIONS, "journey.operations");
            if (step.journey().topologyMutation() != null) {
                requireCount(step.journey().topologyMutation().edges(), MAX_TOPOLOGY_EDGES, "topology.edges");
            }
        }
    }

    private static void requireCount(List<?> values, int maximum, String invariant) {
        if (values == null || values.size() > maximum) {
            throw limit("A stored conversation collection exceeds its declared bound: " + invariant);
        }
    }

    private static void requireText(String value) {
        if (value != null && value.length() > MAX_TEXT_LENGTH) {
            throw limit("A stored conversation string exceeds its declared bound.");
        }
    }

    // ------------------------------------------------------------------ wire construction

    private static WireSnapshot toWire(CampaignConversationSnapshot snapshot) {
        CampaignAssemblyContext context = snapshot.assemblyContext();
        CampaignToolCatalog catalog = context.toolCatalog();
        ProposalBaseRevisions base = context.baseRevisions();
        ProposalClientContext client = context.clientContext();
        WireContext wireContext = new WireContext(
                context.mode(), context.campaignId(), base.campaignRevision(),
                base.catalogVersion(), base.catalogHash(), base.policyVersion(), base.policyHash(),
                client.locale(), catalog.catalogVersion(), catalog.catalogHash());
        return new WireSnapshot(
                FORMAT_VERSION,
                new WireKey(snapshot.key().tenantId(), snapshot.key().programId(), snapshot.key().userId(),
                        snapshot.key().conversationId(), snapshot.key().runId()),
                wireContext,
                snapshot.workspaceRevision(),
                sessionWire(snapshot.session()));
    }

    private static WireSession sessionWire(CampaignAgentSession.Restore memento) {
        if (memento == null) {
            return null;
        }
        List<WireStep> steps = new ArrayList<>(memento.steps().size());
        for (CampaignAgentSession.Step step : memento.steps()) {
            steps.add(stepWire(step));
        }
        List<WireTurn> turns = new ArrayList<>(memento.turns().size());
        for (CampaignConversationTurn turn : memento.turns()) {
            turns.add(turnWire(turn));
        }
        ProposalView proposal = proposalWire(memento.proposal(), memento.proposalWorkspaceRevision());
        return new WireSession(
                memento.revision(), memento.phase(), steps, turns,
                clarificationWire(memento.pendingClarification()),
                continuationWire(memento.segmentContinuation()),
                segmentFreeTextRecoveryWire(memento.segmentFreeTextRecovery()),
                proposal.tree(), proposal.workspaceRevision(),
                audienceWire(memento.audience()),
                journeySummaryWire(memento.journeySummary()),
                memento.followUpPrompt(), memento.assistantCopy(), memento.lastRouteKind(),
                requirementBriefWire(memento.requirementBrief()),
                componentClarificationWires(memento.componentClarifications()),
                manifestWires(memento.componentManifests()));
    }

    private static WireRequirementBrief requirementBriefWire(CampaignRequirementBrief brief) {
        CampaignRequirementBrief value = brief == null ? CampaignRequirementBrief.empty() : brief;
        return new WireRequirementBrief(
                value.type(), value.audience(), value.offerDecision(), value.offerDetail(), value.channels(),
                value.message(), value.branchDecision(), value.branchDetail(), value.branchRule(), value.cadence(),
                scheduleWire(value.schedule()), storedCommunicationDecision(value.communicationDecision()),
                value.triggerEventCode(), objectiveWire(value.confirmedObjective()));
    }

    private static WireObjective objectiveWire(CampaignBusinessObjective objective) {
        if (objective == null) {
            return null;
        }
        return new WireObjective(objective.evidence(), objective.acceptedAtRevision(),
                objective.evidenceTurnId());
    }

    private static WireRequirementProgress requirementProgressWire(CampaignRequirementProgress progress) {
        return new WireRequirementProgress(
                progress.type(), progress.audience(), progress.offerDecision(), progress.offerDetail(),
                progress.channels(), progress.message(), progress.branchDecision(), progress.branchDetail(),
                progress.branchRule(), progress.cadence(), scheduleWire(progress.schedule()), progress.missing(),
                progress.complete(), progress.unsupported(),
                storedCommunicationDecision(progress.communicationDecision()), progress.triggerEventCode());
    }

    /** Only NONE needs storage; DEFINED is derivable from facts and UNKNOWN is their legacy absence. */
    private static CommunicationDecision storedCommunicationDecision(CommunicationDecision decision) {
        return decision == CommunicationDecision.NONE ? decision : null;
    }

    /** The bounded schedule evidence in wire form, or null when it holds no fact, so "absent" stays one form. */
    private static WireSchedule scheduleWire(CampaignExecutionSchedule schedule) {
        if (schedule == null || schedule.isEmpty()) {
            return null;
        }
        return new WireSchedule(
                schedule.timeZone(), schedule.runAt(), schedule.localTime(),
                schedule.startDate(), schedule.endDate());
    }

    /** The schedule evidence of one wire schedule, or the empty schedule when the member is absent. */
    private static CampaignExecutionSchedule scheduleOf(WireSchedule wire) {
        if (wire == null) {
            return CampaignExecutionSchedule.empty();
        }
        try {
            return new CampaignExecutionSchedule(
                    wire.timeZone(), wire.runAt(), wire.localTime(), wire.startDate(), wire.endDate());
        } catch (RuntimeException failure) {
            throw invalid("A stored Campaign schedule evidence is malformed.");
        }
    }

    private static WireStep stepWire(CampaignAgentSession.Step step) {
        return switch (step) {
            case CampaignAgentSession.Step.Accept accept ->
                    new WireStep(STEP_ACCEPT, artifactWire(accept.artifact()), null, null, null, null);
            case CampaignAgentSession.Step.AcceptJourney journey ->
                    new WireStep(STEP_ACCEPT_JOURNEY, null, journeyArtifactWire(journey.artifact()),
                            null, null, null);
            case CampaignAgentSession.Step.Observe observe ->
                    new WireStep(STEP_OBSERVE, null, null, observe.skillId(), observe.state(), observe.code());
        };
    }

    private static WireArtifact artifactWire(CampaignSkillArtifact artifact) {
        return new WireArtifact(artifact.conversationId(), artifact.runId(), artifact.skillId(),
                artifact.skillVersion(), artifact.toolId(), artifact.toolVersion(), artifact.toolCatalogHash(),
                operationWire(artifact.operation()), referenceWires(artifact.references()),
                artifact.artifactHash());
    }

    private static WireJourneyArtifact journeyArtifactWire(CampaignJourneyArtifact artifact) {
        List<WireOperation> operations = new ArrayList<>(artifact.operations().size());
        for (ProposalOperation operation : artifact.operations()) {
            operations.add(operationWire(operation));
        }
        return new WireJourneyArtifact(artifact.conversationId(), artifact.runId(), artifact.skillId(),
                artifact.skillVersion(), artifact.toolId(), artifact.toolVersion(), artifact.toolCatalogHash(),
                operations, topologyWire(artifact.topologyMutation()), referenceWires(artifact.references()),
                artifact.artifactHash());
    }

    private static List<WireReference> referenceWires(List<ResolvedCatalogReference> references) {
        List<WireReference> wires = new ArrayList<>(references.size());
        for (ResolvedCatalogReference reference : references) {
            wires.add(new WireReference(reference.referenceCode(), reference.entityKind(),
                    reference.logicalId(), reference.versionId()));
        }
        return wires;
    }

    private static WireOperation operationWire(ProposalOperation operation) {
        ProposalIdentity target = operation.target();
        ProposalMoveAnchor anchor = operation.anchor();
        JsonNode value = null;
        if (operation.value() != null) {
            value = tree(operation.value().canonicalBytes());
        }
        return new WireOperation(
                operation.kind(),
                operation.capabilityCode(),
                target == null ? null : new WireIdentity(target.logicalId(), target.clientRef()),
                value,
                anchor == null ? null : new WireAnchor(anchor.kind(), anchor.target()));
    }

    private static WireTopology topologyWire(JourneyTopologyMutation mutation) {
        if (mutation == null) {
            return null;
        }
        List<WireEdge> edges = new ArrayList<>();
        if (mutation.edges() != null) {
            for (ProposalTopologyEdge edge : mutation.edges()) {
                edges.add(new WireEdge(edge.edgeId(),
                        new WireIdentity(edge.from().logicalId(), edge.from().clientRef()),
                        new WireIdentity(edge.to().logicalId(), edge.to().clientRef()),
                        edge.branch()));
            }
        }
        return new WireTopology(mutation.action(), mutation.topologyFormatVersion(), edges);
    }

    private static WireTurn turnWire(CampaignConversationTurn turn) {
        return new WireTurn(turn.turnId(), turn.role(), turn.ordinal(), turn.text(), turn.at());
    }

    private static WireClarification clarificationWire(CampaignClarification clarification) {
        if (clarification == null) {
            return null;
        }
        return new WireClarification(clarification.skillId(), clarification.code(), clarification.prompt(),
                List.copyOf(clarification.questionCodes()), optionWires(clarification.options()),
                clarification.freeTextAllowed());
    }

    private static List<WireOption> optionWires(List<CampaignAnswerOption> options) {
        List<WireOption> wires = new ArrayList<>(options.size());
        for (CampaignAnswerOption option : options) {
            wires.add(new WireOption(option.optionId(), option.label()));
        }
        return wires;
    }

    /**
     * The host-owned aggregate of pending component clarifications, in its canonical wire form.
     *
     * <p>An empty aggregate is omitted rather than written as an empty array, so "no component question" has
     * exactly one representation and a stored document from before this member existed still decodes and
     * re-encodes byte-for-byte.
     */
    private static List<WireComponentClarification> componentClarificationWires(
            List<CampaignComponentClarification> clarifications) {
        if (clarifications == null || clarifications.isEmpty()) {
            return null;
        }
        List<WireComponentClarification> wires = new ArrayList<>(clarifications.size());
        for (CampaignComponentClarification clarification : clarifications) {
            wires.add(new WireComponentClarification(
                    clarification.component(), clarification.skillId(), clarification.code(),
                    clarification.prompt(), List.copyOf(clarification.questionCodes()),
                    optionWires(clarification.options()), clarification.freeTextAllowed()));
        }
        return wires;
    }

    /**
     * The host-owned component manifests in their canonical wire form.
     *
     * <p>An empty list is omitted rather than written as an empty array, so "no manifest" has exactly
     * one representation and a stored document from before this member existed still decodes and
     * re-encodes byte-for-byte.
     */
    private static List<WireComponentManifest> manifestWires(
            List<CampaignComponentManifest> manifests) {
        if (manifests == null || manifests.isEmpty()) {
            return null;
        }
        List<WireComponentManifest> wires = new ArrayList<>(manifests.size());
        for (CampaignComponentManifest manifest : manifests) {
            wires.add(new WireComponentManifest(
                    manifest.capability(), manifest.usageDecision(), manifest.reasonCode(),
                    manifest.evidence(), manifest.selectedResourceCodes(), manifest.decisionSource()));
        }
        return wires;
    }

    private static List<CampaignComponentManifest> manifestsOf(
            List<WireComponentManifest> wires) {
        if (wires == null || wires.isEmpty()) {
            return List.of();
        }
        List<CampaignComponentManifest> manifests = new ArrayList<>(wires.size());
        for (WireComponentManifest wire : wires) {
            if (wire == null) {
                throw invalid("A stored component manifest is missing.");
            }
            try {
                manifests.add(new CampaignComponentManifest(
                        require(wire.capability()), require(wire.usageDecision()),
                        wire.reasonCode(), wire.evidence(),
                        wire.selectedResourceCodes() == null ? List.of() : wire.selectedResourceCodes(),
                        require(wire.decisionSource())));
            } catch (RuntimeException failure) {
                throw invalid("A stored component manifest is malformed.");
            }
        }
        return List.copyOf(manifests);
    }

    private static List<CampaignComponentClarification> componentClarificationsOf(
            List<WireComponentClarification> wires) {
        List<CampaignComponentClarification> clarifications = new ArrayList<>();
        if (wires == null) {
            return clarifications;
        }
        for (WireComponentClarification wire : wires) {
            if (wire == null) {
                throw invalid("A stored component clarification is missing.");
            }
            try {
                clarifications.add(new CampaignComponentClarification(
                        wire.component(), wire.skillId(), require(wire.code()), wire.prompt(),
                        wire.questionCodes(), optionsOf(wire.options()), wire.freeTextAllowed()));
            } catch (RuntimeException failure) {
                throw invalid("A stored component clarification is malformed.");
            }
        }
        // The rebuilt aggregate re-proves distinct component ownership, so a stored document cannot claim two
        // questions of the same component and cannot name a Skill the component does not own.
        CampaignClarificationAggregate.of(clarifications);
        return clarifications;
    }

    private static WireContinuation continuationWire(CampaignSegmentContinuation continuation) {
        if (continuation == null) {
            return null;
        }
        List<WireReceipt> receipts = new ArrayList<>(continuation.receipts().size());
        for (ClarificationSelectionReceipt receipt : continuation.receipts()) {
            receipts.add(new WireReceipt(receipt.resolutionId().value(), receipt.selectionTarget(),
                    receipt.optionId()));
        }
        return new WireContinuation(continuation.utterance(), receipts,
                continuation.resolutionId().value(), continuation.selectionTarget(),
                continuation.questionCode(), optionWires(continuation.options()),
                continuation.recoveryAttempts());
    }

    private static WireAudience audienceWire(CampaignAudienceSummary audience) {
        if (audience == null) {
            return null;
        }
        return new WireAudience(audience.status(), audience.matchedCount(), audience.asOf(), audience.timezone(),
                audience.dataWatermark(), audience.previewHash());
    }

    private static WireJourneySummary journeySummaryWire(CampaignJourneySummary journey) {
        if (journey == null) {
            return null;
        }
        return new WireJourneySummary(journey.nodeCount(), journey.edgeCount(), journey.entryComponentId(),
                journey.terminalCount(), journey.topologyHash(), journey.artifactHash());
    }

    // ------------------------------------------------------------------ wire reconstruction

    private static CampaignConversationKey keyOf(WireKey wire) {
        if (wire == null) {
            throw invalid("A stored conversation snapshot requires its trusted key.");
        }
        return new CampaignConversationKey(wire.tenantId(), wire.programId(), wire.userId(),
                wire.conversationId(), wire.runId());
    }

    private static CampaignAssemblyContext contextOf(WireContext wire, CampaignConversationKey key) {
        if (wire == null || wire.mode() == null) {
            throw invalid("A stored conversation snapshot requires its assembly context.");
        }
        CampaignToolCatalog standard = CampaignToolCatalog.standard();
        if (!standard.catalogVersion().equals(wire.toolCatalogVersion())
                || !standard.catalogHash().equals(wire.toolCatalogHash())) {
            throw invalid("A stored assembly context pins a Tool catalog this build does not ship.");
        }
        try {
            ProposalBaseRevisions base = new ProposalBaseRevisions(
                    wire.campaignRevision(),
                    require(wire.catalogVersion()),
                    require(wire.catalogHash()),
                    require(wire.policyVersion()),
                    require(wire.policyHash()));
            ProposalClientContext client = new ProposalClientContext(wire.locale());
            return new CampaignAssemblyContext(key.conversationId(), key.runId(), wire.mode(),
                    wire.campaignId(), base, client, standard);
        } catch (RuntimeException failure) {
            throw invalid("A stored assembly context is incoherent with its trusted key.");
        }
    }

    private CampaignAgentSession.Restore sessionOf(WireSession wire) {
        if (wire == null) {
            return null;
        }
        List<CampaignAgentSession.Step> steps = new ArrayList<>();
        if (wire.steps() != null) {
            for (WireStep step : wire.steps()) {
                steps.add(stepOf(step));
            }
        }
        ProposalView proposal = proposalView(wire.proposal(), wire.proposalWorkspaceRevision());
        return new CampaignAgentSession.Restore(
                steps, wire.revision(), require(wire.phase()), turnsOf(wire.turns()),
                clarificationOf(wire.clarification()), continuationOf(wire.continuation()),
                segmentFreeTextRecoveryOf(wire.segmentFreeTextRecovery()),
                proposal.patch(), proposal.workspaceRevisionValue(),
                audienceOf(wire.audience()), journeySummaryOf(wire.journeySummary()),
                wire.followUpPrompt(), wire.assistantCopy(), wire.lastRouteKind(),
                requirementBriefOf(wire.requirementBrief()),
                componentClarificationsOf(wire.componentClarifications()),
                manifestsOf(wire.componentManifests()));
    }

    private static CampaignRequirementBrief requirementBriefOf(WireRequirementBrief wire) {
        if (wire == null) {
            return CampaignRequirementBrief.empty();
        }
        try {
            return new CampaignRequirementBrief(
                    wire.type(), wire.audience(), wire.offerDecision(), wire.offerDetail(),
                    restoredCommunicationDecision(wire.communicationDecision(), wire.channels(), wire.message()),
                    wire.channels(),
                    wire.message(), wire.branchDecision(), wire.branchDetail(), wire.cadence(), wire.branchRule(),
                    scheduleOf(wire.schedule()), wire.triggerEventCode(), objectiveOf(wire.confirmedObjective()));
        } catch (RuntimeException failure) {
            throw invalid("A stored Campaign requirement brief is malformed.");
        }
    }

    private static CampaignBusinessObjective objectiveOf(WireObjective wire) {
        if (wire == null) {
            return null;
        }
        try {
            return new CampaignBusinessObjective(
                    wire.evidence(), wire.acceptedAtRevision(), wire.evidenceTurnId());
        } catch (RuntimeException failure) {
            throw invalid("A stored Campaign business objective is malformed.");
        }
    }

    private static CampaignRequirementProgress requirementProgressOf(
            WireRequirementProgress wire, List<CampaignSkillState> skills) {
        if (wire == null) {
            return CampaignRequirementProgress.from(CampaignRequirementBrief.empty(), skills);
        }
        try {
            // The component TODO is host-derived, so it is re-derived from the stored brief and the stored Skill
            // states rather than restored: a stored derived value could drift from the facts beside it.
            CampaignExecutionSchedule schedule = scheduleOf(wire.schedule());
            CampaignRequirementBrief brief = new CampaignRequirementBrief(
                    wire.type(), wire.audience(), require(wire.offerDecision()), wire.offerDetail(),
                    restoredCommunicationDecision(wire.communicationDecision(), wire.channels(), wire.message()),
                    wire.channels(), wire.message(), require(wire.branchDecision()), wire.branchDetail(),
                    wire.cadence(), wire.branchRule(), schedule, wire.triggerEventCode(), null);
            return new CampaignRequirementProgress(
                    wire.type(), wire.audience(), require(wire.offerDecision()), wire.offerDetail(),
                    brief.communicationDecision(), wire.channels(), wire.message(),
                    require(wire.branchDecision()), wire.branchDetail(),
                    wire.branchRule(), wire.cadence(), schedule, wire.triggerEventCode(),
                    wire.missing(), wire.complete(),
                    wire.unsupported(), CampaignComponentTodo.from(brief, skills),
                    CampaignBusinessObjective.SafeObjectiveStatus.absent());
        } catch (RuntimeException failure) {
            throw invalid("A stored Campaign requirement progress is malformed.");
        }
    }

    private static CommunicationDecision restoredCommunicationDecision(
            CommunicationDecision stored,
            List<CampaignMessageInputs.Channel> channels,
            String message) {
        if (stored != null) {
            return stored;
        }
        return (channels != null && !channels.isEmpty()) || message != null
                ? CommunicationDecision.DEFINED
                : CommunicationDecision.UNKNOWN;
    }

    private static List<CampaignConversationTurn> turnsOf(List<WireTurn> wires) {
        List<CampaignConversationTurn> turns = new ArrayList<>();
        if (wires == null) {
            return turns;
        }
        for (WireTurn turn : wires) {
            if (turn == null) {
                throw invalid("A stored conversation turn is missing.");
            }
            turns.add(new CampaignConversationTurn(
                    require(turn.turnId()), require(turn.role()), turn.ordinal(),
                    require(turn.text()), turn.at()));
        }
        return turns;
    }

    private static CampaignAgentSession.Step stepOf(WireStep wire) {
        if (wire == null || wire.kind() == null) {
            throw invalid("A stored conversation step requires its kind.");
        }
        return switch (wire.kind()) {
            case STEP_ACCEPT -> new CampaignAgentSession.Step.Accept(artifactOf(wire.artifact()));
            case STEP_ACCEPT_JOURNEY ->
                    new CampaignAgentSession.Step.AcceptJourney(journeyArtifactOf(wire.journey()));
            case STEP_OBSERVE -> new CampaignAgentSession.Step.Observe(
                    require(wire.skillId()), require(wire.state()), wire.code());
            default -> throw invalid("A stored conversation step has an unknown kind.");
        };
    }

    private static CampaignSkillArtifact artifactOf(WireArtifact wire) {
        if (wire == null || wire.operation() == null) {
            throw invalid("A stored artifact step requires its operation.");
        }
        CampaignSkillArtifact artifact;
        try {
            artifact = CampaignSkillArtifact.of(
                    require(wire.conversationId()), require(wire.runId()), require(wire.skillId()),
                    require(wire.skillVersion()), require(wire.toolId()), require(wire.toolVersion()),
                    require(wire.toolCatalogHash()), operationOf(wire.operation()),
                    referencesOf(wire.references()));
        } catch (ConversationStoreException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw invalid("A stored Skill artifact is malformed.");
        }
        if (wire.artifactHash() == null || !wire.artifactHash().equals(artifact.artifactHash())) {
            throw integrity("A stored Skill artifact contradicts its own artifact hash.");
        }
        return artifact;
    }

    private static CampaignJourneyArtifact journeyArtifactOf(WireJourneyArtifact wire) {
        if (wire == null || wire.operations() == null || wire.topologyMutation() == null) {
            throw invalid("A stored Journey step requires its operations and topology.");
        }
        List<ProposalOperation> operations = new ArrayList<>(wire.operations().size());
        for (WireOperation operation : wire.operations()) {
            operations.add(operationOf(operation));
        }
        CampaignJourneyArtifact artifact;
        try {
            artifact = CampaignJourneyArtifact.of(
                    require(wire.conversationId()), require(wire.runId()), require(wire.skillId()),
                    require(wire.skillVersion()), require(wire.toolId()), require(wire.toolVersion()),
                    require(wire.toolCatalogHash()), operations, topologyOf(wire.topologyMutation()),
                    referencesOf(wire.references()));
        } catch (ConversationStoreException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw invalid("A stored Journey bundle is malformed.");
        }
        if (wire.artifactHash() == null || !wire.artifactHash().equals(artifact.artifactHash())) {
            throw integrity("A stored Journey bundle contradicts its own artifact hash.");
        }
        return artifact;
    }

    private static List<ResolvedCatalogReference> referencesOf(List<WireReference> wires) {
        List<ResolvedCatalogReference> references = new ArrayList<>();
        if (wires == null) {
            return references;
        }
        for (WireReference reference : wires) {
            if (reference == null) {
                throw invalid("A stored catalog reference is missing.");
            }
            references.add(new ResolvedCatalogReference(
                    require(reference.referenceCode()), require(reference.entityKind()),
                    require(reference.logicalId()), require(reference.versionId())));
        }
        return references;
    }

    private static ProposalOperation operationOf(WireOperation wire) {
        if (wire == null || wire.kind() == null || wire.target() == null) {
            throw invalid("A stored proposal operation requires its kind and target.");
        }
        CapabilityPayload value = null;
        JsonNode node = present(wire.value());
        if (node != null) {
            try {
                value = CapabilityPayload.fromJsonNode(node);
            } catch (RuntimeException failure) {
                throw invalid("A stored proposal operation carries an invalid payload.");
            }
        }
        ProposalIdentity target = identityOf(wire.target());
        ProposalMoveAnchor anchor = wire.anchor() == null ? null : new ProposalMoveAnchor(
                require(wire.anchor().kind()), require(wire.anchor().target()));
        try {
            return new ProposalOperation(wire.kind(), wire.capabilityCode(), target, value, anchor);
        } catch (RuntimeException failure) {
            throw invalid("A stored proposal operation is incoherent with its own kind.");
        }
    }

    private static JourneyTopologyMutation topologyOf(WireTopology wire) {
        if (wire == null || wire.action() == null) {
            throw invalid("A stored Journey topology requires its action.");
        }
        List<ProposalTopologyEdge> edges = new ArrayList<>();
        if (wire.edges() != null) {
            for (WireEdge edge : wire.edges()) {
                if (edge == null) {
                    throw invalid("A stored Journey topology edge is missing.");
                }
                edges.add(new ProposalTopologyEdge(
                        require(edge.edgeId()), identityOf(edge.from()), identityOf(edge.to()),
                        require(edge.branch())));
            }
        }
        try {
            return new JourneyTopologyMutation(wire.action(), wire.topologyFormatVersion(), edges);
        } catch (RuntimeException failure) {
            throw invalid("A stored Journey topology is incoherent with its own action.");
        }
    }

    private static ProposalIdentity identityOf(WireIdentity wire) {
        if (wire == null) {
            throw invalid("A stored proposal identity is missing.");
        }
        try {
            return new ProposalIdentity(wire.logicalId(), wire.clientRef());
        } catch (RuntimeException failure) {
            throw invalid("A stored proposal identity is malformed.");
        }
    }

    private static CampaignClarification clarificationOf(WireClarification wire) {
        if (wire == null) {
            return null;
        }
        try {
            return new CampaignClarification(wire.skillId(), require(wire.code()), wire.prompt(),
                    wire.questionCodes(), optionsOf(wire.options()), wire.freeTextAllowed());
        } catch (RuntimeException failure) {
            throw invalid("A stored clarification is malformed.");
        }
    }

    private static List<CampaignAnswerOption> optionsOf(List<WireOption> wires) {
        List<CampaignAnswerOption> options = new ArrayList<>();
        if (wires == null) {
            return options;
        }
        for (WireOption option : wires) {
            if (option == null) {
                throw invalid("A stored answer option is missing.");
            }
            options.add(new CampaignAnswerOption(require(option.optionId()), require(option.label())));
        }
        return options;
    }

    private static CampaignSegmentContinuation continuationOf(WireContinuation wire) {
        if (wire == null) {
            return null;
        }
        List<ClarificationSelectionReceipt> receipts = new ArrayList<>();
        if (wire.receipts() != null) {
            for (WireReceipt receipt : wire.receipts()) {
                if (receipt == null) {
                    throw invalid("A stored continuation receipt is missing.");
                }
                receipts.add(new ClarificationSelectionReceipt(
                        new ResolutionId(require(receipt.resolutionId())),
                        require(receipt.selectionTarget()), require(receipt.optionId())));
            }
        }
        try {
            return new CampaignSegmentContinuation(
                    require(wire.utterance()), receipts,
                    new ResolutionId(require(wire.resolutionId())),
                    require(wire.selectionTarget()), require(wire.questionCode()), optionsOf(wire.options()),
                    wire.recoveryAttempts());
        } catch (RuntimeException failure) {
            throw invalid("A stored Segment continuation is malformed.");
        }
    }

    /** Encodes a free-text Segment recovery state to its wire form, or null when absent. */
    private static WireSegmentFreeTextRecovery segmentFreeTextRecoveryWire(
            CampaignSegmentFreeTextRecovery recovery) {
        if (recovery == null) {
            return null;
        }
        return new WireSegmentFreeTextRecovery(
                recovery.diagnosticCode(), recovery.questionCode(), recovery.recoveryAttempts());
    }

    /** Reconstructs a free-text Segment recovery state from its wire form, or null when absent. */
    private static CampaignSegmentFreeTextRecovery segmentFreeTextRecoveryOf(
            WireSegmentFreeTextRecovery wire) {
        if (wire == null) {
            return null;
        }
        require(wire.diagnosticCode());
        require(wire.questionCode());
        try {
            return new CampaignSegmentFreeTextRecovery(
                    wire.diagnosticCode(), wire.questionCode(), wire.recoveryAttempts());
        } catch (RuntimeException failure) {
            throw invalid("A stored Segment free-text recovery state is malformed.");
        }
    }

    private static CampaignAudienceSummary audienceOf(WireAudience wire) {
        if (wire == null) {
            return null;
        }
        try {
            return new CampaignAudienceSummary(wire.status(), wire.matchedCount(), wire.asOf(),
                    wire.timezone(), wire.dataWatermark(), require(wire.previewHash()));
        } catch (RuntimeException failure) {
            throw invalid("A stored audience summary is malformed.");
        }
    }

    private static CampaignJourneySummary journeySummaryOf(WireJourneySummary wire) {
        if (wire == null) {
            return null;
        }
        try {
            return new CampaignJourneySummary(wire.nodeCount(), wire.edgeCount(), wire.entryComponentId(),
                    wire.terminalCount(), require(wire.topologyHash()), require(wire.artifactHash()));
        } catch (RuntimeException failure) {
            throw invalid("A stored Journey summary is malformed.");
        }
    }

    // ------------------------------------------------------------------ outcome wire

    private WireTurnResult turnResultWire(CampaignAgentTurnResult result) {
        return new WireTurnResult(result.accepted(), result.code(), projectionWire(result.projection()));
    }

    private WireProjection projectionWire(CampaignConversationProjection projection) {
        List<WireTurn> turns = new ArrayList<>(projection.turns().size());
        for (CampaignConversationTurn turn : projection.turns()) {
            turns.add(turnWire(turn));
        }
        List<WireSkillState> skills = new ArrayList<>(projection.skills().size());
        for (CampaignSkillState state : projection.skills()) {
            skills.add(new WireSkillState(state.skillId(), state.state(), state.code(),
                    List.copyOf(state.missingDependencyCodes())));
        }
        List<WireComponent> components = new ArrayList<>(projection.components().size());
        for (CampaignComponentView component : projection.components()) {
            components.add(new WireComponent(component.componentId(), component.skillId(),
                    component.capabilityCode(), component.operationKind(), component.artifactHash()));
        }
        ProposalView proposal = proposalWire(projection.proposal(), 0L);
        return new WireProjection(projection.conversationId(), projection.runId(), projection.revision(),
                projection.phase(), projection.disposition(), projection.routeKind(), turns, skills, components,
                clarificationWire(projection.clarification()), projection.assistantCopy(),
                audienceWire(projection.audience()), projection.inspectionHash(),
                proposal.tree(), projection.proposalHash(), projection.followUpPrompt(),
                journeySummaryWire(projection.journey()), requirementProgressWire(projection.requirements()),
                componentClarificationWires(projection.componentIssues()),
                manifestWires(projection.componentManifests()));
    }

    private CampaignAgentTurnResult turnResultOf(WireTurnResult wire) {
        if (wire == null || wire.projection() == null) {
            throw invalid("A stored turn result requires its projection.");
        }
        try {
            return new CampaignAgentTurnResult(wire.accepted(), wire.code(), projectionOf(wire.projection()));
        } catch (ConversationStoreException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw invalid("A stored turn result is malformed.");
        }
    }

    private CampaignConversationProjection projectionOf(WireProjection wire) {
        List<CampaignSkillState> skills = new ArrayList<>();
        if (wire.skills() != null) {
            for (WireSkillState state : wire.skills()) {
                if (state == null) {
                    throw invalid("A stored Skill state is missing.");
                }
                skills.add(new CampaignSkillState(
                        require(state.skillId()), require(state.state()), state.code(),
                        state.missingDependencyCodes() == null ? List.of()
                                : List.copyOf(state.missingDependencyCodes())));
            }
        }
        List<CampaignComponentView> components = new ArrayList<>();
        if (wire.components() != null) {
            for (WireComponent component : wire.components()) {
                if (component == null) {
                    throw invalid("A stored component view is missing.");
                }
                components.add(new CampaignComponentView(
                        require(component.componentId()), require(component.skillId()),
                        component.capabilityCode(), require(component.operationKind()),
                        require(component.artifactHash())));
            }
        }
        ProposalView proposal = proposalView(wire.proposal(), null);
        try {
            return new CampaignConversationProjection(
                    require(wire.conversationId()), require(wire.runId()), wire.revision(),
                    require(wire.phase()), require(wire.disposition()), wire.routeKind(),
                    turnsOf(wire.turns()), skills, components,
                    clarificationOf(wire.clarification()), wire.assistantCopy(), audienceOf(wire.audience()),
                    require(wire.inspectionHash()), proposal.patch(), wire.proposalHash(),
                    wire.followUpPrompt(), journeySummaryOf(wire.journey()),
                    requirementProgressOf(wire.requirements(), skills),
                    componentClarificationsOf(wire.componentClarifications()),
                    manifestsOf(wire.componentManifests()));
        } catch (ConversationStoreException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw invalid("A stored conversation projection is malformed.");
        }
    }

    // ------------------------------------------------------------------ proposal handling

    /**
     * One Campaign Proposal in its canonical wire form.
     *
     * <p>A session stores the Proposal together with the workspace revision it was emitted from; a turn result
     * stores only the Proposal itself, so the workspace revision member is absent there.
     */
    private record ProposalView(CampaignProposalPatch patch, JsonNode tree, Long workspaceRevision) {

        /** The stored workspace revision, or -1 when no Proposal was emitted. */
        private long workspaceRevisionValue() {
            return workspaceRevision == null ? -1L : workspaceRevision;
        }
    }

    private static ProposalView proposalWire(CampaignProposalPatch patch, long workspaceRevision) {
        if (patch == null) {
            return new ProposalView(null, null, null);
        }
        return new ProposalView(patch, tree(patch.canonicalText()), Long.valueOf(workspaceRevision));
    }

    private ProposalView proposalView(JsonNode node, Long workspaceRevision) {
        JsonNode body = present(node);
        if (body == null) {
            return new ProposalView(null, null, null);
        }
        CampaignProposalPatch patch;
        try {
            patch = proposals.parse(body.toString());
        } catch (RuntimeException failure) {
            throw invalid("A stored Campaign Proposal is malformed.");
        }
        if (workspaceRevision == null) {
            return new ProposalView(patch, body, null);
        }
        return new ProposalView(patch, body, require(workspaceRevision));
    }

    // ------------------------------------------------------------------ small helpers

    private static <T> T require(T value) {
        if (value == null) {
            throw invalid("A stored conversation document is missing a required member.");
        }
        return value;
    }

    private static ConversationStoreException invalid(String message) {
        return ConversationStoreException.invalid(
                ConversationStoreErrorCodes.SNAPSHOT_INVALID, "snapshot", message);
    }

    private static ConversationStoreException limit(String message) {
        return ConversationStoreException.invalid(
                ConversationStoreErrorCodes.SNAPSHOT_LIMIT_EXCEEDED, "snapshot", message);
    }

    private static ConversationStoreException integrity(String message) {
        return ConversationStoreException.invalid(
                ConversationStoreErrorCodes.SNAPSHOT_INTEGRITY, "snapshot", message);
    }

    // ------------------------------------------------------------------ closed wire records

    /** One sealed conversation session document: the content hash and the canonical body it covers. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WireEnvelope(ContentHash snapshotHash, JsonNode body) {
    }

    /** One sealed idempotency-outcome document. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WireOutcomeEnvelope(ContentHash outcomeHash, JsonNode body) {
    }

    /** The trusted boundary of one stored conversation. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WireKey(
            String tenantId, String programId, String userId, String conversationId, String runId) {
    }

    /** The host-supplied assembly pins, without the derived Tool catalog object. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WireContext(
            ProposalMode mode,
            String campaignId,
            Long campaignRevision,
            String catalogVersion,
            ContentHash catalogHash,
            String policyVersion,
            ContentHash policyHash,
            LocaleTag locale,
            String toolCatalogVersion,
            ContentHash toolCatalogHash) {
    }

    /** One versioned session snapshot document. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WireSnapshot(
            String formatVersion, WireKey key, WireContext context, long workspaceRevision,
            WireSession session) {
    }

    /** The restorable facts of one accepted session. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WireSession(
            long revision,
            CampaignAgentPhase phase,
            List<WireStep> steps,
            List<WireTurn> turns,
            WireClarification clarification,
            WireContinuation continuation,
            WireSegmentFreeTextRecovery segmentFreeTextRecovery,
            JsonNode proposal,
            Long proposalWorkspaceRevision,
            WireAudience audience,
            WireJourneySummary journeySummary,
            String followUpPrompt,
            String assistantCopy,
            CampaignRouteKind lastRouteKind,
            WireRequirementBrief requirementBrief,
            List<WireComponentClarification> componentClarifications,
            List<WireComponentManifest> componentManifests) {
    }

    /**
     * One pending component-scoped clarification request.
     *
     * <p>It keeps the originating component and Skill, so a restored session resumes exactly the component
     * agents that asked. Every member is optional and omitted when absent, so a stored document from before this
     * member existed still decodes and re-encodes byte-for-byte.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WireComponentClarification(
            CampaignComponentCode component,
            String skillId,
            String code,
            String prompt,
            List<String> questionCodes,
            List<WireOption> options,
            boolean freeTextAllowed) {
    }

    /** One component manifest in its closed wire form.
     *
     * <p>Every member is optional and omitted when absent, so a stored document from before this
     * member existed still decodes and re-encodes byte-for-byte.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WireComponentManifest(
            CampaignCapability capability,
            UsageDecision usageDecision,
            String reasonCode,
            String evidence,
            List<String> selectedResourceCodes,
            DecisionSource decisionSource) {
    }

    /** One accepted workspace step. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WireStep(
            String kind, WireArtifact artifact, WireJourneyArtifact journey, String skillId,
            CampaignComponentState state, String code) {
    }

    /** One accepted single-component Skill artifact. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WireArtifact(
            String conversationId, String runId, String skillId, String skillVersion, String toolId,
            String toolVersion, ContentHash toolCatalogHash, WireOperation operation,
            List<WireReference> references, ContentHash artifactHash) {
    }

    /** One accepted Journey bundle. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WireJourneyArtifact(
            String conversationId, String runId, String skillId, String skillVersion, String toolId,
            String toolVersion, ContentHash toolCatalogHash, List<WireOperation> operations,
            WireTopology topologyMutation, List<WireReference> references, ContentHash artifactHash) {
    }

    /** One proposal operation, in its closed wire form. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WireOperation(
            ProposalOperationKind kind, String capabilityCode, WireIdentity target, JsonNode value,
            WireAnchor anchor) {
    }

    /** One proposal identity, in whichever single form it takes. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WireIdentity(String logicalId, String clientRef) {
    }

    /** One relative move anchor. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WireAnchor(MoveAnchorKind kind, String target) {
    }

    /** One Journey topology mutation. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WireTopology(
            JourneyTopologyAction action, String topologyFormatVersion, List<WireEdge> edges) {
    }

    /** One Journey topology edge. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WireEdge(String edgeId, WireIdentity from, WireIdentity to, JourneyBranchKind branch) {
    }

    /** One resolved catalog reference. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WireReference(String referenceCode, String entityKind, String logicalId, String versionId) {
    }

    /** One accepted transcript turn. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WireTurn(String turnId, String role, int ordinal, String text, String at) {
    }

    /** One bounded answer option. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WireOption(String optionId, String label) {
    }

    /** One pending typed clarification. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WireClarification(
            String skillId, String code, String prompt, List<String> questionCodes, List<WireOption> options,
            boolean freeTextAllowed) {
    }

    /** One replayable Segment clarification continuation. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WireContinuation(
            String utterance, List<WireReceipt> receipts, String resolutionId, String selectionTarget,
            String questionCode, List<WireOption> options, int recoveryAttempts) {
    }

    /** One free-text Segment recovery state for initial model-output rejection. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WireSegmentFreeTextRecovery(
            String diagnosticCode, String questionCode, int recoveryAttempts) {
    }

    /** One replayable clarification selection receipt. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WireReceipt(String resolutionId, String selectionTarget, String optionId) {
    }

    /** One bounded audience summary. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WireAudience(
            String status, int matchedCount, String asOf, String timezone, String dataWatermark,
            ContentHash previewHash) {
    }

    /** One bounded Journey summary. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WireJourneySummary(
            int nodeCount, int edgeCount, String entryComponentId, int terminalCount,
            ContentHash topologyHash, ContentHash artifactHash) {
    }

    /**
     * One confirmed business objective in its closed wire form.
     *
     * <p>Every member is optional and omitted when absent, so a stored document from before this member existed
     * still decodes and re-encodes byte-for-byte.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WireObjective(String evidence, long acceptedAtRevision, String evidenceTurnId) {
    }

    /** The persisted accumulated Campaign requirement brief. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WireRequirementBrief(
            CampaignType type,
            String audience,
            OfferDecision offerDecision,
            String offerDetail,
            List<CampaignMessageInputs.Channel> channels,
            String message,
            BranchDecision branchDecision,
            String branchDetail,
            CampaignBranchRule branchRule,
            CampaignCadence cadence,
            WireSchedule schedule,
            CommunicationDecision communicationDecision,
            String triggerEventCode,
            WireObjective confirmedObjective) {
    }

    /** The persisted requirement progress carried by an idempotent turn outcome. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WireRequirementProgress(
            CampaignType type,
            String audience,
            OfferDecision offerDecision,
            String offerDetail,
            List<CampaignMessageInputs.Channel> channels,
            String message,
            BranchDecision branchDecision,
            String branchDetail,
            CampaignBranchRule branchRule,
            CampaignCadence cadence,
            WireSchedule schedule,
            List<CampaignRequirementCode> missing,
            boolean complete,
            boolean unsupported,
            CommunicationDecision communicationDecision,
            String triggerEventCode) {
    }

    /**
     * The persisted accepted schedule evidence of a Campaign requirement.
     *
     * <p>Every member is optional and omitted when absent, so a stored document without evidence has exactly one
     * representation and an older stored document that predates this member still decodes and re-encodes
     * byte-for-byte.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WireSchedule(
            String timeZone, String runAt, String localTime, String startDate, String endDate) {
    }

    /** One stored idempotency outcome. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WireOutcome(String formatVersion, ContentHash requestFingerprint, WireTurnResult result) {
    }

    /** One stored turn result. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WireTurnResult(boolean accepted, String code, WireProjection projection) {
    }

    /** One stored conversation projection. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WireProjection(
            String conversationId,
            String runId,
            long revision,
            CampaignAgentPhase phase,
            String disposition,
            CampaignRouteKind routeKind,
            List<WireTurn> turns,
            List<WireSkillState> skills,
            List<WireComponent> components,
            WireClarification clarification,
            String assistantCopy,
            WireAudience audience,
            ContentHash inspectionHash,
            JsonNode proposal,
            ContentHash proposalHash,
            String followUpPrompt,
            WireJourneySummary journey,
            WireRequirementProgress requirements,
            List<WireComponentClarification> componentClarifications,
            List<WireComponentManifest> componentManifests) {
    }

    /** One bounded Skill state. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WireSkillState(
            String skillId, CampaignComponentState state, String code, List<String> missingDependencyCodes) {
    }

    /** One bounded component inspection fact. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WireComponent(
            String componentId, String skillId, String capabilityCode, String operationKind,
            ContentHash artifactHash) {
    }
}
