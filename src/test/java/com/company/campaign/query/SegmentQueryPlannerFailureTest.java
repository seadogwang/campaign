package com.company.campaign.query;

import com.company.campaign.query.plan.SegmentQueryPlanner;
import com.company.campaign.query.plan.TrustedPlanContext;
import com.company.campaign.query.testsupport.QueryPlanFixtures;
import com.company.campaign.segment.validation.SegmentValidationResult;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Requirement 3: invalid inputs fail closed with stable, bounded, non-disclosing codes rather than raw
 * runtime exceptions or payload echoes.
 */
class SegmentQueryPlannerFailureTest {

    private static final SegmentQueryPlanner PLANNER =
            new SegmentQueryPlanner(SegmentValidationFixtures.allowAll());

    private static SegmentValidationResult genuine() {
        return SegmentValidationFixtures.validate(SegmentValidationFixtures.validDocument());
    }

    private static void assertCode(Runnable call, String expectedCode) {
        assertThatThrownBy(call::run)
                .isInstanceOf(QueryException.class)
                .hasFieldOrPropertyWithValue("code", expectedCode);
    }

    @Test
    void everyNullPublicInputFailsWithTheMissingInputCode() {
        assertCode(() -> PLANNER.plan(null, SegmentValidationFixtures.snapshot(), SegmentValidationFixtures.context()),
                QueryErrorCodes.QUERY_INPUT_MISSING);
        assertCode(() -> PLANNER.plan(genuine(), null, SegmentValidationFixtures.context()),
                QueryErrorCodes.QUERY_INPUT_MISSING);
        assertCode(() -> PLANNER.plan(genuine(), SegmentValidationFixtures.snapshot(), null),
                QueryErrorCodes.QUERY_INPUT_MISSING);
    }

    @Test
    void aRejectedValidationResultFailsWithTheValidationResultCode() {
        SegmentValidationResult rejected = SegmentValidationFixtures.validate(SegmentValidationFixtures.publish(
                SegmentValidationFixtures.attribute("n-unknown", "member.unknown",
                        com.company.campaign.metadata.domain.ComparisonOperator.EQUAL,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.stringValue("x")))));
        assertThat(rejected.valid()).isFalse();

        assertCode(() -> PLANNER.plan(rejected, SegmentValidationFixtures.snapshot(), SegmentValidationFixtures.context()),
                QueryErrorCodes.QUERY_VALIDATION_RESULT_INVALID);
    }

    @Test
    void aWrongSnapshotPinFailsWithTheBindingCode() {
        assertCode(() -> PLANNER.plan(genuine(), SegmentValidationFixtures.snapshot("metadata-99", "1"),
                        SegmentValidationFixtures.context()),
                QueryErrorCodes.QUERY_METADATA_BINDING_MISMATCH);
    }

    @Test
    void aWrongSnapshotOwnerFailsWithTheOwnerCode() {
        assertCode(() -> PLANNER.plan(genuine(), SegmentValidationFixtures.snapshot(),
                        QueryPlanFixtures.context("tenant-999", "program-001", "scope-008")),
                QueryErrorCodes.QUERY_METADATA_OWNER_MISMATCH);
    }

    @Test
    void aTrustedContextPinningAnotherSnapshotFailsWithTheContextSnapshotCode() {
        assertCode(() -> PLANNER.plan(genuine(), SegmentValidationFixtures.snapshot(),
                        QueryPlanFixtures.contextPinning("metadata-99", "tenant-001", "program-001", "scope-008")),
                QueryErrorCodes.QUERY_CONTEXT_SNAPSHOT_MISMATCH);
    }

    @Test
    void failuresDoNotEchoIdentifiersOperandsOrCauses() {
        QueryException failure = catchThrowableOfType(
                QueryException.class,
                () -> PLANNER.plan(genuine(), SegmentValidationFixtures.snapshot("metadata-99", "1"),
                        QueryPlanFixtures.context("tenant-999", "program-001", "scope-008")));

        String rendered = failure.getMessage() + failure.details();
        assertThat(rendered)
                .doesNotContain("metadata-99")
                .doesNotContain("tenant-999")
                .doesNotContain("scope-008")
                .doesNotContain("member.unknown")
                .doesNotContain("1000");
    }

    @Test
    void aTrustedPlanContextRejectsMalformedTimeAndCurrencyInputs() {
        assertCode(() -> new TrustedPlanContext("t-1", "p-1", "m-1", "u-1", "s-1", null,
                        "Asia/Shanghai", "CNY", QueryPlanFixtures.WATERMARK),
                QueryErrorCodes.QUERY_TIME_CONTEXT_INVALID);
        assertCode(() -> new TrustedPlanContext("t-1", "p-1", "m-1", "u-1", "s-1", QueryPlanFixtures.AS_OF,
                        "Not/AZone", "CNY", QueryPlanFixtures.WATERMARK),
                QueryErrorCodes.QUERY_TIME_CONTEXT_INVALID);
        assertCode(() -> new TrustedPlanContext("t-1", "p-1", "m-1", "u-1", "s-1", QueryPlanFixtures.AS_OF,
                        "Asia/Shanghai", "cny", QueryPlanFixtures.WATERMARK),
                QueryErrorCodes.QUERY_TIME_CONTEXT_INVALID);
    }
}