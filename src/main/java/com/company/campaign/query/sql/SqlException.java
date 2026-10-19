package com.company.campaign.query.sql;

import com.company.campaign.shared.error.ContractException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Raised when the T09B storage catalog, closed SQL AST, compiler or artifact boundary rejects an input.
 *
 * <p>The message is fixed prose and the only structured detail is a closed {@code invariant} name, so a
 * failure never echoes a catalog identifier, a table/schema/column name, a tenant/program/scope value, an
 * operand, a parameter value, a rendered SQL fragment or a thrown cause. Callers branch on {@link #code()},
 * one of {@link SqlErrorCodes}.
 */
public final class SqlException extends ContractException {

    private static final long serialVersionUID = 1L;

    /** Builds the stable failure for one named invariant. */
    public SqlException(String code, String invariant, String message) {
        super(code, message, details(invariant));
    }

    private static Map<String, Object> details(String invariant) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("invariant", invariant);
        return details;
    }
}