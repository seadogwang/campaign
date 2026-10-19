package com.company.campaign.lifecycle;

import com.company.campaign.evaluation.segment.gate.ReferenceReadinessInputs;

import java.util.Objects;

/**
 * The exact host-owned reference facts one reference-bearing production preparation is re-proved against.
 *
 * <p>It pairs the two things a host must re-resolve for a compiled Plan before any reference-bearing
 * preparation can be frozen:
 *
 * <ul>
 *   <li>{@link #dependencies()} — the exact transitive referenced Audience Versions the host resolved for
 *       this Plan, with the canonical hash of that closure. It is compared with the dependency hash the Plan's
 *       own trusted reference evidence froze, so a graph that moved since the compile is observable;</li>
 *   <li>{@link #inputs()} — the dedicated reference Gate inputs in force now, whose canonical content hash is
 *       compared with the reference-inputs hash the Plan froze. It is the input bundle the reference readiness
 *       projection is re-derived against, so a stale reference readiness is refused rather than concluded.</li>
 * </ul>
 *
 * <p>It carries no owner, scope, permission, member set or DSL material, and it is never read from a command,
 * a Tool payload or a model output: the only producer is the host-owned resolution port the prepare boundary
 * asks. Absence of these facts is expressed by the port answering empty, never by an empty value here.
 */
public record ReferencePreparationFacts(
        ResolvedReferenceDependencies dependencies,
        ReferenceReadinessInputs inputs
) {

    public ReferencePreparationFacts {
        Objects.requireNonNull(dependencies, "dependencies");
        Objects.requireNonNull(inputs, "inputs");
    }

    /** A bounded diagnostic: the resolved-version count and presence only, never a hash or an identity. */
    @Override
    public String toString() {
        return "ReferencePreparationFacts[versions=" + dependencies.versions().size()
                + ", inputs=present]";
    }
}