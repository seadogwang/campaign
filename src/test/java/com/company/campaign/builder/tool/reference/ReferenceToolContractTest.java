package com.company.campaign.builder.tool.reference;

import com.company.campaign.builder.tool.SegmentToolIds;
import com.company.campaign.builder.tool.SegmentToolVersions;
import com.company.campaign.builder.tool.StandardSegmentToolHandlers;
import com.company.campaign.builder.tool.SegmentToolArtifact;
import com.company.campaign.builder.tool.SegmentToolExecution;
import com.company.campaign.builder.tool.SegmentToolRunContext;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.contracts.ToolInput;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.metadata.service.MetadataQueryService;
import com.company.campaign.preview.PreviewResponse;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.intent.SegmentIntent;
import com.company.campaign.segment.intent.testsupport.IntentFixtures;
import com.company.campaign.segment.reference.AudienceReferenceDirectoryContext;
import com.company.campaign.segment.reference.AudienceSummary;
import com.company.campaign.segment.reference.testsupport.AudienceReferenceFixtures;
import com.company.campaign.segment.resolution.AudienceReferenceChoice;
import com.company.campaign.segment.resolution.QuestionCode;
import com.company.campaign.segment.resolution.ResolutionResult;
import com.company.campaign.segment.validation.SegmentValidationResult;
import com.company.campaign.security.context.TrustedExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T18 section 6: exactly two host-owned, read-only Existing Audience Reference Tools are registered, their
 * answers are bounded and non-disclosing, and Save/Apply/Publish/retain/delete are refused as host-only
 * capabilities rather than merely unknown identifiers.
 */
class ReferenceToolContractTest {

    // ------------------------------------------------------------------ the closed registry

    @Test
    void theRegistryExposesExactlyTheTwoReadOnlyContracts() {
        assertThat(ReferenceToolCatalog.registeredIds())
                .containsExactly(ReferenceToolCatalog.DISCOVER, ReferenceToolCatalog.RESOLVE);
        assertThat(ReferenceToolCatalog.all()).hasSize(2);
        assertThat(ReferenceToolCatalog.all()).allSatisfy(contract -> {
            assertThat(contract.toolVersion()).isEqualTo(ReferenceToolCatalog.TOOL_VERSION);
            assertThat(contract.maxResultItems()).isBetween(1, ReferenceToolContract.MAX_RESULT_ITEMS);
        });
        // A registered contract is also a server-owned Tool identifier at the exact accepted version, so the
        // reference surface cannot drift into naming a Tool the agent boundary does not know.
        assertThat(ReferenceToolCatalog.registeredIds()).allSatisfy(id -> {
            assertThat(SegmentToolIds.isKnown(id)).as(id).isTrue();
            assertThat(SegmentToolVersions.matches(id, ReferenceToolCatalog.TOOL_VERSION)).as(id).isTrue();
        });
        // ...and both are executed by the one server-owned handler map, not by a second execution path.
        assertThat(StandardSegmentToolHandlers.all().keySet())
                .contains(ReferenceToolCatalog.DISCOVER, ReferenceToolCatalog.RESOLVE);
    }

    @Test
    void theContractsDeclareOnlyReadAnswerShapes() {
        assertThat(ReferenceToolOutputKind.values())
                .containsExactly(ReferenceToolOutputKind.DISCOVERY, ReferenceToolOutputKind.RESOLUTION);
        assertThat(ReferenceToolCatalog.all())
                .filteredOn(ReferenceToolContract::isDiscovery)
                .singleElement()
                .satisfies(contract -> assertThat(contract.toolId()).isEqualTo(ReferenceToolCatalog.DISCOVER));
        assertThat(ReferenceToolCatalog.all())
                .filteredOn(ReferenceToolContract::isResolution)
                .singleElement()
                .satisfies(contract -> assertThat(contract.toolId()).isEqualTo(ReferenceToolCatalog.RESOLVE));
    }

    @Test
    void anUnregisteredIdentifierIsRefused() {
        for (String toolId : List.of("audience.reference.list", "audience.reference.version", "unknown.tool")) {
            assertThat(ReferenceToolCatalog.isRegistered(toolId)).as(toolId).isFalse();
            assertThatThrownBy(() -> ReferenceToolCatalog.require(toolId))
                    .isInstanceOf(ReferenceToolException.class)
                    .extracting(thrown -> ((ReferenceToolException) thrown).code())
                    .as(toolId)
                    .isEqualTo(ReferenceToolErrorCodes.REFERENCE_TOOL_UNKNOWN);
        }
    }

