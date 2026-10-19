package com.company.campaign.preview;

import java.time.Duration;

/**
 * Immutable, bounded server-owned policy for one Preview call.
 *
 * <p>Every value is positive and bounded by {@link PreviewLimits}. The policy bounds the request body, the
 * number of condition statistics, the sample size, the exact matched count and how old the trusted data
 * watermark may be. Presentation flags supplied by a client can only reduce the output within these bounds;
 * they can never raise a limit, because no client input reaches this type.
 *
 * <p>A value outside its absolute bound is rejected at construction with the stable
 * {@link PreviewErrorCodes#DEPENDENCY_MISSING} code rather than clamped.
 *
 * <p>There is deliberately no static factory, default or implicit instance: the only way to obtain a policy is
 * the explicit bounded canonical constructor, so no insecure or permissive server-owned default can exist.
 *
 * @param maxRequestBytes  maximum accepted HTTP request body size in bytes
 * @param maxConditionStats maximum condition statistics a response may carry
 * @param maxSampleSize    maximum sample hashes a response may carry
 * @param maxMatchedCount  maximum exact matched count a response may report
 * @param maxDataAge       maximum accepted {@code asOf - dataWatermark} before the data is stale
 */
public record PreviewPolicy(
        int maxRequestBytes,
        int maxConditionStats,
        int maxSampleSize,
        int maxMatchedCount,
        Duration maxDataAge
) {

    public PreviewPolicy {
        requireBounded(maxRequestBytes, "maxRequestBytes", PreviewLimits.MAX_REQUEST_BYTES);
        requireBounded(maxConditionStats, "maxConditionStats", PreviewLimits.MAX_CONDITION_STATS);
        requireBounded(maxSampleSize, "maxSampleSize", PreviewLimits.MAX_SAMPLE_SIZE);
        requireBounded(maxMatchedCount, "maxMatchedCount", PreviewLimits.MAX_MATCHED_COUNT);
        if (maxDataAge == null || maxDataAge.isNegative() || maxDataAge.isZero()
                || maxDataAge.getSeconds() > PreviewLimits.MAX_DATA_AGE_SECONDS) {
            throw PreviewException.of(PreviewErrorCodes.DEPENDENCY_MISSING, "maxDataAge");
        }
    }

    private static void requireBounded(int value, String field, int maximum) {
        if (value < 1 || value > maximum) {
            // The rejected value is a configuration number, not a payload value, and is safe to report.
            throw PreviewException.of(PreviewErrorCodes.DEPENDENCY_MISSING, field);
        }
    }
}