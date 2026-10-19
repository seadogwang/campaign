package com.company.campaign.metadata.domain;

import com.company.campaign.metadata.MetadataErrorCodes;
import com.company.campaign.metadata.testsupport.MetadataFixtures;
import com.company.campaign.metadata.testsupport.RecordOverride;
import com.company.campaign.testsupport.ContractAssertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Finding 1: the Segment spec section 8 operator vocabulary is complete, strictly type-compatible and has a
 * deterministic, documented canonical order.
 */
class MetadataOperatorVocabularyTest {

    private static final List<ComparisonOperator> STRING_OPERATORS = List.of(
            ComparisonOperator.CONTAINS,
            ComparisonOperator.NOT_CONTAINS,
            ComparisonOperator.STARTS_WITH,
            ComparisonOperator.ENDS_WITH);

    @Test
    void betweenSupportsExactlyTheOrderedTypes() {
        assertThat(ComparisonOperator.BETWEEN.supports(SemanticDataType.INTEGER)).isTrue();
        assertThat(ComparisonOperator.BETWEEN.supports(SemanticDataType.DECIMAL)).isTrue();
        assertThat(ComparisonOperator.BETWEEN.supports(SemanticDataType.MONEY)).isTrue();
        assertThat(ComparisonOperator.BETWEEN.supports(SemanticDataType.DATE)).isTrue();
        assertThat(ComparisonOperator.BETWEEN.supports(SemanticDataType.TIMESTAMP)).isTrue();

        assertThat(ComparisonOperator.BETWEEN.supports(SemanticDataType.STRING)).isFalse();
        assertThat(ComparisonOperator.BETWEEN.supports(SemanticDataType.BOOLEAN)).isFalse();
        assertThat(ComparisonOperator.BETWEEN.supports(SemanticDataType.ENUM)).isFalse();
        assertThat(ComparisonOperator.BETWEEN.supports(null)).isFalse();
    }

    @Test
    void stringOperatorsSupportExactlyTheStringType() {
        for (ComparisonOperator operator : STRING_OPERATORS) {
            assertThat(operator.supports(SemanticDataType.STRING))
                    .as("%s supports STRING", operator).isTrue();
            for (SemanticDataType other : List.of(SemanticDataType.BOOLEAN, SemanticDataType.INTEGER,
                    SemanticDataType.DECIMAL, SemanticDataType.DATE, SemanticDataType.TIMESTAMP,
                    SemanticDataType.ENUM, SemanticDataType.MONEY)) {
                assertThat(operator.supports(other))
                        .as("%s does not support %s", operator, other).isFalse();
            }
            assertThat(operator.supports(null)).as("%s does not support null", operator).isFalse();
        }
    }

    @Test
    void equalityMembershipAndNullOperatorsKeepTheirExistingBehavior() {
        // Existing semantics must not change: membership/equality work everywhere, ordering only on ordered
        // types, and the NULL operators never carry a value and always apply.
        for (SemanticDataType type : SemanticDataType.values()) {
            assertThat(ComparisonOperator.EQUAL.supports(type)).isTrue();
            assertThat(ComparisonOperator.NOT_EQUAL.supports(type)).isTrue();
            assertThat(ComparisonOperator.IN.supports(type)).isTrue();
            assertThat(ComparisonOperator.NOT_IN.supports(type)).isTrue();
            assertThat(ComparisonOperator.IS_NULL.supports(type)).isTrue();
            assertThat(ComparisonOperator.IS_NOT_NULL.supports(type)).isTrue();
        }
        assertThat(ComparisonOperator.GREATER_THAN.supports(SemanticDataType.STRING)).isFalse();
        assertThat(ComparisonOperator.LESS_OR_EQUAL.supports(SemanticDataType.ENUM)).isFalse();
        assertThat(ComparisonOperator.GREATER_OR_EQUAL.supports(SemanticDataType.DATE)).isTrue();
        assertThat(ComparisonOperator.IS_NULL.isNullOperator()).isTrue();
        assertThat(ComparisonOperator.IS_NOT_NULL.isNullOperator()).isTrue();
        assertThat(ComparisonOperator.BETWEEN.isNullOperator()).isFalse();
        assertThat(ComparisonOperator.CONTAINS.isNullOperator()).isFalse();
    }

