package com.company.campaign.segment.validation;

import com.company.campaign.metadata.domain.SemanticDataType;
import com.company.campaign.metadata.domain.StringCaseSensitivity;
import com.company.campaign.metadata.domain.StringNormalization;
import com.company.campaign.metadata.domain.StringSemantics;
import com.company.campaign.segment.canonical.CanonicalForms;
import com.company.campaign.segment.canonical.CanonicalValue;
import com.company.campaign.segment.dsl.value.BooleanValue;
import com.company.campaign.segment.dsl.value.DateValue;
import com.company.campaign.segment.dsl.value.DateTimeValue;
import com.company.campaign.segment.dsl.value.DecimalValue;
import com.company.campaign.segment.dsl.value.EnumValue;
import com.company.campaign.segment.dsl.value.IntegerValue;
import com.company.campaign.segment.dsl.value.ListValue;
import com.company.campaign.segment.dsl.value.MoneyValue;
import com.company.campaign.segment.dsl.value.RangeValue;
import com.company.campaign.segment.dsl.value.ScalarValue;
import com.company.campaign.segment.dsl.value.StringValue;
import com.company.campaign.segment.dsl.value.TypedValue;
import com.company.campaign.segment.dsl.value.ValueKind;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The one place where a T05 typed value is mapped to and normalized against a T04 {@link SemanticDataType}.
 *
 * <p>Two responsibilities live together deliberately, because they are the same contract seen twice:
 * <em>which</em> editable value kind a data type admits, and <em>how</em> an admitted value is normalized for
 * the canonical form. Splitting them would let the check and the normalization disagree.
 *
 * <p>Normalization is Metadata-owned and exhaustive: decimal scale, timestamp offset, enum code and currency
 * casing are fixed here, and a STRING is normalized with its definition's declared Unicode form, case
 * sensitivity and locale. Nothing consults the process default locale or timezone.
 */
final class SegmentValueRules {

    private SegmentValueRules() {
    }

    /** The single editable value kind a Metadata data type admits. */
    static ValueKind expectedKind(SemanticDataType dataType) {
        return switch (dataType) {
            case STRING -> ValueKind.STRING;
            case BOOLEAN -> ValueKind.BOOLEAN;
            case INTEGER -> ValueKind.INTEGER;
            case DECIMAL -> ValueKind.DECIMAL;
            case DATE -> ValueKind.DATE;
            case TIMESTAMP -> ValueKind.DATETIME;
            case ENUM, ORDERED_ENUM -> ValueKind.ENUM;
            case MONEY -> ValueKind.MONEY;
        };
    }

    /** True when the scalar's editable kind is the exact kind the data type admits. */
    static boolean compatibleScalar(SemanticDataType dataType, ScalarValue scalar) {
        return scalar.kind() == expectedKind(dataType);
    }

    /** Canonicalizes a present operand against its owning definition's type and string semantics. */
    static CanonicalValue canonicalValue(
            SemanticDataType dataType, StringSemantics stringSemantics, TypedValue value) {
        return switch (value) {
            case ScalarValue scalar -> canonicalScalar(dataType, stringSemantics, scalar);
            case ListValue list -> {
                List<CanonicalValue> items = new ArrayList<>(list.items().size());
                for (ScalarValue item : list.items()) {
                    items.add(canonicalScalar(dataType, stringSemantics, item));
                }
                // The canonical LIST constructor owns the item order and the duplicate-free guarantee.
                yield new CanonicalValue.ListValue(items);
            }
            case RangeValue range -> new CanonicalValue.RangeValue(
                    canonicalScalar(dataType, stringSemantics, range.lower()),
                    canonicalScalar(dataType, stringSemantics, range.upper()),
                    range.includeLower(),
                    range.includeUpper());
        };
    }

    /** Canonicalizes one scalar, already proven compatible with the data type. */
    static CanonicalValue canonicalScalar(
            SemanticDataType dataType, StringSemantics stringSemantics, ScalarValue scalar) {
        return switch (scalar) {
            case StringValue text -> new CanonicalValue.TextValue(normalizeString(stringSemantics, text.value()));
            case IntegerValue integer -> new CanonicalValue.IntegerValue(integer.value());
            case DecimalValue decimal -> new CanonicalValue.DecimalValue(canonicalDecimal(decimal.value()));
            case BooleanValue bool -> new CanonicalValue.BooleanValue(bool.value());
            case DateValue date -> new CanonicalValue.DateValue(date.value());
            case DateTimeValue dateTime -> new CanonicalValue.InstantValue(canonicalInstant(dateTime.value()));
            case EnumValue enumValue -> new CanonicalValue.EnumValue(enumValue.value());
            case MoneyValue money -> new CanonicalValue.MoneyValue(
                    canonicalDecimal(money.amount()), money.currency());
        };
    }

    /**
     * Normalizes a plain-decimal lexeme to one semantic representation.
     *
     * <p>{@code 1}, {@code 1.0} and {@code 1.00} all become {@code 1}; every zero representation becomes
     * {@code 0}. No rounding, scaling or currency conversion is applied.
     */
    static String canonicalDecimal(String text) {
        return CanonicalForms.decimal(text);
    }

    /** Normalizes an offset date-time to the one UTC instant representation for that instant. */
    static String canonicalInstant(String text) {
        return CanonicalForms.instant(text);
    }

    /**
     * Applies the owning definition's declared string contract deterministically.
     *
     * <p>The Unicode form runs first, then locale-independent or locale-specific case folding when the
     * definition is case-insensitive. Locale-neutral semantics use {@link Locale#ROOT}; a locale-specific
     * definition uses exactly its own declared locale. No collation is applied, because collation only
     * matters for ordering and every ordering decision here is Metadata-owned and already validated.
     */
    static String normalizeString(StringSemantics semantics, String value) {
        String text = value;
        if (semantics != null) {
            Normalizer.Form form = switch (semantics.normalization()) {
                case NONE -> null;
                case NFC -> Normalizer.Form.NFC;
                case NFD -> Normalizer.Form.NFD;
                case NFKC -> Normalizer.Form.NFKC;
                case NFKD -> Normalizer.Form.NFKD;
            };
            if (form != null) {
                text = Normalizer.normalize(text, form);
            }
            if (semantics.caseSensitivity() == StringCaseSensitivity.CASE_INSENSITIVE) {
                Locale locale = semantics.isLocaleNeutral() ? Locale.ROOT : semantics.locale().toLocale();
                text = text.toLowerCase(locale);
            }
        }
        return text;
    }
}
