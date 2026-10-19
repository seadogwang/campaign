package com.company.campaign.evaluation.segment.runner.testsupport;

import com.company.campaign.builder.agent.SegmentAgentEnvironment;
import com.company.campaign.builder.agent.SegmentStageObserver;
import com.company.campaign.builder.agent.testsupport.AgentTestFixtures;
import com.company.campaign.builder.refine.SegmentRefinementEnvironment;
import com.company.campaign.builder.refine.SegmentRefinementPatch;
import com.company.campaign.builder.segment.SegmentIntentModelPort;
import com.company.campaign.builder.segment.SegmentSchemaRepairPolicy;
import com.company.campaign.builder.segment.testsupport.SliceTestFixtures;
import com.company.campaign.builder.skill.SegmentSkillDefinition;
import com.company.campaign.evaluation.segment.runner.EvaluationCasePins;
import com.company.campaign.evaluation.segment.runner.EvaluationRunHost;
import com.company.campaign.evaluation.segment.runner.FixtureExecutionServices;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.metadata.repository.InMemoryMetadataSnapshotRepository;
import com.company.campaign.metadata.service.MetadataQueryService;
import com.company.campaign.preview.testsupport.RecordingTrustedContextProvider;
import com.company.campaign.query.fixture.DatasetField;
import com.company.campaign.query.fixture.FixtureMapping;
import com.company.campaign.query.fixture.FixtureScopeBinding;
import com.company.campaign.query.fixture.FxRateSnapshot;
import com.company.campaign.security.authorization.AuthorizationService;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.segment.intent.RegisteredDefaultSource;
import com.company.campaign.segment.resolution.ResolutionPolicy;
import com.company.campaign.segment.validation.SegmentValidationPolicy;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A genuine T13 host built from the accepted T11/T12 component fixtures.
 *
 * <p>Every service is the real accepted one: the committed Skill and its catalog, the allow-all authorization
 * boundary, the real Metadata query service over the pinned snapshot, the genuine T09C Safe Preview and the
 * genuine T12 refinement environment. Only the provider port and the read-only Preview backend are stubs, which
 * is exactly what the task allows for a run that must not call a real external model or database.
 */
public class T13TestHost implements EvaluationRunHost {

    /** The member-set fixture every create Case of the synthetic corpus references. */
    public static final String TIER_FIXTURE = "fixture-t13-members";
    /** The trusted execution-context fixture every Case references. */
    public static final String CONTEXT_FIXTURE = "fixture-t13-context";
    /** The frozen Metadata snapshot id. */
    public static final String SNAPSHOT_ID = SliceTestFixtures.snapshot().snapshotId();

    private static final AuthorizationService AUTHORIZATION = SegmentValidationFixtures.allowAll();

    private final SegmentIntentModelPort modelPort;
    private final SegmentSchemaRepairPolicy repairPolicy;
    private final Map<String, FixtureExecutionServices> services = new LinkedHashMap<>();
    private final Map<String, TrustedExecutionContext> contexts = new LinkedHashMap<>();
    private SegmentRefinementPatch refinementPatch;
    private boolean failObservation;
    private com.company.campaign.evaluation.segment.runner.EvaluationObservation lastObservation;

    public T13TestHost(SegmentIntentModelPort modelPort) {
        this(modelPort, SegmentSchemaRepairPolicy.DISABLED);
    }

    public T13TestHost(SegmentIntentModelPort modelPort, SegmentSchemaRepairPolicy repairPolicy) {
        this.modelPort = modelPort;
        this.repairPolicy = repairPolicy;
        this.services.put(TIER_FIXTURE, new FixtureExecutionServices(tierMapping(), tierScope(), cnyFx()));
    }

    public T13TestHost refinementPatch(SegmentRefinementPatch patch) {
        this.refinementPatch = patch;
        return this;
    }

    /** Registers one fixture service bundle for a fixture id the synthetic corpus references. */
    public T13TestHost services(String fixtureId, FixtureExecutionServices executionServices) {
        services.put(fixtureId, executionServices);
        return this;
    }

    /** Makes the injected observation boundary throw, so the run must fail closed as INVALID. */
    public T13TestHost failingObservation() {
        this.failObservation = true;
        return this;
    }

    /** The most recent observation boundary this host injected, for evidence-coherence assertions. */
    public com.company.campaign.evaluation.segment.runner.EvaluationObservation lastObservation() {
        return lastObservation;
    }

    /** Registers the trusted execution context one execution-context fixture resolves to. */
    public T13TestHost context(String fixtureId, TrustedExecutionContext executionContext) {
        contexts.put(fixtureId, executionContext);
        return this;
    }

