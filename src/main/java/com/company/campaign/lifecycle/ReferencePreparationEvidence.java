package com.company.campaign.lifecycle;

import com.company.campaign.builder.compiler.ReferencePlanEvidence;
import com.company.campaign.shared.canonical.ContentHash;

/**
 * The exact Existing Audience Reference activation evidence one reference-bearing publish preparation freezes.
 *
 * <p>It is the reference counterpart of the base preparation's readiness pins and is deliberately narrower:
 * it exists only for the one dedicated reference capability/Gate identity, and it is only ever built from an
 * <em>exact</em> {@code ACTIVE} reference readiness projection whose pins match the plan's own frozen
 * reference evidence. There is therefore no development state and no "unapproved" member here: a preparation
 * that cannot name all four facts is not representable at all, and a reference-bearing production path fails
 * closed instead of freezing weaker evidence.
 *
 * <p>Four host facts are frozen and nothing else:
 *
 * <ul>
 *   <li>{@link #capabilityId()} — the dedicated {@code segment.existing-audience-reference} identity, so an
 *       approval of the base {@code segment.rule-based} capability can never be read as one of these;</li>
 *   <li>{@link #referenceDependenciesHash()} — the canonical hash of the referenced version dependencies the
 *       host re-resolved for this exact plan;</li>
 *   <li>{@link #referenceInputsHash()} — the hash of the dedicated reference Gate inputs the activation was
 *       proved against, so a stale reference readiness is observable rather than implicit;</li>
 *   <li>{@link #approvedDecisionHash()} — the approved decision the {@code ACTIVE} readiness rests on, so the
 *       activation is bound to its provenance rather than to a bare state flag.</li>
 * </ul>
 *
 * <p>It carries no tenant, program, user, DataScope, permission, version pin, member identifier, dependency
 * list or Gate payload. It is a hash-bound host fact, and no client, model or Tool payload can supply one: the
 * only producer is the host-only reference activation check over a host-owned readiness projection.
 */
public record ReferencePreparationEvidence(
        String capabilityId,
        ContentHash referenceDependenciesHash,
        ContentHash referenceInputsHash,
        ContentHash approvedDecisionHash
) {

    public ReferencePreparationEvidence {
        if (!ReferencePlanEvidence.CAPABILITY_ID.equals(capabilityId)) {
            // The identity is one closed value: the base Segment identity, or anything else, is refused rather
            // than being written into a preparation as reference activation evidence.
            throw invalid("referenceEvidenceCapability");
        }
        if (referenceDependenciesHash == null || referenceInputsHash == null
                || approvedDecisionHash == null) {
            // An activation always rests on all three: the resolved dependencies, the gated inputs in force and
            // the approved decision behind them. A partial activation is not representable.
            throw invalid("referenceEvidencePins");
        }
    }

    /** True only for the one dedicated reference capability/Gate identity. */
    public boolean isDedicatedIdentity() {
        return ReferencePlanEvidence.CAPABILITY_ID.equals(capabilityId);
    }

    private static LifecycleException invalid(String invariant) {
        return new LifecycleException(
                LifecycleErrorCodes.LIFECYCLE_INTERNAL_ERROR,
                invariant,
                "A publish preparation's reference evidence must be a bounded, closed, host-owned fact.");
    }

    /**
     * A bounded diagnostic: the presence of the pins and nothing else.
     *
     * <p>It prints no capability identity and no hash, so a diagnostic of a stored preparation is never a way
     * to read the reference identity, the dependency graph or a decision hash out of a log line.
     */
    @Override
    public String toString() {
        return "ReferencePreparationEvidence[dependencies=present, inputs=present, decision=present]";
    }
}