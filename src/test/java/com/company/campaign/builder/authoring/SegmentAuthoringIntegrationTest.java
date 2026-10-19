package com.company.campaign.builder.authoring;

import com.company.campaign.builder.authoring.testsupport.SegmentAuthoringFixtures;
import com.company.campaign.builder.authoring.testsupport.SegmentAuthoringIntegrationFixtures;
import com.company.campaign.builder.compiler.CampaignCompiler;
import com.company.campaign.builder.compiler.CampaignPlan;
import com.company.campaign.builder.compiler.ChangeKind;
import com.company.campaign.builder.compiler.CompileRequest;
import com.company.campaign.builder.compiler.DiffEntry;
import com.company.campaign.builder.compiler.FrozenCapabilityCatalog;
import com.company.campaign.builder.compiler.PlanPreviewCard;
import com.company.campaign.builder.compiler.ResolvedComponent;
import com.company.campaign.builder.compiler.TrustedCurrentCampaign;
import com.company.campaign.builder.proposal.CampaignProposalPatch;
import com.company.campaign.builder.proposal.ProposalMode;
import com.company.campaign.builder.proposal.ProposalOperation;
import com.company.campaign.builder.proposal.ProposalOperationKind;
import com.company.campaign.builder.testsupport.BuilderFixtures;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T16 end-to-end: the three proof-bound server-owned Segment sources converge on the one accepted T15
 * compiler, and every non-resolved outcome stays a bounded no-output result.
 */
class SegmentAuthoringIntegrationTest {

    private final SegmentAuthoringIntegration integration =
            new SegmentAuthoringIntegration(new CampaignCompiler(SegmentValidationFixtures.allowAll()));

    private final CampaignCompiler compiler = new CampaignCompiler(SegmentValidationFixtures.allowAll());

    // ------------------------------------------------------------------ create: AI vs Visual Builder

    @Test
    void createThroughAiAndVisualBuilderProduceIdenticalProposalManifestAndPlan() {
        // The two sources are built independently — distinct proof and artifact instances over the same typed
        // Segment — so equivalence can only come from the shared payload writer and the one compiler.
        SegmentAuthoringProof aiProof = SegmentAuthoringIntegrationFixtures.independentProof();
        SegmentAuthoringProof visualProof = SegmentAuthoringIntegrationFixtures.proof();
        assertThat(aiProof).isNotSameAs(visualProof);
        assertThat(aiProof).isEqualTo(visualProof);

        SegmentAuthoringResult.Compiled fromAi = compiled(SegmentAuthoringIntegrationFixtures.createRequest(
                SegmentAuthoringIntegrationFixtures.ai(aiProof)));
        SegmentAuthoringResult.Compiled fromVisual = compiled(SegmentAuthoringIntegrationFixtures.createRequest(
                SegmentAuthoringIntegrationFixtures.visual(visualProof)));

        assertThat(fromVisual.patch().canonicalBytes()).isEqualTo(fromAi.patch().canonicalBytes());
        assertThat(fromVisual.patch().proposalHash()).isEqualTo(fromAi.patch().proposalHash());
        assertThat(fromVisual.plan().manifestHash()).isEqualTo(fromAi.plan().manifestHash());
        assertThat(fromVisual.plan().planHash()).isEqualTo(fromAi.plan().planHash());
        assertThat(fromVisual.plan().canonicalMaterialBytes()).isEqualTo(fromAi.plan().canonicalMaterialBytes());
        assertThat(fromVisual.plan().previewCards()).isEqualTo(fromAi.plan().previewCards());
        assertThat(fromVisual.logicalId()).isEqualTo(fromAi.logicalId());
    }

    @Test
    void conversationAndModelTraceDetailsNeverReachTheSemanticOutput() {
        SegmentAuthoringProof proof = SegmentAuthoringIntegrationFixtures.proof();

        SegmentAuthoringResult.Compiled plain = compiled(SegmentAuthoringIntegrationFixtures.createRequest(
                SegmentAuthoringIntegrationFixtures.ai(proof)));
        SegmentAuthoringResult.Compiled traced = compiled(SegmentAuthoringIntegrationFixtures.createRequest(
                SegmentAuthoringIntegrationFixtures.aiWithDifferentTrace(proof)));

        assertThat(traced.plan().planHash()).isEqualTo(plain.plan().planHash());
        assertThat(traced.patch().canonicalBytes()).isEqualTo(plain.patch().canonicalBytes());
    }

