package com.company.campaign.builder.campaignagent;

import com.company.campaign.builder.assembly.CampaignComponentState;
import com.company.campaign.builder.assembly.CampaignComponentView;
import com.company.campaign.builder.assembly.CampaignSkillState;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared classification and wording-assessment logic for the 70-scenario evaluation.
 *
 * <p>This is the single source of truth for both the focused unit tests
 * ({@link Campaign70ClassifierTest}) and the live-provider evaluation
 * ({@link Campaign70BusinessScenarioEvaluationTest}). No scenario-specific
 * logic, domain map, input keyword rule, or test skip lives here.
 *
 * <p>This class is package-private — it lives under {@code src/test} and is
 * never visible to production code.
 */
final class Campaign70ClassifierSupport {

    private Campaign70ClassifierSupport() {
    }

    // ---- Result classification ----

    enum Classification {
        PASS_COMPLETE,
        PASS_CLARIFICATION,
        PASS_UNSUPPORTED,
        FAIL,
        BLOCKED,
        REVIEW_REQUIRED
    }

    /** One detailed component issue record preserving attribution through serialization. */
    record ComponentIssueDetail(
            String component,
            String skill,
            String code,
            List<String> questionCodes,
            String prompt
    ) {
    }

    /**
     * Classifies one scenario result honestly from the actual accepted system outcome.
     *
     * <p>Classification is derived from the actual observed route kind, phase,
     * clarification quality and component/Skill state — never from domain metadata,
     * scenario IDs, input phrases or expected industry mappings.
     *
     * <h3>Classification decision order</h3>
     * <ol>
     *   <li>Refused turns: model/prov provider errors → {@code BLOCKED};
     *       Skill/artifact failures → {@code FAIL}; other refusals → {@code BLOCKED}.</li>
     *   <li>{@code OUT_OF_SCOPE} route → {@code PASS_UNSUPPORTED} (even if the phase is
     *       {@code AWAITING_GOAL} with no clarification — the system honestly declined).</li>
     *   <li>{@code TERMINAL_INVALID} phase → {@code PASS_UNSUPPORTED}.</li>
     *   <li>{@code AWAITING_CLARIFICATION} with valid clarification → {@code PASS_CLARIFICATION};
     *       missing attribution, empty question codes, or invalid options → {@code FAIL}.</li>
     *   <li>Component issues: missing component/skillId, or ownership violation → {@code FAIL};
     *       otherwise → {@code PASS_CLARIFICATION}.</li>
     *   <li>{@code READY_TO_FINALIZE}/{@code PROPOSAL_READY}: empty components → {@code FAIL};
     *       otherwise → {@code PASS_COMPLETE}.</li>
     *   <li>{@code AUDIENCE_READY} → {@code PASS_COMPLETE}.</li>
     *   <li>{@code BUILDING}: any INVALID skill → {@code PASS_UNSUPPORTED};
     *       otherwise → {@code PASS_COMPLETE}.</li>
     *   <li>{@code AWAITING_GOAL}: non-blank prompt and non-empty question codes
     *       → {@code PASS_CLARIFICATION}; otherwise → {@code FAIL}.</li>
     *   <li>Fallthrough → {@code PASS_UNSUPPORTED}.</li>
     * </ol>
     */
    static Classification classify(CampaignAgentTurnResult result, CampaignAgentSession session) {
        if (!result.accepted()) {
            String code = result.code();
            // Provider or inf infrastructure failure.
            if (CampaignAgentErrorCodes.MODEL_UNAVAILABLE.equals(code)
                    || CampaignAgentErrorCodes.MODEL_OUTPUT_REJECTED.equals(code)
                    || CampaignAgentErrorCodes.MODEL_OUTPUT_OVERSIZED.equals(code)) {
                return Classification.BLOCKED;
            }
            // Skill failure or artifact refusal under live routing is a system defect.
            if (CampaignAgentErrorCodes.SKILL_FAILED.equals(code)
                    || CampaignAgentErrorCodes.ARTIFACT_REFUSED.equals(code)) {
                return Classification.FAIL;
            }
            // Any other refusal is infrastructure.
            return Classification.BLOCKED;
        }

        CampaignConversationProjection projection = result.projection();
        CampaignRouteKind routeKind = projection.routeKind();
        CampaignAgentPhase phase = projection.phase();

        // OUT_OF_SCOPE: system honestly refused. Always PASS_UNSUPPORTED,
        // even if the phase is AWAITING_GOAL with no clarification.
        // An OUT_OF_SCOPE route that returns the conversation to AWAITING_GOAL
        // without a clarification is PASS_UNSUPPORTED because the system
        // honestly declined the request.
        if (routeKind == CampaignRouteKind.OUT_OF_SCOPE) {
            return Classification.PASS_UNSUPPORTED;
        }

        // TERMINAL_INVALID: system identified a capability boundary.
        if (phase == CampaignAgentPhase.TERMINAL_INVALID) {
            return Classification.PASS_UNSUPPORTED;
        }

        // Check for clarification quality.
        CampaignClarification clarification = projection.clarification();
        if (phase == CampaignAgentPhase.AWAITING_CLARIFICATION && clarification != null) {
            // Verify the clarification is attributable and valid.
            boolean attributable = clarification.skillId() != null || clarification.prompt() != null;
            boolean questionsValid = !clarification.questionCodes().isEmpty();
            boolean optionsValid = clarification.options() == null
                    || !clarification.options().isEmpty()
                    || clarification.freeTextAllowed();

            if (!attributable || !questionsValid || !optionsValid) {
                return Classification.FAIL;
            }
            return Classification.PASS_CLARIFICATION;
        }

        // Check for component issues quality.
        List<CampaignComponentClarification> componentIssues = projection.componentIssues();
        if (!componentIssues.isEmpty()) {
            for (CampaignComponentClarification issue : componentIssues) {
                if (issue.skillId() == null || issue.component() == null) {
                    return Classification.FAIL;
                }
                // Verify ownership: the Skill belongs to the component.
                if (!issue.component().skillIds().contains(issue.skillId())) {
                    return Classification.FAIL;
                }
            }
            return Classification.PASS_CLARIFICATION;
        }

        // READY_TO_FINALIZE or PROPOSAL_READY: genuinely completed.
        if (phase == CampaignAgentPhase.READY_TO_FINALIZE
                || phase == CampaignAgentPhase.PROPOSAL_READY) {
            // Verify the completion is genuine: at least one component has been assembled.
            if (projection.components().isEmpty()) {
                return Classification.FAIL;
            }
            return Classification.PASS_COMPLETE;
        }

        // AUDIENCE_READY or BUILDING: system accepted and is building.
        if (phase == CampaignAgentPhase.AUDIENCE_READY) {
            return Classification.PASS_COMPLETE;
        }
        if (phase == CampaignAgentPhase.BUILDING) {
            // Some progress was made. Check for invalid skills as evidence of problems.
            boolean hasInvalid = projection.skills().stream()
                    .anyMatch(s -> s.state() == CampaignComponentState.INVALID);
            if (hasInvalid) {
                return Classification.PASS_UNSUPPORTED;
            }
            return Classification.PASS_COMPLETE;
        }

        // AWAITING_GOAL: only passes if a real question is present.
        // Checked AFTER OUT_OF_SCOPE and TERMINAL_INVALID. A CAMPAIGN_REQUEST
        // (or other in-scope route) that lands in AWAITING_GOAL without a real
        // non-empty clarification question is FAIL — the system accepted an
        // in-scope request but made no progress and asked no question.
        if (phase == CampaignAgentPhase.AWAITING_GOAL) {
            CampaignClarification goalClarification = projection.clarification();
            // The clarification must carry a non-empty, safe prompt and at least one
            // question code to be a genuine ask.
            if (goalClarification != null
                    && goalClarification.prompt() != null
                    && !goalClarification.prompt().isBlank()
                    && !goalClarification.questionCodes().isEmpty()) {
                return Classification.PASS_CLARIFICATION;
            }
            // No real question exists: the system accepted the turn but produced nothing.
            return Classification.FAIL;
        }

        return Classification.PASS_UNSUPPORTED;
    }

