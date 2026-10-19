package com.company.campaign.builder.assembly;

import com.company.campaign.builder.assembly.testsupport.CampaignAssemblyFixtures;
import com.company.campaign.builder.capability.catalog.MessageTemplateVersion;
import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.contracts.Disposition;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 3: Message discovery, exact resolution, variable validation and safe preview run through the
 * accepted immutable catalog and capability pack.
 */
class MessageToolFlowTest {

    private static CampaignToolInvoker invoker() {
        return new CampaignToolInvoker(CampaignToolCatalog.standard(), CampaignSkillIds.MESSAGE_TEMPLATE,
                StandardCampaignToolHandlers.all(), CampaignAssemblyFixtures.runContext());
    }

    private static CapabilityPayload validVariables() {
        return CampaignAssemblyFixtures.variables("firstName", "Ada");
    }

    @Test
    void discoveryIsBoundedAndProjectsOnlySafeSummaries() {
        CampaignToolInvoker invoker = invoker();
        CampaignToolAdmission admission = invoker.invoke(CampaignAssemblyFixtures.call(
                "call-1", new CampaignToolRequest.MessageDiscover("tpl-welcome", 8)));

        assertThat(admission.resolved()).isTrue();
        CampaignToolArtifact artifact = admission.claim(
                "call-1", CampaignToolIds.MESSAGE_TEMPLATE_DISCOVER,
                CampaignToolCatalog.standard().catalogHash());
        assertThat(artifact).isInstanceOf(CampaignToolArtifact.MessageDiscovery.class);
        CampaignToolArtifact.MessageDiscovery discovery = (CampaignToolArtifact.MessageDiscovery) artifact;
        assertThat(discovery.matches()).hasSize(1);
        assertThat(discovery.matches().get(0).referenceCode()).isEqualTo(CampaignAssemblyFixtures.TEMPLATE_REFERENCE);
        assertThat(discovery.publicEvidenceHash()).isEqualTo(artifact.publicEvidenceHash());
    }

    @Test
    void resolutionPinsOneExactEnabledRuntimeCompatibleVersion() {
        CampaignToolInvoker invoker = invoker();
        invoker.invoke(CampaignAssemblyFixtures.call("call-1",
                new CampaignToolRequest.MessageDiscover(null, 64)));
        CampaignToolAdmission admission = invoker.invoke(CampaignAssemblyFixtures.call("call-2",
                new CampaignToolRequest.MessageResolve(CampaignAssemblyFixtures.TEMPLATE_REFERENCE)));

        assertThat(admission.resolved()).isTrue();
        CampaignToolArtifact.MessageResolution resolution = (CampaignToolArtifact.MessageResolution) admission.claim(
                "call-2", CampaignToolIds.MESSAGE_TEMPLATE_RESOLVE,
                CampaignToolCatalog.standard().catalogHash());
        MessageTemplateVersion version = resolution.version();
        assertThat(version.logicalId()).isEqualTo("tpl-welcome");
        assertThat(version.versionId()).isEqualTo("tpl-welcome-v1");
        assertThat(version.enabled()).isTrue();
        assertThat(version.runtimeAvailable()).isTrue();
    }

