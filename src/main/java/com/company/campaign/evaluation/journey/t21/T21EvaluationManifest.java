package com.company.campaign.evaluation.journey.t21;

import com.company.campaign.shared.canonical.ContentHash;

/**
 * The bounded T21 evaluation manifest: the frozen asset identity, the explicitly non-production-evidence flag,
 * the pinned cases path and hash and the manifest's own self-hash.
 *
 * <p>{@code productionGateEvidence} is enforced false at construction, so development evaluation evidence can
 * never be relabelled as production Gate evidence by editing the asset.
 */
public record T21EvaluationManifest(
        String schemaVersion,
        String assetId,
        String assetVersion,
        boolean productionGateEvidence,
        String casesPath,
        ContentHash casesHash,
        int caseCount,
        ContentHash manifestHash) {

    public T21EvaluationManifest {
        if (!T21EvaluationLoader.SCHEMA_VERSION.equals(schemaVersion)
                || !T21EvaluationLoader.MANIFEST_ASSET_ID.equals(assetId)
                || !T21EvaluationLoader.ASSET_VERSION.equals(assetVersion)
                || productionGateEvidence
                || !T21EvaluationLoader.CASES_REFERENCE.equals(casesPath)
                || casesHash == null || manifestHash == null
                || caseCount < 1 || caseCount > T21EvaluationCases.MAX_CASES) {
            throw new T21EvaluationException(
                    T21EvaluationErrorCodes.CORPUS_INVALID,
                    "The T21 evaluation manifest is invalid.");
        }
    }
}