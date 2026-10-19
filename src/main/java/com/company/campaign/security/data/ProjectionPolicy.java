package com.company.campaign.security.data;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Allowlist-based projection policy for one channel.
 *
 * <p>Projection applies, in order:
 *
 * <ol>
 *   <li>payload size, depth and collection limits are enforced (fail, never truncate);</li>
 *   <li>a field not on the allowlist is omitted, even if its classification would otherwise be allowed;</li>
 *   <li>a field more sensitive than the channel maximum is omitted — the model prompt maximum is
 *       {@code CONFIDENTIAL}, so {@code RESTRICTED} is excluded by default;</li>
 *   <li>the field's own <em>name</em> is classified against the whole value: a secret name is always
 *       replaced by {@link Redaction#REDACTED}, and a raw PII name is replaced in every channel. Only a
 *       validated {@link SafeDerivedValue} exempts a PII name, and never a secret name;</li>
 *   <li>a surviving value is recursively redacted for nested secrets and PII and deep-frozen.</li>
 * </ol>
 *
 * <p>There is deliberately no caller-supplied set of field names that escape redaction. Such a set could be
 * satisfied by repeating a sensitive name and would turn a policy option into a PII bypass; a safe derived
 * representation is expressed as a value whose type cannot carry the raw source value instead.
 */
public final class ProjectionPolicy {

    private final ProjectionChannel channel;
    private final Set<String> allowlistedFields;
    private final DataClassification maxClassification;
    private final ProjectionLimits limits;

    public ProjectionPolicy(
            ProjectionChannel channel,
            Set<String> allowlistedFields,
            DataClassification maxClassification,
            ProjectionLimits limits) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.allowlistedFields = immutable(allowlistedFields);
        this.maxClassification = Objects.requireNonNull(maxClassification, "maxClassification");
        this.limits = limits == null ? ProjectionLimits.DEFAULT : limits;
    }

    /** Creates a policy for a channel with the default maximum classification and limits. */
    public static ProjectionPolicy forChannel(ProjectionChannel channel, Set<String> allowlistedFields) {
        return new ProjectionPolicy(
                channel, allowlistedFields, DataClassification.CONFIDENTIAL, ProjectionLimits.DEFAULT);
    }

    public ProjectionChannel channel() {
        return channel;
    }

    public Set<String> allowlistedFields() {
        return allowlistedFields;
    }

    public DataClassification maxClassification() {
        return maxClassification;
    }

    public ProjectionLimits limits() {
        return limits;
    }

    public ProjectionResult project(ClassifiedPayload payload) {
        Objects.requireNonNull(payload, "payload");

        Map<String, Object> rawValues = new LinkedHashMap<>();
        for (ClassifiedField field : payload.fields()) {
            rawValues.put(field.name(), field.value());
        }
        limits.enforce(rawValues);

        Map<String, Object> values = new LinkedHashMap<>();
        Set<String> omitted = new LinkedHashSet<>();
        Set<String> redacted = new LinkedHashSet<>();
        for (ClassifiedField field : payload.fields()) {
            if (!allowlistedFields.contains(field.name())) {
                omitted.add(field.name());
                continue;
            }
            if (field.classification().isMoreSensitiveThan(maxClassification)) {
                omitted.add(field.name());
                continue;
            }
            if (Redaction.isRedactedField(field.name(), field.value(), channel)) {
                // The name classifies the whole value, so a structured value under a sensitive name is
                // replaced as a whole rather than partially traversed.
                values.put(field.name(), Redaction.REDACTED);
                redacted.add(field.name());
            } else {
                values.put(field.name(), Redaction.redact(field.value(), channel));
            }
        }
        return new ProjectionResult(channel, values, omitted, redacted);
    }

    /** Returns the projected value of a required field, or fails with {@code PROJECTION_FIELD_DENIED}. */
    public Object requireProjected(ClassifiedPayload payload, String fieldName) {
        ProjectionResult result = project(payload);
        if (!result.values().containsKey(fieldName)) {
            throw new ProjectionDeniedException(fieldName);
        }
        return result.values().get(fieldName);
    }

    private static Set<String> immutable(Set<String> source) {
        return source == null || source.isEmpty() ? Set.of() : Set.copyOf(source);
    }
}