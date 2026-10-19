package com.company.campaign.contracts;

import java.util.Objects;

/**
 * Version compatibility gate for every contract that declares a {@code schemaVersion}.
 *
 * <p>Compatibility is exact equality. There is deliberately no "same major is compatible" or
 * "newer minor is compatible" rule: this system hashes and replays contract content, so a silently
 * accepted near-miss would produce documents that hash differently from the ones that were validated.
 */
public final class SchemaCompatibility {

    private SchemaCompatibility() {
    }

    /** Requires the actual version to equal the expected version exactly. */
    public static void requireExact(SchemaVersion expected, SchemaVersion actual) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(actual, "actual");
        if (!expected.equals(actual)) {
            throw new SchemaCompatibilityException(expected, actual);
        }
    }

    /** Requires the actual version to be one of {@link ContractVersions#SUPPORTED}. */
    public static void requireSupported(SchemaVersion actual) {
        Objects.requireNonNull(actual, "actual");
        if (!ContractVersions.isSupported(actual)) {
            throw new SchemaCompatibilityException(ContractVersions.CURRENT, actual);
        }
    }

    /** Requires the actual version to be the exact current version. */
    public static void requireCurrent(SchemaVersion actual) {
        requireExact(ContractVersions.CURRENT, actual);
    }
}