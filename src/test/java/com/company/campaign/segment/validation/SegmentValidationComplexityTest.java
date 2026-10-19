package com.company.campaign.segment.validation;

import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Requirement 12: node/depth/ALL/ANY/relation limits and invalid policy construction. */
class SegmentValidationComplexityTest {

    private static SegmentValidationResult validateWithPolicy(Predicate root, SegmentValidationPolicy policy) {
        return SegmentValidationFixtures.validate(
                SegmentValidationFixtures.publish(root), SegmentValidationFixtures.snapshot(),
                SegmentValidationFixtures.context(), SegmentValidationFixtures.allowAll(), policy);
    }

    @Test
    void aNodeBudgetOverflowIsRejected() {
        SegmentValidationPolicy policy = new SegmentValidationPolicy(1, 32, 64, 256, 4);
        SegmentValidationResult result = validateWithPolicy(SegmentValidationFixtures.validDocument().predicate(), policy);

        assertThat(SegmentValidationFixtures.codes(result))
                .contains(SegmentValidationErrorCodes.COMPLEXITY_NODES_EXCEEDED);
        assertThat(result.issues().get(0).details()).containsKeys("limit", "actual");
    }

    @Test
    void aDepthOverflowIsRejected() {
        Predicate root = SegmentValidationFixtures.all("n-root", List.of(
                SegmentValidationFixtures.all("n-inner", List.of(
                        SegmentValidationFixtures.tierEquals("n-tier", "GOLD")))));
        SegmentValidationPolicy policy = new SegmentValidationPolicy(100, 1, 64, 256, 4);

        assertThat(SegmentValidationFixtures.codes(validateWithPolicy(root, policy)))
                .contains(SegmentValidationErrorCodes.COMPLEXITY_DEPTH_EXCEEDED);
    }

    @Test
    void anAnyWidthOverflowIsRejected() {
        Predicate root = SegmentValidationFixtures.any("n-root", List.of(
                SegmentValidationFixtures.tierEquals("n-1", "GOLD"),
                SegmentValidationFixtures.tierEquals("n-2", "SILVER")));
        SegmentValidationPolicy policy = new SegmentValidationPolicy(100, 32, 1, 256, 4);

        assertThat(SegmentValidationFixtures.codes(validateWithPolicy(root, policy)))
                .contains(SegmentValidationErrorCodes.COMPLEXITY_ANY_WIDTH_EXCEEDED);
    }

    @Test
    void anAllWidthOverflowIsRejected() {
        Predicate root = SegmentValidationFixtures.all("n-root", List.of(
                SegmentValidationFixtures.tierEquals("n-1", "GOLD"),
                SegmentValidationFixtures.tierEquals("n-2", "SILVER")));
        SegmentValidationPolicy policy = new SegmentValidationPolicy(100, 32, 64, 1, 4);

        assertThat(SegmentValidationFixtures.codes(validateWithPolicy(root, policy)))
                .contains(SegmentValidationErrorCodes.COMPLEXITY_ALL_WIDTH_EXCEEDED);
    }

    @Test
    void aRelationDepthOverflowIsRejected() {
        Predicate root = SegmentValidationFixtures.exists(
                "n-outer", "member.orders", SegmentValidationFixtures.lifetime(),
                SegmentValidationFixtures.exists("n-inner", "order.lines", SegmentValidationFixtures.lifetime(),
                        SegmentValidationFixtures.attribute("n-channel", "order.channel", ComparisonOperator.EQUAL,
                                SegmentValidationFixtures.value(SegmentValidationFixtures.enumValue("ONLINE")))));
        SegmentValidationPolicy policy = new SegmentValidationPolicy(100, 32, 64, 256, 1);

        assertThat(SegmentValidationFixtures.codes(validateWithPolicy(root, policy)))
                .contains(SegmentValidationErrorCodes.COMPLEXITY_RELATION_DEPTH_EXCEEDED);
    }

    @Test
    void theStandardPolicyAcceptsTheValidDocument() {
        SegmentValidationResult result = validateWithPolicy(
                SegmentValidationFixtures.validDocument().predicate(), SegmentValidationPolicy.standard());
        assertThat(result.valid()).isTrue();
    }

