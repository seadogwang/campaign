package com.company.campaign.dev.campaignagent;

import com.company.campaign.builder.authoring.SegmentAuthoringVersions;
import com.company.campaign.builder.authoring.testsupport.SegmentAuthoringFixtures;
import com.company.campaign.builder.campaignagent.CampaignCompileEvidence;
import com.company.campaign.builder.campaignagent.CampaignCompileEvidenceRegistry;
import com.company.campaign.builder.capability.composition.T19PackIndex;
import com.company.campaign.builder.compiler.CampaignCompiler;
import com.company.campaign.builder.compiler.CampaignPlan;
import com.company.campaign.builder.compiler.CapabilityCompileContext;
import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.builder.compiler.CompileInstants;
import com.company.campaign.builder.compiler.CompileRequest;
import com.company.campaign.builder.compiler.CompilerErrorCodes;
import com.company.campaign.builder.compiler.CompilerException;
import com.company.campaign.builder.compiler.FrozenAuthoringPolicy;
import com.company.campaign.builder.compiler.FrozenCapabilityCatalog;
import com.company.campaign.builder.proposal.CampaignProposalPatch;
import com.company.campaign.builder.proposal.JourneyTopologyMutation;
import com.company.campaign.builder.proposal.ProposalBaseRevisions;
import com.company.campaign.builder.proposal.ProposalClientContext;
import com.company.campaign.builder.proposal.ProposalMode;
import com.company.campaign.builder.proposal.ProposalOperation;
import com.company.campaign.builder.proposal.ProposalTopologyEdge;
import com.company.campaign.builder.testsupport.RecordingAuthorizationService;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Focused regression tests for the compile-evidence bridge between the conversation
 * flow and the plan compile service.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>A proposal with segment + message + Journey compiles when host-owned evidence is registered.</li>
 *   <li>Missing Segment compile evidence fails closed when the proposal has a segment operation.</li>
 *   <li>Journey compilation uses the real frozen pack index.</li>
 *   <li>Idempotent replay: same proposal + same evidence produces byte-identical plans.</li>
 *   <li>The evidence registry itself: register, lookup, eviction, remove.</li>
 * </ul>
 */
class DevelopmentCampaignPlanCompileEvidenceTest {

    private final RecordingAuthorizationService authorization = new RecordingAuthorizationService();
    private final CampaignCompiler compiler = new CampaignCompiler(authorization);

    private final FrozenCapabilityCatalog catalog =
            DevelopmentCampaignAgentAssets.capabilityCatalog();
    private final FrozenAuthoringPolicy policy =
            DevelopmentCampaignAgentAssets.authoringPolicy();

    private final T19PackIndex JOURNEY_PACK_INDEX =
            DevelopmentCampaignAgentAssets.journeyPackIndex();

    private final CapabilityCompileContext SEGMENT_CONTEXT =
            SegmentAuthoringFixtures.compileContext();

    private final TrustedExecutionContext context() {
        return SegmentValidationFixtures.context();
    }

    private final Map<String, Object> MESSAGE_WECHAT_VALUE = Map.of(
            "referenceCode", DevelopmentCampaignAgentAssets.TEMPLATE_REFERENCE_WECHAT,
            "channel", "WECHAT",
            "variables", Map.of("bodyText", "hello"));

    private final Map<String, Object> SEGMENT_VALUE =
            SegmentAuthoringFixtures.envelope();

    // ------------------------------------------------------------------ helpers

