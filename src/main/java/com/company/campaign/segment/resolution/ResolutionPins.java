package com.company.campaign.segment.resolution;

import com.company.campaign.shared.canonical.ContentHash;

/**
 * The exact inputs a resolution was produced from.
 *
 * <p>Every pin is an exact identity, never a "current" pointer: the permitted Metadata snapshot id, version
 * and content hash, the policy version and hash, and the registered-default registry revision. Two
 * resolutions are comparable and a clarification choice is applicable only when all pins are equal.
 */
public record ResolutionPins(
        String snapshotId,
        String snapshotVersion,
        ContentHash snapshotHash,
        String policyVersion,
        ContentHash policyHash,
        String registryVersion,
        ContentHash registryHash
) {

    public ResolutionPins {
        if (snapshotId == null || snapshotVersion == null || snapshotHash == null
                || policyVersion == null || policyHash == null
                || registryVersion == null || registryHash == null) {
            throw ResolutionException.invalid(
                    ResolutionErrorCodes.INPUT_MISSING, "Every resolution pin must be present");
        }
    }
}