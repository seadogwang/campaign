package com.company.campaign.preview.adapter;

import com.company.campaign.preview.PreviewBackendException;
import com.company.campaign.preview.PreviewBackendIdentity;
import com.company.campaign.preview.PreviewBackendResult;
import com.company.campaign.preview.PreviewExecutionBackend;
import com.company.campaign.preview.testsupport.PreviewTestSupport;
import com.company.campaign.query.fixture.FixtureDataset;
import com.company.campaign.query.fixture.testsupport.FixtureTestSupport;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.query.testsupport.QueryPlanFixtures;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import com.company.campaign.security.context.TrustedExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Requirement 14: the fixture adapter delegates a genuine plan to the accepted T09A executor, re-proves and
 * forwards every result pin, requires an explicit FX snapshot for a conversion, and never exposes fixture
 * identity, payload rows or a path in the translated result.
 */
class FixturePreviewExecutionBackendTest {

    @TempDir
    Path tempDir;

    private FixtureDataset dataset;
    private TrustedExecutionContext context;

    @BeforeEach
    void setUp() {
        dataset = PreviewTestSupport.dataset(tempDir);
        context = SegmentValidationFixtures.context();
    }

    @Test
    void delegatesAGenuinePlanAndForwardsEveryResultPin() {
        SemanticQueryPlan plan = QueryPlanFixtures.plan(PreviewTestSupport.goldDocument());
        FixturePreviewExecutionBackend backend = PreviewTestSupport.fixtureBackend(dataset);

        PreviewBackendResult result = backend.execute(plan, context);

        PreviewBackendIdentity identity = backend.identity();
        assertThat(identity.id()).isEqualTo(FixturePreviewExecutionBackend.BACKEND_ID);
        assertThat(identity.version()).isEqualTo(PreviewTestSupport.BACKEND_VERSION);
        assertThat(result.backendId()).isEqualTo(identity.id());
        assertThat(result.backendVersion()).isEqualTo(identity.version());
        assertThat(result.planHash()).isEqualTo(plan.planHash());
        assertThat(result.metadataSnapshotId()).isEqualTo(plan.metadataSnapshotId());
        assertThat(result.metadataSnapshotId()).isEqualTo(context.snapshotRef().metadataSnapshotId());
        assertThat(result.dataScopeId()).isEqualTo(plan.dataScopePredicate().dataScopeId());
        assertThat(result.asOf()).isEqualTo(context.snapshotRef().asOf());
        assertThat(result.timezone()).isEqualTo(context.snapshotRef().timezone().getId());
        assertThat(result.dataWatermark()).isEqualTo(context.snapshotRef().dataWatermark());
        assertThat(result.memberIdSet().memberIds()).containsExactly("m-001", "m-003", "m-006");
        assertThat(result.matchedCount()).isEqualTo(3);
        assertThat(result.conditionStatistics()).isNotEmpty();
    }

    @Test
    void theTranslatedResultIsDeterministicAndExposesNoFixtureIdentity() {
        SemanticQueryPlan plan = QueryPlanFixtures.plan(PreviewTestSupport.goldDocument());
        FixturePreviewExecutionBackend backend = PreviewTestSupport.fixtureBackend(dataset);

        PreviewBackendResult first = backend.execute(plan, context);
        PreviewBackendResult second = backend.execute(plan, context);

        assertThat(first.resultHash()).isEqualTo(second.resultHash());
        assertThat(first.canonicalBytes()).isEqualTo(second.canonicalBytes());

        String rendered = first.toString();
        assertThat(rendered)
                .doesNotContain(FixtureTestSupport.FIXTURE_ID)
                .doesNotContain(FixtureTestSupport.PAYLOAD_REFERENCE)
                .doesNotContain("m-001")
                .doesNotContain(dataset.fixtureId());
    }

