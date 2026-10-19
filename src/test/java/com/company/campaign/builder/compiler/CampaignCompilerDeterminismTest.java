package com.company.campaign.builder.compiler;

import com.company.campaign.builder.proposal.CampaignProposalPatch;
import com.company.campaign.builder.proposal.MoveAnchorKind;
import com.company.campaign.builder.testsupport.BuilderFixtures;
import com.company.campaign.builder.testsupport.FixtureCapability;
import com.company.campaign.builder.testsupport.RecordingAuthorizationService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.company.campaign.builder.testsupport.BuilderFixtures.MESSAGE;
import static com.company.campaign.builder.testsupport.BuilderFixtures.SEGMENT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 16: compiling identical inputs many times yields byte-identical outputs and hashes, and
 * changing any one semantic input changes the covered hash.
 */
class CampaignCompilerDeterminismTest {

    private static final String SEGMENT_VERSION = "segment-rule-based-1.0";
    private static final String SEGMENT_VERSION_2 = "segment-rule-based-2.0";
    private static final String MESSAGE_VERSION = "message-template-1.0";

    private final RecordingAuthorizationService authorization = new RecordingAuthorizationService();
    private final CampaignCompiler compiler = new CampaignCompiler(authorization);
    private final FrozenAuthoringPolicy policy = BuilderFixtures.policy();
    private final FrozenCapabilityCatalog catalog = BuilderFixtures.catalog(
            FixtureCapability.passThrough(SEGMENT, SEGMENT_VERSION, FixtureCapability.ordered("segment")),
            FixtureCapability.passThrough(MESSAGE, MESSAGE_VERSION, FixtureCapability.unorderedMulti("message")));

    @Test
    void compilingIdenticalInputsManyTimesIsByteIdentical() {
        CompileRequest request = baseRequest();
        CampaignPlan first = compiler.compile(request);
        byte[] proposalBytes = request.patch().canonicalBytes();

        for (int attempt = 0; attempt < 120; attempt++) {
            CampaignPlan candidate = compiler.compile(request);
            assertThat(candidate.planId()).isEqualTo(first.planId());
            assertThat(candidate.planHash()).isEqualTo(first.planHash());
            assertThat(candidate.manifestHash()).isEqualTo(first.manifestHash());
            assertThat(candidate.proposalHash()).isEqualTo(first.proposalHash());
            assertThat(candidate.canonicalMaterialBytes()).isEqualTo(first.canonicalMaterialBytes());
            assertThat(candidate.manifest().canonicalBytes()).isEqualTo(first.manifest().canonicalBytes());
            assertThat(candidate.semanticDiff()).isEqualTo(first.semanticDiff());
            assertThat(candidate.changeSet()).isEqualTo(first.changeSet());
            assertThat(candidate.clientRefBindings()).isEqualTo(first.clientRefBindings());
            assertThat(request.patch().canonicalBytes()).isEqualTo(proposalBytes);
        }
    }

    @Test
    void changingAnySemanticInputChangesTheCoveredHash() {
        CampaignPlan base = compiler.compile(baseRequest());
        Map<String, CompileRequest> variants = variants();
        List<String> unchanged = new ArrayList<>();
        for (Map.Entry<String, CompileRequest> variant : variants.entrySet()) {
            if (compiler.compile(variant.getValue()).planHash().equals(base.planHash())) {
                unchanged.add(variant.getKey());
            }
        }
        assertThat(unchanged).as("semantic inputs that did not change the covered hash").isEmpty();
        assertThat(variants).hasSizeGreaterThanOrEqualTo(11);
    }

    private Map<String, CompileRequest> variants() {
        Map<String, CompileRequest> variants = new LinkedHashMap<>();

        // 1. the operation payload.
        variants.put("payload", editRequest(segmentPatch("changed"), List.of(segment("a", "base"))));

        // 2. the resulting component order (same facts, different arrangement).
        variants.put("componentOrder", editRequest(
                BuilderFixtures.editProposal().pins(catalog, policy)
                        .upsert(SEGMENT, "a", Map.of("spec", "base"))
                        .upsert(SEGMENT, "b", Map.of("spec", "base"))
                        .move("b", MoveAnchorKind.BEFORE, "a")
                        .build(),
                List.of(segment("a", "base"), segment("b", "base"))));

        // 3. the order in which operations are submitted (both objects are new).
        variants.put("operationOrder", createRequest(BuilderFixtures.createProposal().pins(catalog, policy)
                .upsertNew(SEGMENT, "first", Map.of("spec", "x"))
                .upsertNew(SEGMENT, "second", Map.of("spec", "y"))
                .build()));

        // 4. a resolved catalog reference.
        variants.put("resolvedReference", referencingVariant());

        // 5. the pinned capability contract version.
        variants.put("contractVersion", contractVersionVariant());

        // 6. the catalog version/hash.
        variants.put("catalog", catalogVariant());

        // 7. the policy version/hash.
        variants.put("policy", policyVersionVariant());

        // 8. the frozen policy's validation issues (same policy hash, different issues).
        variants.put("validationIssues", validationIssuesVariant());

        // 9. the trusted context owner.
        variants.put("contextOwner", new CompileRequest(
                segmentPatch("base"), current(List.of(segment("a", "base"))),
                catalog, policy, BuilderFixtures.instants(), BuilderFixtures.contextWithUser("user-777")));

        // 10. the explicit compile instant.
        variants.put("compileInstant", new CompileRequest(
                segmentPatch("base"), current(List.of(segment("a", "base"))),
                catalog, policy,
                new CompileInstants(Instant.parse("2026-10-03T00:05:00Z"), BuilderFixtures.EXPIRES_AT),
                BuilderFixtures.context()));

        // 11. the explicit expiry instant.
        variants.put("expiryInstant", new CompileRequest(
                segmentPatch("base"), current(List.of(segment("a", "base"))),
                catalog, policy,
                new CompileInstants(BuilderFixtures.COMPILED_AT, Instant.parse("2026-10-03T00:30:00Z")),
                BuilderFixtures.context()));

        // 12. the exact base campaign revision.
        variants.put("baseRevision", new CompileRequest(
                BuilderFixtures.editProposal().pins(catalog, policy).campaignRevision(13L)
                        .upsert(SEGMENT, "a", Map.of("spec", "base")).build(),
                BuilderFixtures.current(13L, 3L, null, List.of(segment("a", "base"))),
                catalog, policy, BuilderFixtures.instants(), BuilderFixtures.context()));

        return variants;
    }

