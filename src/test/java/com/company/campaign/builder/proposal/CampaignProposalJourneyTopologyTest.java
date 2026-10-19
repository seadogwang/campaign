package com.company.campaign.builder.proposal;

import com.company.campaign.journey.graph.JourneyBranchKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Requirement 2: the strict, versioned Journey-topology mutation of the single Campaign authoring request.
 */
class CampaignProposalJourneyTopologyTest {

    private static final CampaignProposalCodec CODEC = new CampaignProposalCodec();
    private static final String CATALOG_HASH = "sha256:" + "a".repeat(64);
    private static final String POLICY_HASH = "sha256:" + "b".repeat(64);

    private static final String REPLACE = """
            ,"journeyTopology":{"action":"REPLACE","topologyFormatVersion":"1.0","edges":[\
            {"edgeId":"e1","from":{"clientRef":"n-start"},"to":{"logicalId":"flow-end-1"},"branch":"DEFAULT"}]}\
            """;

    @Test
    void omissionInheritsAndNeverClearsAndKeepsTheAcceptedCanonicalBytes() {
        CampaignProposalPatch omitted = CODEC.parse(proposal(""));
        assertThat(omitted.journeyTopology()).isNull();
        assertThat(omitted.hasJourneyTopology()).isFalse();
        assertThat(omitted.canonicalText()).doesNotContain("journeyTopology");
        assertThat(CODEC.parse(omitted.canonicalText()).proposalHash()).isEqualTo(omitted.proposalHash());
    }

    @Test
    void explicitReplacementRoundTripsThroughTheCanonicalWireForm() {
        CampaignProposalPatch replaced = CODEC.parse(proposal(REPLACE));
        assertThat(replaced.journeyTopology()).isNotNull();
        assertThat(replaced.journeyTopology().replaces()).isTrue();
        assertThat(replaced.journeyTopology().topologyFormatVersion()).isEqualTo("1.0");
        assertThat(replaced.journeyTopology().edges()).hasSize(1);
        ProposalTopologyEdge edge = replaced.journeyTopology().edges().get(0);
        assertThat(edge.branch()).isEqualTo(JourneyBranchKind.DEFAULT);
        assertThat(edge.from().clientRef()).isEqualTo("n-start");
        assertThat(edge.to().logicalId()).isEqualTo("flow-end-1");

        CampaignProposalPatch roundTripped = CODEC.parse(replaced.canonicalText());
        assertThat(roundTripped.canonicalText()).isEqualTo(replaced.canonicalText());
        assertThat(roundTripped.proposalHash()).isEqualTo(replaced.proposalHash());
    }

    @Test
    void explicitRemovalIsTheOnlyClearPathAndIsDistinctFromOmissionAndReplacement() {
        CampaignProposalPatch removed = CODEC.parse(proposal(",\"journeyTopology\":{\"action\":\"REMOVE\"}"));
        assertThat(removed.journeyTopology().removes()).isTrue();
        assertThat(removed.journeyTopology().edges()).isEmpty();
        assertThat(removed.canonicalText()).contains("REMOVE");

        CampaignProposalPatch omitted = CODEC.parse(proposal(""));
        CampaignProposalPatch replaced = CODEC.parse(proposal(REPLACE));
        assertThat(removed.proposalHash()).isNotEqualTo(omitted.proposalHash());
        assertThat(removed.proposalHash()).isNotEqualTo(replaced.proposalHash());
        assertThat(replaced.proposalHash()).isNotEqualTo(omitted.proposalHash());
    }

    @Test
    void theCanonicalHashCoversEveryTopologySemantic() {
        String branchChanged = proposal(REPLACE.replace("\"DEFAULT\"", "\"TRUE\""));
        String endpointChanged = proposal(REPLACE.replace("flow-end-1", "flow-end-2"));
        String identityChanged = proposal(REPLACE.replace("\"e1\"", "\"e2\""));

        assertThat(CODEC.parse(branchChanged).proposalHash())
                .isNotEqualTo(CODEC.parse(proposal(REPLACE)).proposalHash());
        assertThat(CODEC.parse(endpointChanged).proposalHash())
                .isNotEqualTo(CODEC.parse(proposal(REPLACE)).proposalHash());
        assertThat(CODEC.parse(identityChanged).proposalHash())
                .isNotEqualTo(CODEC.parse(proposal(REPLACE)).proposalHash());
    }

