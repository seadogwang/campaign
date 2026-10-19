package com.company.campaign.builder.segment;

import java.util.ArrayList;
import java.util.List;

final class SemanticDraftTexts {

    private SemanticDraftTexts() {
    }

    static String required(String value, String field, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException("Invalid semantic draft text: " + field);
        }
        return value;
    }

    static String identifier(String value, String field) {
        value = required(value, field, 64);
        if (!value.matches("[A-Za-z][A-Za-z0-9_-]*")) {
            throw new IllegalArgumentException("Invalid semantic draft identifier: " + field);
        }
        return value;
    }

    static String reasonCode(String value) {
        value = required(value, "reasonCode", 128);
        if (!value.matches("[A-Z][A-Z0-9_]*")) {
            throw new IllegalArgumentException("A semantic draft reason code must be uppercase");
        }
        return value;
    }

    static List<String> list(List<String> values, String field, int maximumEntries, int maximumLength) {
        if (values == null) {
            return List.of();
        }
        if (values.size() > maximumEntries) {
            throw new IllegalArgumentException("A semantic draft list exceeds its bound: " + field);
        }
        List<String> copy = new ArrayList<>(values.size());
        for (String value : values) {
            copy.add(required(value, field, maximumLength));
        }
        return List.copyOf(copy);
    }
}
