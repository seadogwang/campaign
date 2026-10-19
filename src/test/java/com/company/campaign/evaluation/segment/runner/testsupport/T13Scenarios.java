package com.company.campaign.evaluation.segment.runner.testsupport;

import com.company.campaign.builder.segment.SegmentIntentModelPort;
import com.company.campaign.builder.segment.SegmentModelRequest;
import com.company.campaign.builder.segment.testsupport.SliceTestFixtures;
import com.company.campaign.evaluation.segment.contract.FixtureRole;
import com.company.campaign.evaluation.segment.contract.RefinementOperation;
import com.company.campaign.evaluation.segment.contract.RiskLevel;
import com.company.campaign.evaluation.segment.contract.SegmentCategory;
import com.company.campaign.evaluation.segment.runner.EvaluationReport;
import com.company.campaign.evaluation.segment.runner.SegmentEvaluationRunner;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.dsl.contract.SegmentDslSchemaVersions;
import com.company.campaign.segment.dsl.predicate.AttributePredicate;
import com.company.campaign.segment.dsl.predicate.ConditionValue;
import com.company.campaign.segment.dsl.value.EnumValue;
import com.company.campaign.segment.dsl.contract.MetadataBinding;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.SubjectKind;
import com.fasterxml.jackson.databind.node.TextNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The shared scenario shapes of the focused T13 tests: one small real corpus, one tier document, and the
 * deterministic provider behaviours a repair or failure test needs.
 */
public final class T13Scenarios {

    public static final String TIER_UTTERANCE = "Gold会员";

    private T13Scenarios() {
    }

    /** A context whose tenant contradicts the pinned Metadata owner, so the Agent denies the run. */
    public static com.company.campaign.security.context.TrustedExecutionContext foreignContext() {
        com.company.campaign.contracts.TrustedExecutionContextRef reference =
                new com.company.campaign.contracts.TrustedExecutionContextRef(
                        "tenant-002", "program-001", "user-002", "scope-008", T13TestHost.SNAPSHOT_ID,
                        com.company.campaign.contracts.LocaleTag.of(java.util.Locale.SIMPLIFIED_CHINESE),
                        T13TestHost.context().snapshotRef().timezone(), "CNY",
                        T13TestHost.context().snapshotRef().asOf(),
                        T13TestHost.context().snapshotRef().dataWatermark());
        return com.company.campaign.security.context.TrustedContextResolver.resolve(
                new com.company.campaign.security.context.VerifiedIdentity(
                        "user-002", "https://issuer.example", java.time.Instant.parse("2026-10-01T00:00:00Z")),
                new com.company.campaign.security.context.PrincipalMembership(
                        "membership-2", "user-002", "tenant-002", "program-001", "scope-008",
                        com.company.campaign.security.context.MembershipStatus.ACTIVE,
                        com.company.campaign.metadata.testsupport.MetadataFixtures.allPermissions()),
                reference);
    }

    /** The one attribute predicate every focused scenario expects: {@code member.tier EQUAL GOLD}. */
    public static SegmentDsl tierDocument() {
        return tierDocument("n-tier");
    }

    public static SegmentDsl tierDocument(String nodeId) {
        MetadataBinding binding = MetadataBinding.fromSnapshot(T13TestHost.snapshot());
        return SegmentDsl.publish(
                SegmentDslSchemaVersions.SUPPORTED,
                SubjectKind.MEMBER,
                binding,
                new AttributePredicate(nodeId, "member.tier", ComparisonOperator.EQUAL,
                        ConditionValue.of(new EnumValue("GOLD"))));
    }

    /** A create Case whose expectation matches what {@link #tierPort()} produces. */
    public static T13TestCorpus.CaseHolder tierCase(
            String caseId, RiskLevel risk, SegmentCategory category) {
        T13TestCorpus.CaseHolder holder = new T13TestCorpus.CaseHolder(
                caseId, category, risk, TIER_UTTERANCE, T13TestHost.SNAPSHOT_ID, T13TestHost.CONTEXT_FIXTURE);
        holder.fixture(T13TestHost.TIER_FIXTURE)
                .disposition(com.company.campaign.contracts.Disposition.RESOLVED)
                .slot("c1", "MEMBER_TIER", "EQUAL", TIER_UTTERANCE, TextNode.valueOf("Gold"))
                .selected("member.tier")
                .ast(tierDocument())
                .memberIds(T13TestHost.TIER_FIXTURE, "m-001", "m-003")
                .claims("CLAIM_TIER_EQUALS_GOLD");
        return holder;
    }

    /** A Case expected to be {@code NEEDS_INPUT} with one clarification question code. */
    public static T13TestCorpus.CaseHolder clarificationCase(String caseId, RiskLevel risk) {
        T13TestCorpus.CaseHolder holder = new T13TestCorpus.CaseHolder(
                caseId, SegmentCategory.MULTIPLE_CANDIDATES_AND_REQUIRED_CLARIFICATION, risk,
                TIER_UTTERANCE, T13TestHost.SNAPSHOT_ID, T13TestHost.CONTEXT_FIXTURE);
        holder.fixture(T13TestHost.TIER_FIXTURE)
                .disposition(com.company.campaign.contracts.Disposition.NEEDS_INPUT)
                .slot("c1", "MEMBER_TIER", "EQUAL", TIER_UTTERANCE, TextNode.valueOf("Gold"))
                .questionCodes("DISAMBIGUATE_DEFINITION")
                .memberIds(T13TestHost.TIER_FIXTURE, "m-001", "m-003")
                .forbidden("ASSUME_INDEX_PATCH");
        return holder;
    }