    @Test
    void createCompilesToOneSegmentComponentAndExactlyOneBoundPreviewCard() {
        SegmentAuthoringResult.Compiled result = compiled(
                SegmentAuthoringIntegrationFixtures.createRequest(
                        SegmentAuthoringIntegrationFixtures.visual(
                                SegmentAuthoringIntegrationFixtures.proof())));
        CampaignPlan plan = result.plan();

        assertThat(plan.manifest().components()).hasSize(1);
        assertThat(plan.capabilityPins()).extracting(pin -> pin.capabilityCode())
                .containsExactly(SegmentAuthoringVersions.CAPABILITY_CODE);
        assertThat(plan.previewCards()).hasSize(1);
        PlanPreviewCard card = plan.previewCards().get(0);
        assertThat(card.cardKind()).isEqualTo(PlanPreviewCard.SEGMENT_PREVIEW);
        assertThat(card.componentLogicalId()).isEqualTo(plan.manifest().components().get(0).logicalId());
        assertThat(card.previewHash()).isEqualTo(SegmentAuthoringFixtures.PREVIEW_HASH);
        assertThat(plan.semanticDiff().entries()).extracting(DiffEntry::kind).containsExactly(ChangeKind.ADD);
    }

    @Test
    void aCreateCompileIsByteIdenticalAcrossRepeatedRuns() {
        SegmentAuthoringRequest request = SegmentAuthoringIntegrationFixtures.createRequest(
                SegmentAuthoringIntegrationFixtures.visual(SegmentAuthoringIntegrationFixtures.proof()));
        SegmentAuthoringResult.Compiled first = compiled(request);
        byte[] patchBytes = first.patch().canonicalBytes();
        byte[] planBytes = first.plan().canonicalMaterialBytes();
        for (int attempt = 0; attempt < 10; attempt++) {
            SegmentAuthoringResult.Compiled candidate = compiled(request);
            assertThat(candidate.patch().canonicalBytes()).isEqualTo(patchBytes);
            assertThat(candidate.plan().canonicalMaterialBytes()).isEqualTo(planBytes);
            assertThat(candidate.plan().planId()).isEqualTo(first.plan().planId());
        }
    }

    // ------------------------------------------------------------------ edit and refine

    @Test
    void anEditOfAnExistingSegmentProducesUpdateNotDeleteAndRecreate() {
        SegmentAuthoringResult.Compiled created = compiled(
                SegmentAuthoringIntegrationFixtures.createRequest(
                        SegmentAuthoringIntegrationFixtures.ai(SegmentAuthoringIntegrationFixtures.proof())));
        String logicalId = created.logicalId();

        SegmentAuthoringProof refined = SegmentAuthoringIntegrationFixtures.refinedProof();
        SegmentAuthoringResult.Compiled edited = compiled(SegmentAuthoringIntegrationFixtures.editRequest(
                SegmentAuthoringIntegrationFixtures.visual(refined), logicalId, currentOf(created.plan())));

        assertThat(created.patch().mode()).isEqualTo(ProposalMode.CREATE);
        assertThat(edited.patch().mode()).isEqualTo(ProposalMode.EDIT);
        assertThat(edited.patch().operations()).hasSize(1);
        ProposalOperation operation = edited.patch().operations().get(0);
        assertThat(operation.kind()).isEqualTo(ProposalOperationKind.UPSERT);
        assertThat(operation.target().logicalId()).isEqualTo(logicalId);

        assertThat(edited.plan().manifest().components()).extracting(ResolvedComponent::logicalId)
                .containsExactly(logicalId);
        assertThat(edited.plan().semanticDiff().entries()).hasSize(1);
        DiffEntry entry = edited.plan().semanticDiff().entries().get(0);
        assertThat(entry.kind()).isEqualTo(ChangeKind.UPDATE);
        assertThat(entry.logicalId()).isEqualTo(logicalId);
        assertThat(edited.plan().previewCards()).hasSize(1);
        PlanPreviewCard card = edited.plan().previewCards().get(0);
        assertThat(card.componentLogicalId()).isEqualTo(logicalId);
        assertThat(card.previewHash()).isEqualTo(SegmentAuthoringIntegrationFixtures.REFINED_PREVIEW_HASH);
    }

