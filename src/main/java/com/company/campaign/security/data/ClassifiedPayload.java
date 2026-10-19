package com.company.campaign.security.data;

import com.company.campaign.shared.ImmutableValues;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;

/**
 * An ordered, immutable collection of {@link ClassifiedField}s.
 *
 * <p>Duplicate field names are rejected: an allowlist decision is made by name, so two fields sharing a
 * name would make the decision ambiguous.
 */
public record ClassifiedPayload(List<ClassifiedField> fields) {

    public ClassifiedPayload {
        Objects.requireNonNull(fields, "fields");
        fields = ImmutableValues.freezeList(fields);
        Set<String> seen = new HashSet<>();
        for (ClassifiedField field : fields) {
            if (!seen.add(field.name())) {
                throw new IllegalArgumentException("Duplicate classified field name: " + field.name());
            }
        }
    }

    public static ClassifiedPayload of(ClassifiedField... fields) {
        return new ClassifiedPayload(List.of(fields));
    }

    /** Fields indexed by name, in declaration order, immutable. */
    public Map<String, ClassifiedField> byName() {
        Map<String, ClassifiedField> byName = new LinkedHashMap<>();
        for (ClassifiedField field : fields) {
            byName.put(field.name(), field);
        }
        return Collections.unmodifiableMap(byName);
    }
}