    // ------------------------------------------------------------------ the forbid-list

    @Test
    void saveApplyPublishRetainReleaseAndDeleteAreRefusedAsHostOnlyCapabilities() {
        for (String toolId : List.of(
                "save", "Save", "save.draft", "SAVE_DRAFT", "campaign.save",
                "apply", "cAmPaInG.appLy", "APPLY_PLAN",
                "publish", "campaign.publish", "PUBLISH_SEGMENT", "prepublish", "unpublish",
                "retain", "retain.version", "RETAIN_HOLD", "retention", "audience.reference.retain",
                "delete", "delete.version", "DELETE_AUDIENCE", "audience.reference.delete",
                "release", "release.hold", "purge", "purge.audience", "erase.version")) {
            assertThat(SegmentToolIds.attemptsForbiddenCapability(toolId)).as(toolId).isTrue();
            assertThat(ReferenceToolCatalog.isRegistered(toolId)).as(toolId).isFalse();
            // The forbidden rule runs first, so a mutation name is reported as the dangerous capability it is
            // rather than as a merely unknown Tool.
            assertThatThrownBy(() -> ReferenceToolCatalog.require(toolId))
                    .isInstanceOf(ReferenceToolException.class)
                    .extracting(thrown -> ((ReferenceToolException) thrown).code())
                    .as(toolId)
                    .isEqualTo(ReferenceToolErrorCodes.REFERENCE_TOOL_FORBIDDEN_CAPABILITY);
        }
    }

    // ------------------------------------------------------------------ bounded, safe answers

    @Test
    void aDiscoveryAnswerIsBoundedAndNeverPrintsALabel() {
        ReferenceDiscoveryToolOutput output = new ReferenceDiscoveryToolOutput(
                List.of(new AudienceSummary("audience-a", "Label A")));
        assertThat(output.size()).isEqualTo(1);
        assertThat(output.isEmpty()).isFalse();
        assertThat(output.toString()).doesNotContain("Label A");

        assertThat(new ReferenceDiscoveryToolOutput(List.of()).isEmpty()).isTrue();

        List<AudienceSummary> overBound = new ArrayList<>();
        for (int index = 0; index <= ReferenceToolContract.MAX_RESULT_ITEMS; index++) {
            overBound.add(new AudienceSummary("audience-" + index, "Label"));
        }
        assertThatThrownBy(() -> new ReferenceDiscoveryToolOutput(overBound))
                .isInstanceOf(ReferenceToolException.class)
                .extracting(thrown -> ((ReferenceToolException) thrown).code())
                .isEqualTo(ReferenceToolErrorCodes.REFERENCE_TOOL_OUTPUT_LIMIT_EXCEEDED);
    }

    @Test
    void aResolutionAnswerBindsItsShapeToItsDisposition() {
        ReferenceResolutionToolOutput needsInput = new ReferenceResolutionToolOutput(
                "ref-1", Disposition.NEEDS_INPUT, null, null, false, false,
                List.of(new AudienceReferenceChoice("audience-vip/version-7@sha256:" + "a".repeat(64), "Label")),
                List.of(QuestionCode.SELECT_AUDIENCE_VERSION), 2, null);
        assertThat(needsInput.isResolved()).isFalse();
        assertThat(needsInput.choices()).hasSize(1);
        assertThat(needsInput.toString()).doesNotContain("Label");

        // An accepted answer must name its pin, its label and its evidence, and must offer no choice.
        assertThatThrownBy(() -> new ReferenceResolutionToolOutput(
                "ref-1", Disposition.RESOLVED, null, null, false, false, List.of(), List.of(), 1, null))
                .isInstanceOf(ReferenceToolException.class)
                .extracting(thrown -> ((ReferenceToolException) thrown).code())
                .isEqualTo(ReferenceToolErrorCodes.REFERENCE_TOOL_INCONSISTENT);
        assertThatThrownBy(() -> new ReferenceResolutionToolOutput(
                "ref-1", Disposition.NEEDS_INPUT, null, null, false, false, List.of(), List.of(), 1, null))
                .isInstanceOf(ReferenceToolException.class)
                .extracting(thrown -> ((ReferenceToolException) thrown).code())
                .isEqualTo(ReferenceToolErrorCodes.REFERENCE_TOOL_INCONSISTENT);
    }