    @Test
    void aSuccessfulRefinementUpdatesTheSameComponentAndRefreshesExactlyOneBoundCard() {
        SegmentAuthoringResult.Compiled created = compiled(
                SegmentAuthoringIntegrationFixtures.createRequest(
                        SegmentAuthoringIntegrationFixtures.ai(SegmentAuthoringIntegrationFixtures.proof())));
        String logicalId = created.logicalId();
        assertThat(created.plan().previewCards()).hasSize(1);
        assertThat(created.plan().previewCards().get(0).previewHash())
                .isEqualTo(SegmentAuthoringFixtures.PREVIEW_HASH);

        SegmentAuthoringProof base = SegmentAuthoringIntegrationFixtures.proof();
        SegmentAuthoringProof refined = SegmentAuthoringIntegrationFixtures.refinedProof();
        SegmentAuthoringResult.Compiled refinedResult = compiled(
                SegmentAuthoringIntegrationFixtures.editRequest(
                        SegmentAuthoringIntegrationFixtures.refinement(base, refined),
                        logicalId, currentOf(created.plan())));

        assertThat(refinedResult.logicalId()).isEqualTo(logicalId);
        assertThat(refinedResult.plan().manifest().components()).extracting(ResolvedComponent::logicalId)
                .containsExactly(logicalId);
        assertThat(refinedResult.plan().previewCards()).hasSize(1);
        PlanPreviewCard card = refinedResult.plan().previewCards().get(0);
        assertThat(card.cardIdentity()).isEqualTo(logicalId + "|SEGMENT_PREVIEW");
        assertThat(card.previewHash()).isEqualTo(SegmentAuthoringIntegrationFixtures.REFINED_PREVIEW_HASH);
        assertThat(card.matchedCount()).isEqualTo(7);
        assertThat(refinedResult.plan().semanticDiff().entries()).extracting(DiffEntry::kind)
                .containsExactly(ChangeKind.UPDATE);
    }

    @Test
    void aDeletedSegmentRemovesItsComponentAndItsCard() {
        SegmentAuthoringResult.Compiled created = compiled(
                SegmentAuthoringIntegrationFixtures.createRequest(
                        SegmentAuthoringIntegrationFixtures.visual(
                                SegmentAuthoringIntegrationFixtures.proof())));
        String logicalId = created.logicalId();

        CampaignProposalPatch deletion = BuilderFixtures.editProposal()
                .pins(SegmentAuthoringIntegrationFixtures.catalog(), BuilderFixtures.policy())
                .delete(logicalId)
                .build();
        CampaignPlan deleted = compiler.compile(CompileRequest.edit(
                deletion, currentOf(created.plan()), SegmentAuthoringIntegrationFixtures.catalog(),
                BuilderFixtures.policy(), SegmentAuthoringIntegrationFixtures.instants(),
                SegmentAuthoringFixtures.context(), SegmentAuthoringFixtures.compileContext()));

        assertThat(deleted.manifest().components()).isEmpty();
        assertThat(deleted.previewCards()).isEmpty();
        assertThat(deleted.semanticDiff().entries()).extracting(DiffEntry::kind)
                .containsExactly(ChangeKind.DELETE);
    }

    // ------------------------------------------------------------------ no second truth

    @Test
    void theIntegrationAddsNothingToTheOneCompilersOwnOutput() {
        SegmentAuthoringProof proof = SegmentAuthoringIntegrationFixtures.proof();
        SegmentAuthoringResult.Compiled viaIntegration = compiled(
                SegmentAuthoringIntegrationFixtures.createRequest(
                        SegmentAuthoringIntegrationFixtures.visual(proof)));

        // Feeding the integration's own Proposal straight to the accepted compiler must reproduce the exact
        // same Plan: the integration is a facade over the one compiler, not a second producer.
        CampaignPlan direct = compiler.compile(CompileRequest.create(
                viaIntegration.patch(), SegmentAuthoringIntegrationFixtures.catalog(),
                BuilderFixtures.policy(), SegmentAuthoringIntegrationFixtures.instants(),
                SegmentAuthoringFixtures.context(),
                SegmentAuthoringIntegrationFixtures.compileContext(
                        proof, SegmentAuthoringFixtures.experimental())));

        assertThat(direct.planHash()).isEqualTo(viaIntegration.plan().planHash());
        assertThat(direct.canonicalMaterialBytes()).isEqualTo(viaIntegration.plan().canonicalMaterialBytes());
        assertThat(direct.manifestHash()).isEqualTo(viaIntegration.plan().manifest().manifestHash());
    }

    // ------------------------------------------------------------------ bounded no-output outcomes

