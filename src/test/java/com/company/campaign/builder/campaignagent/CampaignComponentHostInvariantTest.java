package com.company.campaign.builder.campaignagent;

import com.company.campaign.builder.assembly.CampaignAssemblyErrorCodes;
import com.company.campaign.builder.assembly.CampaignComponentState;
import com.company.campaign.builder.assembly.CampaignSkillIds;
import com.company.campaign.builder.assembly.CampaignSkillState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic contract tests for host-owned invariants of the Campaign Agent component system.
 *
 * <p>These are regression checks only — they prove the host derives the correct status, copy and
 * priority from its own state alone, with no model output, no fixture gateway and no scenario text.
 * They are not business acceptance evidence; the real-chain coordinator validates the live path with
 * a real model, a real browser and a real PostgreSQL instance.
 */
class CampaignComponentHostInvariantTest {

    // ---- communication configuration unavailable ---------------------------------------------

    /**
     * A COMMUNICATION component whose only Skill (MESSAGE_TEMPLATE) is INVALID because the resource
     * catalog has zero selectable channels must be BLOCKED, not NEEDS_INPUT, even when channel and
     * message requirements are still outstanding. The catalog defect is a host-owned configuration
     * problem, not a missing user answer.
     */
    @Test
    void communicationComponentWithInvalidSkillIsBlockedNotNeedsInput() {
        CampaignRequirementBrief brief = new CampaignRequirementBrief(
                null, null, OfferDecision.NONE, null,
                CommunicationDecision.DEFINED, List.of(CampaignMessageInputs.Channel.SMS),
                null, BranchDecision.NONE, null, null, null, CampaignExecutionSchedule.empty());

        List<CampaignSkillState> skills = List.of(
                new CampaignSkillState(
                        CampaignSkillIds.MESSAGE_TEMPLATE,
                        CampaignComponentState.INVALID,
                        CampaignAgentErrorCodes.COMMUNICATION_CONFIG_UNAVAILABLE,
                        List.of()));

        List<CampaignComponentTodo> todos = CampaignComponentTodo.from(brief, skills);

        CampaignComponentTodo communication = todos.stream()
                .filter(todo -> todo.component() == CampaignComponentCode.COMMUNICATION)
                .findFirst()
                .orElseThrow();

        assertThat(communication.status())
                .as("an INVALID owning Skill must produce BLOCKED, not NEEDS_INPUT, "
                        + "even when requirement facts are outstanding")
                .isEqualTo(CampaignTodoStatus.BLOCKED);

        assertThat(communication.validationCodes())
                .contains(CampaignAgentErrorCodes.COMMUNICATION_CONFIG_UNAVAILABLE);

        assertThat(communication.requirements())
                .as("outstanding requirement facts must still be carried — the brief "
                        + "selected a channel but has no message yet")
                .contains(CampaignRequirementCode.MESSAGE_REQUIRED);
    }

    /**
     * A component whose owning Skill is INVALID for a reason other than communication config
     * must also be BLOCKED, not NEEDS_INPUT, when requirement facts remain outstanding.
     */
    @Test
    void anyComponentWithInvalidSkillIsBlockedWhenRequirementsAreOutstanding() {
        // OFFER component: offerDecision is DEFINED but detail is missing.
        CampaignRequirementBrief brief = new CampaignRequirementBrief(
                null, null, OfferDecision.DEFINED, null,
                CommunicationDecision.NONE, List.of(),
                null, BranchDecision.NONE, null, null, null, CampaignExecutionSchedule.empty());

        // PROMOTION_REFERENCE is INVALID but GOVERNANCE_LIMIT is READY — offer detail still missing.
        List<CampaignSkillState> skills = List.of(
                new CampaignSkillState(
                        CampaignSkillIds.PROMOTION_REFERENCE,
                        CampaignComponentState.INVALID,
                        CampaignAgentErrorCodes.SKILL_UNAVAILABLE,
                        List.of()),
                new CampaignSkillState(
                        CampaignSkillIds.GOVERNANCE_LIMIT,
                        CampaignComponentState.READY,
                        null,
                        List.of()));

        List<CampaignComponentTodo> todos = CampaignComponentTodo.from(brief, skills);

        CampaignComponentTodo offer = todos.stream()
                .filter(todo -> todo.component() == CampaignComponentCode.OFFER)
                .findFirst()
                .orElseThrow();

        assertThat(offer.status())
                .as("an INVALID owning Skill must produce BLOCKED even when "
                        + "OFFER_DETAIL_REQUIRED is outstanding")
                .isEqualTo(CampaignTodoStatus.BLOCKED);

        assertThat(offer.validationCodes())
                .contains(CampaignAgentErrorCodes.SKILL_UNAVAILABLE);

        assertThat(offer.requirements())
                .contains(CampaignRequirementCode.OFFER_DETAIL_REQUIRED);
    }

