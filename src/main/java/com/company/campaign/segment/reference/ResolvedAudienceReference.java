package com.company.campaign.segment.reference;

/**
 * One distinct referenced version after trusted resolution: the pin, the catalog snapshot it resolved to, the
 * effective DataScope the reference is evaluated under and the depth it was first discovered at.
 *
 * <p>The effective scope is always the trusted intersection for <em>this</em> reference — it is never the
 * caller's scope copied through, and never the referenced version's published scope on its own. It is what a
 * plan freezes and what an execution guard re-proves, so a referenced audience can never be evaluated with
 * more reach than the caller already had.
 */
public record ResolvedAudienceReference(
        AudienceVersionRef reference,
        AudienceVersionSnapshot snapshot,
        EffectiveDataScope effectiveDataScope,
        int depth
) {

    public ResolvedAudienceReference {
        if (reference == null) {
            throw ReferenceException.invalid("A resolved reference requires the exact pin it resolved");
        }
        if (snapshot == null) {
            throw ReferenceException.invalid("A resolved reference requires the catalog snapshot it resolved to");
        }
        if (effectiveDataScope == null) {
            throw ReferenceException.invalid("A resolved reference requires its trusted effective DataScope");
        }
        if (depth < 1) {
            throw ReferenceException.invalid("A resolved reference requires a positive discovery depth");
        }
        if (!snapshot.version().equals(reference)) {
            throw ReferenceException.invalid("A resolved reference must resolve to exactly the pinned version");
        }
    }
}