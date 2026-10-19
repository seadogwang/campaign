package com.company.campaign.localhealth;

import com.company.campaign.readiness.ReadinessLevel;
import com.company.campaign.readiness.ReadinessLimits;
import com.company.campaign.readiness.ReadinessStatus;

import java.util.List;

/**
 * The bounded readiness answer of the local health endpoint: a level, a status and machine codes only.
 *
 * <p>It carries no evidence record, no hash, no tenant, no identifier and no prose. {@code blockers} and
 * {@code evaluatedRequirements} are closed catalog codes, ordered and bounded, so the response is
 * low-cardinality and two identical environments produce byte-identical output.
 *
 * <p>{@code evaluatedRequirements} names exactly which readiness requirements this endpoint speaks for, so a
 * consumer cannot read its status as a whole-`LOCAL_READY` claim: the endpoint adjudicates the local storage
 * boundary only, and every other requirement stays an explicit blocker owned by the real evaluator.
 */
public record LocalReadiness(
        String level,
        String status,
        List<String> evaluatedRequirements,
        List<String> blockers) {

    private static final int MAX_CODES = ReadinessLimits.MAX_BLOCKER_CODES;

    public LocalReadiness {
        level = requireLevel(level);
        status = requireStatus(status);
        evaluatedRequirements = boundedCodes(evaluatedRequirements, "evaluatedRequirements");
        blockers = boundedCodes(blockers, "blockers");
    }

    private static String requireLevel(String level) {
        if (level == null || !level.equals(ReadinessLevel.LOCAL_READY.name())) {
            throw new IllegalArgumentException("The local health endpoint reports the LOCAL_READY level only.");
        }
        return level;
    }

    private static String requireStatus(String status) {
        if (status == null) {
            throw new IllegalArgumentException("A local health status is required.");
        }
        for (ReadinessStatus candidate : ReadinessStatus.values()) {
            if (candidate.name().equals(status)) {
                return status;
            }
        }
        throw new IllegalArgumentException("A local health status must be a readiness status.");
    }

    private static List<String> boundedCodes(List<String> codes, String field) {
        List<String> values = codes == null ? List.of() : List.copyOf(codes);
        if (values.size() > MAX_CODES) {
            throw new IllegalArgumentException("A local health " + field + " list exceeds its bound.");
        }
        for (String code : values) {
            if (code == null || code.isBlank() || code.length() > ReadinessLimits.MAX_CODE_LENGTH) {
                throw new IllegalArgumentException("A local health code is not a bounded machine code.");
            }
        }
        return values;
    }
}