package com.company.campaign.builder.assembly;

import com.company.campaign.builder.assembly.testsupport.CampaignAssemblyFixtures;
import com.company.campaign.builder.campaignagent.CampaignMessageInputs;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T39B1: the typed inline Message preview Tool is a closed, pure, deterministic Tool-contract slice that
 * revalidates the canonical channel and the exact bounded body and publishes only safe evidence.
 *
 * <p>The concrete copy below is test data only: it proves the Tool preserves an arbitrary user-authored body
 * byte-for-byte and that no production source carries the wording itself.
 */
class MessageInlineToolTest {

    /** The exact user-authored copy of the T39 acceptance scenario; permitted in tests only. */
    private static final String ACCEPTANCE_COPY = "通知客户店里有8折优惠";

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private static final Path ASSEMBLY_SOURCES =
            Path.of("src", "main", "java", "com", "company", "campaign", "builder", "assembly");

    private static CampaignToolRequest.MessageInlinePreview inline(String body) {
        return new CampaignToolRequest.MessageInlinePreview(CampaignMessageInputs.Channel.SMS, body);
    }

    private static void assertRefused(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(CampaignAssemblyException.class,
                failure -> assertThat(failure.code())
                        .isEqualTo(CampaignAssemblyErrorCodes.TOOL_ARGUMENT_INVALID));
    }

    private static ContentHash inlineEvidence(CampaignMessageInputs.Channel channel) {
        CampaignToolInvoker invoker = CampaignAssemblyFixtures.messageInvoker();
        invoker.invoke(CampaignAssemblyFixtures.call("call-1",
                new CampaignToolRequest.MessageDiscover(null, 64)));
        CampaignToolAdmission admission = invoker.invoke(CampaignAssemblyFixtures.call("call-2",
                new CampaignToolRequest.MessageInlinePreview(channel, ACCEPTANCE_COPY)));
        return admission.result().evidenceHash();
    }

    // ------------------------------------------------------------------ catalog

    @Test
    void theInlinePreviewToolIsDeclaredInTheAcceptedCatalog() {
        CampaignToolCatalog catalog = CampaignToolCatalog.standard();
        CampaignToolDescriptor descriptor =
                catalog.descriptor(CampaignToolIds.MESSAGE_INLINE_PREVIEW_SAFE);

        assertThat(CampaignToolIds.isImplemented(CampaignToolIds.MESSAGE_INLINE_PREVIEW_SAFE)).isTrue();
        assertThat(CampaignToolIds.isKnown(CampaignToolIds.MESSAGE_INLINE_PREVIEW_SAFE)).isTrue();
        assertThat(descriptor).isNotNull();
        assertThat(descriptor.toolVersion()).isEqualTo("1.0");
        assertThat(descriptor.owningSkillId()).isEqualTo(CampaignSkillIds.MESSAGE_TEMPLATE);
        assertThat(descriptor.kind()).isEqualTo(CampaignToolKind.PREVIEW);
        assertThat(descriptor.purposeCode()).isEqualTo("MESSAGE_INLINE_SAFE_PREVIEW");
        assertThat(descriptor.modelCallable()).isTrue();
        assertThat(descriptor.available()).isTrue();
        assertThat(catalog.ownedBy(CampaignSkillIds.MESSAGE_TEMPLATE))
                .extracting(CampaignToolDescriptor::toolId)
                .contains("message.template.discover", "message.template.resolve",
                        "message.variables.validate", "message.preview.safe",
                        CampaignToolIds.MESSAGE_INLINE_PREVIEW_SAFE);
        assertThat(catalog.catalogVersion()).isEqualTo("campaign-tool-catalog-4.0");
        assertThat(catalog.catalogHash()).isEqualTo(catalog.recomputeCatalogHash());
    }

    // ------------------------------------------------------------------ typed request

    @Test
    void theTypedRequestCarriesOnlyTheCanonicalChannelAndTheExactBody() {
        CampaignToolRequest.MessageInlinePreview request = inline(ACCEPTANCE_COPY);

        assertThat(request.toolId()).isEqualTo(CampaignToolIds.MESSAGE_INLINE_PREVIEW_SAFE);
        assertThat(request.channel()).isEqualTo(CampaignMessageInputs.Channel.SMS);
        assertThat(request.body()).isEqualTo(ACCEPTANCE_COPY);
        assertThat(request.canonicalArguments()).containsOnlyKeys("channel", "body");
        assertThat(request.canonicalArguments())
                .containsEntry("channel", "SMS")
                .containsEntry("body", ACCEPTANCE_COPY);
    }

