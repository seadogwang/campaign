package com.company.campaign.builder.campaignagent;

import com.company.campaign.builder.assembly.CampaignSkillIds;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The host-owned aggregate of every component clarification one turn collected.
 *
 * <p>It exists because one turn may dispatch several independent component agents and more than one of them may
 * ask a question: the host must keep all of them rather than exposing only the first {@code NeedsInput} and
 * losing the rest. Every element retains its originating component and Skill, so the aggregate is the one place
 * the resume turn reads from, and no client field and no model output can choose which agents are resumed.
 *
 * <p>It is bounded by the closed component count and is immutable: it is copied, stored, restored and rolled
 * back exactly with the {@link CampaignAgentSession} it belongs to. It never carries a Tool argument, a
 * capability payload, a member identifier or a raw model value.
 */
public record CampaignClarificationAggregate(List<CampaignComponentClarification> requests) {

    /**
     * The one stable host-owned code of a multi-question aggregate.
     *
     * <p>A client branches on this exact code, so it is server vocabulary rather than model copy: it says "more
     * than one component still needs an answer", and it never names a component or a Skill.
     */
    public static final String AGGREGATE_CODE = "CAMPAIGN_COMPONENT_CLARIFICATION";

    /** The largest number of component clarifications one aggregate may carry; the closed component count. */
    public static final int MAX_REQUESTS = CampaignComponentCode.values().length;

    /** The prefix of the deterministic fallback sentence used when synthesis is unavailable or rejected. */
    private static final String FALLBACK_PREFIX = "More details are needed before I can continue:";

