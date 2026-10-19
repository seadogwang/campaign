package com.company.campaign.evaluation.segment.reference;

import com.company.campaign.evaluation.segment.contract.CaseFileReference;
import com.company.campaign.evaluation.segment.contract.GateOutcome;
import com.company.campaign.evaluation.segment.gate.CapabilityState;
import com.company.campaign.evaluation.segment.gate.GateApprovalRecord;
import com.company.campaign.evaluation.segment.gate.GateErrorCodes;
import com.company.campaign.evaluation.segment.gate.GateOwnerApproval;
import com.company.campaign.evaluation.segment.gate.GateOwnerRole;
import com.company.campaign.evaluation.segment.gate.ReferenceActivationGuard;
import com.company.campaign.evaluation.segment.gate.ReferenceApprovedDecision;
import com.company.campaign.evaluation.segment.gate.ReferenceGateDecision;
import com.company.campaign.evaluation.segment.gate.ReferenceReadinessInputs;
import com.company.campaign.evaluation.segment.gate.ReferenceReadinessProjector;
import com.company.campaign.evaluation.segment.gate.ReferenceReadinessVersions;
import com.company.campaign.evaluation.segment.gate.SegmentReadinessProjection;
import com.company.campaign.evaluation.segment.reference.testsupport.ReferenceCorpusV1Generator;
import com.company.campaign.segment.reference.ReferenceGraph;
import com.company.campaign.shared.canonical.ContentHash;
import com.company.campaign.shared.error.ContractErrorCodes;
import com.company.campaign.shared.error.ContractException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Focused readiness tests for the committed T18 Existing Audience Reference corpus.
 *
 * <p>They prove that the committed corpus claims nothing it cannot prove, and that nothing in it can be
 * promoted by reading a file. The readiness inputs are derived from the corpus the manifest hash covers, so
 * every pinned asset byte moves them. The manifest is {@code DEVELOPMENT_ONLY} with {@code PENDING} review and
 * no model run, and the release vocabulary has no {@code PASSED} or {@code ACTIVE} constant at all, so a
 * manifest that spelled one fails to load rather than being caught by a rule someone could relax. With no
 * approval the projection stays {@code EXPERIMENTAL}; a stale approval stays {@code EVALUATION_REQUIRED}; and
 * only a genuine approved {@code PASSED} decision over exactly the current inputs is ever {@code ACTIVE}.
 */
class ReferenceCorpusReadinessTest {

    private static final String REFERENCE = "segment.existing-audience-reference";
    private static final Instant DECIDED_AT = Instant.parse("2026-10-07T09:00:00Z");

    @TempDir
    Path temporaryRoot;

    @Test
    void theCommittedCorpusReadinessInputsAreDerivedFromTheCorpus() {
        ReferenceCorpusBundle bundle = bundle();
        ReferenceReadinessInputs inputs = ReferenceCorpusReadiness.inputs(bundle);

        assertThat(inputs.referenceCorpusId()).isEqualTo(ReferenceCorpusVersions.CORPUS_ID);
        assertThat(inputs.referenceCorpusVersion()).isEqualTo(ReferenceCorpusVersions.CORPUS_VERSION);
        assertThat(inputs.referenceCorpusHash()).isEqualTo(bundle.manifest().manifestHash());
        assertThat(inputs.referenceGraphFormat()).isEqualTo(ReferenceGraph.GRAPH_FORMAT_VERSION);
        assertThat(inputs.versions()).isEqualTo(ReferenceReadinessVersions.current());
        assertThat(inputs.referencePolicyHash())
                .isEqualTo(ReferenceCorpusReadiness.referencePolicyHash(bundle));
        assertThat(inputs.catalogDirectoryContractHash())
                .isEqualTo(ReferenceCorpusReadiness.catalogDirectoryContractHash(bundle));
        assertThat(inputs.dependencyGraphInputsHash())
                .isEqualTo(ReferenceCorpusReadiness.dependencyGraphInputsHash(bundle));
    }

    @Test
    void theManifestHashCoversEveryPinnedAssetByte() {
        ReferenceCorpusBundle bundle = bundle();
        ReferenceCorpusManifest manifest = bundle.manifest();

        Set<String> pinned = new HashSet<>();
        pinned.add(manifest.fixtureCatalog().contentHash().value());
        for (ReferenceFixtureDescriptor fixture : bundle.fixtureCatalog().fixtures()) {
            pinned.add(fixture.payloadHash().value());
        }
        for (CaseFileReference reference : manifest.cases()) {
            pinned.add(reference.caseHash().value());
            pinned.add(reference.expectedHash().value());
            pinned.add(reference.reviewHash().value());
        }
        assertThat(pinned)
                .as("every pinned asset must have its own hash, so the manifest hash covers all of them")
                .hasSize(1 + bundle.fixtureCatalog().fixtures().size() + 3 * manifest.cases().size());

        Set<String> componentHashes = new HashSet<>(Arrays.asList(
                ReferenceCorpusReadiness.referencePolicyHash(bundle).value(),
                ReferenceCorpusReadiness.catalogDirectoryContractHash(bundle).value(),
                ReferenceCorpusReadiness.dependencyGraphInputsHash(bundle).value()));
        assertThat(componentHashes).hasSize(3);
    }

