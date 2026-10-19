package com.company.campaign.evaluation.segment.gate;

import com.company.campaign.evaluation.segment.contract.GateOutcome;
import com.company.campaign.evaluation.segment.review.CorpusUsageClassification;
import com.company.campaign.evaluation.segment.runner.EvaluationReport;
import com.company.campaign.evaluation.segment.runner.EvaluationRunContract;
import com.company.campaign.evaluation.segment.runner.MetricId;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static com.company.campaign.evaluation.segment.gate.GateTestSupport.AS_OF;
import static com.company.campaign.evaluation.segment.gate.GateTestSupport.DECIDED_AT;
import static com.company.campaign.evaluation.segment.gate.GateTestSupport.HIGH;
import static com.company.campaign.evaluation.segment.gate.GateTestSupport.caseOutcome;
import static com.company.campaign.evaluation.segment.gate.GateTestSupport.contract;
import static com.company.campaign.evaluation.segment.gate.GateTestSupport.hash;
import static com.company.campaign.evaluation.segment.gate.GateTestSupport.metric;
import static com.company.campaign.evaluation.segment.gate.GateTestSupport.metricsWith;
import static com.company.campaign.evaluation.segment.gate.GateTestSupport.passingHighRiskMetrics;
import static com.company.campaign.evaluation.segment.gate.GateTestSupport.passingMetrics;
import static com.company.campaign.evaluation.segment.gate.GateTestSupport.report;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section 4 and section 5: only the exact latest approved {@code PASSED} pins yield {@code ACTIVE} and the
 * Campaign {@code AI_READY} projection; approved {@code FAILED}/{@code INVALID} and missing approval remain
 * {@code EXPERIMENTAL}; every gated-input change yields {@code EVALUATION_REQUIRED}.
 */
class SegmentCapabilityReadinessTest {

    private static final String SEGMENT = SegmentCapabilityReadinessProjector.SEGMENT_RULE_BASED;
    private static final String MODEL = "gate-replay/1.0";
    private static final String PARSER = "resolver/1.0";
    private static final String CODE = "code-revision-gate";

    // --- ACTIVE only for the exact latest approved PASSED pins ------------------------------------

    @Test
    void onlyTheLatestApprovedPassedExactPinsYieldActiveAndAiReady() {
        GateApprovedDecision approved = approved(passedDecision());
        SegmentReadinessProjection projection = project(approved, approved.decision().runPins());

        assertThat(projection.state()).isEqualTo(CapabilityState.ACTIVE);
        assertThat(projection.aiReady()).isTrue();
        assertThat(projection.reasonCodes()).isEmpty();
        assertThat(projection.approvedDecisionHash()).isEqualTo(approved.decision().decisionHash());
    }

    @Test
    void aMissingApprovalRemainsExperimental() {
        SegmentReadinessProjection projection = project(null, currentPins());

        assertThat(projection.state()).isEqualTo(CapabilityState.EXPERIMENTAL);
        assertThat(projection.aiReady()).isFalse();
        assertThat(projection.reasonCodes()).containsExactly(GateErrorCodes.APPROVAL_MISSING);
    }

    @Test
    void anApprovedFailedDecisionRemainsExperimental() {
        GateDecision failed = failingDecision();
        assertThat(failed.outcome()).isEqualTo(GateOutcome.FAILED);

        SegmentReadinessProjection projection = project(approved(failed), failed.runPins());

        assertThat(projection.state()).isEqualTo(CapabilityState.EXPERIMENTAL);
        assertThat(projection.aiReady()).isFalse();
        assertThat(projection.reasonCodes()).containsExactly(GateErrorCodes.NOT_PASSED);
    }

    @Test
    void anApprovedInvalidDecisionRemainsExperimental() {
        GateDecision invalid = invalidDecision();
        assertThat(invalid.outcome()).isEqualTo(GateOutcome.INVALID);

        SegmentReadinessProjection projection = project(approved(invalid), invalid.runPins());

        assertThat(projection.state()).isEqualTo(CapabilityState.EXPERIMENTAL);
        assertThat(projection.reasonCodes()).containsExactly(GateErrorCodes.NOT_PASSED);
    }

    @Test
    void aForgedDecisionHashFromLookupNeverActivates() {
        GateDecision forged = passedDecision().withDecisionHash(hash("forged-decision"));
        GateApprovedDecision tampered = new GateApprovedDecision(
                SEGMENT, forged,
                GateApprovalRecord.of(forged.decisionHash(), forged.outcome(), GateTestSupport.quorum()));

        SegmentReadinessProjection projection = project(tampered, forged.runPins());

        assertThat(projection.state()).isEqualTo(CapabilityState.EXPERIMENTAL);
        assertThat(projection.aiReady()).isFalse();
        assertThat(projection.reasonCodes()).containsExactly(GateErrorCodes.APPROVAL_INVALID);
    }

