package com.company.campaign.builder.agent.testsupport;

import com.company.campaign.builder.agent.SegmentAgentAdapter;
import com.company.campaign.builder.agent.SegmentAgentEnvironment;
import com.company.campaign.builder.agent.SegmentAgentRequest;
import com.company.campaign.builder.agent.SegmentAgentResult;
import com.company.campaign.builder.agent.SegmentAuditSink;
import com.company.campaign.builder.agent.SegmentRunTimings;
import com.company.campaign.builder.segment.SegmentIntentModelPort;
import com.company.campaign.builder.segment.testsupport.SliceTestFixtures;
import com.company.campaign.builder.skill.SegmentSkillDefinition;
import com.company.campaign.builder.skill.SegmentSkillLoader;
import com.company.campaign.builder.tool.HostSafePreviewPort;
import com.company.campaign.builder.tool.SegmentPreviewPort;
import com.company.campaign.contracts.AgentEnvelope;
import com.company.campaign.contracts.ContractVersions;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.metadata.repository.InMemoryMetadataSnapshotRepository;
import com.company.campaign.metadata.service.MetadataQueryService;
import com.company.campaign.preview.PreviewExecutionBackend;
import com.company.campaign.preview.PreviewPolicy;
import com.company.campaign.preview.PreviewPresentation;
import com.company.campaign.preview.PreviewResponse;
import com.company.campaign.preview.PreviewSampleHasher;
import com.company.campaign.preview.SegmentPreviewService;
import com.company.campaign.preview.testsupport.PreviewTestSupport;
import com.company.campaign.preview.testsupport.RecordingTrustedContextProvider;
import com.company.campaign.preview.testsupport.StubPreviewBackend;
import com.company.campaign.security.authorization.AuthorizationDecision;
import com.company.campaign.security.authorization.AuthorizationRequest;
import com.company.campaign.security.authorization.AuthorizationService;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic T11 formal-Agent fixtures built only from accepted public contracts and the accepted
 * component test support. No clock, network, database, credential, random identifier or corpus asset is used;
 * the model port and the read-only Preview backend are the only stubs, exactly as the task allows.
 */
public final class AgentTestFixtures {

    /** The deterministic member identifiers the stub read-only Preview backend matches. */
    public static final List<String> MEMBERS = List.of("member-1", "member-2", "member-3");

    public static final String AGENT_RUN_ID = "agent-run-1";
    public static final String CONVERSATION_ID = "conversation-1";

    private AgentTestFixtures() {
    }

    public static SegmentSkillDefinition skill() {
        return new SegmentSkillLoader().loadDefault();
    }

    public static TrustedExecutionContext context() {
        return SliceTestFixtures.context();
    }

    public static MetadataSnapshot snapshot() {
        return SliceTestFixtures.snapshot();
    }

    public static MetadataSnapshot ambiguousSnapshot() {
        return SliceTestFixtures.ambiguousSpendSnapshot();
    }

    /** A repository holding the exact snapshot the run pins. */
    public static InMemoryMetadataSnapshotRepository repository(MetadataSnapshot snapshot) {
        InMemoryMetadataSnapshotRepository repository = new InMemoryMetadataSnapshotRepository();
        repository.publish(snapshot);
        return repository;
    }

    /** The genuine T09C Preview service over a stub read-only backend and the exact trusted context. */
    public static SegmentPreviewService previewService(
            MetadataSnapshot snapshot,
            TrustedExecutionContext context,
            AuthorizationService authorizationService,
            PreviewSampleHasher hasher) {
        RecordingTrustedContextProvider provider = RecordingTrustedContextProvider.of(context);
        StubPreviewBackend backend = new StubPreviewBackend((plan, runContext) -> StubPreviewBackend.coherent(
                plan, runContext, MEMBERS, "preview-stub-backend", StubPreviewBackend.DEFAULT_VERSION));
        return PreviewTestSupport.service(
                provider, repository(snapshot), backend, authorizationService, PreviewTestSupport.policy(), hasher);
    }

    public static SegmentPreviewPort previewPort(
            MetadataSnapshot snapshot, TrustedExecutionContext context, AuthorizationService authorizationService) {
        return new HostSafePreviewPort(previewService(
                snapshot, context, authorizationService, PreviewTestSupport.hasher(PreviewTestSupport.KEY_A)));
    }

    /** A typed Preview port over an explicit read-only backend, for backend-failure tests. */
    public static SegmentPreviewPort previewPortWithBackend(
            MetadataSnapshot snapshot,
            TrustedExecutionContext context,
            AuthorizationService authorizationService,
            PreviewExecutionBackend backend) {
        RecordingTrustedContextProvider provider = RecordingTrustedContextProvider.of(context);
        return new HostSafePreviewPort(PreviewTestSupport.service(
                provider, repository(snapshot), backend, authorizationService, PreviewTestSupport.policy(),
                PreviewTestSupport.hasher(PreviewTestSupport.KEY_A)));
    }