    @Test
    void everyDeclaredChannelTravelsAsItsCanonicalMachineCode() {
        for (CampaignMessageInputs.Channel channel : CampaignMessageInputs.Channel.values()) {
            assertThat(new CampaignToolRequest.MessageInlinePreview(channel, "copy").canonicalArguments())
                    .hasSize(2)
                    .containsEntry("channel", channel.name())
                    .containsEntry("body", "copy");
        }
    }

    @Test
    void anAbsentEmptyOrOversizedChannelOrBodyIsRefusedAtTheRequestBoundary() {
        assertRefused(() -> new CampaignToolRequest.MessageInlinePreview(null, "copy"));
        assertRefused(() -> new CampaignToolRequest.MessageInlinePreview(
                CampaignMessageInputs.Channel.EMAIL, null));
        assertRefused(() -> new CampaignToolRequest.MessageInlinePreview(
                CampaignMessageInputs.Channel.EMAIL, ""));
        assertRefused(() -> new CampaignToolRequest.MessageInlinePreview(
                CampaignMessageInputs.Channel.EMAIL, "a".repeat(CampaignMessageInputs.MAX_BODY_LENGTH + 1)));

        // The exact maximum length is accepted: the boundary is inclusive, not a silent truncation.
        String maximal = "a".repeat(CampaignMessageInputs.MAX_BODY_LENGTH);
        assertThat(new CampaignToolRequest.MessageInlinePreview(
                CampaignMessageInputs.Channel.EMAIL, maximal).body()).isEqualTo(maximal);
    }

    // ------------------------------------------------------------------ handler

    @Test
    void theHandlerIsPureDeterministicAndNeedsNoRunEvidence() {
        CampaignToolHandler handler = StandardCampaignToolHandlers.all()
                .get(CampaignToolIds.MESSAGE_INLINE_PREVIEW_SAFE);
        assertThat(handler).isNotNull();

        // The one Message handler that reads no catalog, pack, policy, trusted context or fixture: an
        // evidence-free run context is enough.
        CampaignToolRunContext noEvidence = new CampaignToolRunContext() {
        };
        CampaignToolRequest request = new CampaignToolRequest.MessageInlinePreview(
                CampaignMessageInputs.Channel.EMAIL, ACCEPTANCE_COPY);

        CampaignToolExecution first = handler.execute(noEvidence, request);
        CampaignToolExecution second = handler.execute(noEvidence, request);

        assertThat(first.disposition()).isEqualTo(Disposition.RESOLVED);
        assertThat(first).isEqualTo(second);
        assertThat(first.outcome().toolId()).isEqualTo(CampaignToolIds.MESSAGE_INLINE_PREVIEW_SAFE);
        assertThat(first.outcome().outcomeCode()).isEqualTo("MESSAGE_INLINE_PREVIEW_READY");
        assertThat(first.outcome().itemCount()).isEqualTo(1);

        CampaignToolArtifact.MessageInlineSafePreview artifact =
                (CampaignToolArtifact.MessageInlineSafePreview) first.artifact();
        assertThat(artifact.channel()).isEqualTo("EMAIL");
        assertThat(artifact.body()).isEqualTo(ACCEPTANCE_COPY);
        assertThat(first.outcome().evidenceHash()).isEqualTo(artifact.contentHash());
        assertThat(first.outcome().stableReferences()).containsExactly(artifact.componentCode());
    }

    @Test
    void theArtifactPreservesTheExactBodyAndDerivesDeterministicHashesAndIdentity() {
        CampaignToolArtifact.MessageInlineSafePreview base =
                CampaignToolArtifact.MessageInlineSafePreview.of("SMS", ACCEPTANCE_COPY);
        CampaignToolArtifact.MessageInlineSafePreview again =
                CampaignToolArtifact.MessageInlineSafePreview.of("SMS", ACCEPTANCE_COPY);
        CampaignToolArtifact.MessageInlineSafePreview otherBody =
                CampaignToolArtifact.MessageInlineSafePreview.of("SMS", ACCEPTANCE_COPY + "!");
        CampaignToolArtifact.MessageInlineSafePreview otherChannel =
                CampaignToolArtifact.MessageInlineSafePreview.of("EMAIL", ACCEPTANCE_COPY);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("channel", "SMS");
        payload.put("body", ACCEPTANCE_COPY);

        assertThat(base).isEqualTo(again);
        assertThat(base.body()).isEqualTo(ACCEPTANCE_COPY);
        assertThat(base.bodyHash()).isEqualTo(CANONICAL_JSON.hash(ACCEPTANCE_COPY));
        assertThat(base.contentHash()).isEqualTo(CANONICAL_JSON.hash(payload));
        assertThat(base.publicEvidenceHash()).isEqualTo(base.contentHash());
        assertThat(base.componentCode()).isEqualTo(again.componentCode());
        assertThat(base.componentCode()).startsWith("msg-inline-");
        assertThat(base.componentCode()).matches("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");

        // The body hash covers the body alone; the content hash and identity also bind the channel.
        assertThat(base.bodyHash()).isEqualTo(otherChannel.bodyHash());
        assertThat(base.contentHash()).isNotEqualTo(otherChannel.contentHash());
        assertThat(base.contentHash()).isNotEqualTo(otherBody.contentHash());
        assertThat(base.componentCode()).isNotEqualTo(otherBody.componentCode());
    }

