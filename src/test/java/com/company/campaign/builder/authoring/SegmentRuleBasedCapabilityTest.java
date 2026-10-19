package com.company.campaign.builder.authoring;

import com.company.campaign.builder.authoring.testsupport.SegmentAuthoringFixtures;
import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.builder.compiler.CapabilityReadiness;
import com.company.campaign.builder.compiler.CapabilityResolution;
import com.company.campaign.builder.compiler.CompositionSlot;
import com.company.campaign.builder.compiler.CompilerException;
import com.company.campaign.builder.compiler.PlanPreviewCard;
import com.company.campaign.builder.compiler.PlanPreviewStatistic;
import com.company.campaign.builder.compiler.ResolvedCatalogReference;
import com.company.campaign.builder.compiler.RuntimeCompatibility;
import com.company.campaign.builder.proposal.ProposalReservedFields;
import com.company.campaign.segment.dsl.codec.SegmentDslCodec;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Focused T16 tests for the concrete production {@code segment.rule-based} capability contract: it re-proves
 * the accepted T05/T07/T08/T09/T11 evidence from explicit frozen host inputs, emits one deterministic
 * normalized payload, stable references and exactly one bound card, and fails closed on every mismatch.
 */
class SegmentRuleBasedCapabilityTest {

    private static final SegmentDslCodec DSL_CODEC = new SegmentDslCodec();
    private static final String FIXED_FAILURE = "The rule-based Segment capability resolution failed closed.";

    private final SegmentRuleBasedCapability contract = SegmentAuthoringFixtures.contract();

    // ------------------------------------------------------------------ frozen facts

    @Test
    void declaresItsFrozenCapabilityFacts() {
        assertThat(contract.capabilityCode()).isEqualTo("segment.rule-based");
        assertThat(contract.contractVersion()).isEqualTo(SegmentAuthoringVersions.CONTRACT_VERSION);
        assertThat(contract.enabled()).isTrue();
        CompositionSlot slot = contract.slot();
        assertThat(slot.code()).isEqualTo(SegmentAuthoringVersions.SLOT_CODE);
        assertThat(slot.singleton()).isTrue();
        assertThat(slot.ordered()).isFalse();
        RuntimeCompatibility runtime = contract.runtimeCompatibility();
        assertThat(runtime.runtimeSchemaVersion()).isEqualTo(SegmentAuthoringVersions.RUNTIME_SCHEMA_VERSION);
        assertThat(runtime.previewSupported()).isTrue();
        // Development evidence is never relabelled production-ready: no Apply/Publish/Runtime exists here.
        assertThat(runtime.publishable()).isFalse();
    }

    // ------------------------------------------------------------------ the accepted artifact

