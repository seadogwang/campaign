package com.company.campaign.builder.campaignagent;

import com.company.campaign.builder.assembly.CampaignComponentState;
import com.company.campaign.builder.assembly.CampaignSkillIds;
import com.company.campaign.builder.assembly.CampaignSkillState;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T32 requirement 2: the routing prompt contract is versioned, canonically hashed, bounded and free of any
 * host-private or forbidden content, and its route and repair instructions are distinct.
 */
class CampaignRoutingPromptContractTest {

    private static CampaignRoutingRequest request() {
        return new CampaignRoutingRequest(
                "conversation-t32", "run-t32", CampaignTurnPosition.INITIAL,
                List.of(new CampaignSkillState(
                        CampaignSkillIds.MESSAGE_TEMPLATE, CampaignComponentState.MISSING, null, List.of())),
                List.of("USER: build a welcome campaign"),
                "build a welcome campaign for gold members");
    }

    @Test
    void theContractIsPinnedByAnExactVersionAndHash() {
        assertThat(CampaignRoutingPromptContract.VERSION).isEqualTo("campaign-routing-prompt/7");
        assertThat(CampaignRoutingPromptContract.HASH).isEqualTo(ContentHash.sha256(
                CampaignRoutingPromptContract.text().getBytes(StandardCharsets.UTF_8)));
        assertThat(CampaignRoutingPromptContract.HASH.value()).startsWith("sha256:");
    }

    @Test
    void thePromptDescribesOnlyTheClosedRoutingVocabulary() {
        String prompt = CampaignRoutingPromptContract.routePrompt(request());
        for (CampaignRouteKind kind : CampaignRouteKind.values()) {
            assertThat(prompt).contains(kind.name());
        }
        for (String skillId : CampaignSkillIds.ordered()) {
            assertThat(prompt).contains(skillId);
        }
        assertThat(prompt)
                .contains("routeKind").contains("campaignType").contains("skillIds").contains("questionCodes")
                .contains("assistantCopy").contains("options")
                .contains("canonical order")
                .contains("strictly Campaign work")
                .contains("never ask the user to choose")
                .contains("derived by the host from the schedule")
                .contains("registered business or customer event")
                .contains("objectiveEvidence")
                .contains("copied verbatim")
                .contains("direct string")
                .doesNotContain("preview.safe")
                .doesNotContain("saveDraft")
                .doesNotContain("http://")
                .doesNotContain("https://");
    }

    @Test
    void thePromptNamesTheClosedCampaignTypesAndNoAcceptanceCopy() {
        String prompt = CampaignRoutingPromptContract.text();
        for (CampaignType type : CampaignType.values()) {
            assertThat(prompt).contains(type.name()).contains(type.description());
        }
        assertThat(prompt)
                .doesNotContain(CampaignRoutingVocabulary.TYPE_REQUIRED_QUESTION_CODE)
                .doesNotContain("gold members")
                .doesNotContain("welcome campaign")
                .doesNotContain("20%")
                .doesNotContain("first-name");
    }

    @Test
    void thePromptCarriesTheRequestFactsButNoTrustedIdentityOrToolIdentifier() {
        String prompt = CampaignRoutingPromptContract.routePrompt(request());
        assertThat(prompt)
                .contains("conversation-t32")
                .contains("run-t32")
                .contains("INITIAL")
                .contains("build a welcome campaign for gold members")
                .doesNotContain("tenant")
                .doesNotContain("program")
                .doesNotContain("dataScope");
    }

    @Test
    void routeAndRepairAreDistinctBoundedInstructions() {
        String route = CampaignRoutingPromptContract.routePrompt(request());
        String repair = CampaignRoutingPromptContract.repairPrompt(request(), List.of("ROUTING_SHAPE_INVALID"));
        assertThat(repair).isNotEqualTo(route);
        assertThat(repair).contains("ROUTING_SHAPE_INVALID");
        assertThat(repair).doesNotContain("rejected output");
    }

    @Test
    void theRepairInstructionCarriesOnlyStableIssueCodes() {
        String repair = CampaignRoutingPromptContract.repairPrompt(
                request(), List.of("ROUTING_ORDER_INVALID", "not a code", "ROUTING_LIMIT_EXCEEDED"));
        assertThat(repair)
                .contains("ROUTING_ORDER_INVALID")
                .contains("ROUTING_LIMIT_EXCEEDED")
                .doesNotContain("not a code");
    }
}
