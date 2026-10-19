package com.company.campaign.segment.resolution;

import java.math.BigDecimal;

/**
 * The evidence tier a candidate reached, in the exact order candidates are sorted.
 *
 * <p>Tier is a compatibility/evidence rank, not a probability: it orders candidates for presentation and
 * produces the explanatory score. The score is a fixed decimal weight per tier and is pinned by the policy
 * version; it can never override an incompatibility or choose between semantically conflicting candidates.
 * Declaration order is the sort order, and Metadata kind and stable definition id break ties after it.
 */
public enum CandidateTier {

    EXACT_STABLE_ID_HINT(900),
    EXACT_CONCEPT_POLICY_MATCH(800),
    FILTER_SEMANTICS_MATCH(750),
    EXACT_DISPLAY_MATCH(700),
    EXACT_ALIAS_MATCH(650),
    POSITIVE_EXAMPLE_MATCH(500),
    STRUCTURAL_MATCH(100);

    private final BigDecimal weight;

    CandidateTier(int weight) {
        this.weight = BigDecimal.valueOf(weight);
    }

    /** The explanatory weight pinned by the resolution policy version. */
    public BigDecimal weight() {
        return weight;
    }

    /**
     * True only for a tier that identifies the definition rather than merely matching its structure: an exact
     * stable-id hint, an exact versioned concept-policy mapping, an exact display/alias match or a
     * positive-example match. Structural compatibility alone is never identification evidence.
     */
    public boolean isIdentificationEvidence() {
        return this != STRUCTURAL_MATCH;
    }
}