    @Test
    void resolvesOneBoundCardAndStableReferences() {
        CapabilityResolution resolution = contract.resolve(SegmentAuthoringFixtures.request());
        assertThat(resolution).isInstanceOf(CapabilityResolution.Resolved.class);
        CapabilityResolution.Resolved resolved = (CapabilityResolution.Resolved) resolution;

        assertThat(resolved.cards()).hasSize(1);
        PlanPreviewCard card = resolved.cards().get(0);
        assertThat(card.cardKind()).isEqualTo(PlanPreviewCard.SEGMENT_PREVIEW);
        assertThat(card.componentLogicalId()).isEqualTo(SegmentAuthoringFixtures.LOGICAL_ID);
        assertThat(card.matchedCount()).isEqualTo(42);
        assertThat(card.conditionStatistics())
                .containsExactly(new PlanPreviewStatistic("r.0", 3, 1, 0));
        assertThat(card.hasSample()).isTrue();
        assertThat(card.sampleTokens()).containsExactly(ContentHash.sha256(
                "sample-1".getBytes(StandardCharsets.UTF_8)).value());
        assertThat(card.dslHash()).isEqualTo(SegmentAuthoringFixtures.document().editableDslHash());
        assertThat(card.canonicalAstHash()).isEqualTo(SegmentAuthoringFixtures.validation().canonicalAstHash());
        assertThat(card.semanticPlanHash()).isEqualTo(SegmentAuthoringFixtures.plan().planHash());
        assertThat(card.previewHash()).isEqualTo(SegmentAuthoringFixtures.PREVIEW_HASH);
        assertThat(card.metadataHash()).isEqualTo(SegmentAuthoringFixtures.snapshot().contentHash());
        assertThat(card.skillHash()).isEqualTo(SegmentAuthoringFixtures.SKILL_HASH);
        assertThat(card.metadataSnapshotId()).isEqualTo(SegmentAuthoringFixtures.snapshot().snapshotId());
        assertThat(card.asOf()).isEqualTo(SegmentAuthoringFixtures.context().snapshotRef().asOf());
        assertThat(card.timezone()).isEqualTo(SegmentAuthoringFixtures.context().snapshotRef().timezone().getId());
        assertThat(card.dataWatermark())
                .isEqualTo(SegmentAuthoringFixtures.context().snapshotRef().dataWatermark());
        assertThat(card.readinessState()).isEqualTo(CapabilityReadiness.EXPERIMENTAL);
        assertThat(card.gateInputsHash()).isEqualTo(SegmentAuthoringFixtures.GATE_INPUTS_HASH);
        assertThat(card.approvedDecisionHash()).isNull();

        // One stable reference per resolved Metadata definition, deterministic and duplicate-free.
        assertThat(resolved.references()).hasSize(3);
        assertThat(resolved.references()).extracting(ResolvedCatalogReference::entityKind)
                .containsExactly("ATTRIBUTE", "METRIC", "RELATION");
        assertThat(resolved.references()).extracting(ResolvedCatalogReference::logicalId)
                .containsExactly("member.tier", "order.net_paid_amount", "member.orders");
        assertThat(resolved.references()).extracting(ResolvedCatalogReference::versionId)
                .containsExactly("1.0", "1.0", "1.0:1.0");
    }

    @Test
    void anActiveReadinessIsRepresentedHonestlyOnTheCard() {
        CapabilityResolution resolution = contract.resolve(SegmentAuthoringFixtures.request(
                SegmentAuthoringFixtures.envelope(),
                SegmentAuthoringFixtures.context(),
                SegmentAuthoringFixtures.compileContext(
                        SegmentAuthoringFixtures.active(), SegmentAuthoringFixtures.inputs())));
        PlanPreviewCard card = ((CapabilityResolution.Resolved) resolution).cards().get(0);
        assertThat(card.readinessState()).isEqualTo(CapabilityReadiness.ACTIVE);
        assertThat(card.approvedDecisionHash()).isEqualTo(SegmentAuthoringFixtures.APPROVED_DECISION_HASH);
        assertThat(card.activeReadiness()).isTrue();
    }

    // ------------------------------------------------------------------ determinism

    @Test
    void resolutionIsByteIdenticalAcrossRuns() {
        CapabilityResolution.Resolved first =
                (CapabilityResolution.Resolved) contract.resolve(SegmentAuthoringFixtures.request());
        for (int attempt = 0; attempt < 25; attempt++) {
            CapabilityResolution.Resolved candidate =
                    (CapabilityResolution.Resolved) contract.resolve(SegmentAuthoringFixtures.request());
            assertThat(candidate.value().canonicalBytes()).isEqualTo(first.value().canonicalBytes());
            assertThat(candidate.value().contentHash()).isEqualTo(first.value().contentHash());
            assertThat(candidate.references()).isEqualTo(first.references());
            assertThat(candidate.cards()).isEqualTo(first.cards());
        }
    }

