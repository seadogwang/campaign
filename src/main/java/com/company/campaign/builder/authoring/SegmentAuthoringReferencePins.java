package com.company.campaign.builder.authoring;

import com.company.campaign.shared.canonical.ContentHash;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * The dedicated Existing Audience Reference readiness evidence of one Segment authoring compile.
 *
 * <p>It is present exactly when the Segment depends on immutable referenced Audience Versions, and absent
 * otherwise. That is deliberate: a reference-free compile carries no reference evidence at all, so its
 * Proposal, Manifest, Plan, card and envelope bytes stay exactly the accepted ones and the T18 extension
 * preserves no-reference behavior without versioning the plan format.
 *
 * <p>Three facts are frozen and nothing else:
 *
 * <ul>
 *   <li>{@link #referenceDependenciesHash()} — the canonical, duplicate-free hash of the exact referenced
 *       version dependencies the compile resolved, so any change to the reference graph moves it;</li>
 *   <li>{@link #referenceInputsHash()} — the hash of the dedicated reference Gate inputs this compile ran
 *       against, so a stale or mismatched reference readiness is observable rather than implicit;</li>
 *   <li>{@link #readinessState()} — the reference extension's own readiness, drawn from a closed set that
 *       deliberately has no production-ready member.</li>
 * </ul>
 *
 * <p><strong>Production reference activation is unavailable.</strong> The closed vocabulary here is
 * {@code EXPERIMENTAL} and {@code DEVELOPMENT_ONLY} only. There is no {@code ACTIVE} state, no
 * approved-decision hash and no field that could carry one, because no approved reference Gate decision
 * exists. A reference-bearing compile therefore cannot claim activation, and a later production path that
 * needs one must fail closed rather than read it from here.
 *
 * <p>These pins never carry an owner, a program, a DataScope, a permission, a dependency list, a version pin,
 * a member identifier or a Gate decision. They are hashes of host facts, and a model, a client or a capability
 * payload cannot supply, widen or replace them.
 */
public record SegmentAuthoringReferencePins(
        String capabilityCode,
        String readinessState,
        ContentHash referenceDependenciesHash,
        ContentHash referenceInputsHash
) {

    /** The development state: no approved reference Gate decision covers this extension yet. */
    public static final String EXPERIMENTAL = "EXPERIMENTAL";

    /** The evidence state of reference material produced without a real-model, human-reviewed run. */
    public static final String DEVELOPMENT_ONLY = "DEVELOPMENT_ONLY";

    /** The closed reference readiness vocabulary, which contains no production-ready member. */
    private static final Set<String> READINESS_STATES = Set.of(EXPERIMENTAL, DEVELOPMENT_ONLY);

    private static final Pattern CAPABILITY = Pattern.compile("^[a-z][a-z0-9.-]{0,63}$");

    public SegmentAuthoringReferencePins {
        if (capabilityCode == null || !CAPABILITY.matcher(capabilityCode).matches()) {
            throw new SegmentAuthoringException(
                    SegmentAuthoringErrorCodes.READINESS_INVALID,
                    "referencePinsCapability",
                    "Reference readiness pins must name a bounded capability identity.");
        }
        // The state is checked against the closed set, so ACTIVE is not representable here at all.
        if (readinessState == null || !READINESS_STATES.contains(readinessState)) {
            throw new SegmentAuthoringException(
                    SegmentAuthoringErrorCodes.READINESS_INVALID,
                    "referencePinsReadiness",
                    "Production reference activation is unavailable without an approved reference Gate decision.");
        }
        if (referenceDependenciesHash == null || referenceInputsHash == null) {
            throw new SegmentAuthoringException(
                    SegmentAuthoringErrorCodes.READINESS_INVALID,
                    "referencePinsHashes",
                    "Reference readiness pins require the canonical dependency hash and the reference inputs.");
        }
    }

    /** True only for the state in which reference material has no reviewed real-model evidence behind it. */
    public boolean developmentOnly() {
        return DEVELOPMENT_ONLY.equals(readinessState);
    }

    /**
     * True when these pins rest on this exact dependency graph and this exact reference input revision.
     *
     * <p>It is the convergence test the authoring path uses: two compiles of the same reference-aware Segment
     * must agree on both hashes, whatever source produced them.
     */
    public boolean matches(ContentHash dependenciesHash, ContentHash inputsHash) {
        return referenceDependenciesHash.equals(dependenciesHash) && referenceInputsHash.equals(inputsHash);
    }

    /** A bounded diagnostic: the state and the two hashes, never a referenced audience or version. */
    @Override
    public String toString() {
        return "SegmentAuthoringReferencePins[capabilityCode=" + capabilityCode
                + ", readinessState=" + readinessState
                + ", referenceDependenciesHash=" + referenceDependenciesHash
                + ", referenceInputsHash=" + referenceInputsHash
                + ']';
    }
}