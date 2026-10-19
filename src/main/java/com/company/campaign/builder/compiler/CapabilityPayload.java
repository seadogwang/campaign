package com.company.campaign.builder.compiler;

import com.company.campaign.builder.proposal.ProposalLimits;
import com.company.campaign.builder.proposal.ProposalReservedFields;
import com.company.campaign.shared.ImmutableValues;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable, canonical, defensively frozen capability payload.
 *
 * <p>This is the only value type a capability contract ever receives for the editable part of a component.
 * It has already been proven to be one bounded JSON <em>object</em>, to be free of every reserved trusted,
 * security, runtime, plan or ChangeSet name at any nesting level, and to have exactly one canonical byte
 * form. The canonical bytes and their SHA-256 are computed once, so a payload's hash cannot drift from its
 * content and two payloads with the same bytes always hash and compare equal.
 *
 * <p>The generic T15 core never interprets labels inside a payload and defines no universal rule language.
 * Semantically ordered arrays keep their order; object keys are canonicalized. Nothing here reads a clock, a
 * locale, a random source or a mutable registry.
 *
 * <p>The value is exposed as a deeply unmodifiable map. It is not a {@code JsonNode}, not a raw JSON
 * reference and never a mutable collection: a caller can read a payload but cannot change one after it has
 * been hashed, diffed or stored in a Plan.
 */
public final class CapabilityPayload {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private static final JsonMapper CONVERTER = JsonMapper.builder()
            // Decimals keep their exact value: a payload must round-trip without binary-float drift.
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .build();

    private final Map<String, Object> fields;
    private final byte[] canonicalBytes;
    private final ContentHash contentHash;

    private CapabilityPayload(Map<String, Object> fields, byte[] canonicalBytes, ContentHash contentHash) {
        this.fields = fields;
        this.canonicalBytes = canonicalBytes;
        this.contentHash = contentHash;
    }

    /** Creates a payload from an already-built JSON-shaped object; the root must be an object. */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static CapabilityPayload of(Map<String, Object> fields) {
        Objects.requireNonNull(fields, "fields");
        return fromValue(fields);
    }

    /** Creates the one empty payload. */
    public static CapabilityPayload empty() {
        return fromValue(Map.of());
    }

    /**
     * Creates a payload from a JSON document that the strict proposal codec already parsed.
     *
     * <p>This is an ingest path, never an exposure path: the document is re-proven to be one bounded,
     * canonical, reserved-free JSON object and is then copied into an immutable snapshot. A caller cannot
     * retain a handle to mutable state through it.
     */
    public static CapabilityPayload fromJsonNode(com.fasterxml.jackson.databind.JsonNode document) {
        Objects.requireNonNull(document, "document");
        if (!document.isObject()) {
            throw new CompilerException(
                    CompilerErrorCodes.COMPILER_OUTPUT_INCONSISTENT,
                    "payloadShape",
                    "A capability payload must be a JSON object.");
        }
        return fromValue(CONVERTER.convertValue(document, MAP_TYPE));
    }

    private static CapabilityPayload fromValue(Object value) {
        byte[] canonical;
        try {
            canonical = CANONICAL_JSON.write(value);
        } catch (RuntimeException exception) {
            throw payloadInvalid("payloadCanonicalForm");
        }
        Map<String, Object> converted;
        try {
            converted = CONVERTER.readValue(canonical, MAP_TYPE);
        } catch (Exception exception) {
            throw payloadInvalid("payloadCanonicalForm");
        }
        if (converted == null) {
            throw payloadInvalid("payloadShape");
        }
        ProposalLimits limits = ProposalLimits.DEFAULT;
        limits.enforce(converted);
        if (ProposalReservedFields.containsReservedField(converted)) {
            throw new CompilerException(
                    CompilerErrorCodes.COMPILER_OUTPUT_INCONSISTENT,
                    "payloadReservedField",
                    "A capability payload must not carry a reserved trusted, security or runtime field.");
        }
        Map<String, Object> frozen = ImmutableValues.freezeMap(converted);
        // The canonical bytes are recomputed from the frozen value, so the hash covers exactly what a reader
        // can observe and never a transient representation that the conversion dropped.
        byte[] frozenBytes = CANONICAL_JSON.write(frozen);
        if (!Arrays.equals(canonical, frozenBytes)) {
            throw payloadInvalid("payloadCanonicalForm");
        }
        return new CapabilityPayload(frozen, frozenBytes, ContentHash.sha256(frozenBytes));
    }

    /** The deeply unmodifiable JSON object; never a live reference into a request body. */
    @JsonValue
    public Map<String, Object> fields() {
        return fields;
    }

    /** A defensive copy of the canonical UTF-8 bytes. */
    public byte[] canonicalBytes() {
        return canonicalBytes.clone();
    }

    /** The canonical UTF-8 text form. */
    public String canonicalText() {
        return new String(canonicalBytes, StandardCharsets.UTF_8);
    }

    /** The SHA-256 of the canonical bytes. */
    public ContentHash contentHash() {
        return contentHash;
    }

    public boolean isEmpty() {
        return fields.isEmpty();
    }

    public boolean contains(String field) {
        return field != null && fields.containsKey(field);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CapabilityPayload payload && Arrays.equals(canonicalBytes, payload.canonicalBytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(canonicalBytes);
    }

    @Override
    public String toString() {
        return "CapabilityPayload[fields=" + fields.size() + ", hash=" + contentHash + ']';
    }

    private static CompilerException payloadInvalid(String invariant) {
        return new CompilerException(
                CompilerErrorCodes.COMPILER_OUTPUT_INCONSISTENT,
                invariant,
                "A capability payload must be a bounded canonical JSON object.");
    }
}