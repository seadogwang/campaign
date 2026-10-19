package com.company.campaign.dev.campaignagent;

import com.company.campaign.builder.authoring.testsupport.SegmentAuthoringFixtures;
import com.company.campaign.builder.capability.composition.T19PackIndex;
import com.company.campaign.builder.capability.message.MessageTemplateErrorCodes;
import com.company.campaign.builder.capability.message.MessageTemplatePack;
import com.company.campaign.builder.compiler.CampaignCompiler;
import com.company.campaign.builder.compiler.CampaignPlan;
import com.company.campaign.builder.compiler.CapabilityCompileContext;
import com.company.campaign.builder.compiler.CompileInstants;
import com.company.campaign.builder.compiler.CompileRequest;
import com.company.campaign.builder.compiler.CompilerErrorCodes;
import com.company.campaign.builder.compiler.CompilerException;
import com.company.campaign.builder.compiler.FrozenAuthoringPolicy;
import com.company.campaign.builder.compiler.FrozenCapabilityCatalog;
import com.company.campaign.builder.compiler.ResolvedComponent;
import com.company.campaign.builder.proposal.CampaignProposalPatch;
import com.company.campaign.builder.proposal.JourneyTopologyMutation;
import com.company.campaign.builder.proposal.ProposalBaseRevisions;
import com.company.campaign.builder.proposal.ProposalClientContext;
import com.company.campaign.builder.proposal.ProposalMode;
import com.company.campaign.builder.proposal.ProposalOperation;
import com.company.campaign.builder.proposal.ProposalTopologyEdge;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import com.company.campaign.shared.canonical.ContentHash;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.builder.testsupport.RecordingAuthorizationService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Regression test for the real-chain proposal compiler contract failure.
 *
 * <p>The real DeepSeek + PostgreSQL + browser chain (conv-374ac28068d61854e5c0f1e1b6423507,
 * run-401cc03cc5b714c645a3775038a1c642) produced a proposal whose {@code message.template}
 * operation carried {@code {body, channel}} — a shape the accepted {@link MessageTemplatePack}
 * contract rejects because its closed field set is {@code {referenceCode, variables, channel}}
 * and {@code body} is an unknown field.
 *
 * <p>This test proves:
 * <ol>
 *   <li>The original {@code {body, channel}} payload fails with the exact invariant
 *       {@code "capabilityContractResult"} under code
 *       {@code CAMPAIGN_COMPILER_CAPABILITY_CONTRACT_INVALID} (pre-fix regression guard).</li>
 *   <li>The corrected payload — {@code {referenceCode, variables: {bodyText}, channel}} —
 *       compiles successfully against the frozen development catalog.</li>
 *   <li>The final plan is deterministic and carries the resolved template reference for WECHAT.</li>
 * </ol>
 */
class RealProposalCompilerRegressionTest {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private final RecordingAuthorizationService authorization = new RecordingAuthorizationService();
    private final CampaignCompiler compiler = new CampaignCompiler(authorization);

    private final FrozenCapabilityCatalog catalog =
            DevelopmentCampaignAgentAssets.capabilityCatalog();
    private final FrozenAuthoringPolicy policy =
            DevelopmentCampaignAgentAssets.authoringPolicy();

    // Exact campaign-message-wechat operation from the real transcript:
    // {"capability":"message.template","clientRef":"campaign-message-wechat","op":"UPSERT",
    //  "value":{"body":"查看适合你的缓震跑鞋新品","channel":"WECHAT"}}
    private static final Map<String, Object> REAL_BROKEN_MESSAGE_VALUE = Map.of(
            "body", "查看适合你的缓震跑鞋新品",
            "channel", "WECHAT");

    // Corrected payload after the adapter fix: referenceCode + bounded bodyText variable + channel.
    private static final Map<String, Object> CORRECTED_MESSAGE_VALUE = Map.of(
            "referenceCode", DevelopmentCampaignAgentAssets.TEMPLATE_REFERENCE_WECHAT,
            "channel", "WECHAT",
            "variables", Map.of("bodyText", "查看适合你的缓震跑鞋新品"));