    @Test
    void normalizedPayloadCarriesOnlyTheKnownSafeFields() {
        CapabilityResolution.Resolved resolved =
                (CapabilityResolution.Resolved) contract.resolve(SegmentAuthoringFixtures.request());
        CapabilityPayload payload = resolved.value();
        assertThat(payload.fields().keySet()).containsExactlyInAnyOrder(
                "envelopeVersion", "dslWire", "canonicalAstHash", "semanticPlanHash", "previewHash", "skillHash");
        assertThat(payload.fields().get("dslWire")).isEqualTo(SegmentAuthoringFixtures.dslWire());
        assertThat(payload.fields().get("canonicalAstHash"))
                .isEqualTo(SegmentAuthoringFixtures.validation().canonicalAstHash().value());
        assertThat(payload.fields().get("semanticPlanHash"))
                .isEqualTo(SegmentAuthoringFixtures.plan().planHash().value());
        // The DSL is preserved as one opaque wire field, so its own `subject` root never enters the screen.
        assertThat(payload.fields().get("dslWire")).isInstanceOf(String.class);
    }

    // ------------------------------------------------------------------ mismatch closures

    @Test
    void rejectsADeclaredCanonicalHashTheBoundaryDidNotDerive() {
        assertFailsWith(
                SegmentAuthoringFixtures.envelopeBuilder()
                        .canonicalAstHash(bogusHash())
                        .build(),
                SegmentAuthoringErrorCodes.EVIDENCE_MISMATCH);
    }

    @Test
    void rejectsADeclaredPlanHashTheBoundaryDidNotDerive() {
        assertFailsWith(
                SegmentAuthoringFixtures.envelopeBuilder()
                        .semanticPlanHash(bogusHash())
                        .build(),
                SegmentAuthoringErrorCodes.EVIDENCE_MISMATCH);
    }

    @Test
    void rejectsADslBoundToADifferentMetadataSnapshot() {
        SegmentDsl foreign = SegmentAuthoringFixtures.documentBoundTo("metadata-99");
        assertFailsWith(
                SegmentAuthoringFixtures.envelopeBuilder()
                        .dslWire(SegmentAuthoringFixtures.wireOf(foreign))
                        .build(),
                SegmentAuthoringErrorCodes.METADATA_MISMATCH);
    }

    @Test
    void rejectsAForeignSnapshotOwner() {
        CapabilityResolution resolution = contract.resolve(SegmentAuthoringFixtures.request(
                SegmentAuthoringFixtures.envelope(),
                SegmentAuthoringFixtures.foreignOwnerContext(),
                SegmentAuthoringFixtures.compileContext()));
        assertThat(failureCode(resolution)).isEqualTo(SegmentAuthoringErrorCodes.METADATA_MISMATCH);
    }

