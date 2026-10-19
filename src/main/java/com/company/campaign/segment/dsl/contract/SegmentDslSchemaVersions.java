package com.company.campaign.segment.dsl.contract;

import com.company.campaign.contracts.ContractVersions;
import com.company.campaign.contracts.SchemaVersion;

import java.util.Objects;

/**
 * The exact Segment DSL schema version this build can read and write.
 *
 * <p>There is one supported value, {@code 1.3}, taken from the T01 contract versions. Any other declared
 * version — including a malformed one and a newer one — is rejected with
 * {@link SegmentDslErrorCodes#SCHEMA_VERSION_UNSUPPORTED} rather than coerced, so a document written by a
 * different DSL contract cannot be silently interpreted with this one's rules.
 */
public final class SegmentDslSchemaVersions {

    /** T05 Segment DSL schema version {@code 1.3}. */
    public static final SchemaVersion SUPPORTED = ContractVersions.V1_3;

    private SegmentDslSchemaVersions() {
    }

    /** True when the version is exactly {@link #SUPPORTED}. */
    public static boolean isSupported(SchemaVersion version) {
        return Objects.equals(SUPPORTED, version);
    }

    /** Rejects any version other than {@link #SUPPORTED} with the stable unsupported-version code. */
    public static void requireSupported(SchemaVersion version) {
        if (!isSupported(version)) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.SCHEMA_VERSION_UNSUPPORTED,
                    "Unsupported Segment DSL schema version");
        }
    }

    /** Parses wire text and rejects anything that is not exactly {@link #SUPPORTED}. */
    public static SchemaVersion requireSupported(String wireVersion) {
        SchemaVersion parsed;
        try {
            parsed = SchemaVersion.parse(wireVersion);
        } catch (RuntimeException exception) {
            // The underlying failure carries the rejected text in its details, so it is not attached here.
            throw new SegmentDslException(
                    SegmentDslErrorCodes.SCHEMA_VERSION_UNSUPPORTED,
                    "Malformed Segment DSL schema version");
        }
        requireSupported(parsed);
        return parsed;
    }
}