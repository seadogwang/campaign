package com.company.campaign.evaluation.segment.gate;

import com.company.campaign.builder.segment.SegmentIntentModelPort;
import com.company.campaign.evaluation.segment.contract.GateOutcome;
import com.company.campaign.evaluation.segment.runner.DecodingParameters;
import com.company.campaign.evaluation.segment.runner.EvaluationReport;
import com.company.campaign.evaluation.segment.runner.EvaluationRunContract;
import com.company.campaign.evaluation.segment.runner.EvaluationRunRequest;
import com.company.campaign.evaluation.segment.runner.SegmentEvaluationRunner;
import com.company.campaign.evaluation.segment.runner.testsupport.T13CorpusV1Host;
import com.company.campaign.shared.canonical.CanonicalJson;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static com.company.campaign.evaluation.segment.gate.GateTestSupport.DECIDED_AT;
import static com.company.campaign.evaluation.segment.gate.GateTestSupport.completeEvidence;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section 3 and section 5: the current checked-in T13 diagnostic report cannot produce {@code PASSED}, and the
 * decision it yields carries no raw utterance, member identifier, tenant/user/DataScope fact or SQL.
 *
 * <p>The report is produced by the genuine T13 runner over the committed candidate corpus with a deterministic
 * fake provider and a synthetic deterministic-layer artifact. No Corpus asset is edited and no human review
 * decision is fabricated for it.
 */
class GateCheckedInCorpusTest {

    private static final Path REPOSITORY_ROOT = Path.of(".");
    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    @Test
    void theCheckedInDiagnosticReportCannotProducePassed() {
        EvaluationReport report = checkedInReport();
        assertThat(report.evidenceClass()).isNotEqualTo(EvaluationReport.ReportEvidenceClass.GATE_ELIGIBLE);

        GateEvidencePins pins = GateEvidencePins.of(report);
        GateDecision decision = new SegmentAiGateEvaluator().evaluate(new GateEvaluationRequest(
                "gate-decision-corpus", report, pins, completeEvidence(report), DECIDED_AT));

        assertThat(decision.outcome()).isEqualTo(GateOutcome.INVALID);
        assertThat(decision.issueCodes()).contains(
                GateErrorCodes.REPORT_NOT_GATE_ELIGIBLE,
                GateErrorCodes.REVIEW_EVIDENCE_NOT_READY,
                GateErrorCodes.REPORT_RUN_INVALID,
                GateErrorCodes.ATTEMPTS_UNACCOUNTED);
        assertThat(decision.reviewIssueCodes()).isNotEmpty();
        assertThat(decision.thresholdChecks()).isEmpty();
        assertThat(decision.reportHash()).isEqualTo(report.contentHash());
        assertThat(decision.runPins().cases()).hasSize(225);
    }

    @Test
    void theCheckedInCorpusDecisionCarriesNoRawSensitiveEvidence() {
        EvaluationReport report = checkedInReport();
        GateDecision decision = new SegmentAiGateEvaluator().evaluate(new GateEvaluationRequest(
                "gate-decision-corpus", report, GateEvidencePins.of(report), completeEvidence(report),
                DECIDED_AT));
        String serialized = new String(decision.canonicalBytes(), StandardCharsets.UTF_8);

        assertThat(serialized)
                .doesNotContain("Gold会员")
                .doesNotContain("m-001")
                .doesNotContain("tenant-001")
                .doesNotContain("user-001")
                .doesNotContain("scope-008")
                .doesNotContain("SELECT ")
                .doesNotContain(" FROM ")
                .doesNotContain("DROP TABLE")
                .doesNotContain("INSERT INTO");
    }

    private static EvaluationReport checkedInReport() {
        return new SegmentEvaluationRunner(REPOSITORY_ROOT).run(
                corpusRequest(), new T13CorpusV1Host(wholeUtteranceTierPort(), REPOSITORY_ROOT));
    }

    private static EvaluationRunRequest corpusRequest() {
        return new EvaluationRunRequest(
                "t13-corpus-v1-report",
                new EvaluationRunContract.ModelPin(
                        "deterministic-replay-provider", "t13-corpus-replay", "t13-corpus-replay/1.0"),
                DecodingParameters.deterministicReplay(4096, 20261006L),
                "code-revision-t13-corpus",
                "environment-t13-corpus",
                T13CorpusV1Host.CORPUS_V1_MANIFEST);
    }

    /** The same deterministic fake provider the T13 corpus test uses: a pure function of the utterance. */
    private static SegmentIntentModelPort wholeUtteranceTierPort() {
        return request -> {
            String utterance = request.utterance();
            String clause = "{\"clauseId\":\"c1\",\"concept\":\"MEMBER_TIER\",\"definitionKind\":\"ATTRIBUTE\","
                    + "\"negated\":false,\"operator\":\"EQUAL\",\"value\":{\"kind\":\"ENUM\",\"raw\":\"Gold\"},"
                    + "\"provenance\":{\"type\":\"SOURCE_SPAN\",\"text\":"
                    + CANONICAL_JSON.writeString(utterance)
                    + ",\"start\":0,\"end\":" + utterance.length() + "},"
                    + "\"candidateHints\":[],\"assumptions\":[]}";
            String json = "{\"intentVersion\":\"1.3\",\"subject\":\"MEMBER\",\"clauses\":[" + clause + "],"
                    + "\"logicalExpression\":{\"type\":\"CLAUSE\",\"clauseId\":\"c1\"},\"ambiguities\":[],"
                    + "\"unsupportedRequirements\":[]}";
            return json.getBytes(StandardCharsets.UTF_8);
        };
    }
}