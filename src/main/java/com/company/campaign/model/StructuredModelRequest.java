package com.company.campaign.model;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * One bounded, immutable structured-model request: a fixed system instruction, one bounded user document and
 * one bounded response-schema document.
 *
 * <p>It is deliberately the whole input of {@link StructuredModelPort}. There is no provider SDK object, no
 * credential, no URL, no HTTP response, no tenant, no permission and no mutable map here, so a caller cannot
 * hand a model boundary anything but bounded text. Every part is length-bounded at construction and defensively
 * copied, so one request can never be widened or mutated after it is built.
 *
 * <p>{@link #prompt()} renders the one bounded prompt text the three parts compose to, in the fixed order
 * system, user, schema, joined by a single line feed with empty parts omitted. It is the exact text a
 * text-completion transport is asked to complete.
 */
public record StructuredModelRequest(String system, String user, String schema) {

    /** The largest accepted system-instruction length, in UTF-16 code units. */
    public static final int MAX_SYSTEM_LENGTH = 65_536;

    /** The largest accepted user-document length, in UTF-16 code units. */
    public static final int MAX_USER_LENGTH = 262_144;

    /** The largest accepted response-schema length, in UTF-16 code units. */
    public static final int MAX_SCHEMA_LENGTH = 65_536;

    public StructuredModelRequest {
        system = bounded(system, "system", MAX_SYSTEM_LENGTH);
        user = bounded(user, "user", MAX_USER_LENGTH);
        schema = bounded(schema, "schema", MAX_SCHEMA_LENGTH);
    }

    /** One request whose whole prompt is the bounded user document, with no system instruction or schema. */
    public static StructuredModelRequest ofUser(String user) {
        return new StructuredModelRequest("", user, "");
    }

    /** One request over an explicit system instruction, user document and response schema. */
    public static StructuredModelRequest of(String system, String user, String schema) {
        return new StructuredModelRequest(system, user, schema);
    }

    /** The one bounded prompt text this request renders to. */
    public String prompt() {
        List<String> parts = new ArrayList<>(3);
        if (!system.isEmpty()) {
            parts.add(system);
        }
        if (!user.isEmpty()) {
            parts.add(user);
        }
        if (!schema.isEmpty()) {
            parts.add(schema);
        }
        return String.join("\n", parts);
    }

    /** The exact UTF-8 bytes of the bounded prompt text. */
    public byte[] promptBytes() {
        return prompt().getBytes(StandardCharsets.UTF_8);
    }

    /** True when the request carries no text at all, so no transport may be invoked with it. */
    public boolean isEmpty() {
        return system.isEmpty() && user.isEmpty() && schema.isEmpty();
    }

    private static String bounded(String value, String field, int maxLength) {
        if (value == null) {
            return "";
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException("A structured model request field exceeds its bound: " + field);
        }
        return value;
    }

    /** Redacted diagnostics: the bounded shapes only, never the prompt text. */
    @Override
    public String toString() {
        return "StructuredModelRequest[system=" + system.length()
                + ", user=" + user.length()
                + ", schema=" + schema.length() + ']';
    }
}