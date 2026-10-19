package com.company.campaign.production.campaignagent;

import com.company.campaign.builder.campaignagent.CampaignAgentEnvironment;
import com.company.campaign.builder.campaignagent.CampaignAgentException;
import com.company.campaign.builder.campaignagent.http.CampaignAgentConversationHttpResponse;
import com.company.campaign.builder.campaignagent.http.CampaignAgentHttpErrorCodes;
import com.company.campaign.builder.campaignagent.http.CampaignAgentTurnHttpRequest;
import com.company.campaign.campaignagent.CampaignConversationCreateService;
import com.company.campaign.campaignagent.CampaignConversationTurnService;
import com.company.campaign.contracts.LocaleTag;
import com.company.campaign.contracts.TrustedExecutionContextRef;
import com.company.campaign.conversation.CampaignConversationAuditSink;
import com.company.campaign.conversation.CampaignConversationIdentity;
import com.company.campaign.conversation.CampaignConversationIdentitySource;
import com.company.campaign.dev.campaignagent.InMemoryDevelopmentConversationStore;
import com.company.campaign.security.context.CampaignPermission;
import com.company.campaign.security.context.MembershipStatus;
import com.company.campaign.security.context.PrincipalMembership;
import com.company.campaign.security.context.TrustedContextProvider;
import com.company.campaign.security.context.VerifiedIdentity;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * T35B: the neutral production create/turn services behind the accepted routes bind identity only through the
 * trusted-context provider, and every boundary refusal is bounded and non-disclosing.
 *
 * <p>The accepted HTTP adapters are unchanged and already covered by the T32/T34 HTTP integration tests; these
 * tests drive the very same neutral services the production host wires, so the create idempotency, reservation
 * and cross-boundary non-disclosure behavior is exercised against the production wiring shape. The in-memory
 * store here is a test double only: production resolves the durable marker type.
 */
class ProductionCampaignAgentBoundaryTest {

    private static final String CONVERSATION = "conversation-prod-1";
    private static final String RUN = "run-prod-1";
    private static final String AT = "2026-01-01T00:00:00Z";

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private static final class ScriptedIdentitySource implements CampaignConversationIdentitySource {

        private int issued;

        @Override
        public CampaignConversationIdentity next() {
            issued++;
            return new CampaignConversationIdentity("conversation-prod-" + issued, "run-prod-" + issued);
        }
    }

    private static final class ScriptedIdentity implements TrustedContextProvider {

        private final MembershipStatus status;
        private final Set<CampaignPermission> permissions;
        private final String tenant;
        private final String user;

        private ScriptedIdentity(
                MembershipStatus status, Set<CampaignPermission> permissions, String tenant, String user) {
            this.status = status;
            this.permissions = permissions;
            this.tenant = tenant;
            this.user = user;
        }

        private static ScriptedIdentity active(Set<CampaignPermission> permissions) {
            return new ScriptedIdentity(MembershipStatus.ACTIVE, permissions, "tenant-prod", "user-prod");
        }

        private static ScriptedIdentity inactive() {
            return new ScriptedIdentity(MembershipStatus.INACTIVE, Set.of(CampaignPermission.CAMPAIGN_EDIT),
                    "tenant-prod", "user-prod");
        }

        private static ScriptedIdentity otherTenant() {
            return new ScriptedIdentity(MembershipStatus.ACTIVE, Set.of(CampaignPermission.CAMPAIGN_EDIT),
                    "tenant-other", "user-prod");
        }

        @Override
        public VerifiedIdentity verifiedIdentity() {
            return new VerifiedIdentity(user, "https://issuer.invalid", Instant.parse("2026-01-01T00:00:00Z"));
        }

        @Override
        public PrincipalMembership verifiedMembership() {
            return new PrincipalMembership("membership-prod", user, tenant, "program-prod", "scope-prod",
                    status, permissions);
        }

        @Override
        public TrustedExecutionContextRef executionReference() {
            return new TrustedExecutionContextRef(tenant, "program-prod", user, "scope-prod", "snapshot-prod",
                    LocaleTag.of(Locale.US), ZoneId.of("UTC"), "USD",
                    Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2025-12-31T23:59:00Z"));
        }
    }

    private static CampaignConversationCreateService createService(TrustedContextProvider identity) {
        return new CampaignConversationCreateService(
                identity, store(), new ScriptedIdentitySource());
    }

    private static CampaignConversationTurnService turnService(TrustedContextProvider identity) {
        // The inert environment: no routing model and no Tool evidence, so no refusal test reaches a model.
        CampaignAgentEnvironment environment = () -> null;
        return new CampaignConversationTurnService(
                identity, environment, store(), (key, context) -> null,
                CampaignConversationAuditSink.none(), CLOCK);
    }

    private static InMemoryDevelopmentConversationStore store() {
        return new InMemoryDevelopmentConversationStore(CLOCK, 16, 16, Duration.ofHours(1));
    }

