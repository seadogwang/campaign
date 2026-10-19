package com.company.campaign.builder.campaignagent;

import com.company.campaign.builder.assembly.CampaignJourneyArtifact;
import com.company.campaign.builder.assembly.CampaignSkillArtifact;
import com.company.campaign.builder.compiler.CapabilityCompileContext;
import com.company.campaign.contracts.Disposition;

import java.util.ArrayList;
import java.util.List;

/**
 * The closed typed outcome one Skill runner returns.
 *
 * <p>There are exactly five shapes. {@link Ready} carries the one T27 {@link CampaignSkillArtifact} the host
 * may stage; {@link ReadyJourney} carries the one atomic multi-component Journey bundle plus its bounded
 * summary. The workspace still re-verifies the run, Skill, Tool, catalog and revision before either enters
 * state. {@link NeedsInput} carries a typed clarification and never a partial artifact. {@link Unavailable}
 * carries one bounded code for a Skill this build has no accepted implementation for. {@link Terminal}
 * preserves a genuine non-resolved disposition.
 *
 * <p>None of the five can carry a capability payload, a Tool argument, a template body, a reward secret, a
 * member identifier or an exception message.
 */
public sealed interface CampaignSkillOutcome {

    /** The server-owned Skill identifier this outcome belongs to. */
    String skillId();

    /** One accepted final artifact the T27 workspace may stage, plus its bounded audience card
     *  and optional host-owned compile context when any. */
    record Ready(
            String skillId,
            String toolId,
            CampaignSkillArtifact artifact,
            CampaignAudienceSummary audience,
            CapabilityCompileContext capabilityContext) implements CampaignSkillOutcome {

        public Ready {
            if (skillId == null || toolId == null || artifact == null) {
                throw CampaignAgentException.invalid(
                        CampaignAgentErrorCodes.SKILL_FAILED, "ready",
                        "A ready Skill outcome requires its artifact and binding identity.");
            }
            if (!skillId.equals(artifact.skillId()) || !toolId.equals(artifact.toolId())) {
                throw CampaignAgentException.invalid(
                        CampaignAgentErrorCodes.SKILL_FAILED, "readyBinding",
                        "A ready Skill outcome must carry the artifact of its own Skill and Tool.");
            }
            if (audience != null && !skillId.equals(com.company.campaign.builder.assembly.CampaignSkillIds
                    .SEGMENT_RULE_BASED)) {
                throw CampaignAgentException.invalid(
                        CampaignAgentErrorCodes.SKILL_FAILED, "readyAudience",
                        "Only the Segment Skill may report an audience summary.");
            }
            if (capabilityContext != null && !skillId.equals(com.company.campaign.builder.assembly.CampaignSkillIds
                    .SEGMENT_RULE_BASED)) {
                throw CampaignAgentException.invalid(
                        CampaignAgentErrorCodes.SKILL_FAILED, "readyCapabilityContext",
                        "Only the Segment Skill may report a capability compile context.");
            }
        }

        /** A ready outcome that reports no audience card and no compile context. */
        public Ready(String skillId, String toolId, CampaignSkillArtifact artifact) {
            this(skillId, toolId, artifact, null, null);
        }

        /** A ready outcome that reports an audience card but no compile context. */
        public Ready(String skillId, String toolId, CampaignSkillArtifact artifact,
                     CampaignAudienceSummary audience) {
            this(skillId, toolId, artifact, audience, null);
        }
    }

    /** Multiple accepted components produced by one multi-instance Skill invocation. */
    record ReadyMany(
            String skillId,
            String toolId,
            List<CampaignSkillArtifact> artifacts) implements CampaignSkillOutcome {

        public ReadyMany {
            if (skillId == null || toolId == null || artifacts == null || artifacts.size() < 2) {
                throw CampaignAgentException.invalid(
                        CampaignAgentErrorCodes.SKILL_FAILED, "readyMany",
                        "A multi-component outcome requires at least two bound artifacts.");
            }
            artifacts = List.copyOf(new ArrayList<>(artifacts));
            java.util.HashSet<String> componentIds = new java.util.HashSet<>();
            for (CampaignSkillArtifact artifact : artifacts) {
                if (artifact == null || !skillId.equals(artifact.skillId()) || !toolId.equals(artifact.toolId())
                        || !componentIds.add(artifact.componentId())) {
                    throw CampaignAgentException.invalid(
                            CampaignAgentErrorCodes.SKILL_FAILED, "readyManyBinding",
                            "A multi-component outcome must carry distinct artifacts of its own Skill and Tool.");
                }
            }
        }
    }

