package com.company.campaign.contracts;

import com.company.campaign.shared.error.ContractErrorCodes;
import com.company.campaign.shared.error.ContractException;

import java.util.Map;

/** Raised when model-supplied Tool input carries a field reserved for trusted context or host-only commands. */
public final class ToolInputRejectedException extends ContractException {

    private static final long serialVersionUID = 1L;

    public ToolInputRejectedException(String fieldName, String path) {
        super(
                ContractErrorCodes.TOOL_INPUT_RESERVED_FIELD,
                "Tool input field '" + fieldName + "' is reserved for trusted host context or host-only commands",
                Map.of("field", String.valueOf(fieldName), "path", String.valueOf(path)));
    }
}