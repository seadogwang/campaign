package com.company.campaign.security.data;

import com.company.campaign.shared.error.ContractErrorCodes;
import com.company.campaign.shared.error.ContractException;

import java.util.Map;

/**
 * Raised when a caller requires a field that projection withheld.
 *
 * <p>Only the field name is exposed, never its value, so a denied fail-closed path cannot become the place
 * a restricted value leaks.
 */
public final class ProjectionDeniedException extends ContractException {

    private static final long serialVersionUID = 1L;

    public ProjectionDeniedException(String fieldName) {
        super(
                ContractErrorCodes.PROJECTION_FIELD_DENIED,
                "Field is not permitted in this projection",
                Map.of("field", String.valueOf(fieldName)));
    }
}