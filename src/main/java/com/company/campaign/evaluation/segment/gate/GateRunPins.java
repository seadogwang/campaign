package com.company.campaign.evaluation.segment.gate;

import com.company.campaign.evaluation.segment.runner.DecodingParameters;
import com.company.campaign.evaluation.segment.runner.EvaluationCasePins;
import com.company.campaign.evaluation.segment.runner.EvaluationRunContract;
import com.company.campaign.evaluation.segment.runner.T13Versions;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.List;

/**
 * The complete frozen run and version pins copied from one T13 report.
 *
 * <p>It is the whole of "the gated inputs": the provider/model/model version, the typed decoding parameters,
 * the system-prompt/Skill/Tool-schema artifact pins, the parser/codec/resolver/canonicalizer/validator/planner
 * versions, the Metadata catalog pins, the candidate-corpus pins, the review-readiness pins, the frozen time
 * context, the code revision and environment, and the per-Case reviewed-asset and fixture pins.
 *
 * <p>The value is a copy of an immutable contract, so a decision can never be relabelled when the live
 * contract changes. Structural equality of two pin values is exact structural equality of every gated input,
 * which is what the readiness projection compares to detect an {@code EVALUATION_REQUIRED} condition.
 */
public record GateRunPins(
        String runContractVersion,
        String reportSchemaVersion,
        ContentHash runContractHash,
        EvaluationRunContract.ModelPin model,
        DecodingParameters decoding,
        EvaluationRunContract.ArtifactPins artifacts,
        EvaluationRunContract.ParserPins parsers,
        EvaluationRunContract.MetadataPins metadata,
        EvaluationRunContract.CandidateCorpusPins candidateCorpus,
        EvaluationRunContract.ReviewPins review,
        EvaluationRunContract.TimePins time,
        EvaluationRunContract.CodePins code,
        List<EvaluationCasePins> cases
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public GateRunPins {
        if (!T13Versions.RUN_CONTRACT_VERSION.equals(runContractVersion)
                || !T13Versions.REPORT_SCHEMA_VERSION.equals(reportSchemaVersion)) {
            throw GateException.of(
                    GateErrorCodes.REPORT_PIN_MISMATCH,
                    "Gate run pins must declare the exact accepted T13 contract versions");
        }
        if (runContractHash == null || model == null || decoding == null || artifacts == null || parsers == null
                || metadata == null || candidateCorpus == null || review == null || time == null || code == null) {
            throw GateException.of(
                    GateErrorCodes.REPORT_PIN_MISMATCH, "Gate run pins require every frozen pin group");
        }
        if (cases == null || cases.isEmpty()) {
            throw GateException.of(
                    GateErrorCodes.REPORT_PIN_MISMATCH, "Gate run pins require the frozen Case pins");
        }
        cases = List.copyOf(new ArrayList<>(cases));
    }

    /** Copies every pin from one accepted run contract, with the contract's own canonical hash. */
    public static GateRunPins from(EvaluationRunContract contract) {
        if (contract == null) {
            throw GateException.of(
                    GateErrorCodes.REPORT_PIN_MISMATCH, "Gate run pins require the frozen run contract");
        }
        return new GateRunPins(
                contract.runContractVersion(), contract.reportSchemaVersion(), contract.contentHash(),
                contract.model(), contract.decoding(), contract.artifacts(), contract.parsers(),
                contract.metadata(), contract.candidateCorpus(), contract.review(), contract.time(),
                contract.code(), contract.cases());
    }

    /** The canonical hash of every gated pin; identical pins produce identical bytes. */
    public ContentHash contentHash() {
        return CANONICAL_JSON.hash(this);
    }
}