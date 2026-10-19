package com.company.campaign.evaluation.action.t23;

import com.company.campaign.shared.canonical.ContentHash;

/**
 * The bounded T23 evaluation manifest: the frozen asset identity, the explicitly non-production-evidence flag,
 * the pinned cases path and hash and the manifest's own self-hash.
 *
 * <p>{@code productionGateEvidence} is enforced false at construction, so development evaluation evidence can
 * never be relabelled as production Gate evidence by editing the asset, and a simulated Provider can never be
 * presented as production reliability evidence.
 */
public record T23EvaluationManifest(
        String schemaVersion,
        String assetId,
        String assetVersion,
        boolean productionGateEvidence,
        String casesPath,
        ContentHash casesHash,
        int caseCount,
        ContentHash manifestHash) {

    public T23EvaluationManifest {
        if (!T23EvaluationLoader.SCHEMA_VERSION.equals(schemaVersion)
                || !T23EvaluationLoader.MANIFEST_ASSET_ID.equals(assetId)
                || !T23EvaluationLoader.ASSET_VERSION.equals(assetVersion)
                || productionGateEvidence
                || !T23EvaluationLoader.CASES_REFERENCE.equals(casesPath)
                || casesHash == null || manifestHash == null
                || caseCount < 1 || caseCount > T23EvaluationCases.MAX_CASES) {
            throw new T23EvaluationException(
                    T23EvaluationErrorCodes.CORPUS_INVALID,
                    "The T23 evaluation manifest is invalid.");
        }
    }
}