package com.company.campaign.evaluation.journey.t20;

public final class T20EvaluationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public T20EvaluationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public T20EvaluationException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
