package com.company.campaign.preview;

import com.company.campaign.security.data.SafeDerivedValue;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * The mandatory server-owned opaque sample hasher.
 *
 * <p>A sample is never an unsalted hash of a raw member identifier, because a raw-id hash is enumerable by
 * anyone who can guess identifiers. This hasher keyed-HMACs a domain-separated canonical material that binds
 * the trusted tenant, program and DataScope together with the raw member identifier, then renders the digest
 * in the canonical {@code sha256:<64 lowercase hex>} form and validates it through
 * {@link SafeDerivedValue#memberIdHash(ContentHash)}. Raw identifiers and key bytes therefore never leave this
 * class as values: only the bounded opaque hash does.
 *
 * <p>The key is defensively copied on construction, is never exposed by an accessor, and is never included in
 * any canonical material, log line or {@link #toString()}. A key shorter than 256 bits is rejected, because a
 * short key would weaken the enumeration resistance the hasher exists to provide.
 */
public final class PreviewSampleHasher {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    /** Domain separator, so this key use can never be replayed against another protocol. */
    private static final String DOMAIN = "campaign.preview.sample.v1";

    private static final String ALGORITHM = "HmacSHA256";
    private static final int MIN_KEY_BYTES = 32;

    private final byte[] key;

    /** Copies and validates the server-owned key; the caller keeps no shared reference to it. */
    public PreviewSampleHasher(byte[] key) {
        if (key == null || key.length < MIN_KEY_BYTES) {
            throw PreviewException.of(PreviewErrorCodes.DEPENDENCY_MISSING, "sampleKey");
        }
        this.key = key.clone();
    }

    /**
     * The deterministic opaque sample hash of one member under one trusted context.
     *
     * <p>The same key, tenant, program, DataScope and member always produce the same value; a different key,
     * context or member produces a different value.
     */
    public SafeDerivedValue hash(String tenantId, String programId, String dataScopeId, String memberId) {
        if (tenantId == null || programId == null || dataScopeId == null || memberId == null) {
            throw PreviewException.of(PreviewErrorCodes.INTERNAL_FAILURE, "sampleInput");
        }
        byte[] material = CANONICAL_JSON.write(
                new SampleDomainMaterial(DOMAIN, tenantId, programId, dataScopeId, memberId));
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            String rendered = CanonicalJson.HASH_PREFIX + HexFormat.of().formatHex(mac.doFinal(material));
            return SafeDerivedValue.memberIdHash(new ContentHash(rendered));
        } catch (GeneralSecurityException exception) {
            throw PreviewException.of(PreviewErrorCodes.INTERNAL_FAILURE, "sampleHasher");
        }
    }

    /** Never renders the key: only the algorithm and the domain separator are safe to describe. */
    @Override
    public String toString() {
        return "PreviewSampleHasher[" + ALGORITHM + ", domain=" + DOMAIN + ']';
    }

    /** The exact, domain-separated material one sample hash covers. */
    private record SampleDomainMaterial(
            String domain,
            String tenantId,
            String programId,
            String dataScopeId,
            String memberId) {
    }
}