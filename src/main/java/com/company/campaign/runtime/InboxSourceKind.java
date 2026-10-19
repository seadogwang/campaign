package com.company.campaign.runtime;

/**
 * The closed kinds of durable T21 work the Runtime consumer accepts, and the namespace of an Inbox identity.
 *
 * <p>Inbox uniqueness is at least {@code (sourceKind, sourceId)}, so the two kinds never share an identity
 * space: a registration outbox event identifier and an occurrence execution key can be equal strings without
 * one delivery ever shadowing the other.
 */
public enum InboxSourceKind {

    /** One exact T21 {@code ScheduleRegistrationOutboxEvent}, identified by its event identifier. */
    SCHEDULE_REGISTRATION("SCHEDULE_REGISTRATION"),

    /** One exact committed T21 occurrence record, identified by its stable execution key. */
    SCHEDULE_OCCURRENCE("SCHEDULE_OCCURRENCE");

    private final String code;

    InboxSourceKind(String code) {
        this.code = code;
    }

    /** The bounded machine code persisted with a receipt of this kind. */
    public String code() {
        return code;
    }
}