    @Test
    void thePortAndAdapterExposeNoFixtureIdentityAccessor() {
        List<String> methods = Arrays.stream(PreviewBackendResult.class.getMethods())
                .map(Method::getName)
                .toList();
        assertThat(methods).noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("fixture"));
        assertThat(methods).noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("payload"));
        assertThat(methods).contains("memberIdSet", "resultHash", "backendId");
    }

    @Test
    void aContextThatDoesNotMatchThePlanFailsClosed() {
        SemanticQueryPlan plan = QueryPlanFixtures.plan(PreviewTestSupport.goldDocument());
        FixturePreviewExecutionBackend backend = PreviewTestSupport.fixtureBackend(dataset);
        TrustedExecutionContext foreign = SegmentValidationFixtures.foreignOwnerContext();

        assertThatThrownBy(() -> backend.execute(plan, foreign))
                .isInstanceOf(PreviewBackendException.class);
        assertThatThrownBy(() -> backend.execute(null, context))
                .isInstanceOf(PreviewBackendException.class);
    }

    @Test
    void aConversionRequiresAnExplicitFxSnapshotAndNeverInventsARate() {
        SemanticQueryPlan conversionPlan = QueryPlanFixtures.plan(SegmentValidationFixtures.validDocument());
        FixturePreviewExecutionBackend withoutFx = new FixturePreviewExecutionBackend(
                dataset, FixtureTestSupport.standardMapping(), FixtureTestSupport.standardScope(), null, "v1");

        assertThatThrownBy(() -> withoutFx.execute(conversionPlan, context))
                .isInstanceOf(PreviewBackendException.class);

        FixturePreviewExecutionBackend withFx = new FixturePreviewExecutionBackend(
                dataset, FixtureTestSupport.standardMapping(), FixtureTestSupport.standardScope(),
                FixtureTestSupport.cnyFx("7.0"), "v1");
        PreviewBackendResult result = withFx.execute(conversionPlan, context);
        assertThat(result.matchedCount()).isGreaterThanOrEqualTo(0);
        assertThat(result.planHash()).isEqualTo(conversionPlan.planHash());
    }

    @Test
    void theAdapterOnlyImplementsTheNarrowReadOnlyPort() {
        assertThat(PreviewExecutionBackend.class.getDeclaredMethods())
                .extracting(Method::getName)
                .containsExactlyInAnyOrder("identity", "execute");
    }

    // ---------------------------------------------------------------- stable adapter inputs

    @Test
    void aNullFixtureInputProducesOneBoundedNonDisclosingBackendFailure() {
        assertInvalidResult(() -> new FixturePreviewExecutionBackend(
                null, FixtureTestSupport.standardMapping(), FixtureTestSupport.standardScope(), null, "v1"));
        assertInvalidResult(() -> new FixturePreviewExecutionBackend(
                dataset, null, FixtureTestSupport.standardScope(), null, "v1"));
        assertInvalidResult(() -> new FixturePreviewExecutionBackend(
                dataset, FixtureTestSupport.standardMapping(), null, null, "v1"));
    }

    @Test
    void aNullOrBlankBackendVersionProducesOneBoundedNonDisclosingBackendFailure() {
        assertInvalidResult(() -> new FixturePreviewExecutionBackend(
                dataset, FixtureTestSupport.standardMapping(), FixtureTestSupport.standardScope(), null, null));
        assertInvalidResult(() -> new FixturePreviewExecutionBackend(
                dataset, FixtureTestSupport.standardMapping(), FixtureTestSupport.standardScope(), null, ""));
        assertInvalidResult(() -> new FixturePreviewExecutionBackend(
                dataset, FixtureTestSupport.standardMapping(), FixtureTestSupport.standardScope(), null, "   "));
    }

    @Test
    void theOptionalFxInputStaysOptionalAtConstruction() {
        // The FX snapshot is the one deliberately optional input: a conversion-less plan needs none.
        FixturePreviewExecutionBackend withoutFx = new FixturePreviewExecutionBackend(
                dataset, FixtureTestSupport.standardMapping(), FixtureTestSupport.standardScope(), null, "v1");
        SemanticQueryPlan plan = QueryPlanFixtures.plan(PreviewTestSupport.goldDocument());

        PreviewBackendResult result = withoutFx.execute(plan, context);

        assertThat(result.matchedCount()).isEqualTo(3);
        assertThat(withoutFx.identity().version()).isEqualTo("v1");
    }

    /** Every malformed public constructor input fails as one bounded backend outcome, never a raw NPE. */
    private static void assertInvalidResult(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOf(PreviewBackendException.class)
                .satisfies(thrown -> {
                    PreviewBackendException failure = (PreviewBackendException) thrown;
                    assertThat(failure.kind()).isEqualTo(PreviewBackendException.Kind.INVALID_RESULT);
                    assertThat(failure.getCause()).isNull();
                    assertThat(failure.getMessage())
                            .isEqualTo("The Preview execution backend did not return a usable result.");
                });
    }
}