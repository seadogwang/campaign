package com.company.campaign.segment.canonical;

import com.company.campaign.shared.canonical.ContentHash;

/**
 * The semantic identity of one referenced immutable Audience Version inside the canonical AST.
 *
 * <p>It is exactly the immutable pin and nothing else: the audience identity, the exact version identity and
 * the exact content hash of that version. Every trusted fact a reference depends on — tenant and program
 * owner, root subject, effective DataScope, availability, retention state, canonical/plan hashes and the
 * direct dependency list — is deliberately absent, because it belongs to host-owned catalog material rather
 * than to the document's meaning. Two references with the same pin therefore canonicalize to identical bytes
 * in two different tenants, which is what makes the pin — not the ambient authority — the semantic identity.
 *
 * <p>Because it carries no mutable pointer field, a canonical reference can only ever name one frozen
 * version: there is no representable state meaning "whatever audience X currently is".
 */
public record CanonicalAudienceVersionRef(String audienceId, String versionId, ContentHash versionHash) {

    public CanonicalAudienceVersionRef {
        CanonicalInvariants.requireReferenceId(audienceId, "audienceId");
        CanonicalInvariants.requireReferenceId(versionId, "versionId");
        if (versionHash == null) {
            throw CanonicalInvariants.failure(
                    CanonicalErrorCodes.CANONICAL_REFERENCE_ID_INVALID,
                    "versionHash",
                    "A canonical audience version reference requires the exact version content hash.");
        }
    }
}