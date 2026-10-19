package com.company.campaign.evaluation.segment.gate;

/**
 * The seven frozen subsystem versions of one Existing Audience Reference Gate input bundle.
 *
 * <p>They are the reference extension's own model-input and pipeline versions, deliberately distinct from the
 * base {@code segment.rule-based} capability's pins. Each is a version discriminator rather than
 * configuration: a decision that passed under one set of versions is a decision over exactly those versions,
 * so changing any of them produces a different input bundle and a different canonical current-input hash.
 *
 * <p>{@link #current()} is the honest default for the code that exists today. It is not an approval and does
 * not claim a production-ready reference: no approved reference Gate decision exists, so the readiness this
 * bundle feeds stays {@code EXPERIMENTAL}.
 */
public record ReferenceReadinessVersions(
        String resolverVersion,
        String validatorVersion,
        String canonicalizerVersion,
        String plannerVersion,
        String queryAstVersion,
        String sqlCompilerVersion,
        String fixtureExecutorVersion
) {

    public ReferenceReadinessVersions {
        if (resolverVersion == null || validatorVersion == null || canonicalizerVersion == null
                || plannerVersion == null || queryAstVersion == null || sqlCompilerVersion == null
                || fixtureExecutorVersion == null) {
            throw GateException.of(
                    GateErrorCodes.REFERENCE_INPUT_INVALID,
                    "Reference readiness versions require all seven frozen subsystem versions");
        }
    }

    /** The exact subsystem versions the current reference code declares. */
    public static ReferenceReadinessVersions current() {
        return new ReferenceReadinessVersions(
                "segment-intent-resolver/1.0",
                "segment-validator/1.0",
                "segment-canonicalizer/1.0",
                "segment-query-planner/1.0",
                "segment-query-ast/1.0",
                "segment-sql-compiler/1.0",
                "segment-fixture-executor/1.0");
    }
}