package com.company.campaign.production.security;

import com.company.campaign.security.context.VerifiedIdentity;

import java.util.Optional;

/**
 * The narrow server-side port that resolves the active membership of a verified identity.
 *
 * <p>It is implemented by the durable PostgreSQL adapter and may be implemented by a host. The only inputs are
 * the already-verified {@link VerifiedIdentity} and an optional opaque selector value taken from a configured
 * claim; neither a tenant, a program, a user, a DataScope, a permission, a Metadata snapshot, a locale, a
 * timezone, a currency, an as-of instant nor a watermark may be supplied by a caller.
 *
 * <p>Every refusal — no match, an inactive or expired membership, a cross-owner row, an unknown permission or an
 * ambiguous match — is returned as an empty result, so a caller cannot distinguish one refusal from another and
 * no refusal discloses a fact about the directory. A database failure is raised as a bounded
 * {@link ProductionMembershipUnavailableException} whose message and cause never carry a connection target.
 */
@FunctionalInterface
public interface ProductionMembershipRepository {

    /**
     * Resolves the one active membership owned by the verified issuer and subject.
     *
     * @param identity             the verified identity; its issuer, subject and authentication instant are the
     *                             only ownership and validity inputs
     * @param selectionClaimValue  the optional opaque selector parsed from the configured claim
     * @return the resolved membership and its execution-context snapshot, or empty for every refusal
     */
    Optional<ResolvedMembership> findActiveMembership(VerifiedIdentity identity, Optional<String> selectionClaimValue);
}