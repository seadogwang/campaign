package com.company.campaign.builder.compiler;

import com.company.campaign.shared.canonical.ContentHash;

import java.util.Set;

/**
 * The optional closed, immutable Existing Audience Reference evidence one plan preview card may carry.
 *
 * <p>It is present exactly when the resolved Segment depends on referenced immutable Audience Versions, and
 * absent otherwise. That is deliberate: a reference-free compile carries no reference evidence at all, so its
 * card bytes, card hash, plan material and plan hash stay exactly the accepted ones.
 *
 * <p>Four facts are frozen and nothing else:
 *
 * <ul>
 *   <li>{@link #capabilityId()} — the dedicated {@code segment.existing-audience-reference} capability/Gate
 *       identity, so an approval of the base Segment capability can never be read as an approval of this
 *       extension;</li>
 *   <li>{@link #referenceDependenciesHash()} — the canonical, duplicate-free hash of the exact referenced
 *       version dependencies the compile resolved;</li>
 *   <li>{@link #referenceInputsHash()} — the hash of the dedicated reference Gate inputs the compile ran
 *       against, so a stale or mismatched reference readiness is observable rather than implicit;</li>
 *   <li>{@link #readinessState()} — the reference extension's own readiness, drawn from a closed set that
 *       deliberately has no production-ready member.</li>
 * </ul>
 *
 * <p><strong>Production reference activation is unavailable.</strong> The closed vocabulary here is
 * {@code EXPERIMENTAL} and {@code DEVELOPMENT_ONLY} only. There is no {@code ACTIVE} state, no
 * approved-decision hash and no component that could carry one, because no approved reference Gate decision
 * exists. A reference-bearing compile therefore cannot claim activation through the plan material.
 *
 * <p>The compiler package deliberately reaches no capability, Gate or authoring package, so this type
 * re-states the dedicated reference identity and the closed development vocabulary instead of importing it;
 * the Segment authoring contract supplies the values and requires them to agree.
 *
 * <p>These pins never carry an owner, a program, a DataScope, a permission, a dependency list, a version pin,
 * a member identifier or a Gate decision. They are hashes of host facts, and a model, a client or a capability
 * payload cannot supply, widen or replace them.
 */
public record ReferencePlanEvidence(
        String capabilityId,
        ContentHash referenceDependenciesHash,
        ContentHash referenceInputsHash,
        String readinessState) {

    /** The one dedicated capability/Gate identity reference evidence may be bound to. */
    public static final String CAPABILITY_ID = "segment.existing-audience-reference";

    /** The development state: no approved reference Gate decision covers this extension yet. */
    public static final String EXPERIMENTAL = "EXPERIMENTAL";

    /** The evidence state of reference material produced without a real-model, human-reviewed run. */
    public static final String DEVELOPMENT_ONLY = "DEVELOPMENT_ONLY";

    /** The closed reference readiness vocabulary, which contains no production-ready member. */
    private static final Set<String> READINESS_STATES = Set.of(EXPERIMENTAL, DEVELOPMENT_ONLY);

    public ReferencePlanEvidence {
        if (!CAPABILITY_ID.equals(capabilityId)) {
            // The identity is one closed value: a base-capability approval is not an approval of the
            // extension, so a card can never carry a reference identity the authoring path does not recognise.
            throw invalid("planPreviewReferenceCapability");
        }
        if (referenceDependenciesHash == null || referenceInputsHash == null) {
            throw invalid("planPreviewReferencePins");
        }
        // The state is checked against the closed set, so ACTIVE is not representable here at all.
        if (readinessState == null || !READINESS_STATES.contains(readinessState)) {
            throw invalid("planPreviewReferenceReadiness");
        }
    }

    /** True only for the state in which reference material has no reviewed real-model evidence behind it. */
    public boolean developmentOnly() {
        return DEVELOPMENT_ONLY.equals(readinessState);
    }

    private static CompilerException invalid(String invariant) {
        return new CompilerException(
                CompilerErrorCodes.COMPILER_OUTPUT_INCONSISTENT,
                invariant,
                "Reference evidence on a plan preview card must be a bounded, closed, host-owned fact.");
    }

    /**
     * A bounded diagnostic: the state and the presence of the two host hashes.
     *
     * <p>It prints no capability identity and no hash, so a diagnostic of the plan material is never a way to
     * read the referenced dependency graph out of a log line.
     */
    @Override
    public String toString() {
        return "ReferencePlanEvidence[state=" + readinessState
                + ", dependencies=present"
                + ", inputs=present"
                + ']';
    }
}
