package com.company.campaign.readiness;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The closed set of reason codes an evidence record may declare when it is {@link EvidenceStatus#BLOCKED}
 * or {@link EvidenceStatus#INVALID}.
 *
 * <p>An evidence record that says "this requirement is not met" must say why, using exactly one of these
 * codes. Free prose is unrepresentable, so an operator-facing blocker is always a bounded machine code and
 * never a message, a stack trace or a Provider payload.
 */
public final class ReadinessReasonCodes {

    // ---------------------------------------------------------------- external infrastructure absent

    /** No real PostgreSQL instance was exercised, so PostgreSQL integration is not proved. */
    public static final String POSTGRESQL_NOT_EXERCISED = "READINESS_POSTGRESQL_NOT_EXERCISED";

    /** Ordered migrations were not applied to a real database engine. */
    public static final String MIGRATIONS_NOT_APPLIED = "READINESS_MIGRATIONS_NOT_APPLIED";

    /** No narrow API end-to-end run was performed. */
    public static final String API_END_TO_END_ABSENT = "READINESS_API_END_TO_END_ABSENT";

    /** No real browser end-to-end run was performed. */
    public static final String BROWSER_END_TO_END_ABSENT = "READINESS_BROWSER_END_TO_END_ABSENT";

    /** The deterministic backend contract/security/compiler/query/runtime suite was not executed. */
    public static final String DETERMINISTIC_BACKEND_TESTS_ABSENT = "READINESS_DETERMINISTIC_BACKEND_TESTS_ABSENT";

    /** The frontend typecheck, deterministic component tests and production build were not executed. */
    public static final String FRONTEND_BUILD_ABSENT = "READINESS_FRONTEND_BUILD_ABSENT";

    /** Concurrency, stale-revision, idempotency, timeout, query-cost and rollback coverage is incomplete. */
    public static final String CONCURRENCY_COVERAGE_ABSENT = "READINESS_CONCURRENCY_COVERAGE_ABSENT";

    /** Audit redaction/retention and observability coverage is incomplete. */
    public static final String AUDIT_OBSERVABILITY_ABSENT = "READINESS_AUDIT_OBSERVABILITY_ABSENT";

    /** Runtime availability isolation from Builder/LLM services is not proved. */
    public static final String RUNTIME_ISOLATION_ABSENT = "READINESS_RUNTIME_ISOLATION_ABSENT";

    // ---------------------------------------------------------------- Provider

    /** A simulated Provider capability is not admissible as production Provider reliability evidence. */
    public static final String PROVIDER_NOT_PRODUCTION = "READINESS_PROVIDER_NOT_PRODUCTION";

    /** A Provider capability does not have both business-key idempotency and reliable query by key. */
    public static final String PROVIDER_CAPABILITY_INCOMPLETE = "READINESS_PROVIDER_CAPABILITY_INCOMPLETE";

    /** No simulated Provider failure-mode coverage was recorded. */
    public static final String SIMULATED_PROVIDER_FAILURE_MODES_ABSENT =
            "READINESS_SIMULATED_PROVIDER_FAILURE_MODES_ABSENT";

    /**
     * A production-capable Provider capability alone is not live evidence: no current, successful live
     * verification of the exact configuration exists.
     */
    public static final String PROVIDER_EVIDENCE_ABSENT = "READINESS_PROVIDER_EVIDENCE_ABSENT";

    /** The exact production Provider configuration was exercised live and failed. */
    public static final String PROVIDER_LIVE_VERIFICATION_FAILED =
            "READINESS_PROVIDER_LIVE_VERIFICATION_FAILED";

    /** The production Provider was live-verified, but the evidence describes a non-production scope. */
    public static final String PROVIDER_EVIDENCE_NOT_PRODUCTION =
            "READINESS_PROVIDER_EVIDENCE_NOT_PRODUCTION";

    /** The production Provider live verification is no longer current at the observation instant. */
    public static final String PROVIDER_EVIDENCE_EXPIRED = "READINESS_PROVIDER_EVIDENCE_EXPIRED";

    // ---------------------------------------------------------------- Segment Gate (AI)

    /** No approved Segment AI Gate decision exists for the capability. */
    public static final String AI_GATE_APPROVAL_MISSING = "READINESS_AI_GATE_APPROVAL_MISSING";

    /** The latest Gate decision exists but its approval evidence is not structurally valid. */
    public static final String AI_GATE_NOT_APPROVED = "READINESS_AI_GATE_NOT_APPROVED";

    /** The latest Gate outcome is FAILED. */
    public static final String AI_GATE_OUTCOME_FAILED = "READINESS_AI_GATE_OUTCOME_FAILED";

    /** The latest Gate outcome is INVALID. */
    public static final String AI_GATE_OUTCOME_INVALID = "READINESS_AI_GATE_OUTCOME_INVALID";

    /** The Gate rested on a corpus that is not eligible for a Gate. */
    public static final String AI_GATE_DEVELOPMENT_ONLY = "READINESS_AI_GATE_DEVELOPMENT_ONLY";

    /** A gated model/Prompt/Skill/Tool/Metadata/Corpus pin changed since the approved decision. */
    public static final String AI_GATED_PINS_STALE = "READINESS_AI_GATED_PINS_STALE";

    /** A gated pin changed, so evaluation is required again before the capability may be relied on. */
    public static final String AI_GATED_PIN_CHANGE_REQUIRES_EVALUATION =
            "READINESS_AI_GATED_PIN_CHANGE_REQUIRES_EVALUATION";

    /** No current frozen gated-pin bundle was available to compare against. */
    public static final String AI_GATED_PINS_ABSENT = "READINESS_AI_GATED_PINS_ABSENT";

    // ---------------------------------------------------------------- Segment Authoring Integration

    /** No accepted Segment Authoring Integration evidence exists. */
    public static final String AUTHORING_INTEGRATION_ABSENT = "READINESS_AUTHORING_INTEGRATION_ABSENT";

    /** The authoring integration evidence does not match the accepted version/hash. */
    public static final String AUTHORING_INTEGRATION_MISMATCH = "READINESS_AUTHORING_INTEGRATION_MISMATCH";

    /** The authoring integration evidence is development-only. */
    public static final String AUTHORING_INTEGRATION_DEVELOPMENT_ONLY =
            "READINESS_AUTHORING_INTEGRATION_DEVELOPMENT_ONLY";

    // ---------------------------------------------------------------- Schedule, Runtime and UI

    /** Accepted deterministic schedule coverage exists but no live schedule behaviour was observed. */
    public static final String SCHEDULE_COVERAGE_LOCAL_ONLY = "READINESS_SCHEDULE_COVERAGE_LOCAL_ONLY";

    /** The host runtime availability answer was absent, thrown or explicitly unavailable. */
    public static final String RUNTIME_AVAILABILITY_UNAVAILABLE = "READINESS_RUNTIME_AVAILABILITY_UNAVAILABLE";

    /** A T24 UI evaluation asset is never production Gate evidence and cannot prove browser E2E or LIVE. */
    public static final String UI_ASSETS_NOT_PRODUCTION_EVIDENCE =
            "READINESS_UI_ASSETS_NOT_PRODUCTION_EVIDENCE";

    // ---------------------------------------------------------------- production structured model

    /**
     * Only a configuration, a scripted server or a fixture describes the production model, so no live
     * verification of the exact configuration exists.
     */
    public static final String MODEL_EVIDENCE_ABSENT = "READINESS_MODEL_EVIDENCE_ABSENT";

    /** The exact production model configuration was exercised live and failed. */
    public static final String MODEL_LIVE_VERIFICATION_FAILED = "READINESS_MODEL_LIVE_VERIFICATION_FAILED";

    /** The production model was live-verified, but the evidence describes a non-production scope. */
    public static final String MODEL_EVIDENCE_NOT_PRODUCTION = "READINESS_MODEL_EVIDENCE_NOT_PRODUCTION";

    // ---------------------------------------------------------------- production-only

    /** No production-capable evidence exists for this requirement. */
    public static final String PRODUCTION_EVIDENCE_ABSENT = "READINESS_PRODUCTION_EVIDENCE_ABSENT";

    /** A production-capable run has not been performed for this requirement. */
    public static final String PRODUCTION_RUN_NOT_PERFORMED = "READINESS_PRODUCTION_RUN_NOT_PERFORMED";

    private static final Set<String> ALL;

    static {
        Set<String> codes = new LinkedHashSet<>();
        codes.add(POSTGRESQL_NOT_EXERCISED);
        codes.add(MIGRATIONS_NOT_APPLIED);
        codes.add(API_END_TO_END_ABSENT);
        codes.add(BROWSER_END_TO_END_ABSENT);
        codes.add(DETERMINISTIC_BACKEND_TESTS_ABSENT);
        codes.add(FRONTEND_BUILD_ABSENT);
        codes.add(CONCURRENCY_COVERAGE_ABSENT);
        codes.add(AUDIT_OBSERVABILITY_ABSENT);
        codes.add(RUNTIME_ISOLATION_ABSENT);
        codes.add(PROVIDER_NOT_PRODUCTION);
        codes.add(PROVIDER_CAPABILITY_INCOMPLETE);
        codes.add(SIMULATED_PROVIDER_FAILURE_MODES_ABSENT);
        codes.add(PROVIDER_EVIDENCE_ABSENT);
        codes.add(PROVIDER_LIVE_VERIFICATION_FAILED);
        codes.add(PROVIDER_EVIDENCE_NOT_PRODUCTION);
        codes.add(PROVIDER_EVIDENCE_EXPIRED);
        codes.add(AI_GATE_APPROVAL_MISSING);
        codes.add(AI_GATE_NOT_APPROVED);
        codes.add(AI_GATE_OUTCOME_FAILED);
        codes.add(AI_GATE_OUTCOME_INVALID);
        codes.add(AI_GATE_DEVELOPMENT_ONLY);
        codes.add(AI_GATED_PINS_STALE);
        codes.add(AI_GATED_PIN_CHANGE_REQUIRES_EVALUATION);
        codes.add(AI_GATED_PINS_ABSENT);
        codes.add(AUTHORING_INTEGRATION_ABSENT);
        codes.add(AUTHORING_INTEGRATION_MISMATCH);
        codes.add(AUTHORING_INTEGRATION_DEVELOPMENT_ONLY);
        codes.add(SCHEDULE_COVERAGE_LOCAL_ONLY);
        codes.add(RUNTIME_AVAILABILITY_UNAVAILABLE);
        codes.add(UI_ASSETS_NOT_PRODUCTION_EVIDENCE);
        codes.add(MODEL_EVIDENCE_ABSENT);
        codes.add(MODEL_LIVE_VERIFICATION_FAILED);
        codes.add(MODEL_EVIDENCE_NOT_PRODUCTION);
        codes.add(PRODUCTION_EVIDENCE_ABSENT);
        codes.add(PRODUCTION_RUN_NOT_PERFORMED);
        ALL = Set.copyOf(codes);
    }

    private ReadinessReasonCodes() {
    }

    /** True when the given code is part of the closed reason catalog. */
    public static boolean isKnown(String code) {
        return code != null && ALL.contains(code);
    }

    /** The closed set of evidence reason codes. */
    public static Set<String> all() {
        return ALL;
    }
}