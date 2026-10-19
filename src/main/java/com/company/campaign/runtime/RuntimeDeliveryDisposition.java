package com.company.campaign.runtime;

/**
 * The closed outward disposition of one accepted Runtime delivery.
 *
 * <p>It states what the consumer did, never what a queue holds: a redelivered work item is
 * {@link #DUPLICATE} and returns the original receipt and Execution unchanged, which is how idempotency is
 * observable from outside without exposing a queue handle.
 */
public enum RuntimeDeliveryDisposition {

    /** A schedule-registration event was recorded; it produces no Execution. */
    REGISTERED("REGISTERED"),

    /** A {@code SKIPPED} occurrence was recorded; it produces no Execution. */
    SKIPPED("SKIPPED"),

    /** A {@code DUE} occurrence created exactly one new Execution under its stable execution key. */
    EXECUTION_CREATED("EXECUTION_CREATED"),

    /** The work item was already accepted under the same source hash; nothing was written. */
    DUPLICATE("DUPLICATE");

    private final String code;

    RuntimeDeliveryDisposition(String code) {
        this.code = code;
    }

    /** The bounded machine code this disposition reports as. */
    public String code() {
        return code;
    }
}