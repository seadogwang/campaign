package com.company.campaign.preview.api;

import com.company.campaign.preview.PreviewErrorCodes;

import java.util.Map;

/**
 * The fixed, bounded error body of a Preview error envelope.
 *
 * <p>{@code code} and {@code message} come from the closed {@link PreviewErrorCodes} vocabulary and
 * {@code details} carries only closed invariant names. No exception message, cause, identifier, operand, SQL
 * fragment, bind parameter, member identifier or fixture fact is ever copied here.
 */
public record PreviewErrorBody(String code, String message, Map<String, Object> details) {

    public PreviewErrorBody {
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    /** Builds the bounded error body for one closed code. */
    public static PreviewErrorBody of(String code, Map<String, Object> details) {
        return new PreviewErrorBody(code, PreviewErrorCodes.message(code), details);
    }
}