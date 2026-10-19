package com.company.campaign.security.data;

import com.company.campaign.shared.error.ContractErrorCodes;

import java.time.Duration;

/**
 * Default retention policy: refuse raw {@code RESTRICTED} payloads, never store a raw model prompt, and give
 * a deterministic period for everything else.
 *
 * <p>Decisions depend only on the classification, channel and raw flag, so the same request always produces
 * the same decision. The periods are policy defaults, not derived from the system clock.
 */
public final class DefaultRetentionPolicy implements RetentionPolicy {

    /** Reason recorded when a raw RESTRICTED payload was requested. */
    public static final String REFUSED_UNSAFE = ContractErrorCodes.UNSAFE_RETENTION_REQUEST;

    /** Reason recorded when the request itself is invalid. */
    public static final String REFUSED_INVALID = ContractErrorCodes.RETENTION_INVALID_REQUEST;

    /** Reason recorded when raw data may be stored. */
    public static final String ALLOWED_RAW = "RETENTION_ALLOWED_RAW";

    /** Reason recorded when only a redacted projection may be stored. */
    public static final String ALLOWED_REDACTED = "RETENTION_ALLOWED_REDACTED";

    @Override
    public RetentionDecision decide(RetentionRequest request) {
        if (request == null || request.classification() == null || request.channel() == null) {
            return RetentionDecision.refused(REFUSED_INVALID);
        }

        DataClassification classification = request.classification();
        if (classification.isRestricted() && request.rawPayload()) {
            return RetentionDecision.refused(REFUSED_UNSAFE);
        }

        boolean storeRaw = request.rawPayload()
                && (classification == DataClassification.PUBLIC || classification == DataClassification.INTERNAL)
                && request.channel() != ProjectionChannel.MODEL_PROMPT;

        String reason = storeRaw ? ALLOWED_RAW : ALLOWED_REDACTED;
        return RetentionDecision.allowed(storeRaw, reason, retentionPeriod(classification));
    }

    /** Fixed default retention period for a classification. */
    public static Duration retentionPeriod(DataClassification classification) {
        return switch (classification) {
            case PUBLIC -> Duration.ofDays(365);
            case INTERNAL -> Duration.ofDays(180);
            case CONFIDENTIAL -> Duration.ofDays(30);
            case RESTRICTED -> Duration.ofDays(7);
        };
    }
}