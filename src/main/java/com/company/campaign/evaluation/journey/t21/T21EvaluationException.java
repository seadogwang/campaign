package com.company.campaign.evaluation.journey.t21;

/** The bounded, machine-coded failure of one T21 evaluation asset load. */
public final class T21EvaluationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient String code;

    public T21EvaluationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public T21EvaluationException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** The stable bounded machine code of this failure. */
    public String code() {
        return code;
    }

    @Override
    public String toString() {
        return "T21EvaluationException[code=" + code + ']';
    }
}