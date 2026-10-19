package com.company.campaign.metadata.domain;

import com.company.campaign.metadata.MetadataErrorCodes;
import com.company.campaign.metadata.testsupport.MetadataFixtures;
import com.company.campaign.metadata.testsupport.RecordOverride;
import com.company.campaign.testsupport.ContractAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Finding 3: the complete aggregate x empty-set behavior matrix fixed by Segment spec section 10 is enforced
 * with the stable semantic-combination code.
 */
class MetadataAggregateEmptySetMatrixTest {

    private static final List<TimeWindowKind> LIFETIME = List.of(TimeWindowKind.LIFETIME);

    static Stream<Arguments> matrix() {
        AggregateEmptySet moneyZero = AggregateEmptySet.zero(SemanticDataType.MONEY);
        AggregateEmptySet integerZero = AggregateEmptySet.zero(SemanticDataType.INTEGER);
        AggregateEmptySet decimalZero = AggregateEmptySet.zero(SemanticDataType.DECIMAL);
        AggregateEmptySet stringZero = AggregateEmptySet.zero(SemanticDataType.STRING);
        AggregateEmptySet dateZero = new AggregateEmptySet(
                EmptySetBehavior.ZERO, new MetadataScalarValue(SemanticDataType.DATE, "2026-01-01"));

        return Stream.of(
                // SUM: zero only when explicitly declared, or NULL.
                Arguments.of("SUM/ZERO_money", AggregateFunction.SUM, SemanticDataType.MONEY, moneyZero, true),
                Arguments.of("SUM/NULL", AggregateFunction.SUM, SemanticDataType.MONEY,
                        AggregateEmptySet.nullValue(), true),
                Arguments.of("SUM/UNDEFINED", AggregateFunction.SUM, SemanticDataType.MONEY,
                        AggregateEmptySet.undefined(), false),
                Arguments.of("SUM/ZERO_wrongType", AggregateFunction.SUM, SemanticDataType.MONEY,
                        integerZero, false),

                // AVG/MIN/MAX: NULL only.
                Arguments.of("AVG/ZERO", AggregateFunction.AVG, SemanticDataType.DECIMAL, decimalZero, false),
                Arguments.of("AVG/NULL", AggregateFunction.AVG, SemanticDataType.DECIMAL,
                        AggregateEmptySet.nullValue(), true),
                Arguments.of("AVG/UNDEFINED", AggregateFunction.AVG, SemanticDataType.DECIMAL,
                        AggregateEmptySet.undefined(), false),
                Arguments.of("MIN/ZERO", AggregateFunction.MIN, SemanticDataType.STRING, stringZero, false),
                Arguments.of("MIN/NULL", AggregateFunction.MIN, SemanticDataType.STRING,
                        AggregateEmptySet.nullValue(), true),
                Arguments.of("MIN/UNDEFINED", AggregateFunction.MIN, SemanticDataType.STRING,
                        AggregateEmptySet.undefined(), false),
                Arguments.of("MAX/ZERO", AggregateFunction.MAX, SemanticDataType.DATE, dateZero, false),
                Arguments.of("MAX/NULL", AggregateFunction.MAX, SemanticDataType.DATE,
                        AggregateEmptySet.nullValue(), true),
                Arguments.of("MAX/UNDEFINED", AggregateFunction.MAX, SemanticDataType.DATE,
                        AggregateEmptySet.undefined(), false),

                // COUNT/COUNT_DISTINCT: zero of INTEGER only.
                Arguments.of("COUNT/ZERO", AggregateFunction.COUNT, SemanticDataType.INTEGER, integerZero, true),
                Arguments.of("COUNT/NULL", AggregateFunction.COUNT, SemanticDataType.INTEGER,
                        AggregateEmptySet.nullValue(), false),
                Arguments.of("COUNT/UNDEFINED", AggregateFunction.COUNT, SemanticDataType.INTEGER,
                        AggregateEmptySet.undefined(), false),
                Arguments.of("COUNT_DISTINCT/ZERO", AggregateFunction.COUNT_DISTINCT,
                        SemanticDataType.INTEGER, integerZero, true),
                Arguments.of("COUNT_DISTINCT/NULL", AggregateFunction.COUNT_DISTINCT,
                        SemanticDataType.INTEGER, AggregateEmptySet.nullValue(), false),
                Arguments.of("COUNT_DISTINCT/UNDEFINED", AggregateFunction.COUNT_DISTINCT,
                        SemanticDataType.INTEGER, AggregateEmptySet.undefined(), false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("matrix")
    void enforcesTheAggregateEmptySetMatrix(
            String name,
            AggregateFunction aggregation,
            SemanticDataType resultType,
            AggregateEmptySet emptySet,
            boolean expectedValid) {
        if (expectedValid) {
            assertThat(publish(aggregation, resultType, emptySet)).as(name).isNotNull();
        } else {
            // Construction itself must reject the contradiction, so it happens inside the assertion.
            ContractAssertions.assertFailsWithCode(
                    () -> publish(aggregation, resultType, emptySet),
                    MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID);
        }
    }

    private static MetadataSnapshot publish(
            AggregateFunction aggregation, SemanticDataType resultType, AggregateEmptySet emptySet) {
        MetricDefinition metric = MetadataFixtures.aggregateMetric(
                "matrix.metric", resultType, aggregation, emptySet, LIFETIME);
        return MetadataFixtures.snapshot(
                List.of(), List.of(metric), List.of(MetadataFixtures.ordersRelation()));
    }

    @Test
    void undefinedRemainsMeaningfulForRelationEmptySemantics() {
        // UNDEFINED is rejected for every metric aggregate but stays available for a Relation.
        RelationDefinition relation = RecordOverride.with(
                MetadataFixtures.ordersRelation(),
                "emptyRelationSemantics", EmptySetBehavior.UNDEFINED);
        assertThat(relation.emptyRelationSemantics()).isEqualTo(EmptySetBehavior.UNDEFINED);
        assertThat(MetadataFixtures.snapshot(List.of(), List.of(), List.of(relation))).isNotNull();
    }

    @Test
    void moneyZeroKeepsTheMetricCurrencyPolicyAsTheOwningCurrencyContext() {
        MetricDefinition metric = MetadataFixtures.aggregateMetric(
                "matrix.money", SemanticDataType.MONEY, AggregateFunction.SUM,
                AggregateEmptySet.zero(SemanticDataType.MONEY), LIFETIME);
        assertThat(metric.currencySemantics()).isEqualTo(CurrencySemantics.CONVERT_TO_REPORTING_CURRENCY);
        assertThat(metric.emptySet().value().dataType()).isEqualTo(SemanticDataType.MONEY);
    }
}