    @Test
    void aClarificationProducesNoProposalManifestOrPlan() {
        SegmentAuthoringResult result = integration.author(SegmentAuthoringIntegrationFixtures.createRequest(
                SegmentAuthoringIntegrationFixtures.terminalAi(Disposition.NEEDS_INPUT)));

        assertThat(result).isInstanceOf(SegmentAuthoringResult.Terminal.class);
        SegmentAuthoringResult.Terminal terminal = (SegmentAuthoringResult.Terminal) result;
        assertThat(terminal.disposition()).isEqualTo(Disposition.NEEDS_INPUT);
        assertThat(terminal.code()).isEqualTo("SEGMENT_NEEDS_INPUT");
        assertThat(terminal.sourceKind()).isEqualTo(SegmentAuthoringKind.AI);
        assertThat(terminal.clarification())
                .isEqualTo(SegmentAuthoringIntegrationFixtures.clarification());
        assertNoOutput(result);
    }

    @Test
    void deniedUnsupportedAndInvalidProduceNoProposalManifestOrPlan() {
        for (Disposition disposition : List.of(
                Disposition.DENIED, Disposition.UNSUPPORTED, Disposition.INVALID)) {
            SegmentAuthoringResult result = integration.author(
                    SegmentAuthoringIntegrationFixtures.createRequest(
                            SegmentAuthoringIntegrationFixtures.terminalAi(disposition)));
            assertThat(result).as(disposition.name()).isInstanceOf(SegmentAuthoringResult.Terminal.class);
            SegmentAuthoringResult.Terminal terminal = (SegmentAuthoringResult.Terminal) result;
            assertThat(terminal.disposition()).isEqualTo(disposition);
            assertThat(terminal.clarification()).isNull();
            assertNoOutput(result);
        }
    }

    @Test
    void aRefusedRefinementProducesNoProposalManifestOrPlan() {
        SegmentAuthoringResult result = integration.author(
                SegmentAuthoringIntegrationFixtures.editRequest(
                        SegmentAuthoringIntegrationFixtures.terminalRefinement(Disposition.DENIED),
                        "segment-0001", BuilderFixtures.current()));

        assertThat(result).isInstanceOf(SegmentAuthoringResult.Terminal.class);
        SegmentAuthoringResult.Terminal terminal = (SegmentAuthoringResult.Terminal) result;
        assertThat(terminal.disposition()).isEqualTo(Disposition.DENIED);
        assertThat(terminal.sourceKind()).isEqualTo(SegmentAuthoringKind.REFINEMENT);
        assertNoOutput(result);
    }

    // ------------------------------------------------------------------ proof binding

    @Test
    void aResolvedSourceWhoseResultDisagreesWithItsProofFailsClosed() {
        SegmentAuthoringProof proof = SegmentAuthoringIntegrationFixtures.proof();
        SegmentAuthoringProof other = SegmentAuthoringIntegrationFixtures.refinedProof();

        // The Agent result claims the baseline artifact while the supplied typed proof is a different document.
        SegmentAuthoringSource mismatched = new SegmentAuthoringSource.AiResolved(
                SegmentAuthoringIntegrationFixtures.agentResult(proof), other);
        assertFailsWithCode(() -> integration.author(
                        SegmentAuthoringIntegrationFixtures.createRequest(mismatched)),
                SegmentAuthoringErrorCodes.SOURCE_MISMATCH);

        SegmentAuthoringSource mismatchedRefinement = new SegmentAuthoringSource.Refinement(
                SegmentAuthoringIntegrationFixtures.refinementResult(proof, proof), other);
        assertFailsWithCode(() -> integration.author(
                        SegmentAuthoringIntegrationFixtures.createRequest(mismatchedRefinement)),
                SegmentAuthoringErrorCodes.SOURCE_MISMATCH);
    }

    @Test
    void aProofWithADisagreeingPinIsNotRepresentable() {
        assertFailsWithCode(() -> new SegmentAuthoringProof(
                        SegmentAuthoringFixtures.document(),
                        SegmentAuthoringFixtures.validation(),
                        SegmentAuthoringFixtures.plan(),
                        SegmentAuthoringIntegrationFixtures.refinedCard(),
                        SegmentAuthoringFixtures.SKILL_HASH),
                SegmentAuthoringErrorCodes.EVIDENCE_MISMATCH);
    }

