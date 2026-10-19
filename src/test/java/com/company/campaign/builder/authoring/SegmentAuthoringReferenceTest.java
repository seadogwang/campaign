package com.company.campaign.builder.authoring;

import com.company.campaign.builder.authoring.testsupport.SegmentAuthoringFixtures;
import com.company.campaign.builder.authoring.testsupport.SegmentAuthoringIntegrationFixtures;
import com.company.campaign.builder.compiler.CampaignCompiler;
import com.company.campaign.builder.compiler.CampaignPlan;
import com.company.campaign.builder.compiler.CapabilityCompileContext;
import com.company.campaign.builder.compiler.CapabilityResolution;
import com.company.campaign.builder.compiler.PlanPreviewCard;
import com.company.campaign.builder.compiler.ReferencePlanEvidence;
import com.company.campaign.builder.compiler.ResolvedComponent;
import com.company.campaign.builder.compiler.TrustedCurrentCampaign;
import com.company.campaign.builder.proposal.ProposalIdentity;
import com.company.campaign.builder.testsupport.BuilderFixtures;
import com.company.campaign.segment.dsl.codec.SegmentDslCodec;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T18 section 7 focused completion tests for the dedicated Existing Audience Reference evidence of a Segment
 * authoring compile.
 *
 * <p>Four properties are proved here. First, the three server-owned source kinds — resolved AI, resolved
 * Visual Builder and stable-node refinement — converge on one artifact <em>and</em> on the one reference
 * evidence of the compile, so a source kind is an audit fact only. Second, the reference-dependency hash is
 * the whole sensitivity of that evidence: any change to the referenced version dependencies, or to the
 * dedicated reference inputs, moves the pins and breaks the exact-match test. Third, the pins carry the
 * dedicated {@code segment.existing-audience-reference} capability identity and no production-ready
 * readiness state exists in their closed vocabulary. Fourth, a reference-free Segment keeps the accepted
 * bytes it always had and carries no reference evidence at all.
 *
 * <p>The reference-bearing compile is also pinned to what the accepted boundaries actually require: a Segment
 * that depends on a referenced immutable Audience Version is resolved only through the host-owned reference
 * context — the five-argument validation entry point and the four-argument planning entry point — while a
 * reference-free Segment keeps the accepted reference-free overloads and bytes and never consults a reference
 * port. The host context is required exactly when the document has references, and the compile's dedicated
 * reference pins must rest on the canonical hash of the plan's own frozen dependencies. Nothing here
 * manufactures a reference readiness: the material stays development-only.
 */
class SegmentAuthoringReferenceTest {

    /** The hash a plan's referenced version dependencies resolve to under the standard fixtures. */
    private static final ContentHash DEPENDENCIES_HASH = hash("reference-dependencies-1");

    /** A second dependency hash, standing in for one changed referenced version. */
    private static final ContentHash OTHER_DEPENDENCIES_HASH = hash("reference-dependencies-2");

    /** The hash of the dedicated reference Gate inputs one compile ran against. */
    private static final ContentHash REFERENCE_INPUTS_HASH = hash("reference-inputs-1");

    /** A second reference-inputs hash, standing in for a stale or mismatched reference readiness. */
    private static final ContentHash OTHER_REFERENCE_INPUTS_HASH = hash("reference-inputs-2");

    private static final SegmentDslCodec DSL_CODEC = new SegmentDslCodec();

    private final SegmentAuthoringIntegration integration =
            new SegmentAuthoringIntegration(new CampaignCompiler(SegmentValidationFixtures.allowAll()));

    private final SegmentRuleBasedCapability contract = SegmentAuthoringFixtures.contract();

    // ------------------------------------------------------------- convergence of the three sources

