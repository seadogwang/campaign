package com.company.campaign.security.authorization;

import com.company.campaign.builder.testsupport.BuilderFixtures;
import com.company.campaign.security.context.CampaignPermission;
import com.company.campaign.security.context.TrustedExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T17 requirement 4: a separate fail-closed {@code campaign.publish} authorization decision, added by
 * appending a new resource kind rather than inserting one so every existing ordinal and behaviour is
 * preserved.
 *
 * <p>Preparing a publication is deliberately not authority to publish later, so this decision is
 * independent of the {@code campaign.edit} decision a Save uses.
 */
class CampaignPublishAuthorizationTest {

    private final FailClosedAuthorizationService service =
            new FailClosedAuthorizationService(query -> DataScopeDecision.allowed());

    @Test
    void campaignPublishIsAppendedWithoutShiftingAnyExistingOrdinal() {
        assertThat(ResourceKind.ATTRIBUTE.ordinal()).isZero();
        assertThat(ResourceKind.METRIC.ordinal()).isEqualTo(1);
        assertThat(ResourceKind.RELATION.ordinal()).isEqualTo(2);
        assertThat(ResourceKind.SEGMENT.ordinal()).isEqualTo(3);
        assertThat(ResourceKind.PREVIEW_SAMPLE.ordinal()).isEqualTo(4);
        assertThat(ResourceKind.CAMPAIGN.ordinal()).isEqualTo(5);
        assertThat(ResourceKind.CAMPAIGN_PUBLISH.ordinal()).isEqualTo(6);
        assertThat(ResourceKind.values()).hasSize(7);
        assertThat(ResourceKind.values()[ResourceKind.values().length - 1])
                .isEqualTo(ResourceKind.CAMPAIGN_PUBLISH);
    }

    @Test
    void campaignPublishRequiresTheCampaignPublishPermission() {
        assertThat(ResourceKind.CAMPAIGN_PUBLISH.requiredPermission())
                .isEqualTo(CampaignPermission.CAMPAIGN_PUBLISH);
        assertThat(ResourceKind.CAMPAIGN_PUBLISH.isMetadata()).isFalse();
        assertThat(ResourceKind.CAMPAIGN.requiredPermission())
                .as("the existing Save decision is unchanged")
                .isEqualTo(CampaignPermission.CAMPAIGN_EDIT);
    }

    @Test
    void campaignEditIsNotAuthorityToPublish() {
        TrustedExecutionContext editOnly = BuilderFixtures.context(
                BuilderFixtures.TENANT, BuilderFixtures.PROGRAM,
                EnumSet.of(CampaignPermission.CAMPAIGN_EDIT));

        assertThat(service.authorize(request(editOnly, ResourceKind.CAMPAIGN)).allowed())
                .as("the caller may Save")
                .isTrue();

        AuthorizationDecision publish = service.authorize(
                request(editOnly, ResourceKind.CAMPAIGN_PUBLISH));
        assertThat(publish.allowed()).as("but may not publish").isFalse();
        assertThat(publish.disposition()).isEqualTo(AuthorizationDisposition.PERMISSION_DENIED);
        assertThat(publish.requiredPermission()).isEqualTo(CampaignPermission.CAMPAIGN_PUBLISH);
        assertThat(service).isNotNull();
    }

    @Test
    void aCallerHoldingCampaignPublishIsAllowedWhenOwnershipAndDataScopeMatch() {
        TrustedExecutionContext publisher = BuilderFixtures.context(
                BuilderFixtures.TENANT, BuilderFixtures.PROGRAM,
                EnumSet.of(CampaignPermission.CAMPAIGN_PUBLISH));

        assertThat(service.authorize(request(publisher, ResourceKind.CAMPAIGN_PUBLISH)).allowed()).isTrue();
    }

    @Test
    void aForeignCampaignPublishResourceIsANonDisclosingNotFound() {
        TrustedExecutionContext publisher = BuilderFixtures.context();
        AuthorizationDecision decision = service.authorize(new AuthorizationRequest(
                publisher,
                new OwnedResourceRef(ResourceKind.CAMPAIGN_PUBLISH, "campaign-1", "tenant-999", "program-001")));

        assertThat(decision.disposition()).isEqualTo(AuthorizationDisposition.NOT_FOUND);
        assertThat(decision.reason()).isEqualTo(AuthorizationReason.CROSS_TENANT_RESOURCE);
    }

    @Test
    void aMissingDataScopeDecisionFailsThePublishDecisionClosed() {
        FailClosedAuthorizationService withoutDataScope = new FailClosedAuthorizationService();
        assertThat(withoutDataScope.authorize(request(BuilderFixtures.context(), ResourceKind.CAMPAIGN_PUBLISH))
                .allowed()).isFalse();
        assertThat(withoutDataScope
                .authorize(request(BuilderFixtures.context(), ResourceKind.CAMPAIGN_PUBLISH))
                .disposition()).isEqualTo(AuthorizationDisposition.DATASCOPE_UNAVAILABLE);
    }

    @Test
    void requireAuthorizedRaisesTheBoundedPermissionFailure() {
        TrustedExecutionContext editOnly = BuilderFixtures.context(
                BuilderFixtures.TENANT, BuilderFixtures.PROGRAM,
                EnumSet.of(CampaignPermission.CAMPAIGN_EDIT));

        assertThatThrownBy(() -> service.requireAuthorized(
                request(editOnly, ResourceKind.CAMPAIGN_PUBLISH)))
                .isInstanceOf(PermissionDeniedException.class);
    }

    private static AuthorizationRequest request(TrustedExecutionContext context, ResourceKind kind) {
        return new AuthorizationRequest(
                context,
                new OwnedResourceRef(kind, "campaign-1", context.tenantId(), context.programId()));
    }
}