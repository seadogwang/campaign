package com.company.campaign.contracts;

import com.company.campaign.shared.error.ContractErrorCodes;
import com.company.campaign.shared.error.ContractException;

import java.util.Map;

/** Raised when a locale is not a well-formed BCP-47 language tag. */
public final class LocaleTagException extends ContractException {

    private static final long serialVersionUID = 1L;

    public LocaleTagException(String rejectedValue) {
        super(
                ContractErrorCodes.CONTRACT_INVALID_VALUE,
                "Locale must be a BCP-47 language tag such as zh-CN",
                Map.of("value", rejectedValue == null ? "" : rejectedValue));
    }
}