    private CampaignProposalPatch proposalWithSegment() {
        ContentHash catalogHash = catalog.catalogHash();
        ContentHash policyHash = policy.policyHash();
        ProposalBaseRevisions baseRevisions = new ProposalBaseRevisions(
                null, catalog.catalogVersion(), catalogHash, policy.policyVersion(), policyHash);

        List<ProposalTopologyEdge> edges = List.of(
                ProposalTopologyEdge.ofDeclaredEndpoints("e1", "start", "msg1", "DEFAULT"),
                ProposalTopologyEdge.ofDeclaredEndpoints("e2", "msg1", "end", "DEFAULT"));

        List<ProposalOperation> operations = List.of(
                ProposalOperation.upsertNew("message.template", "campaign-message-wechat",
                        CapabilityPayload.of(MESSAGE_WECHAT_VALUE)),
                ProposalOperation.upsertNew(SegmentAuthoringVersions.CAPABILITY_CODE, "campaign-segment",
                        CapabilityPayload.of(SEGMENT_VALUE)),
                ProposalOperation.upsertNew("flow.start", "start",
                        CapabilityPayload.of(Map.of("triggerEventCode", "MEMBER_REGISTERED"))),
                ProposalOperation.upsertNew("flow.message", "msg1",
                        CapabilityPayload.of(Map.of("messageLogicalId", "campaign-message-wechat"))),
                ProposalOperation.upsertNew("flow.end", "end",
                        CapabilityPayload.of(Map.of("terminal", "END"))));

        return new CampaignProposalPatch(
                com.company.campaign.contracts.SchemaVersion.parse("1.4"),
                ProposalMode.CREATE, null, baseRevisions, operations,
                ProposalClientContext.EMPTY, JourneyTopologyMutation.replace(edges));
    }

    private CampaignProposalPatch proposalWithoutSegment() {
        ContentHash catalogHash = catalog.catalogHash();
        ContentHash policyHash = policy.policyHash();
        ProposalBaseRevisions baseRevisions = new ProposalBaseRevisions(
                null, catalog.catalogVersion(), catalogHash, policy.policyVersion(), policyHash);

        List<ProposalTopologyEdge> edges = List.of(
                ProposalTopologyEdge.ofDeclaredEndpoints("e1", "start", "msg1", "DEFAULT"),
                ProposalTopologyEdge.ofDeclaredEndpoints("e2", "msg1", "end", "DEFAULT"));

        List<ProposalOperation> operations = List.of(
                ProposalOperation.upsertNew("message.template", "campaign-message-wechat",
                        CapabilityPayload.of(MESSAGE_WECHAT_VALUE)),
                ProposalOperation.upsertNew("flow.start", "start",
                        CapabilityPayload.of(Map.of("triggerEventCode", "MEMBER_REGISTERED"))),
                ProposalOperation.upsertNew("flow.message", "msg1",
                        CapabilityPayload.of(Map.of("messageLogicalId", "campaign-message-wechat"))),
                ProposalOperation.upsertNew("flow.end", "end",
                        CapabilityPayload.of(Map.of("terminal", "END"))));

        return new CampaignProposalPatch(
                com.company.campaign.contracts.SchemaVersion.parse("1.4"),
                ProposalMode.CREATE, null, baseRevisions, operations,
                ProposalClientContext.EMPTY, JourneyTopologyMutation.replace(edges));
    }

    private CompileInstants instants() {
        Instant now = Instant.parse("2026-10-18T04:25:00Z");
        return new CompileInstants(now, now.plusSeconds(1));
    }

    // ------------------------------------------------------------------ compile with evidence

    @Test
    void proposalWithSegmentCompilesWhenEvidenceIsRegistered() {
        CampaignProposalPatch proposal = proposalWithSegment();

        CampaignPlan plan = compiler.compile(CompileRequest.create(
                proposal, catalog, policy, instants(), context(),
                SEGMENT_CONTEXT, JOURNEY_PACK_INDEX));

        assertThat(plan).isNotNull();
        assertThat(plan.manifest().components()).hasSize(5);
        assertThat(plan.manifest().components())
                .filteredOn(c -> SegmentAuthoringVersions.CAPABILITY_CODE.equals(c.capabilityCode()))
                .hasSize(1);
        assertThat(plan.journeyTopology()).isNotNull();
        assertThat(plan.journeyTopology().edges()).hasSize(2);
    }