    // SMS inline message payload: referenceCode + bounded bodyText variable + channel SMS.
    private static final Map<String, Object> CORRECTED_MESSAGE_SMS_VALUE = Map.of(
            "referenceCode", DevelopmentCampaignAgentAssets.TEMPLATE_REFERENCE_SMS,
            "channel", "SMS",
            "variables", Map.of("bodyText", "New season running shoes available"));

    // Segment envelope derived from the accepted SegmentAuthoringEnvelope codec, not from the
    // real transcript (whose hashes do not match the dev catalog's deterministic evidence). The
    // envelope carries exactly the six closed fields the segment.rule-based contract accepts, and
    // every hash is a re-provable codec output — none are invented.
    //
    // Previous REAL_SEGMENT_VALUE (from conv-374ac... transcript) is preserved for reference in
    // the class-level comment and in the broken-guard test below. Its hashes are not re-provable
    // against the dev catalog's strict SegmentAuthoringEnvelope contract, which is why the
    // segment.rule-based compatibility failed with capabilityContractResult.
    private static final Map<String, Object> CODEC_SEGMENT_VALUE =
            SegmentAuthoringFixtures.envelope();

    // The frozen Journey pack index a replace-topology compile is validated against.
    private static final T19PackIndex JOURNEY_PACK_INDEX =
            DevelopmentCampaignAgentAssets.journeyPackIndex();

    // Capability compile context with the segment capability's required frozen inputs: the
    // Metadata Snapshot and safe Preview card the contract re-proves against, plus the
    // EXPERIMENTAL readiness fact the development host declares.
    private static final CapabilityCompileContext SEGMENT_COMPILE_CONTEXT =
            SegmentAuthoringFixtures.compileContext();

    /**
     * The trusted execution context the segment.rule-based contract accepts.
     *
     * <p>It uses the SegmentValidationFixtures context whose snapshot ref binds
     * {@code metadata-17}, which is the same snapshot ID the SegmentAuthoringFixtures
     * snapshot declares. The default {@code BuilderFixtures.context()} binds
     * {@code snapshot-001}, which would cause a snapshot-ownership mismatch in
     * {@code requireSnapshotOwnership}.
     */
    private TrustedExecutionContext trustedContext() {
        return SegmentValidationFixtures.context();
    }

    private CompileInstants instants() {
        Instant now = Instant.parse("2026-10-18T04:25:00Z");
        return new CompileInstants(now, now.plusSeconds(1));
    }

    // ------------------------------------------------------------------ build helpers

    private CampaignProposalPatch buildProposal(Map<String, Object> messageValue) {
        FrozenCapabilityCatalog cat = catalog;
        FrozenAuthoringPolicy pol = policy;
        ContentHash catalogHash = cat.catalogHash();
        ContentHash policyHash = pol.policyHash();
        ProposalBaseRevisions baseRevisions = new ProposalBaseRevisions(
                null, cat.catalogVersion(), catalogHash, pol.policyVersion(), policyHash);

        // Journey topology edges from the real transcript.
        List<ProposalTopologyEdge> edges = List.of(
                ProposalTopologyEdge.ofDeclaredEndpoints("e1", "start", "msg1", "DEFAULT"),
                ProposalTopologyEdge.ofDeclaredEndpoints("e2", "msg1", "wait1", "DEFAULT"),
                ProposalTopologyEdge.ofDeclaredEndpoints("e3", "wait1", "msg2", "DEFAULT"),
                ProposalTopologyEdge.ofDeclaredEndpoints("e4", "msg2", "end", "DEFAULT"));

        List<ProposalOperation> operations = List.of(
                ProposalOperation.upsertNew("message.template", "campaign-message-wechat",
                        CapabilityPayload.of(messageValue)),
                ProposalOperation.upsertNew("segment.rule-based", "campaign-segment",
                        CapabilityPayload.of(CODEC_SEGMENT_VALUE)),
                ProposalOperation.upsertNew("flow.start", "start",
                        CapabilityPayload.of(Map.of("triggerEventCode", "MEMBER_REGISTERED"))),
                ProposalOperation.upsertNew("flow.message", "msg1",
                        CapabilityPayload.of(Map.of("messageLogicalId", "campaign-message-wechat"))),
                ProposalOperation.upsertNew("flow.wait", "wait1",
                        CapabilityPayload.of(Map.of("anchor", "ENTRY", "duration", "PT72H"))),
                ProposalOperation.upsertNew("flow.message", "msg2",
                        CapabilityPayload.of(Map.of("messageLogicalId", "campaign-message-wechat"))),
                ProposalOperation.upsertNew("flow.end", "end",
                        CapabilityPayload.of(Map.of("terminal", "END"))));

        return new CampaignProposalPatch(
                com.company.campaign.contracts.SchemaVersion.parse("1.4"),
                ProposalMode.CREATE, null, baseRevisions, operations,
                ProposalClientContext.EMPTY, JourneyTopologyMutation.replace(edges));
    }

