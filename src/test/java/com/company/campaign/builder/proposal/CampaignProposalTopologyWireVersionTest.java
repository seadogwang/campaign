package com.company.campaign.builder.proposal;

import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.journey.graph.JourneyBranchKind;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * T31A requirements 1, 2 and 3: the explicit Proposal wire-version boundary, the omit-versus-null
 * distinction and the canonical topology edge order.
 *
 * <p>These tests pin the exact bytes of a legacy {@code 1.3} Proposal, prove that {@code 1.3} can never carry
 * the T31 {@code journeyTopology} member, exercise the {@code 1.4} omit/replace/remove round-trip, prove an
 * explicit JSON null is not collapsed into omission, and prove that a permuted replacement edge set
 * canonicalises to one byte-identical Proposal.
 */
class CampaignProposalTopologyWireVersionTest {

    private static final CampaignProposalCodec CODEC = new CampaignProposalCodec();

    private static final String CATALOG_HASH = "sha256:" + "a".repeat(64);
    private static final String POLICY_HASH = "sha256:" + "b".repeat(64);

    /**
     * The exact canonical bytes of the accepted pre-T31 shape. No field of the T31 topology member appears, so
     * accepting {@code 1.3} for the exact legacy shape leaves these bytes — and their hash — untouched.
     */
    private static final String LEGACY_CANONICAL_TEXT =
            "{\"baseRevisions\":{\"catalogHash\":\"" + CATALOG_HASH + "\","
            + "\"catalogVersion\":\"catalog-31\",\"policyHash\":\"" + POLICY_HASH + "\","
            + "\"policyVersion\":\"policy-8\"},\"clientContext\":{\"locale\":\"en-US\"},"
            + "\"mode\":\"CREATE\",\"operations\":[{\"capability\":\"flow.start\","
            + "\"clientRef\":\"n-start\",\"op\":\"UPSERT\",\"value\":{}}],\"schemaVersion\":\"1.3\"}";

    private static final String REPLACE = ",\"journeyTopology\":{\"action\":\"REPLACE\","
            + "\"topologyFormatVersion\":\"1.0\",\"edges\":["
            + "{\"edgeId\":\"e-1\",\"from\":{\"clientRef\":\"node-a\"},\"to\":{\"clientRef\":\"node-b\"},"
            + "\"branch\":\"DEFAULT\"}]}";

    @Test
    void legacyVersionWithoutTopologyParsesAndKeepsItsAcceptedCanonicalBytesAndHash() {
        CampaignProposalPatch legacy = CODEC.parse(legacyBody(""));

        assertThat(legacy.schemaVersion()).isEqualTo(ProposalSchemaVersions.LEGACY_V1_3);
        assertThat(legacy.schemaVersion()).isEqualTo(SchemaVersion.parse("1.3"));
        assertThat(legacy.canonicalText()).isEqualTo(LEGACY_CANONICAL_TEXT);
        assertThat(legacy.proposalHash())
                .isEqualTo(ContentHash.sha256(LEGACY_CANONICAL_TEXT.getBytes(StandardCharsets.UTF_8)));

        // The canonical form stays inside the readable legacy shape and round-trips unchanged.
        CampaignProposalPatch roundTripped = CODEC.parse(legacy.canonicalText());
        assertThat(roundTripped.canonicalText()).isEqualTo(LEGACY_CANONICAL_TEXT);
        assertThat(roundTripped.proposalHash()).isEqualTo(legacy.proposalHash());
    }

    @Test
    void legacyVersionCarryingTheTopologyMemberInAnyFormFailsClosed() {
        assertCode(legacyBody(REPLACE),
                ProposalErrorCodes.PROPOSAL_TOPOLOGY_SCHEMA_VERSION_UNSUPPORTED);
        assertCode(legacyBody(",\"journeyTopology\":null"),
                ProposalErrorCodes.PROPOSAL_TOPOLOGY_SCHEMA_VERSION_UNSUPPORTED);
        assertCode(legacyBody(",\"journeyTopology\":{\"action\":\"REPLACE\",\"edges\":[]}"),
                ProposalErrorCodes.PROPOSAL_TOPOLOGY_SCHEMA_VERSION_UNSUPPORTED);
    }

