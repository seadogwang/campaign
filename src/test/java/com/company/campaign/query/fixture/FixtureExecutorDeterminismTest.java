package com.company.campaign.query.fixture;

import com.company.campaign.evaluation.segment.contract.MemberIdSet;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;

import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.EXECUTOR;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.cnyFx;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.load;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.plan;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.standardMapping;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.standardPayload;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.standardScope;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 10: one hundred identical executions produce identical sets, condition statistics, canonical
 * bytes and hashes, and the result carries every plan/fixture/context pin it was produced from.
 */
class FixtureExecutorDeterminismTest {

    @TempDir
    Path repositoryRoot;

    @Test
    void oneHundredIdenticalRunsProduceByteIdenticalResults() {
        FixtureDataset dataset = load(repositoryRoot, standardPayload());
        SemanticQueryPlan plan = plan(predicate());
        FxRateSnapshot fx = cnyFx("7.00");

        FixtureExecutionResult reference = EXECUTOR.execute(
                plan, dataset, standardMapping(), standardScope(), fx);
        String referenceBytes = HexFormat.of().formatHex(reference.canonicalBytes());

        assertThat(reference.memberIdSet()).isEqualTo(MemberIdSet.of("m-001", "m-002", "m-003", "m-005"));
        assertThat(reference.matchedCount()).isEqualTo(reference.memberIdSet().size());
        assertThat(reference.resultHash()).isEqualTo(ContentHash.sha256(reference.canonicalBytes()));
        assertThat(reference.planHash()).isEqualTo(plan.planHash());
        assertThat(reference.fixtureId()).isEqualTo(dataset.fixtureId());
        assertThat(reference.fixtureVersion()).isEqualTo(dataset.fixtureVersion());
        assertThat(reference.payloadHash()).isEqualTo(dataset.payloadHash());
        assertThat(reference.metadataSnapshotId()).isEqualTo(plan.metadataSnapshotId());
        assertThat(reference.dataScopeId()).isEqualTo(plan.trustedContext().dataScopeId());
        assertThat(reference.asOf()).isEqualTo(plan.trustedContext().asOf());
        assertThat(reference.timezone()).isEqualTo(plan.trustedContext().timezone());
        assertThat(reference.dataWatermark()).isEqualTo(plan.trustedContext().dataWatermark());
        assertThat(reference.fxSnapshotId()).isEqualTo(fx.snapshotId());
        assertThat(reference.conditionStatistics()).isNotEmpty();

        for (int run = 0; run < 100; run++) {
            FixtureExecutionResult repeated = EXECUTOR.execute(
                    plan, dataset, standardMapping(), standardScope(), fx);
            assertThat(repeated.memberIdSet()).isEqualTo(reference.memberIdSet());
            assertThat(repeated.conditionStatistics()).isEqualTo(reference.conditionStatistics());
            assertThat(HexFormat.of().formatHex(repeated.canonicalBytes())).isEqualTo(referenceBytes);
            assertThat(repeated.resultHash()).isEqualTo(reference.resultHash());
        }
    }

    @Test
    void conditionStatisticsAreKeyedByStableStructuralPaths() {
        FixtureDataset dataset = load(repositoryRoot, standardPayload());
        SemanticQueryPlan plan = plan(predicate());

        FixtureExecutionResult result = EXECUTOR.execute(
                plan, dataset, standardMapping(), standardScope(), cnyFx("7.00"));

        List<String> paths = result.conditionStatistics().stream()
                .map(FixtureExecutionResult.PathStatistic::path)
                .toList();
        assertThat(paths).containsExactlyElementsOf(paths.stream().sorted().toList());
        assertThat(paths).contains("r", "r.0", "r.1");
        assertThat(paths).containsAll(
                plan.windowBindings().stream().map(binding -> binding.path()).toList());
    }

    private static Predicate predicate() {
        return SegmentValidationFixtures.all("n-and", List.of(
                SegmentValidationFixtures.metric(
                        "n-count", "order.count", SegmentValidationFixtures.lifetime(),
                        ComparisonOperator.GREATER_THAN,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.integerValue(0))),
                SegmentValidationFixtures.attribute(
                        "n-balance", "member.balance", ComparisonOperator.GREATER_THAN,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.moneyValue("0.00", "CNY")))));
    }
}