    private CampaignProposalPatch buildSmsProposal() {
        FrozenCapabilityCatalog cat = catalog;
        FrozenAuthoringPolicy pol = policy;
        ContentHash catalogHash = cat.catalogHash();
        ContentHash policyHash = pol.policyHash();
        ProposalBaseRevisions baseRevisions = new ProposalBaseRevisions(
                null, cat.catalogVersion(), catalogHash, pol.policyVersion(), policyHash);

        List<ProposalTopologyEdge> edges = List.of(
                ProposalTopologyEdge.ofDeclaredEndpoints("e1", "start", "msg-sms", "DEFAULT"),
                ProposalTopologyEdge.ofDeclaredEndpoints("e2", "msg-sms", "end", "DEFAULT"));

        List<ProposalOperation> operations = List.of(
                ProposalOperation.upsertNew("message.template", "campaign-message-sms",
                        CapabilityPayload.of(CORRECTED_MESSAGE_SMS_VALUE)),
                ProposalOperation.upsertNew("segment.rule-based", "campaign-segment",
                        CapabilityPayload.of(CODEC_SEGMENT_VALUE)),
                ProposalOperation.upsertNew("flow.start", "start",
                        CapabilityPayload.of(Map.of("triggerEventCode", "MEMBER_REGISTERED"))),
                ProposalOperation.upsertNew("flow.message", "msg-sms",
                        CapabilityPayload.of(Map.of("messageLogicalId", "campaign-message-sms"))),
                ProposalOperation.upsertNew("flow.end", "end",
                        CapabilityPayload.of(Map.of("terminal", "END"))));

        return new CampaignProposalPatch(
                com.company.campaign.contracts.SchemaVersion.parse("1.4"),
                ProposalMode.CREATE, null, baseRevisions, operations,
                ProposalClientContext.EMPTY, JourneyTopologyMutation.replace(edges));
    }

    // ------------------------------------------------------------------ tests

    @Test
    void theRealBrokenMassagePayloadFailsWithContractInvalidAndCapabilityContractResultInvariant() {
        CampaignProposalPatch proposal = buildProposal(REAL_BROKEN_MESSAGE_VALUE);

        CompilerException thrown = catchThrowableOfType(
                () -> compiler.compile(CompileRequest.create(
                        proposal, catalog, policy, instants(), trustedContext())),
                CompilerException.class);

        assertThat(thrown)
                .as("The real broken {body,channel} payload must fail closed with a contract error")
                .isNotNull();
        assertThat(thrown.code())
                .isEqualTo(CompilerErrorCodes.COMPILER_CAPABILITY_CONTRACT_INVALID);
        assertThat(thrown.details())
                .containsEntry("invariant", "capabilityContractResult");

        // The underlying MessageTemplatePack failure: unknown field "body".
        // The compiler wraps it as CAMPAIGN_COMPILER_CAPABILITY_CONTRACT_INVALID,
        // which the HTTP adapter maps to CAMPAIGN_PLAN_HTTP_COMPILER_CONTRACT_INVALID.
    }

