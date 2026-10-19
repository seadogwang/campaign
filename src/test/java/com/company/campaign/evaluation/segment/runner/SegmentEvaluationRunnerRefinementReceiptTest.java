package com.company.campaign.evaluation.segment.runner;

import com.company.campaign.builder.refine.RefinementOpKind;
import com.company.campaign.builder.refine.SegmentRefinementEditor;
import com.company.campaign.builder.refine.SegmentRefinementOperation;
import com.company.campaign.builder.refine.SegmentRefinementPatch;
import com.company.campaign.builder.refine.SegmentRefinementReceipt;
import com.company.campaign.contracts.ContractVersions;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.evaluation.segment.contract.FixtureRole;
import com.company.campaign.evaluation.segment.contract.RefinementOperation;
import com.company.campaign.evaluation.segment.contract.RiskLevel;
import com.company.campaign.evaluation.segment.contract.SegmentCategory;
import com.company.campaign.evaluation.segment.runner.testsupport.T13Scenarios;
import com.company.campaign.evaluation.segment.runner.testsupport.T13TestAssets;
import com.company.campaign.evaluation.segment.runner.testsupport.T13TestCorpus;
import com.company.campaign.evaluation.segment.runner.testsupport.T13TestHost;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.segment.dsl.contract.MetadataBinding;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.dsl.contract.SegmentDslSchemaVersions;
import com.company.campaign.segment.dsl.predicate.AttributePredicate;
import com.company.campaign.segment.dsl.predicate.ConditionValue;
import com.company.campaign.segment.dsl.predicate.GroupPredicate;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.dsl.predicate.PredicateType;
import com.company.campaign.segment.dsl.value.EnumValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 4 of T13-R1: refinement evidence comes from the typed receipt the genuine T12 editor emitted
 * after it atomically accepted the patch, never from an inferred tree diff, so a same-parent move, an ordered
 * multi-operation patch and every other operation are observed exactly and without an invented precedence.
 */
class SegmentEvaluationRunnerRefinementReceiptTest {

    private static final String ROOT = "n-root";

    private static SegmentDsl document(Predicate predicate) {
        return SegmentDsl.publish(
                SegmentDslSchemaVersions.SUPPORTED, SubjectKind.MEMBER,
                MetadataBinding.fromSnapshot(T13TestHost.snapshot()), predicate);
    }

    private static AttributePredicate tier(String nodeId, ComparisonOperator operator, String code) {
        return new AttributePredicate(nodeId, "member.tier", operator, ConditionValue.of(new EnumValue(code)));
    }

    private static Predicate any(String nodeId, Predicate... children) {
        return new GroupPredicate(nodeId, PredicateType.ANY, List.of(children));
    }

    private static Predicate all(Predicate... children) {
        return new GroupPredicate(ROOT, PredicateType.ALL, List.of(children));
    }

    private static SegmentRefinementPatch patch(SegmentDsl base, SegmentRefinementOperation... operations) {
        return SegmentRefinementPatch.publish(
                ContractVersions.CURRENT, base.editableDslHash(), List.of(operations));
    }

    private static SegmentDsl refined(SegmentDsl base, SegmentRefinementPatch patch) {
        return new SegmentRefinementEditor().apply(base, patch);
    }

    private static EvaluationReport run(
            Path root,
            String caseId,
            SegmentDsl base,
            SegmentRefinementPatch patch,
            String[] expectedMembers,
            RefinementOperation expectedOperation,
            String expectedTarget,
            String expectedScope) {
        SegmentDsl refined = refined(base, patch);
        T13TestCorpus.CaseHolder holder = new T13TestCorpus.CaseHolder(
                caseId, SegmentCategory.REFINEMENT_ADD_CHANGE_DELETE_MOVE, RiskLevel.MEDIUM,
                "refinement utterance", T13TestHost.SNAPSHOT_ID, T13TestHost.CONTEXT_FIXTURE);
        holder.fixture(T13TestHost.TIER_FIXTURE)
                .disposition(Disposition.RESOLVED)
                .currentSegment(base)
                .ast(refined)
                .memberIds(T13TestHost.TIER_FIXTURE, expectedMembers)
                .refinement(expectedOperation, expectedTarget, expectedScope);
        T13TestCorpus.in(root)
                .metadata(T13TestHost.snapshot())
                .context(T13TestHost.CONTEXT_FIXTURE, T13TestHost.context().snapshotRef())
                .fixture(T13TestHost.TIER_FIXTURE, FixtureRole.PRIMARY, T13TestAssets.TIER_UNIVERSE,
                        T13TestAssets.membersPayload())
                .caseSpec(holder)
                .write();
        return T13Scenarios.run(
                root, "report-" + caseId,
                new T13TestHost(T13Scenarios.tierPort()).refinementPatch(patch));
    }

