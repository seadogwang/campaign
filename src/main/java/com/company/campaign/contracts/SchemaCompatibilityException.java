package com.company.campaign.contracts;

import com.company.campaign.shared.error.ContractErrorCodes;
import com.company.campaign.shared.error.ContractException;

import java.util.Map;
import java.util.Objects;

/**
 * Raised when a contract carries a schema version other than the exact expected one.
 *
 * <p>The failure reports the {@link #expected() expected} and {@link #actual() actual} versions both as
 * accessors and in {@link #details()} so an API error envelope can surface them without parsing the
 * message.
 */
public final class SchemaCompatibilityException extends ContractException {

    private static final long serialVersionUID = 1L;

    private final SchemaVersion expected;
    private final SchemaVersion actual;

    public SchemaCompatibilityException(SchemaVersion expected, SchemaVersion actual) {
        super(
                ContractErrorCodes.SCHEMA_VERSION_INCOMPATIBLE,
                "Expected schema version " + expected + " but received " + actual,
                Map.of("expected", String.valueOf(expected), "actual", String.valueOf(actual)));
        this.expected = Objects.requireNonNull(expected, "expected");
        this.actual = Objects.requireNonNull(actual, "actual");
    }

    public SchemaVersion expected() {
        return expected;
    }

    public SchemaVersion actual() {
        return actual;
    }
}