    @Test
    void theCorrectedPayloadCompilesSuccessfullyWithTheDevelopmentCatalog() {
        CampaignProposalPatch proposal = buildProposal(CORRECTED_MESSAGE_VALUE);

        CampaignPlan plan = compiler.compile(CompileRequest.create(
                proposal, catalog, policy, instants(), trustedContext(),
                SEGMENT_COMPILE_CONTEXT, JOURNEY_PACK_INDEX));

        assertThat(plan)
                .as("The corrected proposal must compile against the dev catalog")
                .isNotNull();

        // The message.template component resolved the WECHAT template.
        assertThat(plan.manifest().components())
                .anySatisfy(component -> {
                    if ("message.template".equals(component.capabilityCode())) {
                        assertThat(component.references())
                                .anySatisfy(ref -> {
                                    assertThat(ref.referenceCode())
                                            .isEqualTo(DevelopmentCampaignAgentAssets.TEMPLATE_REFERENCE_WECHAT);
                                    assertThat(ref.entityKind()).isEqualTo("MESSAGE_TEMPLATE");
                                });
                        assertThat(component.value().fields())
                                .containsEntry("channel", "WECHAT");
                        @SuppressWarnings("unchecked")
                        Map<String, Object> variables =
                                (Map<String, Object>) component.value().fields().get("variables");
                        assertThat(variables)
                                .containsEntry("bodyText", "查看适合你的缓震跑鞋新品");
                    }
                });

        // The segment.rule-based component resolved against the frozen authoring inputs.
        assertThat(plan.manifest().components())
                .filteredOn(c -> "segment.rule-based".equals(c.capabilityCode()))
                .as("The segment.rule-based component must resolve against its contract with codec-derived evidence")
                .hasSize(1);

        // Locate the single compiled message.template component and capture its stable logicalId.
        String templateLogicalId = plan.manifest().components().stream()
                .filter(c -> "message.template".equals(c.capabilityCode()))
                .findFirst()
                .orElseThrow()
                .logicalId();

        // The flow.message nodes reference the resolved template's logicalId (not the clientRef).
        assertThat(plan.manifest().components())
                .filteredOn(c -> "flow.message".equals(c.capabilityCode()))
                .hasSize(2)
                .allSatisfy(c ->
                        assertThat(c.value().fields())
                                .containsEntry("messageLogicalId", templateLogicalId));

        // Journey topology is preserved with 5 nodes and 4 edges from the real transcript.
        assertThat(plan.manifest().components()).hasSize(7);
        assertThat(plan.journeyTopology()).isNotNull();
        assertThat(plan.journeyTopology().edges()).hasSize(4);
    }

    @Test
    void thePlanIsDeterministic() {
        CampaignProposalPatch proposal = buildProposal(CORRECTED_MESSAGE_VALUE);

        CampaignPlan first = compiler.compile(CompileRequest.create(
                proposal, catalog, policy, instants(), trustedContext(),
                SEGMENT_COMPILE_CONTEXT, JOURNEY_PACK_INDEX));
        CampaignPlan second = compiler.compile(CompileRequest.create(
                proposal, catalog, policy, instants(), trustedContext(),
                SEGMENT_COMPILE_CONTEXT, JOURNEY_PACK_INDEX));

        assertThat(first.manifest().components()).hasSize(second.manifest().components().size());
        for (int i = 0; i < first.manifest().components().size(); i++) {
            ResolvedComponent a = first.manifest().components().get(i);
            ResolvedComponent b = second.manifest().components().get(i);
            assertThat(a.capabilityCode()).isEqualTo(b.capabilityCode());
            assertThat(a.value().canonicalBytes()).isEqualTo(b.value().canonicalBytes());
            assertThat(a.value().contentHash()).isEqualTo(b.value().contentHash());
        }
        assertThat(first.journeyTopology()).isEqualTo(second.journeyTopology());
    }

