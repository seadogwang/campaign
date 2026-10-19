package com.company.campaign.builder.campaignagent;

import com.company.campaign.builder.assembly.CampaignComponentState;
import com.company.campaign.builder.assembly.CampaignSkillState;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * One bounded component-grouped TODO of a Campaign conversation.
 *
 * <p>It carries the stable component code, the stable host-derived status, the outstanding requirement/question
 * codes of that component and the bounded validation/blocker codes. It never carries a Skill identifier, a Tool
 * identifier, musser copy or a model value: the component code and the requirement codes are the only vocabulary
 * the user-facing progress may name.
 */
public record CampaignComponentTodo(
        CampaignComponentCode component,
        CampaignTodoStatus status,
        List<CampaignRequirementCode> requirements,
        List<String> validationCodes
) {

    public CampaignComponentTodo {
        if (component == null || status == null) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.REQUEST_INVALID, "componentTodo",
                    "A component TODO requires its component code and status.");
        }
        requirements = requirements == null ? List.of() : List.copyOf(new ArrayList<>(requirements));
        List<CampaignRequirementCode> ordered = new ArrayList<>(requirements);
        ordered.sort(Comparator.comparingInt(Enum::ordinal));
        requirements = List.copyOf(ordered);
        validationCodes = validationCodes == null ? List.of() : List.copyOf(new ArrayList<>(validationCodes));
        List<String> codes = new ArrayList<>(validationCodes);
        codes.sort(Comparator.naturalOrder());
        List<String> distinct = new ArrayList<>(codes.size());
        for (String code : codes) {
            if (code == null || !code.matches("^[A-Z][A-Z0-9_]{0,63}$")) {
                throw CampaignAgentException.invalid(
                        CampaignAgentErrorCodes.REQUEST_INVALID, "componentTodoValidationCode",
                        "A component TODO validation code must be a stable machine code.");
            }
            if (distinct.isEmpty() || !distinct.get(distinct.size() - 1).equals(code)) {
                distinct.add(code);
            }
        }
        validationCodes = List.copyOf(distinct);
    }

    /** True only when every requirement fact and every required Skill validation is accepted. */
    public boolean complete() {
        return status == CampaignTodoStatus.COMPLETE;
    }

    /**
     * The host-derived TODO of every component group, in the server-owned canonical order.
     *
     * <p>It is a pure, deterministic function of the accepted Campaign brief and the genuine T27 workspace Skill
     * states alone. A requirement code is outstanding exactly when the brief still reports it missing; a
     * validation or blocker code is the bounded code of a required Skill the workspace really recorded, or a
     * missing-dependency code the workspace really reports for this component. No model claim, no model prose
     * and no stored state is read, so a model can never assert progress the workspace does not hold.
     */
    public static List<CampaignComponentTodo> from(
            CampaignRequirementBrief brief, List<CampaignSkillState> skills) {
        return from(brief, skills, null, null);
    }

    /**
     * The host-derived TODO of every component group, closed against the workspace Skill states and the trusted
     * turn time.
     *
     * <p>It is the same pure, deterministic function as {@link #from(CampaignRequirementBrief, List)}, extended so
     * the EXECUTION_SCHEDULE group is marked {@link CampaignTodoStatus#BLOCKED} when its accepted schedule evidence
     * is complete for its mode but not executable against the trusted turn time. A {@code null} trusted turn time
     * or zone disables that check, so a caller without a trusted clock observes the same status as the two-argument
     * form.
     */
    public static List<CampaignComponentTodo> from(
            CampaignRequirementBrief brief,
            List<CampaignSkillState> skills,
            Instant trustedNow,
            ZoneId trustedZone) {
        CampaignRequirementBrief value = brief == null ? CampaignRequirementBrief.empty() : brief;
        List<CampaignSkillState> states = skills == null ? List.of() : skills;
        List<CampaignRequirementCode> missing = value.missingRequirements();
        List<CampaignComponentTodo> todos = new ArrayList<>();
        for (CampaignComponentCode component : CampaignComponentCode.values()) {
            todos.add(of(component, value, states, missing, trustedNow, trustedZone));
        }
        return List.copyOf(todos);
    }

    private static CampaignComponentTodo of(
            CampaignComponentCode component,
            CampaignRequirementBrief brief,
            List<CampaignSkillState> skills,
            List<CampaignRequirementCode> missing,
            Instant trustedNow,
            ZoneId trustedZone) {
        if (!applicable(component, brief)) {
            // The accepted brief decided this component is not required, so nothing about it is outstanding.
            return new CampaignComponentTodo(component, CampaignTodoStatus.COMPLETE, List.of(), List.of());
        }
        if (brief.unsupported()) {
            return new CampaignComponentTodo(
                    component, CampaignTodoStatus.BLOCKED, List.of(),
                    List.of(CampaignRequirementCode.CAMPAIGN_TYPE_UNSUPPORTED.code()));
        }
        // A genuine owning-Skill INVALID or a genuine missing-dependency code produces BLOCKED even when
        // requirement facts are also outstanding. A system configuration defect or a structural dependency
        // failure is never a user answer gap and must never be presented as NEEDS_INPUT.
        List<String> blockingCodes = blockingCodes(component, skills);
        List<CampaignRequirementCode> outstanding = new ArrayList<>();
        for (CampaignRequirementCode code : component.requirementCodes()) {
            if (missing.contains(code)) {
                outstanding.add(code);
            }
        }
        if (!blockingCodes.isEmpty()) {
            return new CampaignComponentTodo(
                    component, CampaignTodoStatus.BLOCKED, outstanding, blockingCodes);
        }
        if (!outstanding.isEmpty()) {
            CampaignTodoStatus status = started(component, brief)
                    ? CampaignTodoStatus.NEEDS_INPUT
                    : CampaignTodoStatus.NOT_STARTED;
            return new CampaignComponentTodo(
                    component, status, outstanding, outstandingValidationCodes(component, skills));
        }
        return validated(component, brief, skills, trustedNow, trustedZone);
    }

    /** The closed set of validation codes that are genuine system blockers: INVALID Skills and
     * missing workspace dependencies. NEEDS_INPUT codes are excluded because a pending user answer
     * is not a system defect. */
    private static List<String> blockingCodes(
            CampaignComponentCode component, List<CampaignSkillState> skills) {
        List<String> codes = new ArrayList<>();
        for (CampaignSkillState state : skills) {
            if (!component.skillIds().contains(state.skillId())) {
                continue;
            }
            if (state.state() == CampaignComponentState.INVALID) {
                add(codes, state.code());
            }
            for (String dependency : state.missingDependencyCodes()) {
                if (component.dependencyCodes().contains(dependency)) {
                    add(codes, dependency);
                }
            }
        }
        return List.copyOf(codes);
    }

    private static List<String> outstandingValidationCodes(
            CampaignComponentCode component, List<CampaignSkillState> skills) {
        List<String> codes = new ArrayList<>();
        for (CampaignSkillState state : skills) {
            if (!component.skillIds().contains(state.skillId())) {
                continue;
            }
            if (state.state() == CampaignComponentState.NEEDS_INPUT
                    || state.state() == CampaignComponentState.INVALID) {
                add(codes, state.code());
            }
            for (String dependency : state.missingDependencyCodes()) {
                if (component.dependencyCodes().contains(dependency)) {
                    add(codes, dependency);
                }
            }
        }
        return List.copyOf(codes);
    }

    /** The status of a component whose every requirement fact is already accepted, decided from Skill states. */
    private static CampaignComponentTodo validated(
            CampaignComponentCode component,
            CampaignRequirementBrief brief,
            List<CampaignSkillState> skills,
            Instant trustedNow,
            ZoneId trustedZone) {
        List<String> codes = new ArrayList<>();
        boolean blocked = false;
        boolean needsInput = false;
        List<String> ready = new ArrayList<>();
        for (CampaignSkillState state : skills) {
            if (!component.skillIds().contains(state.skillId())) {
                continue;
            }
            switch (state.state()) {
                case READY -> ready.add(state.skillId());
                case INVALID -> {
                    blocked = true;
                    add(codes, state.code());
                }
                case NEEDS_INPUT -> {
                    needsInput = true;
                    add(codes, state.code());
                }
                default -> {
                }
            }
            for (String dependency : state.missingDependencyCodes()) {
                if (component.dependencyCodes().contains(dependency)) {
                    blocked = true;
                    add(codes, dependency);
                }
            }
        }
        // A complete schedule whose evidence is not executable against the trusted turn time blocks its component
        // with bounded codes, even when an owning Skill reached READY: the accepted brief schedule is the authority
        // for finalization, and a past execution instant or window must never be treated as finalizable.
        if (component == CampaignComponentCode.EXECUTION_SCHEDULE) {
            for (String code : brief.schedule().invalid(brief.cadence(), trustedNow, trustedZone)) {
                blocked = true;
                add(codes, code);
            }
        }
        if (blocked) {
            return new CampaignComponentTodo(component, CampaignTodoStatus.BLOCKED, List.of(), codes);
        }
        // A component closes only when EVERY owning Skill is observed READY. One READY Skill is not enough, so
        // an OFFER with a DEFINED promotion requires both the Promotion and the Governance Skill to be READY.
        if (!component.skillIds().isEmpty() && ready.containsAll(component.skillIds())) {
            return new CampaignComponentTodo(component, CampaignTodoStatus.COMPLETE, List.of(), List.of());
        }
        if (needsInput) {
            return new CampaignComponentTodo(component, CampaignTodoStatus.IN_PROGRESS, List.of(), codes);
        }
        return new CampaignComponentTodo(component, CampaignTodoStatus.VALIDATING, List.of(), List.of());
    }

    private static void add(List<String> codes, String code) {
        if (code != null) {
            codes.add(code);
        }
    }

    /** True when the accepted brief still requires this component at all. */
    private static boolean applicable(CampaignComponentCode component, CampaignRequirementBrief brief) {
        return switch (component) {
            case SEGMENT -> brief.audienceApplicable();
            case OFFER -> brief.offerDecision() != OfferDecision.NONE;
            case COMMUNICATION -> brief.communicationDecision() != CommunicationDecision.NONE;
            case EXECUTION_SCHEDULE -> brief.type() != CampaignType.EVENT;
            default -> true;
        };
    }

    /** True when the accepted brief holds at least one fact of this component. */
    private static boolean started(CampaignComponentCode component, CampaignRequirementBrief brief) {
        return switch (component) {
            case SEGMENT -> brief.audience() != null;
            case OFFER -> brief.offerDecision() != OfferDecision.UNKNOWN;
            case COMMUNICATION -> brief.communicationDecision() != CommunicationDecision.UNKNOWN;
            case JOURNEY -> brief.branchDecision() != BranchDecision.UNKNOWN || brief.triggerEventCode() != null;
            case EXECUTION_SCHEDULE -> brief.cadence() != null || !brief.schedule().isEmpty();
        };
    }

    /** A bounded diagnostic: the component, the status and the counts, never a value. */
    @Override
    public String toString() {
        return "CampaignComponentTodo[component=" + component
                + ", status=" + status
                + ", requirements=" + requirements.size()
                + ", validationCodes=" + validationCodes.size() + ']';
    }
}