    @Test
    void aTamperedApprovalHashFromLookupNeverActivates() {
        GateDecision decision = passedDecision();
        GateApprovalRecord tampered = new GateApprovalRecord(
                GateVersions.APPROVAL_SCHEMA_VERSION, decision.decisionHash(), decision.outcome(),
                GateTestSupport.quorum(), null);
        GateApprovedDecision approved = new GateApprovedDecision(SEGMENT, decision, tampered);

        SegmentReadinessProjection projection = project(approved, decision.runPins());

        assertThat(projection.state()).isEqualTo(CapabilityState.EXPERIMENTAL);
        assertThat(projection.reasonCodes()).containsExactly(GateErrorCodes.APPROVAL_INVALID);
    }

    @Test
    void aConflictingOutcomeFromLookupNeverActivates() {
        GateDecision decision = passedDecision();
        GateApprovalRecord conflicting = GateApprovalRecord.of(
                decision.decisionHash(), GateOutcome.FAILED, GateTestSupport.quorum());
        GateApprovedDecision approved = new GateApprovedDecision(SEGMENT, decision, conflicting);

        SegmentReadinessProjection projection = project(approved, decision.runPins());

        assertThat(projection.state()).isEqualTo(CapabilityState.EXPERIMENTAL);
        assertThat(projection.reasonCodes()).containsExactly(GateErrorCodes.APPROVAL_INVALID);
    }

    @Test
    void anIncompleteQuorumFromLookupNeverActivates() {
        GateDecision decision = passedDecision();
        GateApprovalRecord partial = GateApprovalRecord.of(
                decision.decisionHash(), decision.outcome(),
                List.of(GateTestSupport.owner("owner-product-1", GateOwnerRole.PRODUCT)));
        GateApprovedDecision approved = new GateApprovedDecision(SEGMENT, decision, partial);

        SegmentReadinessProjection projection = project(approved, decision.runPins());

        assertThat(projection.state()).isEqualTo(CapabilityState.EXPERIMENTAL);
        assertThat(projection.reasonCodes()).containsExactly(GateErrorCodes.APPROVAL_INVALID);
    }

    @Test
    void aThrowingOrMismatchedLookupNeverActivates() {
        assertThat(projectViaPort(capabilityId -> {
            throw new IllegalStateException("lookup unavailable");
        }, currentPins()).state()).isEqualTo(CapabilityState.EXPERIMENTAL);

        assertThat(projectViaPort(capabilityId -> null, currentPins()).reasonCodes())
                .as("a null lookup answer is malformed evidence")
                .containsExactly(GateErrorCodes.APPROVAL_INVALID);

        GateApprovedDecision mismatched = withCapability(approved(passedDecision()), "other.capability");
        SegmentReadinessProjection projection = project(mismatched, currentPins());

        assertThat(projection.state()).isEqualTo(CapabilityState.EXPERIMENTAL);
        assertThat(projection.reasonCodes()).containsExactly(GateErrorCodes.APPROVAL_INVALID);
    }

    @Test
    void aCapabilityOutsideTheGateStaysExperimental() {
        SegmentReadinessProjection projection = new SegmentCapabilityReadinessProjector(
                GateTestSupport.lookup(approved(passedDecision())))
                .project("campaign.journey", currentPins());

        assertThat(projection.state()).isEqualTo(CapabilityState.EXPERIMENTAL);
        assertThat(projection.aiReady()).isFalse();
        assertThat(projection.reasonCodes()).containsExactly(GateErrorCodes.CAPABILITY_NOT_GATED);
    }

    // --- every gated-input change yields EVALUATION_REQUIRED --------------------------------------

    @Test
    void anUnchangedContractStaysActive() {
        GateApprovedDecision approved = approved(passedDecision());

        assertThat(project(approved, currentPins()).state()).isEqualTo(CapabilityState.ACTIVE);
    }

