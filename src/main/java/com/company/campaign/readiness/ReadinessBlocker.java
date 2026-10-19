package com.company.campaign.readiness;

/**
 * One ordered reason a level is not {@link ReadinessStatus#READY}.
 *
 * <p>The order is deterministic and load-bearing: blockers are sorted by the requirement's catalog position
 * and then by blocker code, and {@link #ordinal()} records that position, so two evaluations of the same
 * scope at the same instant produce byte-identical blocker lists. {@link #code()} is always a bounded machine
 * code from {@link ReadinessBlockerCodes} or {@link ReadinessReasonCodes}, never prose.
 */
public record ReadinessBlocker(
        int ordinal,
        String code,
        ReadinessRequirement requirement,
        ReadinessLevel level) {

    public ReadinessBlocker {
        if (ordinal < 0 || ordinal >= ReadinessLimits.MAX_BLOCKERS) {
            throw new ReadinessException(
                    ReadinessErrorCodes.READINESS_ASSESSMENT_INVALID, "A readiness blocker ordinal is out of range.");
        }
        if (!ReadinessBlockerCodes.isKnown(code) && !ReadinessReasonCodes.isKnown(code)) {
            throw new ReadinessException(
                    ReadinessErrorCodes.READINESS_REASON_UNKNOWN,
                    "A readiness blocker code is not part of a closed catalog.");
        }
        ReadinessSupport.requireNonNull(requirement, "requirement");
        ReadinessSupport.requireNonNull(level, "level");
    }

    /** Package-private hash material. */
    BlockerMaterial material() {
        return new BlockerMaterial(ordinal, code, requirement.code(), level.name());
    }

    /** Package-private canonical hash material for one ordered blocker. */
    record BlockerMaterial(int ordinal, String code, String requirementCode, String level) {
    }
}