    @Test
    void aReferenceResultCarriesExactlyTheOneShapeItsContractProduces() {
        ReferenceToolContract discovery = ReferenceToolCatalog.require(ReferenceToolCatalog.DISCOVER);
        ReferenceToolContract resolution = ReferenceToolCatalog.require(ReferenceToolCatalog.RESOLVE);

        ReferenceToolResult read = new ReferenceToolResult(
                discovery.toolId(), discovery.toolVersion(), ReferenceToolOutputKind.DISCOVERY,
                new ReferenceDiscoveryToolOutput(List.of()), null);
        assertThat(read.outputKind()).isEqualTo(ReferenceToolOutputKind.DISCOVERY);
        // The diagnostic names the contract and the answer shape only: no audience identity, no label and no pin.
        assertThat(read.toString())
                .isEqualTo("ReferenceToolResult[toolId=" + discovery.toolId()
                        + ", toolVersion=" + discovery.toolVersion() + ", outputKind=DISCOVERY]");

        assertThatThrownBy(() -> new ReferenceToolResult(
                discovery.toolId(), discovery.toolVersion(), ReferenceToolOutputKind.DISCOVERY, null, null))
                .isInstanceOf(ReferenceToolException.class);
        assertThatThrownBy(() -> new ReferenceToolResult(
                resolution.toolId(), resolution.toolVersion(), ReferenceToolOutputKind.RESOLUTION,
                new ReferenceDiscoveryToolOutput(List.of()), null))
                .isInstanceOf(ReferenceToolException.class);
    }

    // ------------------------------------------------------------------ the admitted handler

    /**
     * T18 section 6: the read-only resolution answer is produced by the admitted Tool handler from the
     * host-owned directory, so the reference evidence entering the Agent flow is host-resolved and never a fact
     * a model supplied.
     */
    @Test
    void theAdmittedResolveHandlerAnswersFromTheHostDirectoryWithTheExactImmutablePin() {
        AudienceReferenceDirectoryContext directory = AudienceReferenceFixtures.singleVersionDirectory().context();
        SegmentToolExecution execution = StandardSegmentToolHandlers.all()
                .get(SegmentToolIds.AUDIENCE_REFERENCE_RESOLVE)
                .execute(new ReferenceRunContext(IntentFixtures.referenceOnlyIntent(), directory), ToolInput.empty());

        assertThat(execution.disposition()).isEqualTo(Disposition.RESOLVED);
        assertThat(execution.outcome().outcomeCode()).isEqualTo(StandardSegmentToolHandlers.REFERENCE_RESOLUTION);
        SegmentToolArtifact artifact = execution.artifact();
        assertThat(artifact).isInstanceOf(SegmentToolArtifact.ReferencedAudiences.class);
        SegmentToolArtifact.ReferencedAudiences references = (SegmentToolArtifact.ReferencedAudiences) artifact;
        assertThat(references.resolution().isResolved()).isTrue();
        assertThat(references.resolution().resolved().audienceId())
                .isEqualTo(AudienceReferenceFixtures.AUDIENCE_VIP);
        assertThat(references.resolution().resolved().versionId())
                .isEqualTo(AudienceReferenceFixtures.VERSION_SEVEN);
        assertThat(references.resolution().evidenceHash()).isNotNull();
    }

    /** The same handler fails closed when the run exposes no host directory, exactly like the reference-free path. */
    @Test
    void theAdmittedResolveHandlerFailsClosedWithoutAHostDirectory() {
        SegmentToolExecution execution = StandardSegmentToolHandlers.all()
                .get(SegmentToolIds.AUDIENCE_REFERENCE_RESOLVE)
                .execute(new ReferenceRunContext(IntentFixtures.referenceOnlyIntent(), null), ToolInput.empty());

        assertThat(execution.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(execution.code()).isEqualTo(com.company.campaign.builder.tool.SegmentToolErrorCodes
                .TOOL_EVIDENCE_MISSING);
        assertThat(execution.artifact()).isNull();
    }

    /** The minimum host-owned run evidence the two reference Tools read: the accepted Intent and the directory. */
    private record ReferenceRunContext(SegmentIntent intent, AudienceReferenceDirectoryContext directory)
            implements SegmentToolRunContext {

        @Override
        public TrustedExecutionContext trustedContext() {
            return null;
        }

        @Override
        public MetadataSnapshot metadataSnapshot() {
            return null;
        }

        @Override
        public MetadataQueryService metadataQueryService() {
            return null;
        }

        @Override
        public SegmentIntent intent() {
            return intent;
        }

        @Override
        public AudienceReferenceDirectoryContext referenceDirectory() {
            return directory;
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
    }
}