    @Test
    void anUnknownOrDisabledReferenceIsUnavailableRatherThanGuessed() {
        CampaignToolInvoker invoker = invoker();
        invoker.invoke(CampaignAssemblyFixtures.call("call-1",
                new CampaignToolRequest.MessageDiscover(null, 64)));
        CampaignToolAdmission unknown = invoker.invoke(CampaignAssemblyFixtures.call("call-2",
                new CampaignToolRequest.MessageResolve("tpl-absent")));
        assertThat(unknown.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(unknown.failure().code()).isEqualTo(CampaignAssemblyErrorCodes.TOOL_RESOURCE_UNAVAILABLE);

        CampaignToolInvoker second = invoker();
        second.invoke(CampaignAssemblyFixtures.call("call-1",
                new CampaignToolRequest.MessageDiscover(null, 64)));
        CampaignToolAdmission disabled = second.invoke(CampaignAssemblyFixtures.call("call-2",
                new CampaignToolRequest.MessageResolve("tpl-disabled")));
        assertThat(disabled.failure().code()).isEqualTo(CampaignAssemblyErrorCodes.TOOL_RESOURCE_UNAVAILABLE);
    }

    @Test
    void variableValidationAcceptsExactVariablesAndRefusesMissingOrExtraNames() {
        CampaignToolInvoker invoker = invoker();
        invoker.invoke(CampaignAssemblyFixtures.call("call-1",
                new CampaignToolRequest.MessageDiscover(null, 64)));
        invoker.invoke(CampaignAssemblyFixtures.call("call-2",
                new CampaignToolRequest.MessageResolve(CampaignAssemblyFixtures.TEMPLATE_REFERENCE)));
        CampaignToolAdmission accepted = invoker.invoke(CampaignAssemblyFixtures.call("call-3",
                new CampaignToolRequest.MessageVariablesValidate(
                        CampaignAssemblyFixtures.TEMPLATE_REFERENCE, validVariables())));
        assertThat(accepted.resolved()).isTrue();

        CampaignToolInvoker missing = invoker();
        missing.invoke(CampaignAssemblyFixtures.call("call-1",
                new CampaignToolRequest.MessageDiscover(null, 64)));
        missing.invoke(CampaignAssemblyFixtures.call("call-2",
                new CampaignToolRequest.MessageResolve(CampaignAssemblyFixtures.TEMPLATE_REFERENCE)));
        CampaignToolAdmission missingFirstName = missing.invoke(CampaignAssemblyFixtures.call("call-3",
                new CampaignToolRequest.MessageVariablesValidate(
                        CampaignAssemblyFixtures.TEMPLATE_REFERENCE, CapabilityPayload.empty())));
        assertThat(missingFirstName.failure().code())
                .isEqualTo(CampaignAssemblyErrorCodes.TOOL_VARIABLES_INVALID);

        CampaignToolInvoker extra = invoker();
        extra.invoke(CampaignAssemblyFixtures.call("call-1",
                new CampaignToolRequest.MessageDiscover(null, 64)));
        extra.invoke(CampaignAssemblyFixtures.call("call-2",
                new CampaignToolRequest.MessageResolve(CampaignAssemblyFixtures.TEMPLATE_REFERENCE)));
        CampaignToolAdmission extraName = extra.invoke(CampaignAssemblyFixtures.call("call-3",
                new CampaignToolRequest.MessageVariablesValidate(
                        CampaignAssemblyFixtures.TEMPLATE_REFERENCE,
                        CapabilityPayload.of(Map.of("firstName", "Ada", "unknown", "x")))));
        assertThat(extraName.failure().code())
                .isEqualTo(CampaignAssemblyErrorCodes.TOOL_VARIABLES_INVALID);
    }

    @Test
    void safePreviewProjectsCodesChannelAndHashesOnly() {
        CampaignToolInvoker invoker = invoker();
        invoker.invoke(CampaignAssemblyFixtures.call("call-1",
                new CampaignToolRequest.MessageDiscover(null, 64)));
        invoker.invoke(CampaignAssemblyFixtures.call("call-2",
                new CampaignToolRequest.MessageResolve(CampaignAssemblyFixtures.TEMPLATE_REFERENCE)));
        invoker.invoke(CampaignAssemblyFixtures.call("call-3",
                new CampaignToolRequest.MessageVariablesValidate(
                        CampaignAssemblyFixtures.TEMPLATE_REFERENCE, validVariables())));
        CampaignToolAdmission admission = invoker.invoke(CampaignAssemblyFixtures.call("call-4",
                new CampaignToolRequest.MessagePreviewSafe(
                        CampaignAssemblyFixtures.TEMPLATE_REFERENCE, validVariables())));

        assertThat(admission.resolved()).isTrue();
        CampaignToolArtifact.MessageSafePreview preview = (CampaignToolArtifact.MessageSafePreview) admission.claim(
                "call-4", CampaignToolIds.MESSAGE_PREVIEW_SAFE,
                CampaignToolCatalog.standard().catalogHash());
        MessageSafePreviewCard card = preview.card();
        assertThat(card.referenceCode()).isEqualTo(CampaignAssemblyFixtures.TEMPLATE_REFERENCE);
        assertThat(card.channel()).isEqualTo("EMAIL");
        assertThat(card.classificationCode()).isEqualTo("PUBLIC");
        assertThat(card.normalizedPayloadHash()).isEqualTo(preview.normalized().contentHash());
        assertThat(preview.publicEvidenceHash()).isEqualTo(card.normalizedPayloadHash());
        assertThat(admission.result().evidenceHash()).isEqualTo(card.normalizedPayloadHash());
    }
}