package com.company.campaign.contracts;

import com.company.campaign.shared.error.ContractErrorCodes;
import com.company.campaign.shared.error.ContractException;

import java.util.Map;

/** Raised when a schema version string is not a well-formed {@code major.minor} value. */
public final class SchemaVersionException extends ContractException {

    private static final long serialVersionUID = 1L;

    public SchemaVersionException(String rejectedValue) {
        super(
                ContractErrorCodes.SCHEMA_VERSION_MALFORMED,
                "Schema version must use the major.minor form, for example 1.3",
                Map.of("value", rejectedValue == null ? "" : rejectedValue));
    }
}