    @Test
    void aForgedArtifactCannotClaimHashesThatDoNotCoverItsOwnContent() {
        CampaignToolArtifact.MessageInlineSafePreview genuine =
                CampaignToolArtifact.MessageInlineSafePreview.of("SMS", "copy");

        assertThatThrownBy(() -> new CampaignToolArtifact.MessageInlineSafePreview(
                "SMS", "other body", genuine.bodyHash(), genuine.contentHash(), genuine.componentCode()))
                .isInstanceOfSatisfying(CampaignAssemblyException.class, failure -> assertThat(failure.code())
                        .isEqualTo(CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT));
    }

    // ------------------------------------------------------------------ admission and evidence

    @Test
    void theAdmittedInlinePreviewPreservesTheBodyPrivatelyAndPublishesOnlySafeEvidence() {
        CampaignToolInvoker invoker = CampaignAssemblyFixtures.messageInvoker();
        invoker.invoke(CampaignAssemblyFixtures.call("call-1",
                new CampaignToolRequest.MessageDiscover(null, 64)));
        CampaignToolAdmission admission = invoker.invoke(CampaignAssemblyFixtures.call("call-2",
                new CampaignToolRequest.MessageInlinePreview(CampaignMessageInputs.Channel.PUSH, ACCEPTANCE_COPY)));

        assertThat(admission.resolved()).isTrue();
        assertThat(admission.toolId()).isEqualTo(CampaignToolIds.MESSAGE_INLINE_PREVIEW_SAFE);
        assertThat(admission.result().outcomeCode()).isEqualTo("MESSAGE_INLINE_PREVIEW_READY");

        CampaignToolArtifact artifact = admission.claim("call-2",
                CampaignToolIds.MESSAGE_INLINE_PREVIEW_SAFE, CampaignToolCatalog.standard().catalogHash());
        assertThat(artifact).isInstanceOf(CampaignToolArtifact.MessageInlineSafePreview.class);
        CampaignToolArtifact.MessageInlineSafePreview preview =
                (CampaignToolArtifact.MessageInlineSafePreview) artifact;
        assertThat(preview.body()).isEqualTo(ACCEPTANCE_COPY);
        assertThat(preview.channel()).isEqualTo("PUSH");

        // The public result publishes the hashes and identity only: never the body, and never a claim that a
        // Provider persisted the draft or that it is sendable.
        String publicText = CANONICAL_JSON.writeString(admission.result()) + admission.envelope();
        assertThat(publicText).doesNotContain(ACCEPTANCE_COPY);
        assertThat(publicText.toLowerCase()).doesNotContain("persist");
        assertThat(publicText.toLowerCase()).doesNotContain("sendable");
        assertThat(publicText.toLowerCase()).doesNotContain("published");
        assertThat(artifact.toString()).doesNotContain(ACCEPTANCE_COPY);
    }

    @Test
    void twoIndependentRunsOfTheSameInlineDraftPublishTheSameEvidence() {
        assertThat(inlineEvidence(CampaignMessageInputs.Channel.SMS))
                .isEqualTo(inlineEvidence(CampaignMessageInputs.Channel.SMS))
                .isNotEqualTo(inlineEvidence(CampaignMessageInputs.Channel.EMAIL));
    }

    // ------------------------------------------------------------------ generalization rule

    @Test
    void theInlineProductionSourcesCarryNoConcreteAcceptanceCopy() throws IOException {
        for (String file : List.of(
                "CampaignToolIds.java", "CampaignToolCatalog.java", "CampaignToolRequest.java",
                "CampaignToolArtifact.java", "StandardCampaignToolHandlers.java")) {
            String source = Files.readString(ASSEMBLY_SOURCES.resolve(file), StandardCharsets.UTF_8);
            assertThat(source).as(file)
                    .doesNotContain(ACCEPTANCE_COPY)
                    .doesNotContain("8折")
                    .doesNotContain("通知");
        }
    }
}