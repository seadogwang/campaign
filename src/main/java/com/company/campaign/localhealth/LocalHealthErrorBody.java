package com.company.campaign.localhealth;

import java.util.Map;

/**
 * The fixed, bounded error body of a local health error envelope.
 *
 * <p>Both fields come from the closed {@link LocalHealthErrorCodes} vocabulary and {@code details} is always
 * empty, so an error response can never carry an exception message, a cause, a path, a host or a setting.
 */
public record LocalHealthErrorBody(String code, String message, Map<String, Object> details) {

    public LocalHealthErrorBody {
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    /** Builds the bounded error body for the one closed code. */
    public static LocalHealthErrorBody of(String code) {
        return new LocalHealthErrorBody(code, LocalHealthErrorCodes.message(code), Map.of());
    }
}