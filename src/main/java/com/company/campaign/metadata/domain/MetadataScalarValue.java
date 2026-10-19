package com.company.campaign.metadata.domain;

import com.company.campaign.metadata.MetadataErrorCodes;
import com.company.campaign.metadata.MetadataException;
import com.company.campaign.metadata.MetadataTexts;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * A narrowly named, metadata-owned scalar value used only for aggregate empty-set values.
 *
 * <p>This is deliberately not the T05 Segment {@code TypedValue}: it exists so an empty-set behavior can be
 * pinned inside the metadata contract, it carries no expression semantics, and later tasks must not reuse
 * it as a segment value.
 *
 * <p>The value is stored in canonical text form per type, so semantically equal values (for example
 * {@code 1.0} and {@code 1}) cannot produce different snapshot hashes.
 */
public record MetadataScalarValue(SemanticDataType dataType, String canonicalValue) {

    private static final Pattern INTEGER_TEXT = Pattern.compile("^[+-]?(0|[1-9][0-9]*)$");
    private static final Pattern DECIMAL_TEXT = Pattern.compile("^[+-]?(0|[1-9][0-9]*)(\\.[0-9]+)?$");

    public MetadataScalarValue {
        dataType = MetadataTexts.requireNonNull(dataType, "scalarType", MetadataErrorCodes.METADATA_VALUE_INVALID);
        canonicalValue = canonicalize(dataType, canonicalValue);
    }

    private static String canonicalize(SemanticDataType dataType, String value) {
        if (value == null || value.isBlank()) {
            throw invalid("Metadata scalar value must not be blank");
        }
        String text = value.trim();
        try {
            return switch (dataType) {
                case STRING -> text;
                case BOOLEAN -> requireBoolean(text);
                case INTEGER -> requireInteger(text);
                case DECIMAL, MONEY -> requireDecimal(text);
                case DATE -> LocalDate.parse(text).toString();
                case TIMESTAMP -> Instant.parse(text).toString();
                case ENUM, ORDERED_ENUM -> MetadataTexts.requireMachineCode(text, "scalarEnumCode");
            };
        } catch (MetadataException exception) {
            throw exception;
        } catch (DateTimeParseException exception) {
            throw invalid("Metadata scalar value is not a valid " + dataType + " value");
        }
    }

    private static String requireBoolean(String text) {
        if (!"true".equalsIgnoreCase(text) && !"false".equalsIgnoreCase(text)) {
            throw invalid("Metadata boolean scalar must be true or false");
        }
        return text.toLowerCase(java.util.Locale.ROOT);
    }

    private static String requireInteger(String text) {
        if (!INTEGER_TEXT.matcher(text).matches()) {
            throw invalid("Metadata integer scalar is not a plain integer");
        }
        return new BigInteger(text).toString();
    }

    private static String requireDecimal(String text) {
        if (!DECIMAL_TEXT.matcher(text).matches()) {
            throw invalid("Metadata decimal scalar must use plain decimal notation");
        }
        BigDecimal decimal = new BigDecimal(text).stripTrailingZeros();
        if (decimal.scale() < 0) {
            decimal = decimal.setScale(0);
        }
        return decimal.toPlainString();
    }

    private static MetadataException invalid(String message) {
        return new MetadataException(MetadataErrorCodes.METADATA_VALUE_INVALID, message);
    }
}