    @Test
    void theTwoHalvesOfTheSourceUnionCannotBeConfused() {
        SegmentAuthoringProof proof = SegmentAuthoringIntegrationFixtures.proof();

        // A resolved variant refuses a non-resolved result, because it could not then be the artifact it claims.
        assertFailsWithCode(() -> new SegmentAuthoringSource.AiResolved(
                        SegmentAuthoringIntegrationFixtures.terminalAgentResult(Disposition.DENIED), proof),
                SegmentAuthoringErrorCodes.SOURCE_MISMATCH);
        assertFailsWithCode(() -> new SegmentAuthoringSource.Refinement(
                        SegmentAuthoringIntegrationFixtures.terminalRefinementResult(Disposition.DENIED), proof),
                SegmentAuthoringErrorCodes.SOURCE_MISMATCH);

        // A terminal variant refuses a resolved result, because a terminal outcome carries no proof at all.
        assertFailsWithCode(() -> new SegmentAuthoringSource.AiTerminal(
                        SegmentAuthoringIntegrationFixtures.agentResult(proof)),
                SegmentAuthoringErrorCodes.TERMINAL_OUTCOME);
        assertFailsWithCode(() -> new SegmentAuthoringSource.RefinementTerminal(
                        SegmentAuthoringIntegrationFixtures.refinementResult(proof, proof)),
                SegmentAuthoringErrorCodes.TERMINAL_OUTCOME);

        // A terminal code is a bounded machine code, never arbitrary text.
        assertFailsWithCode(() -> new SegmentAuthoringResult.Terminal(
                        Disposition.DENIED, "free text: denied", null, SegmentAuthoringKind.AI),
                SegmentAuthoringErrorCodes.TERMINAL_OUTCOME);
    }

    @Test
    void theSubmissionPayloadAndTheNormalizedComponentPayloadAreOneShape() {
        SegmentAuthoringResult.Compiled result = compiled(
                SegmentAuthoringIntegrationFixtures.createRequest(
                        SegmentAuthoringIntegrationFixtures.visual(
                                SegmentAuthoringIntegrationFixtures.proof())));

        var submitted = result.patch().operations().get(0).value();
        var normalized = result.plan().manifest().components().get(0).value();
        assertThat(submitted.fields().keySet()).isEqualTo(normalized.fields().keySet());
        assertThat(submitted.canonicalBytes()).isEqualTo(normalized.canonicalBytes());
        assertThat(submitted.contentHash()).isEqualTo(normalized.contentHash());
    }

    @Test
    void aCompiledResultMustBindItsProposalToItsPlan() {
        SegmentAuthoringResult.Compiled first = compiled(
                SegmentAuthoringIntegrationFixtures.createRequest(
                        SegmentAuthoringIntegrationFixtures.visual(SegmentAuthoringIntegrationFixtures.proof())));
        SegmentAuthoringResult.Compiled other = compiled(
                SegmentAuthoringIntegrationFixtures.createRequest(
                        SegmentAuthoringIntegrationFixtures.visual(
                                SegmentAuthoringIntegrationFixtures.refinedProof())));

        // Both halves are genuine, but neither is the compile of the other: an unrelated pair is not a result.
        assertFailsWithCode(() -> new SegmentAuthoringResult.Compiled(other.patch(), first.plan()),
                SegmentAuthoringErrorCodes.RESULT_UNBOUND);
        assertFailsWithCode(() -> new SegmentAuthoringResult.Compiled(first.patch(), other.plan()),
                SegmentAuthoringErrorCodes.RESULT_UNBOUND);
    }

    @Test
    void aCompiledResultMustResolveToTheRuleBasedSegmentComponentAndItsCard() {
        // A genuine compile of an unrelated capability: patch and plan really do belong together, but the
        // component is not the rule-based Segment, so the pair is not a compiled Segment authoring result.
        FrozenCapabilityCatalog foreignCatalog = BuilderFixtures.catalog(
                com.company.campaign.builder.testsupport.FixtureCapability.passThrough(
                        "message.template", "1.0",
                        com.company.campaign.builder.testsupport.FixtureCapability.unorderedMulti(
                                "slot-message")));
        CampaignProposalPatch foreignPatch = BuilderFixtures.createProposal()
                .pins(foreignCatalog, BuilderFixtures.policy())
                .upsertNew("message.template", "message-client-ref-1", BuilderFixtures.value("label", "welcome"))
                .build();
        CampaignPlan foreignPlan = compiler.compile(CompileRequest.create(
                foreignPatch, foreignCatalog, BuilderFixtures.policy(), BuilderFixtures.instants(),
                SegmentAuthoringFixtures.context()));

        assertFailsWithCode(() -> new SegmentAuthoringResult.Compiled(foreignPatch, foreignPlan),
                SegmentAuthoringErrorCodes.RESULT_UNBOUND);
    }

