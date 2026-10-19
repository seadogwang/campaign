package com.company.campaign.shared.canonical;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable content hash in the canonical {@code sha256:<64 lowercase hex characters>} form.
 *
 * <p>The value is a plain JSON string on the wire, not an object, so hashes compare and serialize
 * identically everywhere. Malformed values are rejected at construction with
 * {@code CONTENT_HASH_INVALID}; uppercase hex is intentionally rejected so one digest has exactly one
 * representation.
 */
public record ContentHash(@JsonValue String value) {

    private static final Pattern FORMAT = Pattern.compile("^sha256:[0-9a-f]{64}$");

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public ContentHash {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new ContentHashException(value);
        }
    }

    /** Computes the canonical hash of already-canonical bytes. */
    public static ContentHash sha256(byte[] canonicalContent) {
        Objects.requireNonNull(canonicalContent, "canonicalContent");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonicalContent);
            return new ContentHash(CanonicalJson.HASH_PREFIX + HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java platform but is unavailable", exception);
        }
    }

    /** True when this hash is the SHA-256 of the given canonical bytes. */
    public boolean matches(byte[] canonicalContent) {
        return equals(sha256(canonicalContent));
    }

    @Override
    public String toString() {
        return value;
    }
}