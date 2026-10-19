package com.company.campaign.contracts;

/**
 * Severity of a machine-readable {@link Issue}.
 *
 * <p>{@link #ERROR} blocks planning, preview and apply. {@link #WARNING} is surfaced to the user but does
 * not block. {@link #INFO} is explanatory only.
 */
public enum IssueSeverity {

    ERROR,
    WARNING,
    INFO;

    /** True when an issue with this severity must block downstream deterministic processing. */
    public boolean blocksProcessing() {
        return this == ERROR;
    }
}