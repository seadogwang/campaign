package com.company.campaign.evaluation.segment.runner;

import com.company.campaign.builder.refine.SegmentRefinementPatch;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.evaluation.segment.contract.FixtureRole;
import com.company.campaign.evaluation.segment.contract.RefinementOperation;
import com.company.campaign.evaluation.segment.contract.RiskLevel;
import com.company.campaign.evaluation.segment.contract.SegmentCategory;
import com.company.campaign.evaluation.segment.runner.testsupport.T13Scenarios;
import com.company.campaign.evaluation.segment.runner.testsupport.T13TestAssets;
import com.company.campaign.evaluation.segment.runner.testsupport.T13TestCorpus;
import com.company.campaign.evaluation.segment.runner.testsupport.T13TestHost;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sections 2 and 5: a refinement Case is executed through the genuine T12 patch/adapter path, and the observed
 * operation, stable target and enclosing scope are read from the accepted base and refined documents rather
 * than restated from the request.
 */
class SegmentEvaluationRunnerRefinementTest {

    private static EvaluationReport.MetricValue metric(EvaluationReport report, MetricId id) {
        return report.metrics().stream()
                .filter(value -> value.metricId().equals(id.metricCode()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void aRefinementCaseComparesTheObservedOperationTargetAndScope(@TempDir Path root) {
        SegmentDsl base = T13Scenarios.refinementBaseDocument();
        SegmentDsl refined = T13Scenarios.refinedDocument(base);
        SegmentRefinementPatch patch = T13Scenarios.addTierPatch(base);

        T13TestCorpus.CaseHolder holder = new T13TestCorpus.CaseHolder(
                "case-refinement-01", SegmentCategory.REFINEMENT_ADD_CHANGE_DELETE_MOVE, RiskLevel.MEDIUM,
                "再加一个不是银卡的会员", T13TestHost.SNAPSHOT_ID, T13TestHost.CONTEXT_FIXTURE);
        holder.fixture(T13TestHost.TIER_FIXTURE)
                .disposition(Disposition.RESOLVED)
                .currentSegment(base)
                .ast(refined)
                .slot("c1", "MEMBER_TIER", "NOT_EQUAL", "不是银卡", TextNode.valueOf("SILVER"))
                .selected("member.tier")
                .memberIds(T13TestHost.TIER_FIXTURE, "m-001", "m-003")
                .claims("CLAIM_ADD_TIER_CONDITION")
                .refinement(RefinementOperation.ADD_NODE, "n-new-tier", "n-root");

        T13TestCorpus.in(root)
                .metadata(T13TestHost.snapshot())
                .context(T13TestHost.CONTEXT_FIXTURE, T13TestHost.context().snapshotRef())
                .fixture(T13TestHost.TIER_FIXTURE, FixtureRole.PRIMARY, T13TestAssets.TIER_UNIVERSE,
                        T13TestAssets.membersPayload())
                .caseSpec(holder)
                .write();

        EvaluationReport report = T13Scenarios.run(
                root, "t13-refinement-01",
                new T13TestHost(T13Scenarios.tierPort()).refinementPatch(patch));

        EvaluationReport.AttemptOutcome attempt = report.cases().get(0).attempts().get(0);
        assertThat(attempt.disposition()).isEqualTo(Disposition.RESOLVED.name());
        // The refinement path never calls a model: the proposal is host-pinned and no provider call happens.
        assertThat(attempt.providerCalls()).isZero();
        assertThat(attempt.observedStages()).contains("REFINEMENT", "VALIDATION", "PLAN", "PREVIEW");
        assertThat(attempt.observedStages()).doesNotContain("INTENT");
        assertThat(attempt.evidence().actualRefinementOperation()).isEqualTo(RefinementOperation.ADD_NODE.name());
        assertThat(attempt.evidence().refinementMatched()).isTrue();
        assertThat(attempt.evidence().refinementTargetMatched()).isTrue();
        assertThat(attempt.evidence().refinementScopeMatched()).isTrue();
        assertThat(attempt.evidence().expectedCanonicalAstHash())
                .isEqualTo(attempt.evidence().actualCanonicalAstHash());
        assertThat(attempt.evidence().canonicalAstMatched()).isTrue();
        assertThat(attempt.evidence().memberSetsMatched()).isTrue();
        assertThat(metric(report, MetricId.REFINEMENT_TARGET_ACCURACY).numerator()).isEqualTo(1);
        assertThat(metric(report, MetricId.REFINEMENT_TARGET_ACCURACY).denominator()).isEqualTo(1);
    }

    @Test
    void aRefinementCaseWithAWrongFrozenTargetIsReportedAsAMismatch(@TempDir Path root) {
        SegmentDsl base = T13Scenarios.refinementBaseDocument();
        SegmentDsl refined = T13Scenarios.refinedDocument(base);

        T13TestCorpus.CaseHolder holder = new T13TestCorpus.CaseHolder(
                "case-refinement-02", SegmentCategory.REFINEMENT_ADD_CHANGE_DELETE_MOVE, RiskLevel.MEDIUM,
                "再加一个不是银卡的会员", T13TestHost.SNAPSHOT_ID, T13TestHost.CONTEXT_FIXTURE);
        holder.fixture(T13TestHost.TIER_FIXTURE)
                .disposition(Disposition.RESOLVED)
                .currentSegment(base)
                .ast(refined)
                .slot("c1", "MEMBER_TIER", "NOT_EQUAL", "不是银卡", TextNode.valueOf("SILVER"))
                .selected("member.tier")
                .memberIds(T13TestHost.TIER_FIXTURE, "m-001", "m-003")
                .claims("CLAIM_ADD_TIER_CONDITION")
                .refinement(RefinementOperation.REMOVE_NODE, "n-tier", "n-root");

        T13TestCorpus.in(root)
                .metadata(T13TestHost.snapshot())
                .context(T13TestHost.CONTEXT_FIXTURE, T13TestHost.context().snapshotRef())
                .fixture(T13TestHost.TIER_FIXTURE, FixtureRole.PRIMARY, T13TestAssets.TIER_UNIVERSE,
                        T13TestAssets.membersPayload())
                .caseSpec(holder)
                .write();

        EvaluationReport report = T13Scenarios.run(
                root, "t13-refinement-02",
                new T13TestHost(T13Scenarios.tierPort())
                        .refinementPatch(T13Scenarios.addTierPatch(base)));

        EvaluationReport.AttemptOutcome attempt = report.cases().get(0).attempts().get(0);
        assertThat(attempt.evidence().actualRefinementOperation()).isEqualTo(RefinementOperation.ADD_NODE.name());
        assertThat(attempt.evidence().expectedRefinementOperation())
                .isEqualTo(RefinementOperation.REMOVE_NODE.name());
        assertThat(attempt.evidence().refinementMatched()).isFalse();
        assertThat(attempt.taxonomy()).isEqualTo(FailureTaxonomy.COMPARISON_MISMATCH);
        assertThat(report.summary().runInvalid()).isFalse();
    }

    @Test
    void aRefinementCaseWithoutAHostPinnedProposalIsRefusedAtRunStart(@TempDir Path root) {
        SegmentDsl base = T13Scenarios.refinementBaseDocument();
        T13TestCorpus.CaseHolder holder = new T13TestCorpus.CaseHolder(
                "case-refinement-03", SegmentCategory.REFINEMENT_ADD_CHANGE_DELETE_MOVE, RiskLevel.MEDIUM,
                "再加一个不是银卡的会员", T13TestHost.SNAPSHOT_ID, T13TestHost.CONTEXT_FIXTURE);
        holder.fixture(T13TestHost.TIER_FIXTURE)
                .disposition(Disposition.RESOLVED)
                .currentSegment(base)
                .ast(T13Scenarios.refinedDocument(base))
                .slot("c1", "MEMBER_TIER", "NOT_EQUAL", "不是银卡", TextNode.valueOf("SILVER"))
                .memberIds(T13TestHost.TIER_FIXTURE, "m-001", "m-003")
                .refinement(RefinementOperation.ADD_NODE, "n-new-tier", "n-root");

        T13TestCorpus.in(root)
                .metadata(T13TestHost.snapshot())
                .context(T13TestHost.CONTEXT_FIXTURE, T13TestHost.context().snapshotRef())
                .fixture(T13TestHost.TIER_FIXTURE, FixtureRole.PRIMARY, T13TestAssets.TIER_UNIVERSE,
                        T13TestAssets.membersPayload())
                .caseSpec(holder)
                .write();

        EvaluationReport report = T13Scenarios.run(
                root, "t13-refinement-03", new T13TestHost(T13Scenarios.tierPort()));

        assertThat(report.summary().runInvalid()).isTrue();
        assertThat(report.cases().get(0).attempts().get(0).taxonomy())
                .isEqualTo(FailureTaxonomy.PROVIDER_OR_INFRASTRUCTURE_FAILURE);
    }
}