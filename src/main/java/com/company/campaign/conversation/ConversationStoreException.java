package com.company.campaign.conversation;

import com.company.campaign.shared.error.ContractException;

import java.util.Map;

/**
 * A bounded failure of the local Campaign conversation registry.
 *
 * <p>It carries exactly one stable machine code and one bounded invariant name. It never echoes a conversation
 * identifier, a session revision, an idempotency key or a payload.
 */
public final class ConversationStoreException extends ContractException {

    private static final long serialVersionUID = 1L;

    private final String invariant;

    public ConversationStoreException(String code, String invariant, String message) {
        super(code, message, invariant == null ? Map.of() : Map.of("invariant", invariant));
        this.invariant = invariant;
    }

    /** The bounded invariant name, or null when the failure has no single invariant. */
    public String invariant() {
        return invariant;
    }

    /** A bounded failure carrying one stable code, its invariant and a value-free message. */
    public static ConversationStoreException invalid(String code, String invariant, String message) {
        return new ConversationStoreException(code, invariant, message);
    }
}