    private static CampaignAgentTurnHttpRequest turn(long expectedRevision) {
        return new CampaignAgentTurnHttpRequest(CONVERSATION, RUN, expectedRevision, "hello", List.of(), AT);
    }

    // ------------------------------------------------------------------ create boundary

    @Test
    void createWithoutAnActiveMembershipFailsClosedAsUnauthenticated() {
        CampaignAgentException failure = catchThrowableOfType(CampaignAgentException.class,
                () -> createService(ScriptedIdentity.inactive()).create("create-key-1"));
        assertThat(failure.code()).isEqualTo(CampaignAgentHttpErrorCodes.UNAUTHENTICATED);
    }

    @Test
    void createWithoutCampaignEditFailsClosedAsForbidden() {
        CampaignAgentException failure = catchThrowableOfType(CampaignAgentException.class,
                () -> createService(ScriptedIdentity.active(Set.of())).create("create-key-1"));
        assertThat(failure.code()).isEqualTo(CampaignAgentHttpErrorCodes.FORBIDDEN);
    }

    @Test
    void createWithoutAnIdempotencyKeyFailsClosed() {
        CampaignAgentException failure = catchThrowableOfType(CampaignAgentException.class,
                () -> createService(ScriptedIdentity.active(Set.of(CampaignPermission.CAMPAIGN_EDIT))).create(null));
        assertThat(failure.code()).isEqualTo(CampaignAgentHttpErrorCodes.IDEMPOTENCY_KEY_REQUIRED);
    }

    @Test
    void aReplayedCreateReturnsTheVerySameServerIssuedIdentity() {
        CampaignConversationCreateService service =
                createService(ScriptedIdentity.active(Set.of(CampaignPermission.CAMPAIGN_EDIT)));
        CampaignAgentConversationHttpResponse first = service.create("create-key-replay");
        CampaignAgentConversationHttpResponse replay = service.create("create-key-replay");
        assertThat(replay.conversationId()).isEqualTo(first.conversationId());
        assertThat(replay.runId()).isEqualTo(first.runId());
        assertThat(replay.revision()).isZero();
        assertThat(first.conversationId()).startsWith("conversation-prod-");
    }

    // ------------------------------------------------------------------ turn boundary

    @Test
    void aTurnOnAnAbsentConversationFailsClosedAsNotFound() {
        CampaignAgentException failure = catchThrowableOfType(CampaignAgentException.class,
                () -> turnService(ScriptedIdentity.active(Set.of(CampaignPermission.CAMPAIGN_EDIT)))
                        .turn(turn(1L), "turn-key-1"));
        assertThat(failure.code()).isEqualTo(CampaignAgentHttpErrorCodes.NOT_FOUND);
    }

    @Test
    void aCrossBoundaryTurnFailsClosedWithTheSameNotFound() {
        CampaignAgentException failure = catchThrowableOfType(CampaignAgentException.class,
                () -> turnService(ScriptedIdentity.otherTenant()).turn(turn(0L), "turn-key-2"));
        assertThat(failure.code()).isEqualTo(CampaignAgentHttpErrorCodes.NOT_FOUND);
    }

    @Test
    void aTurnWithoutAnActiveMembershipFailsClosedAsUnauthenticated() {
        CampaignAgentException failure = catchThrowableOfType(CampaignAgentException.class,
                () -> turnService(ScriptedIdentity.inactive()).turn(turn(0L), "turn-key-3"));
        assertThat(failure.code()).isEqualTo(CampaignAgentHttpErrorCodes.UNAUTHENTICATED);
    }

    @Test
    void aTurnWithoutCampaignEditFailsClosedAsForbidden() {
        CampaignAgentException failure = catchThrowableOfType(CampaignAgentException.class,
                () -> turnService(ScriptedIdentity.active(Set.of())).turn(turn(0L), "turn-key-4"));
        assertThat(failure.code()).isEqualTo(CampaignAgentHttpErrorCodes.FORBIDDEN);
    }

    @Test
    void aTurnWithoutAnIdempotencyKeyFailsClosed() {
        CampaignAgentException failure = catchThrowableOfType(CampaignAgentException.class,
                () -> turnService(ScriptedIdentity.active(Set.of(CampaignPermission.CAMPAIGN_EDIT)))
                        .turn(turn(0L), "not a valid key!"));
        assertThat(failure.code()).isEqualTo(CampaignAgentHttpErrorCodes.IDEMPOTENCY_KEY_INVALID);
    }

    // ------------------------------------------------------------------ no trusted request field

    @Test
    void theAcceptedTurnRequestCarriesNoTrustedField() {
        List<String> components = java.util.Arrays.stream(
                        CampaignAgentTurnHttpRequest.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();
        assertThat(components).containsExactly(
                "conversationId", "runId", "expectedRevision", "message", "answers", "at");
        assertThatCode(() -> new CampaignAgentTurnHttpRequest(
                CONVERSATION, RUN, 0L, "hello", null, AT)).doesNotThrowAnyException();
    }
}