    @Test
    void everyGatedInputChangeYieldsEvaluationRequired() {
        GateApprovedDecision approved = approved(passedDecision());

        List<EvaluationRunContract> changed = List.of(
                variant("gate-replay/2.0", hash("system-prompt"), PARSER, hash("metadata-catalog"),
                        hash("corpus"), hash("review-set"), AS_OF, CODE, hash("fixture-payload")),
                variant(MODEL, hash("other-prompt"), PARSER, hash("metadata-catalog"),
                        hash("corpus"), hash("review-set"), AS_OF, CODE, hash("fixture-payload")),
                variant(MODEL, hash("system-prompt"), "resolver/2.0", hash("metadata-catalog"),
                        hash("corpus"), hash("review-set"), AS_OF, CODE, hash("fixture-payload")),
                variant(MODEL, hash("system-prompt"), PARSER, hash("other-metadata"),
                        hash("corpus"), hash("review-set"), AS_OF, CODE, hash("fixture-payload")),
                variant(MODEL, hash("system-prompt"), PARSER, hash("metadata-catalog"),
                        hash("other-corpus"), hash("review-set"), AS_OF, CODE, hash("fixture-payload")),
                variant(MODEL, hash("system-prompt"), PARSER, hash("metadata-catalog"),
                        hash("corpus"), hash("other-review-set"), AS_OF, CODE, hash("fixture-payload")),
                variant(MODEL, hash("system-prompt"), PARSER, hash("metadata-catalog"),
                        hash("corpus"), hash("review-set"), Instant.parse("2026-10-03T00:00:00Z"), CODE,
                        hash("fixture-payload")),
                variant(MODEL, hash("system-prompt"), PARSER, hash("metadata-catalog"),
                        hash("corpus"), hash("review-set"), AS_OF, "code-revision-other",
                        hash("fixture-payload")),
                variant(MODEL, hash("system-prompt"), PARSER, hash("metadata-catalog"),
                        hash("corpus"), hash("review-set"), AS_OF, CODE, hash("other-fixture-payload")));

        for (EvaluationRunContract changedContract : changed) {
            GateRunPins changedPins = GateRunPins.from(changedContract);
            SegmentReadinessProjection projection = project(approved, changedPins);
            assertThat(projection.state()).as(changedPins.contentHash().value())
                    .isEqualTo(CapabilityState.EVALUATION_REQUIRED);
            assertThat(projection.aiReady()).isFalse();
            assertThat(projection.reasonCodes()).containsExactly(GateErrorCodes.GATED_INPUT_CHANGED);
        }
    }

    @Test
    void theProjectionIsByteDeterministic() {
        GateApprovedDecision approved = approved(passedDecision());
        GateRunPins pins = approved.decision().runPins();

        assertThat(project(approved, pins).contentHash()).isEqualTo(project(approved, pins).contentHash());
        assertThat(project(approved, pins).contentHash())
                .isNotEqualTo(project((GateApprovedDecision) null, pins).contentHash());
    }

    // --- helpers ---------------------------------------------------------------------------------

    private SegmentReadinessProjection project(GateApprovedDecision approved, GateRunPins currentInputs) {
        return projectViaPort(GateTestSupport.lookup(approved), currentInputs);
    }

    private SegmentReadinessProjection projectViaPort(GateApprovalLookupPort port, GateRunPins currentInputs) {
        return new SegmentCapabilityReadinessProjector(port).project(SEGMENT, currentInputs);
    }

    private static GateRunPins currentPins() {
        return GateRunPins.from(defaultVariant());
    }

    private static EvaluationRunContract defaultVariant() {
        return variant(MODEL, hash("system-prompt"), PARSER, hash("metadata-catalog"), hash("corpus"),
                hash("review-set"), AS_OF, CODE, hash("fixture-payload"));
    }

    private static EvaluationRunContract variant(
            String modelVersion, ContentHash artifactHash, String parserVersion, ContentHash metadataHash,
            ContentHash corpusHash, ContentHash reviewSetHash, Instant asOf, String codeRevision,
            ContentHash fixturePayloadHash) {
        return contract(
                CorpusUsageClassification.GATE_ELIGIBLE, List.of(), 5, HIGH, modelVersion, artifactHash,
                parserVersion, metadataHash, corpusHash, reviewSetHash, asOf, codeRevision, fixturePayloadHash);
    }

    private static GateDecision passedDecision() {
        return GateTestSupport.passedDecision();
    }

    private static GateDecision failingDecision() {
        EvaluationReport report = GateTestSupport.gateEligibleReport(
                metricsWith(MetricId.CANONICAL_AST_EXACT_MATCH, metric(
                        MetricId.CANONICAL_AST_EXACT_MATCH, 0, 100)),
                passingHighRiskMetrics());
        return new SegmentAiGateEvaluator().evaluate(GateTestSupport.request(report));
    }

    private static GateDecision invalidDecision() {
        EvaluationReport report = report(
                contract(CorpusUsageClassification.DEVELOPMENT_ONLY,
                        List.of("EVALUATION_REVIEW_PENDING"), 5, HIGH),
                false, List.of(caseOutcome("case-high-1", HIGH, 5)), passingMetrics(),
                passingHighRiskMetrics(), new EvaluationReport.Summary(1, 5, 5, 0, 5, 0, 0, 0, false));
        return new SegmentAiGateEvaluator().evaluate(GateTestSupport.request(report));
    }

    private static GateApprovedDecision approved(GateDecision decision) {
        GateApprovalRecord approval = new GateApprovalService(GateTestSupport.permissiveAuthorization())
                .approve(decision, GateTestSupport.quorumCommand(decision.decisionHash()));
        return new GateApprovedDecision(SEGMENT, decision, approval);
    }

    private static GateApprovedDecision withCapability(GateApprovedDecision approved, String capabilityId) {
        return new GateApprovedDecision(capabilityId, approved.decision(), approved.approval());
    }
}