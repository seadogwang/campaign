package com.company.campaign.evaluation.segment.runner;

/**
 * The closed set of stable T13 run-contract and execution failure codes.
 *
 * <p>Every code is a bounded machine code: none of them carries an utterance, a provider message, a stack
 * trace, a trusted identifier, a member identifier, a SQL fragment or a Metadata label.
 */
public final class EvaluationRunErrorCodes {

    /** A required frozen pin is absent. */
    public static final String PIN_MISSING = "EVALUATION_RUN_PIN_MISSING";

    /** A frozen pin is stale: it no longer matches the asset it names. */
    public static final String PIN_STALE = "EVALUATION_RUN_PIN_STALE";

    /** Two frozen pins contradict each other. */
    public static final String PIN_CONTRADICTORY = "EVALUATION_RUN_PIN_CONTRADICTORY";

    /** A pin changed between the freeze and the observation it must cover. */
    public static final String PIN_CHANGED = "EVALUATION_RUN_PIN_CHANGED";

    /** The run or report identifier is malformed. */
    public static final String RUN_IDENTIFIER_INVALID = "EVALUATION_RUN_IDENTIFIER_INVALID";

    /** The decoding parameters are missing, out of range or not a closed typed contract. */
    public static final String DECODING_INVALID = "EVALUATION_RUN_DECODING_INVALID";

    /** The corpus classification is not usable for a run at all. */
    public static final String CLASSIFICATION_INVALID = "EVALUATION_RUN_CLASSIFICATION_INVALID";

    /** A report cannot be relabelled after its review state changed; a new run is required. */
    public static final String REPORT_RELABEL_REFUSED = "EVALUATION_RUN_RELABEL_REFUSED";

    /** The requested case is not present in the frozen corpus manifest. */
    public static final String CASE_UNKNOWN = "EVALUATION_RUN_CASE_UNKNOWN";

    /** An attempt produced a comparison that could not be evaluated. */
    public static final String COMPARISON_INVALID = "EVALUATION_RUN_COMPARISON_INVALID";

    /** A metric numerator or denominator violated its own declared rule. */
    public static final String METRIC_INVALID = "EVALUATION_RUN_METRIC_INVALID";

    /** A provider or fixture infrastructure failure made the run unsound. */
    public static final String INFRASTRUCTURE_FAILURE = "EVALUATION_RUN_INFRASTRUCTURE_FAILURE";

    /** The injected observation boundary failed, so the run was failed closed. */
    public static final String OBSERVATION_FAILURE = "EVALUATION_RUN_OBSERVATION_FAILURE";

    /** A required host service was absent. */
    public static final String HOST_SERVICE_MISSING = "EVALUATION_RUN_HOST_SERVICE_MISSING";

    private EvaluationRunErrorCodes() {
    }
}