package com.company.campaign.evaluation.segment.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.time.ZoneId;

/**
 * The time context a run was evaluated in: {@code asOf}, timezone and data watermark.
 *
 * <p>These are inputs, not decoration. Windows are computed from {@code asOf} in the stated timezone and
 * bounded by the watermark, so a report that omits any of them cannot be replayed even with the same
 * corpus. The watermark-after-{@code asOf} contradiction is rejected here for the same reason the trusted
 * execution context rejects it: it would describe data observed after the instant being evaluated.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ReportTimeContext(
        Instant asOf,
        ZoneId timezone,
        Instant dataWatermark
) {
    public ReportTimeContext {
        if (asOf == null || timezone == null || dataWatermark == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.FIXTURE_TIME_CONTEXT_INVALID,
                    "Report time context must declare asOf, timezone and dataWatermark");
        }
        if (dataWatermark.isAfter(asOf)) {
            throw new EvaluationException(
                    EvaluationErrorCodes.FIXTURE_TIME_CONTEXT_INVALID,
                    "Report dataWatermark " + dataWatermark + " must not be after asOf " + asOf);
        }
    }
}