package com.company.campaign.contracts;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A locale in canonical BCP-47 form, such as {@code zh-CN}.
 *
 * <p>{@link Locale} itself is not used as a contract field because it has several equivalent text forms
 * ({@code zh-CN}, {@code zh_CN}, {@code zh-CN-u-nu-latn}) and its {@code toString()} uses underscores
 * while its {@code toLanguageTag()} uses hyphens. Canonicalizing here means one locale has exactly one
 * representation in canonical JSON, so two requests that mean the same thing hash the same.
 *
 * <p>Tags that do not look like BCP-47 are rejected rather than passed through to {@link Locale}, which
 * would otherwise accept and silently reinterpret almost any string.
 */
public record LocaleTag(@JsonValue String value) {

    private static final Pattern FORMAT = Pattern.compile("^[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*$");

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public LocaleTag {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new LocaleTagException(value);
        }
        // Re-canonicalize so casing and subtag spelling cannot vary between producers.
        value = Locale.forLanguageTag(value).toLanguageTag();
    }

    public Locale toLocale() {
        return Locale.forLanguageTag(value);
    }

    public static LocaleTag of(Locale locale) {
        Objects.requireNonNull(locale, "locale");
        if (locale.getLanguage().isEmpty()) {
            throw new LocaleTagException(locale.toString());
        }
        return new LocaleTag(locale.toLanguageTag());
    }

    @Override
    public String toString() {
        return value;
    }
}