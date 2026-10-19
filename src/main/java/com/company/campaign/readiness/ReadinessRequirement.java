package com.company.campaign.readiness;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The one closed requirement catalog for the readiness slice.
 *
 * <p>Each constant declares the level at which the requirement is <em>introduced</em>, whether it is
 * production-only, and exactly which evidence origins are admissible for it. Everything else about the
 * catalog is derived: {@link ReadinessCatalog#requirementsFor(ReadinessLevel)} answers a level by collecting
 * every requirement whose introduction level is that level or lower, so a higher-level report structurally
 * cannot skip a lower-level requirement.
 *
 * <p>Two rules are encoded per constant rather than left to callers:
 * <ul>
 *   <li>a production-only requirement admits exactly {@link EvidenceOrigin#PRODUCTION}, so simulated,
 *       fixture, synthetic, development-only and evaluation-asset evidence is unrepresentable as its
 *       evidence rather than merely discouraged;</li>
 *   <li>the simulated Provider failure-mode requirement admits exactly {@link EvidenceOrigin#SIMULATED},
 *       so a real submission-capable Provider cannot be substituted for the fault-injection coverage that
 *       requirement actually asks for.</li>
 * </ul>
 *
 * <p>No constant admits {@link EvidenceOrigin#EVALUATION_ASSET}: the T25 corpus is regression evidence and
 * cannot itself satisfy any readiness requirement.
 */
public enum ReadinessRequirement {

    // ---------------------------------------------------------------- LOCAL

    LOCAL_POSTGRESQL_INTEGRATION(ReadinessLevel.LOCAL_READY, false,
            EvidenceOrigin.REAL_LOCAL, EvidenceOrigin.PRODUCTION),
    LOCAL_ORDERED_MIGRATIONS(ReadinessLevel.LOCAL_READY, false,
            EvidenceOrigin.REAL_LOCAL, EvidenceOrigin.PRODUCTION),
    LOCAL_DETERMINISTIC_BACKEND_TESTS(ReadinessLevel.LOCAL_READY, false,
            EvidenceOrigin.REAL_LOCAL, EvidenceOrigin.PRODUCTION),
    LOCAL_FRONTEND_TYPECHECK_TEST_BUILD(ReadinessLevel.LOCAL_READY, false,
            EvidenceOrigin.REAL_LOCAL, EvidenceOrigin.PRODUCTION),
    LOCAL_API_END_TO_END(ReadinessLevel.LOCAL_READY, false,
            EvidenceOrigin.REAL_LOCAL, EvidenceOrigin.PRODUCTION),
    LOCAL_BROWSER_END_TO_END(ReadinessLevel.LOCAL_READY, false,
            EvidenceOrigin.REAL_LOCAL, EvidenceOrigin.PRODUCTION),
    LOCAL_SIMULATED_PROVIDER_FAILURE_MODES(ReadinessLevel.LOCAL_READY, false,
            EvidenceOrigin.SIMULATED),
    LOCAL_CONCURRENCY_AND_LIMITS(ReadinessLevel.LOCAL_READY, false,
            EvidenceOrigin.REAL_LOCAL, EvidenceOrigin.PRODUCTION),
    LOCAL_AUDIT_AND_OBSERVABILITY(ReadinessLevel.LOCAL_READY, false,
            EvidenceOrigin.REAL_LOCAL, EvidenceOrigin.PRODUCTION),
    LOCAL_RUNTIME_ISOLATION(ReadinessLevel.LOCAL_READY, false,
            EvidenceOrigin.REAL_LOCAL, EvidenceOrigin.PRODUCTION),

    // ---------------------------------------------------------------- AI

    AI_SEGMENT_GATE_APPROVED_PASSED(ReadinessLevel.AI_READY, false,
            EvidenceOrigin.REAL_LOCAL, EvidenceOrigin.PRODUCTION),
    AI_GATED_PINS_CURRENT(ReadinessLevel.AI_READY, false,
            EvidenceOrigin.REAL_LOCAL, EvidenceOrigin.PRODUCTION),
    AI_SEGMENT_AUTHORING_INTEGRATION(ReadinessLevel.AI_READY, false,
            EvidenceOrigin.REAL_LOCAL, EvidenceOrigin.PRODUCTION),
    AI_GATED_PIN_CHANGE_REQUIRES_EVALUATION(ReadinessLevel.AI_READY, false,
            EvidenceOrigin.REAL_LOCAL, EvidenceOrigin.PRODUCTION),

    // ---------------------------------------------------------------- LIVE

    LIVE_PRODUCTION_AUTH_AND_DATASCOPE(ReadinessLevel.LIVE_READY, true, EvidenceOrigin.PRODUCTION),
    LIVE_SOURCE_FRESHNESS_SEMANTICS(ReadinessLevel.LIVE_READY, true, EvidenceOrigin.PRODUCTION),
    LIVE_PROVIDER_IDEMPOTENCY_AND_RECONCILIATION(ReadinessLevel.LIVE_READY, true, EvidenceOrigin.PRODUCTION),
    LIVE_SCHEDULE_SAFETY(ReadinessLevel.LIVE_READY, true, EvidenceOrigin.PRODUCTION),
    LIVE_OPERATIONAL_ALERTS(ReadinessLevel.LIVE_READY, true, EvidenceOrigin.PRODUCTION),
    LIVE_BACKUP_AND_RESTORE(ReadinessLevel.LIVE_READY, true, EvidenceOrigin.PRODUCTION),
    LIVE_OPERATIONAL_ROLLBACK(ReadinessLevel.LIVE_READY, true, EvidenceOrigin.PRODUCTION),
    LIVE_IMMUTABLE_PUBLICATION_AND_AUDIT(ReadinessLevel.LIVE_READY, true, EvidenceOrigin.PRODUCTION),
    LIVE_ACCEPTANCE_SCENARIOS_PRODUCTION_LIKE(ReadinessLevel.LIVE_READY, true, EvidenceOrigin.PRODUCTION),

    /**
     * T35B: the one bounded production structured model was exercised live and honoured its strict contract.
     *
     * <p>It admits exactly {@link EvidenceOrigin#PRODUCTION}, so a committed configuration, a deterministic
     * scripted HTTP server, a fixture or an evaluation asset is structurally unrepresentable as its evidence —
     * only an environment-gated live run of the exact configuration can satisfy it.
     */
    LIVE_PRODUCTION_STRUCTURED_MODEL(ReadinessLevel.LIVE_READY, true, EvidenceOrigin.PRODUCTION);

    /** The level at which this requirement is introduced; it is required at this level and every level above. */
    private final ReadinessLevel level;

    /** True when only explicit production-capable evidence may satisfy this requirement. */
    private final boolean productionOnly;

    /** The exact evidence origins admissible for this requirement. */
    private final Set<EvidenceOrigin> allowedOrigins;

    ReadinessRequirement(ReadinessLevel level, boolean productionOnly, EvidenceOrigin... allowedOrigins) {
        this.level = level;
        this.productionOnly = productionOnly;
        this.allowedOrigins = Set.copyOf(new LinkedHashSet<>(java.util.Arrays.asList(allowedOrigins)));
        if (productionOnly && (this.allowedOrigins.size() != 1
                || !this.allowedOrigins.contains(EvidenceOrigin.PRODUCTION))) {
            throw new IllegalStateException(
                    "A production-only readiness requirement must admit exactly the production origin");
        }
        if (this.allowedOrigins.isEmpty() || this.allowedOrigins.contains(EvidenceOrigin.EVALUATION_ASSET)) {
            throw new IllegalStateException(
                    "A readiness requirement must admit at least one admissible origin and no evaluation asset");
        }
    }

    /** The level at which this requirement is introduced. */
    public ReadinessLevel level() {
        return level;
    }

    /** True when only explicit production-capable evidence may satisfy this requirement. */
    public boolean productionOnly() {
        return productionOnly;
    }

    /** The exact evidence origins admissible for this requirement. */
    public Set<EvidenceOrigin> allowedOrigins() {
        return allowedOrigins;
    }

    /** True when the given origin is admissible for this requirement. */
    public boolean admits(EvidenceOrigin origin) {
        return origin != null && allowedOrigins.contains(origin);
    }

    /** True when this requirement is required to reach the given level. */
    public boolean requiredAt(ReadinessLevel other) {
        return other != null && other.includes(level);
    }

    /** The stable machine code of this requirement: its name. */
    public String code() {
        return name();
    }

    /** Resolves a bounded requirement code, rejecting anything outside the closed catalog. */
    public static ReadinessRequirement fromCode(String code) {
        if (code != null) {
            for (ReadinessRequirement requirement : values()) {
                if (requirement.name().equals(code)) {
                    return requirement;
                }
            }
        }
        throw new ReadinessException(
                ReadinessErrorCodes.READINESS_REQUIREMENT_UNKNOWN,
                "Readiness requirement code '" + code + "' is not part of the closed catalog.");
    }
}