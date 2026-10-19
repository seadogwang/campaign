package com.company.campaign.evaluation.segment.gate;

import com.company.campaign.evaluation.segment.contract.GateOutcome;

/**
 * The fail-closed guard over Existing Audience Reference activation.
 *
 * <p>It accepts exactly one thing: a genuine {@link ReferenceApprovedDecision} bound to the dedicated
 * reference capability whose decision and approval recompute to their declared hashes, agree on the verdict,
 * carry a complete PRODUCT/DATA/TEST owner quorum, are {@code PASSED} and pin exactly the current reference
 * inputs. Everything weaker fails closed.
 *
 * <p>A decision bound to another capability — for example the base {@code segment.rule-based} identity — is
 * refused as {@link GateErrorCodes#CAPABILITY_NOT_GATED}, because an approval of one Gate identity is never
 * an approval of the other. A pending, failed, stale, forged or otherwise malformed decision is refused with
 * its own bounded code. A reference-free path has no reference evidence to guard and never reaches here.
 */
public final class ReferenceActivationGuard {

    private ReferenceActivationGuard() {
    }

    /** True only when the exact approved reference decision over the exact current inputs may activate. */
    public static boolean isActivatable(
            ReferenceApprovedDecision approved, ReferenceReadinessInputs currentInputs) {
        try {
            requireActivatable(approved, currentInputs);
            return true;
        } catch (GateException failure) {
            return false;
        }
    }

    /**
     * Requires an exactly-pinned, genuine approved {@code PASSED} reference decision, or fails closed with the
     * bounded code that names the condition.
     */
    public static void requireActivatable(
            ReferenceApprovedDecision approved, ReferenceReadinessInputs currentInputs) {
        if (approved == null
                || !ReferenceReadinessProjector.REFERENCE_EXISTING_AUDIENCE_REFERENCE
                        .equals(approved.capabilityId())) {
            throw GateException.of(
                    GateErrorCodes.CAPABILITY_NOT_GATED,
                    "Only an approval bound to the dedicated reference capability may activate it");
        }
        if (currentInputs == null) {
            throw GateException.of(
                    GateErrorCodes.REFERENCE_NOT_PRESENT,
                    "A reference activation requires the current reference inputs");
        }
        if (!approved.isStructurallyValid()) {
            throw GateException.of(
                    GateErrorCodes.APPROVAL_INVALID,
                    "A reference approval must recompute to its declared decision and approval hashes");
        }
        if (!approved.approval().hasCompleteQuorum()) {
            throw GateException.of(
                    GateErrorCodes.REFERENCE_APPROVAL_PENDING,
                    "A reference approval requires a complete PRODUCT/DATA/TEST owner quorum");
        }
        if (approved.decision().outcome() != GateOutcome.PASSED) {
            throw GateException.of(
                    GateErrorCodes.NOT_PASSED, "Only an approved PASSED reference decision may activate");
        }
        if (!approved.decision().inputs().equals(currentInputs)) {
            throw GateException.of(
                    GateErrorCodes.GATED_INPUT_CHANGED,
                    "A reference approval must pin exactly the current reference inputs");
        }
    }
}