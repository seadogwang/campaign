package com.company.campaign.builder.compiler;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The explicit compile and expiry instants a host supplies for one compile.
 *
 * <p>The compiler never reads a clock: both instants are inputs, so an identical compile request produces
 * byte-identical Plan material on any machine at any wall-clock moment. Expiry must be strictly after the
 * compile instant and within {@link #MAX_LIFETIME}, so a Plan is always usable for a bounded window and a
 * non-expiring Plan is not representable.
 */
public record CompileInstants(Instant compiledAt, Instant expiresAt) {

    /** The longest lifetime a host may declare for one Plan. */
    public static final Duration MAX_LIFETIME = Duration.ofHours(1);

    public CompileInstants {
        Objects.requireNonNull(compiledAt, "compiledAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(compiledAt)) {
            throw new CompilerException(
                    CompilerErrorCodes.COMPILER_INSTANTS_INVALID,
                    "expiryNotAfterCompile",
                    "A compile expiry instant must be strictly after its compile instant.");
        }
        if (Duration.between(compiledAt, expiresAt).compareTo(MAX_LIFETIME) > 0) {
            throw new CompilerException(
                    CompilerErrorCodes.COMPILER_INSTANTS_INVALID,
                    "lifetimeBound",
                    "A compile expiry instant must lie within the bounded Plan lifetime.");
        }
    }

    @Override
    public String toString() {
        return "CompileInstants[compiledAt=" + compiledAt + ", expiresAt=" + expiresAt + ']';
    }
}