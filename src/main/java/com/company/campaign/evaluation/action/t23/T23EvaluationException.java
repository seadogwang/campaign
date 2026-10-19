package com.company.campaign.evaluation.action.t23;

/** The bounded, machine-coded failure of one T23 external-action evaluation asset load. */
public final class T23EvaluationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient String code;

    public T23EvaluationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public T23EvaluationException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** The stable bounded machine code of this failure. */
    public String code() {
        return code;
    }

    @Override
    public String toString() {
        return "T23EvaluationException[code=" + code + ']';
    }
}