    @Test
    void allThreeSourcesConvergeOnOneArtifactAndOneReferenceEvidence() {
        SegmentAuthoringResult.Compiled created = compiled(SegmentAuthoringIntegrationFixtures.createRequest(
                SegmentAuthoringIntegrationFixtures.ai(SegmentAuthoringIntegrationFixtures.proof())));
        String logicalId = created.logicalId();
        TrustedCurrentCampaign current = currentOf(created.plan());

        SegmentAuthoringProof refined = SegmentAuthoringIntegrationFixtures.refinedProof();
        SegmentAuthoringResult.Compiled viaAi = compiled(SegmentAuthoringIntegrationFixtures.editRequest(
                SegmentAuthoringIntegrationFixtures.ai(refined), logicalId, current));
        SegmentAuthoringResult.Compiled viaVisual = compiled(SegmentAuthoringIntegrationFixtures.editRequest(
                SegmentAuthoringIntegrationFixtures.visual(refined), logicalId, current));
        SegmentAuthoringResult.Compiled viaRefinement = compiled(SegmentAuthoringIntegrationFixtures.editRequest(
                SegmentAuthoringIntegrationFixtures.refinement(
                        SegmentAuthoringIntegrationFixtures.proof(), refined),
                logicalId, current));

        for (SegmentAuthoringResult.Compiled candidate : List.of(viaVisual, viaRefinement)) {
            assertThat(candidate.patch().canonicalBytes()).isEqualTo(viaAi.patch().canonicalBytes());
            assertThat(candidate.patch().proposalHash()).isEqualTo(viaAi.patch().proposalHash());
            assertThat(candidate.plan().manifestHash()).isEqualTo(viaAi.plan().manifestHash());
            assertThat(candidate.plan().planHash()).isEqualTo(viaAi.plan().planHash());
            assertThat(candidate.plan().canonicalMaterialBytes())
                    .isEqualTo(viaAi.plan().canonicalMaterialBytes());
            assertThat(candidate.plan().previewCards()).isEqualTo(viaAi.plan().previewCards());
            assertThat(candidate.logicalId()).isEqualTo(viaAi.logicalId());
            // The source kind never enters the artifact, and it never re-types the compile's reference
            // evidence: the one host input bundle is what every source converges on.
            assertThat(candidate.referencePins()).isEqualTo(viaAi.referencePins());
            assertThat(candidate.hasReferenceEvidence()).isEqualTo(viaAi.hasReferenceEvidence());
        }
        // This Segment depends on no referenced Audience Version, so convergence carries no reference
        // evidence at all — and the refined card is still the refined one, so the three sources really did
        // compile the same refined artifact rather than the same unchanged one.
        assertThat(viaAi.hasReferenceEvidence()).isFalse();
        assertThat(viaAi.plan().previewCards().get(0).previewHash())
                .isEqualTo(SegmentAuthoringIntegrationFixtures.REFINED_PREVIEW_HASH);
    }

    // ------------------------------------------------------------- reference-dependency sensitivity

    @Test
    void theReferenceDependencyHashIsTheWholeSensitivityOfTheEvidence() {
        SegmentAuthoringReferencePins pins =
                SegmentAuthoringReferenceReadiness.developmentPins(DEPENDENCIES_HASH, REFERENCE_INPUTS_HASH);

        // The exact pair of host facts is what the pins rest on.
        assertThat(pins.matches(DEPENDENCIES_HASH, REFERENCE_INPUTS_HASH)).isTrue();
        assertThat(pins.referenceDependenciesHash()).isEqualTo(DEPENDENCIES_HASH);
        assertThat(pins.referenceInputsHash()).isEqualTo(REFERENCE_INPUTS_HASH);

        // One changed referenced version is a different dependency graph, so the pins are different pins and
        // the exact-match test the authoring path uses no longer holds.
        SegmentAuthoringReferencePins changedGraph =
                SegmentAuthoringReferenceReadiness.developmentPins(OTHER_DEPENDENCIES_HASH, REFERENCE_INPUTS_HASH);
        assertThat(changedGraph.referenceDependenciesHash()).isNotEqualTo(DEPENDENCIES_HASH);
        assertThat(changedGraph).isNotEqualTo(pins);
        assertThat(pins.matches(OTHER_DEPENDENCIES_HASH, REFERENCE_INPUTS_HASH)).isFalse();

        // A stale or mismatched reference-input revision is observable the same way, even under one graph.
        SegmentAuthoringReferencePins staleInputs = SegmentAuthoringReferenceReadiness.developmentPins(
                DEPENDENCIES_HASH, OTHER_REFERENCE_INPUTS_HASH);
        assertThat(staleInputs).isNotEqualTo(pins);
        assertThat(pins.matches(DEPENDENCIES_HASH, OTHER_REFERENCE_INPUTS_HASH)).isFalse();

        // The same two facts reproduce the same pins byte for byte, whatever produced them.
        assertThat(SegmentAuthoringReferenceReadiness.developmentPins(DEPENDENCIES_HASH, REFERENCE_INPUTS_HASH))
                .isEqualTo(pins);
    }

    // ------------------------------------------------------------- the dedicated capability identity

    @Test
    void theReferencePinsCarryTheDedicatedCapabilityIdentity() {
        SegmentAuthoringReferencePins pins =
                SegmentAuthoringReferenceReadiness.developmentPins(DEPENDENCIES_HASH, REFERENCE_INPUTS_HASH);

        assertThat(pins.capabilityCode())
                .isEqualTo(SegmentAuthoringVersions.REFERENCE_CAPABILITY_CODE)
                .isEqualTo("segment.existing-audience-reference");
        // The card-level evidence restates the same closed identity, so the plan material can never carry a
        // reference capability identity the authoring path does not recognise.
        assertThat(ReferencePlanEvidence.CAPABILITY_ID)
                .isEqualTo(SegmentAuthoringVersions.REFERENCE_CAPABILITY_CODE)
                .isEqualTo(pins.capabilityCode());
        // The reference extension is its own Gate identity: an approval of segment.rule-based is not an
        // approval of this extension, so the two identities must never collapse into one another.
        assertThat(pins.capabilityCode()).isNotEqualTo(SegmentAuthoringVersions.CAPABILITY_CODE);
        assertThat(pins.developmentOnly()).isTrue();
    }

