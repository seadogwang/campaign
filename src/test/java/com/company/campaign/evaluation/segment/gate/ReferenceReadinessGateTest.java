package com.company.campaign.evaluation.segment.gate;

import com.company.campaign.builder.authoring.SegmentAuthoringVersions;
import com.company.campaign.evaluation.segment.contract.GateOutcome;
import com.company.campaign.segment.reference.ReferenceGraph;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T18 section 7 focused tests for the host-owned Existing Audience Reference readiness/Gate core.
 *
 * <p>The reference extension is its own Gate identity, so five properties are proved here. First,
 * <em>capability isolation</em>: the dedicated reference projector promotes only
 * {@code segment.existing-audience-reference} and never {@code segment.rule-based}, an old rule-based
 * approval never satisfies the reference capability, and the reference guard refuses a rule-based decision.
 * Second, <em>every pinned input is sensitive</em>: the reference corpus identity and hash, all seven
 * subsystem versions, the reference graph format, the reference policy hash, the catalog/directory contract
 * hash and the dependency-graph inputs hash each move the canonical current-input hash, so any drift is
 * observable rather than implicit. Third, every <em>missing, pending, failed, stale, mismatched or
 * wrong-capability</em> decision fails closed. Fourth, only an exact genuine approved {@code PASSED} decision
 * over the exact current inputs is {@code ACTIVE} and accepted by the guard. Fifth, a <em>reference-free</em>
 * compile consults no reference Gate port at all.
 *
 * <p>Nothing here manufactures an approval: the tests build synthetic approved-decision evidence only to
 * prove the acceptance path, exactly as the T14 readiness fixtures do. This core reports
 * {@code EXPERIMENTAL}/{@code EVALUATION_REQUIRED} for the current code, which has no approved reference Gate
 * decision, and stays independent of any lifecycle.
 */
class ReferenceReadinessGateTest {

    /** The dedicated reference capability identity, restated here so a drift from authoring fails the build. */
    private static final String REFERENCE = "segment.existing-audience-reference";

    /** The already-approved base Segment capability identity; the reference extension must never collapse. */
    private static final String RULE_BASED = "segment.rule-based";

    private static final Instant DECIDED_AT = Instant.parse("2026-10-07T09:00:00Z");

    // ------------------------------------------------------------- capability isolation

    @Test
    void theReferenceCapabilityIdentityIsItsOwnAndMatchesTheAuthoringIdentity() {
        assertThat(ReferenceReadinessProjector.REFERENCE_EXISTING_AUDIENCE_REFERENCE)
                .isEqualTo(REFERENCE)
                .isEqualTo(SegmentAuthoringVersions.REFERENCE_CAPABILITY_CODE);
        // The two Gate identities are deliberately different: an approval of one is never an approval of the
        // other, because the reference extension has its own corpus, inputs and model inputs.
        assertThat(ReferenceReadinessProjector.REFERENCE_EXISTING_AUDIENCE_REFERENCE)
                .isNotEqualTo(RULE_BASED)
                .isNotEqualTo(SegmentCapabilityReadinessProjector.SEGMENT_RULE_BASED);
    }

    @Test
    void theReferenceProjectorNeverPromotesAnotherCapability() {
        ReferenceReadinessProjector projector = projectorOf(approvedPassed(currentInputs()));

        for (String other : List.of(RULE_BASED, "campaign.journey")) {
            SegmentReadinessProjection projection = projector.project(other, currentInputs());
            assertThat(projection.capabilityId()).as(other).isEqualTo(other);
            assertThat(projection.state()).as(other).isEqualTo(CapabilityState.EXPERIMENTAL);
            assertThat(projection.aiReady()).as(other).isFalse();
            assertThat(projection.approvedDecisionHash()).as(other).isNull();
            assertThat(projection.reasonCodes()).as(other).containsExactly(GateErrorCodes.CAPABILITY_NOT_GATED);
        }

        assertThatThrownBy(() -> projector.project(null, currentInputs()))
                .isInstanceOf(GateException.class)
                .extracting(thrown -> ((GateException) thrown).code())
                .isEqualTo(GateErrorCodes.CAPABILITY_NOT_GATED);
    }

