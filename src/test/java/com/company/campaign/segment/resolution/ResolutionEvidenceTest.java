package com.company.campaign.segment.resolution;

import com.company.campaign.contracts.SourceSpan;
import com.company.campaign.metadata.MetadataKind;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.RelationJoinKind;
import com.company.campaign.metadata.domain.RelationScope;
import com.company.campaign.segment.intent.IntentClause;
import com.company.campaign.segment.intent.IntentProvenance;
import com.company.campaign.segment.intent.IntentValue;
import com.company.campaign.segment.intent.testsupport.IntentFixtures;
import com.company.campaign.segment.resolution.testsupport.ResolutionFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 3 evidence taxonomy: the resolver distinguishes the stable-id hint, display and alias match,
 * positive-example match, negative-example conflict and semantic-policy conflict, alongside the per-slot
 * compatibility evidence.
 */
class ResolutionEvidenceTest {

    private final IntentResolver resolver = new IntentResolver(
            ResolutionFixtures.standardSnapshot(), ResolutionFixtures.policy(), ResolutionFixtures.registry());

    @Test
    void stableIdHintDisplayAliasAndPositiveExampleMatchesProduceTheirOwnEvidence() {
        assertTierEvidence("Member Tier", EvidenceCode.EXACT_DISPLAY_MATCH, List.of());
        assertTierEvidence("卡级", EvidenceCode.EXACT_ALIAS_MATCH, List.of());
        assertTierEvidence("Gold会员", EvidenceCode.POSITIVE_EXAMPLE_MATCH, List.of());
        assertTierEvidence("Gold会员", EvidenceCode.EXACT_STABLE_ID_HINT, List.of("member.tier"));
    }

    @Test
    void negativeExampleAndSemanticPolicyConflictsAreDistinct() {
        RejectedCandidate plain = rejectedFor("高价值会员", List.of());
        assertThat(plain.reason()).isEqualTo(RejectionReason.NEGATIVE_EXAMPLE_CONFLICT);
        assertThat(plain.evidence()).contains(EvidenceCode.NEGATIVE_EXAMPLE_CONFLICT);

        RejectedCandidate hinted = rejectedFor("高价值会员", List.of("member.tier"));
        assertThat(hinted.reason()).isEqualTo(RejectionReason.SEMANTIC_POLICY_CONFLICT);
        assertThat(hinted.evidence()).contains(EvidenceCode.SEMANTIC_POLICY_CONFLICT);
    }

    @Test
    void everyPopulatedSlotContributesCompatibilityEvidence() {
        IntentClause spend = IntentFixtures.withRelationScope(
                ResolutionFixtures.spendClause(),
                new RelationScope(RelationJoinKind.CORRELATED_BY_SOURCE_KEY, "member.id", "order.member_id"));
        ClauseResolution clause = resolver.resolve(IntentFixtures.intent(spend)).clause("c2");

        assertThat(clause.accepted()).isEqualTo("order.net_paid_amount");
        List<EvidenceCode> evidence = clause.candidates().get(0).evidence();
        assertThat(evidence).contains(
                EvidenceCode.KIND_MATCH,
                EvidenceCode.SUBJECT_MATCH,
                EvidenceCode.OPERATOR_COMPATIBLE,
                EvidenceCode.VALUE_TYPE_COMPATIBLE,
                EvidenceCode.VALUE_SHAPE_COMPATIBLE,
                EvidenceCode.WINDOW_COMPATIBLE,
                EvidenceCode.CURRENCY_COMPATIBLE,
                EvidenceCode.AGGREGATION_COMPATIBLE,
                EvidenceCode.RELATION_COMPATIBLE);
    }

    private void assertTierEvidence(String spanText, EvidenceCode expected, List<String> hints) {
        ClauseResolution clause = resolver.resolve(IntentFixtures.intent(tierClause(spanText, hints))).clause("c1");
        assertThat(clause.accepted()).isEqualTo("member.tier");
        assertThat(clause.candidates().get(0).evidence()).contains(expected);
    }

    private RejectedCandidate rejectedFor(String spanText, List<String> hints) {
        ClauseResolution clause = resolver.resolve(IntentFixtures.intent(tierClause(spanText, hints))).clause("c1");
        assertThat(clause.accepted()).isNull();
        return clause.rejected().stream()
                .filter(rejected -> rejected.definitionId().equals("member.tier"))
                .findFirst().orElseThrow();
    }

    private static IntentClause tierClause(String spanText, List<String> hints) {
        return new IntentClause(
                "c1", "MEMBER_TIER", MetadataKind.ATTRIBUTE, false, ComparisonOperator.EQUAL,
                new IntentValue.EnumValue("Gold"), null, null, null, null,
                new IntentProvenance.FromSourceSpan(new SourceSpan(spanText, 0, spanText.length())),
                hints, List.of());
    }
}