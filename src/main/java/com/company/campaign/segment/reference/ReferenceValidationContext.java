package com.company.campaign.segment.reference;

/**
 * The trusted reference-resolution context a validate, plan or execute call must supply.
 *
 * <p>It bundles the host-owned ports and the configured policy — the version catalog, the program-relation
 * policy, the DataScope intersection port and the reference policy — plus the identity of the audience
 * version the document under edit will become, when the host knows it.
 *
 * <p>Nothing here is reachable from a document, a model payload or a client flag: the context is created by
 * the host and handed to the validator. That is why a document containing a reference and no context fails
 * closed rather than degrading to "no trusted facts, so assume the best".
 *
 * @param catalog              the host-owned exact-version catalog; never a mutable pointer lookup
 * @param programPolicy        the host program-relation policy; never a client boolean
 * @param dataScopePort        the host DataScope intersection port; never a caller-supplied scope
 * @param policy               the bounded reference policy; never a request-specific value
 * @param candidateRootVersion the identity of the version this document will become, or {@code null} when the
 *                             host does not know it yet. When present, a reference graph that reaches it is a
 *                             cycle and is rejected, which is what stops a version from being made to depend
 *                             on itself through an edit.
 */
public record ReferenceValidationContext(
        AudienceVersionCatalog catalog,
        AudienceProgramPolicy programPolicy,
        ReferenceDataScopePort dataScopePort,
        ReferencePolicy policy,
        AudienceVersionRef candidateRootVersion
) {

    public ReferenceValidationContext {
        if (catalog == null) {
            throw ReferenceException.invalid("A reference context requires the host-owned version catalog");
        }
        if (programPolicy == null) {
            throw ReferenceException.invalid("A reference context requires the host program-relation policy");
        }
        if (dataScopePort == null) {
            throw ReferenceException.invalid("A reference context requires the host DataScope port");
        }
        if (policy == null) {
            throw ReferenceException.invalid("A reference context requires a bounded reference policy");
        }
    }

    /** The same context for a document the host has not yet pinned to a version identity. */
    public static ReferenceValidationContext of(
            AudienceVersionCatalog catalog,
            AudienceProgramPolicy programPolicy,
            ReferenceDataScopePort dataScopePort,
            ReferencePolicy policy) {
        return new ReferenceValidationContext(catalog, programPolicy, dataScopePort, policy, null);
    }
}