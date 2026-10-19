package com.company.campaign.builder.campaignagent.http;

import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * The bounded wire response of one Campaign conversation turn.
 *
 * <p>It is the conversation projection in wire form: the disposition of the turn, the session revision and
 * phase, the route kind, the transcript, the host-derived Skill states, the bounded ordered component views of
 * the T27 inspection, the pending typed clarification, the bounded assistant message, the audience result card,
 * the T27 workspace inspection hash and — only when the host produced one — the Proposal the existing Plan
 * action may consume.
 *
 * <p>It carries no model request or output byte, no Tool artifact, no template body, no reward secret, no
 * member identifier, no Provider fact and no exception message.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CampaignAgentTurnHttpResponse(
        boolean accepted,
        String code,
        String conversationId,
        String runId,
        long revision,
        String phase,
        String disposition,
        String routeKind,
        List<Turn> turns,
        List<Skill> skills,
        List<Component> components,
        Clarification clarification,
        String assistantMessage,
        Audience audience,
        ContentHash inspectionHash,
        JsonNode proposal,
        ContentHash proposalHash,
        String followUpPrompt,
        Journey journey,
        Requirements requirements,
        List<ComponentIssue> componentIssues,
        @JsonProperty("componentManifest")
        List<ComponentManifest> componentManifests
) {

    public CampaignAgentTurnHttpResponse(
            boolean accepted,
            String code,
            String conversationId,
            String runId,
            long revision,
            String phase,
            String disposition,
            String routeKind,
            List<Turn> turns,
            List<Skill> skills,
            List<Component> components,
            Clarification clarification,
            String assistantMessage,
            Audience audience,
            ContentHash inspectionHash,
            JsonNode proposal,
            ContentHash proposalHash,
            String followUpPrompt,
            Journey journey) {
        this(accepted, code, conversationId, runId, revision, phase, disposition, routeKind, turns, skills,
                components, clarification, assistantMessage, audience, inspectionHash, proposal, proposalHash,
                followUpPrompt, journey, null, null, null);
    }

    public CampaignAgentTurnHttpResponse(
            boolean accepted,
            String code,
            String conversationId,
            String runId,
            long revision,
            String phase,
            String disposition,
            String routeKind,
            List<Turn> turns,
            List<Skill> skills,
            List<Component> components,
            Clarification clarification,
            String assistantMessage,
            Audience audience,
            ContentHash inspectionHash,
            JsonNode proposal,
            ContentHash proposalHash,
            String followUpPrompt,
            Journey journey,
            Requirements requirements,
            List<ComponentIssue> componentIssues) {
        this(accepted, code, conversationId, runId, revision, phase, disposition, routeKind, turns, skills,
                components, clarification, assistantMessage, audience, inspectionHash, proposal, proposalHash,
                followUpPrompt, journey, requirements, componentIssues, null);
    }

    public CampaignAgentTurnHttpResponse {
        turns = turns == null ? List.of() : List.copyOf(new ArrayList<>(turns));
        skills = skills == null ? List.of() : List.copyOf(new ArrayList<>(skills));
        components = components == null ? List.of() : List.copyOf(new ArrayList<>(components));
        componentIssues = componentIssues == null ? null : List.copyOf(new ArrayList<>(componentIssues));
        componentManifests = componentManifests == null
                ? null
                : List.copyOf(new ArrayList<>(componentManifests));
    }

    /** One transcript turn. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Turn(String turnId, String role, int ordinal, String text, String at) {
    }

    /** One host-derived Skill progress fact. */
    public record Skill(
            String skillId, String state, String code, List<String> missingDependencyCodes) {

        public Skill {
            missingDependencyCodes = missingDependencyCodes == null
                    ? List.of()
                    : List.copyOf(new ArrayList<>(missingDependencyCodes));
        }
    }

    /**
     * One bounded component projection fact.
     *
     * <p>It carries only the accepted T27 inspection facts — the component identity, the owning Skill, the
     * nullable capability code, the operation kind and the accepted artifact hash. It cannot carry a Proposal
     * payload, a capability payload, a template body, a variable value, a reward value, a Tool result, a
     * member or contact identifier, Provider data or model output.
     */
    public record Component(
            String componentId,
            String skillId,
            String capabilityCode,
            String operationKind,
            ContentHash artifactHash) {
    }

    /** One pending typed clarification. */
    public record Clarification(
            String skillId,
            String code,
            String prompt,
            List<String> questionCodes,
            List<Option> options,
            boolean freeTextAllowed) {

        public Clarification {
            questionCodes = questionCodes == null ? List.of() : List.copyOf(new ArrayList<>(questionCodes));
            options = options == null ? List.of() : List.copyOf(new ArrayList<>(options));
        }
    }

    /** One bounded answer option. */
    public record Option(String optionId, String label) {
    }

    /** One bounded audience result card. */
    public record Audience(
            String status,
            int matchedCount,
            String asOf,
            String timezone,
            String dataWatermark,
            ContentHash previewHash) {
    }

    /**
     * One bounded Journey summary.
     *
     * <p>It carries only the accepted counts, the entry component identity and the two integrity hashes of the
     * staged bundle: no node payload, template body, reward secret, member fact or Provider response is
     * representable in it.
     */
    public record Journey(
            int nodeCount,
            int edgeCount,
            String entryComponentId,
            int terminalCount,
            ContentHash topologyHash,
            ContentHash artifactHash) {
    }

    /** The accumulated requirement facts and deterministic remaining gaps. */
    public record Requirements(
            String type,
            String audience,
            String offerDecision,
            String offerDetail,
            List<String> channels,
            String message,
            String branchDecision,
            String branchDetail,
            BranchRule branchRule,
            String cadence,
            Schedule schedule,
            String triggerEventCode,
            List<String> missing,
            boolean complete,
            boolean unsupported,
            List<ComponentTodo> componentTodo,
            Objective objective) {

        public Requirements {
            channels = channels == null ? List.of() : List.copyOf(channels);
            missing = missing == null ? List.of() : List.copyOf(missing);
            componentTodo = componentTodo == null ? List.of() : List.copyOf(new ArrayList<>(componentTodo));
            objective = objective == null ? new Objective(false, 0, -1L) : objective;
        }

        /** A source-compatible constructor for callers that carry no objective status. */
        public Requirements(
                String type,
                String audience,
                String offerDecision,
                String offerDetail,
                List<String> channels,
                String message,
                String branchDecision,
                String branchDetail,
                BranchRule branchRule,
                String cadence,
                Schedule schedule,
                String triggerEventCode,
                List<String> missing,
                boolean complete,
                boolean unsupported,
                List<ComponentTodo> componentTodo) {
            this(type, audience, offerDecision, offerDetail, channels, message, branchDecision, branchDetail,
                    branchRule, cadence, schedule, triggerEventCode, missing, complete, unsupported, componentTodo, null);
        }

        /** A source-compatible constructor for callers that carry no schedule evidence. */
        public Requirements(
                String type,
                String audience,
                String offerDecision,
                String offerDetail,
                List<String> channels,
                String message,
                String branchDecision,
                String branchDetail,
                BranchRule branchRule,
                String cadence,
                List<String> missing,
                boolean complete,
                boolean unsupported,
                List<ComponentTodo> componentTodo) {
            this(type, audience, offerDecision, offerDetail, channels, message, branchDecision, branchDetail,
                    branchRule, cadence, null, null, missing, complete, unsupported, componentTodo);
        }
    }

    /**
     * The accepted schedule evidence of a Campaign requirement.
     *
     * <p>It carries only bounded user-authored schedule facts — the canonical timezone identifier and, per mode,
     * the single execution instant or the repeated execution's local time and inclusive window. It carries no
     * computed occurrence, no resolved DST transition and no scheduling claim.
     */
    public record Schedule(
            String timeZone,
            String runAt,
            String localTime,
            String startDate,
            String endDate) {
    }

    /**
     * One component-grouped TODO fact in wire form.
     *
     * <p>It carries only the host-derived component code, the closed status, the outstanding requirement codes
     * of that component and the bounded validation/blocker codes: no Skill identifier, no Tool identifier, no
     * model value and no prose is representable in it.
     */
    public record ComponentTodo(
            String component,
            String status,
            List<String> requirements,
            List<String> validationCodes) {

        public ComponentTodo {
            requirements = requirements == null ? List.of() : List.copyOf(new ArrayList<>(requirements));
            validationCodes = validationCodes == null ? List.of() : List.copyOf(new ArrayList<>(validationCodes));
        }
    }

    /** The confirmed condition and both outcomes of a branched Campaign flow. */
    public record BranchRule(String condition, String whenTrue, String whenFalse) {
    }

    /** The safe, non-sensitive status of a confirmed Campaign business objective. */
    public record Objective(boolean confirmed, int evidenceLength, long acceptedAtRevision) {
    }

    /**
     * One per-component pending issue.
     *
     * <p>It carries the originating component's business area, its owning Skill, a stable error code, the
     * validated user-facing clarification question, the offending question codes as safe evidence, the bounded
     * answer options and the recoverable flag. It is the per-component complement to the flattened
     * {@link Clarification} — a frontend may use this list when it is present and falls back to the single
     * clarification otherwise.
     */
    public record ComponentIssue(
            String component,
            String skillId,
            String code,
            String prompt,
            List<String> questionCodes,
            List<Option> options,
            boolean freeTextAllowed) {

        public ComponentIssue {
            questionCodes = questionCodes == null ? List.of() : List.copyOf(new ArrayList<>(questionCodes));
            options = options == null ? List.of() : List.copyOf(new ArrayList<>(options));
        }
    }

    /**
     * One per-capability component manifest in wire form.
     *
     * <p>It carries the capability code, usage decision, decision source, reason code, evidence
     * and the bound resource codes. It is a read-only projection: the client may display it
     * but never mutate it. The server is the sole authority on manifests.
     */
    public record ComponentManifest(
            String capability,
            String usageDecision,
            String decisionSource,
            String reasonCode,
            String evidence,
            List<String> selectedResourceCodes) {

        public ComponentManifest {
            selectedResourceCodes = selectedResourceCodes == null
                    ? List.of()
                    : List.copyOf(new ArrayList<>(selectedResourceCodes));
        }
    }
}
