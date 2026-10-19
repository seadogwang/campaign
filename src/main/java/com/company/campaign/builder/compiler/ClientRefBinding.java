package com.company.campaign.builder.compiler;

import java.util.Objects;

/**
 * One entry of the {@code clientRef -> logicalId} map the compiler owns.
 *
 * <p>The compiler — never the caller — turns a {@code clientRef} into a deterministic plan-scoped
 * {@code logicalId}. Recording the mapping in the Plan is what lets a later Apply return it to the client,
 * so an editor can address the object it just created without ever having chosen the identifier itself.
 */
public record ClientRefBinding(String clientRef, String logicalId) {

    public ClientRefBinding {
        Objects.requireNonNull(clientRef, "clientRef");
        Objects.requireNonNull(logicalId, "logicalId");
    }

    @Override
    public String toString() {
        return "ClientRefBinding[clientRef=" + clientRef + ", logicalId=" + logicalId + ']';
    }
}