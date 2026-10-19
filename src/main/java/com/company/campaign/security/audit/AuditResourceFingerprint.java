package com.company.campaign.security.audit;

import com.company.campaign.security.authorization.OwnedResourceRef;
import com.company.campaign.security.authorization.ResourceKind;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Non-reversible fingerprint of one server-owned audit resource.
 *
 * <p>The previous audit contract carried a readable resource reference. A conservative identifier grammar
 * cannot prove that a single token is non-sensitive: it still accepted plausible raw identifiers such as
 * {@code member-42} or a foreign {@code tenant-999}. This type makes the readable form unrepresentable
 * instead. It stores only the {@link ResourceKind} and a canonical {@link ContentHash} of the
 * server-resolved opaque identity, so the original resource, tenant and program identifiers cannot be read
 * back from the value or from its serialized form.
 *
 * <p>The hash is computed over a domain-separated, length-prefixed tuple rather than a joined string. A
 * naive join with a separator is ambiguous when a value itself contains the separator
 * ({@code ("a:b", "c")} and {@code ("a", "b:c")} produce the same text); length prefixing makes every field
 * self-delimiting, and the leading domain tag ensures a resource fingerprint can never collide with a hash
 * computed for any other purpose, such as a redacted payload projection.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record AuditResourceFingerprint(ResourceKind kind, ContentHash hash) {

    /**
     * Domain tag that separates this hash from every other hash domain in the system.
     *
     * <p>Package-private so the pre-hash regression test can prove the separation; it is not serialized.
     */
    static final String PREIMAGE_DOMAIN = "campaign.audit.resource-fingerprint.v1";

    public AuditResourceFingerprint {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(hash, "hash");
    }

    /**
     * Deterministic factory over a server-owned resource reference.
     *
     * <p>The caller must pass a reference that was resolved server-side. The resulting fingerprint contains
     * only the kind plus the hash; the reference's resource, tenant and program identifiers are consumed to
     * compute the hash and are never retained.
     */
    public static AuditResourceFingerprint of(OwnedResourceRef resource) {
        Objects.requireNonNull(resource, "resource");
        byte[] preimage = canonicalPreimage(
                resource.kind(), resource.resourceId(), resource.tenantId(), resource.programId());
        return new AuditResourceFingerprint(resource.kind(), ContentHash.sha256(preimage));
    }

    /**
     * The exact byte representation that is hashed.
     *
     * <p>Each field is written as a 4-byte big-endian length followed by its UTF-8 bytes, in the order
     * domain, kind, resource id, tenant id, program id. The encoding is package-private for the pre-hash
     * regression test only; it neither stores nor serializes the identity.
     */
    static byte[] canonicalPreimage(
            ResourceKind kind, String resourceId, String tenantId, String programId) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            writeField(out, PREIMAGE_DOMAIN);
            writeField(out, kind.name());
            writeField(out, resourceId);
            writeField(out, tenantId);
            writeField(out, programId);
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