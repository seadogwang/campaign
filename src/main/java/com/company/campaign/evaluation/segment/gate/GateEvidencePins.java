package com.company.campaign.evaluation.segment.gate;

import com.company.campaign.evaluation.segment.runner.EvaluationReport;
import com.company.campaign.shared.canonical.ContentHash;

/**
 * The exact report, candidate-corpus and review pins the trusted host expects one Gate evaluation to cover.
 *
 * <p>The Gate never accepts a report merely because it names itself: the host states the report hash and the
 * frozen candidate-corpus and review-readiness pins it intends to judge, and the evaluator proves the report
 * carries exactly those bytes. A report whose classification was changed after the fact, or a report of a
 * different corpus revision, therefore fails with a pin mismatch instead of being silently relabelled.
 */
public record GateEvidencePins(
        ContentHash reportHash,
        ContentHash candidateCorpusHash,
        ContentHash candidateManifestHash,
        ContentHash reviewSetHash,
        ContentHash reviewManifestHash
) {

    public GateEvidencePins {
        if (reportHash == null || candidateCorpusHash == null || candidateManifestHash == null
                || reviewSetHash == null || reviewManifestHash == null) {
            throw GateException.of(
                    GateErrorCodes.REPORT_PIN_MISMATCH, "Gate evidence pins require every expected hash");
        }
    }

    /** The pins a report itself declares, for a host that judges exactly the loaded revision. */
    public static GateEvidencePins of(EvaluationReport report) {
        if (report == null) {
            throw GateException.of(
                    GateErrorCodes.REPORT_PIN_MISMATCH, "Gate evidence pins require a report");
        }
        return new GateEvidencePins(
                report.contentHash(),
                report.contract().candidateCorpus().corpusHash(),
                report.contract().candidateCorpus().manifestHash(),
                report.contract().review().reviewSetHash(),
                report.contract().review().manifestHash());
    }

    /** True only when the report's own canonical bytes hash to the expected report hash. */
    public boolean reportHashMatches(EvaluationReport report) {
        return report != null && this.reportHash.equals(report.contentHash());
    }

    /** True only when every frozen candidate-corpus and review pin matches the report contract. */
    public boolean pinsMatch(EvaluationReport report) {
        if (report == null) {
            return false;
        }
        return candidateCorpusHash.equals(report.contract().candidateCorpus().corpusHash())
                && candidateManifestHash.equals(report.contract().candidateCorpus().manifestHash())
                && reviewSetHash.equals(report.contract().review().reviewSetHash())
                && reviewManifestHash.equals(report.contract().review().manifestHash());
    }
}