    @Test
    void canonicalOperatorOrderIsDeclarationOrderAndStable() {
        // The canonical set order is the enum declaration order; the operators added for section 8 are
        // appended after the previously published block, so pre-existing relative order is unchanged.
        assertThat(ComparisonOperator.values()).containsExactly(
                ComparisonOperator.EQUAL,
                ComparisonOperator.NOT_EQUAL,
                ComparisonOperator.IN,
                ComparisonOperator.NOT_IN,
                ComparisonOperator.GREATER_THAN,
                ComparisonOperator.GREATER_OR_EQUAL,
                ComparisonOperator.LESS_THAN,
                ComparisonOperator.LESS_OR_EQUAL,
                ComparisonOperator.IS_NULL,
                ComparisonOperator.IS_NOT_NULL,
                ComparisonOperator.BETWEEN,
                ComparisonOperator.CONTAINS,
                ComparisonOperator.NOT_CONTAINS,
                ComparisonOperator.STARTS_WITH,
                ComparisonOperator.ENDS_WITH);

        assertThat(MetadataVocabulary.canonicalOperators(
                List.of(ComparisonOperator.IS_NOT_NULL, ComparisonOperator.CONTAINS,
                        ComparisonOperator.EQUAL, ComparisonOperator.IS_NULL),
                SemanticDataType.STRING))
                .containsExactly(ComparisonOperator.EQUAL, ComparisonOperator.IS_NULL,
                        ComparisonOperator.IS_NOT_NULL, ComparisonOperator.CONTAINS);

        // Pre-existing operators keep their relative canonical order.
        assertThat(MetadataVocabulary.canonicalOperators(
                List.of(ComparisonOperator.GREATER_THAN, ComparisonOperator.IN, ComparisonOperator.EQUAL),
                SemanticDataType.INTEGER))
                .containsExactly(ComparisonOperator.EQUAL, ComparisonOperator.IN,
                        ComparisonOperator.GREATER_THAN);
    }

    // --- positive definition coverage for every added operator --------------------------------------

    @Test
    void everyStringOperatorIsAcceptedOnAStringAttribute() {
        for (ComparisonOperator operator : STRING_OPERATORS) {
            MetadataFixtures.snapshot(
                    List.of(RecordOverride.with(
                            MetadataFixtures.snapshotAttribute("member.city", "Member City"),
                            "allowedOperators", List.of(operator))),
                    List.of(), List.of());
        }
    }

    @Test
    void everyStringOperatorIsAcceptedOnAStringMetric() {
        for (ComparisonOperator operator : STRING_OPERATORS) {
            MetadataFixtures.snapshot(
                    List.of(),
                    List.of(RecordOverride.with(
                            MetadataFixtures.textMinMetric(),
                            "allowedOperators", List.of(operator))),
                    List.of(MetadataFixtures.ordersRelation()));
        }
    }

    @Test
    void betweenIsAcceptedOnAnOrderedAttribute() {
        for (SemanticDataType type : List.of(SemanticDataType.INTEGER, SemanticDataType.DECIMAL,
                SemanticDataType.MONEY, SemanticDataType.DATE, SemanticDataType.TIMESTAMP)) {
            MetadataFixtures.snapshot(
                    List.of(RecordOverride.with(
                            MetadataFixtures.orderedAttribute("member.score", "Member Score", type),
                            "allowedOperators", List.of(ComparisonOperator.BETWEEN))),
                    List.of(), List.of());
        }
    }

    // --- negative coverage for every added operator --------------------------------------------------

    @Test
    void stringOperatorsAreRejectedOnANonStringAttribute() {
        for (ComparisonOperator operator : STRING_OPERATORS) {
            ContractAssertions.assertFailsWithCode(
                    () -> MetadataFixtures.snapshot(
                            List.of(RecordOverride.with(
                                    MetadataFixtures.orderedAttribute(
                                            "member.score", "Member Score", SemanticDataType.INTEGER),
                                    "allowedOperators", List.of(operator))),
                            List.of(), List.of()),
                    MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID);
        }
    }

    @Test
    void stringOperatorsAreRejectedOnANonStringMetricResult() {
        for (ComparisonOperator operator : STRING_OPERATORS) {
            ContractAssertions.assertFailsWithCode(
                    () -> MetadataFixtures.snapshot(
                            List.of(),
                            List.of(RecordOverride.with(
                                    MetadataFixtures.netPaidAmountMetric(),
                                    "allowedOperators", List.of(operator))),
                            List.of(MetadataFixtures.ordersRelation())),
                    MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID);
        }
    }

    @Test
    void betweenIsRejectedOnUnorderedAttributesAndMetrics() {
        ContractAssertions.assertFailsWithCode(
                () -> MetadataFixtures.snapshot(
                        List.of(RecordOverride.with(
                                MetadataFixtures.snapshotAttribute("member.city", "Member City"),
                                "allowedOperators", List.of(ComparisonOperator.BETWEEN))),
                        List.of(), List.of()),
                MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID);

        Map<String, Object> booleanOverrides = new java.util.LinkedHashMap<>();
        booleanOverrides.put("dataType", SemanticDataType.BOOLEAN);
        booleanOverrides.put("stringSemantics", null);
        booleanOverrides.put("allowedOperators", List.of(ComparisonOperator.BETWEEN));
        ContractAssertions.assertFailsWithCode(
                () -> MetadataFixtures.snapshot(
                        List.of(RecordOverride.withAll(
                                MetadataFixtures.snapshotAttribute("member.flag", "Member Flag"),
                                booleanOverrides)),
                        List.of(), List.of()),
                MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID);

        ContractAssertions.assertFailsWithCode(
                () -> MetadataFixtures.snapshot(
                        List.of(RecordOverride.with(
                                MetadataFixtures.tierAttribute(),
                                "allowedOperators", List.of(ComparisonOperator.BETWEEN))),
                        List.of(), List.of()),
                MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID);
    }
}