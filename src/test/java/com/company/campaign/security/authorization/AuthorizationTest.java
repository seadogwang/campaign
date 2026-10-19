package com.company.campaign.security.authorization;

import com.company.campaign.contracts.LocaleTag;
import com.company.campaign.contracts.TrustedExecutionContextRef;
import com.company.campaign.security.context.CampaignPermission;
import com.company.campaign.security.context.CampaignPermissions;
import com.company.campaign.security.context.MembershipStatus;
import com.company.campaign.security.context.PrincipalMembership;
import com.company.campaign.security.context.TrustedContextResolver;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.security.context.VerifiedIdentity;
import com.company.campaign.shared.error.ContractErrorCodes;
import com.company.campaign.testsupport.ContractAssertions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Fail-closed authorization: permission mapping, DataScope, Preview Sample and cross-boundary non-disclosure.
 */
class AuthorizationTest {

    private static final Instant AUTHENTICATED_AT = Instant.parse("2026-10-01T01:00:00Z");
    private static final Instant AS_OF = Instant.parse("2026-10-01T02:00:00Z");
    private static final Instant WATERMARK = Instant.parse("2026-10-01T01:55:00Z");

    private static final DataScopePolicy ALLOW_ALL = query -> DataScopeDecision.allowed();

    // Requirement 4: missing permissions deny Attribute/Metric/Relation/Segment access.
    @Test
    void missingPermissionDeniesMetadataAndSegmentAccess() {
        TrustedExecutionContext withoutRead = context(Set.of(CampaignPermission.CAMPAIGN_EDIT));
        AuthorizationService service = new FailClosedAuthorizationService(ALLOW_ALL);

        for (ResourceKind kind : List.of(ResourceKind.ATTRIBUTE, ResourceKind.METRIC, ResourceKind.RELATION)) {
            AuthorizationDecision decision = service.authorize(request(withoutRead, kind));
            assertThat(decision.disposition()).as("kind %s", kind)
                    .isEqualTo(AuthorizationDisposition.PERMISSION_DENIED);
            assertThat(decision.reason()).isEqualTo(AuthorizationReason.MISSING_PERMISSION);
            assertThat(decision.resourceKind()).isEqualTo(kind);
        }

        AuthorizationDecision segment = service.authorize(request(withoutRead, ResourceKind.SEGMENT));
        assertThat(segment.disposition()).isEqualTo(AuthorizationDisposition.PERMISSION_DENIED);
        assertThat(segment.requiredPermission()).isEqualTo(CampaignPermission.SEGMENT_EVALUATE);

        assertThat(service.authorize(request(context(CampaignPermissions.minimum()), ResourceKind.SEGMENT)).allowed())
                .isTrue();
    }

    // Requirement 5 and 6: cross-tenant and cross-program resources produce the identical non-disclosing failure.
    @Test
    void crossTenantCrossProgramAndMissingResourcesShareOneOutwardFailure() {
        TrustedExecutionContext context = context(CampaignPermissions.minimum());
        AuthorizationService service = new FailClosedAuthorizationService(ALLOW_ALL);

        Throwable crossTenant = catchThrowable(() -> service.requireAuthorized(AuthorizationRequest.of(
                context, new OwnedResourceRef(ResourceKind.ATTRIBUTE, "attr-1", "tenant-999", "program-001"))));
        Throwable crossProgram = catchThrowable(() -> service.requireAuthorized(AuthorizationRequest.of(
                context, new OwnedResourceRef(ResourceKind.ATTRIBUTE, "attr-1", "tenant-001", "program-999"))));
        Throwable missing = catchThrowable(() -> service.requireAuthorized(
                new AuthorizationRequest(context, null)));

        for (Throwable thrown : List.of(crossTenant, crossProgram, missing)) {
            assertThat(thrown).isInstanceOf(ResourceNotFoundException.class);
            assertThat(((ResourceNotFoundException) thrown).code())
                    .isEqualTo(ContractErrorCodes.RESOURCE_NOT_FOUND);
        }
        assertThat(crossTenant.getMessage()).isEqualTo(crossProgram.getMessage()).isEqualTo(missing.getMessage());
        assertThat(((ResourceNotFoundException) crossTenant).details())
                .isEqualTo(((ResourceNotFoundException) crossProgram).details())
                .isEqualTo(((ResourceNotFoundException) missing).details())
                .isEmpty();

        // Requirement 6: the public failure must not reveal foreign tenant/program identifiers or kind.
        for (Throwable thrown : List.of(crossTenant, crossProgram)) {
            ResourceNotFoundException failure = (ResourceNotFoundException) thrown;
            String rendered = failure.getMessage() + failure.details();
            assertThat(rendered).doesNotContain("tenant-999", "program-999", "ATTRIBUTE", "attr-1");
        }
    }