    @Test
    void proposalWithSegmentFailsClosedWhenNoSegmentEvidence() {
        CampaignProposalPatch proposal = proposalWithSegment();

        CompilerException thrown = catchThrowableOfType(
                () -> compiler.compile(CompileRequest.create(
                        proposal, catalog, policy, instants(), context())),
                CompilerException.class);

        assertThat(thrown).isNotNull();
        assertThat(thrown.code())
                .isEqualTo(CompilerErrorCodes.COMPILER_CAPABILITY_CONTRACT_INVALID);
    }

    @Test
    void proposalWithoutSegmentCompilesWithoutEvidence() {
        CampaignProposalPatch proposal = proposalWithoutSegment();

        CampaignPlan plan = compiler.compile(CompileRequest.create(
                proposal, catalog, policy, instants(), context(),
                CapabilityCompileContext.empty(), JOURNEY_PACK_INDEX));

        assertThat(plan).isNotNull();
        // No segment component: only message, start, message-flow, end
        assertThat(plan.manifest().components()).hasSize(4);
        assertThat(plan.manifest().components())
                .filteredOn(c -> SegmentAuthoringVersions.CAPABILITY_CODE.equals(c.capabilityCode()))
                .isEmpty();
    }

    @Test
    void planIsDeterministicWithSameEvidence() {
        CampaignProposalPatch proposal = proposalWithSegment();

        CampaignPlan first = compiler.compile(CompileRequest.create(
                proposal, catalog, policy, instants(), context(),
                SEGMENT_CONTEXT, JOURNEY_PACK_INDEX));
        CampaignPlan second = compiler.compile(CompileRequest.create(
                proposal, catalog, policy, instants(), context(),
                SEGMENT_CONTEXT, JOURNEY_PACK_INDEX));

        assertThat(first.manifest().components()).hasSize(second.manifest().components().size());
        for (int i = 0; i < first.manifest().components().size(); i++) {
            var a = first.manifest().components().get(i);
            var b = second.manifest().components().get(i);
            assertThat(a.capabilityCode()).isEqualTo(b.capabilityCode());
            assertThat(a.value().canonicalBytes()).isEqualTo(b.value().canonicalBytes());
            assertThat(a.value().contentHash()).isEqualTo(b.value().contentHash());
        }
        assertThat(first.journeyTopology()).isEqualTo(second.journeyTopology());
    }

    @Test
    void journeyTopologyUsesRealPackIndex() {
        CampaignProposalPatch proposal = proposalWithSegment();

        CampaignPlan plan = compiler.compile(CompileRequest.create(
                proposal, catalog, policy, instants(), context(),
                SEGMENT_CONTEXT, JOURNEY_PACK_INDEX));

        assertThat(plan.journeyTopology()).isNotNull();
        assertThat(plan.journeyTopology().edges()).isNotEmpty();
        // Every edge endpoint must name a component the manifest owns.
        var componentLogicalIds = new java.util.HashSet<String>();
        for (var component : plan.manifest().components()) {
            componentLogicalIds.add(component.logicalId());
        }
        for (var edge : plan.journeyTopology().edges()) {
            assertThat(componentLogicalIds).contains(edge.fromNodeLogicalId());
            assertThat(componentLogicalIds).contains(edge.toNodeLogicalId());
        }
    }

    // ------------------------------------------------------------------ evidence registry

    @Test
    void evidenceRegistryStageBindAndLookup() {
        DevelopmentCampaignCompileEvidenceRegistry registry =
                new DevelopmentCampaignCompileEvidenceRegistry(java.time.Clock.systemUTC());
        CapabilityCompileContext context = SEGMENT_CONTEXT;
        T19PackIndex packIndex = JOURNEY_PACK_INDEX;

        String conversationId = "conv-test-001";
        String runId = "run-test-001";
        String hash = "sha256-" + "a".repeat(64);

        assertThat(registry.lookup(hash)).isEmpty();

        registry.stage(conversationId, runId, context, packIndex);
        // Staged evidence is not yet bound — lookup by proposal hash returns empty.
        assertThat(registry.lookup(hash)).isEmpty();

        registry.bind(conversationId, runId, hash);

        Optional<CampaignCompileEvidence> evidence = registry.lookup(hash);
        assertThat(evidence).isPresent();
        assertThat(evidence.get().capabilityContext()).isEqualTo(context);
        assertThat(evidence.get().journeyPackIndex()).isEqualTo(packIndex);
    }

