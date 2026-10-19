package com.company.campaign.query;

import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.query.plan.SegmentQueryPlanner;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.query.testsupport.QueryPlanFixtures;
import com.company.campaign.segment.validation.SegmentValidationResult;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import com.company.campaign.security.authorization.AuthorizationDecision;
import com.company.campaign.security.authorization.AuthorizationDisposition;
import com.company.campaign.security.authorization.AuthorizationReason;
import com.company.campaign.security.authorization.AuthorizationRequest;
import com.company.campaign.security.authorization.AuthorizationService;
import com.company.campaign.security.authorization.ResourceKind;
import com.company.campaign.security.context.CampaignPermission;
import com.company.campaign.security.context.TrustedExecutionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Finding 1: planning reauthorizes every dependency for the exact trusted context.
 *
 * <p>A genuine T07 result proven under one principal/DataScope is not a licence to plan under another. The
 * planner authorizes every distinct direct and transitive dependency through the mandatory T02 boundary and
 * fails closed with one stable non-disclosing code for denial, not-found, DataScope denial/unavailability,
 * an invalid request, a null decision and any thrown boundary failure.
 */
class SegmentQueryPlannerAuthorizationTest {

    private static final ComparisonOperator GT = ComparisonOperator.GREATER_THAN;
    private static final ComparisonOperator EQ = ComparisonOperator.EQUAL;

    /** A document referencing an attribute, an EXISTS relation and a metric whose relation is transitive. */
    private static final com.company.campaign.segment.dsl.contract.SegmentDsl DOCUMENT =
            SegmentValidationFixtures.publish(SegmentValidationFixtures.all("n-root", List.of(
                    SegmentValidationFixtures.tierEquals("n-tier", "GOLD"),
                    SegmentValidationFixtures.exists("n-e", "member.orders", SegmentValidationFixtures.lifetime(),
                            SegmentValidationFixtures.attribute("n-c", "order.channel", EQ,
                                    SegmentValidationFixtures.value(SegmentValidationFixtures.enumValue("ONLINE")))),
                    SegmentValidationFixtures.metric("n-spend", "order.net_paid_amount",
                            SegmentValidationFixtures.rolling(90, com.company.campaign.segment.dsl.window.WindowUnit.DAY),
                            GT,
                            SegmentValidationFixtures.value(
                                    SegmentValidationFixtures.moneyValue("1000.00", "CNY"))))));

    private static final SegmentValidationResult RESULT = SegmentValidationFixtures.validate(DOCUMENT);

    private static TrustedExecutionContext otherScope() {
        return QueryPlanFixtures.context("tenant-001", "program-001", "scope-009");
    }

