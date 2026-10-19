package com.company.campaign.fullpublish;

import com.company.campaign.builder.compiler.RiskSummary;
import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.lifecycle.LifecycleErrorCodes;
import com.company.campaign.lifecycle.LifecycleException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The bounded, shared mechanics the T21 full-publication records and boundaries use to canonicalize and to fail
 * closed.
 *
 * <p>It exists so a preparation, a definition and a receipt cannot drift apart on what a bounded identifier, a
 * canonical risk-code set or a bounded preparation lifetime is. Nothing here reads a clock, a random source, a
 * repository or an ambient value.
 */
final class FullPublishSupport {

    /** The largest number of risk codes one full publication may require a caller to acknowledge. */
    static final int MAX_RISK_CODES = RiskSummary.MAX_RISK_CODES;

    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);
    private static final Pattern MACHINE_CODE = Pattern.compile(ContractPatterns.MACHINE_CODE);

    private FullPublishSupport() {
    }

    /** Requires a bounded canonical identifier. */
    static String requireIdentifier(String value, String invariant) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw invalid(FullPublishErrorCodes.FULL_PREPARATION_INVALID, invariant);
        }
        return value;
    }

    /** True when a value is null or blank, for the structural command checks. */
    static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * The canonical risk-code set: bounded machine codes, sorted, duplicate-free and bounded in size.
     *
     * <p>It is a set rather than a list because acknowledging a risk is not an ordered act, and a repeated
     * acknowledgement would otherwise change the frozen material without changing what was acknowledged.
     */
    static List<String> canonicalRiskCodes(List<String> riskCodes, String invariant) {
        if (riskCodes == null || riskCodes.size() > MAX_RISK_CODES) {
            throw invalid(FullPublishErrorCodes.FULL_PREPARATION_INVALID, invariant);
        }
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        for (String code : riskCodes) {
            if (code == null || !MACHINE_CODE.matcher(code).matches()) {
                throw invalid(FullPublishErrorCodes.FULL_PREPARATION_INVALID, invariant);
            }
            distinct.add(code);
        }
        List<String> ordered = new ArrayList<>(distinct);
        ordered.sort(String::compareTo);
        return List.copyOf(ordered);
    }

    /** Requires a lifetime inside the frozen full-preparation bound. */
    static void requireBoundedLifetime(Instant createdAt, Instant expiresAt, String invariant) {
        Duration lifetime = Duration.between(createdAt, expiresAt);
        if (lifetime.compareTo(FullPublishVersions.MIN_PREPARATION_LIFETIME) < 0
                || lifetime.compareTo(FullPublishVersions.MAX_PREPARATION_LIFETIME) > 0) {
            throw invalid(FullPublishErrorCodes.FULL_PREPARATION_INVALID, invariant);
        }
    }

    /** The bounded lifecycle failure of one rejected full-publication artifact or command. */
    static LifecycleException invalid(String code, String invariant) {
        return new LifecycleException(code, invariant,
                "A full publication artifact must be a bounded, self-binding, hash-bound record.");
    }

    /** The bounded lifecycle failure of one drift between frozen evidence and the state it describes. */
    static LifecycleException stale(String invariant) {
        return new LifecycleException(
                LifecycleErrorCodes.LIFECYCLE_FULL_PREPARATION_STALE, invariant,
                "The full preparation's frozen evidence no longer matches the state it would publish.");
    }
}