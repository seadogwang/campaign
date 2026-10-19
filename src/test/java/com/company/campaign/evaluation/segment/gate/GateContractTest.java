package com.company.campaign.evaluation.segment.gate;

import com.company.campaign.evaluation.segment.contract.GateOutcome;
import com.company.campaign.evaluation.segment.contract.ReviewRole;
import com.company.campaign.evaluation.segment.runner.EvaluationReport;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.company.campaign.evaluation.segment.gate.GateTestSupport.completeEvidence;
import static com.company.campaign.evaluation.segment.gate.GateTestSupport.gateEligibleReport;
import static com.company.campaign.evaluation.segment.gate.GateTestSupport.hash;
import static com.company.campaign.evaluation.segment.gate.GateTestSupport.passedDecision;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Section 1: the closed immutable Gate contracts reject nulls, unknown values, duplicates, inconsistent
 * hashes, malformed owner/timestamp/version evidence and unbounded text, and produce deterministic canonical
 * bytes without any open map or composite score.
 */
class GateContractTest {

    // --- deterministic-layer contracts -----------------------------------------------------------

    @Test
    void aDuplicateOrNullDeterministicCheckIsRejected() {
        List<DeterministicCheck> duplicated = new ArrayList<>();
        duplicated.add(DeterministicCheck.passing(DeterministicCheckId.SCHEMA, 10));
        duplicated.add(DeterministicCheck.passing(DeterministicCheckId.SCHEMA, 10));
        assertThatThrownBy(() -> new DeterministicLayerEvidence(
                GateVersions.DETERMINISTIC_EVIDENCE_VERSION, hash("run-pins"), duplicated,
                ReplayDeterminismEvidence.identical(100, hash("replay"))))
                .isInstanceOf(GateException.class)
                .extracting(exception -> ((GateException) exception).code())
                .isEqualTo(GateErrorCodes.DETERMINISTIC_EVIDENCE_INCOMPLETE);

        List<DeterministicCheck> withNull = new ArrayList<>();
        withNull.add(DeterministicCheck.passing(DeterministicCheckId.SCHEMA, 10));
        withNull.add(null);
        assertThatThrownBy(() -> new DeterministicLayerEvidence(
                GateVersions.DETERMINISTIC_EVIDENCE_VERSION, hash("run-pins"), withNull,
                ReplayDeterminismEvidence.identical(100, hash("replay"))))
                .isInstanceOf(GateException.class);
    }

    @Test
    void aDeterministicCheckRequiresABoundedTally() {
        assertThatThrownBy(() -> new DeterministicCheck(DeterministicCheckId.SCHEMA, 0, 0))
                .isInstanceOf(GateException.class);
        assertThatThrownBy(() -> new DeterministicCheck(DeterministicCheckId.SCHEMA, 11, 10))
                .isInstanceOf(GateException.class);
        assertThatThrownBy(() -> new DeterministicCheck(DeterministicCheckId.SCHEMA, -1, 10))
                .isInstanceOf(GateException.class);
        assertThatThrownBy(() -> new DeterministicCheck(null, 1, 1)).isInstanceOf(GateException.class);
    }

    @Test
    void replayEvidenceRequiresAHashAndBoundedCounts() {
        assertThatThrownBy(() -> new ReplayDeterminismEvidence(100, 1, null)).isInstanceOf(GateException.class);
        assertThatThrownBy(() -> new ReplayDeterminismEvidence(0, 1, hash("replay")))
                .isInstanceOf(GateException.class);
        assertThatThrownBy(() -> new ReplayDeterminismEvidence(10, 11, hash("replay")))
                .isInstanceOf(GateException.class);
        assertThat(ReplayDeterminismEvidence.identical(100, hash("replay")).isSatisfied()).isTrue();
        assertThat(ReplayDeterminismEvidence.identical(99, hash("replay")).isSatisfied()).isFalse();
    }

    @Test
    void deterministicEvidenceRequiresTheExactVersionAndReplayResult() {
        assertThatThrownBy(() -> new DeterministicLayerEvidence(
                "segment-ai-gate-deterministic-evidence/9.9", hash("run-pins"), List.of(),
                ReplayDeterminismEvidence.identical(100, hash("replay"))))
                .isInstanceOf(GateException.class);
        assertThatThrownBy(() -> new DeterministicLayerEvidence(
                GateVersions.DETERMINISTIC_EVIDENCE_VERSION, hash("run-pins"),
                List.of(DeterministicCheck.passing(DeterministicCheckId.SCHEMA, 10)), null))
                .isInstanceOf(GateException.class);
    }

