package com.company.campaign.builder.authoring;

import com.company.campaign.shared.canonical.ContentHash;

/**
 * The one adapter from host-owned reference-readiness inputs to the card's dedicated reference pins.
 *
 * <p>It is the reference counterpart of {@link SegmentAuthoringReadiness}, and it is deliberately narrower.
 * The base capability adapter faithfully restates a Gate projection that can legitimately be {@code ACTIVE};
 * this one cannot, because <strong>no approved Existing Audience Reference Gate decision exists</strong>. The
 * extension may be authored and previewed in development, but every production Prepare/Publish/Runtime path
 * containing a reference must fail closed until a genuine approved {@code PASSED} decision over the dedicated
 * reference corpus, deterministic inputs and model inputs does. Rather than accept an activation claim it
 * cannot verify, this adapter has no parameter for one: the state it accepts is restricted to the two
 * development states, and {@code ACTIVE} is refused as the malformed input it would be.
 *
 * <p>Nothing is invented here. The reference-dependency hash and the reference-inputs hash are host facts
 * supplied by the boundary that resolved the plan's references; the adapter only binds them to the dedicated
 * reference capability identity and refuses anything that would overstate readiness.
 */
public final class SegmentAuthoringReferenceReadiness {

    private SegmentAuthoringReferenceReadiness() {
    }

    /**
     * Builds the dedicated reference pins of one compile from host-owned reference-readiness inputs.
     *
     * @param referenceDependenciesHash the canonical hash of the plan's referenced version dependencies
     * @param referenceInputsHash       the hash of the dedicated reference Gate inputs the compile ran against
     * @param readinessState            the reference readiness; only a development state is accepted
     * @throws SegmentAuthoringException when the state is not a development state, or a hash is missing
     */
    public static SegmentAuthoringReferencePins pins(
            ContentHash referenceDependenciesHash, ContentHash referenceInputsHash, String readinessState) {
        if (referenceDependenciesHash == null || referenceInputsHash == null) {
            throw new SegmentAuthoringException(
                    SegmentAuthoringErrorCodes.READINESS_INVALID,
                    "referenceReadinessHashes",
                    "A reference compile requires the canonical dependency hash and its reference input pins.");
        }
        if (!SegmentAuthoringReferencePins.EXPERIMENTAL.equals(readinessState)
                && !SegmentAuthoringReferencePins.DEVELOPMENT_ONLY.equals(readinessState)) {
            // ACTIVE is refused here, and so is anything else: production reference activation is unavailable
            // until a real approved reference Gate decision exists, and this adapter will not manufacture one.
            throw new SegmentAuthoringException(
                    SegmentAuthoringErrorCodes.READINESS_INVALID,
                    "referenceReadinessState",
                    "Production reference activation is unavailable without an approved reference Gate decision.");
        }
        return new SegmentAuthoringReferencePins(
                SegmentAuthoringVersions.REFERENCE_CAPABILITY_CODE,
                readinessState,
                referenceDependenciesHash,
                referenceInputsHash);
    }

    /** Builds the development-only reference pins of material produced without a reviewed real-model run. */
    public static SegmentAuthoringReferencePins developmentPins(
            ContentHash referenceDependenciesHash, ContentHash referenceInputsHash) {
        return pins(
                referenceDependenciesHash, referenceInputsHash,
                SegmentAuthoringVersions.REFERENCE_DEVELOPMENT_ONLY);
    }
}