    @Test
    void anEditOfAnotherCapabilityIsNeverSilentlyRettyped() {
        SegmentAuthoringSource source = SegmentAuthoringIntegrationFixtures.visual(
                SegmentAuthoringIntegrationFixtures.proof());
        TrustedCurrentCampaign foreign = BuilderFixtures.current(
                BuilderFixtures.component("segment-0001", "message.template", "1.0",
                        BuilderFixtures.value("label", "welcome")));

        assertFailsWithCode(() -> integration.author(
                        SegmentAuthoringIntegrationFixtures.editRequest(source, "segment-0001", foreign)),
                SegmentAuthoringErrorCodes.EDIT_TARGET_INVALID);
    }

    @Test
    void anEditOfAnUnknownOrDifferentlyVersionedSegmentComponentIsRefused() {
        SegmentAuthoringSource source = SegmentAuthoringIntegrationFixtures.visual(
                SegmentAuthoringIntegrationFixtures.proof());

        assertFailsWithCode(() -> integration.author(
                        SegmentAuthoringIntegrationFixtures.editRequest(source, "segment-9999",
                                BuilderFixtures.current())),
                SegmentAuthoringErrorCodes.EDIT_TARGET_INVALID);

        TrustedCurrentCampaign otherVersion = BuilderFixtures.current(
                BuilderFixtures.component("segment-0001", SegmentAuthoringVersions.CAPABILITY_CODE, "0.9",
                        BuilderFixtures.value("label", "welcome")));
        assertFailsWithCode(() -> integration.author(
                        SegmentAuthoringIntegrationFixtures.editRequest(source, "segment-0001", otherVersion)),
                SegmentAuthoringErrorCodes.EDIT_TARGET_INVALID);
    }

    @Test
    void aRefinementOfAStaleBaseIsRefused() {
        SegmentAuthoringResult.Compiled created = compiled(
                SegmentAuthoringIntegrationFixtures.createRequest(
                        SegmentAuthoringIntegrationFixtures.ai(SegmentAuthoringIntegrationFixtures.proof())));

        // The refinement claims the refined document as its base, while the current component holds the
        // baseline document: the claimed provenance is false and the refined proof is never applied.
        SegmentAuthoringSource stale = SegmentAuthoringIntegrationFixtures.refinement(
                SegmentAuthoringIntegrationFixtures.refinedProof(),
                SegmentAuthoringIntegrationFixtures.refinedProof());

        assertFailsWithCode(() -> integration.author(SegmentAuthoringIntegrationFixtures.editRequest(
                        stale, created.logicalId(), currentOf(created.plan()))),
                SegmentAuthoringErrorCodes.REFINEMENT_BASE_STALE);
    }

    @Test
    void aRefinementCannotBeSubmittedAsACreate() {
        // A refinement preserves an existing Segment's identity, so a CREATE has no base to refine.
        assertFailsWithCode(() -> integration.author(
                        SegmentAuthoringIntegrationFixtures.createRequest(
                                SegmentAuthoringIntegrationFixtures.refinement(
                                        SegmentAuthoringIntegrationFixtures.proof(),
                                        SegmentAuthoringIntegrationFixtures.refinedProof()))),
                SegmentAuthoringErrorCodes.EDIT_TARGET_INVALID);
    }

    @Test
    void anEditTargetWithoutATrustedCurrentAndACreateTargetWithOneAreBothRefused() {
        SegmentAuthoringSource source = SegmentAuthoringIntegrationFixtures.visual(
                SegmentAuthoringIntegrationFixtures.proof());
        assertFailsWithCode(() -> new SegmentAuthoringRequest(
                        source, com.company.campaign.builder.proposal.ProposalIdentity.ofClientRef("client-1"),
                        BuilderFixtures.current(), SegmentAuthoringIntegrationFixtures.catalog(),
                        BuilderFixtures.policy(), SegmentAuthoringIntegrationFixtures.instants(),
                        SegmentAuthoringFixtures.context(),
                        SegmentAuthoringIntegrationFixtures.compileContext(
                                SegmentAuthoringIntegrationFixtures.proof(),
                                SegmentAuthoringFixtures.experimental())),
                SegmentAuthoringErrorCodes.REQUEST_INVALID);
        assertFailsWithCode(() -> new SegmentAuthoringRequest(
                        source, com.company.campaign.builder.proposal.ProposalIdentity.ofLogicalId("segment-1"),
                        null, SegmentAuthoringIntegrationFixtures.catalog(), BuilderFixtures.policy(),
                        SegmentAuthoringIntegrationFixtures.instants(), SegmentAuthoringFixtures.context(),
                        SegmentAuthoringIntegrationFixtures.compileContext(
                                SegmentAuthoringIntegrationFixtures.proof(),
                                SegmentAuthoringFixtures.experimental())),
                SegmentAuthoringErrorCodes.REQUEST_INVALID);
    }