    // Requirement 9: the internal decision still distinguishes metadata kinds.
    @Test
    void deniedMetadataStaysDistinguishableInternally() {
        TrustedExecutionContext context = context(CampaignPermissions.minimum());
        DataScopePolicy denyAttributeAndMetric = query -> switch (query.resourceKind()) {
            case ATTRIBUTE, METRIC -> DataScopeDecision.denied("KIND_NOT_IN_SCOPE");
            default -> DataScopeDecision.allowed();
        };
        AuthorizationService service = new FailClosedAuthorizationService(denyAttributeAndMetric);

        assertThat(service.authorize(request(context, ResourceKind.ATTRIBUTE)).resourceKind())
                .isEqualTo(ResourceKind.ATTRIBUTE);
        assertThat(service.authorize(request(context, ResourceKind.METRIC)).resourceKind())
                .isEqualTo(ResourceKind.METRIC);
        assertThat(service.authorize(request(context, ResourceKind.RELATION)).allowed()).isTrue();

        // The unsafe-disclosure path never names the kind.
        Throwable crossTenantMetadata = catchThrowable(() -> service.requireAuthorized(AuthorizationRequest.of(
                context, new OwnedResourceRef(ResourceKind.ATTRIBUTE, "attr-1", "tenant-999", "program-001"))));
        assertThat(crossTenantMetadata.getMessage() + ((ResourceNotFoundException) crossTenantMetadata).details())
                .doesNotContain("ATTRIBUTE");
    }

    // Requirement 7: DataScope deny, unavailable and error decisions all fail closed.
    @Test
    void dataScopeDenyUnavailableAndErrorFailClosed() {
        TrustedExecutionContext context = context(CampaignPermissions.minimum());

        assertThat(new FailClosedAuthorizationService(query -> DataScopeDecision.denied("POLICY_DENIED"))
                .authorize(request(context, ResourceKind.SEGMENT)).disposition())
                .isEqualTo(AuthorizationDisposition.DATASCOPE_DENIED);

        assertThat(new FailClosedAuthorizationService(query -> DataScopeDecision.unavailable("SOURCE_DOWN"))
                .authorize(request(context, ResourceKind.SEGMENT)).disposition())
                .isEqualTo(AuthorizationDisposition.DATASCOPE_UNAVAILABLE);

        assertThat(new FailClosedAuthorizationService(query -> {
            throw new IllegalStateException("policy failure");
        }).authorize(request(context, ResourceKind.SEGMENT)).disposition())
                .isEqualTo(AuthorizationDisposition.DATASCOPE_UNAVAILABLE);

        assertThat(new FailClosedAuthorizationService().authorize(request(context, ResourceKind.SEGMENT))
                .disposition())
                .isEqualTo(AuthorizationDisposition.DATASCOPE_UNAVAILABLE);

        assertThat(new FailClosedAuthorizationService(query -> null)
                .authorize(request(context, ResourceKind.SEGMENT)).disposition())
                .isEqualTo(AuthorizationDisposition.DATASCOPE_UNAVAILABLE);

        AuthorizationService denying = new FailClosedAuthorizationService(
                query -> DataScopeDecision.denied("POLICY_DENIED"));
        ContractAssertions.assertFailsWithCode(
                () -> denying.requireAuthorized(request(context, ResourceKind.SEGMENT)),
                ContractErrorCodes.DATASCOPE_DENIED);
        ContractAssertions.assertFailsWithCode(
                () -> new FailClosedAuthorizationService().requireAuthorized(request(context, ResourceKind.SEGMENT)),
                ContractErrorCodes.DATASCOPE_UNAVAILABLE);
    }

