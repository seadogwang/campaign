package com.company.campaign.builder.tool.reference;

/**
 * The closed set of answer shapes the two read-only reference Tools may produce.
 *
 * <p>There are exactly two, and neither can describe a mutation: a discovery answer lists bounded safe
 * audience summaries, and a resolution answer reports one deterministic resolution outcome. There is
 * deliberately no shape for a written, retained, released, deleted or published Audience Version, because no
 * such Tool exists to produce one.
 */
public enum ReferenceToolOutputKind {

    /** Bounded, non-disclosing audience summaries an untrusted candidate text matched. */
    DISCOVERY,

    /** One deterministic resolution outcome of one Existing Audience Reference. */
    RESOLUTION
}