    @Test
    void readinessGateAndTrustedTimeFactsCannotEnterAProposalPayload() {
        for (String reserved : List.of(
                "readiness", "gateInputsHash", "approvedDecisionHash",
                "asOf", "as_of", "AS-OF", "timezone", "timeZone", "dataWatermark", "data_watermark")) {
            assertFailsWithCode(() -> com.company.campaign.builder.compiler.CapabilityPayload.of(
                            java.util.Map.of(reserved, "sha256:" + "0".repeat(64))),
                    com.company.campaign.builder.compiler.CompilerErrorCodes.COMPILER_OUTPUT_INCONSISTENT);
        }
        // The three trusted time pins are reserved aliases in their own right, under any spelling.
        assertThat(com.company.campaign.builder.proposal.ProposalReservedFields.isReserved("asOf")).isTrue();
        assertThat(com.company.campaign.builder.proposal.ProposalReservedFields.isReserved("time-zone")).isTrue();
        assertThat(com.company.campaign.builder.proposal.ProposalReservedFields.isReserved("dataWatermark"))
                .isTrue();
    }

    @Test
    void aClientDeclaringATrustedTimePinIsRefusedByTheProposalBoundary() {
        // A client can only submit a Proposal patch, and a patch that declares a trusted time pin is refused
        // outright: the name is reserved, and the envelope has no such field, so it can never be silently
        // trusted as a compile fact.
        for (String declared : List.of("asOf", "timezone", "dataWatermark")) {
            java.util.Map<String, Object> forged = new java.util.LinkedHashMap<>(
                    SegmentAuthoringFixtures.envelope());
            forged.put(declared, "2026-10-03T00:00:00Z");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> compiler.compile(
                            CompileRequest.create(
                                    com.company.campaign.builder.testsupport.BuilderFixtures.createProposal()
                                            .pins(SegmentAuthoringIntegrationFixtures.catalog(),
                                                    BuilderFixtures.policy())
                                            .upsertNew(SegmentAuthoringVersions.CAPABILITY_CODE,
                                                    SegmentAuthoringFixtures.CLIENT_REF, forged)
                                            .build(),
                                    SegmentAuthoringIntegrationFixtures.catalog(), BuilderFixtures.policy(),
                                    SegmentAuthoringIntegrationFixtures.instants(),
                                    SegmentAuthoringFixtures.context(),
                                    SegmentAuthoringIntegrationFixtures.compileContext(
                                            SegmentAuthoringIntegrationFixtures.proof(),
                                            SegmentAuthoringFixtures.experimental()))))
                    .as(declared)
                    .isInstanceOf(com.company.campaign.builder.compiler.CompilerException.class)
                    .hasMessageNotContaining("2026-10-03T00:00:00Z");
        }
    }

    @Test
    void theSubmittedPayloadCarriesOnlyTheClosedSemanticEnvelopeFields() {
        SegmentAuthoringResult.Compiled result = compiled(
                SegmentAuthoringIntegrationFixtures.createRequest(
                        SegmentAuthoringIntegrationFixtures.ai(
                                SegmentAuthoringIntegrationFixtures.proof())));
        var payload = result.patch().operations().get(0).value();

        assertThat(payload.fields().keySet()).containsExactlyInAnyOrder(
                "envelopeVersion", "dslWire", "canonicalAstHash", "semanticPlanHash", "previewHash",
                "skillHash");
        // The DSL is one opaque canonical wire, and no field carries a raw member identifier, SQL, provider or
        // model output, clarification answer, conversation or trace fact, trusted identity, trusted time pin or
        // Gate declaration.
        assertThat(payload.fields().get("dslWire")).isInstanceOf(String.class);
        assertThat(payload.canonicalText())
                .doesNotContain("tenant-001")
                .doesNotContain("program-001")
                .doesNotContain("user-001")
                .doesNotContain("select ")
                .doesNotContain("SELECT ")
                .doesNotContain("asOf")
                .doesNotContain("timezone")
                .doesNotContain("dataWatermark")
                .doesNotContain("Asia/Shanghai");
        // The trusted time pins are host-owned compile facts: they are not serialized anywhere in Proposal
        // bytes, and the trusted context of this very compile carries them instead.
        assertThat(SegmentAuthoringFixtures.context().snapshotRef().asOf()).isNotNull();
        assertThat(SegmentAuthoringFixtures.context().snapshotRef().timezone()).isNotNull();
        assertThat(SegmentAuthoringFixtures.context().snapshotRef().dataWatermark()).isNotNull();
    }

    @Test
    void neitherThePlanNorItsCardLeaksATrustedOrBusinessValueThroughToString() {
        SegmentAuthoringResult.Compiled result = compiled(
                SegmentAuthoringIntegrationFixtures.createRequest(
                        SegmentAuthoringIntegrationFixtures.visual(
                                SegmentAuthoringIntegrationFixtures.proof())));
        PlanPreviewCard card = result.plan().previewCards().get(0);

        for (String rendered : List.of(
                result.toString(), result.plan().toString(), card.toString(),
                result.patch().toString(), card.componentLogicalId())) {
            assertThat(rendered)
                    .doesNotContain("tenant-001")
                    .doesNotContain("program-001")
                    .doesNotContain("user-001")
                    .doesNotContain("scope-001")
                    .doesNotContain("member.tier")
                    .doesNotContain("order.net_paid_amount")
                    .doesNotContain("GOLD")
                    .doesNotContain("CNY");
        }
    }

    // ------------------------------------------------------------------ redaction

    @Test
    void noTrustedValueOrBusinessLiteralLeaksThroughToStringOrAFailure() {
        SegmentAuthoringResult terminal = integration.author(
                SegmentAuthoringIntegrationFixtures.createRequest(
                        SegmentAuthoringIntegrationFixtures.terminalAi(Disposition.DENIED)));
        assertThat(terminal.toString())
                .doesNotContain("tenant-001")
                .doesNotContain("program-001")
                .doesNotContain("user-001")
                .doesNotContain("member.tier")
                .doesNotContain("GOLD")
                .doesNotContain("sha256:");

        assertFailsWithCode(() -> integration.author(
                        SegmentAuthoringIntegrationFixtures.createRequest(
                                new SegmentAuthoringSource.AiResolved(
                                        SegmentAuthoringIntegrationFixtures.agentResult(
                                                SegmentAuthoringIntegrationFixtures.proof()),
                                        SegmentAuthoringIntegrationFixtures.refinedProof()))),
                SegmentAuthoringErrorCodes.SOURCE_MISMATCH);
        assertThatThrownBy(() -> integration.author(
                SegmentAuthoringIntegrationFixtures.createRequest(
                        new SegmentAuthoringSource.AiResolved(
                                SegmentAuthoringIntegrationFixtures.agentResult(
                                        SegmentAuthoringIntegrationFixtures.proof()),
                                SegmentAuthoringIntegrationFixtures.refinedProof()))))
                .hasMessageNotContaining("member.tier")
                .hasMessageNotContaining("GOLD")
                .hasMessageNotContaining("tenant-001");
    }

    // ------------------------------------------------------------------ helpers

    private SegmentAuthoringResult.Compiled compiled(SegmentAuthoringRequest request) {
        return (SegmentAuthoringResult.Compiled) integration.author(request);
    }

    private static void assertNoOutput(SegmentAuthoringResult result) {
        assertThat(result).isNotInstanceOf(SegmentAuthoringResult.Compiled.class);
        assertThat(result.toString()).doesNotContain("sha256:");
    }

    private static void assertFailsWithCode(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable callable, String expectedCode) {
        assertThatThrownBy(callable)
                .isInstanceOf(com.company.campaign.shared.error.ContractException.class)
                .satisfies(throwable ->
                        assertThat(((com.company.campaign.shared.error.ContractException) throwable).code())
                                .isEqualTo(expectedCode));
    }

    private static TrustedCurrentCampaign currentOf(CampaignPlan plan) {
        return BuilderFixtures.current(plan.manifest().components().toArray(new ResolvedComponent[0]));
    }
}