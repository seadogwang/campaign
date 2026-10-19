package com.company.campaign.production.security;

import com.company.campaign.contracts.TrustedExecutionContextRef;
import com.company.campaign.security.context.PrincipalMembership;
import com.company.campaign.security.context.TrustedContextProvider;
import com.company.campaign.security.context.VerifiedIdentity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/**
 * The request-scoped production {@link TrustedContextProvider}.
 *
 * <p>It is published as a request-scoped proxy, so exactly one instance is built per authenticated request and
 * every method call sees that request's verified identity, resolved membership and execution-context snapshot.
 * The values are read once, from the verified Spring {@link Jwt} bound to the request and from the server-side
 * membership directory — never from a request header, body field, query parameter, role or scope, and never from
 * a static holder or a hand-rolled per-thread holder.
 *
 * <p>The provider fails closed: when there is no authenticated {@link Jwt} bound to the current execution, when a
 * required claim is missing, malformed, blank or over-limit, or when the directory refuses the lookup, it raises
 * the bounded {@link ProductionTrustedContextException}. Accessing it outside a bearer request therefore fails
 * rather than silently returning a development identity.
 */
public final class ProductionTrustedContextProvider implements TrustedContextProvider {

    /** The clearly named provider; a host can log it without implying a development identity. */
    public static final String PROVIDER_NAME = "campaign-production-identity";

    private final VerifiedIdentity identity;
    private final PrincipalMembership membership;
    private final TrustedExecutionContextRef reference;

    public ProductionTrustedContextProvider(
            ProductionMembershipRepository repository, ProductionSecurityProperties properties, Clock clock) {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(clock, "clock");
        Jwt jwt = verifiedJwt();
        this.identity = ProductionJwtClaims.verifiedIdentity(jwt, properties, clock);
        Optional<String> selector =
                ProductionJwtClaims.selectionValue(jwt, properties.getMembership().getSelectClaim());
        ResolvedMembership resolved = repository.findActiveMembership(identity, selector)
                .orElseThrow(ProductionTrustedContextException::new);
        this.membership = resolved.membership();
        this.reference = resolved.reference();
    }

    /** The verified identity of the current bearer request; never taken from a header or payload. */
    @Override
    public VerifiedIdentity verifiedIdentity() {
        return identity;
    }

    /** The active membership the directory resolved for that identity. */
    @Override
    public PrincipalMembership verifiedMembership() {
        return membership;
    }

    /** The execution-context snapshot the directory published with that membership. */
    @Override
    public TrustedExecutionContextRef executionReference() {
        return reference;
    }

    private static Jwt verifiedJwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ProductionTrustedContextException();
        }
        if (!(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new ProductionTrustedContextException();
        }
        return jwt;
    }

    /** A bounded diagnostic: the provider name only, never a token, subject, issuer or membership identifier. */
    @Override
    public String toString() {
        return "ProductionTrustedContextProvider[provider=" + PROVIDER_NAME + ']';
    }
}