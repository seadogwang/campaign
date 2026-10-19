package com.company.campaign.evaluation.segment.runner;

import com.company.campaign.builder.segment.SegmentIntentModelPort;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.evaluation.segment.loader.SegmentEvaluationLoader;
import com.company.campaign.evaluation.segment.runner.testsupport.T13CorpusV1Host;
import com.company.campaign.shared.canonical.CanonicalJson;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section 7: the full checked-in 225-Case corpus, driven by deterministic fake provider responses and the real
 * loader and review classification. Every required Case and attempt is accounted for, and the report stays
 * permanently {@code DEVELOPMENT_ONLY} while the human review decisions remain pending.
 */
class SegmentEvaluationCorpusV1Test {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();
    private static final Path REPOSITORY_ROOT = Path.of(".");

    /**
     * A deterministic provider that answers every Case with the same schema-valid Intent, grounded in the exact
     * utterance it was handed.
     *
     * <p>It is a fake, not a model: it reads no clock, no random source and no corpus asset, and its answer is a
     * pure function of the bounded request. That is what lets the whole corpus exercise the genuine T10/T07/T08/
     * T09C pipeline deterministically; whatever accuracy the report then shows is what a model emitting this
     * fixed answer would actually achieve, reported honestly rather than curated.
     */
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

    private static EvaluationReport runCorpus() {
        return new SegmentEvaluationRunner(REPOSITORY_ROOT).run(
                corpusRequest(), new T13CorpusV1Host(wholeUtteranceTierPort(), REPOSITORY_ROOT));
    }