    @Test
    void deterministicEvidenceBindsToTheExactRunPinsAndHashesTheBinding() {
        GateRunPins pins = GateRunPins.from(gateEligibleReport().contract());
        DeterministicLayerEvidence bound = GateTestSupport.completeEvidence(pins.contentHash());
        DeterministicLayerEvidence unbound = DeterministicLayerEvidence.complete(
                null, 25, ReplayDeterminismEvidence.identical(100, hash("replay")));

        assertThat(bound.bindsRunPins(pins)).isTrue();
        assertThat(unbound.bindsRunPins(pins)).as("a missing binding is never a match").isFalse();
        assertThat(bound.contentHash()).as("the binding is part of the canonical evidence bytes")
                .isNotEqualTo(unbound.contentHash());
        assertThat(GateTestSupport.completeEvidence(pins.contentHash()).contentHash())
                .as("the same binding is byte-deterministic")
                .isEqualTo(bound.contentHash());
    }

    // --- threshold contracts ---------------------------------------------------------------------

    @Test
    void aThresholdRuleRejectsUnknownMetricsAndNonCanonicalValues() {
        assertThatThrownBy(() -> new ThresholdRule(
                "CANONICAL_AST_EXACT_MATCH", ThresholdScope.OVERALL, ThresholdComparator.AT_LEAST, "0.95"))
                .isInstanceOf(GateException.class);
        assertThatThrownBy(() -> new ThresholdRule(
                "canonical-ast-exact-match", ThresholdScope.OVERALL, ThresholdComparator.AT_LEAST, "0.90"))
                .isInstanceOf(GateException.class);
        assertThatThrownBy(() -> new ThresholdRule(
                "canonical-ast-exact-match", ThresholdScope.OVERALL, ThresholdComparator.AT_LEAST, "nope"))
                .isInstanceOf(GateException.class);
        assertThatCode(() -> new ThresholdRule(
                "canonical-ast-exact-match", ThresholdScope.OVERALL, ThresholdComparator.AT_LEAST, "0.95"))
                .doesNotThrowAnyException();
    }

    @Test
    void aThresholdCheckRejectsInconsistentOrUnboundedEvidence() {
        assertThatThrownBy(() -> new ThresholdCheck(
                "canonical-ast-exact-match", ThresholdScope.OVERALL, ThresholdComparator.AT_LEAST, "0.95",
                0, 0, 0, false, null, true))
                .as("an undefined check can never pass")
                .isInstanceOf(GateException.class);
        assertThatThrownBy(() -> new ThresholdCheck(
                "canonical-ast-exact-match", ThresholdScope.OVERALL, ThresholdComparator.AT_LEAST, "0.95",
                95, 100, 0, true, "0.949", true))
                .as("an observed value must agree with its counts")
                .isInstanceOf(GateException.class);
        assertThatThrownBy(() -> new ThresholdCheck(
                "canonical-ast-exact-match", ThresholdScope.OVERALL, ThresholdComparator.AT_LEAST, "0.95",
                100, 100, 0, true, "1", false))
                .as("a result must agree with its comparator")
                .isInstanceOf(GateException.class);
        assertThatThrownBy(() -> new ThresholdCheck(
                "canonical-ast-exact-match", ThresholdScope.OVERALL, ThresholdComparator.AT_LEAST, "0.95",
                101, 100, 0, true, "1", false))
                .as("a numerator must not exceed its denominator")
                .isInstanceOf(GateException.class);
    }

    @Test
    void theExactComparatorRejectsAValueAboveAnExactRequirement() {
        assertThat(ThresholdComparator.EXACTLY.satisfies(new java.math.BigDecimal("1.01"),
                new java.math.BigDecimal("1"))).isFalse();
        assertThat(ThresholdComparator.EXACTLY.satisfies(new java.math.BigDecimal("0"),
                new java.math.BigDecimal("0"))).isTrue();
        assertThat(ThresholdComparator.AT_LEAST.satisfies(new java.math.BigDecimal("0.949999999999"),
                new java.math.BigDecimal("0.95"))).isFalse();
    }

    // --- decision contract -----------------------------------------------------------------------