    @Test
    void unknownNullAndAlternateCasedTopologyMaterialFailsClosed() {
        assertCode(proposal(",\"journeyTopology\":{\"action\":\"REPLACE\",\"topologyFormatVersion\":\"1.0\","
                + "\"edges\":[{\"edgeId\":\"e1\",\"from\":{\"clientRef\":\"n-start\"},"
                + "\"to\":{\"logicalId\":\"flow-end-1\"},\"branch\":\"DEFAULT\"}],\"topologyHash\":\"x\"}"),
                ProposalErrorCodes.PROPOSAL_SHAPE_INVALID);
        assertCode(proposal(",\"journeyTopology\":null"), ProposalErrorCodes.PROPOSAL_NULL_FIELD);
        assertCode(proposal(",\"journeyTopology\":{\"action\":\"replace\"}"),
                ProposalErrorCodes.PROPOSAL_TOPOLOGY_INVALID);
        assertCode(proposal(",\"journeyTopology\":{\"action\":\"MERGE\"}"),
                ProposalErrorCodes.PROPOSAL_TOPOLOGY_INVALID);
        assertCode(proposal(REPLACE.replace("\"1.0\"", "\"2.0\"")),
                ProposalErrorCodes.PROPOSAL_TOPOLOGY_VERSION_INVALID);
        assertCode(proposal(REPLACE.replace("\"DEFAULT\"", "\"default\"")),
                ProposalErrorCodes.PROPOSAL_TOPOLOGY_INVALID);
    }

    @Test
    void duplicateSelfEdgeOverBoundAndUndeclaredEndpointMaterialFailsClosed() {
        assertCode(proposal(REPLACE.replace("}]}", "},{" + "\"edgeId\":\"e1\",\"from\":{\"clientRef\":\"a\"},"
                + "\"to\":{\"logicalId\":\"b\"},\"branch\":\"TRUE\"}]}")),
                ProposalErrorCodes.PROPOSAL_TOPOLOGY_DUPLICATE_EDGE);
        assertCode(proposal(REPLACE.replace("{\"logicalId\":\"flow-end-1\"}", "{\"clientRef\":\"n-start\"}")),
                ProposalErrorCodes.PROPOSAL_TOPOLOGY_SELF_EDGE);
        assertCode(proposal(REPLACE.replace("{\"clientRef\":\"n-start\"}",
                        "{\"clientRef\":\"n-start\",\"logicalId\":\"flow-end-1\"}")),
                ProposalErrorCodes.PROPOSAL_TOPOLOGY_ENDPOINT_INVALID);
        assertCode(proposal(REPLACE.replace("{\"clientRef\":\"n-start\"}", "{}")),
                ProposalErrorCodes.PROPOSAL_TOPOLOGY_ENDPOINT_INVALID);
        assertCode(proposal(",\"journeyTopology\":{\"action\":\"REPLACE\",\"topologyFormatVersion\":\"1.0\","
                + "\"edges\":[]}"), ProposalErrorCodes.PROPOSAL_TOPOLOGY_LIMIT_EXCEEDED);
        assertCode(proposal(",\"journeyTopology\":{\"action\":\"REMOVE\",\"edges\":["
                + "{\"edgeId\":\"e1\",\"from\":{\"clientRef\":\"a\"},\"to\":{\"logicalId\":\"b\"},"
                + "\"branch\":\"DEFAULT\"}]}"), ProposalErrorCodes.PROPOSAL_TOPOLOGY_INVALID);
    }

    @Test
    void theMutationModelRefusesAnOverBoundOrRepeatedEdgeSetDirectly() {
        List<ProposalTopologyEdge> overBound = new java.util.ArrayList<>();
        for (int index = 0; index <= com.company.campaign.journey.graph.JourneyTopology.MAX_EDGES; index++) {
            overBound.add(new ProposalTopologyEdge(
                    "edge-" + index,
                    ProposalIdentity.ofClientRef("from-" + index),
                    ProposalIdentity.ofClientRef("to-" + index),
                    JourneyBranchKind.DEFAULT));
        }
        assertThatThrownBy(() -> JourneyTopologyMutation.replace(overBound))
                .isInstanceOf(ProposalException.class);
        assertThatThrownBy(() -> JourneyTopologyMutation.replace(List.of(
                new ProposalTopologyEdge("e1", ProposalIdentity.ofClientRef("a"),
                        ProposalIdentity.ofClientRef("b"), JourneyBranchKind.DEFAULT),
                new ProposalTopologyEdge("e1", ProposalIdentity.ofClientRef("c"),
                        ProposalIdentity.ofClientRef("d"), JourneyBranchKind.DEFAULT))))
                .isInstanceOf(ProposalException.class);
    }

    private static void assertCode(String json, String expected) {
        ProposalException failure = catchThrowableOfType(() -> CODEC.parse(json), ProposalException.class);
        assertThat(failure).as(json).isNotNull();
        assertThat(failure.code()).as(json).isEqualTo(expected);
    }

    /**
     * One bounded CREATE proposal with the optional topology field appended verbatim.
     *
     * <p>It is stamped with the current Proposal version {@code 1.4}: the legacy {@code 1.3} shape has no
     * {@code journeyTopology} member at all.
     */
    private static String proposal(String topologyField) {
        return """
                {"schemaVersion":"%s","mode":"CREATE","baseRevisions":{\
                "catalogVersion":"catalog-31","catalogHash":"%s",\
                "policyVersion":"policy-8","policyHash":"%s"},\
                "operations":[{"op":"UPSERT","capability":"flow.start","clientRef":"n-start","value":{}}],\
                "clientContext":{"locale":"en-US"}%s}\
                """.formatted(ProposalSchemaVersions.CURRENT.value(), CATALOG_HASH, POLICY_HASH, topologyField);
    }
}