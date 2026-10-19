package com.company.campaign.preview;

import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.metadata.repository.MetadataSnapshotRepository;
import com.company.campaign.preview.adapter.FixturePreviewExecutionBackend;
import com.company.campaign.preview.testsupport.PreviewAuthorizationStub;
import com.company.campaign.preview.testsupport.PreviewTestSupport;
import com.company.campaign.preview.testsupport.RecordingMetadataSnapshotRepository;
import com.company.campaign.preview.testsupport.RecordingTrustedContextProvider;
import com.company.campaign.preview.testsupport.StubPreviewBackend;
import com.company.campaign.query.fixture.FixtureDataset;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.query.testsupport.QueryPlanFixtures;
import com.company.campaign.segment.dsl.contract.MetadataBinding;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import com.company.campaign.security.authorization.AuthorizationService;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Requirements 3-9: one-call trusted context, exact snapshot pins, validation/planning short-circuits,
 * backend result coherence, freshness and limits, the required success fields and deterministic bounded
 * condition statistics.
 */
class SegmentPreviewServicePipelineTest {

    @TempDir
    Path tempDir;

    private FixtureDataset dataset;
    private RecordingTrustedContextProvider provider;
    private MetadataSnapshotRepository repository;

    @BeforeEach
    void setUp() {
        dataset = PreviewTestSupport.dataset(tempDir);
        provider = RecordingTrustedContextProvider.of(SegmentValidationFixtures.context());
        repository = PreviewTestSupport.repository();
    }

    // ------------------------------------------------------------------ success and one-call context

    @Test
    void successResponseCarriesEveryRequiredDeterministicField() {
        SegmentPreviewService service =
                PreviewTestSupport.service(provider, repository, PreviewTestSupport.fixtureBackend(dataset));

        PreviewResponse response = service.preview(
                PreviewTestSupport.body(PreviewTestSupport.goldDocument(), false, true));

        assertThat(response.status()).isEqualTo("SUCCESS");
        assertThat(response.canonicalAstHash()).matches("^sha256:[0-9a-f]{64}$");
        assertThat(response.queryPlanHash()).matches("^sha256:[0-9a-f]{64}$");
        assertThat(response.queryPlanId()).isEqualTo("qp-" + response.queryPlanHash().substring("sha256:".length()));
        assertThat(response.queryPlanId()).matches("^qp-[0-9a-f]{64}$");
        assertThat(response.matchedCount()).isEqualTo(3);
        assertThat(response.sample()).isNull();
        assertThat(response.conditionStats()).isNotNull();

        PreviewExecutionContext context = response.executionContext();
        assertThat(context.metadataSnapshotId()).isEqualTo("metadata-17");
        assertThat(context.asOf()).isEqualTo("2026-10-01T02:00:00Z");
        assertThat(context.timezone()).isEqualTo("Asia/Shanghai");
        assertThat(context.dataWatermark()).isEqualTo("2026-10-01T01:55:00Z");
    }

    @Test
    void trustedContextIsResolvedExactlyOncePerCallAndDrivesExecution() {
        SegmentPreviewService service =
                PreviewTestSupport.service(provider, repository, PreviewTestSupport.fixtureBackend(dataset));

        service.preview(PreviewTestSupport.body(PreviewTestSupport.goldDocument(), false, true));
        assertThat(provider.callCount()).isEqualTo(1);

        service.preview(PreviewTestSupport.body(PreviewTestSupport.goldDocument(), false, true));
        assertThat(provider.callCount()).isEqualTo(2);
    }

