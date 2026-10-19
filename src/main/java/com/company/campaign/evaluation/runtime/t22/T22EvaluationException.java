package com.company.campaign.evaluation.runtime.t22;

/** The bounded, machine-coded failure of one T22 Runtime evaluation asset load. */
public final class T22EvaluationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient String code;

    public T22EvaluationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public T22EvaluationException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** The stable bounded machine code of this failure. */
    public String code() {
        return code;
    }

    @Override
    public String toString() {
        return "T22EvaluationException[code=" + code + ']';
    }
}