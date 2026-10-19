package com.company.campaign.evaluation.segment.gate;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.evaluation.segment.runner.EvaluationReport;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * The trusted host input of one Gate evaluation.
 *
 * <p>Everything the Gate consumes is explicit here: the exact frozen T13 report, the expected report/corpus/
 * review pins, the complete deterministic-layer evidence artifact, the bounded decision id and the decision
 * timestamp. Nothing is read from a wall clock, the default timezone, a random source, a provider, a model, a
 * repository or the ambient environment, and the request is immutable so an evaluated report cannot be swapped
 * afterwards.
 */
public record GateEvaluationRequest(
        String decisionId,
        EvaluationReport report,
        GateEvidencePins evidencePins,
        DeterministicLayerEvidence deterministicEvidence,
        Instant decidedAt
) {

    private static final Pattern REFERENCE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public GateEvaluationRequest {
        if (decisionId == null || !REFERENCE_ID.matcher(decisionId).matches()) {
            throw GateException.of(
                    GateErrorCodes.APPROVAL_INVALID, "A Gate evaluation requires a bounded decision id");
        }
        if (report == null || evidencePins == null || deterministicEvidence == null || decidedAt == null) {
            throw GateException.of(
                    GateErrorCodes.APPROVAL_INVALID,
                    "A Gate evaluation requires the frozen report, pins, deterministic evidence and time");
        }
    }
}