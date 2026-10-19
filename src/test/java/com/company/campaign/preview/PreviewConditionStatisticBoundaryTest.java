package com.company.campaign.preview;

import com.company.campaign.metadata.repository.MetadataSnapshotRepository;
import com.company.campaign.preview.adapter.FixturePreviewExecutionBackend;
import com.company.campaign.preview.testsupport.PreviewTestSupport;
import com.company.campaign.preview.testsupport.RecordingTrustedContextProvider;
import com.company.campaign.preview.testsupport.StubPreviewBackend;
import com.company.campaign.query.fixture.FixtureDataset;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.query.testsupport.QueryPlanFixtures;
import com.company.campaign.segment.dsl.contract.SegmentDslLimits;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * Requirement: only the exact accepted T08/T09 structural-path grammar may be response-visible, so a faulty
 * backend cannot place a member identifier, a Metadata identifier, SQL text or a secret-like value in a
 * {@code PreviewResponse.conditionStats[].path}.
 */
class PreviewConditionStatisticBoundaryTest {

    private static final List<String> ACCEPTED_PATHS = List.of(
            "r",
            "r.0",
            "r.1",
            "r.9999",
            "r.not",
            "r.exists",
            "r.0.exists",
            "r.0.not",
            "r.exists.0",
            "r.0.exists.1.not",
            "r.1.exists");

    private static final List<String> HOSTILE_PATHS = List.of(
            "m-001",
            "member-1234567890",
            "metadata-17",
            "snapshot-abcdef",
            "r; DROP TABLE members",
            "r-- comment",
            "r/*comment*/",
            "r.0 UNION SELECT secret",
            "SELECT secret FROM members",
            "r.0.exists(\"x\")",
            "",
            "   ",
            "x",
            "R",
            "root",
            ".r",
            "r0",
            "r.",
            "r..0",
            "r.0.",
            "r.notexists",
            "r.existsx",
            "r.0exists",
            "r/0",
            "r[0]",
            "r.0\n",
            " r.0",
            "r.0 ",
            "r.\t0",
            "r.+1",
            "r.-1",
            "r.01",
            "r.00",
            "r.0000",
            "r.1.02",
            "r.10000",
            "r.99999",
            "r.2147483647",
            "r.0.999999999",
            "r" + ".0".repeat(100),
            "r" + ".not".repeat(65),
            "r" + ".exists".repeat(40));

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

    // ------------------------------------------------------------------ positive grammar

