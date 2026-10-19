package com.company.campaign.evaluation.segment.runner.testsupport;

import com.company.campaign.builder.agent.SegmentAgentEnvironment;
import com.company.campaign.builder.agent.SegmentMetadataSnapshotSource;
import com.company.campaign.builder.agent.SegmentStageObserver;
import com.company.campaign.builder.agent.testsupport.AgentTestFixtures;
import com.company.campaign.builder.refine.SegmentRefinementEnvironment;
import com.company.campaign.builder.refine.SegmentRefinementOperation;
import com.company.campaign.builder.refine.SegmentRefinementPatch;
import com.company.campaign.builder.segment.SegmentIntentModelPort;
import com.company.campaign.builder.segment.SegmentSchemaRepairPolicy;
import com.company.campaign.builder.segment.testsupport.SliceTestFixtures;
import com.company.campaign.builder.skill.SegmentSkillDefinition;
import com.company.campaign.contracts.ContractVersions;
import com.company.campaign.contracts.LocaleTag;
import com.company.campaign.contracts.TrustedExecutionContextRef;
import com.company.campaign.evaluation.segment.contract.CorpusBundle;
import com.company.campaign.evaluation.segment.loader.SegmentEvaluationLoader;
import com.company.campaign.evaluation.segment.runner.EvaluationCasePins;
import com.company.campaign.evaluation.segment.runner.EvaluationRunHost;
import com.company.campaign.evaluation.segment.runner.FixtureExecutionServices;
import com.company.campaign.metadata.MetadataSchemaVersions;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.metadata.repository.InMemoryMetadataSnapshotRepository;
import com.company.campaign.metadata.service.MetadataQueryService;
import com.company.campaign.metadata.testsupport.MetadataFixtures;
import com.company.campaign.preview.testsupport.RecordingTrustedContextProvider;
import com.company.campaign.query.fixture.DatasetField;
import com.company.campaign.query.fixture.FixtureDatasetLoader;
import com.company.campaign.query.fixture.FixtureMapping;
import com.company.campaign.query.fixture.FixtureScopeBinding;
import com.company.campaign.query.fixture.FxRateSnapshot;
import com.company.campaign.evaluation.segment.loader.EvaluationRoot;
import com.company.campaign.security.authorization.AuthorizationService;
import com.company.campaign.security.context.MembershipStatus;
import com.company.campaign.security.context.PrincipalMembership;
import com.company.campaign.security.context.TrustedContextResolver;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.security.context.VerifiedIdentity;
import com.company.campaign.segment.dsl.codec.SegmentDslCodec;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.dsl.predicate.AttributePredicate;
import com.company.campaign.segment.dsl.predicate.ConditionValue;
import com.company.campaign.segment.dsl.value.StringValue;
import com.company.campaign.segment.intent.RegisteredDefaultSource;
import com.company.campaign.segment.resolution.ResolutionPolicy;
import com.company.campaign.segment.validation.SegmentValidationPolicy;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import com.company.campaign.shared.canonical.CanonicalJson;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A genuine T13 host for the committed candidate v1 corpus.
 *
 * <p>It loads the corpus through the accepted loader so it can build the exact per-Case environment the real
 * corpus pins need: the parsed T04 Metadata snapshot, the trusted execution context of each execution-context
 * fixture, the genuine T09C Safe Preview over that snapshot and context, and the server-owned fixture services
 * the runner executes against. Only the provider port and the read-only Preview backend are deterministic
 * stubs, which is exactly what a run without an external model or database allows.
 */
public final class T13CorpusV1Host implements EvaluationRunHost {

    /** The committed corpus manifest reference. */
    public static final String CORPUS_V1_MANIFEST = "evaluation/segment/corpus-v1/manifest.json";

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();
    private static final SegmentDslCodec DSL_CODEC = new SegmentDslCodec();
    private static final AuthorizationService AUTHORIZATION = SegmentValidationFixtures.allowAll();

