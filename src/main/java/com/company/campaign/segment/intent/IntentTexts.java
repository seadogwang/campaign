package com.company.campaign.segment.intent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Bounded text and machine-code validation shared by the Intent model.
 *
 * <p>Every check raises a fixed, non-disclosing {@link IntentException}; rejected text is never echoed.
 */
final class IntentTexts {

    private static final Pattern MACHINE_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");
    private static final Pattern REFERENCE_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    private IntentTexts() {
    }

    static String requireCode(String value, String field) {
        if (value == null || !MACHINE_CODE.matcher(value).matches()) {
            throw new IntentException(
                    IntentErrorCodes.MACHINE_CODE_INVALID,
                    "Intent field '" + field + "' is not a bounded machine code");
        }
        return value;
    }

    static String requireReferenceId(String value, String field) {
        if (value == null || !REFERENCE_ID.matcher(value).matches()) {
            throw new IntentException(
                    IntentErrorCodes.CLAUSE_ID_INVALID,
                    "Intent field '" + field + "' is not a valid reference identifier");
        }
        return value;
    }

    static String requireCurrencyCode(String value, String field) {
        if (value == null || !CURRENCY.matcher(value).matches()) {
            throw new IntentException(
                    IntentErrorCodes.CURRENCY_INVALID,
                    "Intent field '" + field + "' is not a canonical ISO-4217 currency code");
        }
        return value;
    }

    static List<String> boundedReferenceIds(List<String> values, String field) {
        if (values == null) {
            return List.of();
        }
        if (values.size() > IntentLimits.MAX_LIST_ENTRIES) {
            throw new IntentException(
                    IntentErrorCodes.LIMIT_EXCEEDED, "Intent list '" + field + "' exceeds the entry limit");
        }
        Set<String> unique = new LinkedHashSet<>();
        List<String> copy = new ArrayList<>(values.size());
        for (String value : values) {
            String id = requireReferenceId(value, field);
            if (!unique.add(id)) {
                throw new IntentException(
                        IntentErrorCodes.MACHINE_CODE_INVALID, "Intent list '" + field + "' repeats an entry");
            }
            copy.add(id);
        }
        return List.copyOf(copy);
    }

    static List<String> boundedCodes(List<String> values, String field) {
        if (values == null) {
            return List.of();
        }
        if (values.size() > IntentLimits.MAX_CODE_ENTRIES) {
            throw new IntentException(
                    IntentErrorCodes.LIMIT_EXCEEDED, "Intent list '" + field + "' exceeds the entry limit");
        }
        Set<String> unique = new LinkedHashSet<>();
        List<String> copy = new ArrayList<>(values.size());
        for (String value : values) {
            String code = requireCode(value, field);
            if (!unique.add(code)) {
                throw new IntentException(
                        IntentErrorCodes.MACHINE_CODE_INVALID, "Intent list '" + field + "' repeats an entry");
            }
            copy.add(code);
        }
        return List.copyOf(copy);
    }

    /** A required bounded value text: present, within the limit, and containing no control characters. */
    static String requireText(String value, String field) {
        if (value == null || value.length() > IntentLimits.MAX_TEXT_LENGTH || !isWellFormed(value)) {
            throw new IntentException(
                    IntentErrorCodes.TEXT_INVALID,
                    "Intent field '" + field + "' is not admissible bounded text");
        }
        return value;
    }

    /** True when the text contains no lone UTF-16 surrogate, so it can be encoded losslessly. */
    static boolean isWellFormed(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    return false;
                }
                index++;
            } else if (Character.isLowSurrogate(character)) {
                return false;
            }
        }
        return true;
    }
}
