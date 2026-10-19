package com.company.campaign.evaluation.journey.t20;

import com.company.campaign.shared.canonical.ContentHash;

public record T20EvaluationManifest(
        String schemaVersion,
        String assetId,
        String assetVersion,
        boolean productionGateEvidence,
        String casesPath,
        ContentHash casesHash,
        int caseCount,
        ContentHash manifestHash) {

    public T20EvaluationManifest {
        if (!T20EvaluationLoader.SCHEMA_VERSION.equals(schemaVersion)
                || !T20EvaluationLoader.MANIFEST_ASSET_ID.equals(assetId)
                || !T20EvaluationLoader.ASSET_VERSION.equals(assetVersion)
                || productionGateEvidence
                || !T20EvaluationLoader.CASES_REFERENCE.equals(casesPath)
                || casesHash == null || manifestHash == null
                || caseCount < 1 || caseCount > T20EvaluationCases.MAX_CASES) {
            throw new T20EvaluationException(
                    T20EvaluationErrorCodes.CORPUS_INVALID,
                    "The T20 evaluation manifest is invalid.");
        }
    }
}