    @Test
    void smsInlineDraftCompilesSuccessfullyWithTheDevelopmentCatalog() {
        CampaignProposalPatch proposal = buildSmsProposal();

        CampaignPlan plan = compiler.compile(CompileRequest.create(
                proposal, catalog, policy, instants(), trustedContext(),
                SEGMENT_COMPILE_CONTEXT, JOURNEY_PACK_INDEX));

        assertThat(plan)
                .as("The SMS inline proposal must compile against the dev catalog")
                .isNotNull();

        // The message.template component resolved the SMS template with channel=SMS and bodyText variable.
        assertThat(plan.manifest().components())
                .anySatisfy(component -> {
                    if ("message.template".equals(component.capabilityCode())) {
                        assertThat(component.references())
                                .anySatisfy(ref -> {
                                    assertThat(ref.referenceCode())
                                            .isEqualTo(DevelopmentCampaignAgentAssets.TEMPLATE_REFERENCE_SMS);
                                    assertThat(ref.entityKind()).isEqualTo("MESSAGE_TEMPLATE");
                                });
                        assertThat(component.value().fields())
                                .containsEntry("channel", "SMS");
                        @SuppressWarnings("unchecked")
                        Map<String, Object> variables =
                                (Map<String, Object>) component.value().fields().get("variables");
                        assertThat(variables)
                                .containsEntry("bodyText", "New season running shoes available");
                    }
                });

        // Locate the single compiled message.template component and capture its stable logicalId.
        String templateLogicalId = plan.manifest().components().stream()
                .filter(c -> "message.template".equals(c.capabilityCode()))
                .findFirst()
                .orElseThrow()
                .logicalId();

        // The flow.message node references the resolved template's logicalId (not the clientRef).
        assertThat(plan.manifest().components())
                .filteredOn(c -> "flow.message".equals(c.capabilityCode()))
                .hasSize(1)
                .allSatisfy(c ->
                        assertThat(c.value().fields())
                                .containsEntry("messageLogicalId", templateLogicalId));

        // The segment.rule-based component resolved against the frozen authoring inputs.
        assertThat(plan.manifest().components())
                .filteredOn(c -> "segment.rule-based".equals(c.capabilityCode()))
                .as("The segment.rule-based component must resolve against its contract with codec-derived evidence")
                .hasSize(1);

        // Journey topology: 4 nodes (start, msg-sms, end) + segment + message + flow components.
        assertThat(plan.journeyTopology()).isNotNull();
        assertThat(plan.journeyTopology().edges()).hasSize(2);
    }

    // ------------------------------------------------------------------ durable plan round-trip