    public CampaignClarificationAggregate {
        requests = requests == null ? List.of() : List.copyOf(new ArrayList<>(requests));
        if (requests.size() > MAX_REQUESTS) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.ROUTING_LIMIT_EXCEEDED, "clarificationAggregate",
                    "A clarification aggregate exceeds its declared component bound.");
        }
        // Dedup key is (component, firstQuestionCode): two capabilities that map to the same
        // component (e.g. EVENT and FLOW both map to JOURNEY) may each carry a distinct
        // COMPONENT_DECISION question code. Two requests with the same component AND the same
        // first question code are still refused as ambiguous duplicates.
        Set<String> seen = new LinkedHashSet<>();
        for (CampaignComponentClarification request : requests) {
            if (request == null) {
                throw CampaignAgentException.invalid(
                        CampaignAgentErrorCodes.CLARIFICATION_MISMATCH, "clarificationAggregate",
                        "A clarification aggregate must not carry a null request.");
            }
            String key = request.component().name() + ':'
                    + (request.questionCodes().isEmpty() ? "" : request.questionCodes().get(0));
            if (!seen.add(key)) {
                throw CampaignAgentException.invalid(
                        CampaignAgentErrorCodes.CLARIFICATION_MISMATCH, "clarificationAggregate",
                        "A clarification aggregate carries distinct owned component+question requests only.");
            }
        }
    }

    /** The empty aggregate of a turn that collected no component question. */
    public static CampaignClarificationAggregate empty() {
        return new CampaignClarificationAggregate(List.of());
    }

    /** The aggregate of the component clarification requests one turn collected, in canonical component order. */
    public static CampaignClarificationAggregate of(List<CampaignComponentClarification> requests) {
        if (requests == null || requests.isEmpty()) {
            return empty();
        }
        List<CampaignComponentClarification> ordered = new ArrayList<>(requests);
        ordered.sort(Comparator.comparingInt(request -> request.component().ordinal()));
        return new CampaignClarificationAggregate(ordered);
    }

    /** True when this turn collected no component question. */
    public boolean isEmpty() {
        return requests.isEmpty();
    }

    /** True when exactly one component question is outstanding. */
    public boolean single() {
        return requests.size() == 1;
    }

    /**
     * The component-scoped pending request of one component, or null when that component has no
     * pending issue in this aggregate.
     *
     * <p>It is the host-owned lookup that the answer-matching guard uses to validate that an answer's
     * code belongs to the component it names. No model output or client field contributes to it.
     */
    public CampaignComponentClarification requestFor(CampaignComponentCode component) {
        for (CampaignComponentClarification request : requests) {
            if (request.component() == component) {
                return request;
            }
        }
        return null;
    }

    /**
     * The distinct owning Skills of this aggregate, in the server-owned canonical Skill order.
     *
     * <p>It is the host-derived resume set: the answer turn resumes exactly these Skills, never a Skill the
     * client or the model names, and never a Skill that is not recorded here.
     */
    public List<String> pendingSkillIds() {
        Set<String> distinct = new LinkedHashSet<>();
        for (CampaignComponentClarification request : requests) {
            distinct.add(request.skillId());
        }
        List<String> ordered = new ArrayList<>(distinct);
        ordered.sort(Comparator.comparingInt(skillId -> CampaignSkillIds.ordered().indexOf(skillId)));
        return List.copyOf(ordered);
    }

    /** The stable question codes of every outstanding request, in canonical order and without repetition. */
    public List<String> questionCodes() {
        Set<String> distinct = new LinkedHashSet<>();
        for (CampaignComponentClarification request : requests) {
            distinct.addAll(request.questionCodes());
        }
        List<String> ordered = new ArrayList<>(distinct);
        ordered.sort(Comparator.naturalOrder());
        return List.copyOf(ordered);
    }

    /**
     * The one backward-compatible {@link CampaignClarification} the existing HTTP/frontend contract receives.
     *
     * <p>Component prompts produced by
     * {@link CampaignComponentClarification#from(CampaignComponentCode, CampaignSkillOutcome.NeedsInput)}
     * are final — the Main Agent does not model-rewrite them.
     *
     * <p>A single outstanding question is projected with its component-owned prompt, so the top-level
     * {@code prompt} equals the owning {@link CampaignComponentClarification#prompt()} exactly. Several
     * outstanding questions become one bounded aggregate: the stable question codes are the union, the
     * prompt is a deterministic bounded concatenation of the individual component prompts, and an option
     * set is exposed only when exactly one outstanding question owns one. No model synthesis is used —
     * the aggregate prompt is entirely host-built from the component-level prompts.</p>
     *
     * <p><strong>Option-ownership invariant.</strong> … (unchanged)</p>
     */
    public CampaignClarification project(
            CampaignRequirementExtractorPort extractor, CampaignRequirementBrief brief) {
        return project(extractor, brief, false);
    }

    /**
     * The one backward-compatible projection, with the host's knowledge that the Segment Skill is mid-selection.
     *
     * <p>A Segment definition-selection question can only ever be answered by choosing one of its own options,
     * so when such a selection is genuinely pending it is the option owner even if another question also carries
     * options. The other question stays free-text answerable, and exactly one unambiguous question owns the
     * exposed options either way, so no ambiguous option is ever created.
     *
     * <p><strong>Option-ownership invariant.</strong> The existing wire contract — one projected
     * {@code skillId}, one flat {@code questionCodes} union and one flat {@code options} list — has no per-question
     * option field, so whenever this projection exposes options it makes the owning question unambiguous in the
     * only place the contract allows: the <em>first</em> element of {@code questionCodes} is always a question
     * code of the option-owning request, and {@code skillId} is always that same request's Skill. The owner's own
     * codes (in the owner's order) come first, followed by every remaining union code in the deterministic
     * natural code order. Without this rule a client that binds a chosen option to {@code questionCodes[0]}, as
     * the existing frontend does, could submit a Promotion option under a Segment question code whenever the
     * Segment code happened to sort first. An aggregate that exposes no options keeps the plain deterministic
     * union order and a {@code null} projected {@code skillId}, because then no code is privileged.
     */
    public CampaignClarification project(
            CampaignRequirementExtractorPort extractor,
            CampaignRequirementBrief brief,
            boolean segmentSelectionPending) {
        if (requests.isEmpty()) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.CLARIFICATION_MISMATCH, "clarificationAggregate",
                    "An empty aggregate has no clarification to project.");
        }
        if (single()) {
            CampaignComponentClarification only = requests.get(0);
            return new CampaignClarification(
                    only.skillId(), only.code(), only.prompt(), only.questionCodes(), only.options(),
                    only.freeTextAllowed());
        }
        List<String> questionCodes = questionCodes();
        if (questionCodes.size() > CampaignAgentLimits.MAX_QUESTION_CODES * MAX_REQUESTS) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.ROUTING_LIMIT_EXCEEDED, "clarificationAggregateQuestions",
                    "A clarification aggregate exceeds its declared question bound.");
        }
        String prompt = fallback();
        // Selectable options are exposed only when exactly one outstanding question owns them: then their
        // ownership is unambiguous, the projected Skill identifier is that same question's Skill, and free text
        // stays allowed for the other outstanding questions. Two option-bearing questions expose no option at
        // all, so no ambiguity about who owns a choice can ever be created.
        CampaignComponentClarification owner = segmentSelectionPending ? segmentSelectionOwner() : null;
        if (owner == null) {
            List<CampaignComponentClarification> optionOwners = new ArrayList<>();
            for (CampaignComponentClarification request : requests) {
                if (!request.options().isEmpty()) {
                    optionOwners.add(request);
                }
            }
            if (optionOwners.size() != 1) {
                // No option is exposed, so no question is privileged: the plain deterministic union order is
                // projected and the Skill identity stays null because the aggregate owns the question, not a
                // single component.
                return new CampaignClarification(null, AGGREGATE_CODE, prompt, questionCodes, List.of(), true);
            }
            owner = optionOwners.get(0);
        }
        return new CampaignClarification(
                owner.skillId(), AGGREGATE_CODE, prompt, ownedFirst(questionCodes, owner), owner.options(), true);
    }

    /**
     * The union question codes with the option owner's own codes first, in the owner's order.
     *
     * <p>It is the deterministic ordering half of the option-ownership invariant documented on
     * {@link #project(CampaignRequirementExtractorPort, CampaignRequirementBrief, boolean)}: the exposed options
     * and the projected Skill identity both belong to the first question code, so a client may bind a chosen
     * option to it without ever mis-attributing the choice to another component's question. The remaining union
     * codes keep the aggregate's natural code order.
     */
    private static List<String> ownedFirst(
            List<String> union, CampaignComponentClarification owner) {
        List<String> ordered = new ArrayList<>(union.size());
        for (String code : owner.questionCodes()) {
            if (union.contains(code) && !ordered.contains(code)) {
                ordered.add(code);
            }
        }
        for (String code : union) {
            if (!ordered.contains(code)) {
                ordered.add(code);
            }
        }
        return List.copyOf(ordered);
    }

    /** The pending Segment selection question when it really owns options, or null. */
    private CampaignComponentClarification segmentSelectionOwner() {
        for (CampaignComponentClarification request : requests) {
            if (request.segment() && !request.options().isEmpty()) {
                return request;
            }
        }
        return null;
    }

    private String fallback() {
        StringBuilder sentence = new StringBuilder(FALLBACK_PREFIX);
        for (CampaignComponentClarification request : requests) {
            String next = " " + request.prompt();
            if (sentence.length() + next.length() > CampaignAgentLimits.MAX_ASSISTANT_COPY_LENGTH) {
                break;
            }
            sentence.append(next);
        }
        return sentence.toString();
    }

    /** A bounded diagnostic: the count and the owning skills, never a prompt or a label. */
    @Override
    public String toString() {
        return "CampaignClarificationAggregate[requests=" + requests.size()
                + ", skills=" + pendingSkillIds().size() + ']';
    }
}