    @Test
    void aRuleBasedApprovedDecisionNeverSatisfiesTheReferenceProjection() {
        // The lookup port answers with an approval bound to the base capability: the exact case the T18 spec
        // forbids. It must never be accepted as reference evidence.
        ReferenceApprovedDecision ruleBased = approvedOf(RULE_BASED, GateOutcome.PASSED, currentInputs());

        SegmentReadinessProjection projection = projectorOf(ruleBased).project(REFERENCE, currentInputs());

        assertThat(projection.state()).isEqualTo(CapabilityState.EXPERIMENTAL);
        assertThat(projection.aiReady()).isFalse();
        assertThat(projection.reasonCodes()).containsExactly(GateErrorCodes.APPROVAL_INVALID);
    }

    @Test
    void theReferenceGuardRejectsARuleBasedDecision() {
        ReferenceApprovedDecision ruleBased = approvedOf(RULE_BASED, GateOutcome.PASSED, currentInputs());

        assertThat(ReferenceActivationGuard.isActivatable(ruleBased, currentInputs())).isFalse();
        assertThatThrownBy(() -> ReferenceActivationGuard.requireActivatable(ruleBased, currentInputs()))
                .isInstanceOf(GateException.class)
                .extracting(thrown -> ((GateException) thrown).code())
                .isEqualTo(GateErrorCodes.CAPABILITY_NOT_GATED);

        // The dedicated reference capability of the same shape is accepted, so the rejection is about the
        // identity and not about the guard refusing every decision wholesale.
        assertThat(ReferenceActivationGuard.isActivatable(approvedPassed(currentInputs()), currentInputs()))
                .isTrue();
    }

    // ------------------------------------------------------------- every hash sensitivity

    @Test
    void theCurrentInputsPinTheCorpusVersionsGraphFormatAndHashes() {
        ReferenceReadinessInputs inputs = ReferenceReadinessInputs.current(
                "reference-corpus-1", "reference-corpus/1.0", hash("reference-corpus"),
                hash("reference-policy"), hash("catalog-directory-contract"), hash("dependency-graph-inputs"));

        assertThat(inputs.referenceCorpusId()).isEqualTo("reference-corpus-1");
        assertThat(inputs.referenceCorpusVersion()).isEqualTo("reference-corpus/1.0");
        assertThat(inputs.referenceCorpusHash()).isEqualTo(hash("reference-corpus"));
        assertThat(inputs.referencePolicyHash()).isEqualTo(hash("reference-policy"));
        assertThat(inputs.catalogDirectoryContractHash()).isEqualTo(hash("catalog-directory-contract"));
        assertThat(inputs.dependencyGraphInputsHash()).isEqualTo(hash("dependency-graph-inputs"));
        assertThat(inputs.referenceGraphFormat()).isEqualTo(ReferenceGraph.GRAPH_FORMAT_VERSION);
        assertThat(inputs.versions()).isEqualTo(ReferenceReadinessVersions.current());
    }

    @Test
    void everyPinnedReferenceInputMovesTheCurrentInputsHash() {
        ReferenceReadinessInputs base = currentInputs();

        List<ReferenceReadinessInputs> changed = List.of(
                // reference corpus identity and its content hash
                Inputs.of(base).corpusId("reference-corpus-2").build(),
                Inputs.of(base).corpusVersion("reference-corpus/2.0").build(),
                Inputs.of(base).corpusHash(hash("other-reference-corpus")).build(),
                // every frozen subsystem version
                Inputs.of(base).versions(
                        versionsWithResolver("segment-intent-resolver/2.0")).build(),
                Inputs.of(base).versions(
                        versionsWithValidator("segment-validator/2.0")).build(),
                Inputs.of(base).versions(
                        versionsWithCanonicalizer("segment-canonicalizer/2.0")).build(),
                Inputs.of(base).versions(
                        versionsWithPlanner("segment-query-planner/2.0")).build(),
                Inputs.of(base).versions(
                        versionsWithQueryAst("segment-query-ast/2.0")).build(),
                Inputs.of(base).versions(
                        versionsWithSqlCompiler("segment-sql-compiler/2.0")).build(),
                Inputs.of(base).versions(
                        versionsWithFixtureExecutor("segment-fixture-executor/2.0")).build(),
                // the reference graph format
                Inputs.of(base).graphFormat("segment-audience-reference-graph/2.0").build(),
                // the reference policy, the catalog/directory contract and the dependency-graph inputs
                Inputs.of(base).policyHash(hash("other-reference-policy")).build(),
                Inputs.of(base).contractHash(hash("other-catalog-directory-contract")).build(),
                Inputs.of(base).depsHash(hash("other-dependency-graph-inputs")).build());

        assertThat(changed).hasSize(14);
        Set<ContentHash> hashes = new LinkedHashSet<>();
        hashes.add(base.contentHash());
        for (ReferenceReadinessInputs variant : changed) {
            assertThat(variant.contentHash())
                    .as("a changed pinned input must move the canonical current-input hash")
                    .isNotEqualTo(base.contentHash());
            assertThat(hashes.add(variant.contentHash()))
                    .as("two different input bundles must never share one current-input hash")
                    .isTrue();
        }
    }

