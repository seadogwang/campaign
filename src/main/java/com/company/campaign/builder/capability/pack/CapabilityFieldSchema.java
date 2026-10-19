package com.company.campaign.builder.capability.pack;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One declared payload field of a capability pack: a bounded field name and its typed constraint.
 *
 * <p>The name is a bounded canonical field identifier and nothing else — no display label, no prompt text,
 * no expression. Its constraint fixes the value kind and the bound, so a later reader can tell exactly what
 * a normalized payload may carry without executing anything. The name is additionally screened against the
 * closed safety vocabulary so executable, credential, provider or trusted-authority content can never be
 * declared as a payload field.
 *
 * <p>Whether the field is required or optional is not a fact of the schema: it is decided by which list of
 * {@link CapabilityPayloadContract} the schema appears in, so a field cannot be declared required in one
 * place and optional in another.
 */
public record CapabilityFieldSchema(String fieldName, CapabilityFieldConstraint constraint) {

    /** Largest permitted field-name length. */
    public static final int MAX_FIELD_NAME_LENGTH = 64;

    private static final Pattern FIELD_NAME = Pattern.compile("^[a-z][A-Za-z0-9_]{0,63}$");

    public CapabilityFieldSchema {
        if (fieldName == null || !FIELD_NAME.matcher(fieldName).matches()) {
            throw CapabilityPackSafety.invalid("capabilityPackFieldName");
        }
        CapabilityPackSafety.requireSafe(fieldName, "capabilityPackFieldName");
        if (constraint == null) {
            throw CapabilityPackSafety.invalid("capabilityPackFieldConstraint");
        }
    }

    /** The value kind the field declares, taken from its constraint. */
    public CapabilityValueKind kind() {
        return constraint.kind();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CapabilityFieldSchema schema
                && Objects.equals(fieldName, schema.fieldName)
                && Objects.equals(constraint, schema.constraint);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fieldName, constraint);
    }

    @Override
    public String toString() {
        return "CapabilityFieldSchema[name=" + fieldName + ", kind=" + kind() + ']';
    }
}