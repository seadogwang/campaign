package com.company.campaign.segment.validation;

import com.company.campaign.contracts.Issue;
import com.company.campaign.security.authorization.AuthorizationService;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Requirement 11: every authorization outcome is fail-closed and non-disclosing. */
class SegmentValidationAuthorizationTest {

    private static SegmentValidationResult validateWith(AuthorizationService service) {
        return SegmentValidationFixtures.validate(
                SegmentValidationFixtures.validDocument(), SegmentValidationFixtures.snapshot(),
                SegmentValidationFixtures.context(), service, SegmentValidationFixtures.policy());
    }

    @Test
    void anAllowedDecisionProducesAValidResult() {
        assertThat(validateWith(SegmentValidationFixtures.allowAll()).valid()).isTrue();
    }

    @Test
    void denialNotFoundDataScopeDeniedUnavailableAndInvalidAllFailClosedIdentically() {
        List<AuthorizationService> denied = List.of(
                SegmentValidationFixtures.denyAll(),
                SegmentValidationFixtures.notFound(),
                SegmentValidationFixtures.dataScopeDenied(),
                SegmentValidationFixtures.dataScopeUnavailable(),
                SegmentValidationFixtures.invalidRequest());

        for (AuthorizationService service : denied) {
            SegmentValidationResult result = validateWith(service);
            assertThat(result.valid()).isFalse();
            assertThat(result.canonicalSegment()).isNull();
            assertThat(SegmentValidationFixtures.codes(result))
                    .contains(SegmentValidationErrorCodes.RESOURCE_NOT_ACCESSIBLE)
                    .doesNotContain(SegmentValidationErrorCodes.DEFINITION_UNKNOWN);
        }
    }

    @Test
    void aThrowingBoundaryIsTreatedAsUnavailableAndFailsClosed() {
        SegmentValidationResult result = validateWith(SegmentValidationFixtures.throwing());

        assertThat(result.valid()).isFalse();
        assertThat(SegmentValidationFixtures.codes(result))
                .contains(SegmentValidationErrorCodes.RESOURCE_NOT_ACCESSIBLE);
    }

    @Test
    void aMissingAuthorizationServiceDeniesEveryDefinition() {
        SegmentValidationResult result = validateWith(null);

        assertThat(result.valid()).isFalse();
        assertThat(SegmentValidationFixtures.codes(result))
                .contains(SegmentValidationErrorCodes.RESOURCE_NOT_ACCESSIBLE);
    }

    @Test
    void aContextWithoutTheRequiredPermissionIsDeniedByTheT02Boundary() {
        SegmentValidationResult result = SegmentValidationFixtures.validate(
                SegmentValidationFixtures.validDocument(), SegmentValidationFixtures.snapshot(),
                SegmentValidationFixtures.noPermissionContext(), SegmentValidationFixtures.allowAll(),
                SegmentValidationFixtures.policy());

        assertThat(result.valid()).isFalse();
        assertThat(SegmentValidationFixtures.codes(result))
                .contains(SegmentValidationErrorCodes.RESOURCE_NOT_ACCESSIBLE);
    }

    @Test
    void anInaccessibleDefinitionOfTheWrongKindDoesNotRevealItsKind() {
        // An attribute predicate names a metric ID that exists but is denied: only the non-disclosing
        // authorization issue may appear, never a kind-mismatch finding about an inaccessible resource.
        SegmentValidationResult result = SegmentValidationFixtures.validate(
                SegmentValidationFixtures.publish(SegmentValidationFixtures.attribute(
                        "n", "order.count",
                        com.company.campaign.metadata.domain.ComparisonOperator.EQUAL,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.integerValue(1)))),
                SegmentValidationFixtures.snapshot(), SegmentValidationFixtures.context(),
                SegmentValidationFixtures.denyAll(), SegmentValidationFixtures.policy());

        assertThat(SegmentValidationFixtures.codes(result))
                .containsExactly(SegmentValidationErrorCodes.RESOURCE_NOT_ACCESSIBLE)
                .doesNotContain(SegmentValidationErrorCodes.DEFINITION_KIND_MISMATCH);
    }

    @Test
    void authorizationIssuesNeverEchoInaccessibleIdentifiersOrOwnerIdentifiers() {
        SegmentValidationResult result = validateWith(SegmentValidationFixtures.denyAll());

        for (Issue issue : result.issues()) {
            assertThat(issue.message())
                    .doesNotContain("member.tier")
                    .doesNotContain("order.net_paid_amount")
                    .doesNotContain("tenant-001")
                    .doesNotContain("program-001")
                    .doesNotContain("secret");
            assertThat(issue.details().toString())
                    .doesNotContain("member.tier")
                    .doesNotContain("order.net_paid_amount")
                    .doesNotContain("tenant-001");
            assertThat(issue.sourceSpan()).isNull();
        }
    }

    @Test
    void anInaccessibleDefinitionDoesNotAlsoLeakASemanticFindingAboutIt() {
        // The document references a real definition with an operator it does not allow. A denied definition
        // must not additionally reveal that semantic fact.
        SegmentValidationResult result = SegmentValidationFixtures.validate(
                SegmentValidationFixtures.publish(SegmentValidationFixtures.attribute(
                        "n", "member.tier", com.company.campaign.metadata.domain.ComparisonOperator.GREATER_THAN,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.integerValue(1)))),
                SegmentValidationFixtures.snapshot(), SegmentValidationFixtures.context(),
                SegmentValidationFixtures.denyAll(), SegmentValidationFixtures.policy());

        assertThat(SegmentValidationFixtures.codes(result))
                .containsExactly(SegmentValidationErrorCodes.RESOURCE_NOT_ACCESSIBLE);
    }
}