    @Test
    void issuesAreOrderedByPipelineStageNotByDiscoveryOrder() {
        // One complexity overflow (stage 7) and one unknown definition (stage 3) in the same run.
        Predicate root = SegmentValidationFixtures.all("n-root", List.of(
                SegmentValidationFixtures.attribute("n-unknown", "member.unknown", ComparisonOperator.EQUAL,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.stringValue("x"))),
                SegmentValidationFixtures.tierEquals("n-tier", "GOLD")));
        SegmentValidationPolicy policy = new SegmentValidationPolicy(1, 32, 64, 256, 4);

        SegmentValidationResult result = validateWithPolicy(root, policy);

        assertThat(SegmentValidationFixtures.codes(result))
                .containsExactly(
                        SegmentValidationErrorCodes.DEFINITION_UNKNOWN,
                        SegmentValidationErrorCodes.COMPLEXITY_NODES_EXCEEDED);
    }

    @Test
void everyIssueCarriesItsPipelineStageAsASafeEnum() {
        Predicate root = SegmentValidationFixtures.all("n-root", List.of(
                SegmentValidationFixtures.attribute("n-unknown", "member.unknown", ComparisonOperator.EQUAL,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.stringValue("x"))),
                SegmentValidationFixtures.tierEquals("n-tier", "GOLD")));
        SegmentValidationPolicy policy = new SegmentValidationPolicy(1, 32, 64, 256, 4);

        SegmentValidationResult result = validateWithPolicy(root, policy);

        assertThat(result.issues()).allSatisfy(issue ->
                assertThat(issue.details()).containsKey("stage"));
        assertThat(result.issues().get(0).details()).containsEntry("stage", "DEFINITION_TYPE_OPERATOR");
        assertThat(result.issues().get(1).details()).containsEntry("stage", "COMPLEXITY");
    }

    // ------------------------------------------------------------------ policy construction

    @Test
    void aNonPositivePolicyValueIsRejectedAtConstruction() {
        assertThatThrownBy(() -> new SegmentValidationPolicy(0, 32, 64, 256, 4))
                .isInstanceOf(SegmentValidationException.class)
                .hasFieldOrPropertyWithValue("code", SegmentValidationErrorCodes.POLICY_INVALID);
        assertThatThrownBy(() -> new SegmentValidationPolicy(100, -1, 64, 256, 4))
                .isInstanceOf(SegmentValidationException.class);
    }

    @Test
    void aPolicyValueBeyondTheAbsoluteBoundIsRejectedAtConstruction() {
        assertThatThrownBy(() -> new SegmentValidationPolicy(
                SegmentValidationLimits.MAX_PREDICATE_NODES + 1, 32, 64, 256, 4))
                .isInstanceOf(SegmentValidationException.class)
                .hasFieldOrPropertyWithValue("code", SegmentValidationErrorCodes.POLICY_INVALID);
        assertThatThrownBy(() -> new SegmentValidationPolicy(
                100, SegmentValidationLimits.MAX_PREDICATE_DEPTH + 1, 64, 256, 4))
                .isInstanceOf(SegmentValidationException.class);
        assertThatThrownBy(() -> new SegmentValidationPolicy(
                100, 32, SegmentValidationLimits.MAX_ANY_WIDTH + 1, 256, 4))
                .isInstanceOf(SegmentValidationException.class);
        assertThatThrownBy(() -> new SegmentValidationPolicy(
                100, 32, 64, SegmentValidationLimits.MAX_ALL_WIDTH + 1, 4))
                .isInstanceOf(SegmentValidationException.class);
        assertThatThrownBy(() -> new SegmentValidationPolicy(
                100, 32, 64, 256, SegmentValidationLimits.MAX_RELATION_NESTING_DEPTH + 1))
                .isInstanceOf(SegmentValidationException.class);
    }

    @Test
    void theStandardPolicyIsWithinEveryAbsoluteBound() {
        SegmentValidationPolicy policy = SegmentValidationPolicy.standard();
        assertThat(policy.maxPredicateNodes()).isBetween(1, SegmentValidationLimits.MAX_PREDICATE_NODES);
        assertThat(policy.maxPredicateDepth()).isBetween(1, SegmentValidationLimits.MAX_PREDICATE_DEPTH);
        assertThat(policy.maxAnyWidth()).isBetween(1, SegmentValidationLimits.MAX_ANY_WIDTH);
        assertThat(policy.maxAllWidth()).isBetween(1, SegmentValidationLimits.MAX_ALL_WIDTH);
        assertThat(policy.maxRelationNestingDepth())
                .isBetween(1, SegmentValidationLimits.MAX_RELATION_NESTING_DEPTH);
    }
}