    @Test
    void trustedTimePinsNeverEnterThePayloadAndComeOnlyFromTheTrustedContext() {
        // The payload carries the closed semantic field set and no trusted time pin at all: a client cannot
        // declare `asOf`, `timezone` or `dataWatermark`, and the contract never reads them from a payload.
        assertThat(SegmentAuthoringFixtures.envelope().keySet())
                .containsExactlyInAnyOrder(
                        "envelopeVersion", "dslWire", "canonicalAstHash", "semanticPlanHash", "previewHash",
                        "skillHash");
        CapabilityPayload payload = ((CapabilityResolution.Resolved)
                contract.resolve(SegmentAuthoringFixtures.request())).value();
        assertThat(payload.fields().keySet())
                .containsExactlyInAnyOrder(
                        "envelopeVersion", "dslWire", "canonicalAstHash", "semanticPlanHash", "previewHash",
                        "skillHash");

        // The host-pinned card is re-proved against the trusted context's own pins, so a card whose time pins
        // disagree with the compile's context fails closed instead of being trusted.
        var reference = SegmentAuthoringFixtures.context().snapshotRef();
        assertFailsWithContext(
                SegmentAuthoringFixtures.compileContext(
                        SegmentAuthoringFixtures.experimental(),
                        SegmentAuthoringFixtures.inputs(
                                SegmentAuthoringFixtures.snapshot(),
                                SegmentAuthoringFixtures.cardBuilder()
                                        .asOf(reference.asOf().plusSeconds(3600).toString())
                                        .build(),
                                SegmentAuthoringFixtures.SKILL_HASH)),
                SegmentAuthoringErrorCodes.PREVIEW_MISMATCH);
        assertFailsWithContext(
                SegmentAuthoringFixtures.compileContext(
                        SegmentAuthoringFixtures.experimental(),
                        SegmentAuthoringFixtures.inputs(
                                SegmentAuthoringFixtures.snapshot(),
                                SegmentAuthoringFixtures.cardBuilder().timezone("UTC").build(),
                                SegmentAuthoringFixtures.SKILL_HASH)),
                SegmentAuthoringErrorCodes.PREVIEW_MISMATCH);
        assertFailsWithContext(
                SegmentAuthoringFixtures.compileContext(
                        SegmentAuthoringFixtures.experimental(),
                        SegmentAuthoringFixtures.inputs(
                                SegmentAuthoringFixtures.snapshot(),
                                SegmentAuthoringFixtures.cardBuilder()
                                        .dataWatermark(reference.dataWatermark().minusSeconds(30).toString())
                                        .build(),
                                SegmentAuthoringFixtures.SKILL_HASH)),
                SegmentAuthoringErrorCodes.PREVIEW_MISMATCH);
        // Malformed pins on the evidence side fail closed exactly like absent ones.
        assertFailsWithContext(
                SegmentAuthoringFixtures.compileContext(
                        SegmentAuthoringFixtures.experimental(),
                        SegmentAuthoringFixtures.inputs(
                                SegmentAuthoringFixtures.snapshot(),
                                SegmentAuthoringFixtures.cardBuilder().asOf("not-an-instant").build(),
                                SegmentAuthoringFixtures.SKILL_HASH)),
                SegmentAuthoringErrorCodes.PREVIEW_MISMATCH);
        assertFailsWithContext(
                SegmentAuthoringFixtures.compileContext(
                        SegmentAuthoringFixtures.experimental(),
                        SegmentAuthoringFixtures.inputs(
                                SegmentAuthoringFixtures.snapshot(),
                                SegmentAuthoringFixtures.cardBuilder().timezone("Not/AZone").build(),
                                SegmentAuthoringFixtures.SKILL_HASH)),
                SegmentAuthoringErrorCodes.PREVIEW_MISMATCH);
    }

    @Test
    void aClientDeclaringATrustedTimePinIsRejectedBeforeItCanBeRead() {
        // The three names are reserved on the Proposal surface, so a payload that declares one is refused
        // outright by the reserved-name boundary — it can never reach the envelope, let alone be trusted as a
        // compile fact, and the message never echoes the declared value.
        for (String declared : List.of("asOf", "timezone", "dataWatermark")) {
            assertThatThrownBy(() -> CapabilityPayload.of(
                            SegmentAuthoringFixtures.envelopeBuilder()
                                    .extra(declared, "2026-10-01T03:00:00Z")
                                    .build()))
                    .as(declared)
                    .isInstanceOf(CompilerException.class)
                    .hasMessageNotContaining("2026-10-01T03:00:00Z");
        }
        // The envelope's own closed field set carries no time pin, so none can be declared at all.
        assertThat(SegmentAuthoringFixtures.envelope().keySet())
                .doesNotContain("asOf", "timezone", "dataWatermark");
    }

    @Test
    void rejectsPreviewEvidenceThatIsNotTheOneThisCompileResolves() {
        assertFailsWith(
                SegmentAuthoringFixtures.envelopeBuilder().previewHash(bogusHash()).build(),
                SegmentAuthoringErrorCodes.PREVIEW_MISMATCH);
        assertFailsWith(
                SegmentAuthoringFixtures.envelopeBuilder().skillHash(bogusHash()).build(),
                SegmentAuthoringErrorCodes.PREVIEW_MISMATCH);
        CapabilityResolution cardMismatch = contract.resolve(SegmentAuthoringFixtures.request(
                SegmentAuthoringFixtures.envelope(),
                SegmentAuthoringFixtures.context(),
                SegmentAuthoringFixtures.compileContext(
                        SegmentAuthoringFixtures.experimental(),
                        SegmentAuthoringFixtures.inputs(
                                SegmentAuthoringFixtures.snapshot(),
                                SegmentAuthoringFixtures.cardBuilder().planHash(bogusContentHash()).build(),
                                SegmentAuthoringFixtures.SKILL_HASH))));
        assertThat(failureCode(cardMismatch)).isEqualTo(SegmentAuthoringErrorCodes.PREVIEW_MISMATCH);
    }