    /**
     * Assesses whether the clarification wording is mechanically verifiable.
     *
     * <p>A clarification with a non-blank, safe prompt that passes the safe-copy check
     * is structurally sound, but whether a natural-language question is genuinely
     * "understandable" to the end user cannot be decided mechanically. Therefore every
     * wording assessment of a live-generated question is {@code REVIEW_REQUIRED} unless
     * a mechanical defect (blank, unsafe, over-length) is found.
     */
    static String wordingAssessment(
            Classification classification,
            CampaignAgentTurnResult result) {
        if (result == null || !result.accepted()) {
            return "NOT_APPLICABLE";
        }
        CampaignConversationProjection projection = result.projection();
        CampaignClarification clarification = projection != null
                ? projection.clarification() : null;

        // No clarification means no wording to assess.
        if (clarification == null && projection != null
                && projection.componentIssues().isEmpty()) {
            return "NOT_APPLICABLE";
        }

        // Check top-level clarification wording.
        if (clarification != null) {
            String prompt = clarification.prompt();
            if (prompt == null || prompt.isBlank()) {
                return "FAIL: clarification prompt is blank or missing";
            }
            if (prompt.length() > CampaignAgentLimits.MAX_ASSISTANT_COPY_LENGTH) {
                return "FAIL: clarification prompt exceeds length bound";
            }
            try {
                CampaignRoutingVocabulary.requireSafeCopy(prompt, "wordingCheck");
            } catch (CampaignAgentException e) {
                return "FAIL: clarification prompt contains forbidden tokens";
            }
        }

        // Check component-scoped clarification wording.
        if (projection != null) {
            for (CampaignComponentClarification issue : projection.componentIssues()) {
                String issuePrompt = issue.prompt();
                if (issuePrompt == null || issuePrompt.isBlank()) {
                    return "FAIL: component issue prompt is blank or missing";
                }
                try {
                    CampaignRoutingVocabulary.requireSafeCopy(issuePrompt, "wordingCheck");
                } catch (CampaignAgentException e) {
                    return "FAIL: component issue prompt contains forbidden tokens";
                }
            }
        }

        // A structurally valid question exists, but natural-language
        // understandability cannot be mechanically verified.
        return "REVIEW_REQUIRED: question text is structurally valid (non-blank, bounded, "
                + "safe) but natural-language understandability requires human review.";
    }

