package com.company.campaign.builder.tool;

import com.company.campaign.builder.segment.testsupport.SliceTestFixtures;
import com.company.campaign.builder.skill.SegmentSkillDefinition;
import com.company.campaign.builder.skill.SegmentSkillLoader;
import com.company.campaign.contracts.ContractVersions;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.contracts.ToolEnvelope;
import com.company.campaign.contracts.ToolInput;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.metadata.repository.InMemoryMetadataSnapshotRepository;
import com.company.campaign.metadata.service.MetadataQueryService;
import com.company.campaign.preview.PreviewResponse;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.intent.SegmentIntent;
import com.company.campaign.segment.resolution.ResolutionResult;
import com.company.campaign.segment.validation.SegmentValidationResult;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import com.company.campaign.security.authorization.AuthorizationService;
import com.company.campaign.security.context.TrustedExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 2: the server-owned Metadata discovery and detail Tools operate over the exact permitted T04
 * snapshot and through the accepted T02 authorization boundary, and never expose a restricted fact. The
 * committed {@code segment.rule-based/1.0.0} Skill permits discovery only, so the detail Tool is exercised over
 * an explicitly published definition that selects it.
 */
class SegmentToolHandlersTest {

    private static SegmentSkillDefinition committedSkill() {
        return new SegmentSkillLoader().loadDefault();
    }

    /** The discovery-plus-detail allowlist a future Skill version would declare; not the committed one. */
    private static SegmentSkillDefinition discoveryAndDetailSkill() {
        SegmentSkillDefinition base = committedSkill();
        return SegmentSkillDefinition.publish(
                base.schemaVersion(), base.skillId(), base.skillVersion(), base.capabilityState(),
                base.evidenceClass(), base.contractPins(),
                List.of(new SegmentSkillDefinition.ToolPin(SegmentToolIds.METADATA_DISCOVER, "1.0", 0, 4096),
                        new SegmentSkillDefinition.ToolPin(SegmentToolIds.METADATA_DETAIL, "1.0", 1, 4096)),
                base.inputDispositions(), base.outputDispositions(), base.terminalStates(), base.limits(),
                base.dataClassification(), base.sampleOutputPermitted(), base.ambiguityStopsAt(),
                base.failureBehavior());
    }

    private static MetadataSnapshot snapshot() {
        return SliceTestFixtures.snapshot();
    }

    private static SegmentToolInvoker invoker(AuthorizationService authorizationService) {
        return invoker(discoveryAndDetailSkill(), authorizationService);
    }

    private static SegmentToolInvoker invoker(SegmentSkillDefinition skill, AuthorizationService authorizationService) {
        InMemoryMetadataSnapshotRepository repository = new InMemoryMetadataSnapshotRepository();
        repository.publish(snapshot());
        MetadataQueryService queryService = new MetadataQueryService(authorizationService, repository);
        SegmentToolRunContext run = new SegmentToolRunContext() {
            @Override
            public TrustedExecutionContext trustedContext() {
                return SliceTestFixtures.context();
            }

            @Override
            public MetadataSnapshot metadataSnapshot() {
                return snapshot();
            }

            @Override
            public MetadataQueryService metadataQueryService() {
                return queryService;
            }

            @Override
            public SegmentIntent intent() {
                return null;
            }

            @Override
            public ResolutionResult resolution() {
                return null;
            }

            @Override
            public SegmentDsl dslDocument() {
                return null;
            }

            @Override
            public SegmentValidationResult validation() {
                return null;
            }

            @Override
            public SemanticQueryPlan plan() {
                return null;
            }

            @Override
            public PreviewResponse previewResponse() {
                return null;
            }
        };
        return new SegmentToolInvoker(SegmentToolCatalog.fromSkill(skill), StandardSegmentToolHandlers.all(), run);
    }

    @Test
    void theCommittedSkillPermitsMetadataDiscoveryOnly() {
        assertThat(committedSkill().allowedToolIds()).contains(SegmentToolIds.METADATA_DISCOVER);
        assertThat(committedSkill().allowedToolIds()).doesNotContain(SegmentToolIds.METADATA_DETAIL);
    }

    @Test
    void discoveryListsOnlyPermittedSemanticIdentifiersAndCarriesTheAuthorizedProjection() {
        SegmentToolInvoker invoker = invoker(committedSkill(), SegmentValidationFixtures.allowAll());
        SegmentToolAdmission admission = invoker.invoke(new ToolEnvelope(
                ContractVersions.CURRENT, "call-1", SegmentToolIds.METADATA_DISCOVER, false, ToolInput.empty()));

        assertThat(admission.disposition()).isEqualTo(Disposition.RESOLVED);
        assertThat(admission.result().outcomeCode()).isEqualTo(StandardSegmentToolHandlers.METADATA_LIST);
        assertThat(admission.result().stableReferences()).contains("member.tier", "order.net_paid_amount");
        assertThat(admission.result().evidenceHash()).isEqualTo(snapshot().contentHash());

        SegmentToolArtifact artifact =
                admission.claim("call-1", SegmentToolIds.METADATA_DISCOVER, committedSkill().canonicalHash());
        assertThat(artifact).isInstanceOf(SegmentToolArtifact.MetadataDiscovery.class);
        assertThat(((SegmentToolArtifact.MetadataDiscovery) artifact).definitionIds())
                .containsExactlyElementsOf(admission.result().stableReferences());
    }