    @Test
    void rejectsAMalformedOrNonCanonicalDslWire() {
        assertFailsWith(
                SegmentAuthoringFixtures.envelopeBuilder().dslWire("!!!not-base64!!!").build(),
                SegmentAuthoringErrorCodes.DSL_INVALID);
        assertFailsWith(
                SegmentAuthoringFixtures.envelopeBuilder()
                        .dslWire(Base64.getEncoder().encodeToString(
                                "not a json document".getBytes(StandardCharsets.UTF_8)))
                        .build(),
                SegmentAuthoringErrorCodes.DSL_INVALID);
        String canonical = new String(DSL_CODEC.write(SegmentAuthoringFixtures.document()),
                StandardCharsets.UTF_8);
        String nonCanonical = canonical.replace("{", "{ ");
        assertFailsWith(
                SegmentAuthoringFixtures.envelopeBuilder()
                        .dslWire(Base64.getEncoder().encodeToString(
                                nonCanonical.getBytes(StandardCharsets.UTF_8)))
                        .build(),
                SegmentAuthoringErrorCodes.DSL_INVALID);
    }

    @Test
    void rejectsAnEnvelopeWithAnUnknownOrMissingField() {
        assertFailsWith(
                SegmentAuthoringFixtures.envelopeBuilder().extra("extraField", "x").build(),
                SegmentAuthoringErrorCodes.ENVELOPE_INVALID);
        assertFailsWith(
                SegmentAuthoringFixtures.envelopeBuilder().without("previewHash").build(),
                SegmentAuthoringErrorCodes.ENVELOPE_INVALID);
        assertFailsWith(
                SegmentAuthoringFixtures.envelopeBuilder().version("segment-authoring-envelope/9.9").build(),
                SegmentAuthoringErrorCodes.ENVELOPE_INVALID);
        assertFailsWith(
                SegmentAuthoringFixtures.envelopeBuilder().extra("envelopeVersion2", "x").build(),
                SegmentAuthoringErrorCodes.ENVELOPE_INVALID);
    }

    @Test
    void rejectsACompileThatPinnedNoReadinessOrNoInputs() {
        CapabilityResolution missingReadiness = contract.resolve(SegmentAuthoringFixtures.request(
                SegmentAuthoringFixtures.envelope(),
                SegmentAuthoringFixtures.context(),
                SegmentAuthoringFixtures.withoutReadiness()));
        assertThat(failureCode(missingReadiness)).isEqualTo(SegmentAuthoringErrorCodes.READINESS_MISSING);

        CapabilityResolution missingInputs = contract.resolve(SegmentAuthoringFixtures.request(
                SegmentAuthoringFixtures.envelope(),
                SegmentAuthoringFixtures.context(),
                SegmentAuthoringFixtures.withoutInputs()));
        assertThat(failureCode(missingInputs)).isEqualTo(SegmentAuthoringErrorCodes.INPUT_MISSING);
    }

    @Test
    void rejectsAnIncoherentReadinessFact() {
        CapabilityReadiness incomplete = new CapabilityReadiness(
                SegmentAuthoringVersions.CAPABILITY_CODE,
                CapabilityReadiness.EXPERIMENTAL,
                false,
                List.of("APPROVAL_MISSING"),
                null,
                null);
        CapabilityResolution resolution = contract.resolve(SegmentAuthoringFixtures.request(
                SegmentAuthoringFixtures.envelope(),
                SegmentAuthoringFixtures.context(),
                SegmentAuthoringFixtures.compileContext(incomplete, SegmentAuthoringFixtures.inputs())));
        assertThat(failureCode(resolution)).isEqualTo(SegmentAuthoringErrorCodes.READINESS_INVALID);
    }