    @Test
    void theManifestClaimsNeitherReviewNorAModelRun() {
        ReferenceCorpusManifest manifest = bundle().manifest();

        assertThat(manifest.releaseStatus()).isEqualTo(ReferenceReleaseStatus.DEVELOPMENT_ONLY);
        assertThat(manifest.releaseStatus())
                .isIn(ReferenceReleaseStatus.DEVELOPMENT_ONLY, ReferenceReleaseStatus.EVALUATION_REQUIRED);
        assertThat(manifest.humanReviewStatus()).isEqualTo(ReferenceHumanReviewStatus.PENDING);
        assertThat(manifest.claimsHumanReviewComplete()).isFalse();
        assertThat(manifest.modelEvidenceStatus()).isEqualTo(ReferenceModelEvidenceStatus.NOT_RUN);
        assertThat(manifest.modelEvidenceReference()).isNull();
    }

    @Test
    void theStatusVocabulariesHaveNoPassedOrActiveClaim() {
        assertThat(Arrays.stream(ReferenceReleaseStatus.values()).map(Enum::name))
                .containsExactly("DEVELOPMENT_ONLY", "EVALUATION_REQUIRED", "CANDIDATE_COMPLETE");
        assertThatThrownBy(() -> ReferenceReleaseStatus.valueOf("PASSED"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReferenceReleaseStatus.valueOf("ACTIVE"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(Arrays.stream(ReferenceHumanReviewStatus.values()).map(Enum::name))
                .containsExactly("PENDING", "COMPLETE");
        assertThat(Arrays.stream(ReferenceModelEvidenceStatus.values()).map(Enum::name))
                .containsExactly("NOT_RUN", "RECORDED");
        assertThatThrownBy(() -> ReferenceModelEvidenceStatus.valueOf("PASSED"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aManifestClaimingAnActivationStatusCannotEvenBeLoaded() {
        ReferenceCorpusV1Generator.write(temporaryRoot, ReferenceCorpusV1Generator.generate());
        Path manifestFile = temporaryRoot.resolve(ReferenceCorpusVersions.MANIFEST_REFERENCE);
        String tampered = new String(read(manifestFile), StandardCharsets.UTF_8)
                .replace("\"DEVELOPMENT_ONLY\"", "\"ACTIVE\"");
        assertThat(tampered).contains("\"releaseStatus\":\"ACTIVE\"");
        write(manifestFile, tampered.getBytes(StandardCharsets.UTF_8));

        // A production activation claim is unrepresentable rather than merely forbidden: the constant does
        // not exist, so the manifest fails to bind before any rule could be applied to it.
        assertThatThrownBy(() -> new ReferenceCorpusLoader(temporaryRoot).loadCorpus())
                .isInstanceOf(ContractException.class)
                .extracting(thrown -> ((ContractException) thrown).code())
                .isEqualTo(ContractErrorCodes.CONTRACT_INVALID_VALUE);
    }

    @Test
    void anUnapprovedCorpusProjectsExperimentalAndNeverActive() {
        ReferenceCorpusBundle bundle = bundle();
        ReferenceReadinessInputs inputs = ReferenceCorpusReadiness.inputs(bundle);

        SegmentReadinessProjection projection = projectorOf(null).project(REFERENCE, inputs);

        assertThat(projection.state()).isEqualTo(CapabilityState.EXPERIMENTAL);
        assertThat(projection.aiReady()).isFalse();
        assertThat(projection.approvedDecisionHash()).isNull();
        assertThat(projection.reasonCodes()).containsExactly(GateErrorCodes.APPROVAL_MISSING);
    }

    @Test
    void aCorpusRevisionChangeMovesTheCurrentInputsHashAndInvalidatesAPriorApproval() {
        ReferenceCorpusBundle bundle = bundle();
        ReferenceReadinessInputs current = ReferenceCorpusReadiness.inputs(bundle);

        ReferenceCorpusBundle revised = withManifest(bundle, manifestWithReleaseStatus(
                bundle.manifest(), ReferenceReleaseStatus.EVALUATION_REQUIRED));
        ReferenceReadinessInputs moved = ReferenceCorpusReadiness.inputs(revised);

        assertThat(moved.contentHash()).isNotEqualTo(current.contentHash());

        // A decision approved over the previous revision is stale evidence for this one, and the guard agrees.
        ReferenceApprovedDecision approved = approvedPassed(current);
        SegmentReadinessProjection projection = projectorOf(approved).project(REFERENCE, moved);
        assertThat(projection.state()).isEqualTo(CapabilityState.EVALUATION_REQUIRED);
        assertThat(projection.aiReady()).isFalse();
        assertThat(projection.reasonCodes()).containsExactly(GateErrorCodes.GATED_INPUT_CHANGED);
        assertThat(ReferenceActivationGuard.isActivatable(approved, moved)).isFalse();
    }

    @Test
    void onlyAGenuineApprovedPassedDecisionOverTheExactInputsCouldActivate() {
        // The corpus on its own never reaches ACTIVE: the only path is a real approved PASSED decision whose
        // frozen inputs equal the corpus-derived ones exactly.
        ReferenceReadinessInputs current = ReferenceCorpusReadiness.inputs(bundle());
        ReferenceApprovedDecision approved = approvedPassed(current);

        SegmentReadinessProjection projection = projectorOf(approved).project(REFERENCE, current);
        assertThat(projection.state()).isEqualTo(CapabilityState.ACTIVE);
        assertThat(projection.aiReady()).isTrue();
        assertThat(projection.reasonCodes()).isEmpty();
        assertThat(projection.approvedDecisionHash()).isEqualTo(approved.decision().decisionHash());
        assertThat(projection.currentInputsHash()).isEqualTo(current.contentHash());

        // A decision over any other reference input bundle is never accepted, even when it is genuine.
        SegmentReadinessProjection stale = projectorOf(approvedPassed(changedInputs(current)))
                .project(REFERENCE, current);
        assertThat(stale.state()).isEqualTo(CapabilityState.EVALUATION_REQUIRED);
        assertThat(stale.aiReady()).isFalse();
        assertThat(ReferenceActivationGuard.isActivatable(approved, changedInputs(current))).isFalse();
    }

    @Test
    void aNonPassedApprovedDecisionOverTheExactInputsStaysExperimental() {
        ReferenceReadinessInputs current = ReferenceCorpusReadiness.inputs(bundle());
        ReferenceApprovedDecision approved = approvedOf(GateOutcome.FAILED, current);

        SegmentReadinessProjection projection = projectorOf(approved).project(REFERENCE, current);

        assertThat(projection.state()).isEqualTo(CapabilityState.EXPERIMENTAL);
        assertThat(projection.aiReady()).isFalse();
        assertThat(projection.reasonCodes()).containsExactly(GateErrorCodes.NOT_PASSED);
    }

    // ------------------------------------------------------------- helpers

    private static ReferenceCorpusBundle bundle() {
        return new ReferenceCorpusLoader(Path.of("")).loadCorpus();
    }

    private static ReferenceReadinessProjector projectorOf(ReferenceApprovedDecision approved) {
        return new ReferenceReadinessProjector(capabilityId -> Optional.ofNullable(approved));
    }

    private static ReferenceApprovedDecision approvedPassed(ReferenceReadinessInputs inputs) {
        return approvedOf(GateOutcome.PASSED, inputs);
    }

    private static ReferenceApprovedDecision approvedOf(GateOutcome outcome, ReferenceReadinessInputs inputs) {
        ReferenceGateDecision decision =
                ReferenceGateDecision.of("reference-decision-1", outcome, inputs, DECIDED_AT);
        return new ReferenceApprovedDecision(
                REFERENCE, decision,
                GateApprovalRecord.of(decision.decisionHash(), decision.outcome(), List.of(
                        new GateOwnerApproval("owner-product-1", GateOwnerRole.PRODUCT, DECIDED_AT),
                        new GateOwnerApproval("owner-data-1", GateOwnerRole.DATA, DECIDED_AT),
                        new GateOwnerApproval("owner-test-1", GateOwnerRole.TEST, DECIDED_AT))));
    }

    /** The same inputs with exactly one changed dependency-graph input, standing in for any drift. */
    private static ReferenceReadinessInputs changedInputs(ReferenceReadinessInputs source) {
        return new ReferenceReadinessInputs(
                source.referenceCorpusId(), source.referenceCorpusVersion(), source.referenceCorpusHash(),
                source.versions(), source.referenceGraphFormat(), source.referencePolicyHash(),
                source.catalogDirectoryContractHash(),
                ContentHash.sha256("other-dependency-graph-inputs".getBytes(StandardCharsets.UTF_8)));
    }

    private static ReferenceCorpusBundle withManifest(
            ReferenceCorpusBundle bundle, ReferenceCorpusManifest manifest) {
        return new ReferenceCorpusBundle(
                manifest, bundle.fixtureCatalog(), bundle.fixturePayloads(), bundle.cases(),
                bundle.expectedResults(), bundle.reviews());
    }

    private static ReferenceCorpusManifest manifestWithReleaseStatus(
            ReferenceCorpusManifest source, ReferenceReleaseStatus releaseStatus) {
        ReferenceCorpusManifest withoutHash = new ReferenceCorpusManifest(
                source.schemaVersion(), source.corpusVersion(), source.fixtureCatalog(),
                source.minimumCases(), source.minimumCasesPerCategory(), source.humanReviewStatus(),
                source.modelEvidenceStatus(), source.modelEvidenceReference(), releaseStatus,
                source.requiredCategories(), source.cases(), null);
        return withoutHash.withManifestHash(withoutHash.recomputeContentHash());
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static void write(Path file, byte[] bytes) {
        try {
            Files.write(file, bytes);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}