    @Test
    void theCurrentInputsHashIsByteDeterministic() {
        assertThat(currentInputs()).isEqualTo(currentInputs());
        assertThat(currentInputs().contentHash()).isEqualTo(currentInputs().contentHash());
        assertThat(currentInputs().contentHash()).isNotEqualTo(changedInputs().contentHash());
    }

    // ------------------------------------------------------------- missing / pending / failed / stale

    @Test
    void aMissingReferenceApprovalRemainsExperimental() {
        SegmentReadinessProjection projection = projectorOf(null).project(REFERENCE, currentInputs());

        assertThat(projection.state()).isEqualTo(CapabilityState.EXPERIMENTAL);
        assertThat(projection.aiReady()).isFalse();
        assertThat(projection.reasonCodes()).containsExactly(GateErrorCodes.APPROVAL_MISSING);
    }

    @Test
    void aPendingReferenceApprovalRemainsExperimental() {
        // An approval exists but its quorum is not yet complete: this is pending, not failure.
        ReferenceGateDecision decision = passedDecision(currentInputs());
        GateApprovalRecord partial = GateApprovalRecord.of(
                decision.decisionHash(), decision.outcome(),
                List.of(new GateOwnerApproval("owner-product-1", GateOwnerRole.PRODUCT, DECIDED_AT)));
        ReferenceApprovedDecision pending = new ReferenceApprovedDecision(REFERENCE, decision, partial);

        SegmentReadinessProjection projection = projectorOf(pending).project(REFERENCE, currentInputs());

        assertThat(projection.state()).isEqualTo(CapabilityState.EXPERIMENTAL);
        assertThat(projection.aiReady()).isFalse();
        assertThat(projection.reasonCodes()).containsExactly(GateErrorCodes.REFERENCE_APPROVAL_PENDING);
        assertThat(ReferenceActivationGuard.isActivatable(pending, currentInputs())).isFalse();
    }

    @Test
    void anApprovedFailedOrInvalidReferenceDecisionRemainsExperimental() {
        for (GateOutcome outcome : List.of(GateOutcome.FAILED, GateOutcome.INVALID)) {
            ReferenceApprovedDecision approved = approvedOf(REFERENCE, outcome, currentInputs());
            SegmentReadinessProjection projection = projectorOf(approved).project(REFERENCE, currentInputs());

            assertThat(projection.state()).as(outcome.name()).isEqualTo(CapabilityState.EXPERIMENTAL);
            assertThat(projection.aiReady()).as(outcome.name()).isFalse();
            assertThat(projection.reasonCodes()).as(outcome.name()).containsExactly(GateErrorCodes.NOT_PASSED);
            assertThat(projection.approvedDecisionHash()).as(outcome.name())
                    .isEqualTo(approved.decision().decisionHash());
            assertThat(ReferenceActivationGuard.isActivatable(approved, currentInputs()))
                    .as(outcome.name()).isFalse();
        }
    }

    @Test
    void aStaleReferenceApprovalYieldsEvaluationRequired() {
        // The decision was approved over one input bundle; the compile runs against another.
        ReferenceApprovedDecision approved = approvedPassed(currentInputs());
        SegmentReadinessProjection projection = projectorOf(approved).project(REFERENCE, changedInputs());

        assertThat(projection.state()).isEqualTo(CapabilityState.EVALUATION_REQUIRED);
        assertThat(projection.aiReady()).isFalse();
        assertThat(projection.reasonCodes()).containsExactly(GateErrorCodes.GATED_INPUT_CHANGED);
        assertThat(projection.currentInputsHash()).isEqualTo(changedInputs().contentHash());
        assertThat(ReferenceActivationGuard.isActivatable(approved, changedInputs())).isFalse();
    }