    private static EvaluationReport.AttemptOutcome attempt(EvaluationReport report) {
        return report.cases().get(0).attempts().get(0);
    }

    @Test
    void aSameParentMoveIsObservedFromTheAcceptedReceipt(@TempDir Path root) {
        SegmentDsl base = document(all(
                tier("n-a", ComparisonOperator.EQUAL, "GOLD"),
                tier("n-b", ComparisonOperator.NOT_EQUAL, "SILVER")));
        SegmentRefinementPatch patch = patch(base, new SegmentRefinementOperation.MoveNode(
                "op-1", "n-b", ROOT, "n-a"));

        EvaluationReport report = run(root, "case-receipt-move-same-parent", base, patch,
                new String[] {"m-001", "m-003"}, RefinementOperation.MOVE_NODE, "n-b", ROOT);

        EvaluationReport.AttemptOutcome outcome = attempt(report);
        assertThat(outcome.observedStages()).contains("REFINEMENT", "REFINEMENT_RECEIPT");
        assertThat(outcome.evidence().actualRefinementOperation()).isEqualTo("MOVE_NODE");
        assertThat(outcome.evidence().refinementTargetMatched()).isTrue();
        assertThat(outcome.evidence().refinementScopeMatched()).isTrue();
        assertThat(outcome.evidence().refinementMatched()).isTrue();
        assertThat(outcome.evidence().mismatched()).isFalse();
        assertThat(outcome.taxonomy()).isNull();
    }

    @Test
    void aCrossParentMoveIsObservedFromTheAcceptedReceipt(@TempDir Path root) {
        SegmentDsl base = document(all(
                any("n-g",
                        tier("n-a", ComparisonOperator.EQUAL, "GOLD"),
                        tier("n-b", ComparisonOperator.EQUAL, "SILVER")),
                tier("n-c", ComparisonOperator.NOT_EQUAL, "GOLD")));
        SegmentRefinementPatch patch = patch(base, new SegmentRefinementOperation.MoveNode(
                "op-1", "n-b", ROOT, "n-c"));

        EvaluationReport report = run(root, "case-receipt-move-cross-parent", base, patch,
                new String[] {}, RefinementOperation.MOVE_NODE, "n-b", ROOT);

        EvaluationReport.AttemptOutcome outcome = attempt(report);
        assertThat(outcome.evidence().actualRefinementOperation()).isEqualTo("MOVE_NODE");
        assertThat(outcome.evidence().refinementMatched()).isTrue();
        assertThat(outcome.evidence().mismatched()).isFalse();
        assertThat(outcome.taxonomy()).isNull();
    }

    @Test
    void anAddedNodeIsObservedFromTheAcceptedReceipt(@TempDir Path root) {
        SegmentDsl base = document(all(
                tier("n-a", ComparisonOperator.EQUAL, "GOLD"),
                tier("n-b", ComparisonOperator.NOT_EQUAL, "SILVER")));
        SegmentRefinementPatch patch = patch(base, new SegmentRefinementOperation.AddNode(
                "op-1", ROOT, null, tier("n-c", ComparisonOperator.NOT_EQUAL, "GOLD")));

        EvaluationReport report = run(root, "case-receipt-add", base, patch,
                new String[] {}, RefinementOperation.ADD_NODE, "n-c", ROOT);

        EvaluationReport.AttemptOutcome outcome = attempt(report);
        assertThat(outcome.evidence().actualRefinementOperation()).isEqualTo("ADD_NODE");
        assertThat(outcome.evidence().actualRefinementOperation()).isEqualTo("ADD_NODE");
        assertThat(outcome.evidence().refinementTargetMatched()).isTrue();
        assertThat(outcome.evidence().refinementMatched()).isTrue();
        assertThat(outcome.evidence().mismatched()).isFalse();
        assertThat(outcome.taxonomy()).isNull();
    }

