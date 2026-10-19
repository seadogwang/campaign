package com.company.campaign.builder.capability.message;

import com.company.campaign.builder.capability.CapabilityPlanPreviewCard;
import com.company.campaign.builder.capability.catalog.MessageClassification;
import com.company.campaign.builder.capability.catalog.MessageTemplateCatalog;
import com.company.campaign.builder.capability.catalog.MessageTemplateVariable;
import com.company.campaign.builder.capability.catalog.MessageTemplateVersion;
import com.company.campaign.builder.capability.pack.CapabilityValueKind;
import com.company.campaign.builder.compiler.CampaignCompiler;
import com.company.campaign.builder.compiler.CampaignPlan;
import com.company.campaign.builder.compiler.CompileRequest;
import com.company.campaign.builder.compiler.FrozenCapabilityCatalog;
import com.company.campaign.builder.compiler.FrozenAuthoringPolicy;
import com.company.campaign.builder.compiler.ResolvedComponent;
import com.company.campaign.builder.testsupport.BuilderFixtures;
import com.company.campaign.builder.testsupport.RecordingAuthorizationService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T19 sections 2 and 4: the concrete {@code message.template} pack through the accepted compiler.
 *
 * <p>The pack is an ordinary frozen capability contract, so a resolved component and its one bound generic
 * capability preview card reach the {@code CampaignPlan} with no compiler change at all.
 */
class MessageTemplatePackCompilerTest {

    private final RecordingAuthorizationService authorization = new RecordingAuthorizationService();
    private final CampaignCompiler compiler = new CampaignCompiler(authorization);
    private final FrozenAuthoringPolicy policy = BuilderFixtures.policy();

    private final MessageTemplatePack pack = new MessageTemplatePack(MessageTemplateCatalog.of("tpl-catalog-1",
            List.of(MessageTemplateVersion.of("tpl-welcome", "tpl-welcome", "tpl-welcome-v1", "EMAIL",
                    List.of(new MessageTemplateVariable("firstName", CapabilityValueKind.STRING, true, 16)),
                    true, true, MessageClassification.PUBLIC))));

    @Test
    void aResolvedMessageComponentAndItsBoundCardReachThePlan() {
        FrozenCapabilityCatalog catalog = BuilderFixtures.catalog(pack);
        CampaignPlan plan = compiler.compile(CompileRequest.create(
                BuilderFixtures.createProposal().pins(catalog, policy)
                        .upsertNew("message.template", "message-new",
                                Map.of("referenceCode", "tpl-welcome", "variables", Map.of("firstName", "Ada")))
                        .build(),
                catalog, policy, BuilderFixtures.instants(), BuilderFixtures.context()));

        assertThat(plan.manifest().components()).hasSize(1);
        ResolvedComponent component = plan.manifest().components().get(0);
        assertThat(component.capabilityCode()).isEqualTo("message.template");
        assertThat(component.contractVersion()).isEqualTo("1.0");
        assertThat(component.slot().code()).isEqualTo("campaign.message");
        assertThat(component.references()).containsExactly(new com.company.campaign.builder.compiler
                .ResolvedCatalogReference("tpl-welcome", "MESSAGE_TEMPLATE", "tpl-welcome", "tpl-welcome-v1"));

        assertThat(plan.capabilityPreviewCards()).hasSize(1);
        CapabilityPlanPreviewCard card = plan.capabilityPreviewCards().get(0);
        assertThat(card.componentLogicalId()).isEqualTo(component.logicalId());
        assertThat(card.capabilityCode()).isEqualTo("message.template");
        assertThat(card.contractVersion()).isEqualTo("1.0");
        assertThat(card.normalizedPayloadHash()).isEqualTo(component.value().contentHash());
        assertThat(card.runtimeCompatibility()).isEqualTo(pack.runtimeCompatibility());
        assertThat(card.hasResolvedCatalogReference()).isTrue();
        // The accepted Segment card path is untouched.
        assertThat(plan.previewCards()).isEmpty();
    }
}