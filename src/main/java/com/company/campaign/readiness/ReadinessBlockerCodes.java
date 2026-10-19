package com.company.campaign.readiness;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The closed set of blocker codes the evaluator itself derives.
 *
 * <p>Every blocker an {@link ReadinessAssessment} reports is either one of these codes or one of the
 * {@link ReadinessReasonCodes} an evidence record declared. Both are closed sets of bounded machine codes,
 * which is what makes "counts of blockers by stable code" a bounded metric label rather than a
 * high-cardinality one.
 */
public final class ReadinessBlockerCodes {

    /** No evidence at all was recorded for a required requirement. */
    public static final String REQUIREMENT_MISSING = "READINESS_REQUIREMENT_MISSING";

    /** The evidence for a requirement is past its declared expiry instant. */
    public static final String EVIDENCE_EXPIRED = "READINESS_EVIDENCE_EXPIRED";

    /** The evidence was observed after the assessment instant it claims to describe. */
    public static final String EVIDENCE_FUTURE_OBSERVED = "READINESS_EVIDENCE_FUTURE_OBSERVED";

    /** The evidence origin is not admissible for the requirement it claims. */
    public static final String EVIDENCE_WRONG_ORIGIN = "READINESS_EVIDENCE_WRONG_ORIGIN";

    /** A production-only requirement received evidence that is not production-capable and explicitly stamped. */
    public static final String EVIDENCE_NOT_PRODUCTION = "READINESS_EVIDENCE_NOT_PRODUCTION";

    /** An evidence record claims production-capable status while its origin or environment contradicts it. */
    public static final String EVIDENCE_ORIGIN_MISMATCH = "READINESS_EVIDENCE_ORIGIN_MISMATCH";

    /** More than one distinct evidence record exists for one requirement in one scope. */
    public static final String EVIDENCE_DUPLICATE = "READINESS_EVIDENCE_DUPLICATE";

    /** The evidence was recorded in an environment other than the scope being assessed. */
    public static final String EVIDENCE_WRONG_ENVIRONMENT = "READINESS_EVIDENCE_WRONG_ENVIRONMENT";

    /** An evidence record carries a status the evaluator cannot interpret as a claim. */
    public static final String EVIDENCE_STATUS_UNUSABLE = "READINESS_EVIDENCE_STATUS_UNUSABLE";

    private static final Set<String> ALL;

    static {
        Set<String> codes = new LinkedHashSet<>();
        codes.add(REQUIREMENT_MISSING);
        codes.add(EVIDENCE_EXPIRED);
        codes.add(EVIDENCE_FUTURE_OBSERVED);
        codes.add(EVIDENCE_WRONG_ORIGIN);
        codes.add(EVIDENCE_NOT_PRODUCTION);
        codes.add(EVIDENCE_ORIGIN_MISMATCH);
        codes.add(EVIDENCE_DUPLICATE);
        codes.add(EVIDENCE_WRONG_ENVIRONMENT);
        codes.add(EVIDENCE_STATUS_UNUSABLE);
        ALL = Set.copyOf(codes);
    }

    private ReadinessBlockerCodes() {
    }

    /** True when the given code is an evaluator-derived blocker code. */
    public static boolean isKnown(String code) {
        return code != null && ALL.contains(code);
    }

    /** The closed set of evaluator-derived blocker codes. */
    public static Set<String> all() {
        return ALL;
    }
}