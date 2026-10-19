package com.company.campaign.evaluation.segment.reference;

import java.util.List;

/**
 * The closed set of evaluation scenarios a reference-corpus case may declare.
 *
 * <p>A scenario says which part of the reference pipeline the case drives, and therefore which trusted
 * fixtures and which request parts the case must declare and which it must not. Making that explicit is what
 * lets the validator reject an under-specified or over-specified case instead of evaluating a case whose
 * meaning depends on defaults.
 */
public enum ReferenceScenario {

    /**
     * A reference-bearing T05 document validated through the accepted validator with a trusted reference
     * context: the canonical AST hash, the reference graph and the ordered issue codes are observed.
     */
    REFERENCE_DOCUMENT,

    /**
     * A request wording resolved through the accepted reference resolver against a host directory: the
     * disposition, the exact pin, the clarification question and the resolution evidence hash are observed.
     */
    REFERENCE_RESOLUTION,

    /**
     * Local fixture membership looked up for exact pins: the observed member-set hash and presence are
     * compared with the expectation.
     */
    REFERENCE_MEMBERSHIP,

    /**
     * The host retention store exercised directly: holds, release policy and hard deletion outcomes are
     * compared with the expectation.
     */
    REFERENCE_RETENTION;

    /** Every scenario, in declaration order. */
    public static List<ReferenceScenario> ordered() {
        return List.of(values());
    }
}