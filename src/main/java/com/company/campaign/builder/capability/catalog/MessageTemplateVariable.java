package com.company.campaign.builder.capability.catalog;

import com.company.campaign.builder.capability.pack.CapabilityValueKind;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One variable a Message Template Version declares, as a deterministic schema fact.
 *
 * <p>A variable is a bounded name, exactly one scalar value kind, an explicit required flag and — for text
 * only — a bounded maximum length. It is a <em>schema</em>, never a value and never a body: it carries no
 * default, no prompt text, no expression, no provider or credential fact. Whether a later compile supplies
 * the variable is decided by the payload, not by this declaration.
 *
 * <p>Only the scalar, authoring-safe kinds are representable. Reference, enumeration and duration kinds
 * belong to capability payload fields rather than to a message variable, so a declaration that names one
 * fails closed. A maximum length is meaningful for text alone: a non-text declaration must leave it unset,
 * and a text declaration must carry a positive bounded length, so the two can never be confused.
 */
public record MessageTemplateVariable(
        String name,
        CapabilityValueKind type,
        boolean required,
        int maxLength) {

    /** The closed set of kinds a message variable may declare. */
    private static final Set<CapabilityValueKind> SCALAR_KINDS = Set.of(
            CapabilityValueKind.STRING,
            CapabilityValueKind.INTEGER,
            CapabilityValueKind.DECIMAL,
            CapabilityValueKind.BOOLEAN,
            CapabilityValueKind.DATE,
            CapabilityValueKind.DATETIME);

    /** The unset maximum length a non-text variable must declare. */
    public static final int NO_MAX_LENGTH = 0;

    /** Largest permitted variable-name length. */
    public static final int MAX_NAME_LENGTH = 64;

    /** Largest permitted maximum length of one text variable. */
    public static final int MAX_TEXT_LENGTH = 4096;

    private static final Pattern NAME = Pattern.compile("^[a-z][A-Za-z0-9_]{0,63}$");

    public MessageTemplateVariable {
        if (name == null || !NAME.matcher(name).matches()) {
            throw CatalogSafety.invalid("messageTemplateVariableName");
        }
        if (type == null || !SCALAR_KINDS.contains(type)) {
            throw CatalogSafety.invalid("messageTemplateVariableType");
        }
        if (type == CapabilityValueKind.STRING) {
            if (maxLength < 1 || maxLength > MAX_TEXT_LENGTH) {
                throw CatalogSafety.invalid("messageTemplateVariableMaxLength");
            }
        } else if (maxLength != NO_MAX_LENGTH) {
            // A length bound on a non-text variable would describe something the value can never carry.
            throw CatalogSafety.invalid("messageTemplateVariableMaxLength");
        }
    }

    /** True when this variable is bounded text. */
    public boolean isText() {
        return type == CapabilityValueKind.STRING;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MessageTemplateVariable variable
                && required == variable.required
                && maxLength == variable.maxLength
                && type == variable.type
                && Objects.equals(name, variable.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, type, required, maxLength);
    }

    /** Redacted diagnostics: the variable name is bounded machine metadata and safe to report. */
    @Override
    public String toString() {
        return "MessageTemplateVariable[name=" + name + ", type=" + type
                + ", required=" + required + ", maxLength=" + maxLength + ']';
    }
}