    @Test
    void rejectsANullRequest() {
        assertThat(failureCode(contract.resolve(null))).isEqualTo(SegmentAuthoringErrorCodes.INPUT_MISSING);
    }

    // ------------------------------------------------------------------ redaction and reserved names

    @Test
    void aFailureNeverEchoesTheRejectedValue() {
        String echoed = bogusHash();
        CapabilityResolution resolution = contract.resolve(SegmentAuthoringFixtures.request(
                SegmentAuthoringFixtures.envelopeBuilder()
                        .semanticPlanHash(echoed)
                        .build()));
        assertThat(resolution).isInstanceOf(CapabilityResolution.Failed.class);
        List<com.company.campaign.contracts.Issue> issues =
                ((CapabilityResolution.Failed) resolution).issues();
        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).code()).isEqualTo(SegmentAuthoringErrorCodes.EVIDENCE_MISMATCH);
        assertThat(issues.get(0).message()).isEqualTo(FIXED_FAILURE);
        assertThat(issues.toString())
                .doesNotContain(echoed)
                .doesNotContain("member.tier")
                .doesNotContain("net_paid_amount")
                .doesNotContain("metadata-17");
    }

    @Test
    void theEnvelopeNamesNoReservedFieldAndTheScreenStillRefusesSubject() {
        for (String field : SegmentAuthoringFixtures.envelope().keySet()) {
            assertThat(ProposalReservedFields.isReserved(field))
                    .as("envelope field %s must not need a weakened reserved-name screen", field)
                    .isFalse();
        }
        assertThatThrownBy(() -> CapabilityPayload.of(Map.of("subject", "MEMBER")))
                .isInstanceOf(CompilerException.class);
        assertThatThrownBy(() -> CapabilityPayload.of(Map.of("tenantId", "tenant-001")))
                .isInstanceOf(CompilerException.class);
        // The trusted time pins are reserved aliases in their own right, under any spelling.
        assertThat(ProposalReservedFields.isReserved("asOf")).isTrue();
        assertThat(ProposalReservedFields.isReserved("as_of")).isTrue();
        assertThat(ProposalReservedFields.isReserved("timezone")).isTrue();
        assertThat(ProposalReservedFields.isReserved("dataWatermark")).isTrue();
    }

    @Test
    void toStringRevealsNoTrustedValue() {
        assertThat(contract.toString()).doesNotContain(SegmentAuthoringFixtures.snapshot().snapshotId());
        assertThat(SegmentAuthoringFixtures.inputs().toString()).doesNotContain("member.tier");
        CapabilityPayload payload = ((CapabilityResolution.Resolved)
                contract.resolve(SegmentAuthoringFixtures.request())).value();
        assertThat(payload.toString()).doesNotContain("member.tier").doesNotContain("metadata-17");
    }

    // ------------------------------------------------------------------ helpers

    private void assertFailsWith(Map<String, Object> payload, String expectedCode) {
        assertThat(failureCode(contract.resolve(SegmentAuthoringFixtures.request(payload))))
                .isEqualTo(expectedCode);
    }

    private void assertFailsWithContext(
            com.company.campaign.builder.compiler.CapabilityCompileContext compileContext, String expectedCode) {
        CapabilityResolution resolution = contract.resolve(SegmentAuthoringFixtures.request(
                SegmentAuthoringFixtures.envelope(), SegmentAuthoringFixtures.context(), compileContext));
        assertThat(failureCode(resolution)).isEqualTo(expectedCode);
    }

    private static String failureCode(CapabilityResolution resolution) {
        assertThat(resolution).isInstanceOf(CapabilityResolution.Failed.class);
        List<com.company.campaign.contracts.Issue> issues =
                ((CapabilityResolution.Failed) resolution).issues();
        assertThat(issues).hasSize(1);
        return issues.get(0).code();
    }

    private static String bogusHash() {
        return bogusContentHash().value();
    }

    private static ContentHash bogusContentHash() {
        return ContentHash.sha256("a-rejected-value".getBytes(StandardCharsets.UTF_8));
    }
}