    @Test
    void everyAcceptedStructuralPathIsAdmitted() {
        for (String path : ACCEPTED_PATHS) {
            assertThatCode(() -> new PreviewConditionStatistic(path, 1, 0, 0))
                    .as(path)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void everyRealFixtureStatisticPathIsAdmittedAndProjected() {
        SemanticQueryPlan plan = QueryPlanFixtures.plan(PreviewTestSupport.goldDocument());
        FixturePreviewExecutionBackend backend = PreviewTestSupport.fixtureBackend(dataset);

        int checked = backend.execute(plan, SegmentValidationFixtures.context())
                .conditionStatistics().size();
        assertThat(checked).isGreaterThan(0);

        SegmentPreviewService service = PreviewTestSupport.service(provider, repository, backend);
        PreviewResponse response = service.preview(
                PreviewTestSupport.body(PreviewTestSupport.goldDocument(), false, true));
        assertThat(response.conditionStats()).isNotEmpty();
        for (PreviewConditionStatistic statistic : response.conditionStats()) {
            assertThatCode(() -> new PreviewConditionStatistic(
                    statistic.path(), statistic.trueCount(), statistic.falseCount(), statistic.unknownCount()))
                    .as(statistic.path())
                    .doesNotThrowAnyException();
        }
    }

    // ------------------------------------------------------------------ adversarial grammar

    @Test
    void everyHostilePathIsRefusedWithoutEchoingTheSuppliedValue() {
        for (String hostilePath : HOSTILE_PATHS) {
            assertThatThrownBy(() -> new PreviewConditionStatistic(hostilePath, 1, 0, 0))
                    .as(String.valueOf(hostilePath))
                    .isInstanceOf(PreviewException.class)
                    .satisfies(thrown -> {
                        PreviewException failure = (PreviewException) thrown;
                        assertThat(failure.code()).isEqualTo(PreviewErrorCodes.EXECUTION_RESULT_INCOHERENT);
                        // The message is the closed constant and the only detail is the closed invariant, so
                        // no supplied value can be echoed regardless of its content.
                        assertThat(failure.getMessage())
                                .isEqualTo(PreviewErrorCodes.message(PreviewErrorCodes.EXECUTION_RESULT_INCOHERENT));
                        assertThat(failure.details()).containsOnlyKeys("invariant");
                        assertThat(failure.details()).containsEntry("invariant", "conditionStatistic");
                        assertThat(failure.getCause()).isNull();
                    });
        }
    }

    @Test
    void excessiveDepthAndLengthAreRefusedAtExplicitAbsoluteBounds() {
        String overDeep = "r" + ".0".repeat(100);
        String overLong = "r" + ".exists".repeat(40);
        assertThat(overDeep.length()).isLessThanOrEqualTo(PreviewLimits.MAX_CONDITION_PATH_LENGTH);
        assertThat(overLong.length()).isGreaterThan(PreviewLimits.MAX_CONDITION_PATH_LENGTH);
        assertThatThrownBy(() -> new PreviewConditionStatistic(overDeep, 1, 0, 0))
                .isInstanceOf(PreviewException.class);
        assertThatThrownBy(() -> new PreviewConditionStatistic(overLong, 1, 0, 0))
                .isInstanceOf(PreviewException.class);
    }

    @Test
    void exactlyTheMaximumAcceptedPredicateDepthIsAdmittedAndOneMoreStepIsRefused() {
        // The root r is depth 1 and each step descends one level, so the deepest accepted path has
        // MAX_PREDICATE_DEPTH - 1 child steps; one more step belongs to no accepted T05/T08 predicate tree.
        int maxSteps = PreviewLimits.MAX_CONDITION_PATH_STEPS;
        assertThat(maxSteps).isEqualTo(SegmentDslLimits.MAX_PREDICATE_DEPTH - 1);

        String deepestAccepted = "r" + ".0".repeat(maxSteps);
        String oneStepTooDeep = deepestAccepted + ".0";
        assertThat(deepestAccepted.length()).isLessThanOrEqualTo(PreviewLimits.MAX_CONDITION_PATH_LENGTH);

        assertThatCode(() -> new PreviewConditionStatistic(deepestAccepted, 1, 0, 0))
                .as(deepestAccepted)
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> new PreviewConditionStatistic(oneStepTooDeep, 1, 0, 0))
                .isInstanceOf(PreviewException.class)
                .satisfies(thrown -> {
                    PreviewException failure = (PreviewException) thrown;
                    assertThat(failure.code()).isEqualTo(PreviewErrorCodes.EXECUTION_RESULT_INCOHERENT);
                    assertThat(failure.details()).containsEntry("invariant", "conditionStatistic");
                });
    }

    @Test
    void countsMustStillBeNonNegative() {
        assertThatThrownBy(() -> new PreviewConditionStatistic("r", -1, 0, 0))
                .isInstanceOf(PreviewException.class)
                .satisfies(thrown -> assertThat(((PreviewException) thrown).code())
                        .isEqualTo(PreviewErrorCodes.EXECUTION_RESULT_INCOHERENT));
    }

    // ------------------------------------------------------------------ service-level containment

    @Test
    void aFaultyBackendHostilePathNeverReachesTheResponse() {
        for (String hostilePath : HOSTILE_PATHS) {
            String backendId = "preview-hostile-backend";
            StubPreviewBackend stub = new StubPreviewBackend(backendId, (plan, context) -> {
                // A faulty backend tries to make a hostile value response-visible; the Preview-owned record
                // refuses it before any result can be published.
                new PreviewConditionStatistic(hostilePath, 1, 0, 0);
                return StubPreviewBackend.coherent(
                        plan, context, List.of("m-001"), backendId, StubPreviewBackend.DEFAULT_VERSION);
            });
            SegmentPreviewService service = PreviewTestSupport.service(provider, repository, stub);

            try {
                service.preview(PreviewTestSupport.body(PreviewTestSupport.goldDocument(), false, true));
                fail("a hostile statistic path must never be projected");
            } catch (PreviewException failure) {
                // Whichever bounded code the boundary maps it to, the message is the closed constant and the
                // only detail is a closed invariant name; the supplied value is never echoed.
                assertThat(failure.code()).as(hostilePath).isNotBlank();
                assertThat(failure.getMessage())
                        .isEqualTo(PreviewErrorCodes.message(failure.code()));
                assertThat(failure.details()).containsOnlyKeys("invariant");
            }
        }
    }
}