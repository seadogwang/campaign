package com.company.campaign.builder.capability.flow;

/**
 * The internal, bounded rejection of one Flow node payload.
 *
 * <p>It carries exactly one closed machine code and fixed prose. It never carries the rejected value, a field
 * name, a logical identifier or a cause, so a rejection can never become an observability leak. It exists only
 * inside this package: a pack turns it into one ordered, bounded {@code Issue}, and it never escapes
 * {@link FlowPackSupport#resolve}.
 */
final class FlowPackException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private static final String MESSAGE = "The flow node capability payload failed closed.";

    private final String code;

    FlowPackException(String code) {
        super(MESSAGE);
        this.code = code;
    }

    /** The stable machine-readable failure code of this rejection. */
    String code() {
        return code;
    }
}