package com.company.campaign.segment.resolution;

import com.company.campaign.contracts.Disposition;
import com.company.campaign.metadata.MetadataKind;
import com.company.campaign.metadata.domain.AggregateFunction;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.RelationJoinKind;
import com.company.campaign.metadata.domain.RelationScope;
import com.company.campaign.segment.intent.IntentClause;
import com.company.campaign.segment.intent.IntentCurrency;
import com.company.campaign.segment.intent.IntentValue;
import com.company.campaign.segment.intent.IntentWindow;
import com.company.campaign.segment.intent.IntentWindowUnit;
import com.company.campaign.segment.intent.testsupport.IntentFixtures;
import com.company.campaign.segment.resolution.testsupport.ResolutionFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Correction 2: RELATION candidates are produced safely. A relation clause considers permitted relations,
 * grounds them exactly as any other kind, accepts only relation-appropriate slots and matches its declared
 * scope exactly; a relation clarification exposes permitted relations with safe labels.
 */
class RelationResolutionTest {

    private final IntentResolver resolver = new IntentResolver(
            ResolutionFixtures.standardSnapshot(), ResolutionFixtures.policy(), ResolutionFixtures.registry());

    @Test
    void aRelationClauseResolvesTheAliasGroundedRelation() {
        ClauseResolution clause = resolver.resolve(IntentFixtures.intent(ResolutionFixtures.relationClause()))
                .clause("c3");

        assertThat(clause.accepted()).isEqualTo("member.orders");
        assertThat(clause.acceptedKind()).isEqualTo(MetadataKind.RELATION);
        assertThat(clause.candidates().get(0).evidence())
                .contains(EvidenceCode.EXACT_ALIAS_MATCH, EvidenceCode.KIND_MATCH, EvidenceCode.SUBJECT_MATCH);
    }

    @Test
    void stableIdHintAndConceptPolicyMappingBothGroundARelation() {
        ClauseResolution hinted = resolver.resolve(IntentFixtures.intent(IntentFixtures.withHints(
                ResolutionFixtures.relationClauseWithUnrelatedSpan(), List.of("member.orders")))).clause("c3");
        assertThat(hinted.accepted()).isEqualTo("member.orders");
        assertThat(hinted.candidates().get(0).evidence()).contains(EvidenceCode.EXACT_STABLE_ID_HINT);

        IntentResolver mapped = new IntentResolver(
                ResolutionFixtures.standardSnapshot(),
                ResolutionFixtures.policyWithConceptMapping(Map.of(
                        ResolutionFixtures.MEMBER_ORDERS_CONCEPT, ResolutionFixtures.MEMBER_ORDERS_DEFINITION_ID)),
                ResolutionFixtures.registry());
        ClauseResolution conceptMapped = mapped.resolve(IntentFixtures.intent(
                ResolutionFixtures.relationClauseWithUnrelatedSpan())).clause("c3");
        assertThat(conceptMapped.accepted()).isEqualTo("member.orders");
        assertThat(conceptMapped.candidates().get(0).evidence()).contains(EvidenceCode.CONCEPT_POLICY_MATCH);
    }

    @Test
    void twoCompatibleRelationsReturnNeedsInputWithRelationOptions() {
        ResolutionResult result = new IntentResolver(
                ResolutionFixtures.twoRelationSnapshot(), ResolutionFixtures.policyWithoutConceptMapping(),
                ResolutionFixtures.registry())
                .resolve(IntentFixtures.intent(ResolutionFixtures.relationClauseWithUnrelatedSpan()));

        assertThat(result.disposition()).isEqualTo(Disposition.NEEDS_INPUT);
        ClauseResolution clause = result.clause("c3");
        assertThat(clause.accepted()).isNull();
        assertThat(clause.candidates()).hasSize(2);
        assertThat(clause.questionCodes()).contains(QuestionCode.DISAMBIGUATE_DEFINITION);

        assertThat(result.clarification().questionCode()).isEqualTo(QuestionCode.DISAMBIGUATE_DEFINITION);
        assertThat(result.clarification().options()).hasSize(2);
        assertThat(result.clarification().options().stream().map(ClarificationOption::definitionId))
                .containsExactlyInAnyOrder("member.orders", "member.profile");
        assertThat(result.clarification().options())
                .allSatisfy(option -> assertThat(option.label()).isNotBlank());
        assertThat(result.clarification().options().stream().map(ClarificationOption::label))
                .containsExactlyInAnyOrder("Member Orders", "Member Profile");
    }

