package com.company.campaign.builder.tool.reference;

import com.company.campaign.shared.error.ContractException;

import java.util.Map;

/**
 * One stable, non-disclosing failure of the read-only Existing Audience Reference Tool surface.
 *
 * <p>The message and details never contain a rejected Tool identifier, a candidate text, an audience label, a
 * version pin, a member identifier or an implementation exception message.
 */
public final class ReferenceToolException extends ContractException {

    private static final long serialVersionUID = 1L;

    public ReferenceToolException(String code, String message) {
        super(code, message, Map.of());
    }

    /** Builds one bounded reference Tool rejection. */
    public static ReferenceToolException invalid(String code, String message) {
        return new ReferenceToolException(code, message);
    }
}