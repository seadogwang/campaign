package com.company.campaign.segment.canonical;

/**
 * Version discriminators stamped into every canonical semantic artifact.
 *
 * <p>Canonicalization is a deterministic, versioned transformation: a change to a normalization rule is a
 * new canonicalizer version, so an evaluation cannot silently compare hashes produced under different rules.
 * The value is a plain string because it is reported verbatim in an evaluation run record.
 */
public final class CanonicalizerVersions {

    /**
     * The T07 canonical semantic transform version.
     *
     * <p>It is {@code 1.2} because the canonical output domain changed: {@link CanonicalNode.AudienceReference}
     * is a new closed node whose own canonical bytes, and therefore the canonical AST hash of any document
     * carrying one, did not exist under {@code 1.1}. A canonicalization change is a new version, so an
     * artifact produced under the earlier rule can never be silently compared with — or read as — one produced
     * now: a reference-bearing canonical AST stamped {@code 1.1} would claim a transform that had no concept
     * of a reference at all. The transform of a document without a reference is byte-identical under both
     * versions; only the version pin, and therefore the hash that embeds it, moves.
     */
    public static final String CANONICALIZER_VERSION = "segment-canonicalizer/1.2";

    private CanonicalizerVersions() {
    }
}