    private CompileRequest referencingVariant() {
        FrozenCapabilityCatalog referencing = BuilderFixtures.catalog(
                FixtureCapability.referencing(SEGMENT, SEGMENT_VERSION, FixtureCapability.ordered("segment"),
                        BuilderFixtures.reference("tpl-1", "TEMPLATE", "template-a", "v1")),
                FixtureCapability.passThrough(MESSAGE, MESSAGE_VERSION, FixtureCapability.unorderedMulti("message")));
        return CompileRequest.create(
                BuilderFixtures.createProposal().pins(referencing, policy)
                        .upsertNew(SEGMENT, "segment-new", Map.of()).build(),
                referencing, policy, BuilderFixtures.instants(), BuilderFixtures.context());
    }

    private CompileRequest contractVersionVariant() {
        FrozenCapabilityCatalog versionTwo = BuilderFixtures.catalog(
                FixtureCapability.passThrough(SEGMENT, SEGMENT_VERSION_2, FixtureCapability.ordered("segment")),
                FixtureCapability.passThrough(MESSAGE, MESSAGE_VERSION, FixtureCapability.unorderedMulti("message")));
        ResolvedComponent segment = BuilderFixtures.component(
                "a", SEGMENT, SEGMENT_VERSION_2, FixtureCapability.ordered("segment"), Map.of("spec", "base"));
        return new CompileRequest(
                BuilderFixtures.editProposal().pins(versionTwo, policy)
                        .upsert(SEGMENT, "a", Map.of("spec", "base")).build(),
                BuilderFixtures.current(12L, 3L, null, List.of(segment)),
                versionTwo, policy, BuilderFixtures.instants(), BuilderFixtures.context());
    }

    private CompileRequest catalogVariant() {
        FrozenCapabilityCatalog rehashed = BuilderFixtures.catalogWithHash(
                "sha256:" + "e".repeat(64),
                FixtureCapability.passThrough(SEGMENT, SEGMENT_VERSION, FixtureCapability.ordered("segment")),
                FixtureCapability.passThrough(MESSAGE, MESSAGE_VERSION, FixtureCapability.unorderedMulti("message")));
        return new CompileRequest(
                BuilderFixtures.editProposal().pins(rehashed, policy)
                        .upsert(SEGMENT, "a", Map.of("spec", "base")).build(),
                current(List.of(segment("a", "base"))),
                rehashed, policy, BuilderFixtures.instants(), BuilderFixtures.context());
    }

    private CompileRequest policyVersionVariant() {
        FrozenAuthoringPolicy other = BuilderFixtures.policy("policy-9", request -> List.of());
        return new CompileRequest(
                BuilderFixtures.editProposal().pins(catalog, other)
                        .upsert(SEGMENT, "a", Map.of("spec", "base")).build(),
                current(List.of(segment("a", "base"))),
                catalog, other, BuilderFixtures.instants(), BuilderFixtures.context());
    }

    private CompileRequest validationIssuesVariant() {
        // The same policy version and hash, but a different ordered issue list.
        FrozenAuthoringPolicy advisory = BuilderFixtures.policyWithWarnings();
        assertThat(advisory.policyHash()).isEqualTo(policy.policyHash());
        return new CompileRequest(
                BuilderFixtures.editProposal().pins(catalog, advisory)
                        .upsert(SEGMENT, "a", Map.of("spec", "base")).build(),
                current(List.of(segment("a", "base"))),
                catalog, advisory, BuilderFixtures.instants(), BuilderFixtures.context());
    }

    private CompileRequest baseRequest() {
        return editRequest(BuilderFixtures.editProposal().pins(catalog, policy)
                .upsertNew(SEGMENT, "segment-new", Map.of("spec", "new"))
                .upsert(SEGMENT, "a", Map.of("spec", "base"))
                .upsertNew(MESSAGE, "message-new", Map.of("text", "hi"))
                .build(), List.of(segment("a", "base")));
    }

    private CompileRequest editRequest(CampaignProposalPatch patch, List<ResolvedComponent> components) {
        return new CompileRequest(
                patch, BuilderFixtures.current(12L, 3L, null, components), catalog, policy,
                BuilderFixtures.instants(), BuilderFixtures.context());
    }

    private CompileRequest createRequest(CampaignProposalPatch patch) {
        return CompileRequest.create(
                patch, catalog, policy, BuilderFixtures.instants(), BuilderFixtures.context());
    }

    private static TrustedCurrentCampaign current(List<ResolvedComponent> components) {
        return BuilderFixtures.current(12L, 3L, null, components);
    }

    private CampaignProposalPatch segmentPatch(String value) {
        return BuilderFixtures.editProposal().pins(catalog, policy)
                .upsert(SEGMENT, "a", Map.of("spec", value)).build();
    }

    private static ResolvedComponent segment(String logicalId, String value) {
        return BuilderFixtures.component(
                logicalId, SEGMENT, SEGMENT_VERSION, FixtureCapability.ordered("segment"), Map.of("spec", value));
    }
}