    /**
     * Builds detailed component issue records preserving attribution through serialization.
     */
    static List<ComponentIssueDetail> buildComponentIssueDetails(
            List<CampaignComponentClarification> issues) {
        List<ComponentIssueDetail> details = new ArrayList<>();
        for (CampaignComponentClarification issue : issues) {
            details.add(new ComponentIssueDetail(
                    issue.component().code(),
                    issue.skillId(),
                    issue.code(),
                    issue.questionCodes(),
                    issue.prompt()));
        }
        return List.copyOf(details);
    }

    // ---- Component and Skill snapshots for turn evidence ----

    /** One bounded component snapshot: identity facts only, no artifact hash or payload. */
    record ComponentSnapshot(
            String componentId,
            String skillId,
            String capabilityCode,
            String operationKind
    ) {}

    /** One bounded Skill snapshot: state, stable code, and missing dependencies. */
    record SkillSnapshot(
            String skillId,
            CampaignComponentState state,
            String code,
            List<String> missingDependencyCodes
    ) {}

    /** Builds bounded component snapshots from a projection, excluding artifact hashes. */
    static List<ComponentSnapshot> buildComponentSnapshots(CampaignConversationProjection projection) {
        List<ComponentSnapshot> snapshots = new ArrayList<>();
        for (CampaignComponentView component : projection.components()) {
            snapshots.add(new ComponentSnapshot(
                    component.componentId(),
                    component.skillId(),
                    component.capabilityCode(),
                    component.operationKind()));
        }
        return List.copyOf(snapshots);
    }

    /** Builds bounded Skill snapshots from a projection. */
    static List<SkillSnapshot> buildSkillSnapshots(CampaignConversationProjection projection) {
        List<SkillSnapshot> snapshots = new ArrayList<>();
        for (CampaignSkillState skill : projection.skills()) {
            snapshots.add(new SkillSnapshot(
                    skill.skillId(),
                    skill.state(),
                    skill.code(),
                    skill.missingDependencyCodes()));
        }
        return List.copyOf(snapshots);
    }