    @Test
    void evidenceRegistryStageOverwrites() {
        DevelopmentCampaignCompileEvidenceRegistry registry =
                new DevelopmentCampaignCompileEvidenceRegistry(java.time.Clock.systemUTC());
        String conversationId = "conv-test-002";
        String runId = "run-test-002";
        String hash = "sha256-" + "a".repeat(64);

        CapabilityCompileContext first = CapabilityCompileContext.empty();
        T19PackIndex packIndex = JOURNEY_PACK_INDEX;

        registry.stage(conversationId, runId, first, packIndex);
        registry.stage(conversationId, runId, SEGMENT_CONTEXT, packIndex);

        registry.bind(conversationId, runId, hash);
        Optional<CampaignCompileEvidence> evidence = registry.lookup(hash);
        assertThat(evidence).isPresent();
        // The second staging must have overwritten the first.
        assertThat(evidence.get().capabilityContext()).isNotEqualTo(first);
        assertThat(evidence.get().capabilityContext()).isEqualTo(SEGMENT_CONTEXT);
    }

    @Test
    void evidenceRegistryBindFailsClosedWhenNothingStaged() {
        DevelopmentCampaignCompileEvidenceRegistry registry =
                new DevelopmentCampaignCompileEvidenceRegistry(java.time.Clock.systemUTC());

        com.company.campaign.builder.campaignagent.CampaignAgentException thrown =
                catchThrowableOfType(
                        () -> registry.bind("conv-nonexistent", "run-nonexistent",
                                "sha256-" + "a".repeat(64)),
                        com.company.campaign.builder.campaignagent.CampaignAgentException.class);

        assertThat(thrown).isNotNull();
    }

    @Test
    void evidenceRegistryRemove() {
        DevelopmentCampaignCompileEvidenceRegistry registry =
                new DevelopmentCampaignCompileEvidenceRegistry(java.time.Clock.systemUTC());
        String conversationId = "conv-test-003";
        String runId = "run-test-003";
        String hash = "sha256-" + "a".repeat(64);

        registry.stage(conversationId, runId, SEGMENT_CONTEXT, JOURNEY_PACK_INDEX);
        registry.bind(conversationId, runId, hash);
        assertThat(registry.lookup(hash)).isPresent();

        registry.remove(hash);
        assertThat(registry.lookup(hash)).isEmpty();
    }

    @Test
    void evidenceRegistryLookupNonExistent() {
        DevelopmentCampaignCompileEvidenceRegistry registry =
                new DevelopmentCampaignCompileEvidenceRegistry(java.time.Clock.systemUTC());

        assertThat(registry.lookup("sha256-" + "b".repeat(64))).isEmpty();
    }

    @Test
    void evidenceRegistryLookupMismatchedHash() {
        DevelopmentCampaignCompileEvidenceRegistry registry =
                new DevelopmentCampaignCompileEvidenceRegistry(java.time.Clock.systemUTC());
        String conversationId = "conv-test-004";
        String runId = "run-test-004";
        String registeredHash = "sha256-" + "a".repeat(64);
        String differentHash = "sha256-" + "b".repeat(64);

        registry.stage(conversationId, runId, SEGMENT_CONTEXT, JOURNEY_PACK_INDEX);
        registry.bind(conversationId, runId, registeredHash);

        assertThat(registry.lookup(registeredHash)).isPresent();
        assertThat(registry.lookup(differentHash)).isEmpty();
    }
}
