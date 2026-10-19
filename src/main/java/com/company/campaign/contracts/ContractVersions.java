package com.company.campaign.contracts;

import java.util.Objects;
import java.util.Set;

/**
 * The contract schema versions this build understands.
 *
 * <p>Version {@code 1.3} is the schema version published by the current architecture baseline and is the
 * only version the implementation accepts. The set is explicit rather than "any version newer than X",
 * because a newer version is by definition not understood and must be refused instead of partially read.
 */
public final class ContractVersions {

    /** The v1.3 contract schema version. */
    public static final SchemaVersion V1_3 = new SchemaVersion(1, 3);

    /** Version stamped onto newly produced contracts. */
    public static final SchemaVersion CURRENT = V1_3;

    /** Every version this build is able to read and write. */
    public static final Set<SchemaVersion> SUPPORTED = Set.of(V1_3);

    private ContractVersions() {
    }

    public static boolean isSupported(SchemaVersion version) {
        return version != null && SUPPORTED.contains(version);
    }

    /** True when the version is the exact current version, which is the only accepted wire version. */
    public static boolean isCurrent(SchemaVersion version) {
        return Objects.equals(CURRENT, version);
    }
}