    // ------------------------------------------------------------- development-only readiness states

    @Test
    void theReferenceReadinessVocabularyHasNoProductionReadyState() {
        SegmentAuthoringReferencePins experimental = SegmentAuthoringReferenceReadiness.pins(
                DEPENDENCIES_HASH, REFERENCE_INPUTS_HASH, SegmentAuthoringReferencePins.EXPERIMENTAL);
        assertThat(experimental.readinessState()).isEqualTo("EXPERIMENTAL");
        assertThat(experimental.developmentOnly()).isFalse();

        SegmentAuthoringReferencePins development =
                SegmentAuthoringReferenceReadiness.developmentPins(DEPENDENCIES_HASH, REFERENCE_INPUTS_HASH);
        assertThat(development.readinessState()).isEqualTo(SegmentAuthoringVersions.REFERENCE_DEVELOPMENT_ONLY);
        assertThat(development.developmentOnly()).isTrue();

        // No approved reference Gate decision exists, so ACTIVE is not merely discouraged — it is refused as
        // the malformed input it would be, and so is every other state outside the closed development pair.
        for (String refused : List.of("ACTIVE", "PRODUCTION", "PASSED", "EVALUATION_REQUIRED", "active")) {
            assertThatThrownBy(() -> SegmentAuthoringReferenceReadiness.pins(
                    DEPENDENCIES_HASH, REFERENCE_INPUTS_HASH, refused))
                    .as(refused)
                    .isInstanceOf(SegmentAuthoringException.class)
                    .extracting(thrown -> ((SegmentAuthoringException) thrown).code())
                    .isEqualTo(SegmentAuthoringErrorCodes.READINESS_INVALID);
        }
        assertThatThrownBy(() -> SegmentAuthoringReferenceReadiness.pins(
                DEPENDENCIES_HASH, REFERENCE_INPUTS_HASH, null))
                .isInstanceOf(SegmentAuthoringException.class);

        // The pins themselves enforce the same closed vocabulary, so an ACTIVE pin is not representable even
        // when the readiness adapter is bypassed.
        assertThatThrownBy(() -> new SegmentAuthoringReferencePins(
                SegmentAuthoringVersions.REFERENCE_CAPABILITY_CODE, "ACTIVE",
                DEPENDENCIES_HASH, REFERENCE_INPUTS_HASH))
                .isInstanceOf(SegmentAuthoringException.class)
                .extracting(thrown -> ((SegmentAuthoringException) thrown).code())
                .isEqualTo(SegmentAuthoringErrorCodes.READINESS_INVALID);

        // A reference pin without its two host hashes is not evidence of anything.
        assertThatThrownBy(() -> SegmentAuthoringReferenceReadiness.pins(
                null, REFERENCE_INPUTS_HASH, SegmentAuthoringReferencePins.EXPERIMENTAL))
                .isInstanceOf(SegmentAuthoringException.class)
                .extracting(thrown -> ((SegmentAuthoringException) thrown).code())
                .isEqualTo(SegmentAuthoringErrorCodes.READINESS_INVALID);
    }

    // ------------------------------------------------------------- byte-compatible no-reference evidence

    @Test
    void aReferenceFreeSegmentKeepsItsAcceptedBytesAndCarriesNoReferenceEvidence() {
        SegmentAuthoringResult.Compiled first = compiled(SegmentAuthoringIntegrationFixtures.createRequest(
                SegmentAuthoringIntegrationFixtures.ai(SegmentAuthoringIntegrationFixtures.proof())));
        SegmentAuthoringResult.Compiled second = compiled(SegmentAuthoringIntegrationFixtures.createRequest(
                SegmentAuthoringIntegrationFixtures.visual(SegmentAuthoringIntegrationFixtures.proof())));

        // No reference evidence is attached, so nothing about the reference extension reaches the artifact.
        assertThat(first.referencePins()).isNull();
        assertThat(first.hasReferenceEvidence()).isFalse();
        assertThat(first.patch().canonicalBytes()).isEqualTo(second.patch().canonicalBytes());
        assertThat(first.plan().canonicalMaterialBytes()).isEqualTo(second.plan().canonicalMaterialBytes());
        assertThat(first.plan().previewCards()).isEqualTo(second.plan().previewCards());
        // The card carries no reference evidence at all — not a zero-valued or empty one — so its material is
        // the accepted reference-free material and no reference fact enters the plan hash.
        assertThat(first.plan().previewCards().get(0).referenceEvidence()).isNull();

        // The host input bundle of a reference-free compile is the accepted bundle exactly: adding the
        // optional reference-pins argument with no pins changes no byte of it.
        SegmentAuthoringInputs accepted = new SegmentAuthoringInputs(
                SegmentAuthoringFixtures.snapshot(), SegmentAuthoringFixtures.card(),
                SegmentAuthoringFixtures.SKILL_HASH);
        SegmentAuthoringInputs withAbsentPins = new SegmentAuthoringInputs(
                SegmentAuthoringFixtures.snapshot(), SegmentAuthoringFixtures.card(),
                SegmentAuthoringFixtures.SKILL_HASH, null);
        assertThat(withAbsentPins).isEqualTo(accepted);
        assertThat(accepted.hasReferencePins()).isFalse();
        assertThat(withAbsentPins.hasReferencePins()).isFalse();
        assertThat(withAbsentPins.toString()).isEqualTo(accepted.toString());
        assertThat(accepted.toString()).contains("referencePins=none");
    }