    @Override
    public SegmentSkillDefinition skill() {
        return AgentTestFixtures.skill();
    }

    @Override
    public SegmentIntentModelPort modelPort() {
        return modelPort;
    }

    @Override
    public SegmentSchemaRepairPolicy repairPolicy() {
        return repairPolicy;
    }

    @Override
    public RegisteredDefaultSource registeredDefaults() {
        return SliceTestFixtures.registry();
    }

    @Override
    public ResolutionPolicy resolutionPolicy() {
        return SliceTestFixtures.policy();
    }

    @Override
    public SegmentValidationPolicy validationPolicy() {
        return SliceTestFixtures.validationPolicy();
    }

    @Override
    public AuthorizationService authorizationService() {
        return AUTHORIZATION;
    }

    @Override
    public SegmentAgentEnvironment agentEnvironment(
            EvaluationCasePins pins, SegmentStageObserver observer, SegmentIntentModelPort port) {
        TrustedExecutionContext executionContext = trustedContext(pins);
        lastObservation = (com.company.campaign.evaluation.segment.runner.EvaluationObservation) observer;
        return AgentTestFixtures.environment(
                        port, snapshot(), executionContext, AUTHORIZATION,
                        AgentTestFixtures.previewPort(snapshot(), executionContext, AUTHORIZATION), null, null)
                .withStageObserver(observed(observer))
                .withRepairPolicy(repairPolicy);
    }

    @Override
    public SegmentRefinementEnvironment refinementEnvironment(
            EvaluationCasePins pins, SegmentStageObserver observer) {
        TrustedExecutionContext executionContext = trustedContext(pins);
        lastObservation = (com.company.campaign.evaluation.segment.runner.EvaluationObservation) observer;
        return SegmentRefinementEnvironment.of(
                        AgentTestFixtures.skill(),
                        RecordingTrustedContextProvider.of(executionContext),
                        runContext -> snapshot(),
                        new MetadataQueryService(AUTHORIZATION, repository()),
                        AUTHORIZATION,
                        SliceTestFixtures.validationPolicy(),
                        AgentTestFixtures.previewPort(snapshot(), executionContext, AUTHORIZATION),
                        null,
                        null)
                .withStageObserver(observed(observer));
    }

    private SegmentStageObserver observed(SegmentStageObserver observer) {
        if (!failObservation) {
            return observer;
        }
        return new SegmentStageObserver() {
            @Override
            public void intentAccepted(
                    com.company.campaign.segment.intent.SegmentIntent intent,
                    com.company.campaign.shared.canonical.ContentHash intentHash) {
                throw new IllegalStateException("observer unavailable");
            }
        };
    }

    @Override
    public TrustedExecutionContext trustedContext(EvaluationCasePins pins) {
        TrustedExecutionContext registered = contexts.get(pins.executionContextFixture());
        return registered == null ? context() : registered;
    }

    @Override
    public FixtureExecutionServices fixtureServices(String fixtureId) {
        return services.get(fixtureId);
    }

    @Override
    public SegmentRefinementPatch refinementPatch(EvaluationCasePins pins) {
        return refinementPatch;
    }

    // ------------------------------------------------------------------ shared genuine services

    public static MetadataSnapshot snapshot() {
        return SliceTestFixtures.snapshot();
    }

    public static TrustedExecutionContext context() {
        return SliceTestFixtures.context();
    }

    private static InMemoryMetadataSnapshotRepository repository() {
        InMemoryMetadataSnapshotRepository repository = new InMemoryMetadataSnapshotRepository();
        repository.publish(snapshot());
        return repository;
    }

    // ------------------------------------------------------------------ fixture services

    public static FixtureMapping tierMapping() {
        return FixtureMapping.publish(
                "t13-fixture-mapping-1",
                List.of(new FixtureMapping.FieldBinding("source.id",
                        com.company.campaign.metadata.domain.SubjectKind.MEMBER, DatasetField.MEMBER_ID)),
                List.of(new FixtureMapping.AttributeBinding(
                        "member.tier",
                        com.company.campaign.metadata.domain.SubjectKind.MEMBER,
                        com.company.campaign.metadata.MetadataSchemaVersions.SUPPORTED,
                        DatasetField.MEMBER_TIER)),
                List.of(),
                List.of());
    }

    public static FixtureScopeBinding tierScope() {
        return FixtureScopeBinding.publish("t13-fixture-scope-1", List.of(
                new FixtureScopeBinding.ScopeEntry("scope-008", T13TestAssets.TIER_UNIVERSE)));
    }

    public static FxRateSnapshot cnyFx() {
        return FxRateSnapshot.publish("fx-t13-001", "1", "CNY", Instant.parse("2026-10-01T00:00:00Z"), Map.of());
    }
}