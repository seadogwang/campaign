package com.company.campaign.evaluation.segment.contract;

import com.company.campaign.contracts.ContractVersions;
import com.company.campaign.contracts.SchemaCompatibility;
import com.company.campaign.contracts.SchemaVersion;

/**
 * Schema versions accepted for evaluation assets.
 *
 * <p>Evaluation assets are versioned on the same version line as the base contracts, so a case file and
 * the contract it is read by cannot drift apart. Compatibility is exact equality through
 * {@link SchemaCompatibility}: a newer version is not "close enough" because these assets are hashed and
 * replayed, and a partially understood file would hash differently from the file that was reviewed.
 */
public final class EvaluationVersions {

    /** Version stamped onto every evaluation asset produced by this build. */
    public static final SchemaVersion CURRENT = ContractVersions.V1_3;

    private EvaluationVersions() {
    }

    /** Requires the asset's declared version to be one this build understands. */
    public static void requireSupported(SchemaVersion declared) {
        SchemaCompatibility.requireSupported(declared);
    }
}