    @Test
    void theTrustedDataScopeDrivesWhichMembersExecute() {
        // A server-owned scope binding that excludes m-006; the plan and request never name a DataScope.
        FixturePreviewExecutionBackend restricted = new FixturePreviewExecutionBackend(
                dataset, com.company.campaign.query.fixture.testsupport.FixtureTestSupport.standardMapping(),
                com.company.campaign.query.fixture.testsupport.FixtureTestSupport.restrictedScope(), null, "v1");
        SegmentPreviewService service = PreviewTestSupport.service(provider, repository, restricted);

        PreviewResponse response = service.preview(
                PreviewTestSupport.body(PreviewTestSupport.goldDocument(), false, true));

        assertThat(response.matchedCount()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ snapshot pins

    @Test
    void missingSnapshotFailsBeforeBackendExecution() {
        StubPreviewBackend stub = new StubPreviewBackend((plan, context) -> coherent(plan, context, List.of()));
        SegmentPreviewService service = PreviewTestSupport.service(
                provider, PreviewTestSupport.repository(null), stub);

        PreviewException failure = failureOf(service,
                PreviewTestSupport.body(PreviewTestSupport.goldDocument(), false, true));

        assertThat(failure.code()).isEqualTo(PreviewErrorCodes.METADATA_SNAPSHOT_MISSING);
        assertThat(stub.callCount()).isZero();
    }

    @Test
    void theServicePerformsExactlyOneExactPinLookupWithTheCompleteBinding() {
        RecordingMetadataSnapshotRepository recording =
                new RecordingMetadataSnapshotRepository(PreviewTestSupport.repository());
        StubPreviewBackend stub = new StubPreviewBackend((plan, context) -> coherent(plan, context, List.of()));
        SegmentPreviewService service = PreviewTestSupport.service(provider, recording, stub);

        PreviewResponse response = service.preview(
                PreviewTestSupport.body(PreviewTestSupport.goldDocument(), false, true));

        assertThat(response.status()).isEqualTo("SUCCESS");
        MetadataBinding binding = PreviewTestSupport.goldDocument().metadata();
        assertThat(recording.findByPinCalls()).hasSize(1);
        assertThat(recording.findByPinCalls().get(0).snapshotId()).isEqualTo(binding.snapshotId());
        assertThat(recording.findByPinCalls().get(0).snapshotVersion()).isEqualTo(binding.snapshotVersion());
        assertThat(recording.findByPinCalls().get(0).contentHash()).isEqualTo(binding.contentHash());
        assertThat(recording.findByIdCallCount()).isZero();
        assertThat(recording.findByContentHashCallCount()).isZero();
        assertThat(recording.retainedCallCount()).isZero();
    }

    @Test
    void conflictingSnapshotOwnershipFailsBeforeBackendExecution() {
        // The DSL pins a snapshot that is exactly retained, but it is owned by another tenant/program.
        MetadataSnapshot foreign = QueryPlanFixtures.foreignOwnerSnapshot("tenant-999", "program-001");
        StubPreviewBackend stub = new StubPreviewBackend((plan, context) -> coherent(plan, context, List.of()));
        RecordingTrustedContextProvider foreignContext = RecordingTrustedContextProvider.of(
                QueryPlanFixtures.contextPinning(
                        SegmentValidationFixtures.SNAPSHOT_ID, "tenant-001", "program-001", "scope-008"));
        SegmentPreviewService service = PreviewTestSupport.service(
                foreignContext, PreviewTestSupport.repository(foreign), stub);
        SegmentDsl foreignBound = QueryPlanFixtures.rebind(PreviewTestSupport.goldDocument(), foreign);

        PreviewException failure = failureOf(service, PreviewTestSupport.body(foreignBound, false, true));

        assertThat(failure.code()).isEqualTo(PreviewErrorCodes.METADATA_OWNER_MISMATCH);
        assertThat(stub.callCount()).isZero();
    }

    @Test
    void aStaleSnapshotVersionFailsTheExactLookupBeforeValidationOrExecution() {
        StubPreviewBackend stub = new StubPreviewBackend((plan, context) -> coherent(plan, context, List.of()));
        SegmentPreviewService service = PreviewTestSupport.service(provider, repository, stub);
        MetadataSnapshot standard = SegmentValidationFixtures.snapshot();
        MetadataBinding staleVersion = new MetadataBinding(
                SegmentValidationFixtures.SNAPSHOT_ID, "2",
                standard.schemaVersion(), standard.contentHash());
        SegmentDsl document = SegmentValidationFixtures.publish(
                staleVersion, PreviewTestSupport.goldDocument().predicate());

        PreviewException failure = failureOf(service, PreviewTestSupport.body(document, false, true));

        assertThat(failure.code()).isEqualTo(PreviewErrorCodes.METADATA_SNAPSHOT_MISSING);
        assertThat(stub.callCount()).isZero();
    }

    @Test
    void aStaleSnapshotHashFailsTheExactLookupBeforeValidationOrExecution() {
        StubPreviewBackend stub = new StubPreviewBackend((plan, context) -> coherent(plan, context, List.of()));
        SegmentPreviewService service = PreviewTestSupport.service(provider, repository, stub);
        SegmentDsl rebound = QueryPlanFixtures.rebind(
                PreviewTestSupport.goldDocument(), QueryPlanFixtures.snapshotWithExtraAttribute());

        PreviewException failure = failureOf(service, PreviewTestSupport.body(rebound, false, true));

        assertThat(failure.code()).isEqualTo(PreviewErrorCodes.METADATA_SNAPSHOT_MISSING);
        assertThat(stub.callCount()).isZero();
    }

    @Test
    void aDslSnapshotIdThatDiffersFromTheContextPinFailsBeforeAnyLookup() {
        RecordingMetadataSnapshotRepository recording =
                new RecordingMetadataSnapshotRepository(repository);
        StubPreviewBackend stub = new StubPreviewBackend((plan, context) -> coherent(plan, context, List.of()));
        SegmentPreviewService service = PreviewTestSupport.service(provider, recording, stub);
        SegmentDsl rebound = QueryPlanFixtures.rebind(
                PreviewTestSupport.goldDocument(), SegmentValidationFixtures.snapshot("metadata-99", "1"));

        PreviewException failure = failureOf(service, PreviewTestSupport.body(rebound, false, true));

        assertThat(failure.code()).isEqualTo(PreviewErrorCodes.METADATA_BINDING_MISMATCH);
        assertThat(recording.findByPinCalls()).isEmpty();
        assertThat(stub.callCount()).isZero();
    }

    @Test
    void unavailableTrustedContextFailsClosed() {
        SegmentPreviewService service = PreviewTestSupport.service(
                RecordingTrustedContextProvider.failing(), repository,
                PreviewTestSupport.fixtureBackend(dataset));

        PreviewException failure = failureOf(service,
                PreviewTestSupport.body(PreviewTestSupport.goldDocument(), false, true));

        assertThat(failure.code()).isEqualTo(PreviewErrorCodes.TRUSTED_CONTEXT_UNAVAILABLE);
    }

    // ------------------------------------------------------------------ pipeline short-circuits

    @Test
    void validationRejectionProducesNoPlanOrBackendCall() {
        StubPreviewBackend stub = new StubPreviewBackend((plan, context) -> coherent(plan, context, List.of()));
        SegmentPreviewService service = PreviewTestSupport.service(provider, repository, stub);

        PreviewException failure = failureOf(service,
                PreviewTestSupport.body(PreviewTestSupport.unknownDefinitionDocument(), false, true));

        assertThat(failure.code()).isEqualTo(PreviewErrorCodes.VALIDATION_REJECTED);
        assertThat(stub.callCount()).isZero();
    }

    @Test
    void deniedMetadataDependencyIsAValidationRejectionWithNoBackendCall() {
        StubPreviewBackend stub = new StubPreviewBackend((plan, context) -> coherent(plan, context, List.of()));
        SegmentPreviewService service = PreviewTestSupport.service(
                provider, repository, stub, SegmentValidationFixtures.denyAll(),
                PreviewTestSupport.policy(), PreviewTestSupport.hasher(PreviewTestSupport.KEY_A));

        PreviewException failure = failureOf(service,
                PreviewTestSupport.body(PreviewTestSupport.goldDocument(), false, true));

        assertThat(failure.code()).isEqualTo(PreviewErrorCodes.VALIDATION_REJECTED);
        assertThat(stub.callCount()).isZero();
    }

    @Test
    void theTrustedContextPermissionsDriveValidation() {
        // The document is well formed, but its context carries no CAMPAIGN_READ, so validation must reject it.
        StubPreviewBackend stub = new StubPreviewBackend((plan, context) -> coherent(plan, context, List.of()));
        SegmentPreviewService service = PreviewTestSupport.service(
                RecordingTrustedContextProvider.of(SegmentValidationFixtures.noPermissionContext()),
                repository, stub);

        PreviewException failure = failureOf(service,
                PreviewTestSupport.body(PreviewTestSupport.goldDocument(), false, true));

        assertThat(failure.code()).isEqualTo(PreviewErrorCodes.VALIDATION_REJECTED);
        assertThat(stub.callCount()).isZero();
    }

    @Test
    void planningRejectionProducesNoBackendCall() {
        StubPreviewBackend stub = new StubPreviewBackend((plan, context) -> coherent(plan, context, List.of()));
        // The single-attribute document authorizes exactly once during validation; the planner's
        // reauthorization is then denied, so validation succeeds and planning fails closed.
        AuthorizationService allowValidationOnly = PreviewAuthorizationStub.allowFirstMetadata(1);
        SegmentPreviewService service = PreviewTestSupport.service(
                provider, repository, stub, allowValidationOnly,
                PreviewTestSupport.policy(), PreviewTestSupport.hasher(PreviewTestSupport.KEY_A));

        PreviewException failure = failureOf(service,
                PreviewTestSupport.body(PreviewTestSupport.goldDocument(), false, true));

        assertThat(failure.code()).isEqualTo(PreviewErrorCodes.PLANNING_REJECTED);
        assertThat(stub.callCount()).isZero();
    }

    // ------------------------------------------------------------------ backend result coherence

    @Test
    void everyIncoherentBackendResultFactFailsClosed() {
        assertIncoherent(result -> Rebuilt.withPlanHash(result, ContentHash.sha256("other".getBytes(StandardCharsets.UTF_8))));
        assertIncoherent(result -> Rebuilt.withSnapshotId(result, "metadata-99"));
        assertIncoherent(result -> Rebuilt.withDataScopeId(result, "scope-999"));
        assertIncoherent(result -> Rebuilt.withAsOf(result, Instant.parse("2026-10-01T03:00:00Z")));
        assertIncoherent(result -> Rebuilt.withTimezone(result, "UTC"));
        assertIncoherent(result -> Rebuilt.withWatermark(result, Instant.parse("2026-10-01T01:50:00Z")));
        assertIncoherent(result -> Rebuilt.withBackendId(result, "some-other-backend"));
        assertIncoherent(result -> Rebuilt.withBackendVersion(result, "some-other-version"));
    }

    @Test
    void aBackendResultCarryingAWrongIdentityIdFailsClosed() {
        StubPreviewBackend stub = new StubPreviewBackend(
                "preview-identity-backend", StubPreviewBackend.DEFAULT_VERSION, (plan, context) ->
                StubPreviewBackend.coherent(
                        plan, context, List.of("m-001"), "some-other-backend", StubPreviewBackend.DEFAULT_VERSION));
        SegmentPreviewService service = PreviewTestSupport.service(provider, repository, stub);

        PreviewException failure = failureOf(service,
                PreviewTestSupport.body(PreviewTestSupport.goldDocument(), false, true));

        assertThat(failure.code()).isEqualTo(PreviewErrorCodes.EXECUTION_RESULT_INCOHERENT);
    }

    @Test
    void aBackendResultCarryingAWrongVersionFailsClosed() {
        StubPreviewBackend stub = new StubPreviewBackend(
                "preview-identity-backend", "identity-v2", (plan, context) ->
                StubPreviewBackend.coherent(
                        plan, context, List.of("m-001"), "preview-identity-backend", "result-v1"));
        SegmentPreviewService service = PreviewTestSupport.service(provider, repository, stub);

        PreviewException failure = failureOf(service,
                PreviewTestSupport.body(PreviewTestSupport.goldDocument(), false, true));

        assertThat(failure.code()).isEqualTo(PreviewErrorCodes.EXECUTION_RESULT_INCOHERENT);
    }

    @Test
    void aNullBackendIdentityFailsClosedWithNoRawException() {
        PreviewExecutionBackend backend = new PreviewExecutionBackend() {
            @Override
            public PreviewBackendIdentity identity() {
                return null;
            }

            @Override
            public PreviewBackendResult execute(SemanticQueryPlan plan, TrustedExecutionContext context) {
                return StubPreviewBackend.coherent(plan, context, List.of("m-001"), "any-id", "any-version");
            }
        };
        SegmentPreviewService service = PreviewTestSupport.service(provider, repository, backend);

        PreviewException failure = failureOf(service,
                PreviewTestSupport.body(PreviewTestSupport.goldDocument(), false, true));

        assertThat(failure.code()).isEqualTo(PreviewErrorCodes.EXECUTION_RESULT_INCOHERENT);
    }

    @Test
    void aThrowingBackendIdentityFailsClosedWithNoRawException() {
        PreviewExecutionBackend backend = new PreviewExecutionBackend() {
            @Override
            public PreviewBackendIdentity identity() {
                throw new IllegalStateException("identity failure with secret-like text");
            }

            @Override
            public PreviewBackendResult execute(SemanticQueryPlan plan, TrustedExecutionContext context) {
                return StubPreviewBackend.coherent(plan, context, List.of("m-001"), "any-id", "any-version");
            }
        };
        SegmentPreviewService service = PreviewTestSupport.service(provider, repository, backend);

        PreviewException failure = failureOf(service,
                PreviewTestSupport.body(PreviewTestSupport.goldDocument(), false, true));

        assertThat(failure.code()).isEqualTo(PreviewErrorCodes.BACKEND_FAILED);
        assertThat(failure.getMessage()).doesNotContain("secret-like");
        assertThat(failure.details().toString()).doesNotContain("secret-like");
    }

    private void assertIncoherent(java.util.function.UnaryOperator<PreviewBackendResult> mutation) {
        String backendId = "preview-coherence-backend";
        StubPreviewBackend stub = new StubPreviewBackend(backendId, (plan, context) ->
                mutation.apply(coherent(plan, context, List.of("m-001"), backendId)));
        SegmentPreviewService service = PreviewTestSupport.service(provider, repository, stub);

        PreviewException failure = failureOf(service,
                PreviewTestSupport.body(PreviewTestSupport.goldDocument(), false, true));

        assertThat(failure.code()).isEqualTo(PreviewErrorCodes.EXECUTION_RESULT_INCOHERENT);
    }

    @Test
    void aTamperedBackendResultHashIsRejectedByTheService() {
        String backendId = "preview-hash-backend";
        StubPreviewBackend stub = new StubPreviewBackend(backendId, (plan, context) -> {
            PreviewBackendResult result = coherent(plan, context, List.of("m-001"), backendId);
            tamperResultHash(result, ContentHash.sha256("tampered".getBytes(StandardCharsets.UTF_8)));
            return result;
        });
        SegmentPreviewService service = PreviewTestSupport.service(provider, repository, stub);

        PreviewException failure = failureOf(service,
                PreviewTestSupport.body(PreviewTestSupport.goldDocument(), false, true));

        assertThat(failure.code()).isEqualTo(PreviewErrorCodes.EXECUTION_RESULT_INCOHERENT);
    }

    private static void tamperResultHash(PreviewBackendResult result, ContentHash hash) {
        try {
            java.lang.reflect.Field field = PreviewBackendResult.class.getDeclaredField("resultHash");
            field.setAccessible(true);
            field.set(result, hash);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    // ------------------------------------------------------------------ freshness and limits

    @Test
    void backendFailureKindsMapToStableCodes() {
        assertThat(backendFailure(PreviewBackendException.Kind.UNAVAILABLE))
                .isEqualTo(PreviewErrorCodes.BACKEND_UNAVAILABLE);
        assertThat(backendFailure(PreviewBackendException.Kind.TIMEOUT))
                .isEqualTo(PreviewErrorCodes.BACKEND_TIMEOUT);
        assertThat(backendFailure(PreviewBackendException.Kind.INVALID_RESULT))
                .isEqualTo(PreviewErrorCodes.EXECUTION_RESULT_INCOHERENT);
    }

    private String backendFailure(PreviewBackendException.Kind kind) {
        StubPreviewBackend stub = new StubPreviewBackend((plan, context) -> {
            throw new PreviewBackendException(kind);
        });
        SegmentPreviewService service = PreviewTestSupport.service(provider, repository, stub);
        return failureOf(service, PreviewTestSupport.body(PreviewTestSupport.goldDocument(), false, true)).code();
    }

    @Test
    void anUnexpectedBackendFailureIsBounded() {
        StubPreviewBackend stub = new StubPreviewBackend((plan, context) -> {
            throw new IllegalStateException("driver failure with secret-like text");
        });
        SegmentPreviewService service = PreviewTestSupport.service(provider, repository, stub);

        PreviewException failure = failureOf(service,
                PreviewTestSupport.body(PreviewTestSupport.goldDocument(), false, true));

        assertThat(failure.code()).isEqualTo(PreviewErrorCodes.BACKEND_FAILED);
        assertThat(failure.getMessage()).doesNotContain("secret-like");
        assertThat(failure.details().toString()).doesNotContain("secret-like");
    }

    @Test
    void staleDataFailsWithStableCodeAndNoFabricatedCount() {
        StubPreviewBackend stub = new StubPreviewBackend("preview-stale-backend", (plan, context) ->
                coherent(plan, context, List.of("m-001", "m-003"), "preview-stale-backend"));
        SegmentPreviewService service = PreviewTestSupport.service(
                provider, repository, stub, SegmentValidationFixtures.allowAll(),
                PreviewTestSupport.policyWithMaxDataAge(Duration.ofSeconds(60)),
                PreviewTestSupport.hasher(PreviewTestSupport.KEY_A));

        PreviewException failure = failureOf(service,
                PreviewTestSupport.body(PreviewTestSupport.goldDocument(), false, true));

        assertThat(failure.code()).isEqualTo(PreviewErrorCodes.DATA_STALE);
    }

    @Test
    void overLimitMatchedCountFailsWithStableCodeAndNoFabricatedCount() {
        StubPreviewBackend stub = new StubPreviewBackend("preview-limit-backend", (plan, context) ->
                coherent(plan, context, List.of("m-001", "m-002", "m-003"), "preview-limit-backend"));
        PreviewPolicy policy = new PreviewPolicy(
                PreviewTestSupport.policy().maxRequestBytes(), 128, 100, 1, Duration.ofHours(24));
        SegmentPreviewService service = PreviewTestSupport.service(
                provider, repository, stub, SegmentValidationFixtures.allowAll(), policy,
                PreviewTestSupport.hasher(PreviewTestSupport.KEY_A));

        PreviewException failure = failureOf(service,
                PreviewTestSupport.body(PreviewTestSupport.goldDocument(), false, true));

        assertThat(failure.code()).isEqualTo(PreviewErrorCodes.RESULT_LIMIT_EXCEEDED);
    }

    // ------------------------------------------------------------------ condition statistics

    @Test
    void conditionStatisticsAreAbsentWhenDisabled() {
        SegmentPreviewService service =
                PreviewTestSupport.service(provider, repository, PreviewTestSupport.fixtureBackend(dataset));

        PreviewResponse response = service.preview(
                PreviewTestSupport.body(PreviewTestSupport.multiPathDocument(), false, false));

        assertThat(response.conditionStats()).isNull();
    }

    @Test
    void conditionStatisticsAreOrderedByStructuralPathAndServerCapped() {
        SegmentPreviewService service =
                PreviewTestSupport.service(provider, repository, PreviewTestSupport.fixtureBackend(dataset));

        PreviewResponse unbounded = service.preview(
                PreviewTestSupport.body(PreviewTestSupport.multiPathDocument(), false, true));
        List<String> paths = unbounded.conditionStats().stream().map(PreviewConditionStatistic::path).toList();
        assertThat(paths).containsExactly("r", "r.0", "r.1", "r.2");
        assertThat(paths).isSortedAccordingTo(java.util.Comparator.naturalOrder());

        PreviewPolicy capped = new PreviewPolicy(
                PreviewTestSupport.policy().maxRequestBytes(), 2, 100, 1_000_000, Duration.ofHours(24));
        SegmentPreviewService cappedService = PreviewTestSupport.service(
                provider, repository, PreviewTestSupport.fixtureBackend(dataset),
                SegmentValidationFixtures.allowAll(), capped, PreviewTestSupport.hasher(PreviewTestSupport.KEY_A));

        PreviewResponse bounded = cappedService.preview(
                PreviewTestSupport.body(PreviewTestSupport.multiPathDocument(), false, true));
        assertThat(bounded.conditionStats()).hasSize(2);
        assertThat(bounded.conditionStats().stream().map(PreviewConditionStatistic::path))
                .containsExactly("r", "r.0");
    }

    // ------------------------------------------------------------------ helpers

    private static PreviewBackendResult coherent(
            SemanticQueryPlan plan, TrustedExecutionContext context, List<String> memberIds) {
        return StubPreviewBackend.coherent(
                plan, context, memberIds, "preview-stub-backend", "stub-v1");
    }

    private static PreviewBackendResult coherent(
            SemanticQueryPlan plan, TrustedExecutionContext context, List<String> memberIds, String backendId) {
        return StubPreviewBackend.coherent(plan, context, memberIds, backendId, "stub-v1");
    }

    private static PreviewException failureOf(SegmentPreviewService service, byte[] body) {
        try {
            service.preview(body);
            fail("expected a bounded PreviewException");
            return null;
        } catch (PreviewException failure) {
            return failure;
        } catch (RuntimeException unexpected) {
            fail("a non-bounded exception escaped the service: " + unexpected.getClass().getName());
            return null;
        }
    }

    /** Republish helpers, so a mutation always produces an internally coherent but wrong result. */
    private static final class Rebuilt {

        private Rebuilt() {
        }

        static PreviewBackendResult withPlanHash(PreviewBackendResult r, ContentHash planHash) {
            return republish(r, planHash, r.metadataSnapshotId(), r.dataScopeId(), r.asOf(), r.timezone(),
                    r.dataWatermark(), r.backendId(), r.backendVersion());
        }

        static PreviewBackendResult withSnapshotId(PreviewBackendResult r, String snapshotId) {
            return republish(r, r.planHash(), snapshotId, r.dataScopeId(), r.asOf(), r.timezone(),
                    r.dataWatermark(), r.backendId(), r.backendVersion());
        }

        static PreviewBackendResult withDataScopeId(PreviewBackendResult r, String dataScopeId) {
            return republish(r, r.planHash(), r.metadataSnapshotId(), dataScopeId, r.asOf(), r.timezone(),
                    r.dataWatermark(), r.backendId(), r.backendVersion());
        }

        static PreviewBackendResult withAsOf(PreviewBackendResult r, Instant asOf) {
            return republish(r, r.planHash(), r.metadataSnapshotId(), r.dataScopeId(), asOf, r.timezone(),
                    r.dataWatermark(), r.backendId(), r.backendVersion());
        }

        static PreviewBackendResult withTimezone(PreviewBackendResult r, String timezone) {
            return republish(r, r.planHash(), r.metadataSnapshotId(), r.dataScopeId(), r.asOf(), timezone,
                    r.dataWatermark(), r.backendId(), r.backendVersion());
        }

        static PreviewBackendResult withWatermark(PreviewBackendResult r, Instant watermark) {
            return republish(r, r.planHash(), r.metadataSnapshotId(), r.dataScopeId(), r.asOf(), r.timezone(),
                    watermark, r.backendId(), r.backendVersion());
        }

        static PreviewBackendResult withBackendId(PreviewBackendResult r, String backendId) {
            return republish(r, r.planHash(), r.metadataSnapshotId(), r.dataScopeId(), r.asOf(), r.timezone(),
                    r.dataWatermark(), backendId, r.backendVersion());
        }

        static PreviewBackendResult withBackendVersion(PreviewBackendResult r, String backendVersion) {
            return republish(r, r.planHash(), r.metadataSnapshotId(), r.dataScopeId(), r.asOf(), r.timezone(),
                    r.dataWatermark(), r.backendId(), backendVersion);
        }

        private static PreviewBackendResult republish(
                PreviewBackendResult r,
                ContentHash planHash,
                String snapshotId,
                String dataScopeId,
                Instant asOf,
                String timezone,
                Instant watermark,
                String backendId,
                String backendVersion) {
            return PreviewBackendResult.publish(
                    r.memberIdSet(), r.conditionStatistics(), planHash, snapshotId, dataScopeId, asOf,
                    timezone, watermark, backendId, backendVersion);
        }
    }
}