    /**
     * One accepted atomic Journey bundle the T27 workspace may stage, plus its bounded summary.
     *
     * <p>A Journey is a bounded set of actual Flow components plus one topology, so its artifact is staged
     * through the workspace's own atomic bundle transaction rather than one component at a time. The summary
     * carries only counts, the entry identity and integrity hashes.
     */
    record ReadyJourney(
            String skillId,
            String toolId,
            CampaignJourneyArtifact artifact,
            CampaignJourneySummary summary) implements CampaignSkillOutcome {

        public ReadyJourney {
            if (skillId == null || toolId == null || artifact == null || summary == null) {
                throw CampaignAgentException.invalid(
                        CampaignAgentErrorCodes.SKILL_FAILED, "readyJourney",
                        "A ready Journey outcome requires its bundle, summary and binding identity.");
            }
            if (!skillId.equals(artifact.skillId()) || !toolId.equals(artifact.toolId())) {
                throw CampaignAgentException.invalid(
                        CampaignAgentErrorCodes.SKILL_FAILED, "readyJourneyBinding",
                        "A ready Journey outcome must carry the artifact of its own Skill and Tool.");
            }
        }
    }

    /** A genuine typed clarification that must be answered before any downstream Skill runs. */
    record NeedsInput(
            String skillId,
            String code,
            List<String> questionCodes,
            List<CampaignAnswerOption> options,
            boolean freeTextAllowed,
            CampaignSegmentContinuation continuation) implements CampaignSkillOutcome {

        public NeedsInput {
            if (skillId == null || code == null || !code.matches("^[A-Z][A-Z0-9_]{0,63}$")) {
                throw CampaignAgentException.invalid(
                        CampaignAgentErrorCodes.SKILL_FAILED, "needsInput",
                        "A clarification outcome requires its Skill and one bounded machine code.");
            }
            questionCodes = questionCodes == null ? List.of() : List.copyOf(new ArrayList<>(questionCodes));
            options = options == null ? List.of() : List.copyOf(new ArrayList<>(options));
            if (questionCodes.isEmpty()) {
                throw CampaignAgentException.invalid(
                        CampaignAgentErrorCodes.SKILL_FAILED, "clarificationQuestions",
                        "A clarification outcome must name at least one question code.");
            }
            if (continuation != null && !continuation.questionCode().equals(questionCodes.get(0))) {
                throw CampaignAgentException.invalid(
                        CampaignAgentErrorCodes.SKILL_FAILED, "clarificationContinuation",
                        "A Segment continuation must name the exact question the clarification asks.");
            }
        }

        /**
         * A clarification that carries no host-owned continuation evidence.
         *
         * <p>Every non-Segment Skill asks through this shape: only an accepted Segment clarification has a
         * deterministic resolution identity a later turn can replay.
         */
        public NeedsInput(
                String skillId,
                String code,
                List<String> questionCodes,
                List<CampaignAnswerOption> options,
                boolean freeTextAllowed) {
            this(skillId, code, questionCodes, options, freeTextAllowed, null);
        }
    }

    /** A Skill this build has no accepted implementation for. */
    record Unavailable(String skillId, String code) implements CampaignSkillOutcome {

        public Unavailable {
            if (skillId == null || code == null || !code.matches("^[A-Z][A-Z0-9_]{0,63}$")) {
                throw CampaignAgentException.invalid(
                        CampaignAgentErrorCodes.SKILL_FAILED, "unavailable",
                        "An unavailable outcome requires its Skill and one bounded machine code.");
            }
        }
    }

    /** A genuine non-resolved terminal of an implemented Skill. */
    record Terminal(String skillId, Disposition disposition, String code) implements CampaignSkillOutcome {

        public Terminal {
            if (skillId == null || disposition == null || disposition.isResolved() || code == null) {
                throw CampaignAgentException.invalid(
                        CampaignAgentErrorCodes.SKILL_FAILED, "terminal",
                        "A terminal Skill outcome requires one bounded non-resolved disposition.");
            }
        }
    }
}
