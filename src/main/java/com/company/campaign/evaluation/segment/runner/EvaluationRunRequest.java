package com.company.campaign.evaluation.segment.runner;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.evaluation.segment.contract.EvaluationAssetPatterns;

/**
 * The explicit host request that starts one frozen T13 run.
 *
 * <p>Everything a run is not allowed to read implicitly is an explicit field here: the code revision and the
 * environment identifier are supplied rather than read from the ambient Git state or the machine, the model
 * identity and the decoding parameters are supplied rather than taken from a provider default, and the corpus
 * is named by a repository-relative manifest reference rather than discovered by listing a directory.
 */
public record EvaluationRunRequest(
        String reportId,
        EvaluationRunContract.ModelPin model,
        DecodingParameters decoding,
        String codeRevision,
        String environment,
        String manifestReference
) {

    public EvaluationRunRequest {
        if (reportId == null || !reportId.matches(ContractPatterns.REFERENCE_ID)) {
            throw EvaluationRunException.of(
                    EvaluationRunErrorCodes.RUN_IDENTIFIER_INVALID, "A run request requires a bounded report id");
        }
        if (model == null || decoding == null) {
            throw EvaluationRunException.of(
                    EvaluationRunErrorCodes.PIN_MISSING, "A run request requires its model and decoding pins");
        }
        if (codeRevision == null || environment == null) {
            throw EvaluationRunException.of(
                    EvaluationRunErrorCodes.PIN_MISSING,
                    "A run request requires an explicit code revision and environment identifier");
        }
        if (manifestReference == null
                || !manifestReference.matches(EvaluationAssetPatterns.REPOSITORY_RELATIVE_JSON)) {
            throw EvaluationRunException.of(
                    EvaluationRunErrorCodes.PIN_MISSING,
                    "A run request requires a repository-relative corpus manifest reference");
        }
    }
}