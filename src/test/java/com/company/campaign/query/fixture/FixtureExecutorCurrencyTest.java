package com.company.campaign.query.fixture;

import com.company.campaign.evaluation.segment.contract.MemberIdSet;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import com.company.campaign.testsupport.ContractAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.CNY_BALANCE_UNIVERSE;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.EXECUTOR;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.cnyBalancePayload;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.cnyFx;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.load;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.plan;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.singleCurrencyMapping;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.singleCurrencySnapshot;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.standardMapping;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.standardPayload;
import static com.company.campaign.query.fixture.testsupport.FixtureTestSupport.standardScope;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 8: a conversion fails closed without a hash-pinned, time-pinned, exact-rate snapshot, and
 * succeeds with exact decimal arithmetic; a single-currency requirement is enforced rather than converted.
 */
class FixtureExecutorCurrencyTest {

    @TempDir
    Path repositoryRoot;

    @Test
    void aRequiredConversionWithoutAnySnapshotFailsClosed() {
        FixtureDataset dataset = load(repositoryRoot, standardPayload());

        ContractAssertions.assertFailsWithCode(
                () -> EXECUTOR.execute(
                        plan(balanceGreaterThan("250.00")), dataset, standardMapping(), standardScope(), null),
                FixtureErrorCodes.FIXTURE_FX_REQUIRED);
    }

    @Test
    void aSnapshotThatDoesNotTargetTheReportingCurrencyFailsClosed() {
        FixtureDataset dataset = load(repositoryRoot, standardPayload());
        FxRateSnapshot wrongTarget = FxRateSnapshot.publish(
                "fx-x", "1", "USD", Instant.parse("2026-10-01T00:00:00Z"), Map.of("CNY", BigDecimal.ONE));

        ContractAssertions.assertFailsWithCode(
                () -> EXECUTOR.execute(
                        plan(balanceGreaterThan("250.00")), dataset, standardMapping(), standardScope(),
                        wrongTarget),
                FixtureErrorCodes.FIXTURE_FX_INVALID);
    }

    @Test
    void aSnapshotObservedAfterTheEvaluationInstantFailsClosed() {
        FixtureDataset dataset = load(repositoryRoot, standardPayload());
        FxRateSnapshot future = FxRateSnapshot.publish(
                "fx-x", "1", "CNY", Instant.parse("2026-10-02T00:00:00Z"),
                Map.of("USD", new BigDecimal("7.00")));

        ContractAssertions.assertFailsWithCode(
                () -> EXECUTOR.execute(
                        plan(balanceGreaterThan("250.00")), dataset, standardMapping(), standardScope(), future),
                FixtureErrorCodes.FIXTURE_FX_INVALID);
    }

    @Test
    void aSnapshotWithoutTheRequiredRateFailsClosed() {
        FixtureDataset dataset = load(repositoryRoot, standardPayload());
        FxRateSnapshot incomplete = FxRateSnapshot.publish(
                "fx-x", "1", "CNY", Instant.parse("2026-10-01T00:00:00Z"),
                Map.of("EUR", new BigDecimal("8.00")));

        ContractAssertions.assertFailsWithCode(
                () -> EXECUTOR.execute(
                        plan(balanceGreaterThan("250.00")), dataset, standardMapping(), standardScope(), incomplete),
                FixtureErrorCodes.FIXTURE_FX_INVALID);
    }

    @Test
    void anExactRateConvertsAmountsExactly() {
        MemberIdSet matched = EXECUTOR.execute(
                plan(balanceGreaterThan("250.00")),
                load(repositoryRoot, standardPayload()),
                standardMapping(),
                standardScope(),
                cnyFx("7.00")).memberIdSet();

        assertThat(matched).isEqualTo(MemberIdSet.of("m-003", "m-005", "m-006"));

        MemberIdSet exact = EXECUTOR.execute(
                plan(SegmentValidationFixtures.attribute(
                        "n-balance", "member.balance", ComparisonOperator.EQUAL,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.moneyValue("2175.00", "CNY")))),
                load(repositoryRoot, standardPayload()),
                standardMapping(),
                standardScope(),
                cnyFx("7.25")).memberIdSet();

        assertThat(exact).isEqualTo(MemberIdSet.of("m-003"));
    }

    @Test
    void aSingleCurrencyRequirementRejectsAMixedCurrencyColumn() {
        MetadataSnapshot snapshot = singleCurrencySnapshot();
        FixtureDataset dataset = load(repositoryRoot, standardPayload());

        ContractAssertions.assertFailsWithCode(
                () -> EXECUTOR.execute(
                        plan(snapshot, singleCurrencyGreaterThan("150.00")), dataset, singleCurrencyMapping(),
                        standardScope(), cnyFx("7.00")),
                FixtureErrorCodes.FIXTURE_CURRENCY_MISMATCH);
    }

    @Test
    void aSingleCurrencyRequirementMatchesWhenEveryRowSharesOneCurrency() {
        MetadataSnapshot snapshot = singleCurrencySnapshot();
        FixtureDataset dataset = load(repositoryRoot, cnyBalancePayload(), CNY_BALANCE_UNIVERSE);

        MemberIdSet matched = EXECUTOR.execute(
                plan(snapshot, singleCurrencyGreaterThan("150.00")), dataset, singleCurrencyMapping(),
                standardScope(), null).memberIdSet();

        assertThat(matched).isEqualTo(MemberIdSet.of("m-002"));
    }

    private static Predicate balanceGreaterThan(String amount) {
        return SegmentValidationFixtures.attribute(
                "n-balance", "member.balance", ComparisonOperator.GREATER_THAN,
                SegmentValidationFixtures.value(SegmentValidationFixtures.moneyValue(amount, "CNY")));
    }

    private static Predicate singleCurrencyGreaterThan(String amount) {
        return SegmentValidationFixtures.attribute(
                "n-balance", "member.single_balance", ComparisonOperator.GREATER_THAN,
                SegmentValidationFixtures.value(SegmentValidationFixtures.moneyValue(amount, "CNY")));
    }
}