    private final Map<String, MetadataSnapshot> snapshots = new LinkedHashMap<>();
    private final Map<String, TrustedExecutionContext> contexts = new LinkedHashMap<>();
    private final Map<String, FixtureExecutionServices> services = new LinkedHashMap<>();
    private final Map<String, SegmentDsl> currentDocuments = new LinkedHashMap<>();
    private final Map<String, SegmentRefinementPatch> patches = new LinkedHashMap<>();
    private final SegmentIntentModelPort modelPort;

    public T13CorpusV1Host(SegmentIntentModelPort modelPort, Path repositoryRoot) {
        this.modelPort = modelPort;
        CorpusBundle bundle = new SegmentEvaluationLoader(repositoryRoot).loadCorpus(CORPUS_V1_MANIFEST);
        bundle.metadataCatalog().snapshots().forEach(descriptor -> snapshots.put(
                descriptor.snapshotId(),
                CANONICAL_JSON.read(new String(bundle.metadataPayload(descriptor.snapshotId()),
                        StandardCharsets.UTF_8), MetadataSnapshot.class)));
        bundle.fixtureCatalog().fixtures().forEach(descriptor -> {
            if (descriptor.role().isMemberSetFixture()) {
                services.put(descriptor.fixtureId(), new FixtureExecutionServices(
                        corpusMapping(), corpusScope(), cnyFx()));
            }
        });
        bundle.executionContexts().forEach((fixtureId, reference) -> contexts.put(fixtureId, context(reference)));
        bundle.cases().forEach(caseInput -> {
            if (caseInput.currentSegment() != null) {
                SegmentDsl base = DSL_CODEC.parse(
                        CANONICAL_JSON.writeString(caseInput.currentSegment().content()));
                currentDocuments.put(caseInput.caseId(), base);
                patches.put(caseInput.caseId(), addCityPatch(base));
            }
        });
    }

    /** A fixture dataset loader for the same repository root, used by payload-level assertions. */
    public FixtureDatasetLoader datasetLoader(Path repositoryRoot) {
        return new FixtureDatasetLoader(EvaluationRoot.of(repositoryRoot));
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
        return SegmentSchemaRepairPolicy.DISABLED;
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
        MetadataSnapshot snapshot = snapshot(pins);
        TrustedExecutionContext context = trustedContext(pins);
        return AgentTestFixtures.environment(
                        port, snapshot, context, AUTHORIZATION,
                        AgentTestFixtures.previewPort(snapshot, context, AUTHORIZATION), null, null)
                .withStageObserver(observer);
    }

    @Override
    public SegmentRefinementEnvironment refinementEnvironment(
            EvaluationCasePins pins, SegmentStageObserver observer) {
        MetadataSnapshot snapshot = snapshot(pins);
        TrustedExecutionContext context = trustedContext(pins);
        return SegmentRefinementEnvironment.of(
                        AgentTestFixtures.skill(),
                        RecordingTrustedContextProvider.of(context),
                        runContext -> snapshot,
                        new MetadataQueryService(AUTHORIZATION, repository(snapshot)),
                        AUTHORIZATION,
                        SliceTestFixtures.validationPolicy(),
                        AgentTestFixtures.previewPort(snapshot, context, AUTHORIZATION),
                        null,
                        null)
                .withStageObserver(observer);
    }

    @Override
    public TrustedExecutionContext trustedContext(EvaluationCasePins pins) {
        TrustedExecutionContext context = contexts.get(pins.executionContextFixture());
        if (context == null) {
            throw new IllegalStateException("no context for " + pins.executionContextFixture());
        }
        return context;
    }

    @Override
    public FixtureExecutionServices fixtureServices(String fixtureId) {
        return services.get(fixtureId);
    }

    @Override
    public SegmentRefinementPatch refinementPatch(EvaluationCasePins pins) {
        return patches.get(pins.caseId());
    }

    /** The parsed DSL of one refinement Case, for payload-level assertions. */
    public SegmentDsl currentDocument(String caseId) {
        return currentDocuments.get(caseId);
    }

