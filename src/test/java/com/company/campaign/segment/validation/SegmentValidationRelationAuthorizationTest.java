package com.company.campaign.segment.validation;

import com.company.campaign.contracts.Issue;
import com.company.campaign.security.authorization.AuthorizationDecision;
import com.company.campaign.security.authorization.AuthorizationDisposition;
import com.company.campaign.security.authorization.AuthorizationReason;
import com.company.campaign.security.authorization.AuthorizationRequest;
import com.company.campaign.security.authorization.AuthorizationService;
import com.company.campaign.security.authorization.ResourceKind;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Finding 2: a Metric predicate's referenced Relation is an operational semantic dependency.
 *
 * <p>Authorizing the metric ID alone is not enough: a principal could reach a Metric while the T02 boundary
 * denies the exact T04 Relation that metric requires. These tests use a resource-aware authorization fake to
 * prove both resources are authorized with the correct kind, that a failure of either fails closed without
 * disclosure, and that the outcome is deterministic and non-disclosing.
 */
class SegmentValidationRelationAuthorizationTest {

    private static final String TIER = "ATTRIBUTE|member.tier";
    private static final String METRIC = "METRIC|order.net_paid_amount";
    private static final String RELATION = "RELATION|member.orders";

    /** The resource-aware fake: one explicit outcome per (kind, id), deny by default. */
    private static final class ResourceAwareAuthorization implements AuthorizationService {

        private enum Outcome {
            ALLOW,
            DENY,
            UNAVAILABLE,
            THROW
        }

        private final Map<String, Outcome> outcomes = new HashMap<>();
        private final List<String> consulted = new ArrayList<>();

        ResourceAwareAuthorization allow(String key) {
            outcomes.put(key, Outcome.ALLOW);
            return this;
        }

        ResourceAwareAuthorization outcome(String key, Outcome outcome) {
            outcomes.put(key, outcome);
            return this;
        }

        List<String> consulted() {
            return List.copyOf(consulted);
        }

        @Override
        public AuthorizationDecision authorize(AuthorizationRequest request) {
            ResourceKind kind = request.resource().kind();
            String id = request.resource().resourceId();
            String key = kind.name() + "|" + id;
            consulted.add(key);
            return switch (outcomes.getOrDefault(key, Outcome.DENY)) {
                case ALLOW -> AuthorizationDecision.allow(kind, kind.requiredPermission());
                case DENY -> AuthorizationDecision.deny(
                        AuthorizationDisposition.PERMISSION_DENIED, AuthorizationReason.MISSING_PERMISSION,
                        kind, kind.requiredPermission());
                case UNAVAILABLE -> AuthorizationDecision.deny(
                        AuthorizationDisposition.DATASCOPE_UNAVAILABLE, AuthorizationReason.DATASCOPE_UNAVAILABLE,
                        kind, kind.requiredPermission());
                case THROW -> throw new IllegalStateException("boundary failure with secret-like text");
            };
        }
    }

    private static SegmentValidationResult validate(AuthorizationService service) {
        return SegmentValidationFixtures.validate(
                SegmentValidationFixtures.validDocument(), SegmentValidationFixtures.snapshot(),
                SegmentValidationFixtures.context(), service, SegmentValidationFixtures.policy());
    }

    @Test
    void metricAndItsRelationBothAllowedProducesAValidResult() {
        ResourceAwareAuthorization authorization = new ResourceAwareAuthorization()
                .allow(TIER)
                .allow(METRIC)
                .allow(RELATION);

        SegmentValidationResult result = validate(authorization);

        assertThat(result.valid()).as("issues: %s", SegmentValidationFixtures.codes(result)).isTrue();
        assertThat(authorization.consulted()).contains(TIER, METRIC, RELATION);
    }

    @Test
    void anAllowedMetricWithADeniedRelationFailsClosed() {
        ResourceAwareAuthorization authorization = new ResourceAwareAuthorization()
                .allow(TIER)
                .allow(METRIC)
                .outcome(RELATION, ResourceAwareAuthorization.Outcome.DENY);

        SegmentValidationResult result = validate(authorization);

        assertThat(authorization.consulted()).contains(RELATION);
        assertRejectedIdentically(result);
    }