    @Test
    void currentVersionRoundTripsOmitReplaceAndRemoveCoherently() {
        CampaignProposalPatch omitted = CODEC.parse(currentBody(""));
        assertThat(omitted.schemaVersion()).isEqualTo(ProposalSchemaVersions.CURRENT_V1_4);
        assertThat(omitted.journeyTopology()).isNull();
        assertThat(omitted.canonicalText()).doesNotContain("journeyTopology");

        CampaignProposalPatch replaced = CODEC.parse(currentBody(REPLACE));
        assertThat(replaced.journeyTopology()).isNotNull();
        assertThat(replaced.canonicalText()).contains("\"schemaVersion\":\"1.4\"");

        CampaignProposalPatch removed = CODEC.parse(currentBody(",\"journeyTopology\":{\"action\":\"REMOVE\"}"));
        assertThat(removed.journeyTopology()).isNotNull();
        assertThat(removed.journeyTopology().removes()).isTrue();

        // Omit, replace and remove stay three distinct canonical forms at 1.4.
        assertThat(replaced.proposalHash()).isNotEqualTo(omitted.proposalHash());
        assertThat(removed.proposalHash()).isNotEqualTo(omitted.proposalHash());
        assertThat(removed.proposalHash()).isNotEqualTo(replaced.proposalHash());

        for (CampaignProposalPatch patch : List.of(omitted, replaced, removed)) {
            CampaignProposalPatch roundTripped = CODEC.parse(patch.canonicalText());
            assertThat(roundTripped.schemaVersion()).isEqualTo(ProposalSchemaVersions.CURRENT_V1_4);
            assertThat(roundTripped.canonicalText()).isEqualTo(patch.canonicalText());
            assertThat(roundTripped.proposalHash()).isEqualTo(patch.proposalHash());
        }
    }

    @Test
    void anExplicitTopologyNullIsRejectedByTheCurrentCodec() {
        // Missing means inherit; present JSON null is invalid and is never collapsed into omission.
        assertCode(currentBody(",\"journeyTopology\":null"), ProposalErrorCodes.PROPOSAL_NULL_FIELD);
    }

    @Test
    void directModelConstructionEnforcesTheSameVersionTopologyInvariant() {
        ProposalBaseRevisions baseRevisions = new ProposalBaseRevisions(
                null, "catalog-31", new ContentHash(CATALOG_HASH), "policy-8", new ContentHash(POLICY_HASH));
        List<ProposalOperation> operations = List.of(
                ProposalOperation.upsertNew("flow.start", "n-start", CapabilityPayload.empty()));

        ProposalException failure = catchThrowableOfType(() -> new CampaignProposalPatch(
                ProposalSchemaVersions.LEGACY_V1_3,
                ProposalMode.CREATE,
                null,
                baseRevisions,
                operations,
                ProposalClientContext.EMPTY,
                JourneyTopologyMutation.remove()), ProposalException.class);
        assertThat(failure).isNotNull();
        assertThat(failure.code())
                .isEqualTo(ProposalErrorCodes.PROPOSAL_TOPOLOGY_SCHEMA_VERSION_UNSUPPORTED);

        // The identical material is accepted under the current version, and the legacy six-argument overload
        // keeps the honest inherited topology accepted.
        assertThat(new CampaignProposalPatch(
                ProposalSchemaVersions.CURRENT_V1_4,
                ProposalMode.CREATE,
                null,
                baseRevisions,
                operations,
                ProposalClientContext.EMPTY,
                JourneyTopologyMutation.remove()).hasJourneyTopology()).isTrue();
        assertThat(new CampaignProposalPatch(
                ProposalSchemaVersions.LEGACY_V1_3,
                ProposalMode.CREATE,
                null,
                baseRevisions,
                operations,
                ProposalClientContext.EMPTY).hasJourneyTopology()).isFalse();

        ProposalException unsupported = catchThrowableOfType(() -> new CampaignProposalPatch(
                SchemaVersion.parse("2.0"),
                ProposalMode.CREATE,
                null,
                baseRevisions,
                operations,
                ProposalClientContext.EMPTY), ProposalException.class);
        assertThat(unsupported).isNotNull();
        assertThat(unsupported.code()).isEqualTo(ProposalErrorCodes.PROPOSAL_SCHEMA_VERSION_UNSUPPORTED);
    }

    @Test
    void permutedEdgeInputYieldsIdenticalCanonicalBytesAndHash() {
        CampaignProposalPatch forward = CODEC.parse(currentBody(edges("e-1", "e-2", "e-3")));
        CampaignProposalPatch permuted = CODEC.parse(currentBody(edges("e-3", "e-2", "e-1")));

        assertThat(permuted.canonicalText()).isEqualTo(forward.canonicalText());
        assertThat(permuted.proposalHash()).isEqualTo(forward.proposalHash());

        List<String> canonicalEdgeIds = forward.journeyTopology().edges().stream()
                .map(ProposalTopologyEdge::edgeId)
                .toList();
        assertThat(canonicalEdgeIds).containsExactly("e-1", "e-2", "e-3");
    }