    @Test
    void aForgedOrTamperedReferenceDecisionHashNeverActivates() {
        ReferenceGateDecision decision = passedDecision(currentInputs());
        ReferenceGateDecision forged = decision.withDecisionHash(hash("forged-reference-decision"));
        ReferenceApprovedDecision approved = new ReferenceApprovedDecision(
                REFERENCE, forged,
                GateApprovalRecord.of(forged.decisionHash(), forged.outcome(), quorumList()));

        SegmentReadinessProjection projection = projectorOf(approved).project(REFERENCE, currentInputs());

        assertThat(projection.state()).isEqualTo(CapabilityState.EXPERIMENTAL);
        assertThat(projection.reasonCodes()).containsExactly(GateErrorCodes.APPROVAL_INVALID);
        assertThat(ReferenceActivationGuard.isActivatable(approved, currentInputs())).isFalse();
    }

    @Test
    void aTamperedReferenceApprovalHashOrConflictingOutcomeNeverActivates() {
        ReferenceGateDecision decision = passedDecision(currentInputs());

        GateApprovalRecord tampered = new GateApprovalRecord(
                GateVersions.APPROVAL_SCHEMA_VERSION, decision.decisionHash(), decision.outcome(),
                quorumList(), null);
        ReferenceApprovedDecision tamperedApproval =
                new ReferenceApprovedDecision(REFERENCE, decision, tampered);
        assertThat(projectorOf(tamperedApproval).project(REFERENCE, currentInputs()).reasonCodes())
                .containsExactly(GateErrorCodes.APPROVAL_INVALID);
        assertThat(ReferenceActivationGuard.isActivatable(tamperedApproval, currentInputs())).isFalse();

        GateApprovalRecord conflicting = GateApprovalRecord.of(
                decision.decisionHash(), GateOutcome.FAILED, quorumList());
        ReferenceApprovedDecision conflictingApproval =
                new ReferenceApprovedDecision(REFERENCE, decision, conflicting);
        assertThat(projectorOf(conflictingApproval).project(REFERENCE, currentInputs()).reasonCodes())
                .containsExactly(GateErrorCodes.APPROVAL_INVALID);
        assertThat(ReferenceActivationGuard.isActivatable(conflictingApproval, currentInputs())).isFalse();
    }

    @Test
    void aThrowingOrNullReferenceLookupIsTreatedAsAbsentEvidence() {
        ReferenceReadinessProjector throwing = new ReferenceReadinessProjector(capabilityId -> {
            throw new IllegalStateException("reference lookup unavailable");
        });
        assertThat(throwing.project(REFERENCE, currentInputs()).state())
                .isEqualTo(CapabilityState.EXPERIMENTAL);
        assertThat(throwing.project(REFERENCE, currentInputs()).reasonCodes())
                .containsExactly(GateErrorCodes.APPROVAL_INVALID);

        ReferenceReadinessProjector nullAnswer = new ReferenceReadinessProjector(capabilityId -> null);
        assertThat(nullAnswer.project(REFERENCE, currentInputs()).reasonCodes())
                .containsExactly(GateErrorCodes.APPROVAL_INVALID);
    }

    // ------------------------------------------------------------- the exact approved decision

    @Test
    void exactlyTheApprovedPassedDecisionOverExactInputsIsActive() {
        ReferenceApprovedDecision approved = approvedPassed(currentInputs());
        SegmentReadinessProjection projection = projectorOf(approved).project(REFERENCE, currentInputs());

        assertThat(projection.state()).isEqualTo(CapabilityState.ACTIVE);
        assertThat(projection.aiReady()).isTrue();
        assertThat(projection.reasonCodes()).isEmpty();
        assertThat(projection.approvedDecisionHash()).isEqualTo(approved.decision().decisionHash());
        assertThat(projection.currentInputsHash()).isEqualTo(currentInputs().contentHash());

        // The guard accepts exactly this evidence and nothing weaker.
        assertThat(ReferenceActivationGuard.isActivatable(approved, currentInputs())).isTrue();
        ReferenceActivationGuard.requireActivatable(approved, currentInputs());

        // A decision over a different corpus, policy, version or graph is a different input bundle and must
        // never activate.
        ReferenceApprovedDecision stale = approvedPassed(changedInputs());
        assertThat(projectorOf(stale).project(REFERENCE, currentInputs()).state())
                .isEqualTo(CapabilityState.EVALUATION_REQUIRED);
        assertThat(ReferenceActivationGuard.isActivatable(stale, currentInputs())).isFalse();
    }

