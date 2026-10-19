package com.company.campaign.dev.campaignagent;

import com.company.campaign.contracts.TrustedExecutionContextRef;
import com.company.campaign.contracts.LocaleTag;
import com.company.campaign.security.context.CampaignPermission;
import com.company.campaign.security.context.MembershipStatus;
import com.company.campaign.security.context.PrincipalMembership;
import com.company.campaign.security.context.TrustedContextProvider;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.security.context.VerifiedIdentity;

import java.time.Instant;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * The clearly named, fixed <em>development-only</em> identity provider of the local Campaign Agent host.
 *
 * <p>This repository has no production OIDC adapter, so the opt-in development host cannot resolve a real
 * principal. This provider supplies one visibly development identity: it is named for what it is, it issues no
 * token, it verifies nothing and it is <strong>never production evidence</strong>. Production authentication
 * remains a blocker.
 *
 * <p>The trusted instant is injected rather than committed, so even the development identity reads no clock of
 * its own: a host constructs it from its own injected clock. Its tenant, program, DataScope and snapshot
 * identifiers are fixed development constants, exactly as the accepted
 * {@link com.company.campaign.dev.segment.CoreLoyaltyDevelopmentProfile} fixes its own.
 */
public final class DevelopmentCampaignAgentIdentity implements TrustedContextProvider {

    /** The clearly named identity of this provider; a host can log it without implying production identity. */
    public static final String PROVIDER_NAME = "campaign-agent-development-identity";

    /** The fixed development tenant. */
    public static final String TENANT = "tenant-dev";

    /** The fixed development program. */
    public static final String PROGRAM = "program-dev";

    /** The fixed development user. */
    public static final String USER = "user-dev";

    /** The fixed development DataScope. */
    public static final String DATA_SCOPE = "scope-dev";

    /** The fixed development metadata snapshot identifier. */
    public static final String SNAPSHOT_ID = "core-loyalty-metadata";

    /** The development issuer; it is deliberately not a resolvable production issuer. */
    public static final String ISSUER = "https://development.invalid";

    private static final String MEMBERSHIP = "membership-dev";
    private static final String TIMEZONE = "Asia/Shanghai";

    private final Clock clock;
    private final Set<CampaignPermission> permissions;

    public DevelopmentCampaignAgentIdentity(Instant trustedInstant, Set<CampaignPermission> permissions) {
        this(Clock.fixed(Objects.requireNonNull(trustedInstant, "trustedInstant"), ZoneId.of("UTC")), permissions);
    }

    /** A development identity whose trusted instant is refreshed from the host clock for every request. */
    public DevelopmentCampaignAgentIdentity(Clock clock, Set<CampaignPermission> permissions) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.permissions = permissions == null
                ? Set.of(CampaignPermission.values())
                : Set.copyOf(permissions);
    }

    /** The fixed identity the development host has "verified". */
    @Override
    public VerifiedIdentity verifiedIdentity() {
        return new VerifiedIdentity(USER, ISSUER, clock.instant());
    }

    /** The fixed active membership of the development identity. */
    @Override
    public PrincipalMembership verifiedMembership() {
        return new PrincipalMembership(
                MEMBERSHIP, USER, TENANT, PROGRAM, DATA_SCOPE, MembershipStatus.ACTIVE, permissions);
    }

    /** The current development-day execution-context snapshot bound to the development identity. */
    @Override
    public TrustedExecutionContextRef executionReference() {
        ZoneId timeZone = ZoneId.of(TIMEZONE);
        Instant trustedInstant = clock.instant().atZone(timeZone).toLocalDate()
                .atStartOfDay(ZoneId.of("UTC")).toInstant();
        return new TrustedExecutionContextRef(
                TENANT, PROGRAM, USER, DATA_SCOPE, SNAPSHOT_ID,
                LocaleTag.of(Locale.SIMPLIFIED_CHINESE), timeZone, "CNY",
                trustedInstant, trustedInstant.minusSeconds(60));
    }

    /** The resolved development context; the accepted resolver re-validates every field. */
    public TrustedExecutionContext context() {
        return trustedContext();
    }

    /** A bounded diagnostic: the provider name and the fixed identifiers only, never a token. */
    @Override
    public String toString() {
        return "DevelopmentCampaignAgentIdentity[provider=" + PROVIDER_NAME
                + ", tenant=" + TENANT + ", program=" + PROGRAM + ']';
    }
}