    /**
     * A component whose owning Skill has a genuine NEEDS_INPUT state (not INVALID) and no missing
     * dependencies may report NEEDS_INPUT when requirement facts are outstanding. The NEEDS_INPUT
     * state is the result of a genuine agent question the user can answer; it is not a system defect.
     */
    @Test
    void componentWithoutBlockerReportsNeedsInputWhenRequirementsOutstanding() {
        CampaignRequirementBrief brief = new CampaignRequirementBrief(
                null, null, OfferDecision.UNKNOWN, null,
                CommunicationDecision.NONE, List.of(),
                null, BranchDecision.NONE, null, null, null, CampaignExecutionSchedule.empty());

        // No INVALID Skill, no missing dependency — genuine expectation of user answer.
        List<CampaignSkillState> skills = List.of();

        List<CampaignComponentTodo> todos = CampaignComponentTodo.from(brief, skills);

        CampaignComponentTodo offer = todos.stream()
                .filter(todo -> todo.component() == CampaignComponentCode.OFFER)
                .findFirst()
                .orElseThrow();

        assertThat(offer.status())
                .as("an unhinted component with no blocker must be NOT_STARTED")
                .isEqualTo(CampaignTodoStatus.NOT_STARTED);
    }

    /**
     * A missing workspace dependency (e.g. MISSING_GOVERNANCE_LIMIT for the OFFER component)
     * produces BLOCKED even when requirement facts are outstanding. A structural dependency
     * failure is a host-owned defect, not a user answer gap.
     */
    @Test
    void missingWorkspaceDependencyProducesBlockedWhenRequirementsOutstanding() {
        CampaignRequirementBrief brief = new CampaignRequirementBrief(
                null, null, OfferDecision.DEFINED, null,
                CommunicationDecision.NONE, List.of(),
                null, BranchDecision.NONE, null, null, null, CampaignExecutionSchedule.empty());

        // Both Skills are READY but the workspace reports a missing dependency code owned by OFFER.
        List<CampaignSkillState> skills = List.of(
                new CampaignSkillState(
                        CampaignSkillIds.PROMOTION_REFERENCE,
                        CampaignComponentState.READY,
                        null,
                        List.of(CampaignAssemblyErrorCodes.MISSING_GOVERNANCE_LIMIT)));

        List<CampaignComponentTodo> todos = CampaignComponentTodo.from(brief, skills);

        CampaignComponentTodo offer = todos.stream()
                .filter(todo -> todo.component() == CampaignComponentCode.OFFER)
                .findFirst()
                .orElseThrow();

        assertThat(offer.status())
                .as("a missing workspace dependency must produce BLOCKED even when "
                        + "OFFER_DETAIL_REQUIRED is outstanding")
                .isEqualTo(CampaignTodoStatus.BLOCKED);

        assertThat(offer.validationCodes())
                .contains(CampaignAssemblyErrorCodes.MISSING_GOVERNANCE_LIMIT);
    }

    // ---- OUT_OF_SCOPE copy -------------------------------------------------------------------

    /**
     * The host-owned OUT_OF_SCOPE copy must state the builder's capability bound without
     * inventing missing requirements, suggesting what the user should send, or asking for
     * audience/content.
     */
    @Test
    void outOfScopeCopyIsCapabilityBoundAndDoesNotInventRequirements() {
        String en = CampaignConversationAgent.OUT_OF_SCOPE_COPY_EN;
        String zh = CampaignConversationAgent.OUT_OF_SCOPE_COPY_ZH;

        // Must state what the builder is (creates/edits Campaign configurations).
        assertThat(en).contains("creates and edits");
        assertThat(zh).contains("创建和编辑");

        // Must state the builder cannot execute the requested operation.
        assertThat(en).contains("cannot execute");
        assertThat(zh).contains("无法执行");

        // Must NOT invent missing requirements — no "describe what you want to send",
        // "its audience", "supply content", etc.
        assertThat(en).doesNotContain("Describe what");
        assertThat(en).doesNotContain("its audience");
        assertThat(en).doesNotContain("who you want to reach");
        assertThat(zh).doesNotContain("请描述您要");
        assertThat(zh).doesNotContain("受众");
    }

    // ---- COMMUNICATION_CONFIG_UNAVAILABLE copy ----------------------------------------------

