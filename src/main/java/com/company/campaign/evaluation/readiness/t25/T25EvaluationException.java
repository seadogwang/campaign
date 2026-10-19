package com.company.campaign.evaluation.readiness.t25;

/**
 * Every T25 readiness evaluation asset failure, carrying a bounded machine code.
 *
 * <p>It carries no asset payload and no evidence value: a rejected asset is described by its code and, at
 * most, the bounded field name that failed.
 */
public final class T25EvaluationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public T25EvaluationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public T25EvaluationException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** Stable machine code, one of {@link T25EvaluationErrorCodes}. */
    public String code() {
        return code;
    }
}