    @Test
    void discoveryRefusesWhenTheAuthorizationBoundaryPermitsNothing() {
        SegmentToolInvoker invoker = invoker(committedSkill(), SegmentValidationFixtures.denyAll());
        SegmentToolAdmission admission = invoker.invoke(new ToolEnvelope(
                ContractVersions.CURRENT, "call-1", SegmentToolIds.METADATA_DISCOVER, false, ToolInput.empty()));

        assertThat(admission.disposition()).isEqualTo(Disposition.DENIED);
        assertThat(admission.failure().code()).isEqualTo(SegmentToolErrorCodes.TOOL_DENIED);
        assertThat(admission.hasArtifact()).isFalse();
    }

    @Test
    void detailReturnsOnePermittedDefinition() {
        SegmentToolInvoker invoker = invoker(SegmentValidationFixtures.allowAll());
        invoker.invoke(new ToolEnvelope(
                ContractVersions.CURRENT, "call-1", SegmentToolIds.METADATA_DISCOVER, false, ToolInput.empty()));
        SegmentToolAdmission result = invoker.invoke(new ToolEnvelope(
                ContractVersions.CURRENT, "call-2", SegmentToolIds.METADATA_DETAIL, false,
                ToolInput.of(java.util.Map.of("kind", "ATTRIBUTE", "definitionId", "member.tier"))));

        assertThat(result.disposition()).isEqualTo(Disposition.RESOLVED);
        assertThat(result.result().outcomeCode()).isEqualTo(StandardSegmentToolHandlers.METADATA_DETAIL);
        assertThat(result.result().stableReferences()).containsExactly("member.tier");
        assertThat(result.result().toString()).doesNotContain("tenant-001").doesNotContain("program-001");
    }

    @Test
    void detailFailsClosedForADeniedDefinition() {
        // Discovery authorizes every permitted definition (five); the sixth decision is the detail lookup.
        SegmentToolInvoker invoker = invoker(
                com.company.campaign.preview.testsupport.PreviewAuthorizationStub.allowFirstMetadata(5));
        invoker.invoke(new ToolEnvelope(
                ContractVersions.CURRENT, "call-1", SegmentToolIds.METADATA_DISCOVER, false, ToolInput.empty()));
        SegmentToolAdmission denied = invoker.invoke(new ToolEnvelope(
                ContractVersions.CURRENT, "call-2", SegmentToolIds.METADATA_DETAIL, false,
                ToolInput.of(java.util.Map.of("kind", "ATTRIBUTE", "definitionId", "member.tier"))));

        assertThat(denied.disposition()).isEqualTo(Disposition.DENIED);
        assertThat(denied.failure().code()).isEqualTo(SegmentToolErrorCodes.TOOL_RESOURCE_DENIED);
    }

    @Test
    void detailRejectsMalformedArguments() {
        SegmentToolInvoker invoker = invoker(SegmentValidationFixtures.allowAll());
        invoker.invoke(new ToolEnvelope(
                ContractVersions.CURRENT, "call-1", SegmentToolIds.METADATA_DISCOVER, false, ToolInput.empty()));
        SegmentToolAdmission result = invoker.invoke(new ToolEnvelope(
                ContractVersions.CURRENT, "call-2", SegmentToolIds.METADATA_DETAIL, false,
                ToolInput.of(java.util.Map.of("kind", "not-a-kind"))));

        assertThat(result.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(result.failure().code()).isEqualTo(SegmentToolErrorCodes.TOOL_ARGUMENT_INVALID);
    }

    @Test
    void aStageWithoutItsHostEvidenceFailsClosed() {
        SegmentSkillDefinition base = committedSkill();
        SegmentSkillDefinition discoveryAndResolve = SegmentSkillDefinition.publish(
                base.schemaVersion(), base.skillId(), base.skillVersion(), base.capabilityState(),
                base.evidenceClass(), base.contractPins(),
                List.of(new SegmentSkillDefinition.ToolPin(SegmentToolIds.METADATA_DISCOVER, "1.0", 0, 4096),
                        new SegmentSkillDefinition.ToolPin(SegmentToolIds.INTENT_RESOLVE, "1.0", 1, 4096)),
                base.inputDispositions(), base.outputDispositions(), base.terminalStates(), base.limits(),
                base.dataClassification(), base.sampleOutputPermitted(), base.ambiguityStopsAt(),
                base.failureBehavior());
        SegmentToolInvoker invoker = invoker(discoveryAndResolve, SegmentValidationFixtures.allowAll());
        invoker.invoke(new ToolEnvelope(
                ContractVersions.CURRENT, "call-1", SegmentToolIds.METADATA_DISCOVER, false, ToolInput.empty()));
        SegmentToolAdmission result = invoker.invoke(new ToolEnvelope(
                ContractVersions.CURRENT, "call-2", SegmentToolIds.INTENT_RESOLVE, false, ToolInput.empty()));

        assertThat(result.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(result.failure().code()).isEqualTo(SegmentToolErrorCodes.TOOL_EVIDENCE_MISSING);
    }
}