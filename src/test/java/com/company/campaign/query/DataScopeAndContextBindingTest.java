package com.company.campaign.query;

import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.metadata.testsupport.MetadataFixtures;
import com.company.campaign.query.plan.DataScopePredicate;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.query.testsupport.QueryPlanFixtures;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import com.company.campaign.security.context.TrustedExecutionContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirements 12 and 13: the DataScope requirement is always present, is never sourced from DSL input and
 * is hash-bound to the trusted scope, and no tenant/program/membership binding can reuse another's plan hash.
 */
class DataScopeAndContextBindingTest {

    @Test
    void theMandatoryDataScopeRequirementIsAlwaysPresentAndTrustedContextDerived() {
        SemanticQueryPlan plan = QueryPlanFixtures.plan(SegmentValidationFixtures.validDocument());

        DataScopePredicate predicate = plan.dataScopePredicate();
        assertThat(predicate).isNotNull();
        assertThat(predicate.kind()).isEqualTo(DataScopePredicate.Requirement.SUBJECT_IN_DATASCOPE);
        assertThat(predicate.subject()).isEqualTo(SubjectKind.MEMBER);
        assertThat(predicate.dataScopeId()).isEqualTo("scope-008");
        assertThat(predicate.dataScopeId()).isEqualTo(plan.trustedContext().dataScopeId());
    }

    @Test
    void theScopeIdentifierNeverAppearsInTheUserPredicateTreeButChangesThePlanHash() {
        SemanticQueryPlan scopeA = QueryPlanFixtures.plan(SegmentValidationFixtures.validDocument());
        TrustedExecutionContext other = QueryPlanFixtures.context("tenant-001", "program-001", "scope-009");
        SemanticQueryPlan scopeB = QueryPlanFixtures.plan(
                SegmentValidationFixtures.validDocument(), SegmentValidationFixtures.snapshot(), other);

        assertThat(scopeA.dataScopePredicate().dataScopeId()).isEqualTo("scope-008");
        assertThat(scopeB.dataScopePredicate().dataScopeId()).isEqualTo("scope-009");
        assertThat(scopeA.planHash()).isNotEqualTo(scopeB.planHash());

        // The user predicate tree is identical; only the trusted binding differs.
        assertThat(scopeA.predicateRoot()).isEqualTo(scopeB.predicateRoot());
        assertThat(new com.company.campaign.query.ast.QueryAstCodec().writeString(scopeA.predicateRoot()))
                .doesNotContain("scope-008")
                .doesNotContain("scope-009");
    }

    @Test
    void aPlanAuthorizedForOneTenantProgramOrMembershipCannotBeReplayedUnderAnother() {
        SemanticQueryPlan base = QueryPlanFixtures.plan(SegmentValidationFixtures.validDocument());

        var foreignSnapshot = QueryPlanFixtures.foreignOwnerSnapshot("tenant-999", "program-001");
        var foreignDocument = QueryPlanFixtures.document(
                foreignSnapshot, SegmentValidationFixtures.tierEquals("n-tier", "GOLD"));
        SemanticQueryPlan foreign = QueryPlanFixtures.plan(
                foreignDocument, foreignSnapshot, QueryPlanFixtures.context("tenant-999", "program-001", "scope-008"));

        SemanticQueryPlan otherMembership = QueryPlanFixtures.plan(
                SegmentValidationFixtures.validDocument(), SegmentValidationFixtures.snapshot(),
                QueryPlanFixtures.context("tenant-001", "program-001", "scope-008", "membership-2", "user-001",
                        QueryPlanFixtures.AS_OF, QueryPlanFixtures.WATERMARK, ZoneId.of("Asia/Shanghai"), "CNY",
                        MetadataFixtures.allPermissions()));

        SemanticQueryPlan otherUser = QueryPlanFixtures.plan(
                SegmentValidationFixtures.validDocument(), SegmentValidationFixtures.snapshot(),
                QueryPlanFixtures.context("tenant-001", "program-001", "scope-008", "membership-1", "user-002",
                        QueryPlanFixtures.AS_OF, QueryPlanFixtures.WATERMARK, ZoneId.of("Asia/Shanghai"), "CNY",
                        MetadataFixtures.allPermissions()));

        assertThat(base.trustedContext().tenantId()).isEqualTo("tenant-001");
        assertThat(foreign.trustedContext().tenantId()).isEqualTo("tenant-999");
        assertThat(base.planHash()).isNotEqualTo(foreign.planHash());
        assertThat(base.planHash()).isNotEqualTo(otherMembership.planHash());
        assertThat(base.planHash()).isNotEqualTo(otherUser.planHash());
    }

    @Test
    void aDifferentWatermarkChangesThePlanHashEvenWithTheSameAsOf() {
        SemanticQueryPlan base = QueryPlanFixtures.plan(SegmentValidationFixtures.validDocument());
        TrustedExecutionContext earlierWatermark = QueryPlanFixtures.context(
                "tenant-001", "program-001", "scope-008", "membership-1", "user-001",
                QueryPlanFixtures.AS_OF, Instant.parse("2026-10-01T01:00:00Z"),
                ZoneId.of("Asia/Shanghai"), "CNY", MetadataFixtures.allPermissions());

        SemanticQueryPlan changed = QueryPlanFixtures.plan(
                SegmentValidationFixtures.validDocument(), SegmentValidationFixtures.snapshot(), earlierWatermark);

        assertThat(base.trustedContext().dataWatermark()).isEqualTo(QueryPlanFixtures.WATERMARK);
        assertThat(changed.trustedContext().dataWatermark()).isEqualTo(Instant.parse("2026-10-01T01:00:00Z"));
        assertThat(base.planHash()).isNotEqualTo(changed.planHash());
    }
}