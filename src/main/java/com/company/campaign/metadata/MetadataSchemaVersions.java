package com.company.campaign.metadata;

import com.company.campaign.contracts.SchemaVersion;

/**
 * The exact metadata schema version this build can read and publish.
 *
 * <p>There is one supported value. Any other declared version is rejected with
 * {@link MetadataErrorCodes#METADATA_SCHEMA_VERSION_UNSUPPORTED} rather than being coerced, so a snapshot
 * written by a different metadata contract cannot be silently interpreted with this one's rules.
 */
public final class MetadataSchemaVersions {

    /** T04 semantic metadata schema version {@code 1.0}. */
    public static final SchemaVersion SUPPORTED = new SchemaVersion(1, 0);

    private MetadataSchemaVersions() {
    }

    /** Rejects any version other than {@link #SUPPORTED} with the stable unsupported-version code. */
    public static void requireSupported(SchemaVersion version) {
        if (version == null || !SUPPORTED.equals(version)) {
            throw new MetadataException(
                    MetadataErrorCodes.METADATA_SCHEMA_VERSION_UNSUPPORTED,
                    "Unsupported metadata schema version");
        }
    }
}