    @Test
    void aReferenceFreeSegmentThatDeclaresReferencePinsIsRefusedRatherThanSelfDeclaringADependency() {
        // The pins and the plan's own reference dependencies must be present together or absent together: a
        // reference-free plan carrying reference readiness would claim a dependency it does not have.
        CapabilityCompileContext declaring = new CapabilityCompileContext(
                List.of(SegmentAuthoringFixtures.experimental()),
                List.of(new SegmentAuthoringInputs(
                        SegmentAuthoringFixtures.snapshot(), SegmentAuthoringFixtures.card(),
                        SegmentAuthoringFixtures.SKILL_HASH,
                        SegmentAuthoringReferenceReadiness.developmentPins(
                                DEPENDENCIES_HASH, REFERENCE_INPUTS_HASH))));

        CapabilityResolution resolution = contract.resolve(SegmentAuthoringFixtures.request(
                SegmentAuthoringFixtures.envelope(), SegmentAuthoringFixtures.context(), declaring));
        assertThat(resolution).isInstanceOf(CapabilityResolution.Failed.class);
        List<com.company.campaign.contracts.Issue> issues =
                ((CapabilityResolution.Failed) resolution).issues();
        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).code()).isEqualTo(SegmentAuthoringErrorCodes.EVIDENCE_MISMATCH);
    }

    @Test
    void aClientOrModelCannotDeclareAReferenceReadinessFactInTheEnvelope() {
        // A readiness fact smuggled in under a host-owned name never becomes a payload at all: the payload
        // boundary refuses the reserved name outright.
        for (String declared : List.of(
                "readinessState", "readiness", "gateInputsHash", "approvedDecisionHash")) {
            Map<String, Object> forged = new LinkedHashMap<>(SegmentAuthoringFixtures.envelope());
            forged.put(declared, "sha256:" + "0".repeat(64));
            assertThatThrownBy(() -> com.company.campaign.builder.compiler.CapabilityPayload.of(forged))
                    .as(declared)
                    .isInstanceOf(com.company.campaign.builder.compiler.CompilerException.class)
                    .hasMessageNotContaining("0000000000000000");
        }

        // A reference name the envelope does not declare is refused by the closed envelope itself, and the
        // refusal is one bounded issue that never echoes the supplied value.
        for (String declared : List.of(
                "referenceDependenciesHash", "referenceInputsHash", "referencePins",
                "capabilityCode", "dependencyGraphHash")) {
            Map<String, Object> forged = new LinkedHashMap<>(SegmentAuthoringFixtures.envelope());
            forged.put(declared, "sha256:" + "0".repeat(64));
            CapabilityResolution resolution = contract.resolve(SegmentAuthoringFixtures.request(forged));
            assertThat(resolution).as(declared).isInstanceOf(CapabilityResolution.Failed.class);
            List<com.company.campaign.contracts.Issue> issues =
                    ((CapabilityResolution.Failed) resolution).issues();
            assertThat(issues).as(declared).hasSize(1);
            assertThat(issues.get(0).code()).as(declared)
                    .isEqualTo(SegmentAuthoringErrorCodes.ENVELOPE_INVALID);
            assertThat(issues.get(0).toString()).as(declared).doesNotContain("0000000000000000");
        }
    }

    // ------------------------------------------------------------- end-to-end reference-bearing compile

    @Test
    void referenceBearingSourcesConvergeOnOneArtifactAndOneReferenceEvidence() {
        SegmentAuthoringProof base = SegmentAuthoringIntegrationFixtures.referenceProof();
        SegmentAuthoringResult.Compiled created = compiled(
                SegmentAuthoringIntegrationFixtures.referenceCreateRequest(
                        SegmentAuthoringIntegrationFixtures.ai(base), base));
        String logicalId = created.logicalId();
        TrustedCurrentCampaign current = currentOf(created.plan());

        SegmentAuthoringProof refined = SegmentAuthoringIntegrationFixtures.refinedReferenceProof();
        SegmentAuthoringResult.Compiled viaAi = compiled(
                SegmentAuthoringIntegrationFixtures.referenceEditRequest(
                        SegmentAuthoringIntegrationFixtures.ai(refined), logicalId, current, refined));
        SegmentAuthoringResult.Compiled viaVisual = compiled(
                SegmentAuthoringIntegrationFixtures.referenceEditRequest(
                        SegmentAuthoringIntegrationFixtures.visual(refined), logicalId, current, refined));
        SegmentAuthoringResult.Compiled viaRefinement = compiled(
                SegmentAuthoringIntegrationFixtures.referenceEditRequest(
                        SegmentAuthoringIntegrationFixtures.refinement(base, refined),
                        logicalId, current, refined));

        for (SegmentAuthoringResult.Compiled candidate : List.of(viaVisual, viaRefinement)) {
            assertThat(candidate.patch().canonicalBytes()).isEqualTo(viaAi.patch().canonicalBytes());
            assertThat(candidate.patch().proposalHash()).isEqualTo(viaAi.patch().proposalHash());
            assertThat(candidate.plan().manifestHash()).isEqualTo(viaAi.plan().manifestHash());
            assertThat(candidate.plan().planHash()).isEqualTo(viaAi.plan().planHash());
            assertThat(candidate.plan().canonicalMaterialBytes())
                    .isEqualTo(viaAi.plan().canonicalMaterialBytes());
            assertThat(candidate.plan().previewCards()).isEqualTo(viaAi.plan().previewCards());
            assertThat(candidate.logicalId()).isEqualTo(viaAi.logicalId());
            // The source kind never re-types the compile's reference evidence: the one host input bundle is
            // what every source converges on, development-only reference pins included.
            assertThat(candidate.referencePins()).isEqualTo(viaAi.referencePins());
            assertThat(candidate.hasReferenceEvidence()).isEqualTo(viaAi.hasReferenceEvidence());
            // And the one card of every source carries that same evidence, byte for byte.
            assertThat(candidate.plan().previewCards().get(0).referenceEvidence())
                    .isNotNull()
                    .isEqualTo(viaAi.plan().previewCards().get(0).referenceEvidence());
        }

        // The compile really is reference-bearing: the canonical AST, the Semantic Plan and the dedicated
        // reference pins are the reference-aware ones, not a reference-free artifact reinterpreted.
        assertThat(viaAi.hasReferenceEvidence()).isTrue();
        assertThat(viaAi.referencePins().developmentOnly()).isTrue();
        assertThat(viaAi.referencePins().capabilityCode())
                .isEqualTo(SegmentAuthoringVersions.REFERENCE_CAPABILITY_CODE);
        PlanPreviewCard card = viaAi.plan().previewCards().get(0);
        assertThat(card.canonicalAstHash()).isEqualTo(refined.validation().canonicalAstHash());
        assertThat(card.semanticPlanHash()).isEqualTo(refined.plan().planHash());
        assertThat(card.previewHash()).isEqualTo(SegmentAuthoringIntegrationFixtures.REFINED_PREVIEW_HASH);
        // The pins rest on the canonical hash of the plan's own frozen reference dependencies.
        assertThat(viaAi.referencePins().referenceDependenciesHash())
                .isEqualTo(SegmentAuthoringIntegrationFixtures.referenceDependenciesHash(refined.plan()));

        // The card carries the compile's dedicated reference evidence: the plan material now covers which
        // dedicated reference identity, which dependency graph and which reference inputs the compile ran
        // against, so a reference-bearing plan is no longer indistinguishable from a reference-free one.
        ReferencePlanEvidence evidence = card.referenceEvidence();
        assertThat(evidence).isNotNull();
        assertThat(evidence.capabilityId())
                .isEqualTo(ReferencePlanEvidence.CAPABILITY_ID)
                .isEqualTo(SegmentAuthoringVersions.REFERENCE_CAPABILITY_CODE)
                .isEqualTo("segment.existing-audience-reference");
        assertThat(evidence.readinessState()).isEqualTo(SegmentAuthoringVersions.REFERENCE_DEVELOPMENT_ONLY);
        assertThat(evidence.developmentOnly()).isTrue();
        // The evidence is the host pins exactly, never re-derived by the source or the contract.
        SegmentAuthoringReferencePins pins = viaAi.referencePins();
        assertThat(evidence.referenceDependenciesHash()).isEqualTo(pins.referenceDependenciesHash());
        assertThat(evidence.referenceInputsHash()).isEqualTo(pins.referenceInputsHash());
        assertThat(evidence.referenceInputsHash())
                .isEqualTo(SegmentAuthoringIntegrationFixtures.REFERENCE_INPUTS_HASH);
    }

    // ------------------------------------------------------------- hash sensitivity of the card evidence

    @Test
    void theCardReferenceEvidenceIsHashSensitiveAndReachesThePlanMaterial() {
        SegmentAuthoringProof proof = SegmentAuthoringIntegrationFixtures.referenceProof();
        SegmentAuthoringReferencePins acceptedPins =
                SegmentAuthoringIntegrationFixtures.referencePins(proof);

        SegmentAuthoringResult.Compiled accepted = compiled(referenceCreate(
                SegmentAuthoringIntegrationFixtures.ai(proof), proof, acceptedPins));
        // One changed referenced version, or one stale reference-input revision, is a different evidence.
        SegmentAuthoringResult.Compiled changedGraph = compiled(referenceCreate(
                SegmentAuthoringIntegrationFixtures.ai(proof), proof,
                SegmentAuthoringReferenceReadiness.developmentPins(
                        OTHER_DEPENDENCIES_HASH, REFERENCE_INPUTS_HASH)));
        SegmentAuthoringResult.Compiled changedInputs = compiled(referenceCreate(
                SegmentAuthoringIntegrationFixtures.ai(proof), proof,
                SegmentAuthoringReferenceReadiness.developmentPins(
                        acceptedPins.referenceDependenciesHash(), OTHER_REFERENCE_INPUTS_HASH)));

        PlanPreviewCard acceptedCard = accepted.plan().previewCards().get(0);
        assertThat(acceptedCard.referenceEvidence().referenceDependenciesHash())
                .isEqualTo(acceptedPins.referenceDependenciesHash());
        assertThat(acceptedCard.referenceEvidence().referenceInputsHash())
                .isEqualTo(acceptedPins.referenceInputsHash())
                .isEqualTo(SegmentAuthoringIntegrationFixtures.REFERENCE_INPUTS_HASH);

        for (SegmentAuthoringResult.Compiled changed : List.of(changedGraph, changedInputs)) {
            PlanPreviewCard changedCard = changed.plan().previewCards().get(0);
            // Only the evidence moved: every other card fact, and the artifact the source produced, is equal.
            assertThat(changedCard.referenceEvidence()).isNotEqualTo(acceptedCard.referenceEvidence());
            assertThat(changedCard.previewHash()).isEqualTo(acceptedCard.previewHash());
            assertThat(changedCard.canonicalAstHash()).isEqualTo(acceptedCard.canonicalAstHash());
            assertThat(changed.patch().proposalHash()).isEqualTo(accepted.patch().proposalHash());
            // The card is part of the plan material, so the evidence is hash-sensitive all the way up: two
            // plans that differ only in their reference evidence are different plans.
            assertThat(changed.plan().planHash()).isNotEqualTo(accepted.plan().planHash());
            assertThat(changed.plan().planId()).isNotEqualTo(accepted.plan().planId());
        }
        // The same host facts reproduce the same plan material byte for byte.
        assertThat(SegmentAuthoringIntegrationFixtures.referencePins(proof)).isEqualTo(acceptedPins);
    }

    // ------------------------------------------------------------- the evidence is host-owned only

    @Test
    void aClientOrModelCannotInjectTheCardReferenceEvidence() {
        // A reference-evidence name the closed envelope does not declare never becomes a payload at all, so no
        // client or model value can ever reach the card's evidence slot.
        for (String declared : List.of(
                "referenceEvidence", "referencePlanEvidence", "referenceEvidenceState", "readinessEvidence",
                "referenceDependencyEvidence")) {
            Map<String, Object> forged = new LinkedHashMap<>(SegmentAuthoringFixtures.envelope());
            forged.put(declared, "segment.existing-audience-reference");
            CapabilityResolution resolution = contract.resolve(SegmentAuthoringFixtures.request(forged));
            assertThat(resolution).as(declared).isInstanceOf(CapabilityResolution.Failed.class);
            List<com.company.campaign.contracts.Issue> issues =
                    ((CapabilityResolution.Failed) resolution).issues();
            assertThat(issues).as(declared).hasSize(1);
            assertThat(issues.get(0).code()).as(declared)
                    .isEqualTo(SegmentAuthoringErrorCodes.ENVELOPE_INVALID);
            assertThat(issues.get(0).toString()).as(declared)
                    .doesNotContain("segment.existing-audience-reference");
        }

        // The genuine evidence is exactly the host pins the compile was handed, and a host pin that is not the
        // dedicated reference identity is refused rather than written onto the card.
        SegmentAuthoringProof proof = SegmentAuthoringIntegrationFixtures.referenceProof();
        SegmentAuthoringReferencePins foreignIdentity = new SegmentAuthoringReferencePins(
                SegmentAuthoringVersions.CAPABILITY_CODE, SegmentAuthoringReferencePins.DEVELOPMENT_ONLY,
                DEPENDENCIES_HASH, REFERENCE_INPUTS_HASH);
        CapabilityResolution resolution = contract.resolve(SegmentAuthoringFixtures.request(
                referenceEnvelope(proof), SegmentAuthoringFixtures.context(),
                new CapabilityCompileContext(List.of(SegmentAuthoringFixtures.experimental()),
                        List.of(referenceInputsOf(proof, foreignIdentity)))));
        assertThat(resolution).isInstanceOf(CapabilityResolution.Failed.class);
        List<com.company.campaign.contracts.Issue> issues =
                ((CapabilityResolution.Failed) resolution).issues();
        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).code()).isEqualTo(SegmentAuthoringErrorCodes.EVIDENCE_MISMATCH);
        assertThat(issues.get(0).toString()).doesNotContain(SegmentAuthoringVersions.CAPABILITY_CODE);
    }

    // ------------------------------------------------------------- the host reference context is required

    @Test
    void aReferenceBearingCompileRequiresTheHostReferenceContextAndFailsClosedWithoutIt() {
        // A reference-bearing document can only be resolved through the host's trusted reference ports. A
        // compile that pins none fails closed rather than reaching a reference-free boundary, and the failure
        // never echoes the referenced audience or version it refused.
        SegmentAuthoringProof proof = SegmentAuthoringIntegrationFixtures.referenceProof();
        CapabilityCompileContext pinned = new CapabilityCompileContext(
                List.of(SegmentAuthoringFixtures.experimental()),
                List.of(SegmentAuthoringIntegrationFixtures.referenceInputsWithoutHostContext(proof)));

        CapabilityResolution resolution = contract.resolve(SegmentAuthoringFixtures.request(
                referenceEnvelope(proof), SegmentAuthoringFixtures.context(), pinned));
        assertThat(resolution).isInstanceOf(CapabilityResolution.Failed.class);
        List<com.company.campaign.contracts.Issue> issues =
                ((CapabilityResolution.Failed) resolution).issues();
        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).code()).isEqualTo(SegmentAuthoringErrorCodes.REFERENCE_CONTEXT_MISSING);
        assertThat(issues.get(0).toString()).doesNotContain("audience-vip").doesNotContain("version-7");
    }

    @Test
    void aReferenceBearingCompileFailsClosedWhenTheHostContextCannotResolveTheVersion() {
        // The host context is present, but its trusted catalog holds no such version. The accepted validation
        // boundary then rejects the document, so no artifact is produced and nothing about the reference leaks.
        SegmentAuthoringProof proof = SegmentAuthoringIntegrationFixtures.referenceProof();
        SegmentAuthoringInputs inputs = new SegmentAuthoringInputs(
                SegmentAuthoringFixtures.snapshot(), proof.previewCard(), proof.skillHash(),
                SegmentAuthoringIntegrationFixtures.referencePins(proof),
                SegmentAuthoringReferenceContext.of(
                        SegmentAuthoringIntegrationFixtures.unresolvableReferenceExecutionContext()));
        CapabilityCompileContext pinned = new CapabilityCompileContext(
                List.of(SegmentAuthoringFixtures.experimental()), List.of(inputs));

        CapabilityResolution resolution = contract.resolve(SegmentAuthoringFixtures.request(
                referenceEnvelope(proof), SegmentAuthoringFixtures.context(), pinned));
        assertThat(resolution).isInstanceOf(CapabilityResolution.Failed.class);
        List<com.company.campaign.contracts.Issue> issues =
                ((CapabilityResolution.Failed) resolution).issues();
        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).code()).isEqualTo(SegmentAuthoringErrorCodes.DSL_INVALID);
        assertThat(issues.get(0).toString()).doesNotContain("audience-vip").doesNotContain("version-7");
    }

    @Test
    void aReferenceFreeCompileThatPinsAHostReferenceContextIsRefusedRatherThanConsultingReferencePorts() {
        // The host context is present exactly when the document depends on a reference. A reference-free
        // document that pins one would silently never consult its ports, so the disagreement is refused.
        CapabilityCompileContext declaring = new CapabilityCompileContext(
                List.of(SegmentAuthoringFixtures.experimental()),
                List.of(new SegmentAuthoringInputs(
                        SegmentAuthoringFixtures.snapshot(), SegmentAuthoringFixtures.card(),
                        SegmentAuthoringFixtures.SKILL_HASH, null,
                        SegmentAuthoringReferenceContext.of(
                                SegmentAuthoringIntegrationFixtures.referenceExecutionContext()))));

        CapabilityResolution resolution = contract.resolve(SegmentAuthoringFixtures.request(
                SegmentAuthoringFixtures.envelope(), SegmentAuthoringFixtures.context(), declaring));
        assertThat(resolution).isInstanceOf(CapabilityResolution.Failed.class);
        List<com.company.campaign.contracts.Issue> issues =
                ((CapabilityResolution.Failed) resolution).issues();
        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).code()).isEqualTo(SegmentAuthoringErrorCodes.EVIDENCE_MISMATCH);
    }

    @Test
    void neitherAClientNorAModelCanInjectTheHostReferenceContext() {
        // A host context smuggled in under a name the closed envelope does not declare never becomes a payload
        // at all: the envelope refuses it outright and echoes nothing it carried.
        for (String declared : List.of(
                "referenceContext", "referenceExecutionContext", "referenceValidationContext",
                "candidateRootVersion", "referencePorts")) {
            Map<String, Object> forged = new LinkedHashMap<>(SegmentAuthoringFixtures.envelope());
            forged.put(declared, "sha256:" + "0".repeat(64));
            CapabilityResolution resolution = contract.resolve(SegmentAuthoringFixtures.request(forged));
            assertThat(resolution).as(declared).isInstanceOf(CapabilityResolution.Failed.class);
            List<com.company.campaign.contracts.Issue> issues =
                    ((CapabilityResolution.Failed) resolution).issues();
            assertThat(issues).as(declared).hasSize(1);
            assertThat(issues.get(0).code()).as(declared)
                    .isEqualTo(SegmentAuthoringErrorCodes.ENVELOPE_INVALID);
            assertThat(issues.get(0).toString()).as(declared).doesNotContain("0000000000000000");
        }

        // The host context's own diagnostic states presence only: no port, catalog, audience or version.
        SegmentAuthoringReferenceContext context = SegmentAuthoringReferenceContext.of(
                SegmentAuthoringIntegrationFixtures.referenceExecutionContext());
        assertThat(context.toString())
                .contains("present")
                .doesNotContain("audience-vip").doesNotContain("version-7")
                .doesNotContain("catalog").doesNotContain("scope-");

        // And a reference-bearing compile's normalized payload is exactly the closed envelope: the host context
        // never reaches a proposal byte.
        SegmentAuthoringProof proof = SegmentAuthoringIntegrationFixtures.refinedReferenceProof();
        SegmentAuthoringResult.Compiled referenceCompiled = compiled(
                SegmentAuthoringIntegrationFixtures.referenceCreateRequest(
                        SegmentAuthoringIntegrationFixtures.ai(proof), proof));
        Map<String, Object> fields = referenceCompiled.patch().operations().get(0).value().fields();
        assertThat(fields.keySet()).containsExactlyInAnyOrder(
                "envelopeVersion", "dslWire", "canonicalAstHash", "semanticPlanHash", "previewHash",
                "skillHash");
    }

    // ------------------------------------------------------------- helpers

    private SegmentAuthoringResult.Compiled compiled(SegmentAuthoringRequest request) {
        return (SegmentAuthoringResult.Compiled) integration.author(request);
    }

    /** The CREATE submission of one reference-bearing source, pinned to exactly these reference pins. */
    private static SegmentAuthoringRequest referenceCreate(
            SegmentAuthoringSource source, SegmentAuthoringProof proof, SegmentAuthoringReferencePins pins) {
        return new SegmentAuthoringRequest(
                source, ProposalIdentity.ofClientRef(SegmentAuthoringFixtures.CLIENT_REF), null,
                SegmentAuthoringIntegrationFixtures.catalog(), BuilderFixtures.policy(),
                BuilderFixtures.instants(), SegmentAuthoringFixtures.context(),
                new CapabilityCompileContext(List.of(SegmentAuthoringFixtures.experimental()),
                        List.of(referenceInputsOf(proof, pins))));
    }

    /** The frozen host inputs of a reference-bearing compile, pinned to exactly these reference pins. */
    private static SegmentAuthoringInputs referenceInputsOf(
            SegmentAuthoringProof proof, SegmentAuthoringReferencePins pins) {
        return new SegmentAuthoringInputs(
                SegmentAuthoringFixtures.snapshot(), proof.previewCard(), proof.skillHash(), pins,
                SegmentAuthoringReferenceContext.of(
                        SegmentAuthoringIntegrationFixtures.referenceExecutionContext()));
    }

    /** The closed capability payload envelope of one reference-bearing proof, derived from its own artifact. */
    private static Map<String, Object> referenceEnvelope(SegmentAuthoringProof proof) {
        return SegmentAuthoringFixtures.envelopeBuilder()
                .dslWire(SegmentAuthoringFixtures.wireOf(proof.document()))
                .canonicalAstHash(proof.canonicalAstHash().value())
                .semanticPlanHash(proof.planHash().value())
                .previewHash(proof.previewHash().value())
                .skillHash(proof.skillHash().value())
                .build();
    }

    private static TrustedCurrentCampaign currentOf(CampaignPlan plan) {
        return com.company.campaign.builder.testsupport.BuilderFixtures.current(
                plan.manifest().components().toArray(new ResolvedComponent[0]));
    }

    private static ContentHash hash(String text) {
        return ContentHash.sha256(text.getBytes(StandardCharsets.UTF_8));
    }
}