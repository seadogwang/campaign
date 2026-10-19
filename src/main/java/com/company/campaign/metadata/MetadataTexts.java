package com.company.campaign.metadata;

import com.company.campaign.contracts.ContractPatterns;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Shared validation and canonical ordering for metadata identifiers and explanatory text.
 *
 * <p>Aliases, positive examples, negative examples and enum aliases are explanatory metadata, never
 * executable input. They keep their original display text for output, are rejected when blank, and are
 * ordered by their normalized key so a snapshot's canonical bytes do not depend on the order a caller
 * happened to supply.
 */
public final class MetadataTexts {

    private static final Pattern REFERENCE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);
    private static final Pattern MACHINE_CODE = Pattern.compile(ContractPatterns.MACHINE_CODE);

    private MetadataTexts() {
    }

    /** Validates a stable identifier against the bounded reference grammar. */
    public static String requireId(String value, String field) {
        if (value == null || !REFERENCE_ID.matcher(value).matches()) {
            throw new MetadataException(
                    MetadataErrorCodes.METADATA_ID_INVALID,
                    "Metadata identifier '" + field + "' is not a valid reference identifier");
        }
        return value;
    }

    /** Validates a stable uppercase machine code, such as an enum value code. */
    public static String requireMachineCode(String value, String field) {
        if (value == null || !MACHINE_CODE.matcher(value).matches()) {
            throw new MetadataException(
                    MetadataErrorCodes.METADATA_ID_INVALID,
                    "Metadata code '" + field + "' is not a valid machine code");
        }
        return value;
    }

    /** Validates a required display name; the original text is preserved. */
    public static String requireDisplayName(String value, String field) {
        MetadataTextNormalizer.requireNormalized(value, field);
        return value;
    }

    /** A null value stays null; a present value must be non-blank and keeps its original text. */
    public static String optionalText(String value, String field) {
        if (value == null) {
            return null;
        }
        MetadataTextNormalizer.requireNormalized(value, field);
        return value;
    }

    /**
     * Canonicalizes an explanatory text list: rejects null elements and blanks, rejects duplicate normalized
     * keys, and orders by normalized key then display text. The original text is preserved.
     */
    public static List<String> canonicalTextList(List<String> values, String field) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        List<String> copy = new ArrayList<>(values.size());
        for (String value : values) {
            String normalized = MetadataTextNormalizer.requireNormalized(value, field);
            if (!seen.add(normalized)) {
                throw new MetadataException(
                        MetadataErrorCodes.METADATA_NORMALIZED_COLLISION,
                        "Metadata text '" + field + "' contains a duplicate after normalization");
            }
            copy.add(value);
        }
        copy.sort(normalizedOrder());
        return List.copyOf(copy);
    }

    /** Orders text by normalized key, then by the original display text as a deterministic last resort. */
    public static Comparator<String> normalizedOrder() {
        return Comparator.comparing(MetadataTextNormalizer::normalize).thenComparing(Function.identity());
    }

    /** Rejects a list whose normalized forms overlap a second normalized form (for example negative vs positive examples). */
    public static void requireDisjoint(List<String> left, List<String> right, String field) {
        Set<String> leftKeys = normalizedKeys(left);
        for (String value : right) {
            if (leftKeys.contains(MetadataTextNormalizer.normalize(value))) {
                throw new MetadataException(
                        MetadataErrorCodes.METADATA_NORMALIZED_COLLISION,
                        "Metadata text '" + field + "' must be distinct after normalization");
            }
        }
    }

    /** Rejects a normalized text that collides with a normalized display name. */
    public static void requireNoCollision(String displayName, List<String> values, String field) {
        String displayKey = MetadataTextNormalizer.normalize(displayName);
        for (String value : values) {
            if (displayKey.equals(MetadataTextNormalizer.normalize(value))) {
                throw new MetadataException(
                        MetadataErrorCodes.METADATA_NORMALIZED_COLLISION,
                        "Metadata text '" + field + "' collides with the display name after normalization");
            }
        }
    }

    /** The normalized key of a required text. */
    public static String normalizedKey(String value, String field) {
        return MetadataTextNormalizer.requireNormalized(value, field);
    }

    /** Immutable copy of a value that must not be null. */
    public static <T> T requireNonNull(T value, String field, String code) {
        if (value == null) {
            throw new MetadataException(code, "Metadata field '" + field + "' must not be null");
        }
        return value;
    }

    private static Set<String> normalizedKeys(List<String> values) {
        Set<String> keys = new LinkedHashSet<>();
        if (values != null) {
            values.forEach(value -> keys.add(MetadataTextNormalizer.normalize(Objects.requireNonNull(value))));
        }
        return keys;
    }
}