    @Test
    void aDecisionRequiresItsExactVersionVerdictAndTime() {
        GateDecision decision = passedDecision();
        assertThatThrownBy(() -> new GateDecision(
                "segment-ai-gate-decision/9.9", decision.decisionId(), decision.outcome(),
                decision.reportHash(), decision.runPins(), decision.deterministicEvidence(),
                decision.thresholdChecks(), decision.issueCodes(), decision.reviewIssueCodes(),
                decision.decidedAt(), decision.decisionHash()))
                .isInstanceOf(GateException.class);
        assertThatThrownBy(() -> new GateDecision(
                GateVersions.DECISION_SCHEMA_VERSION, "bad id with spaces", decision.outcome(),
                decision.reportHash(), decision.runPins(), decision.deterministicEvidence(),
                decision.thresholdChecks(), decision.issueCodes(), decision.reviewIssueCodes(),
                decision.decidedAt(), decision.decisionHash()))
                .isInstanceOf(GateException.class);
        assertThatThrownBy(() -> new GateDecision(
                GateVersions.DECISION_SCHEMA_VERSION, decision.decisionId(), decision.outcome(),
                decision.reportHash(), decision.runPins(), decision.deterministicEvidence(),
                decision.thresholdChecks(), List.of("lowercase-code"), decision.reviewIssueCodes(),
                decision.decidedAt(), decision.decisionHash()))
                .as("an unbounded issue code is rejected")
                .isInstanceOf(GateException.class);
    }

    @Test
    void anInvalidDecisionCarriesNoThresholdChecksAndAJudgedOneCarriesThemAll() {
        GateDecision judged = passedDecision();
        assertThatThrownBy(() -> new GateDecision(
                GateVersions.DECISION_SCHEMA_VERSION, "gate-decision-1", GateOutcome.INVALID,
                judged.reportHash(), judged.runPins(), judged.deterministicEvidence(),
                judged.thresholdChecks(), List.of(), List.of(), judged.decidedAt(), null))
                .as("INVALID is not threshold-evaluated")
                .isInstanceOf(GateException.class);
        assertThatThrownBy(() -> new GateDecision(
                GateVersions.DECISION_SCHEMA_VERSION, "gate-decision-1", GateOutcome.PASSED,
                judged.reportHash(), judged.runPins(), judged.deterministicEvidence(),
                judged.thresholdChecks().subList(0, 1), List.of(), List.of(), judged.decidedAt(), null))
                .as("a judged decision must carry every threshold check")
                .isInstanceOf(GateException.class);
    }

    @Test
    void duplicateIssueCodesAreCollapsedInDeclarationOrder() {
        GateDecision judged = passedDecision();
        GateDecision decision = GateDecision.of(
                "gate-decision-1", GateOutcome.INVALID, judged.reportHash(), judged.runPins(),
                judged.deterministicEvidence(), List.of(),
                List.of(GateErrorCodes.REPORT_RUN_INVALID, GateErrorCodes.REPORT_RUN_INVALID), List.of(),
                Instant.parse("2026-10-02T09:30:00Z"));

        assertThat(decision.issueCodes()).containsExactly(GateErrorCodes.REPORT_RUN_INVALID);
    }

