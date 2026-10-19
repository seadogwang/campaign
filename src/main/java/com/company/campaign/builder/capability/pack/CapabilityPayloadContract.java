package com.company.campaign.builder.capability.pack;

import com.company.campaign.shared.ImmutableValues;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The closed payload contract of one capability pack: which fields are required, which are optional, and the
 * typed constraint each one declares.
 *
 * <p>Requiredness is a fact of the containing list, not of the field schema, so the two lists together are
 * the whole declaration. A field name may appear exactly once overall: repeating it inside one list is a
 * duplicate field, and declaring it required in one list and optional in the other is an inconsistent
 * required/optional declaration. Both fail closed rather than letting list order decide.
 *
 * <p>Both lists are frozen into one deterministic name order and the field count is bounded, so a contract
 * is immutable, canonical and comparable by value, and two declarations of the same fields always produce
 * identical bytes whatever order the caller supplied.
 */
public record CapabilityPayloadContract(
        List<CapabilityFieldSchema> requiredFields,
        List<CapabilityFieldSchema> optionalFields) {

    /** Largest permitted number of declared fields in one payload contract. */
    public static final int MAX_FIELDS = 64;

    public CapabilityPayloadContract {
        requiredFields = canonical(requiredFields, "capabilityPackRequiredFields");
        optionalFields = canonical(optionalFields, "capabilityPackOptionalFields");
        Set<String> declared = new HashSet<>(requiredFields.size() + optionalFields.size());
        for (CapabilityFieldSchema field : requiredFields) {
            if (!declared.add(field.fieldName())) {
                throw CapabilityPackSafety.invalid("capabilityPackInconsistentFieldDeclaration");
            }
        }
        for (CapabilityFieldSchema field : optionalFields) {
            if (!declared.add(field.fieldName())) {
                throw CapabilityPackSafety.invalid("capabilityPackInconsistentFieldDeclaration");
            }
        }
        if (declared.size() > MAX_FIELDS) {
            throw CapabilityPackSafety.invalid("capabilityPackFieldCount");
        }
    }

    /** Every declared field, required and optional, in one deterministic name order. */
    public List<CapabilityFieldSchema> fields() {
        List<CapabilityFieldSchema> all = new ArrayList<>(requiredFields.size() + optionalFields.size());
        all.addAll(requiredFields);
        all.addAll(optionalFields);
        all.sort(Comparator.comparing(CapabilityFieldSchema::fieldName));
        return List.copyOf(all);
    }

    /** The required field names, in deterministic name order. */
    public List<String> requiredNames() {
        return requiredFields.stream().map(CapabilityFieldSchema::fieldName).toList();
    }

    /** The optional field names, in deterministic name order. */
    public List<String> optionalNames() {
        return optionalFields.stream().map(CapabilityFieldSchema::fieldName).toList();
    }

    /** True when the contract declares the field as required or optional. */
    public boolean declares(String fieldName) {
        return requiredNames().contains(fieldName) || optionalNames().contains(fieldName);
    }

    /**
     * Proves one field list is bounded and non-null, rejects a repeated name inside it, and freezes it into
     * one deterministic name order.
     */
    private static List<CapabilityFieldSchema> canonical(List<CapabilityFieldSchema> fields, String invariant) {
        if (fields == null) {
            throw CapabilityPackSafety.invalid(invariant);
        }
        List<CapabilityFieldSchema> copy = new ArrayList<>(fields.size());
        for (CapabilityFieldSchema field : fields) {
            if (field == null) {
                throw CapabilityPackSafety.invalid(invariant);
            }
            copy.add(field);
        }
        copy.sort(Comparator.comparing(CapabilityFieldSchema::fieldName));
        for (int index = 1; index < copy.size(); index++) {
            if (copy.get(index - 1).fieldName().equals(copy.get(index).fieldName())) {
                throw CapabilityPackSafety.invalid("capabilityPackDuplicateField");
            }
        }
        return ImmutableValues.freezeList(copy);
    }

    @Override
    public String toString() {
        return "CapabilityPayloadContract[required=" + requiredFields.size()
                + ", optional=" + optionalFields.size() + ']';
    }
}