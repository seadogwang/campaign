package com.company.campaign.builder.capability.catalog;

import com.company.campaign.builder.compiler.CompilerErrorCodes;
import com.company.campaign.builder.compiler.CompilerException;

/**
 * The one stable failure a frozen T19 catalog raises for a malformed, unbounded, duplicated or
 * hash-conflicting declaration.
 *
 * <p>A catalog is a frozen, host-owned input: it is read once, canonicalized and hash-bound, and every
 * later resolution is a pure lookup. Nothing here reads a file, a clock, a registry or a provider, and no
 * rejected value is ever echoed into the failure, because the failure may describe attacker-influenced
 * catalog content.
 */
final class CatalogSafety {

    private CatalogSafety() {
    }

    /** The stable failure for one closed, bounded, deterministic frozen-catalog invariant. */
    static CompilerException invalid(String invariant) {
        return new CompilerException(
                CompilerErrorCodes.COMPILER_FROZEN_INPUT_INVALID,
                invariant,
                "A frozen catalog must be a bounded, closed, deterministic, hash-bound declaration.");
    }
}