    @Test
    void aDecisionIsByteDeterministicAndCarriesNoCompositeScore() {
        GateDecision first = passedDecision();
        GateDecision second = passedDecision();

        assertThat(first.canonicalBytes()).isEqualTo(second.canonicalBytes());
        assertThat(first.decisionHash()).isEqualTo(second.decisionHash());
        assertThat(first.decisionHash()).isEqualTo(first.recomputeHash());
        assertThat(GateDecision.class.getMethods())
                .noneMatch(method -> method.getName().toLowerCase(Locale.ROOT).contains("score"));
        assertThat(GateDecision.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly(
                        "decisionSchemaVersion", "decisionId", "outcome", "reportHash", "runPins",
                        "deterministicEvidence", "thresholdChecks", "issueCodes", "reviewIssueCodes",
                        "decidedAt", "decisionHash");
    }

    @Test
    void runPinsRequireTheExactAcceptedVersionsAndEveryGroup() {
        GateRunPins pins = GateRunPins.from(gateEligibleReport().contract());
        assertThatThrownBy(() -> new GateRunPins(
                "other-run-contract/1.0", pins.reportSchemaVersion(), pins.runContractHash(), pins.model(),
                pins.decoding(), pins.artifacts(), pins.parsers(), pins.metadata(), pins.candidateCorpus(),
                pins.review(), pins.time(), pins.code(), pins.cases()))
                .isInstanceOf(GateException.class);
        assertThatThrownBy(() -> new GateRunPins(
                pins.runContractVersion(), pins.reportSchemaVersion(), pins.runContractHash(), null,
                pins.decoding(), pins.artifacts(), pins.parsers(), pins.metadata(), pins.candidateCorpus(),
                pins.review(), pins.time(), pins.code(), pins.cases()))
                .isInstanceOf(GateException.class);
        assertThatThrownBy(() -> new GateRunPins(
                pins.runContractVersion(), pins.reportSchemaVersion(), pins.runContractHash(), pins.model(),
                pins.decoding(), pins.artifacts(), pins.parsers(), pins.metadata(), pins.candidateCorpus(),
                pins.review(), pins.time(), pins.code(), List.of()))
                .isInstanceOf(GateException.class);
    }

    @Test
    void anEvaluationRequestRequiresEveryTrustedInput() {
        EvaluationReport report = gateEligibleReport();
        GateEvidencePins pins = GateEvidencePins.of(report);
        assertThatThrownBy(() -> new GateEvaluationRequest("gate-decision-1", null, pins, completeEvidence(),
                Instant.parse("2026-10-02T09:30:00Z")))
                .isInstanceOf(GateException.class);
        assertThatThrownBy(() -> new GateEvaluationRequest("gate-decision-1", report, pins, completeEvidence(),
                null))
                .isInstanceOf(GateException.class);
        assertThatThrownBy(() -> new GateEvaluationRequest("bad id", report, pins, completeEvidence(),
                Instant.parse("2026-10-02T09:30:00Z")))
                .isInstanceOf(GateException.class);
    }

    // --- approval and readiness contracts --------------------------------------------------------

    @Test
    void ownerRolesMatchTheReviewRolesTheyMirror() {
        assertThat(GateOwnerRole.PRODUCT.reviewRole()).isEqualTo(ReviewRole.PRODUCT);
        assertThat(GateOwnerRole.DATA.reviewRole()).isEqualTo(ReviewRole.DATA);
        assertThat(GateOwnerRole.TEST.reviewRole()).isEqualTo(ReviewRole.TEST);
        assertThat(GateOwnerRole.values()).hasSize(3);
    }

    @Test
    void aGateOwnerApprovalRequiresABoundedOpaqueOwnerAndExplicitTime() {
        assertThatThrownBy(() -> new GateOwnerApproval(
                "owner@example.com", GateOwnerRole.PRODUCT, Instant.parse("2026-10-02T09:30:00Z")))
                .isInstanceOf(GateException.class)
                .extracting(exception -> ((GateException) exception).code())
                .isEqualTo(GateErrorCodes.APPROVAL_INVALID);
        assertThatThrownBy(() -> new GateOwnerApproval("owner-product-1", null, Instant.parse("2026-10-02T09:30:00Z")))
                .isInstanceOf(GateException.class);
        assertThatThrownBy(() -> new GateOwnerApproval("owner-product-1", GateOwnerRole.PRODUCT, null))
                .isInstanceOf(GateException.class);
    }

    @Test
    void anApprovalCommandRejectsNullDuplicateConflictingAndExtraOwnerEvidence() {
        ContentHash decisionHash = passedDecision().decisionHash();
        GateOwnerApproval product = GateTestSupport.owner("owner-product-1", GateOwnerRole.PRODUCT);
        GateOwnerApproval data = GateTestSupport.owner("owner-data-1", GateOwnerRole.DATA);
        GateOwnerApproval test = GateTestSupport.owner("owner-test-1", GateOwnerRole.TEST);

        assertThatThrownBy(() -> new GateApprovalCommand(decisionHash, null)).isInstanceOf(GateException.class);
        assertThatThrownBy(() -> new GateApprovalCommand(decisionHash,
                java.util.Arrays.asList(product, null, test)))
                .as("a null owner is rejected")
                .isInstanceOf(GateException.class);
        assertThatThrownBy(() -> new GateApprovalCommand(decisionHash,
                List.of(product, GateTestSupport.owner("owner-product-2", GateOwnerRole.PRODUCT), test)))
                .as("a duplicated role is rejected")
                .isInstanceOf(GateException.class)
                .extracting(exception -> ((GateException) exception).code())
                .isEqualTo(GateErrorCodes.APPROVAL_INVALID);
        assertThatThrownBy(() -> new GateApprovalCommand(decisionHash,
                List.of(product, GateTestSupport.owner("owner-product-1", GateOwnerRole.DATA), test)))
                .as("one owner reference cannot hold two roles")
                .isInstanceOf(GateException.class);
        assertThatThrownBy(() -> new GateApprovalCommand(decisionHash,
                List.of(product, data, test, product)))
                .as("extra owner evidence is rejected")
                .isInstanceOf(GateException.class);
    }

    @Test
    void ownerApprovalsAreCanonicalizedIntoStableRoleOrderIndependentOfInputOrder() {
        ContentHash decisionHash = passedDecision().decisionHash();
        GateOwnerApproval product = GateTestSupport.owner("owner-product-1", GateOwnerRole.PRODUCT);
        GateOwnerApproval data = GateTestSupport.owner("owner-data-1", GateOwnerRole.DATA);
        GateOwnerApproval test = GateTestSupport.owner("owner-test-1", GateOwnerRole.TEST);

        GateApprovalCommand inOrder = new GateApprovalCommand(decisionHash, List.of(product, data, test));
        GateApprovalCommand reversed = new GateApprovalCommand(decisionHash, List.of(test, data, product));

        assertThat(reversed.ownerApprovals()).isEqualTo(inOrder.ownerApprovals());
        assertThat(reversed.ownerApprovals()).extracting(GateOwnerApproval::ownerRole)
                .containsExactly(GateOwnerRole.PRODUCT, GateOwnerRole.DATA, GateOwnerRole.TEST);
    }

    @Test
    void anApprovalRecordRequiresItsDecisionVerdictAndExplicitOwnerEvidence() {
        assertThatThrownBy(() -> GateApprovalRecord.of(null, GateOutcome.PASSED, GateTestSupport.quorum()))
                .isInstanceOf(GateException.class);
        assertThatThrownBy(() -> GateApprovalRecord.of(hash("decision"), null, GateTestSupport.quorum()))
                .isInstanceOf(GateException.class);
        assertThatThrownBy(() -> GateApprovalRecord.of(hash("decision"), GateOutcome.PASSED, null))
                .isInstanceOf(GateException.class);
    }

    @Test
    void anIncompleteQuorumIsRepresentableButNeverComplete() {
        GateApprovalRecord partial = GateApprovalRecord.of(
                hash("decision"), GateOutcome.PASSED,
                List.of(GateTestSupport.owner("owner-product-1", GateOwnerRole.PRODUCT)));

        assertThat(partial.hasCompleteQuorum()).isFalse();
        assertThat(GateApprovalRecord.of(hash("decision"), GateOutcome.PASSED, GateTestSupport.quorum())
                .hasCompleteQuorum()).isTrue();
        assertThat(partial.approvalHash()).isEqualTo(partial.recomputeHash());
    }

    @Test
    void anApprovedDecisionPairRejectsAMismatchedApprovalHash() {
        GateDecision decision = passedDecision();
        GateApprovalRecord approval = GateApprovalRecord.of(
                decision.decisionHash(), decision.outcome(), GateTestSupport.quorum());

        assertThatThrownBy(() -> new GateApprovedDecision(
                "segment.rule-based", decision,
                GateApprovalRecord.of(hash("other-decision"), decision.outcome(), GateTestSupport.quorum())))
                .isInstanceOf(GateException.class)
                .extracting(exception -> ((GateException) exception).code())
                .isEqualTo(GateErrorCodes.APPROVAL_DECISION_HASH_MISMATCH);
        assertThatCode(() -> new GateApprovedDecision("segment.rule-based", decision, approval))
                .doesNotThrowAnyException();
        assertThat(new GateApprovedDecision("segment.rule-based", decision, approval).isValid()).isTrue();
    }

    @Test
    void aReadinessProjectionRequiresConsistentStateAndBoundedReasons() {
        assertThatThrownBy(() -> SegmentReadinessProjection.of(
                "segment.rule-based", CapabilityState.EXPERIMENTAL, true, List.of(), null, hash("inputs")))
                .as("only an ACTIVE projection may be AI_READY")
                .isInstanceOf(GateException.class);
        assertThatThrownBy(() -> SegmentReadinessProjection.of(
                "segment.rule-based", CapabilityState.ACTIVE, true, List.of(), null, hash("inputs")))
                .as("an ACTIVE projection must rest on an approved decision")
                .isInstanceOf(GateException.class);
        assertThatThrownBy(() -> SegmentReadinessProjection.of(
                "segment.rule-based", CapabilityState.EXPERIMENTAL, false, List.of("lowercase"),
                null, hash("inputs")))
                .as("a reason code must be bounded")
                .isInstanceOf(GateException.class);
    }
}