package com.company.campaign.builder.capability.message;

/**
 * The closed, ordered vocabulary of stable issues the {@code message.template} pack reports.
 *
 * <p>Each code names exactly one deterministic rejection so a caller can branch on it, and none of them
 * echoes a rejected value: a payload, a reference code, a variable name or a channel that failed is never
 * reproduced in an issue, because those are client-influenced input.
 */
public final class MessageTemplateErrorCodes {

    /** The payload is absent or is not the one closed shape the pack declares. */
    public static final String MESSAGE_TEMPLATE_PAYLOAD_INVALID = "MESSAGE_TEMPLATE_PAYLOAD_INVALID";

    /** The payload declares a field the pack contract does not declare. */
    public static final String MESSAGE_TEMPLATE_UNKNOWN_FIELD = "MESSAGE_TEMPLATE_UNKNOWN_FIELD";

    /** The payload's reference code names no template version in the frozen catalog. */
    public static final String MESSAGE_TEMPLATE_UNKNOWN_REFERENCE = "MESSAGE_TEMPLATE_UNKNOWN_REFERENCE";

    /** The exact template version the reference code names is disabled for authoring. */
    public static final String MESSAGE_TEMPLATE_DISABLED = "MESSAGE_TEMPLATE_DISABLED";

    /** The exact template version the reference code names is not runtime-available. */
    public static final String MESSAGE_TEMPLATE_UNAVAILABLE = "MESSAGE_TEMPLATE_UNAVAILABLE";

    /** The payload declares a channel that is not the resolved template version's own channel. */
    public static final String MESSAGE_TEMPLATE_CHANNEL_MISMATCH = "MESSAGE_TEMPLATE_CHANNEL_MISMATCH";

    /** The supplied variables are missing, extra, of the wrong type or over their declared length. */
    public static final String MESSAGE_TEMPLATE_VARIABLES_INVALID = "MESSAGE_TEMPLATE_VARIABLES_INVALID";

    private MessageTemplateErrorCodes() {
    }
}