    @Test
    void anAllowedMetricWithAnUnavailableRelationFailsClosed() {
        ResourceAwareAuthorization authorization = new ResourceAwareAuthorization()
                .allow(TIER)
                .allow(METRIC)
                .outcome(RELATION, ResourceAwareAuthorization.Outcome.UNAVAILABLE);

        assertRejectedIdentically(validate(authorization));
    }

    @Test
    void anAllowedMetricWithAThrowingRelationBoundaryFailsClosed() {
        ResourceAwareAuthorization authorization = new ResourceAwareAuthorization()
                .allow(TIER)
                .allow(METRIC)
                .outcome(RELATION, ResourceAwareAuthorization.Outcome.THROW);

        assertRejectedIdentically(validate(authorization));
    }

    @Test
    void aDeniedMetricNeverConsultsItsRelationAndNeverDisclosesWhetherItIsAllowed() {
        // The relation is allowed in one run and denied in the other, but a denied metric must produce the
        // same non-disclosing result and must never ask the boundary about the relation at all.
        ResourceAwareAuthorization relationAllowed = new ResourceAwareAuthorization()
                .allow(TIER)
                .outcome(METRIC, ResourceAwareAuthorization.Outcome.DENY)
                .allow(RELATION);
        ResourceAwareAuthorization relationDenied = new ResourceAwareAuthorization()
                .allow(TIER)
                .outcome(METRIC, ResourceAwareAuthorization.Outcome.DENY)
                .outcome(RELATION, ResourceAwareAuthorization.Outcome.DENY);

        SegmentValidationResult first = validate(relationAllowed);
        SegmentValidationResult second = validate(relationDenied);

        assertRejectedIdentically(first);
        assertThat(second).isEqualTo(first);
        assertThat(relationAllowed.consulted()).containsExactly(TIER, METRIC);
        assertThat(relationDenied.consulted()).containsExactly(TIER, METRIC);
        assertThat(SegmentValidationFixtures.codes(first))
                .doesNotContain(SegmentValidationErrorCodes.DEFINITION_UNKNOWN);
    }

    @Test
    void repeatedValidationConsultsTheSameResourcesAndIsDeterministic() {
        ResourceAwareAuthorization first = new ResourceAwareAuthorization()
                .allow(TIER).allow(METRIC).allow(RELATION);
        ResourceAwareAuthorization second = new ResourceAwareAuthorization()
                .allow(TIER).allow(METRIC).allow(RELATION);

        SegmentValidationResult firstResult = validate(first);
        SegmentValidationResult secondResult = validate(second);

        assertThat(first.consulted()).isEqualTo(second.consulted());
        assertThat(firstResult.canonicalAstHash()).isEqualTo(secondResult.canonicalAstHash());
        assertThat(firstResult.issues()).isEqualTo(secondResult.issues());
    }

    @Test
    void authorizationEvidenceContainsNoIdentifiersOwnersOrCauseText() {
        ResourceAwareAuthorization authorization = new ResourceAwareAuthorization()
                .allow(TIER)
                .allow(METRIC)
                .outcome(RELATION, ResourceAwareAuthorization.Outcome.THROW);
        ResourceAwareAuthorization deniedMetric = new ResourceAwareAuthorization()
                .allow(TIER)
                .outcome(METRIC, ResourceAwareAuthorization.Outcome.DENY);

        for (SegmentValidationResult result : List.of(validate(authorization), validate(deniedMetric))) {
            for (Issue issue : result.issues()) {
                assertThat(issue.message())
                        .doesNotContain("member.tier")
                        .doesNotContain("order.net_paid_amount")
                        .doesNotContain("member.orders")
                        .doesNotContain("tenant-001")
                        .doesNotContain("program-001")
                        .doesNotContain("secret");
                assertThat(issue.details().toString())
                        .doesNotContain("member.")
                        .doesNotContain("order.")
                        .doesNotContain("tenant-001")
                        .doesNotContain("secret");
            }
        }
    }

    private static void assertRejectedIdentically(SegmentValidationResult result) {
        assertThat(result.valid()).isFalse();
        assertThat(result.canonicalSegment()).isNull();
        assertThat(SegmentValidationFixtures.codes(result))
                .contains(SegmentValidationErrorCodes.RESOURCE_NOT_ACCESSIBLE)
                .doesNotContain(SegmentValidationErrorCodes.DEFINITION_KIND_MISMATCH)
                .doesNotContain(SegmentValidationErrorCodes.OPERATOR_NOT_ALLOWED);
    }
}