    /** The fully defaulted environment: allow-all authorization, no audit sink and no timing port. */
    public static SegmentAgentEnvironment environment(
            SegmentIntentModelPort port, MetadataSnapshot snapshot, TrustedExecutionContext context) {
        return environment(port, snapshot, context, SegmentValidationFixtures.allowAll(),
                previewPort(snapshot, context, SegmentValidationFixtures.allowAll()), null, null);
    }

    public static SegmentAgentEnvironment environment(
            SegmentIntentModelPort port,
            MetadataSnapshot snapshot,
            TrustedExecutionContext context,
            AuthorizationService authorizationService,
            SegmentPreviewPort previewPort,
            SegmentAuditSink auditSink,
            SegmentRunTimings timings) {
        return environment(skill(), port, snapshot, context, authorizationService, previewPort, auditSink, timings);
    }

    /** An environment over an explicitly injected Skill, for the compatibility-preflight tests. */
    public static SegmentAgentEnvironment environment(
            SegmentSkillDefinition skill,
            SegmentIntentModelPort port,
            MetadataSnapshot snapshot,
            TrustedExecutionContext context,
            AuthorizationService authorizationService,
            SegmentPreviewPort previewPort,
            SegmentAuditSink auditSink,
            SegmentRunTimings timings) {
        return SegmentAgentEnvironment.of(
                skill,
                RecordingTrustedContextProvider.of(context),
                runContext -> snapshot,
                new MetadataQueryService(authorizationService, repository(snapshot)),
                authorizationService,
                port,
                SliceTestFixtures.policy(),
                SliceTestFixtures.registry(),
                SliceTestFixtures.validationPolicy(),
                previewPort,
                auditSink,
                timings);
    }

    /** The default environment over the standard snapshot and context. */
    public static SegmentAgentEnvironment environment(SegmentIntentModelPort port) {
        return environment(port, snapshot(), context());
    }

    public static AgentEnvelope<SegmentAgentRequest> envelope(SegmentAgentRequest request) {
        return new AgentEnvelope<>(ContractVersions.CURRENT, AGENT_RUN_ID, CONVERSATION_ID, request);
    }

    public static AgentEnvelope<SegmentAgentRequest> envelope(String runId, String conversationId,
            SegmentAgentRequest request) {
        return new AgentEnvelope<>(ContractVersions.CURRENT, runId, conversationId, request);
    }

    /** Runs one request against one environment. */
    public static SegmentAgentResult run(SegmentAgentEnvironment environment, SegmentAgentRequest request) {
        return new SegmentAgentAdapter(environment).invoke(envelope(request));
    }

    /** A deterministic stub model port returning one fixed response. */
    public static SliceTestFixtures.StubModelPort portReturning(String json) {
        return SliceTestFixtures.StubModelPort.returning(json.getBytes(StandardCharsets.UTF_8));
    }

    /** A deterministic stub model port that always fails. */
    public static SegmentIntentModelPort failingPort() {
        return SliceTestFixtures.StubModelPort.throwing();
    }

    /** The Preview policy used by the fixtures, exposed for assertions. */
    public static PreviewPolicy policy() {
        return PreviewTestSupport.policy();
    }

    /**
     * A typed Preview port that records the host-owned presentation it was called with, so a test can prove the
     * Skill's sample prohibition reached the genuine T09C boundary.
     */
    public static final class RecordingPreviewPort implements SegmentPreviewPort {

        private final SegmentPreviewPort delegate;
        private final List<PreviewPresentation> presentations = new ArrayList<>();

        public RecordingPreviewPort(SegmentPreviewPort delegate) {
            this.delegate = delegate;
        }

        @Override
        public PreviewResponse preview(SegmentDsl document, PreviewPresentation presentation) {
            presentations.add(presentation);
            return delegate.preview(document, presentation);
        }

        /** Every presentation this port received, in call order. */
        public List<PreviewPresentation> presentations() {
            return List.copyOf(presentations);
        }
    }

    /** A typed Preview port that always returns one fixed response, for incoherence tests. */
    public static SegmentPreviewPort previewPortReturning(PreviewResponse response) {
        return (document, presentation) -> response;
    }

    // ------------------------------------------------------------------ Skill mutations for preflight tests