    // ---- Probe outcome derivation ----

    /** Evidence-accurate probe outcome, derived from actual component and Skill state. */
    enum ProbeOutcome {
        BLOCKED_BEFORE_COMPONENTS,
        COMPONENTS_INVALID,
        COMPONENTS_WITH_ISSUES,
        COMPONENTS_READY,
        COMPONENTS_IN_PROGRESS,
        REPEATED_REQUIREMENT_LOOP,
        SAFE_STOPPED_UNKNOWN_CODES,
        MAX_TURNS_REACHED,
        STOPPED_BEFORE_COMPONENTS
    }

    /**
     * Derives the evidence-accurate probe outcome from actual turn data.
     *
     * <p>Precedence (highest first):
     * <ol>
     *   <li>Blocked before any component → {@code BLOCKED_BEFORE_COMPONENTS}</li>
     *   <li>Components present and any Skill INVALID → {@code COMPONENTS_INVALID}</li>
     *   <li>Components present and attributable component issues/NEEDS_INPUT → {@code COMPONENTS_WITH_ISSUES}</li>
     *   <li>Components present and READY_TO_FINALIZE/PROPOSAL_READY with no invalid/issues → {@code COMPONENTS_READY}</li>
     *   <li>Components present in another non-invalid progress phase → {@code COMPONENTS_IN_PROGRESS}</li>
     *   <li>Repeated requirement loop → {@code REPEATED_REQUIREMENT_LOOP}</li>
     *   <li>Safe stopped with unknown codes → {@code SAFE_STOPPED_UNKNOWN_CODES}</li>
     *   <li>Max turns reached without components → {@code MAX_TURNS_REACHED}</li>
     *   <li>Fallthrough: stopped before components → {@code STOPPED_BEFORE_COMPONENTS}</li>
     * </ol>
     */
    static ProbeOutcome deriveProbeOutcome(
            boolean blocked,
            boolean reachedComponents,
            boolean repeatedRequirement,
            boolean safeStopped,
            boolean maxTurnReached,
            boolean anySkillInvalid,
            boolean hasComponentIssues,
            boolean hasReadyToFinalizeOrProposalReady) {

        // 1. Blocked before any component
        if (blocked && !reachedComponents) {
            return ProbeOutcome.BLOCKED_BEFORE_COMPONENTS;
        }

        // 2-5: Component-based outcomes
        if (reachedComponents) {
            // 2. Any Skill INVALID
            if (anySkillInvalid) {
                return ProbeOutcome.COMPONENTS_INVALID;
            }
            // 3. Attributable component issues or NEEDS_INPUT
            if (hasComponentIssues) {
                return ProbeOutcome.COMPONENTS_WITH_ISSUES;
            }
            // 4. READY_TO_FINALIZE or PROPOSAL_READY with no invalid/issues
            if (hasReadyToFinalizeOrProposalReady) {
                return ProbeOutcome.COMPONENTS_READY;
            }
            // 5. Components present in another non-invalid progress phase
            return ProbeOutcome.COMPONENTS_IN_PROGRESS;
        }

        // 6-9: Non-component outcomes
        if (repeatedRequirement) {
            return ProbeOutcome.REPEATED_REQUIREMENT_LOOP;
        }
        if (safeStopped) {
            return ProbeOutcome.SAFE_STOPPED_UNKNOWN_CODES;
        }
        if (maxTurnReached) {
            return ProbeOutcome.MAX_TURNS_REACHED;
        }
        return ProbeOutcome.STOPPED_BEFORE_COMPONENTS;
    }

    /**
     * Returns the evidence-accurate finding for a probe that stopped without components
     * when no clarification codes were present.
     *
     * <p>A probe with components and no further clarification codes must record
     * "component execution completed with no further host requirement question",
     * never "stopped before components".
     */
    static String noClarificationCodesFinding(int turnSeq, boolean hasComponents) {
        if (hasComponents) {
            return "No clarification codes at turn " + turnSeq
                    + "; component execution completed with no further host requirement question.";
        }
        return "No clarification codes at turn " + turnSeq
                + "; probe stopped before components.";
    }
}