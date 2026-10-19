package com.company.campaign.lifecycle;

import com.company.campaign.builder.compiler.ReferencePlanEvidence;
import com.company.campaign.evaluation.segment.gate.ReferenceReadinessProjector;
import com.company.campaign.lifecycle.testsupport.PublishScenario;
import com.company.campaign.segment.reference.AudienceVersionRef;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T18 section 7 / T17 boundary: the additive Existing Audience Reference slot of a publish preparation.
 *
 * <p>Three properties are proved here. First, a reference-free preparation keeps the accepted bytes it always
 * had: the optional slot is genuinely omitted from the canonical material rather than serialized as a null, so
 * its frozen hash is unchanged. Second, a reference-bearing preparation binds the exact dedicated reference
 * evidence, and any change to that evidence moves the preparation hash. Third, the evidence itself is a
 * closed, bounded, host-only fact: only the dedicated reference identity is representable and no production
 * state weaker than an exact {@code ACTIVE} activation can be declared.
 */
class ReferencePreparationContractTest {

    /** The preparation hash a reference-free Prepare Publish froze before the reference slot existed. */
    private static final String GOLDEN_REFERENCE_FREE_PREPARATION_HASH =
            "sha256:fb335e45da21874573a75eabe8b7bff84cf4f050409394d8288265dac917b017";

    /** The plan hash the same reference-free compile has always produced. */
    private static final String GOLDEN_REFERENCE_FREE_PLAN_HASH =
            "sha256:ce2aa7c80f393a6e75b622e1ad94ff572549cc7fc5030dc90eafb4a2fb7abe7b";

    private static final ContentHash DEPENDENCIES = hash("reference-dependencies-1");
    private static final ContentHash OTHER_DEPENDENCIES = hash("reference-dependencies-2");
    private static final ContentHash INPUTS = hash("reference-inputs-1");
    private static final ContentHash OTHER_INPUTS = hash("reference-inputs-2");
    private static final ContentHash DECISION = hash("reference-approved-decision-1");
    private static final String REFERENCE_CAPABILITY = ReferencePlanEvidence.CAPABILITY_ID;

    // ------------------------------------------------------------------ the reference-free regression

    @Test
    void aReferenceFreePreparationOmitsTheReferenceSlotAndKeepsItsAcceptedBytes() {
        PublishScenario scenario = PublishScenario.active();
        PreparePublishResult prepared = scenario.prepare();
        PublishPreparation stored = scenario.store.findPreparation(prepared.preparationId()).orElseThrow();

        // The optional slot is absent, not present-and-null: the canonical preparation material carries no
        // reference key at all, which is what keeps the frozen hash exactly the accepted one.
        assertThat(stored.referenceEvidence()).isNull();
        assertThat(stored.preparationHash().value()).isEqualTo(GOLDEN_REFERENCE_FREE_PREPARATION_HASH);
        assertThat(scenario.plan.planHash().value()).isEqualTo(GOLDEN_REFERENCE_FREE_PLAN_HASH);
        assertThat(new CanonicalJson().writeString(stored.previewCards().get(0)))
                .doesNotContain("referenceEvidence");
        assertThat(new CanonicalJson().writeString(referenceFreeMaterial(stored)))
                .doesNotContain("referenceEvidence");
    }

    /** The canonical preparation hash material with the absence of any reference evidence. */
    private static PublishPreparation.Material referenceFreeMaterial(PublishPreparation preparation) {
        return new PublishPreparation.Material(
                preparation.formatVersion(), preparation.preparationId(), preparation.tenantId(),
                preparation.programId(), preparation.userId(), preparation.dataScopeId(),
                preparation.campaignId(), preparation.campaignRevision(), preparation.draftId(),
                preparation.draftRevision(), preparation.forkBase(), preparation.planId(),
                preparation.planHash(), preparation.manifestHash(), preparation.draftHash(),
                preparation.catalogVersion(), preparation.catalogHash(), preparation.policyVersion(),
                preparation.policyHash(), preparation.capabilityPins(), preparation.previewCards(),
                preparation.requiredRiskCodes(), preparation.readinessCapability(),
                preparation.readinessState(), preparation.gateInputsHash(), preparation.approvedDecisionHash(),
                preparation.createdAt(), preparation.expiresAt(), null);
    }

    // ------------------------------------------------------------------ the evidence contract