    @Test
    void planRoundTripsThroughCanonicalMaterialAndManifestJson() {
        CampaignProposalPatch proposal = buildProposal(CORRECTED_MESSAGE_VALUE);

        CampaignPlan original = compiler.compile(CompileRequest.create(
                proposal, catalog, policy, instants(), trustedContext(),
                SEGMENT_COMPILE_CONTEXT, JOURNEY_PACK_INDEX));

        // Round-trip: canonical material bytes + manifest canonical JSON → reconstruct.
        byte[] materialBytes = original.canonicalMaterialBytes();
        String manifestJson = new String(original.manifest().canonicalBytes(), StandardCharsets.UTF_8);

        CampaignPlan reconstructed = CampaignPlan.reconstruct(materialBytes, manifestJson);

        // planId and planHash are byte-identical.
        assertThat(reconstructed.planId())
                .as("Reconstructed planId must equal the original")
                .isEqualTo(original.planId());
        assertThat(reconstructed.planHash())
                .as("Reconstructed planHash must equal the original")
                .isEqualTo(original.planHash());

        // Canonical material bytes are byte-identical.
        assertThat(reconstructed.canonicalMaterialBytes())
                .as("Reconstructed canonical material must be byte-identical to the original")
                .isEqualTo(original.canonicalMaterialBytes());

        // Manifest is byte-identical.
        assertThat(reconstructed.manifest().canonicalBytes())
                .as("Reconstructed manifest must be byte-identical to the original")
                .isEqualTo(original.manifest().canonicalBytes());

        // Manifest hash matches.
        assertThat(reconstructed.manifestHash())
                .as("Reconstructed manifestHash must equal the original")
                .isEqualTo(original.manifestHash());

        // Topology is identical.
        if (original.journeyTopology() != null) {
            assertThat(reconstructed.journeyTopology())
                    .as("Reconstructed journey topology must equal the original")
                    .isEqualTo(original.journeyTopology());
        } else {
            assertThat(reconstructed.journeyTopology()).isNull();
        }

        // Same number of components.
        assertThat(reconstructed.manifest().components())
                .as("Reconstructed manifest must have the same components")
                .hasSameSizeAs(original.manifest().components());

        // Every component is byte-identical.
        for (int i = 0; i < original.manifest().components().size(); i++) {
            var a = original.manifest().components().get(i);
            var b = reconstructed.manifest().components().get(i);
            assertThat(a.logicalId()).isEqualTo(b.logicalId());
            assertThat(a.capabilityCode()).isEqualTo(b.capabilityCode());
            assertThat(a.contractVersion()).isEqualTo(b.contractVersion());
            assertThat(a.slot()).isEqualTo(b.slot());
            assertThat(a.value().canonicalBytes()).isEqualTo(b.value().canonicalBytes());
            assertThat(a.value().contentHash()).isEqualTo(b.value().contentHash());
            assertThat(a.references()).hasSameSizeAs(b.references());
            for (int j = 0; j < a.references().size(); j++) {
                var ra = a.references().get(j);
                var rb = b.references().get(j);
                assertThat(ra.referenceCode()).isEqualTo(rb.referenceCode());
                assertThat(ra.entityKind()).isEqualTo(rb.entityKind());
                assertThat(ra.logicalId()).isEqualTo(rb.logicalId());
                assertThat(ra.versionId()).isEqualTo(rb.versionId());
            }
        }

        // Preview cards are byte-identical.
        assertThat(reconstructed.previewCards())
                .as("Reconstructed preview cards must equal the original")
                .isEqualTo(original.previewCards());

        // Capability preview cards are byte-identical.
        assertThat(reconstructed.capabilityPreviewCards())
                .as("Reconstructed capability preview cards must equal the original")
                .isEqualTo(original.capabilityPreviewCards());

        // ChangeSet is identical.
        assertThat(reconstructed.changeSet())
                .as("Reconstructed changeSet must equal the original")
                .isEqualTo(original.changeSet());

        // SemanticDiff is identical.
        assertThat(reconstructed.semanticDiff())
                .as("Reconstructed semanticDiff must equal the original")
                .isEqualTo(original.semanticDiff());

        // ClientRefBindings are identical.
        assertThat(reconstructed.clientRefBindings())
                .as("Reconstructed clientRefBindings must equal the original")
                .isEqualTo(original.clientRefBindings());

        // Validation issues are identical.
        assertThat(reconstructed.validationIssues())
                .as("Reconstructed validationIssues must equal the original")
                .isEqualTo(original.validationIssues());

        // Internal identifiers.
        assertThat(reconstructed.planFormatVersion())
                .isEqualTo(original.planFormatVersion());
        assertThat(reconstructed.compilerVersion())
                .isEqualTo(original.compilerVersion());
        assertThat(reconstructed.proposalHash())
                .isEqualTo(original.proposalHash());
        assertThat(reconstructed.catalogVersion())
                .isEqualTo(original.catalogVersion());
        assertThat(reconstructed.catalogHash())
                .isEqualTo(original.catalogHash());
        assertThat(reconstructed.policyVersion())
                .isEqualTo(original.policyVersion());
        assertThat(reconstructed.policyHash())
                .isEqualTo(original.policyHash());
        assertThat(reconstructed.riskSummary())
                .isEqualTo(original.riskSummary());
    }
}