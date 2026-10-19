package com.company.campaign.security.data;

import com.company.campaign.shared.ImmutableValues;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Recursive redaction of secrets and PII from JSON-shaped values.
 *
 * <p>Rules, applied at every nesting level — including the top level, where a field name classifies the
 * whole value it carries:
 *
 * <ul>
 *   <li>credential, token, authorization-header, cookie, password and private-key names are redacted in
 *       every channel and can never be exempted;</li>
 *   <li>member identifiers, email, phone and other PII names are redacted from every projection channel;</li>
 *   <li>the single exception is a value that is explicitly a validated {@link SafeDerivedValue}; its
 *       grammar cannot carry the raw source value, so it is projected as its derived string. Secret names
 *       are never exemptible, even with a {@code SafeDerivedValue};</li>
 *   <li>maps, lists, sets and arrays are traversed recursively;</li>
 *   <li>the result is deep-frozen, so a redacted projection cannot be mutated afterwards.</li>
 * </ul>
 *
 * <p>A field name classifies the value it carries: a top-level field named {@code email} is replaced as a
 * whole, whether its value is a scalar or a structure. Inspecting only the value's children would leave the
 * raw value in place while still reporting the field as redacted.
 *
 * <p>The caller is still responsible for classifying genuinely restricted values as
 * {@link DataClassification#RESTRICTED}; name matching cannot see a secret stored under an innocent name.
 */
public final class Redaction {

    /** Replacement value written in place of a redacted scalar or subtree. */
    public static final String REDACTED = "[REDACTED]";

    private Redaction() {
    }

    /**
     * Redacts a value and returns a deep-frozen structure.
     *
     * <p>The value is treated as a naked value: its own name is unknown, so only nested field names can
     * classify it. A named top-level field must first be classified with
     * {@link #isRedactedField(String, Object, ProjectionChannel)}.
     */
    public static Object redact(Object value, ProjectionChannel channel) {
        Objects.requireNonNull(channel, "channel");
        return ImmutableValues.freeze(redactNode(value, channel));
    }

    /**
     * True when a named field's value is replaced by {@link #REDACTED} in this channel.
     *
     * <p>This describes what the projection actually does, not what the name suggests: a PII name carrying
     * a validated {@link SafeDerivedValue} is not replaced, and is therefore not reported as redacted.
     */
    public static boolean isRedactedField(String fieldName, Object value, ProjectionChannel channel) {
        Objects.requireNonNull(channel, "channel");
        SensitiveFieldClassifier.Sensitivity sensitivity = SensitiveFieldClassifier.classify(fieldName);
        if (sensitivity == SensitiveFieldClassifier.Sensitivity.SECRET) {
            return true;
        }
        return sensitivity == SensitiveFieldClassifier.Sensitivity.PII
                && redactsPii(channel)
                && !(value instanceof SafeDerivedValue);
    }

    /** Whether PII is withheld for a channel. */
    static boolean redactsPii(ProjectionChannel channel) {
        return channel == ProjectionChannel.APPLICATION_LOG
                || channel == ProjectionChannel.AUDIT_METADATA
                || channel == ProjectionChannel.MODEL_PROMPT;
    }

    private static Object redactNode(Object value, ProjectionChannel channel) {
        if (value instanceof SafeDerivedValue derived) {
            return derived.value();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> redacted = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (isRedactedField(key, entry.getValue(), channel)) {
                    redacted.put(key, REDACTED);
                } else {
                    redacted.put(key, redactNode(entry.getValue(), channel));
                }
            }
            return redacted;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> redacted = new ArrayList<>(collection.size());
            for (Object element : collection) {
                redacted.add(redactNode(element, channel));
            }
            return redacted;
        }
        if (value != null && value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> redacted = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                redacted.add(redactNode(Array.get(value, index), channel));
            }
            return redacted;
        }
        return value;
    }
}