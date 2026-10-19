package com.company.campaign.contracts;

import com.company.campaign.shared.ImmutableValues;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The only shape in which model-supplied Tool arguments may enter the system.
 *
 * <p>Trusted tenant, program, user, DataScope and evaluation-time values are injected by the host from the
 * server-side security context; they are never read from a Tool payload. Rather than trusting every tool
 * author to remember that, this contract refuses the reserved names outright — including when they are
 * nested inside an argument object or an array element — and fails with
 * {@code TOOL_INPUT_RESERVED_FIELD} naming the exact field and path.
 *
 * <p>Apply, Save, Publish, Pause, Resume, Retry and Archive are host-only commands, not Tools. Their names
 * are reserved here as well, so a prompt-injected or forged payload cannot smuggle a host-only action into
 * a Tool call.
 *
 * <p>Matching is deliberately loose: names are compared case-insensitively and with separators removed, so
 * {@code tenantId}, {@code tenant_id}, {@code TENANT-ID} and {@code tenant.id} are all refused. Rejecting
 * a legitimate field is the safe direction — a tool author renames it, whereas a missed alias is a
 * privilege-escalation hole.
 *
 * <p>The argument map is deep-frozen on construction, so a payload cannot be mutated after it has been
 * hashed or after a redaction decision was made about it.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ToolInput(@JsonValue Map<String, Object> arguments) {

    /** Normalized names that may never appear in model-supplied Tool input. */
    private static final Set<String> RESERVED_FIELD_NAMES = Set.of(
            // Trusted execution context.
            "tenant", "tenantid", "tenantname",
            "program", "programid", "programname",
            "user", "userid", "username", "currentuser",
            "datascope", "datascopeid", "datascopes", "scope", "scopes",
            "actor", "actorid", "principal", "principalid",
            "role", "roles", "permission", "permissions",
            "authority", "authorities", "grant", "grants", "entitlements",
            "securitycontext", "trustedcontext", "executioncontext", "hostcontext",
            "locale", "timezone", "reportingcurrency",
            "metadatasnapshot", "metadatasnapshotid", "asof", "datawatermark", "fixtureversion",
            // Host-only commands that are never model-callable.
            "apply", "save", "savedraft", "publish", "pause", "resume",
            "retry", "archive", "reject", "confirm", "approve");

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public ToolInput {
        Objects.requireNonNull(arguments, "arguments");
        rejectReservedNames(arguments, "$");
        arguments = ImmutableValues.freezeMap(arguments);
    }

    public static ToolInput of(Map<String, Object> arguments) {
        return new ToolInput(arguments);
    }

    public static ToolInput empty() {
        return new ToolInput(Map.of());
    }

    public boolean isEmpty() {
        return arguments.isEmpty();
    }

    private static void rejectReservedNames(Object value, String path) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String fieldName = String.valueOf(entry.getKey());
                if (RESERVED_FIELD_NAMES.contains(normalizeName(fieldName))) {
                    throw new ToolInputRejectedException(fieldName, path);
                }
                rejectReservedNames(entry.getValue(), path + "." + fieldName);
            }
            return;
        }
        if (value instanceof List<?> list) {
            for (int index = 0; index < list.size(); index++) {
                rejectReservedNames(list.get(index), path + "[" + index + "]");
            }
        }
    }

    private static String normalizeName(String fieldName) {
        StringBuilder normalized = new StringBuilder(fieldName.length());
        for (int index = 0; index < fieldName.length(); index++) {
            char character = fieldName.charAt(index);
            if (Character.isLetterOrDigit(character)) {
                // Locale-independent lowering: the reserved-name decision must not depend on the default locale.
                normalized.append(Character.toLowerCase(character));
            }
        }
        return normalized.toString();
    }
}