    @Test
    void theMutationConstructorNormalisesEdgeOrderIndependentlyOfDuplicateDetection() {
        JourneyTopologyMutation mutation = JourneyTopologyMutation.replace(List.of(
                new ProposalTopologyEdge("e-3", ProposalIdentity.ofLogicalId("flow-old-2"),
                        ProposalIdentity.ofClientRef("node-b"), JourneyBranchKind.FALSE),
                new ProposalTopologyEdge("e-2", ProposalIdentity.ofClientRef("node-a"),
                        ProposalIdentity.ofLogicalId("flow-old-1"), JourneyBranchKind.TRUE),
                new ProposalTopologyEdge("e-1", ProposalIdentity.ofClientRef("node-a"),
                        ProposalIdentity.ofClientRef("node-b"), JourneyBranchKind.DEFAULT)));

        assertThat(mutation.edges().stream().map(ProposalTopologyEdge::edgeId).toList())
                .containsExactly("e-1", "e-2", "e-3");

        // A duplicate edge identity is still refused, whichever position it occupies.
        assertThatThrownBy(() -> JourneyTopologyMutation.replace(List.of(
                new ProposalTopologyEdge("e-9", ProposalIdentity.ofClientRef("a"),
                        ProposalIdentity.ofClientRef("b"), JourneyBranchKind.DEFAULT),
                new ProposalTopologyEdge("e-9", ProposalIdentity.ofClientRef("c"),
                        ProposalIdentity.ofClientRef("d"), JourneyBranchKind.TRUE))))
                .isInstanceOf(ProposalException.class)
                .satisfies(thrown -> assertThat(((ProposalException) thrown).code())
                        .isEqualTo(ProposalErrorCodes.PROPOSAL_TOPOLOGY_DUPLICATE_EDGE));
    }

    // ------------------------------------------------------------------ helpers

    private static String edges(String first, String second, String third) {
        return ",\"journeyTopology\":{\"action\":\"REPLACE\",\"topologyFormatVersion\":\"1.0\",\"edges\":["
                + edgeJson(first) + "," + edgeJson(second) + "," + edgeJson(third) + "]}";
    }

    private static String edgeJson(String edgeId) {
        return switch (edgeId) {
            case "e-1" -> "{\"edgeId\":\"e-1\",\"from\":{\"clientRef\":\"node-a\"},"
                    + "\"to\":{\"clientRef\":\"node-b\"},\"branch\":\"DEFAULT\"}";
            case "e-2" -> "{\"edgeId\":\"e-2\",\"from\":{\"clientRef\":\"node-a\"},"
                    + "\"to\":{\"logicalId\":\"flow-old-1\"},\"branch\":\"TRUE\"}";
            case "e-3" -> "{\"edgeId\":\"e-3\",\"from\":{\"logicalId\":\"flow-old-2\"},"
                    + "\"to\":{\"clientRef\":\"node-b\"},\"branch\":\"FALSE\"}";
            default -> throw new IllegalArgumentException("unknown test edge " + edgeId);
        };
    }

    /** One legacy {@code 1.3} CREATE body with an optional member appended verbatim. */
    private static String legacyBody(String trailing) {
        return """
                {"schemaVersion":"1.3","mode":"CREATE","baseRevisions":{\
                "catalogVersion":"catalog-31","catalogHash":"%s",\
                "policyVersion":"policy-8","policyHash":"%s"},\
                "operations":[{"op":"UPSERT","capability":"flow.start","clientRef":"n-start","value":{}}],\
                "clientContext":{"locale":"en-US"}%s}\
                """.formatted(CATALOG_HASH, POLICY_HASH, trailing);
    }

    /** One current {@code 1.4} CREATE body with an optional member appended verbatim. */
    private static String currentBody(String trailing) {
        return """
                {"schemaVersion":"1.4","mode":"CREATE","baseRevisions":{\
                "catalogVersion":"catalog-31","catalogHash":"%s",\
                "policyVersion":"policy-8","policyHash":"%s"},\
                "operations":[{"op":"UPSERT","capability":"flow.start","clientRef":"node-a","value":{}},\
                {"op":"UPSERT","capability":"flow.end","clientRef":"node-b","value":{}}],\
                "clientContext":{"locale":"en-US"}%s}\
                """.formatted(CATALOG_HASH, POLICY_HASH, trailing);
    }

    private static void assertCode(String json, String expected) {
        ProposalException failure = catchThrowableOfType(() -> CODEC.parse(json), ProposalException.class);
        assertThat(failure).as(json).isNotNull();
        assertThat(failure.code()).as(json).isEqualTo(expected);
    }
}
