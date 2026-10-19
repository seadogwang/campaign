package com.company.campaign.segment.reference;

/**
 * The trusted context a plan or an execution must supply to resolve Existing Audience References.
 *
 * <p>It is the planning and execution counterpart of {@link ReferenceValidationContext}. Validation needs the
 * catalog, the program policy, the DataScope port and the policy bounds; planning and execution additionally
 * need to re-observe availability and retention, because resolving a reference once is not permanent authority
 * and a referenced immutable version can be withdrawn or have its last retention hold released between two
 * steps.
 *
 * <p>Every port here is host-owned. Nothing is reachable from a document, a canonical AST, a model payload or
 * a client flag, which is why a reference-bearing plan or execution with no context fails closed rather than
 * degrading to "no trusted facts, so assume the best".
 *
 * @param catalog          the host-owned exact-version catalog; never a mutable pointer lookup
 * @param programPolicy    the host program-relation policy; never a client boolean
 * @param dataScopePort    the host DataScope intersection port; never a caller-supplied scope
 * @param availabilityPort the host availability port, re-asked at every re-check
 * @param retentionPort    the host retention port, so evidence can be frozen and re-proved
 * @param policy           the bounded reference policy; never a request-specific value
 */
public record ReferenceExecutionContext(
        AudienceVersionCatalog catalog,
        AudienceProgramPolicy programPolicy,
        ReferenceDataScopePort dataScopePort,
        AudienceVersionAvailabilityPort availabilityPort,
        AudienceRetentionPort retentionPort,
        ReferencePolicy policy
) {

    public ReferenceExecutionContext {
        if (catalog == null) {
            throw ReferenceException.invalid("A reference execution context requires the host version catalog");
        }
        if (programPolicy == null) {
            throw ReferenceException.invalid("A reference execution context requires the host program policy");
        }
        if (dataScopePort == null) {
            throw ReferenceException.invalid("A reference execution context requires the host DataScope port");
        }
        if (availabilityPort == null) {
            throw ReferenceException.invalid("A reference execution context requires the host availability port");
        }
        if (retentionPort == null) {
            throw ReferenceException.invalid("A reference execution context requires the host retention port");
        }
        if (policy == null) {
            throw ReferenceException.invalid("A reference execution context requires a bounded reference policy");
        }
    }

    /**
     * The equivalent validation context, so resolution reuses the one accepted reference-graph resolver rather
     * than a second traversal that could disagree with it about cycles, limits or scope intersection.
     */
    public ReferenceValidationContext asValidationContext(AudienceVersionRef candidateRootVersion) {
        return new ReferenceValidationContext(catalog, programPolicy, dataScopePort, policy, candidateRootVersion);
    }

    }