package com.company.campaign.evaluation.runtime.t22;

import com.company.campaign.shared.canonical.ContentHash;

/**
 * The bounded T22 evaluation manifest: the frozen asset identity, the explicitly non-production-evidence flag,
 * the pinned cases path and hash and the manifest's own self-hash.
 *
 * <p>{@code productionGateEvidence} is enforced false at construction, so development evaluation evidence can
 * never be relabelled as production Gate evidence by editing the asset.
 */
public record T22EvaluationManifest(
        String schemaVersion,
        String assetId,
        String assetVersion,
        boolean productionGateEvidence,
        String casesPath,
        ContentHash casesHash,
        int caseCount,
        ContentHash manifestHash) {

    public T22EvaluationManifest {
        if (!T22EvaluationLoader.SCHEMA_VERSION.equals(schemaVersion)
                || !T22EvaluationLoader.MANIFEST_ASSET_ID.equals(assetId)
                || !T22EvaluationLoader.ASSET_VERSION.equals(assetVersion)
                || productionGateEvidence
                || !T22EvaluationLoader.CASES_REFERENCE.equals(casesPath)
                || casesHash == null || manifestHash == null
                || caseCount < 1 || caseCount > T22EvaluationCases.MAX_CASES) {
            throw new T22EvaluationException(
                    T22EvaluationErrorCodes.CORPUS_INVALID,
                    "The T22 evaluation manifest is invalid.");
        }
    }
}