    /** Re-publishes the committed Skill with an explicitly replaced ordered Tool allowlist. */
    public static SegmentSkillDefinition skillWithTools(List<SegmentSkillDefinition.ToolPin> tools) {
        SegmentSkillDefinition base = skill();
        return SegmentSkillDefinition.publish(
                base.schemaVersion(), base.skillId(), base.skillVersion(), base.capabilityState(),
                base.evidenceClass(), base.contractPins(), tools, base.inputDispositions(),
                base.outputDispositions(), base.terminalStates(), base.limits(), base.dataClassification(),
                base.sampleOutputPermitted(), base.ambiguityStopsAt(), base.failureBehavior());
    }

    /** The committed Tool pins, in order, for building mutated allowlists. */
    public static List<SegmentSkillDefinition.ToolPin> committedToolPins() {
        return skill().tools();
    }

    /** A Tool pin at the server-owned version with an explicit rank and payload bound. */
    public static SegmentSkillDefinition.ToolPin pin(String toolId, int rank) {
        return new SegmentSkillDefinition.ToolPin(toolId, "1.0", rank, 4096);
    }

    /** Re-publishes the committed Skill with an exactly replaced step/Tool-call bound. */
    public static SegmentSkillDefinition skillWithLimits(int maxSteps, int maxToolCalls) {
        SegmentSkillDefinition base = skill();
        SegmentSkillDefinition.Bounds bounds = new SegmentSkillDefinition.Bounds(
                maxSteps, maxToolCalls, base.limits().maxToolPayloadBytes(), base.limits().maxModelOutputBytes(),
                base.limits().maxUtteranceLength(), base.limits().maxConversationTurns());
        return SegmentSkillDefinition.publish(
                base.schemaVersion(), base.skillId(), base.skillVersion(), base.capabilityState(),
                base.evidenceClass(), base.contractPins(), base.tools(), base.inputDispositions(),
                base.outputDispositions(), base.terminalStates(), bounds, base.dataClassification(),
                base.sampleOutputPermitted(), base.ambiguityStopsAt(), base.failureBehavior());
    }

    /** Re-publishes the committed Skill with an explicitly replaced terminal-state vocabulary. */
    public static SegmentSkillDefinition skillWithTerminalStates(List<String> terminalStates) {
        SegmentSkillDefinition base = skill();
        return SegmentSkillDefinition.publish(
                base.schemaVersion(), base.skillId(), base.skillVersion(), base.capabilityState(),
                base.evidenceClass(), base.contractPins(), base.tools(), base.inputDispositions(),
                base.outputDispositions(), terminalStates, base.limits(), base.dataClassification(),
                base.sampleOutputPermitted(), base.ambiguityStopsAt(), base.failureBehavior());
    }

    /** Re-publishes the committed Skill with an explicitly replaced output-disposition vocabulary. */
    public static SegmentSkillDefinition skillWithOutputDispositions(List<Disposition> outputDispositions) {
        SegmentSkillDefinition base = skill();
        return SegmentSkillDefinition.publish(
                base.schemaVersion(), base.skillId(), base.skillVersion(), base.capabilityState(),
                base.evidenceClass(), base.contractPins(), base.tools(), base.inputDispositions(),
                outputDispositions, base.terminalStates(), base.limits(), base.dataClassification(),
                base.sampleOutputPermitted(), base.ambiguityStopsAt(), base.failureBehavior());
    }

    /**
     * A Skill that pins one Tool at version {@code toolVersion}, which the server-owned Tool surface must
     * refuse: a Tool schema this build does not implement cannot be executed safely.
     */
    public static SegmentSkillDefinition skillWithToolVersion(String toolId, String toolVersion) {
        List<SegmentSkillDefinition.ToolPin> pins = new ArrayList<>();
        int rank = 0;
        for (SegmentSkillDefinition.ToolPin pin : committedToolPins()) {
            pins.add(pin.toolId().equals(toolId)
                    ? new SegmentSkillDefinition.ToolPin(toolId, toolVersion, rank, pin.maxPayloadBytes())
                    : new SegmentSkillDefinition.ToolPin(pin.toolId(), pin.toolVersion(), rank, pin.maxPayloadBytes()));
            rank++;
        }
        return skillWithTools(pins);
    }

    // ------------------------------------------------------------------ counting host services

    /**
     * A delegating authorization boundary that counts every decision, so a test can observe whether the genuine
     * T07 validator or T08 planner ran at all inside a Tool call.
     */
    public static final class CountingAuthorization implements AuthorizationService {

        private final AuthorizationService delegate;
        private final java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();

        public CountingAuthorization(AuthorizationService delegate) {
            this.delegate = delegate;
        }

        @Override
        public AuthorizationDecision authorize(AuthorizationRequest request) {
            calls.incrementAndGet();
            return delegate.authorize(request);
        }

        /** How many authorization decisions were requested. */
        public int calls() {
            return calls.get();
        }
    }
}