    private MetadataSnapshot snapshot(EvaluationCasePins pins) {
        MetadataSnapshot snapshot = snapshots.get(pins.metadataSnapshotId());
        if (snapshot == null) {
            throw new IllegalStateException("no snapshot for " + pins.metadataSnapshotId());
        }
        return snapshot;
    }

    private static InMemoryMetadataSnapshotRepository repository(MetadataSnapshot snapshot) {
        InMemoryMetadataSnapshotRepository repository = new InMemoryMetadataSnapshotRepository();
        repository.publish(snapshot);
        return repository;
    }

    /** A deterministic host-owned context derived from one corpus execution-context fixture. */
    private static TrustedExecutionContext context(TrustedExecutionContextRef reference) {
        return TrustedContextResolver.resolve(
                new VerifiedIdentity(
                        reference.userId(), "https://issuer.example", Instant.parse("2026-10-01T00:00:00Z")),
                new PrincipalMembership(
                        "membership-" + reference.dataScopeId(), reference.userId(), reference.tenantId(),
                        reference.programId(), reference.dataScopeId(), MembershipStatus.ACTIVE,
                        MetadataFixtures.allPermissions()),
                reference);
    }

    /** The server-owned mapping covering the attribute vocabulary the corpus documents use. */
    public static FixtureMapping corpusMapping() {
        return FixtureMapping.publish(
                "t13-corpus-mapping-1",
                List.of(new FixtureMapping.FieldBinding("source.id", SubjectKind.MEMBER, DatasetField.MEMBER_ID)),
                List.of(
                        new FixtureMapping.AttributeBinding(
                                "member.tier", SubjectKind.MEMBER, MetadataSchemaVersions.SUPPORTED,
                                DatasetField.MEMBER_TIER),
                        new FixtureMapping.AttributeBinding(
                                "member.city", SubjectKind.MEMBER, MetadataSchemaVersions.SUPPORTED,
                                DatasetField.MEMBER_CITY),
                        new FixtureMapping.AttributeBinding(
                                "member.status", SubjectKind.MEMBER, MetadataSchemaVersions.SUPPORTED,
                                DatasetField.MEMBER_STATUS)),
                List.of(),
                List.of());
    }

    /** The DataScope binding covering both corpus execution contexts. */
    public static FixtureScopeBinding corpusScope() {
        return FixtureScopeBinding.publish("t13-corpus-scope-1", List.of(
                new FixtureScopeBinding.ScopeEntry(
                        "scope-008", com.company.campaign.evaluation.segment.contract.MemberIdSet.of(
                                "m-001", "m-002", "m-003", "m-004", "m-005", "m-006", "m-007", "m-008",
                                "m-009", "m-010", "m-011", "m-012")),
                new FixtureScopeBinding.ScopeEntry(
                        "scope-limited-002", com.company.campaign.evaluation.segment.contract.MemberIdSet.of(
                                "m-001", "m-002", "m-003"))));
    }

    public static FxRateSnapshot cnyFx() {
        return FxRateSnapshot.publish("fx-t13-corpus-001", "1", "CNY", Instant.parse("2026-10-01T00:00:00Z"), Map.of());
    }

    /** The deterministic refinement proposal of one corpus refinement Case. */
    private static SegmentRefinementPatch addCityPatch(SegmentDsl base) {
        return SegmentRefinementPatch.publish(
                ContractVersions.CURRENT,
                base.editableDslHash(),
                List.of(new SegmentRefinementOperation.AddNode(
                        "op-1", base.predicate().nodeId(), null,
                        new AttributePredicate(
                                "n-added-city", "member.city", ComparisonOperator.EQUAL,
                                ConditionValue.of(new StringValue("City-9"))))));
    }

    /** The exact locale tag of the primary corpus context, for payload-level assertions. */
    public static LocaleTag primaryLocale() {
        return LocaleTag.of(java.util.Locale.SIMPLIFIED_CHINESE);
    }

    /** The union of every fixture id this host supplies services for. */
    public List<String> fixtureIds() {
        return new ArrayList<>(services.keySet());
    }
}