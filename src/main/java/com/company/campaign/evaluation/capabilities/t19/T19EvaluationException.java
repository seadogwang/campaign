package com.company.campaign.evaluation.capabilities.t19;

import com.company.campaign.shared.ImmutableValues;

import java.util.Map;

/**
 * One deterministic, bounded failure of the T19 capability evaluation-asset loader.
 *
 * <p>The exception carries a stable {@link #code()} callers branch on and a defensive, frozen detail map.
 * The message is user-safe prose and never the machine interface; no detail value ever carries a payload
 * body, a template text, a reward secret, a credential, a member identifier or a provider response.
 */
public final class T19EvaluationException extends RuntimeException {

    private final String code;
    private final Map<String, Object> details;

    public T19EvaluationException(String code, String message) {
        this(code, message, Map.of(), null);
    }

    public T19EvaluationException(String code, String message, Map<String, Object> details) {
        this(code, message, details, null);
    }

    public T19EvaluationException(
            String code, String message, Map<String, Object> details, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.details = ImmutableValues.freezeMap(details);
    }

    public String code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }
}