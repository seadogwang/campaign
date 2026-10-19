package com.company.campaign.localhealth;

import java.util.Map;

/**
 * The closed error vocabulary of the local health endpoint.
 *
 * <p>It has exactly one code, because the endpoint is a read-only report of already-computed, bounded state:
 * the only meaningful failure is that the report itself could not be produced. A code carries a fixed message
 * and closed detail names, so no exception text, class name, path or configuration value can reach a client.
 */
public final class LocalHealthErrorCodes {

    /** The bounded report could not be produced. */
    public static final String LOCAL_HEALTH_UNAVAILABLE = "LOCAL_HEALTH_UNAVAILABLE";

    private static final Map<String, String> MESSAGES = Map.of(
            LOCAL_HEALTH_UNAVAILABLE, "The local health report is unavailable.");

    private LocalHealthErrorCodes() {
    }

    /** The fixed message of one closed code. */
    public static String message(String code) {
        return MESSAGES.getOrDefault(code, MESSAGES.get(LOCAL_HEALTH_UNAVAILABLE));
    }
}