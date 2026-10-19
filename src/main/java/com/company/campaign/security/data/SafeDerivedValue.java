package com.company.campaign.security.data;

import com.company.campaign.shared.canonical.ContentHash;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * An explicitly typed, validated derived representation of a sensitive value.
 *
 * <p>This is the only way a field that would otherwise be PII-redacted can carry a value through
 * projection. It replaces the earlier {@code safeDerivedFields} string set, which could be satisfied by
 * simply repeating a sensitive field name and therefore turned a policy option into a PII bypass.
 *
 * <p>The type makes the bypass unrepresentable rather than detecting it: there is no kind that can hold a
 * raw email address, member identifier, phone number or credential, because every kind's grammar rejects
 * the shape of the raw source value. Marking raw data "safe" is not a decision a caller can make — it is a
 * property of the value that is checked at construction.
 *
 * <p>Secret names remain redacted regardless: this type can exempt PII redaction, never secret redaction.
 * The constructor never echoes the rejected value, because the rejected value may itself be the secret.
 *
 * <p>Matching is by explicit kind and anchored grammar, never by inspecting content heuristically.
 */
public record SafeDerivedValue(DerivedKind kind, String value) {

    /** The closed set of derived representations the security core accepts. */
    public enum DerivedKind {

        /**
         * The domain part of an email address, for example {@code example.com}. The grammar excludes
         * {@code @}, so a full address cannot be represented.
         */
        EMAIL_DOMAIN("^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$"),

        /**
         * The canonical SHA-256 hash of a member identifier. A raw identifier is not a canonical hash and
         * therefore cannot be represented. The grammar is deliberately identical to
         * {@link ContentHash}'s canonical wire form; {@link #memberIdHash(ContentHash)} accepts a
         * {@link ContentHash} so the two can never drift.
         */
        MEMBER_ID_HASH("^sha256:[0-9a-f]{64}$"),

        /**
         * An E.164 country calling code such as {@code +86}. The grammar allows at most four characters, so
         * a full phone number cannot be represented.
         */
        PHONE_COUNTRY_CODE("^\\+[0-9]{1,3}$");

        private final Pattern format;

        DerivedKind(String format) {
            this.format = Pattern.compile(format);
        }

        boolean accepts(String candidate) {
            return format.matcher(candidate).matches();
        }
    }

    /** Longest derived value any kind can accept (a DNS name); bounded so a derived value stays compact. */
    private static final int MAX_LENGTH = 253;

    public SafeDerivedValue {
        Objects.requireNonNull(kind, "kind");
        if (value == null
                || value.isEmpty()
                || value.length() > MAX_LENGTH
                || !kind.accepts(value)) {
            // Never include the candidate: it may be the raw sensitive value someone tried to mark safe.
            throw new IllegalArgumentException("value is not a valid " + kind + " derived representation");
        }
    }

    /** Validates a derived value against its declared kind. */
    public static SafeDerivedValue of(DerivedKind kind, String value) {
        return new SafeDerivedValue(kind, value);
    }

    /** The domain part of an email address. */
    public static SafeDerivedValue emailDomain(String domain) {
        return new SafeDerivedValue(DerivedKind.EMAIL_DOMAIN, domain);
    }

    /** The canonical hash of a member identifier. */
    public static SafeDerivedValue memberIdHash(ContentHash hash) {
        Objects.requireNonNull(hash, "hash");
        return new SafeDerivedValue(DerivedKind.MEMBER_ID_HASH, hash.value());
    }

    /** An E.164 country calling code. */
    public static SafeDerivedValue phoneCountryCode(String countryCode) {
        return new SafeDerivedValue(DerivedKind.PHONE_COUNTRY_CODE, countryCode);
    }

    /** The projected representation: the validated derived string, never the raw source value. */
    @Override
    public String toString() {
        return value;
    }
}