    /** Writes a one-case corpus and returns its repository root. */
    public static Path corpusWith(Path root, T13TestCorpus.CaseHolder holder) {
        T13TestCorpus.in(root)
                .metadata(T13TestHost.snapshot())
                .context(T13TestHost.CONTEXT_FIXTURE, T13TestHost.context().snapshotRef())
                .fixture(T13TestHost.TIER_FIXTURE, FixtureRole.PRIMARY, T13TestAssets.TIER_UNIVERSE,
                        T13TestAssets.membersPayload())
                .caseSpec(holder)
                .write();
        return root;
    }

    /** Runs one case corpus with the given provider behaviour. */
    public static EvaluationReport run(Path root, String reportId, SegmentIntentModelPort port) {
        return run(root, reportId, new T13TestHost(port));
    }

    public static EvaluationReport run(Path root, String reportId, T13TestHost host) {
        return new SegmentEvaluationRunner(root).run(T13TestAssets.request(reportId), host);
    }

    /** The deterministic provider that produces the exact tier expectation. */
    public static SegmentIntentModelPort tierPort() {
        return SliceTestFixtures.StubModelPort.returning(SliceTestFixtures.enumTierJson());
    }

    /** A provider that answers the first pass with prose and the schema repair with the exact Intent. */
    public static SegmentIntentModelPort repairOncePort() {
        return new ScriptedPort(List.of("not json at all", SliceTestFixtures.enumTierJson()));
    }

    /** A provider that answers both calls with prose, so the single bounded repair also fails. */
    public static SegmentIntentModelPort alwaysProsePort() {
        return new ScriptedPort(List.of("first pass prose", "repair prose"));
    }

    /** A provider that records every request it is handed. */
    public static final class RecordingPort implements SegmentIntentModelPort {

        private final SegmentIntentModelPort delegate;
        private final List<SegmentModelRequest> requests = new ArrayList<>();

        public RecordingPort(SegmentIntentModelPort delegate) {
            this.delegate = delegate;
        }

        @Override
        public byte[] invoke(SegmentModelRequest request) {
            requests.add(request);
            return delegate.invoke(request);
        }

        public List<SegmentModelRequest> requests() {
            return List.copyOf(requests);
        }
    }

    /** A provider that fails the first pass with a semantic disposition and can never repair. */
    public static SegmentIntentModelPort unsupportedPort() {
        return new CountingDelegate(SliceTestFixtures.StubModelPort.returning(
                SliceTestFixtures.enumTierJson().replace(
                        "\"unsupportedRequirements\":[]", "\"unsupportedRequirements\":[\"RAW_SQL\"]")));
    }

    /** The number of times a wrapped provider was called. */
    public static final class CountingDelegate implements SegmentIntentModelPort {

        private final SegmentIntentModelPort delegate;
        private final AtomicInteger calls = new AtomicInteger();

        public CountingDelegate(SegmentIntentModelPort delegate) {
            this.delegate = delegate;
        }

        @Override
        public byte[] invoke(SegmentModelRequest request) {
            calls.incrementAndGet();
            return delegate.invoke(request);
        }

        public int calls() {
            return calls.get();
        }
    }

    /** A provider that answers each call from a fixed script; an exhausted script repeats its last entry. */
    public static final class ScriptedPort implements SegmentIntentModelPort {

        private final List<String> script;

        public ScriptedPort(List<String> script) {
            this.script = List.copyOf(script);
        }

        @Override
        public byte[] invoke(SegmentModelRequest request) {
            int index = request.callContext().kind()
                    == com.company.campaign.builder.segment.SegmentModelCallKind.FIRST_PASS ? 0 : 1;
            String response = script.get(Math.min(index, script.size() - 1));
            return response.getBytes(StandardCharsets.UTF_8);
        }
    }

    /** A single-condition base document every refinement scenario starts from. */
    public static SegmentDsl refinementBaseDocument() {
        MetadataBinding binding = MetadataBinding.fromSnapshot(T13TestHost.snapshot());
        return SegmentDsl.publish(
                SegmentDslSchemaVersions.SUPPORTED, SubjectKind.MEMBER, binding,
                new com.company.campaign.segment.dsl.predicate.GroupPredicate(
                        "n-root", com.company.campaign.segment.dsl.predicate.PredicateType.ALL,
                        List.of(new AttributePredicate(
                                "n-tier", "member.tier", ComparisonOperator.EQUAL,
                                ConditionValue.of(new EnumValue("GOLD"))))));
    }

    /** The one refinement proposal the focused refinement scenario pins: add a second tier node to the root. */
    public static com.company.campaign.builder.refine.SegmentRefinementPatch addTierPatch(SegmentDsl base) {
        return com.company.campaign.builder.refine.SegmentRefinementPatch.publish(
                com.company.campaign.contracts.ContractVersions.CURRENT,
                base.editableDslHash(),
                List.of(new com.company.campaign.builder.refine.SegmentRefinementOperation.AddNode(
                        "op-1", base.predicate().nodeId(), null,
                        new AttributePredicate("n-new-tier", "member.tier", ComparisonOperator.NOT_EQUAL,
                                ConditionValue.of(new EnumValue("SILVER"))))));
    }

    /** Applies the pinned patch through the genuine T12 editor, for building the frozen expectation. */
    public static SegmentDsl refinedDocument(SegmentDsl base) {
        return new com.company.campaign.builder.refine.SegmentRefinementEditor().apply(base, addTierPatch(base));
    }

    /** The refinement target every refined document of {@link #addTierPatch} observes. */
    public static RefinementOperation addTierOperation() {
        return RefinementOperation.ADD_NODE;
    }
}