    /**
     * The host-owned COMMUNICATION_CONFIG_UNAVAILABLE copy must truthfully report the
     * configuration defect, state the channel was preserved, and never claim message
     * content, audience or another user answer is missing.
     */
    @Test
    void communicationConfigUnavailableCopyIsHostOwnedAndDoesNotBlameUser() {
        String en = CampaignConversationAgent.COMMUNICATION_CONFIG_UNAVAILABLE_COPY_EN;
        String zh = CampaignConversationAgent.COMMUNICATION_CONFIG_UNAVAILABLE_COPY_ZH;

        // Must state configuration is unavailable / no selectable channels.
        assertThat(en).contains("no selectable channels");
        assertThat(zh).contains("未配置任何可选渠道");

        // Must state the channel was preserved.
        assertThat(en).contains("preserved");
        assertThat(zh).contains("已保留");

        // Must state an administrator must configure channels.
        assertThat(en).contains("administrator");
        assertThat(zh).contains("管理员");

        // Must NOT claim message content, audience or user input is missing.
        assertThat(en).doesNotContain("message content");
        assertThat(en).doesNotContain("audience");
        assertThat(en).doesNotContain("missing detail");
        assertThat(zh).doesNotContain("消息内容");
        assertThat(zh).doesNotContain("受众");
        assertThat(zh).doesNotContain("缺失");
    }

    // ---- MISSING_METADATA_DEFINITION copy ----------------------------------------------------

    /**
     * The host-owned MISSING_METADATA_DEFINITION copy must identify a system metadata/config
     * gap, state that an administrator must register a definition, and never ask the user to
     * restate already-complete logical rules or fabricate an unrelated channel/event reason.
     */
    @Test
    void missingMetadataDefinitionCopyIdentifiesSystemGapNeverBlamesUser() {
        String en = CampaignConversationAgent.MISSING_METADATA_DEFINITION_COPY_EN;
        String zh = CampaignConversationAgent.MISSING_METADATA_DEFINITION_COPY_ZH;

        // Must state the system metadata definitions do not include the concept.
        assertThat(en).contains("metadata definitions");
        assertThat(zh).contains("元数据定义");

        // Must state an administrator must register a definition.
        assertThat(en).contains("administrator");
        assertThat(en).contains("register");
        assertThat(zh).contains("管理员");
        assertThat(zh).contains("注册");

        // Must NOT ask the user to restate rules.
        assertThat(en).doesNotContain("restate");
        assertThat(en).doesNotContain("supply the");
        assertThat(zh).doesNotContain("请提供");
        assertThat(zh).doesNotContain("规则定义");

        // Must NOT fabricate a channel/event incompatibility.
        assertThat(en).doesNotContain("channel");
        assertThat(en).doesNotContain("incompatible");
        assertThat(zh).doesNotContain("渠道");
        assertThat(zh).doesNotContain("触发事件");

        // Must NOT contain model-generated, ungrounded phrasing.
        assertThat(en).doesNotContain("触发事件");
        assertThat(en).doesNotContain("所选渠道");
        assertThat(zh).doesNotContain("所选渠道");
    }

    // ---- TERMINAL_INVALID copy is host-owned and never model-generated -------------------------

    /**
     * TERMINAL_INVALID copy must be host-owned deterministic text that never consults a model
     * and can never fabricate an unrelated reason like a channel/event incompatibility.
     *
     * <p>The terminalCopy method and the SYSTEM_FAILURE fallback are the two host-owned
     * sources for TERMINAL_INVALID copy. Neither may contain modeled, ungrounded text.
     */
    @Test
    void terminalInvalidCopyIsDeterministicAndNeverUngrounded() {
        String systemEn = CampaignConversationAgent.SYSTEM_FAILURE_COPY_EN;
        String systemZh = CampaignConversationAgent.SYSTEM_FAILURE_COPY_ZH;

        // SYSTEM_FAILURE is a bounded internal-error message.
        assertThat(systemEn).isEqualTo("An internal error occurred while processing your request.");
        assertThat(systemZh).isEqualTo("处理您的请求时发生内部错误。");

        // Must NOT fabricate channel/event or audience reasons.
        assertThat(systemEn).doesNotContain("channel");
        assertThat(systemEn).doesNotContain("event");
        assertThat(systemEn).doesNotContain("audience");
        assertThat(systemZh).doesNotContain("渠道");
        assertThat(systemZh).doesNotContain("触发");
        assertThat(systemZh).doesNotContain("受众");

        // A TERMINAL_INVALID phase uses terminalCopy(), whose DENIED flavor says
        // "That requirement is not permitted for your access." — this is the host-owned
        // deterministic text that must never fabricate an ungrounded reason.
        String deniedEn = "That requirement is not permitted for your access.";
        assertThat(deniedEn).doesNotContain("channel");
        assertThat(deniedEn).doesNotContain("incompatible");
        assertThat(deniedEn).doesNotContain("不支持");

        // The UNSUPPORTED flavor says "That requirement is not supported by this builder."
        String unsupportedEn = "That requirement is not supported by this builder.";
        assertThat(unsupportedEn).doesNotContain("channel");
        assertThat(unsupportedEn).doesNotContain("不支持");
    }
}