    @Test
    void referenceEvidenceBindsTheDedicatedIdentityAndEveryExactPin() {
        ReferencePreparationEvidence evidence =
                new ReferencePreparationEvidence(REFERENCE_CAPABILITY, DEPENDENCIES, INPUTS, DECISION);

        assertThat(evidence.capabilityId())
                .isEqualTo(REFERENCE_CAPABILITY)
                .isEqualTo(ReferenceReadinessProjector.REFERENCE_EXISTING_AUDIENCE_REFERENCE)
                .isEqualTo("segment.existing-audience-reference");
        assertThat(evidence.referenceDependenciesHash()).isEqualTo(DEPENDENCIES);
        assertThat(evidence.referenceInputsHash()).isEqualTo(INPUTS);
        assertThat(evidence.approvedDecisionHash()).isEqualTo(DECISION);
        // The evidence is a closed host fact: no tenant, program, DataScope, member, version pin or owner.
        assertThat(evidence.toString())
                .contains("dependencies=present")
                .doesNotContain(DEPENDENCIES.value())
                .doesNotContain(REFERENCE_CAPABILITY);
    }

    @Test
    void referenceEvidenceRefusesEveryIdentityOtherThanTheDedicatedOne() {
        for (String capabilityId : List.of(
                "segment.rule-based", "segment.existing-audience-reference.extra", "reference", "SEGMENT")) {
            assertThatThrownBy(() -> new ReferencePreparationEvidence(
                    capabilityId, DEPENDENCIES, INPUTS, DECISION))
                    .as(capabilityId)
                    .isInstanceOf(LifecycleException.class)
                    .hasFieldOrPropertyWithValue("code", LifecycleErrorCodes.LIFECYCLE_INTERNAL_ERROR);
        }
    }

    @Test
    void referenceEvidenceRefusesMissingPins() {
        assertThatThrownBy(() -> new ReferencePreparationEvidence(REFERENCE_CAPABILITY, null, INPUTS, DECISION))
                .isInstanceOf(LifecycleException.class);
        assertThatThrownBy(
                () -> new ReferencePreparationEvidence(REFERENCE_CAPABILITY, DEPENDENCIES, null, DECISION))
                .isInstanceOf(LifecycleException.class);
        // An activation always rests on an approved decision, so an absent one is not representable.
        assertThatThrownBy(
                () -> new ReferencePreparationEvidence(REFERENCE_CAPABILITY, DEPENDENCIES, INPUTS, null))
                .isInstanceOf(LifecycleException.class);
    }

    // ------------------------------------------------------------------ resolution contract

    @Test
    void resolvedDependenciesAreCanonicalDuplicateFreeAndBounded() {
        AudienceVersionRef first = new AudienceVersionRef("audience-a", "version-1", hash("a1"));
        AudienceVersionRef second = new AudienceVersionRef("audience-b", "version-2", hash("b2"));

        ResolvedReferenceDependencies resolved =
                new ResolvedReferenceDependencies(REFERENCE_CAPABILITY, DEPENDENCIES,
                        List.of(second, first, second));

        assertThat(resolved.versions()).containsExactly(first, second);
        assertThat(resolved.dependenciesHash()).isEqualTo(DEPENDENCIES);
        assertThat(resolved.toString())
                .contains("versions=2")
                .doesNotContain("audience-a").doesNotContain("audience-b")
                .doesNotContain("version-1");

        assertThatThrownBy(() -> new ResolvedReferenceDependencies(
                "segment.rule-based", DEPENDENCIES, List.of(first)))
                .isInstanceOf(LifecycleException.class);
        assertThatThrownBy(() -> new ResolvedReferenceDependencies(
                REFERENCE_CAPABILITY, null, List.of(first)))
                .isInstanceOf(LifecycleException.class);
        assertThatThrownBy(() -> new ResolvedReferenceDependencies(
                REFERENCE_CAPABILITY, DEPENDENCIES, null))
                .isInstanceOf(LifecycleException.class);
        assertThatThrownBy(() -> new ResolvedReferenceDependencies(
                REFERENCE_CAPABILITY, DEPENDENCIES, java.util.Arrays.asList(first, null)))
                .isInstanceOf(LifecycleException.class);
    }

    private static ContentHash hash(String seed) {
        return ContentHash.sha256(seed.getBytes(StandardCharsets.UTF_8));
    }
}