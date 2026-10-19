package com.company.campaign.builder.segment;

import com.company.campaign.builder.segment.testsupport.SliceTestFixtures;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.segment.intent.SegmentIntent;
import com.company.campaign.segment.intent.SegmentIntentCodec;
import com.company.campaign.segment.resolution.ResolutionResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The fail-closed lowering boundary of the deterministic resolved-Intent to DSL compiler.
 *
 * <p>A well-formed but unlowerable Intent must never make {@link ResolvedIntentDslCompiler#compile} or
 * {@link DevelopmentAiToSqlService#invoke} throw a raw runtime exception. Numeric overflow, a value beyond the
 * T05 representable domain, a range the typed-value constructor refuses and a derived node identity beyond the
 * T05 predicate bound all become one stable {@code INVALID} result that never echoes the raw value.
 */
class ResolvedIntentDslCompilerLoweringTest {

    private static final String COUNT_UTTERANCE = "订单数超过3";
    private static final String MONEY_UTTERANCE = "过去90天消费超过1000元";

    private static final String POSITIVE_OVERFLOW = "9223372036854775808";
    private static final String NEGATIVE_OVERFLOW = "-9223372036854775809";
    private static final String BEYOND_DECIMAL_PRECISION = "9".repeat(39);

    @Test
    void aPositiveIntegerBeyondTheT05RangeFailsClosedWithoutEchoingTheValue() {
        assertUnlowerable(intent(COUNT_UTTERANCE, integerMetricJson(POSITIVE_OVERFLOW)), POSITIVE_OVERFLOW);
    }

    @Test
    void aNegativeIntegerBeyondTheT05RangeFailsClosedWithoutEchoingTheValue() {
        assertUnlowerable(intent(COUNT_UTTERANCE, integerMetricJson(NEGATIVE_OVERFLOW)), NEGATIVE_OVERFLOW);
    }

    @Test
    void aMoneyLexemeBeyondTheT05DecimalPrecisionFailsClosedWithoutEchoingTheValue() {
        assertUnlowerable(intent(MONEY_UTTERANCE, moneyMetricJson(BEYOND_DECIMAL_PRECISION)),
                BEYOND_DECIMAL_PRECISION);
    }

    @Test
    void anInvertedRangeRefusedByTheT05ConstructorFailsClosedWithoutEchoingTheBounds() {
        DslCompilationResult result = compile(intent(COUNT_UTTERANCE, invertedRangeJson()));

        assertThat(result.isResolved()).isFalse();
        assertThat(result.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(result.failureCode()).isEqualTo(SegmentDslCompilationErrorCodes.VALUE_NOT_REPRESENTABLE);
        assertThat(result.failureClauseId()).isEqualTo("c1");
        // Neither the bounds nor any part of the Intent payload is echoed.
        assertThat(result.toString())
                .doesNotContain("RANGE").doesNotContain(COUNT_UTTERANCE).doesNotContain("lower");
    }

    @Test
    void aDerivedNodeIdentityBeyondTheT05PredicateBoundFailsClosed() {
        // T10 permits an expression nested to depth 32 and a clause-level negation. The compiler maps every
        // expression node to one predicate node, so clause-level negation adds one more derived node than T10
        // counted and the published tree reaches depth 33, one beyond the T05 predicate bound. No raw value or
        // T05 exception may escape.
        SegmentIntent intent = intent("Gold会员", deeplyNegatedTierJson());
        DslCompilationResult result = compile(intent);

        assertThat(result.isResolved()).isFalse();
        assertThat(result.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(result.failureCode()).isEqualTo(SegmentDslCompilationErrorCodes.INTERNAL_LOWERING_FAILED);
        assertThat(result.document()).isNull();
    }

    @Test
    void aWellFormedButUnlowerableIntentNeverMakesTheServiceThrow() {
        String json = integerMetricJson(POSITIVE_OVERFLOW);
        SliceTestFixtures.StubModelPort port = SliceTestFixtures.StubModelPort.returning(json);
        DevelopmentAiToSqlService service = SliceTestFixtures.service(port);

        assertThatCode(() -> {
            DevelopmentAiToSqlResult result =
                    service.invoke(SliceTestFixtures.request(COUNT_UTTERANCE, port));
            assertThat(result.disposition()).isEqualTo(Disposition.INVALID);
            assertThat(result.failureCode()).isEqualTo(SegmentDslCompilationErrorCodes.VALUE_NOT_REPRESENTABLE);
            assertThat(result.sql()).isNull();
            assertThat(result.toString()).doesNotContain(POSITIVE_OVERFLOW);
        }).doesNotThrowAnyException();
    }

    @Test
    void aWellFormedButTooDeepIntentNeverMakesTheServiceThrow() {
        SliceTestFixtures.StubModelPort port =
                SliceTestFixtures.StubModelPort.returning(deeplyNegatedTierJson());
        DevelopmentAiToSqlService service = SliceTestFixtures.service(port);

        assertThatCode(() -> {
            DevelopmentAiToSqlResult result =
                    service.invoke(SliceTestFixtures.request("Gold会员", port));
            assertThat(result.disposition()).isEqualTo(Disposition.INVALID);
            assertThat(result.failureCode()).isEqualTo(SegmentDslCompilationErrorCodes.INTERNAL_LOWERING_FAILED);
            assertThat(result.sql()).isNull();
        }).doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------ helpers

    private static void assertUnlowerable(SegmentIntent intent, String rawValue) {
        DslCompilationResult result = compile(intent);

        assertThat(result.isResolved()).isFalse();
        assertThat(result.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(result.failureCode()).isEqualTo(SegmentDslCompilationErrorCodes.VALUE_NOT_REPRESENTABLE);
        assertThat(result.document()).isNull();
        // The failure code, the clause id and the whole result must never echo the rejected raw value.
        assertThat(result.failureCode()).doesNotContain(rawValue);
        assertThat(result.toString()).doesNotContain(rawValue);
    }

    private static DslCompilationResult compile(SegmentIntent intent) {
        ResolutionResult resolution = SliceTestFixtures.resolve(
                intent, SliceTestFixtures.snapshot(), SliceTestFixtures.policy());
        assertThat(resolution.disposition().isResolved())
                .as("the adversarial Intent is well-formed and resolves; only lowering must fail")
                .isTrue();
        return new ResolvedIntentDslCompiler(SliceTestFixtures.policy())
                .compile(intent, resolution, SliceTestFixtures.snapshot(), SliceTestFixtures.registry());
    }

    private static SegmentIntent intent(String utterance, String json) {
        return new SegmentIntentCodec().parse(
                json.getBytes(StandardCharsets.UTF_8), utterance, SliceTestFixtures.registry());
    }

    private static String integerMetricJson(String raw) {
        return SliceTestFixtures.singleClauseJson(COUNT_UTTERANCE, "ORDER_COUNT", "METRIC",
                "\"operator\":\"GREATER_THAN\",",
                "\"value\":{\"kind\":\"INTEGER\",\"raw\":\"" + raw + "\"},"
                        + "\"window\":{\"kind\":\"LIFETIME\"},\"aggregation\":\"COUNT\",",
                COUNT_UTTERANCE, 0);
    }

    private static String moneyMetricJson(String raw) {
        return SliceTestFixtures.singleClauseJson(MONEY_UTTERANCE, "SPEND", "METRIC",
                "\"operator\":\"GREATER_THAN\",",
                "\"value\":{\"kind\":\"MONEY\",\"raw\":\"" + raw + "\"},"
                        + "\"window\":{\"kind\":\"ROLLING\",\"amount\":90,\"unit\":\"DAY\"},"
                        + "\"currency\":{\"mode\":\"EXPLICIT\",\"code\":\"CNY\"},\"aggregation\":\"SUM\",",
                MONEY_UTTERANCE, 0);
    }

    private static String invertedRangeJson() {
        return SliceTestFixtures.singleClauseJson(COUNT_UTTERANCE, "ORDER_COUNT", "METRIC",
                "\"operator\":\"BETWEEN\",",
                "\"value\":{\"kind\":\"RANGE\",\"lower\":{\"kind\":\"INTEGER\",\"raw\":\"9\"},"
                        + "\"upper\":{\"kind\":\"INTEGER\",\"raw\":\"1\"},"
                        + "\"includeLower\":true,\"includeUpper\":false},"
                        + "\"window\":{\"kind\":\"LIFETIME\"},\"aggregation\":\"COUNT\",",
                COUNT_UTTERANCE, 0);
    }

    /** One negated clause under thirty-one nested expression-level NOTs (T10 depth 32). */
    private static String deeplyNegatedTierJson() {
        String clause = SliceTestFixtures.clause("c1", "MEMBER_TIER", "ATTRIBUTE", true,
                "\"operator\":\"EQUAL\",", "\"value\":{\"kind\":\"ENUM\",\"raw\":\"Gold\"},",
                "Gold会员", 0);
        String expression = "{\"type\":\"CLAUSE\",\"clauseId\":\"c1\"}";
        for (int index = 0; index < 31; index++) {
            expression = "{\"type\":\"NOT\",\"child\":" + expression + "}";
        }
        return SliceTestFixtures.intentJson(List.of(clause), expression, "[]");
    }
}