    @Test
    void incompatibleRelationSlotsAreRejectedWithStableReasons() {
        assertRelationRejected(
                IntentFixtures.withOperator(ResolutionFixtures.relationClause(), ComparisonOperator.EQUAL),
                RejectionReason.OPERATOR_NOT_ALLOWED);
        assertRelationRejected(
                IntentFixtures.withValue(ResolutionFixtures.relationClause(), new IntentValue.StringValue("x")),
                RejectionReason.VALUE_SHAPE_INCOMPATIBLE);
        assertRelationRejected(
                IntentFixtures.withWindow(ResolutionFixtures.relationClause(),
                        new IntentWindow.Rolling(30, IntentWindowUnit.DAY)),
                RejectionReason.WINDOW_UNSUPPORTED);
        assertRelationRejected(
                IntentFixtures.withCurrency(ResolutionFixtures.relationClause(),
                        new IntentCurrency.Explicit("CNY")),
                RejectionReason.CURRENCY_INCOMPATIBLE);
        assertRelationRejected(
                IntentFixtures.withAggregation(ResolutionFixtures.relationClause(), AggregateFunction.SUM),
                RejectionReason.AGGREGATION_MISMATCH);
    }

    @Test
    void aRelationScopeThatDiffersFromTheDeclaredScopeIsRejected() {
        assertRelationRejected(
                IntentFixtures.withRelationScope(ResolutionFixtures.relationClause(),
                        new RelationScope(RelationJoinKind.GLOBAL, null, null)),
                RejectionReason.RELATION_MISMATCH);

        ClauseResolution matching = resolver.resolve(IntentFixtures.intent(
                IntentFixtures.withRelationScope(
                        ResolutionFixtures.relationClause(), ResolutionFixtures.ordersRelationScope())))
                .clause("c3");
        assertThat(matching.accepted()).isEqualTo("member.orders");
        assertThat(matching.candidates().get(0).evidence()).contains(EvidenceCode.RELATION_COMPATIBLE);
    }

    @Test
    void missingOrDeniedRelationsAreNonDisclosing() {
        IntentClause clause = IntentFixtures.withHints(
                ResolutionFixtures.relationClause(), List.of("secret.denied.relation"));
        ResolutionResult result = new IntentResolver(
                ResolutionFixtures.noRelationSnapshot(), ResolutionFixtures.policyWithoutConceptMapping(),
                ResolutionFixtures.registry())
                .resolve(IntentFixtures.intent(clause));

        ClauseResolution resolved = result.clause("c3");
        assertThat(resolved.accepted()).isNull();
        assertThat(resolved.candidates()).isEmpty();
        assertThat(resolved.rejected()).as("absence and denial are indistinguishable").isEmpty();
        assertThat(resolved.disposition()).isEqualTo(Disposition.NEEDS_INPUT);
        assertThat(resolved.questionCodes()).contains(QuestionCode.SELECT_DEFINITION);
        assertThat(result.clarification().options()).isEmpty();
        assertThat(result.toString()).doesNotContain("secret.denied.relation");
        assertThat(result.clarification().toString()).doesNotContain("secret.denied.relation");
    }

    private void assertRelationRejected(IntentClause clause, RejectionReason expected) {
        ClauseResolution resolved = resolver.resolve(IntentFixtures.intent(clause)).clause(clause.clauseId());
        assertThat(resolved.accepted()).isNull();
        assertThat(resolved.candidates()).isEmpty();
        assertThat(resolved.rejected())
                .filteredOn(rejected -> rejected.definitionId().equals("member.orders"))
                .singleElement()
                .satisfies(rejected -> assertThat(rejected.reason()).isEqualTo(expected));
    }
}