package com.company.campaign.preview;

/**
 * The immutable, server-owned identity of one injected Preview execution backend.
 *
 * <p>It carries both the stable backend identifier and the exact backend version as one indivisible value, so a
 * backend can never report a self-consistent but arbitrary version: the application service captures this
 * identity through a bounded boundary and requires every published result to equal both values. Construction
 * rejects a null or blank member with the stable {@link PreviewErrorCodes#DEPENDENCY_MISSING} code, so an
 * identity that cannot name the backend never exists.
 *
 * @param id      the stable server-owned backend identifier
 * @param version the exact server-owned backend version discriminator
 */
public record PreviewBackendIdentity(String id, String version) {

    public PreviewBackendIdentity {
        id = requireText(id, "backendId");
        version = requireText(version, "backendVersion");
    }

    private static String requireText(String value, String invariant) {
        if (value == null || value.isBlank()) {
            throw PreviewException.of(PreviewErrorCodes.DEPENDENCY_MISSING, invariant);
        }
        return value;
    }
}