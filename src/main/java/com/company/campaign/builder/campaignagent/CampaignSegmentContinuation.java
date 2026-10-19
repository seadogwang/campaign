package com.company.campaign.builder.campaignagent;

import com.company.campaign.segment.resolution.ClarificationSelectionReceipt;
import com.company.campaign.segment.resolution.ResolutionId;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The exact continuation evidence of a pending Segment clarification, held by the Campaign session.
 *
 * <p>A genuine T10 clarification can only be answered by replaying the <em>same</em> deterministic resolution:
 * the accepted resolver keys a choice on the resolution identifier, the base Intent hash, the pins, the
 * question and the clause, and the Intent is derived from the original utterance. The evidence therefore
 * carries the original utterance, the ordered receipts already replayed, the genuine opaque resolution identity
 * and selection target of the question still pending, its stable question code and the offered option
 * identifiers. It never carries a Metadata definition identifier, a capability payload or a Tool argument.
 *
 * <p>It is deliberately a value, not a store. It is copied and rolled back with the {@link CampaignAgentSession}
 * it belongs to, so a failed turn, an evicted conversation or an idempotent replay can never desynchronize a
 * session from its continuation. Nothing here is a mutable map, a static registry or a provider-side pending
 * conversation cache.
 *
 * <p>{@link #answered(String)} is the one way a receipt comes into existence, and it refuses any option this
 * exact clarification did not offer, so a client can never name a resolution, a target or an option that was
 * not genuinely offered. This build's Segment resolver applies selection answers only, so
 * {@link #freeTextAllowed()} is always {@code false} and is reported honestly rather than promising a
 * free-text continuation the accepted resolver cannot apply.
 */
public record CampaignSegmentContinuation(
        String utterance,
        List<ClarificationSelectionReceipt> receipts,
        ResolutionId resolutionId,
        String selectionTarget,
        String questionCode,
        List<CampaignAnswerOption> options,
        int recoveryAttempts
) {

    /** The largest accepted original-utterance length; the Campaign turn's own absolute bound. */
    public static final int MAX_UTTERANCE_LENGTH = CampaignAgentLimits.MAX_UTTERANCE_LENGTH;

    /** The largest accepted selection-target length. */
    public static final int MAX_TARGET_LENGTH = 256;

    /** The largest accepted replayed-receipt chain; the Segment Agent's own absolute bound. */
    public static final int MAX_RECEIPTS = 16;

    /**
     * The bounded recovery attempt counter, persisted across turns.
     *
     * <p>It is incremented by exactly one per recovery retry; it is independent of the receipt chain
     * (which counts clarification answers, not recovery attempts). This is the authoritative budget
     * counter the Segment runner reads to decide whether a retry is still within budget.
     */
    public static final int MAX_RECOVERY_ATTEMPTS = 3;

    private static final Pattern QUESTION_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    public CampaignSegmentContinuation {
        if (utterance == null || utterance.isBlank() || utterance.length() > MAX_UTTERANCE_LENGTH) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.SEGMENT_SOURCE_REQUIRED, "continuationUtterance",
                    "A Segment continuation requires the bounded original utterance it replays.");
        }
        receipts = receipts == null ? List.of() : List.copyOf(new ArrayList<>(receipts));
        if (receipts.size() > MAX_RECEIPTS) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.SEGMENT_SOURCE_REQUIRED, "continuationReceipts",
                    "A Segment continuation exceeds its bounded receipt chain.");
        }
        Objects.requireNonNull(resolutionId, "resolutionId");
        if (selectionTarget == null || selectionTarget.isBlank()
                || selectionTarget.length() > MAX_TARGET_LENGTH) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.SEGMENT_SOURCE_REQUIRED, "continuationTarget",
                    "A Segment continuation requires one bounded selection target.");
        }
        if (questionCode == null || !QUESTION_CODE.matcher(questionCode).matches()) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.SEGMENT_SOURCE_REQUIRED, "continuationQuestion",
                    "A Segment continuation requires one bounded stable question code.");
        }
        options = options == null ? List.of() : List.copyOf(new ArrayList<>(options));
        if (options.isEmpty() || options.size() > CampaignAgentLimits.MAX_OPTIONS) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.SEGMENT_SOURCE_REQUIRED, "continuationOptions",
                    "A Segment continuation requires a bounded non-empty option set.");
        }
        if (recoveryAttempts < 0 || recoveryAttempts > MAX_RECOVERY_ATTEMPTS) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.SEGMENT_SOURCE_REQUIRED, "continuationRecoveryAttempts",
                    "A Segment continuation requires a bounded recovery attempt count.");
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (CampaignAnswerOption option : options) {
            if (option == null || !distinct.add(option.optionId())) {
                throw CampaignAgentException.invalid(
                        CampaignAgentErrorCodes.SEGMENT_SOURCE_REQUIRED, "continuationOptions",
                        "A Segment continuation requires distinct bounded options.");
            }
        }
    }

    /** The pending evidence of one offered Segment clarification, which has replayed no receipt yet. */
    public static CampaignSegmentContinuation pending(
            String utterance,
            ResolutionId resolutionId,
            String selectionTarget,
            String questionCode,
            List<CampaignAnswerOption> options) {
        return pending(utterance, List.of(), resolutionId, selectionTarget, questionCode, options, 0);
    }

    /**
     * The pending evidence of one offered Segment clarification that follows an already replayed chain.
     *
     * <p>A multi-clause or audience-reference Intent can ask several questions in turn, so every new question
     * carries the exact receipts of the answers already applied: replaying only the newest receipt would derive
     * a different resolution and be refused as stale.
     */
    public static CampaignSegmentContinuation pending(
            String utterance,
            List<ClarificationSelectionReceipt> receipts,
            ResolutionId resolutionId,
            String selectionTarget,
            String questionCode,
            List<CampaignAnswerOption> options) {
        return pending(utterance, receipts, resolutionId, selectionTarget, questionCode, options, 0);
    }

    private static CampaignSegmentContinuation pending(
            String utterance,
            List<ClarificationSelectionReceipt> receipts,
            ResolutionId resolutionId,
            String selectionTarget,
            String questionCode,
            List<CampaignAnswerOption> options,
            int recoveryAttempts) {
        return new CampaignSegmentContinuation(
                utterance, receipts, resolutionId, selectionTarget, questionCode, options, recoveryAttempts);
    }

    /**
     * The answered form of this exact clarification, carrying the exact replayable receipt chain.
     *
     * @throws CampaignAgentException when the option was not one this exact clarification offered
     */
    public CampaignSegmentContinuation answered(String optionId) {
        for (CampaignAnswerOption option : options) {
            if (option.optionId().equals(optionId)) {
                List<ClarificationSelectionReceipt> chain = new ArrayList<>(receipts.size() + 1);
                chain.addAll(receipts);
                chain.add(new ClarificationSelectionReceipt(resolutionId, selectionTarget, optionId));
                return new CampaignSegmentContinuation(
                        utterance, chain, resolutionId, selectionTarget, questionCode, options,
                        recoveryAttempts);
            }
        }
        throw CampaignAgentException.of(
                CampaignAgentErrorCodes.CLARIFICATION_MISMATCH,
                "A clarification answer names an option that was not offered.");
    }

    /**
     * Increments the recovery attempt counter by exactly one.
     *
     * <p>This is called by the Segment runner when a replay produces a recoverable resolution-rejection
     * and the retry budget has not been exhausted. The counter is persisted across turns; it is
     * independent of the receipt chain, which counts clarification answers, not recovery attempts.
     *
     * @return a new continuation with the recovery counter incremented by one
     */
    public CampaignSegmentContinuation withIncrementedRecovery() {
        return new CampaignSegmentContinuation(
                utterance, receipts, resolutionId, selectionTarget, questionCode, options,
                recoveryAttempts + 1);
    }

    /** True when this continuation carries at least one exact receipt the next Segment request replays. */
    public boolean isAnswered() {
        return !receipts.isEmpty();
    }

    /** This build's Segment resolver accepts selection answers only, so free text is never offered. */
    public boolean freeTextAllowed() {
        return false;
    }

    /** A bounded diagnostic: the question code, the option count and the receipt count, never the utterance. */
    @Override
    public String toString() {
        return "CampaignSegmentContinuation[question=" + questionCode
                + ", options=" + options.size()
                + ", receipts=" + receipts.size() + ']';
    }
}