    @Test
    void aRemovedNodeIsObservedFromTheAcceptedReceipt(@TempDir Path root) {
        SegmentDsl base = document(all(
                tier("n-a", ComparisonOperator.EQUAL, "GOLD"),
                tier("n-b", ComparisonOperator.NOT_EQUAL, "SILVER")));
        SegmentRefinementPatch patch = patch(base, new SegmentRefinementOperation.RemoveNode(
                "op-1", "n-b"));

        EvaluationReport report = run(root, "case-receipt-remove", base, patch,
                new String[] {"m-001", "m-003"}, RefinementOperation.REMOVE_NODE, "n-b", ROOT);

        EvaluationReport.AttemptOutcome outcome = attempt(report);
        assertThat(outcome.evidence().actualRefinementOperation()).isEqualTo("REMOVE_NODE");
        assertThat(outcome.evidence().refinementTargetMatched()).isTrue();
        assertThat(outcome.evidence().refinementScopeMatched()).isTrue();
        assertThat(outcome.evidence().mismatched()).isFalse();
        assertThat(outcome.taxonomy()).isNull();
    }

    @Test
    void aReplacedFieldIsObservedFromTheAcceptedReceipt(@TempDir Path root) {
        SegmentDsl base = document(all(tier("n-a", ComparisonOperator.EQUAL, "GOLD")));
        SegmentRefinementPatch patch = patch(base, new SegmentRefinementOperation.ReplaceField(
                "op-1", "n-a", com.company.campaign.builder.refine.RefinementField.VALUE,
                new com.company.campaign.builder.refine.RefinementReplacement.ConditionReplacement(
                        new EnumValue("SILVER"))));

        EvaluationReport report = run(root, "case-receipt-replace", base, patch,
                new String[] {"m-002"}, RefinementOperation.REPLACE_FIELD, "n-a", ROOT);

        EvaluationReport.AttemptOutcome outcome = attempt(report);
        assertThat(outcome.evidence().actualRefinementOperation()).isEqualTo("REPLACE_FIELD");
        assertThat(outcome.evidence().refinementTargetMatched()).isTrue();
        assertThat(outcome.evidence().mismatched()).isFalse();
        assertThat(outcome.taxonomy()).isNull();
    }

    @Test
    void changingOnlyTheRefinementTargetChangesTaxonomyAndSummary(@TempDir Path root) {
        SegmentDsl base = document(all(
                tier("n-a", ComparisonOperator.EQUAL, "GOLD"),
                tier("n-b", ComparisonOperator.NOT_EQUAL, "SILVER")));
        SegmentRefinementPatch patch = patch(base, new SegmentRefinementOperation.MoveNode(
                "op-1", "n-b", ROOT, "n-a"));

        // The same operation and scope, but the reviewed target names the other node.
        EvaluationReport report = run(root, "case-receipt-target-only", base, patch,
                new String[] {"m-001", "m-003"}, RefinementOperation.MOVE_NODE, "n-a", ROOT);

        EvaluationReport.AttemptOutcome outcome = attempt(report);
        assertThat(outcome.evidence().actualRefinementOperation()).isEqualTo("MOVE_NODE");
        assertThat(outcome.evidence().refinementTargetMatched()).isFalse();
        assertThat(outcome.evidence().refinementScopeMatched()).isTrue();
        assertThat(outcome.evidence().refinementMatched()).isFalse();
        assertThat(outcome.evidence().mismatched()).isTrue();
        assertThat(outcome.taxonomy()).isEqualTo(FailureTaxonomy.COMPARISON_MISMATCH);
        assertThat(report.summary().mismatchedAttemptCount()).isEqualTo(1);
        assertThat(report.summary().runInvalid()).isFalse();
    }

    @Test
    void aLaterReplacementObservesTheScopeAnEarlierMoveProduced(@TempDir Path root) {
        SegmentDsl base = document(all(
                any("n-g2", tier("n-a", ComparisonOperator.EQUAL, "GOLD")),
                tier("n-b", ComparisonOperator.EQUAL, "SILVER")));
        SegmentRefinementPatch patch = patch(base,
                new SegmentRefinementOperation.MoveNode("op-1", "n-b", "n-g2", null),
                new SegmentRefinementOperation.ReplaceField(
                        "op-2", "n-b", com.company.campaign.builder.refine.RefinementField.VALUE,
                        new com.company.campaign.builder.refine.RefinementReplacement.ConditionReplacement(
                                new EnumValue("GOLD"))));

        SegmentRefinementReceipt receipt = runAndObserveReceipt(root, "case-receipt-scope-replace", base, patch);

        assertThat(receipt.operations()).hasSize(2);
        assertThat(receipt.operations().get(0).kind()).isEqualTo(RefinementOpKind.MOVE_NODE);
        assertThat(receipt.operations().get(0).parentNodeId()).isEqualTo("n-g2");
        assertThat(receipt.operations().get(1).kind()).isEqualTo(RefinementOpKind.REPLACE_FIELD);
        // The base-tree parent of n-b is the root n-root; after the earlier move, the operation-time parent
        // the editor observed is n-g2, which is the scope the receipt must publish.
        assertThat(receipt.operations().get(1).parentNodeId()).isEqualTo("n-g2");
        assertThat(receipt.baseDslHash()).isEqualTo(base.editableDslHash());
    }

