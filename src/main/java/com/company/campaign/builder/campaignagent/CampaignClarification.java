package com.company.campaign.builder.campaignagent;

import java.util.ArrayList;
import java.util.List;

/**
 * One pending typed clarification of a conversation.
 *
 * <p>It carries the Skill that asked, a bounded machine code, bounded safe prompt text, the stable question
 * codes and the bounded answer options. It never carries a Tool argument, a capability payload, a Metadata
 * definition identifier, a member identifier or a saved-state fact: a question cannot become a channel for
 * restricted data, and answering it is exactly one more bounded turn.
 *
 * <p>At the Campaign boundary a clarification with no bounded options is always free-text answerable: an
 * arbitrary missing fact must be answerable by ordinary chat, so a no-option clarification never carries
 * {@code freeTextAllowed=false}. A clarification that offers options remains an option selection and keeps the
 * Skill's own honest free-text setting.
 *
 * <p><strong>Option-ownership invariant.</strong> The bounded options and the projected {@code skillId} belong
 * to exactly one question code, and whenever {@code options} is non-empty that code is always the <em>first</em>
 * element of {@code questionCodes}. A client that submits a chosen option therefore binds it to
 * {@code questionCodes[0]} (and to no other code), which is also the question whose Skill is {@code skillId}. A
 * clarification with no options is a plain free-text question and privileges no code. See
 * {@link CampaignClarificationAggregate#project(CampaignRequirementExtractorPort, CampaignRequirementBrief, boolean)}
 * for the aggregate that upholds this contract.
 */
public record CampaignClarification(
        String skillId,
        String code,
        String prompt,
        List<String> questionCodes,
        List<CampaignAnswerOption> options,
        boolean freeTextAllowed
) {

    public CampaignClarification {
        if (code == null || !code.matches("^[A-Z][A-Z0-9_]{0,63}$")) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.SKILL_FAILED, "clarification",
                    "A clarification requires one bounded machine code.");
        }
        if (skillId != null) {
            CampaignRoutingVocabulary.requireKnownSkill(skillId);
        }
        prompt = prompt == null ? "Please clarify your Campaign requirement." : prompt;
        if (prompt.isEmpty() || prompt.length() > CampaignAgentLimits.MAX_ASSISTANT_COPY_LENGTH) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.SKILL_FAILED, "clarificationPrompt",
                    "A clarification prompt must be bounded.");
        }
        CampaignRoutingVocabulary.requireSafeCopy(prompt, "clarificationPrompt");
        questionCodes = questionCodes == null ? List.of() : List.copyOf(new ArrayList<>(questionCodes));
        if (questionCodes.isEmpty()) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.SKILL_FAILED, "clarificationQuestions",
                    "A clarification must name at least one stable question code.");
        }
        options = options == null ? List.of() : List.copyOf(new ArrayList<>(options));
        if (options.size() > CampaignAgentLimits.MAX_OPTIONS) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.ROUTING_LIMIT_EXCEEDED, "clarificationOptions",
                    "A clarification exceeds its option bound.");
        }
        // A clarification the Campaign boundary exposes with no bounded options is one arbitrary missing fact,
        // so it must remain answerable by free text: a no-option, no-free-text clarification would gate the
        // whole conversation on a question the user could never answer through ordinary chat. The Skill that
        // asked may still report free text honestly inside the router; this boundary only guarantees that what
        // the host projects can always be answered by a nonblank message with empty answers.
        if (options.isEmpty()) {
            freeTextAllowed = true;
        }
    }

    /** A bounded diagnostic: the code and the counts, never the prompt or an option label. */
    @Override
    public String toString() {
        return "CampaignClarification[code=" + code
                + ", questions=" + questionCodes.size()
                + ", options=" + options.size() + ']';
    }
}