    // ------------------------------------------------------------- no-reference non-consultation

    @Test
    void aReferenceFreeProjectionConsultsNoReferencePortAndStaysNonActive() {
        // A compile with no reference dependencies supplies no reference inputs. The reference Gate port must
        // not be asked at all, and the projection must never be ACTIVE.
        ReferenceReadinessProjector neverConsulted = new ReferenceReadinessProjector(capabilityId -> {
            throw new AssertionError("a reference-free compile must not consult the reference Gate port");
        });

        SegmentReadinessProjection projection = neverConsulted.project(REFERENCE, null);

        assertThat(projection.capabilityId()).isEqualTo(REFERENCE);
        assertThat(projection.state()).isEqualTo(CapabilityState.EXPERIMENTAL);
        assertThat(projection.aiReady()).isFalse();
        assertThat(projection.approvedDecisionHash()).isNull();
        assertThat(projection.reasonCodes()).containsExactly(GateErrorCodes.REFERENCE_NOT_PRESENT);
    }

    @Test
    void theProjectionIsByteDeterministic() {
        ReferenceApprovedDecision approved = approvedPassed(currentInputs());

        assertThat(projectorOf(approved).project(REFERENCE, currentInputs()).contentHash())
                .isEqualTo(projectorOf(approved).project(REFERENCE, currentInputs()).contentHash());
        assertThat(projectorOf(approved).project(REFERENCE, currentInputs()).contentHash())
                .isNotEqualTo(projectorOf(null).project(REFERENCE, currentInputs()).contentHash());
    }

    // ------------------------------------------------------------- helpers

    private static ReferenceReadinessInputs currentInputs() {
        return ReferenceReadinessInputs.current(
                "reference-corpus-1", "reference-corpus/1.0", hash("reference-corpus"),
                hash("reference-policy"), hash("catalog-directory-contract"), hash("dependency-graph-inputs"));
    }

    /** The same inputs with exactly one changed dependency-graph input, standing in for any drift. */
    private static ReferenceReadinessInputs changedInputs() {
        return Inputs.of(currentInputs()).depsHash(hash("other-dependency-graph-inputs")).build();
    }

    private static ReferenceReadinessProjector projectorOf(ReferenceApprovedDecision approved) {
        return new ReferenceReadinessProjector(capabilityId -> Optional.ofNullable(approved));
    }

    private static ReferenceApprovedDecision approvedPassed(ReferenceReadinessInputs inputs) {
        return approvedOf(REFERENCE, GateOutcome.PASSED, inputs);
    }

    private static ReferenceApprovedDecision approvedOf(
            String capabilityId, GateOutcome outcome, ReferenceReadinessInputs inputs) {
        ReferenceGateDecision decision =
                ReferenceGateDecision.of("reference-decision-1", outcome, inputs, DECIDED_AT);
        return new ReferenceApprovedDecision(
                capabilityId, decision,
                GateApprovalRecord.of(decision.decisionHash(), decision.outcome(), quorumList()));
    }

    private static ReferenceGateDecision passedDecision(ReferenceReadinessInputs inputs) {
        return ReferenceGateDecision.of("reference-decision-1", GateOutcome.PASSED, inputs, DECIDED_AT);
    }

    private static List<GateOwnerApproval> quorumList() {
        return List.of(
                new GateOwnerApproval("owner-product-1", GateOwnerRole.PRODUCT, DECIDED_AT),
                new GateOwnerApproval("owner-data-1", GateOwnerRole.DATA, DECIDED_AT),
                new GateOwnerApproval("owner-test-1", GateOwnerRole.TEST, DECIDED_AT));
    }

    private static ReferenceReadinessVersions versionsWithResolver(String resolverVersion) {
        ReferenceReadinessVersions base = ReferenceReadinessVersions.current();
        return new ReferenceReadinessVersions(
                resolverVersion, base.validatorVersion(), base.canonicalizerVersion(), base.plannerVersion(),
                base.queryAstVersion(), base.sqlCompilerVersion(), base.fixtureExecutorVersion());
    }

    private static ReferenceReadinessVersions versionsWithValidator(String validatorVersion) {
        ReferenceReadinessVersions base = ReferenceReadinessVersions.current();
        return new ReferenceReadinessVersions(
                base.resolverVersion(), validatorVersion, base.canonicalizerVersion(), base.plannerVersion(),
                base.queryAstVersion(), base.sqlCompilerVersion(), base.fixtureExecutorVersion());
    }

