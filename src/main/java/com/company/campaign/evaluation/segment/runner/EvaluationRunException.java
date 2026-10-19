package com.company.campaign.evaluation.segment.runner;

import com.company.campaign.shared.error.ContractException;

import java.util.Map;

/**
 * A frozen-run failure detected while freezing, executing or reporting one T13 evaluation run.
 *
 * <p>The code is always one of {@link EvaluationRunErrorCodes}, and it is the only machine interface: the
 * message is human-readable and must never be parsed. No instance carries an utterance, a provider message, a
 * stack trace, a trusted identifier, a member identifier or a Metadata label.
 */
public final class EvaluationRunException extends ContractException {

    private static final long serialVersionUID = 1L;

    public EvaluationRunException(String code, String message) {
        this(code, message, Map.of());
    }

    public EvaluationRunException(String code, String message, Map<String, Object> details) {
        super(code, message, details);
    }

    /** Builds a bounded run failure with the given stable code. */
    public static EvaluationRunException of(String code, String message) {
        return new EvaluationRunException(code, message);
    }
}