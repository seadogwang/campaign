package com.company.campaign.builder.campaignagent;

import com.company.campaign.builder.agent.SegmentAgentErrorCodes;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The host-owned free-text Segment recovery state for initial model-output rejection.
 *
 * <p>When the Segment component's first real-model output is rejected with
 * {@code SEGMENT_AGENT_MODEL_OUTPUT_REJECTED} (semantic/schema invalidity, not provider/transport failure),
 * the host creates this recovery state instead of a {@link CampaignSegmentContinuation}, because there is no
 * genuine resolution identity, no bounded option set, and no receipt to replay. It carries its own bounded
 * {@code recoveryAttempts} counter, a diagnostic code, and a stable generic question code, so the user can
 * restate the affected audience condition in free text.
 *
 * <p>It is deliberately a value, not a store. It is copied and rolled back with the
 * {@link CampaignAgentSession} it belongs to, so a failed turn, an evicted conversation or an idempotent
 * replay can never desynchronize a session from its recovery state. It serializes through
 * {@code CampaignConversationSnapshotCodec} and survives backend restart.
 *
 * <p>It never carries a capabilty payload, a Tool argument, a member identifier, a template body, a reward
 * secret, a model output fragment, an option identity, a {@link com.company.campaign.segment.resolution.ResolutionId},
 * or an exception message.
 */
public record CampaignSegmentFreeTextRecovery(
        String diagnosticCode,
        String questionCode,
        int recoveryAttempts
) {

    /** The bounded recovery attempt counter, persisted across turns. */
    public static final int MAX_RECOVERY_ATTEMPTS = 3;

    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");
    private static final Pattern QUESTION_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    public CampaignSegmentFreeTextRecovery {
        if (diagnosticCode == null || !CODE_PATTERN.matcher(diagnosticCode).matches()) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.SEGMENT_SOURCE_REQUIRED, "segmentFreeTextRecoveryCode",
                    "A Segment free-text recovery requires one bounded diagnostic code.");
        }
        if (questionCode == null || !QUESTION_PATTERN.matcher(questionCode).matches()) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.SEGMENT_SOURCE_REQUIRED, "segmentFreeTextRecoveryQuestion",
                    "A Segment free-text recovery requires one bounded stable question code.");
        }
        if (recoveryAttempts < 0 || recoveryAttempts > MAX_RECOVERY_ATTEMPTS) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.SEGMENT_SOURCE_REQUIRED, "segmentFreeTextRecoveryAttempts",
                    "A Segment free-text recovery requires a bounded recovery attempt count.");
        }
    }

    /** The initial recovery state, carrying the diagnostic code and the standard rephrase question. */
    public static CampaignSegmentFreeTextRecovery initial(String diagnosticCode) {
        return new CampaignSegmentFreeTextRecovery(diagnosticCode, "SEGMENT_AUDIENCE_REPHRASE_REQUIRED", 1);
    }

    /**
     * A pending free-text recovery state for a non-definition Segment NEEDS_INPUT question
     * that has no bounded option set and no &#64;{@link com.company.campaign.segment.resolution.ResolutionId}.
     *
     * <p>The pending state records the genuine question code at attempt zero — it has not yet been
     * retried. On a later turn, when the model returns {@code RESOLUTION_REJECTED} or
     * {@code MODEL_OUTPUT_REJECTED}, the host increments the counter and re-presents the original
     * question with free text. This avoids fabricating an option identity or receipt for a question
     * that has none, while still allowing a bounded recovery budget.
     */
    public static CampaignSegmentFreeTextRecovery pending(String diagnosticCode, String questionCode) {
        return new CampaignSegmentFreeTextRecovery(diagnosticCode, questionCode, 0);
    }

    /**
     * Increments the recovery attempt counter by exactly one.
     *
     * @return a new recovery state with the recovery counter incremented by one
     * @throws CampaignAgentException when the counter would exceed the budget
     */
    public CampaignSegmentFreeTextRecovery withIncrementedRecovery() {
        return new CampaignSegmentFreeTextRecovery(diagnosticCode, questionCode, recoveryAttempts + 1);
    }

    /** True when the recovery budget is fully consumed. */
    public boolean exhausted() {
        return recoveryAttempts >= MAX_RECOVERY_ATTEMPTS;
    }

    /**
     * True when the diagnostic code is an explicitly recoverable model-output-failure code.
     *
     * <p>Only {@link SegmentAgentErrorCodes#MODEL_OUTPUT_REJECTED} is recoverable in the initial
     * (non-replay) context. Provider failures, transport errors, authorization errors, persistence
     * errors and contract corruption codes remain non-recoverable.
     */
    public static boolean isRecoverableModelFailure(String code) {
        return SegmentAgentErrorCodes.MODEL_OUTPUT_REJECTED.equals(code);
    }

    /** A bounded diagnostic: the question code, the diagnostic code and the attempt count. */
    @Override
    public String toString() {
        return "CampaignSegmentFreeTextRecovery[question=" + questionCode
                + ", diagnostic=" + diagnosticCode
                + ", attempts=" + recoveryAttempts + ']';
    }
}