    private static ReferenceReadinessVersions versionsWithCanonicalizer(String canonicalizerVersion) {
        ReferenceReadinessVersions base = ReferenceReadinessVersions.current();
        return new ReferenceReadinessVersions(
                base.resolverVersion(), base.validatorVersion(), canonicalizerVersion, base.plannerVersion(),
                base.queryAstVersion(), base.sqlCompilerVersion(), base.fixtureExecutorVersion());
    }

    private static ReferenceReadinessVersions versionsWithPlanner(String plannerVersion) {
        ReferenceReadinessVersions base = ReferenceReadinessVersions.current();
        return new ReferenceReadinessVersions(
                base.resolverVersion(), base.validatorVersion(), base.canonicalizerVersion(), plannerVersion,
                base.queryAstVersion(), base.sqlCompilerVersion(), base.fixtureExecutorVersion());
    }

    private static ReferenceReadinessVersions versionsWithQueryAst(String queryAstVersion) {
        ReferenceReadinessVersions base = ReferenceReadinessVersions.current();
        return new ReferenceReadinessVersions(
                base.resolverVersion(), base.validatorVersion(), base.canonicalizerVersion(),
                base.plannerVersion(), queryAstVersion, base.sqlCompilerVersion(), base.fixtureExecutorVersion());
    }

    private static ReferenceReadinessVersions versionsWithSqlCompiler(String sqlCompilerVersion) {
        ReferenceReadinessVersions base = ReferenceReadinessVersions.current();
        return new ReferenceReadinessVersions(
                base.resolverVersion(), base.validatorVersion(), base.canonicalizerVersion(),
                base.plannerVersion(), base.queryAstVersion(), sqlCompilerVersion, base.fixtureExecutorVersion());
    }

    private static ReferenceReadinessVersions versionsWithFixtureExecutor(String fixtureExecutorVersion) {
        ReferenceReadinessVersions base = ReferenceReadinessVersions.current();
        return new ReferenceReadinessVersions(
                base.resolverVersion(), base.validatorVersion(), base.canonicalizerVersion(),
                base.plannerVersion(), base.queryAstVersion(), base.sqlCompilerVersion(), fixtureExecutorVersion);
    }

    private static ContentHash hash(String seed) {
        return ContentHash.sha256(seed.getBytes(StandardCharsets.UTF_8));
    }

    /** A tiny test-only mutable copy of one input bundle, so a sensitivity test changes exactly one field. */
    private static final class Inputs {

        private String corpusId;
        private String corpusVersion;
        private ContentHash corpusHash;
        private ReferenceReadinessVersions versions;
        private String graphFormat;
        private ContentHash policyHash;
        private ContentHash contractHash;
        private ContentHash depsHash;

        private Inputs(ReferenceReadinessInputs source) {
            this.corpusId = source.referenceCorpusId();
            this.corpusVersion = source.referenceCorpusVersion();
            this.corpusHash = source.referenceCorpusHash();
            this.versions = source.versions();
            this.graphFormat = source.referenceGraphFormat();
            this.policyHash = source.referencePolicyHash();
            this.contractHash = source.catalogDirectoryContractHash();
            this.depsHash = source.dependencyGraphInputsHash();
        }

        static Inputs of(ReferenceReadinessInputs source) {
            return new Inputs(source);
        }

        Inputs corpusId(String value) {
            this.corpusId = value;
            return this;
        }

        Inputs corpusVersion(String value) {
            this.corpusVersion = value;
            return this;
        }

        Inputs corpusHash(ContentHash value) {
            this.corpusHash = value;
            return this;
        }

        Inputs versions(ReferenceReadinessVersions value) {
            this.versions = value;
            return this;
        }

        Inputs graphFormat(String value) {
            this.graphFormat = value;
            return this;
        }

        Inputs policyHash(ContentHash value) {
            this.policyHash = value;
            return this;
        }

        Inputs contractHash(ContentHash value) {
            this.contractHash = value;
            return this;
        }

        Inputs depsHash(ContentHash value) {
            this.depsHash = value;
            return this;
        }

        ReferenceReadinessInputs build() {
            return new ReferenceReadinessInputs(
                    corpusId, corpusVersion, corpusHash, versions, graphFormat, policyHash, contractHash,
                    depsHash);
        }
    }
}