    @Test
    void theFullCheckedInCorpusIsAccountedForAndStopsAtTheUnsoundReviewedExpectation() {
        List<String> manifestCaseIds = new SegmentEvaluationLoader(REPOSITORY_ROOT)
                .loadCorpus(T13CorpusV1Host.CORPUS_V1_MANIFEST)
                .manifest()
                .cases()
                .stream()
                .map(reference -> reference.caseId())
                .toList();
        assertThat(manifestCaseIds).hasSize(225);

        EvaluationReport report = runCorpus();

        // Every Case, in the exact frozen manifest order, and every required attempt accounted for. The first
        // reviewed expectation the genuine T07 refuses is unsound input evidence: it stops the run
        // deterministically and every later required attempt is recorded as not run, never dropped.
        assertThat(report.cases()).extracting(EvaluationReport.CaseOutcome::caseId)
                .containsExactlyElementsOf(manifestCaseIds);
        assertThat(report.summary().caseCount()).isEqualTo(225);
        assertThat(report.summary().requiredAttemptCount()).isEqualTo(993);
        assertThat(report.summary().executedAttemptCount()).isEqualTo(80);
        assertThat(report.summary().notRunAttemptCount()).isEqualTo(913);
        assertThat(report.cases()).allSatisfy(outcome -> {
            assertThat(outcome.requiredAttempts()).isEqualTo(outcome.attempts().size() + outcome.notRunAttempts());
            assertThat(outcome.attempts().size() + outcome.notRunAttempts())
                    .isEqualTo(outcome.riskLevel().equals("HIGH") ? 5 : 1);
            if (outcome.notRunAttempts() > 0) {
                assertThat(outcome.notRunCode())
                        .isEqualTo(SegmentEvaluationRunner.NOT_RUN_DUE_TO_RUN_INVALID);
            }
        });

        // High-risk Cases still require exactly five attempts and every other Case exactly one; the required
        // counts are frozen independently of how far execution actually got.
        assertThat(report.cases().get(0).requiredAttempts()).isEqualTo(1);
        assertThat(report.cases().get(3).requiredAttempts()).isEqualTo(5);

        // The reviewed expectation that stops the run is recorded honestly against its exact Case, with its
        // bounded expected-evidence code, and it is input invalidity rather than a model answer.
        EvaluationReport.CaseOutcome stopped = report.cases().get(27);
        assertThat(stopped.caseId()).isEqualTo("case-bool-13-or-with-exists");
        assertThat(stopped.attempts()).hasSize(1);
        assertThat(stopped.notRunAttempts()).isEqualTo(4);
        assertThat(stopped.attempts().get(0).evidence().expectedEvidenceCode())
                .isEqualTo(SegmentEvaluationRunner.EXPECTED_EVIDENCE_AST_INVALID);
        assertThat(stopped.attempts().get(0).taxonomy())
                .isEqualTo(FailureTaxonomy.INPUT_OR_PIN_INVALIDITY);

        // The grounded-explanation dimension is honest about the known corpus/T11 vocabulary gap: every executed
        // attempt that genuinely reached the accepted create success path is applicable and mismatches, while a
        // refinement attempt, a non-resolved verdict and an attempt whose expectation could not be frozen all
        // report inapplicability and never manufacture a mismatch from an absent claim list. The exact
        // deterministic count is 79: of the 80 executed attempts, the single attempt whose reviewed expectation
        // the genuine T07 refused (the stopping Case above) carries its bounded expected-evidence code and is
        // therefore not applicable. Neither the full Case accounting above nor the frozen development-only
        // classification is affected by that comparison.
        int applicable = 0;
        for (EvaluationReport.CaseOutcome outcome : report.cases()) {
            for (EvaluationReport.AttemptOutcome attempt : outcome.attempts()) {
                EvaluationReport.ComparisonEvidence evidence = attempt.evidence();
                if (evidence.expectedEvidenceCode() != null
                        || evidence.refinementAttempt()
                        || !Disposition.RESOLVED.name().equals(attempt.disposition())) {
                    assertThat(evidence.explanationApplicable()).isFalse();
                } else {
                    assertThat(evidence.explanationApplicable()).isTrue();
                    assertThat(evidence.explanationClaimsMatched()).isFalse();
                    applicable++;
                }
            }
        }
        assertThat(applicable).isEqualTo(79);

        // The frozen review classification stays development-only — it is independent of execution soundness —
        // while the unsound reviewed evidence makes the run itself INVALID.
        assertThat(report.contract().review().classification().name()).isEqualTo("DEVELOPMENT_ONLY");
        assertThat(report.contract().review().issueCodes()).isNotEmpty();
        assertThat(report.contract().review().reviewSetHash()).isNotNull();
        assertThat(report.evidenceClass()).isEqualTo(EvaluationReport.ReportEvidenceClass.INVALID);
        assertThat(report.evidenceClass().isDiagnosticOnly()).isTrue();
        assertThat(report.summary().runInvalid()).isTrue();
        assertThat(report.contract().candidateCorpus().caseCount()).isEqualTo(225);
        assertThat(report.contract().candidateCorpus().requiredHighRiskRepeats()).isEqualTo(5);

        // Every metric is reported separately, defined or explicitly undefined, and the one executed
        // run-invalidating attempt is counted in every metric's invalid field rather than in a denominator.
        assertThat(report.metrics()).hasSize(MetricId.values().length);
        assertThat(report.metrics()).allSatisfy(metric -> assertThat(metric.invalid()).isEqualTo(1));
        // The grouped breakdowns report the same complete metric set for every group they observed; because
        // execution stopped early, only the groups reached before the unsound Case are present.
        assertThat(report.perCategory()).isNotEmpty();
        assertThat(report.perCategory().values()).allSatisfy(values ->
                assertThat(values).hasSize(MetricId.values().length));
        assertThat(report.perRisk()).isNotEmpty();
        assertThat(report.perRisk().keySet()).isSubsetOf("HIGH", "MEDIUM", "LOW");
        assertThat(report.perRisk().values()).allSatisfy(values ->
                assertThat(values).hasSize(MetricId.values().length));
    }

    @Test
    void theFullCorpusRunIsByteDeterministic() {
        EvaluationReport first = runCorpus();
        EvaluationReport second = runCorpus();

        assertThat(first.contentHash()).isEqualTo(second.contentHash());
        assertThat(first.canonicalBytes()).isEqualTo(second.canonicalBytes());
    }

    @Test
    void theFullCorpusReportCarriesNoCorpusUtteranceOrMemberIdentifier() {
        EvaluationReport report = runCorpus();
        String serialized = new String(report.canonicalBytes(), StandardCharsets.UTF_8);

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
}