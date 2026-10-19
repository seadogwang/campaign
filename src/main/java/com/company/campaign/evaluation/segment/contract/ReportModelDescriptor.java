package com.company.campaign.evaluation.segment.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.regex.Pattern;

/**
 * The exact model that produced an evaluation run, frozen for reproducibility.
 *
 * <p>Segment specification section 24 requires each run to report model, provider and version. A version
 * that only says "latest" or is omitted entirely makes two runs incomparable, which is why all three
 * values are mandatory here — a deterministic, model-free run names its rule-based engine explicitly
 * instead of leaving the field blank.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ReportModelDescriptor(
        String provider,
        String model,
        String modelVersion
) {
    /** Provider, model and version tokens: letters, digits, dot, dash, underscore, colon and slash. */
    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$");

    public ReportModelDescriptor {
        requireToken("provider", provider);
        requireToken("model", model);
        requireToken("modelVersion", modelVersion);
    }

    private static void requireToken(String field, String value) {
        if (value == null || !TOKEN.matcher(value).matches()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.ID_INVALID,
                    "Report model field '" + field + "' value '" + value + "' is outside its bounded grammar");
        }
    }
}