    private static Stream<Arguments> denyingServices() {
        return Stream.of(
                Arguments.of("permissionDenied", SegmentValidationFixtures.denyAll()),
                Arguments.of("dataScopeDenied", SegmentValidationFixtures.dataScopeDenied()),
                Arguments.of("dataScopeUnavailable", SegmentValidationFixtures.dataScopeUnavailable()),
                Arguments.of("notFound", SegmentValidationFixtures.notFound()),
                Arguments.of("invalidRequest", SegmentValidationFixtures.invalidRequest()),
                Arguments.of("nullDecision", (AuthorizationService) request -> null),
                Arguments.of("throwing", SegmentValidationFixtures.throwing()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("denyingServices")
    void aResultValidatedElsewhereCannotBeReplannedWhenAuthorizationDenies(
            String name, AuthorizationService service) {
        assertThatThrownBy(() -> new SegmentQueryPlanner(service)
                .plan(RESULT, SegmentValidationFixtures.snapshot(), otherScope()))
                .isInstanceOf(QueryException.class)
                .hasFieldOrPropertyWithValue("code", QueryErrorCodes.QUERY_AUTHORIZATION_DENIED);
    }

    @Test
    void authorizationFailuresAreStableAndNonDisclosing() {
        for (Arguments arguments : denyingServices().toList()) {
            AuthorizationService service = (AuthorizationService) arguments.get()[1];
            QueryException failure = catchThrowableOfType(QueryException.class,
                    () -> new SegmentQueryPlanner(service)
                            .plan(RESULT, SegmentValidationFixtures.snapshot(), otherScope()));
            String rendered = failure.getMessage() + failure.details();
            assertThat(rendered)
                    .doesNotContain("tenant-001")
                    .doesNotContain("scope-009")
                    .doesNotContain("member.tier")
                    .doesNotContain("order.channel")
                    .doesNotContain("order.net_paid_amount")
                    .doesNotContain("member.orders")
                    .doesNotContain("secret-like text")
                    .doesNotContain("boundary failure");
            assertThat(failure.details().keySet()).containsExactly("invariant");
            assertThat(failure.getCause()).isNull();
        }
    }

    @Test
    void thePlannerRequiresAMandatoryAuthorizationService() {
        assertThatThrownBy(() -> new SegmentQueryPlanner(null))
                .isInstanceOf(QueryException.class)
                .hasFieldOrPropertyWithValue("code", QueryErrorCodes.QUERY_INPUT_MISSING);

        Constructor<?>[] constructors = SegmentQueryPlanner.class.getConstructors();
        assertThat(constructors).hasSize(1);
        assertThat(constructors[0].getParameterTypes()).containsExactly(AuthorizationService.class);
    }

    @Test
    void everyDistinctDirectAndTransitiveDependencyIsAuthorizedExactlyOnce() {
        CountingAuthorizationService counting = new CountingAuthorizationService(SegmentValidationFixtures.allowAll());
        new SegmentQueryPlanner(counting)
                .plan(RESULT, SegmentValidationFixtures.snapshot(), otherScope());

        assertThat(counting.requests).containsExactlyInAnyOrder(
                "ATTRIBUTE|member.tier",
                "ATTRIBUTE|order.channel",
                "METRIC|order.net_paid_amount",
                "RELATION|member.orders");
        assertThat(counting.requests).doesNotHaveDuplicates();
    }

    @Test
    void aMetricsTransitiveRelationIsAuthorizedEvenWithoutAnExistsNode() {
        var metricOnly = SegmentValidationFixtures.publish(SegmentValidationFixtures.metric(
                "n-count", "order.count", SegmentValidationFixtures.lifetime(), GT,
                SegmentValidationFixtures.value(SegmentValidationFixtures.integerValue(0))));
        SegmentValidationResult result = SegmentValidationFixtures.validate(metricOnly);

        CountingAuthorizationService counting = new CountingAuthorizationService(SegmentValidationFixtures.allowAll());
        new SegmentQueryPlanner(counting).plan(result, SegmentValidationFixtures.snapshot(), otherScope());

        assertThat(counting.requests).containsExactly(
                "METRIC|order.count", "RELATION|member.orders");
    }

    @Test
    void eachDependencyIsAuthorizedWithTheExactTrustedContextAndSnapshotOwnership() {
        List<AuthorizationRequest> seen = new ArrayList<>();
        AuthorizationService recording = request -> {
            seen.add(request);
            return AuthorizationDecision.allow(
                    request.resource().kind(), request.resource().kind().requiredPermission());
        };

        new SegmentQueryPlanner(recording)
                .plan(RESULT, SegmentValidationFixtures.snapshot(), otherScope());

        assertThat(seen).isNotEmpty();
        assertThat(seen).allSatisfy(request -> {
            assertThat(request.context().dataScopeId()).isEqualTo("scope-009");
            assertThat(request.context().tenantId()).isEqualTo("tenant-001");
            assertThat(request.resource().tenantId())
                    .isEqualTo(SegmentValidationFixtures.snapshot().tenantId());
            assertThat(request.resource().programId())
                    .isEqualTo(SegmentValidationFixtures.snapshot().programId());
        });
    }

    @Test
    void anAllowedDecisionOfTheWrongResourceKindIsRejected() {
        AuthorizationService wrongKind = request ->
                AuthorizationDecision.allow(ResourceKind.SEGMENT, CampaignPermission.SEGMENT_EVALUATE);
        assertDenied(wrongKind);
    }

    @Test
    void anAllowedDecisionWithTheWrongRequiredPermissionIsRejected() {
        AuthorizationService wrongPermission = request -> AuthorizationDecision.allow(
                request.resource().kind(), CampaignPermission.SEGMENT_EVALUATE);
        assertDenied(wrongPermission);
    }

    @Test
    void anAllowedDispositionWithTheWrongReasonIsRejected() {
        AuthorizationService wrongReason = request -> AuthorizationDecision.deny(
                AuthorizationDisposition.ALLOWED, AuthorizationReason.MISSING_PERMISSION,
                request.resource().kind(), request.resource().kind().requiredPermission());
        assertDenied(wrongReason);
    }

    @Test
    void anApprovalWithAMissingKindOrPermissionIsRejected() {
        AuthorizationService missingKind = request -> new AuthorizationDecision(
                AuthorizationDisposition.ALLOWED, AuthorizationReason.ALLOWED, null,
                CampaignPermission.CAMPAIGN_READ);
        assertDenied(missingKind);

        AuthorizationService missingPermission = request -> new AuthorizationDecision(
                AuthorizationDisposition.ALLOWED, AuthorizationReason.ALLOWED,
                request.resource().kind(), null);
        assertDenied(missingPermission);
    }

    @Test
    void malformedApprovalsStayStableAndNonDisclosing() {
        AuthorizationService wrongKind = request ->
                AuthorizationDecision.allow(ResourceKind.SEGMENT, CampaignPermission.SEGMENT_EVALUATE);
        QueryException failure = catchThrowableOfType(QueryException.class,
                () -> new SegmentQueryPlanner(wrongKind)
                        .plan(RESULT, SegmentValidationFixtures.snapshot(), otherScope()));
        assertThat(failure.code()).isEqualTo(QueryErrorCodes.QUERY_AUTHORIZATION_DENIED);
        assertThat(failure.getMessage() + failure.details()).doesNotContain("member.tier", "member.orders");
        assertThat(failure.details().keySet()).containsExactly("invariant");
        assertThat(failure.getCause()).isNull();
    }

    @Test
    void theGenuineT02AllowServiceStillPlansEveryDependency() {
        CountingAuthorizationService counting = new CountingAuthorizationService(SegmentValidationFixtures.allowAll());
        SemanticQueryPlan plan = new SegmentQueryPlanner(counting)
                .plan(RESULT, SegmentValidationFixtures.snapshot(), otherScope());

        assertThat(plan.planHash()).isNotNull();
        assertThat(counting.requests).containsExactlyInAnyOrder(
                "ATTRIBUTE|member.tier", "ATTRIBUTE|order.channel",
                "METRIC|order.net_paid_amount", "RELATION|member.orders");
    }

    private static void assertDenied(AuthorizationService service) {
        assertThatThrownBy(() -> new SegmentQueryPlanner(service)
                .plan(RESULT, SegmentValidationFixtures.snapshot(), otherScope()))
                .isInstanceOf(QueryException.class)
                .hasFieldOrPropertyWithValue("code", QueryErrorCodes.QUERY_AUTHORIZATION_DENIED);
    }

    @Test
    void theAllowPathRemainsDeterministic() {
        CountingAuthorizationService first = new CountingAuthorizationService(SegmentValidationFixtures.allowAll());
        CountingAuthorizationService second = new CountingAuthorizationService(SegmentValidationFixtures.allowAll());

        SemanticQueryPlan firstPlan = new SegmentQueryPlanner(first)
                .plan(RESULT, SegmentValidationFixtures.snapshot(), otherScope());
        SemanticQueryPlan secondPlan = new SegmentQueryPlanner(second)
                .plan(RESULT, SegmentValidationFixtures.snapshot(), otherScope());

        assertThat(firstPlan.planHash()).isEqualTo(secondPlan.planHash());
        assertThat(first.requests).isEqualTo(second.requests);
    }

    /** Wraps an authorization service, recording the (kind, ID) of every resource it is asked about. */
    private static final class CountingAuthorizationService implements AuthorizationService {

        private final AuthorizationService delegate;
        private final List<String> requests = new ArrayList<>();

        private CountingAuthorizationService(AuthorizationService delegate) {
            this.delegate = delegate;
        }

        @Override
        public AuthorizationDecision authorize(AuthorizationRequest request) {
            requests.add(request.resource().kind().name() + "|" + request.resource().resourceId());
            return delegate.authorize(request);
        }
    }
}