    // Requirement 8: Preview Sample requires audience.preview and a DataScope approval.
    @Test
    void previewSampleRequiresPreviewPermissionAndDataScope() {
        AuthorizationService service = new FailClosedAuthorizationService(ALLOW_ALL);

        TrustedExecutionContext withoutPreview = context(Set.of(
                CampaignPermission.CAMPAIGN_READ, CampaignPermission.SEGMENT_EVALUATE));
        AuthorizationDecision deniedPermission = service.authorize(request(withoutPreview, ResourceKind.PREVIEW_SAMPLE));
        assertThat(deniedPermission.disposition()).isEqualTo(AuthorizationDisposition.PERMISSION_DENIED);
        assertThat(deniedPermission.reason()).isEqualTo(AuthorizationReason.PREVIEW_SAMPLE_MISSING_PERMISSION);
        assertThat(deniedPermission.requiredPermission()).isEqualTo(CampaignPermission.AUDIENCE_PREVIEW);

        TrustedExecutionContext withPreview = context(Set.of(CampaignPermission.AUDIENCE_PREVIEW));
        AuthorizationService denyingScope = new FailClosedAuthorizationService(
                query -> DataScopeDecision.denied("SAMPLE_NOT_IN_SCOPE"));
        assertThat(denyingScope.authorize(request(withPreview, ResourceKind.PREVIEW_SAMPLE)).disposition())
                .isEqualTo(AuthorizationDisposition.DATASCOPE_DENIED);

        assertThat(service.authorize(request(withPreview, ResourceKind.PREVIEW_SAMPLE)).allowed()).isTrue();
    }

    @Test
    void structurallyInvalidRequestIsDeniedEvenWithPermissivePolicy() {
        AuthorizationService service = new FailClosedAuthorizationService(ALLOW_ALL);

        assertThat(service.authorize(null).disposition()).isEqualTo(AuthorizationDisposition.INVALID_REQUEST);
        assertThat(service.authorize(new AuthorizationRequest(null, null)).disposition())
                .isEqualTo(AuthorizationDisposition.INVALID_REQUEST);
        ContractAssertions.assertFailsWithCode(
                () -> service.requireAuthorized(null), ContractErrorCodes.AUTHORIZATION_INVALID_REQUEST);
    }

    private static AuthorizationRequest request(TrustedExecutionContext context, ResourceKind kind) {
        return AuthorizationRequest.of(context, kind, "resource-1", context.tenantId(), context.programId());
    }

    private static TrustedExecutionContext context(Set<CampaignPermission> permissions) {
        return TrustedContextResolver.resolve(
                new VerifiedIdentity("user-001", "https://issuer.example", AUTHENTICATED_AT),
                new PrincipalMembership(
                        "membership-1", "user-001", "tenant-001", "program-001", "scope-008",
                        MembershipStatus.ACTIVE, permissions),
                new TrustedExecutionContextRef(
                        "tenant-001", "program-001", "user-001", "scope-008", "metadata-17",
                        LocaleTag.of(Locale.SIMPLIFIED_CHINESE),
                        ZoneId.of("Asia/Shanghai"),
                        "CNY",
                        AS_OF,
                        WATERMARK));
    }
}