    @Test
    void aLaterRemovalObservesTheScopeAnEarlierMoveProduced(@TempDir Path root) {
        SegmentDsl base = document(all(
                any("n-g2",
                        tier("n-a", ComparisonOperator.EQUAL, "GOLD"),
                        tier("n-b", ComparisonOperator.EQUAL, "SILVER")),
                tier("n-c", ComparisonOperator.NOT_EQUAL, "GOLD")));
        SegmentRefinementPatch patch = patch(base,
                new SegmentRefinementOperation.MoveNode("op-1", "n-b", ROOT, null),
                new SegmentRefinementOperation.RemoveNode("op-2", "n-b"));

        SegmentRefinementReceipt receipt = runAndObserveReceipt(root, "case-receipt-scope-remove", base, patch);

        assertThat(receipt.operations()).hasSize(2);
        assertThat(receipt.operations().get(0).kind()).isEqualTo(RefinementOpKind.MOVE_NODE);
        assertThat(receipt.operations().get(1).kind()).isEqualTo(RefinementOpKind.REMOVE_NODE);
        // The base-tree parent of n-b is n-g2; after the earlier move into the root, the operation-time parent
        // is n-root, which is the scope the receipt must publish.
        assertThat(receipt.operations().get(1).parentNodeId()).isEqualTo(ROOT);
    }

    /** Runs one two-operation refinement Case and returns the receipt the genuine T12 editor emitted. */
    private static SegmentRefinementReceipt runAndObserveReceipt(
            Path root, String caseId, SegmentDsl base, SegmentRefinementPatch patch) {
        T13TestCorpus.CaseHolder holder = new T13TestCorpus.CaseHolder(
                caseId, SegmentCategory.REFINEMENT_ADD_CHANGE_DELETE_MOVE, RiskLevel.MEDIUM,
                "refinement utterance", T13TestHost.SNAPSHOT_ID, T13TestHost.CONTEXT_FIXTURE);
        holder.fixture(T13TestHost.TIER_FIXTURE)
                .disposition(Disposition.RESOLVED)
                .currentSegment(base)
                .ast(refined(base, patch))
                .refinement(RefinementOperation.REPLACE_FIELD, "n-b", ROOT);
        T13TestCorpus.in(root)
                .metadata(T13TestHost.snapshot())
                .context(T13TestHost.CONTEXT_FIXTURE, T13TestHost.context().snapshotRef())
                .fixture(T13TestHost.TIER_FIXTURE, FixtureRole.PRIMARY, T13TestAssets.TIER_UNIVERSE,
                        T13TestAssets.membersPayload())
                .caseSpec(holder)
                .write();

        T13TestHost host = new T13TestHost(T13Scenarios.tierPort()).refinementPatch(patch);
        T13Scenarios.run(root, "report-" + caseId, host);
        SegmentRefinementReceipt receipt = host.lastObservation().refinementReceipt();
        assertThat(receipt).isNotNull();
        return receipt;
    }

    @Test
    void anOrderedMultiOperationPatchDoesNotInventOnePrecedence(@TempDir Path root) {
        SegmentDsl base = document(all(
                tier("n-a", ComparisonOperator.EQUAL, "GOLD"),
                tier("n-b", ComparisonOperator.NOT_EQUAL, "SILVER")));
        SegmentRefinementPatch patch = patch(base,
                new SegmentRefinementOperation.AddNode(
                        "op-1", ROOT, null, tier("n-c", ComparisonOperator.NOT_EQUAL, "GOLD")),
                new SegmentRefinementOperation.AddNode(
                        "op-2", ROOT, null, tier("n-d", ComparisonOperator.EQUAL, "SILVER")));

        EvaluationReport report = run(root, "case-receipt-multi", base, patch,
                new String[] {}, RefinementOperation.ADD_NODE, "n-c", ROOT);

        EvaluationReport.AttemptOutcome outcome = attempt(report);
        // The singular placeholder names no operation id, so two accepted operations identify none.
        assertThat(outcome.evidence().actualRefinementOperation()).isNull();
        assertThat(outcome.evidence().refinementMatched()).isFalse();
        assertThat(outcome.evidence().mismatched()).isTrue();
        assertThat(outcome.taxonomy()).isEqualTo(FailureTaxonomy.COMPARISON_MISMATCH);
        assertThat(report.summary().runInvalid()).isFalse();
    }
}