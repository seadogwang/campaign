package com.company.campaign.metadata.domain;

import com.company.campaign.contracts.LocaleTag;
import com.company.campaign.metadata.MetadataErrorCodes;
import com.company.campaign.metadata.MetadataException;
import com.company.campaign.metadata.MetadataTexts;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Immutable, narrowly scoped string-comparison semantics for a {@code STRING} Attribute or Metric.
 *
 * <p>Segment spec section 8 requires strings to declare case sensitivity, normalization and locale, because
 * two evaluators can otherwise derive different member sets from the same snapshot. This value freezes
 * exactly those three decisions and nothing else: it is semantic hash material, it contains no comparator
 * and no Segment value representation, and it deliberately does not reuse {@code MetadataTextNormalizer},
 * which remains the fixed catalog-discovery normalizer.
 *
 * <p>Locale behavior is structural, never implied: {@link StringLocaleMode#LOCALE_NEUTRAL} forbids a locale
 * tag, {@link StringLocaleMode#LOCALE_SPECIFIC} requires one, and a partial declaration is rejected.
 */
public record StringSemantics(
        StringCaseSensitivity caseSensitivity,
        StringNormalization normalization,
        StringLocaleMode localeMode,
        LocaleTag locale
) {

    public StringSemantics {
        caseSensitivity = MetadataTexts.requireNonNull(
                caseSensitivity, "stringCaseSensitivity", MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID);
        normalization = MetadataTexts.requireNonNull(
                normalization, "stringNormalization", MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID);
        localeMode = MetadataTexts.requireNonNull(
                localeMode, "stringLocaleMode", MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID);
        switch (localeMode) {
            case LOCALE_NEUTRAL -> {
                if (locale != null) {
                    throw combination("A locale-neutral string semantics must not declare a locale tag");
                }
            }
            case LOCALE_SPECIFIC -> {
                if (locale == null) {
                    throw combination("A locale-specific string semantics must declare a locale tag");
                }
            }
        }
    }

    /** String semantics that may not depend on any locale. */
    public static StringSemantics localeNeutral(
            StringCaseSensitivity caseSensitivity, StringNormalization normalization) {
        return new StringSemantics(caseSensitivity, normalization, StringLocaleMode.LOCALE_NEUTRAL, null);
    }

    /** String semantics defined for exactly one validated locale tag. */
    public static StringSemantics ofLocale(
            StringCaseSensitivity caseSensitivity, StringNormalization normalization, LocaleTag locale) {
        return new StringSemantics(caseSensitivity, normalization, StringLocaleMode.LOCALE_SPECIFIC, locale);
    }

    /**
     * True when comparison may not depend on any locale.
     *
     * <p>Not a JSON property: {@link JsonIgnore} keeps this derived convenience accessor out of the
     * canonical contract, which carries only the four declared components.
     */
    @JsonIgnore
    public boolean isLocaleNeutral() {
        return localeMode == StringLocaleMode.LOCALE_NEUTRAL;
    }

    private static MetadataException combination(String message) {
        return new MetadataException(MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID, message);
    }
}