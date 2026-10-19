package com.company.campaign.evaluation.readiness.t25;

import com.company.campaign.shared.canonical.ContentHash;

/**
 * The bounded T25 evaluation manifest: the frozen asset identity, the explicitly non-production-evidence
 * flag, the pinned cases path and hash, and the manifest's own self-hash.
 *
 * <p>{@code productionGateEvidence} is enforced false at construction, so a T25 asset can never be
 * relabelled as production Gate evidence and the readiness corpus can never itself satisfy a readiness
 * requirement.
 */
public record T25EvaluationManifest(
        String schemaVersion,
        String assetId,
        String assetVersion,
        boolean productionGateEvidence,
        String casesPath,
        ContentHash casesHash,
        int caseCount,
        ContentHash manifestHash) {

    public T25EvaluationManifest {
        if (!T25EvaluationLoader.SCHEMA_VERSION.equals(schemaVersion)
                || !T25EvaluationLoader.MANIFEST_ASSET_ID.equals(assetId)
                || !T25EvaluationLoader.ASSET_VERSION.equals(assetVersion)
                || productionGateEvidence
                || !T25EvaluationLoader.CASES_REFERENCE.equals(casesPath)
                || casesHash == null || manifestHash == null
                || caseCount < 1 || caseCount > T25EvaluationCases.MAX_CASES) {
            throw new T25EvaluationException(
                    T25EvaluationErrorCodes.CORPUS_INVALID,
                    "The T25 evaluation manifest is invalid.");
        }
    }
}