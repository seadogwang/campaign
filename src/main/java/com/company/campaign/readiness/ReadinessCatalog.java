package com.company.campaign.readiness;

import java.util.ArrayList;
import java.util.List;

/**
 * The one place a readiness level is expanded into the exact requirements it demands.
 *
 * <p>Nothing here is written per level: {@link #requirementsFor(ReadinessLevel)} collects every
 * {@link ReadinessRequirement} whose introduction level is that level or lower, so
 * {@code AI_READY} structurally includes every {@code LOCAL_READY} requirement and {@code LIVE_READY}
 * includes every {@code LOCAL_READY} and {@code AI_READY} requirement. A level therefore cannot be assessed
 * while skipping a lower one.
 *
 * <p>The catalog order is the {@link ReadinessRequirement} declaration order, which is also the order of the
 * blockers an assessment reports — the reason two evaluations of the same evidence are byte-identical.
 */
public final class ReadinessCatalog {

    /** The version of the closed requirement catalog. */
    public static final String VERSION = ReadinessVersions.CATALOG_VERSION;

    private static final List<ReadinessRequirement> ALL = List.of(ReadinessRequirement.values());

    private ReadinessCatalog() {
    }

    /** Every requirement in the catalog, in declaration order. */
    public static List<ReadinessRequirement> all() {
        return ALL;
    }

    /** Every requirement that reaching the given level demands, in catalog order. */
    public static List<ReadinessRequirement> requirementsFor(ReadinessLevel level) {
        ReadinessSupport.requireNonNull(level, "level");
        List<ReadinessRequirement> required = new ArrayList<>();
        for (ReadinessRequirement requirement : ALL) {
            if (requirement.requiredAt(level)) {
                required.add(requirement);
            }
        }
        return List.copyOf(required);
    }

    /** Every requirement introduced exactly at the given level, in catalog order. */
    public static List<ReadinessRequirement> introducedAt(ReadinessLevel level) {
        ReadinessSupport.requireNonNull(level, "level");
        return ALL.stream().filter(requirement -> requirement.level() == level).toList();
    }

    /** Resolves a bounded requirement code, rejecting anything outside the closed catalog. */
    public static ReadinessRequirement require(String code) {
        return ReadinessRequirement.fromCode(code);
    }

    /** True when the given string names a requirement in the closed catalog. */
    public static boolean isKnownCode(String code) {
        if (code == null) {
            return false;
        }
        for (ReadinessRequirement requirement : ALL) {
            if (requirement.code().equals(code)) {
                return true;
            }
        }
        return false;
    }

    /** The deterministic catalog position of a requirement. */
    public static int ordinalOf(ReadinessRequirement requirement) {
        ReadinessSupport.requireNonNull(requirement, "requirement");
        return requirement.ordinal();
    }
}