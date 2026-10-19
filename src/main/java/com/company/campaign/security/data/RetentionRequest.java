package com.company.campaign.security.data;

import java.util.Objects;

/**
 * A request to retain a payload of a given classification from a given channel.
 *
 * <p>{@link #rawPayload()} distinguishes a request to store the original value from one to store an
 * already-redacted projection; the default policy refuses the former for {@code RESTRICTED} data.
 */
public record RetentionRequest(
        DataClassification classification,
        ProjectionChannel channel,
        boolean rawPayload
) {
    public RetentionRequest {
        Objects.requireNonNull(classification, "classification");
        Objects.requireNonNull(channel, "channel");
    }
}