package com.company.campaign.production.audit;

import com.company.campaign.shared.canonical.ContentHash;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Non-reversible, domain-separated fingerprints for the durable audit ledger.
 *
 * <p>The ledger may not persist a raw tenant, program, user, DataScope, subject, execution or member identifier.
 * This type makes the readable form unrepresentable: it consumes a bounded identifier together with the
 * <em>namespace</em> that says which kind of identifier it was, and returns only a canonical
 * {@link ContentHash}. The identifier cannot be read back from the fingerprint or from its serialized form.
 *
 * <p>The preimage is a length-prefixed tuple, not a joined string. A naive join with a separator is ambiguous
 * when a value itself contains the separator ({@code ("a:b", "c")} and {@code ("a", "b:c")} produce the same
 * text); length prefixing makes every field self-delimiting. The leading fixed domain tag separates this hash
 * from every other hash domain in the system — a subject fingerprint can never equal an
 * {@code AuditResourceFingerprint} or a redacted projection hash computed over the same text.
 */
public final class ProductionAuditFingerprint {

    /** Domain tag that separates this hash from every other hash domain in the system. */
    static final String PREIMAGE_DOMAIN = "campaign.production.audit.fingerprint.v1";

    /** The largest accepted identifier length; it matches the accepted reference-identifier bound. */
    static final int MAX_VALUE_LENGTH = 255;

    private ProductionAuditFingerprint() {
    }

    /**
     * The non-reversible fingerprint of one bounded identifier in one namespace.
     *
     * <p>The namespace is a literal supplied by an adapter, never a request value, so a caller cannot move an
     * identifier into another namespace's hash domain.
     */
    public static ContentHash of(String namespace, String value) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(value, "value");
        if (value.isEmpty() || value.length() > MAX_VALUE_LENGTH) {
            throw ProductionAuditCodec.invalid("fingerprint");
        }
        return ContentHash.sha256(preimage(namespace, value));
    }

    /** The fingerprint of a present identifier, or {@code null} when the source carries none. */
    public static ContentHash ofNullable(String namespace, String value) {
        return value == null ? null : of(namespace, value);
    }

    /**
     * The exact byte representation that is hashed.
     *
     * <p>Each field is written as a 4-byte big-endian length followed by its UTF-8 bytes, in the order domain,
     * namespace, value. The encoding is package-private so a regression test can prove the domain separation;
     * it neither stores nor serializes the identifier.
     */
    static byte[] preimage(String namespace, String value) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            writeField(out, PREIMAGE_DOMAIN);
            writeField(out, namespace);
            writeField(out, value);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return buffer.toByteArray();
    }

    private static void writeField(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }
}