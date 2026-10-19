package com.company.campaign.builder.authoring;

import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.segment.dsl.codec.SegmentDslCodec;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.dsl.contract.SegmentDslLimits;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The closed, versioned capability payload envelope a resolved {@code segment.rule-based} Segment is
 * submitted as.
 *
 * <p>It is the only shape the contract accepts, and it is deliberately an <em>opaque</em> carrier: the
 * complete strict T05 Segment DSL — whose root legitimately names a {@code subject} — is embedded as one
 * bounded base64 string rather than as a nested JSON object. That keeps the DSL outside the reserved-name
 * screen (which must never be weakened to admit the DSL's own field names) while still preserving the whole
 * executable document, because the field is decoded only through the accepted strict {@link SegmentDslCodec}.
 *
 * <p>Parsing is strict and closed: exactly the six known keys, each a bounded string of the declared shape.
 * An absent, unknown, mistyped or unbounded field fails closed with one stable code and never echoes a value.
 *
 * <p>The envelope carries only the closed <em>semantic</em> material of a resolved Segment — its format
 * version, the opaque canonical DSL wire and the four already-derived proof hashes. It deliberately carries no
 * trusted time pin ({@code asOf}, {@code timezone}, {@code dataWatermark}), no matched count, no condition
 * statistic, no sample, no readiness and no Gate fact: the trusted time pins and every other host-owned fact
 * reach the contract only from the accepted {@code TrustedExecutionContext} and the frozen compile inputs, and
 * the active boundary states that server timestamps are never model/client Proposal fields. Reserving those
 * three names is what makes their appearance anywhere in a Proposal a rejection rather than a silent claim.
 *
 * <p>Because the submission payload and the normalized component payload are written by the one
 * {@link #payloadOf} writer, the two can never drift into two slightly different semantic field sets.
 *
 * <p>The type is package-private so it never becomes part of the public capability contract surface.
 */
final class SegmentAuthoringEnvelope {

    /** The closed set of envelope field names. None of them is a reserved name. */
    static final String FIELD_VERSION = "envelopeVersion";
    static final String FIELD_DSL_WIRE = "dslWire";
    static final String FIELD_CANONICAL_AST_HASH = "canonicalAstHash";
    static final String FIELD_SEMANTIC_PLAN_HASH = "semanticPlanHash";
    static final String FIELD_PREVIEW_HASH = "previewHash";
    static final String FIELD_SKILL_HASH = "skillHash";

    private static final Set<String> FIELDS = Set.of(
            FIELD_VERSION, FIELD_DSL_WIRE, FIELD_CANONICAL_AST_HASH, FIELD_SEMANTIC_PLAN_HASH,
            FIELD_PREVIEW_HASH, FIELD_SKILL_HASH);

    /**
     * The largest base64 text that can carry one accepted DSL document.
     *
     * <p>The bound is derived from the accepted T05 input bound before any decode, so a hostile envelope can
     * never make the contract allocate more than one decoded document's worth of bytes.
     */
    private static final int MAX_WIRE_LENGTH =
            ((SegmentDslLimits.MAX_INPUT_BYTES + 2) / 3) * 4;

    /** The one stateless strict T05 codec, so the produced wire is exactly the accepted canonical form. */
    private static final SegmentDslCodec CODEC = new SegmentDslCodec();

    private final String version;
    private final String dslWire;
    private final ContentHash canonicalAstHash;
    private final ContentHash semanticPlanHash;
    private final ContentHash previewHash;
    private final ContentHash skillHash;

    private SegmentAuthoringEnvelope(
            String version,
            String dslWire,
            ContentHash canonicalAstHash,
            ContentHash semanticPlanHash,
            ContentHash previewHash,
            ContentHash skillHash) {
        this.version = version;
        this.dslWire = dslWire;
        this.canonicalAstHash = canonicalAstHash;
        this.semanticPlanHash = semanticPlanHash;
        this.previewHash = previewHash;
        this.skillHash = skillHash;
    }

    /** The closed set of accepted envelope field names, so a test can prove none of them is reserved. */
    static Set<String> fieldNames() {
        return FIELDS;
    }

    String version() {
        return version;
    }

    ContentHash canonicalAstHash() {
        return canonicalAstHash;
    }

    ContentHash semanticPlanHash() {
        return semanticPlanHash;
    }

    ContentHash previewHash() {
        return previewHash;
    }

    ContentHash skillHash() {
        return skillHash;
    }

    /**
     * Decodes the embedded DSL strictly and requires the wire to be exactly its canonical form.
     *
     * <p>Decoding goes only through {@link SegmentDslCodec}, which proves the declared editable hash from the
     * document's own material. Requiring the re-serialized bytes to equal the submitted bytes means two
     * spellings of one document can never produce two artifacts, and a non-canonical wire fails closed.
     */
    SegmentDsl decodeDsl(SegmentDslCodec codec) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(dslWire);
        } catch (IllegalArgumentException exception) {
            throw new SegmentAuthoringException(
                    SegmentAuthoringErrorCodes.DSL_INVALID,
                    "dslWire",
                    "The embedded Segment DSL wire is not a bounded base64 document.");
        }
        if (decoded.length > SegmentDslLimits.MAX_INPUT_BYTES) {
            throw new SegmentAuthoringException(
                    SegmentAuthoringErrorCodes.DSL_INVALID,
                    "dslWire",
                    "The embedded Segment DSL wire exceeds the accepted input bound.");
        }
        SegmentDsl document;
        try {
            document = codec.parse(decoded);
        } catch (RuntimeException exception) {
            // The rejected document and the thrown cause are never attached to the failure.
            throw new SegmentAuthoringException(
                    SegmentAuthoringErrorCodes.DSL_INVALID,
                    "dslWire",
                    "The embedded Segment DSL wire is not an accepted canonical document.");
        }
        byte[] canonical;
        try {
            canonical = codec.write(document);
        } catch (RuntimeException exception) {
            throw new SegmentAuthoringException(
                    SegmentAuthoringErrorCodes.DSL_INVALID,
                    "dslWire",
                    "The embedded Segment DSL document could not be re-serialized.");
        }
        if (!Arrays.equals(canonical, decoded)) {
            throw new SegmentAuthoringException(
                    SegmentAuthoringErrorCodes.DSL_INVALID,
                    "dslWire",
                    "The embedded Segment DSL wire is not the canonical form of its document.");
        }
        return document;
    }

    /** The exact canonical base64 wire one already-decoded document normalizes to. */
    static String wireOf(SegmentDslCodec codec, SegmentDsl document) {
        return Base64.getEncoder().encodeToString(codec.write(document));
    }

    /**
     * The accepted editable DSL hash of one already-normalized resolved Segment component payload.
     *
     * <p>It is the honest way to read a Segment's current resolved DSL out of the authoritative component the
     * trusted Current campaign carries: the payload is re-proved to be the closed envelope and its embedded
     * wire is decoded only through the accepted strict T05 codec, so a foreign, malformed or non-Segment
     * payload fails closed instead of yielding a hash. An EDIT or a refinement base is compared against it.
     */
    static ContentHash dslHashOf(CapabilityPayload payload) {
        return parse(payload).decodeDsl(CODEC).editableDslHash();
    }

    /**
     * Writes the one canonical payload of an already-proved resolved Segment component.
     *
     * <p>This is the single place the envelope's <em>semantic</em> material is produced, so the shared
     * authoring integration and the capability contract can never drift into two slightly different payload
     * shapes: a submitted Proposal payload and the normalized component payload are literally the same writer
     * over the same closed semantic field set. The material is exactly the envelope version, the opaque
     * canonical DSL wire and the four accepted hashes — and nothing else. A natural-language prompt, a provider
     * or model output, a clarification answer, an audit trace, a matched count, a sample, a raw member
     * identifier, SQL, a trusted identity, a trusted time pin, a readiness fact or a Gate fact is not
     * representable in it.
     */
    static CapabilityPayload payloadOf(
            SegmentDsl document,
            ContentHash canonicalAstHash,
            ContentHash semanticPlanHash,
            ContentHash previewHash,
            ContentHash skillHash) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(FIELD_VERSION, SegmentAuthoringVersions.ENVELOPE_FORMAT_VERSION);
        fields.put(FIELD_DSL_WIRE, wireOf(CODEC, document));
        fields.put(FIELD_CANONICAL_AST_HASH, canonicalAstHash.value());
        fields.put(FIELD_SEMANTIC_PLAN_HASH, semanticPlanHash.value());
        fields.put(FIELD_PREVIEW_HASH, previewHash.value());
        fields.put(FIELD_SKILL_HASH, skillHash.value());
        return CapabilityPayload.of(fields);
    }

    /** Parses one payload into the one closed envelope shape, or fails closed with a stable code. */
    static SegmentAuthoringEnvelope parse(CapabilityPayload payload) {
        Map<String, Object> fields = payload.fields();
        Set<String> unknown = new LinkedHashSet<>(fields.keySet());
        unknown.removeAll(FIELDS);
        if (!unknown.isEmpty() || fields.size() != FIELDS.size()) {
            // An unknown name is never printed: it is attacker-controlled input. A client that declares a
            // trusted time pin, a readiness fact or any other host-owned name is refused here as unknown.
            throw new SegmentAuthoringException(
                    SegmentAuthoringErrorCodes.ENVELOPE_INVALID,
                    "envelopeFields",
                    "A Segment capability payload must carry exactly the known envelope fields.");
        }
        String version = requireString(fields, FIELD_VERSION);
        if (!SegmentAuthoringVersions.ENVELOPE_FORMAT_VERSION.equals(version)) {
            throw new SegmentAuthoringException(
                    SegmentAuthoringErrorCodes.ENVELOPE_INVALID,
                    "envelopeVersion",
                    "A Segment capability payload must declare the supported envelope version.");
        }
        String wire = requireString(fields, FIELD_DSL_WIRE);
        if (wire.isEmpty() || wire.length() > MAX_WIRE_LENGTH) {
            throw new SegmentAuthoringException(
                    SegmentAuthoringErrorCodes.ENVELOPE_INVALID,
                    "envelopeDslWire",
                    "A Segment capability payload must carry a bounded DSL wire field.");
        }
        return new SegmentAuthoringEnvelope(
                version,
                wire,
                requireHash(fields, FIELD_CANONICAL_AST_HASH),
                requireHash(fields, FIELD_SEMANTIC_PLAN_HASH),
                requireHash(fields, FIELD_PREVIEW_HASH),
                requireHash(fields, FIELD_SKILL_HASH));
    }

    private static String requireString(Map<String, Object> fields, String key) {
        Object value = fields.get(key);
        if (!(value instanceof String text)) {
            throw new SegmentAuthoringException(
                    SegmentAuthoringErrorCodes.ENVELOPE_INVALID,
                    "envelopeValue",
                    "A Segment capability payload field must be a bounded string.");
        }
        return text;
    }

    private static ContentHash requireHash(Map<String, Object> fields, String key) {
        try {
            return new ContentHash(requireString(fields, key));
        } catch (RuntimeException exception) {
            throw new SegmentAuthoringException(
                    SegmentAuthoringErrorCodes.ENVELOPE_INVALID,
                    "envelopeHash",
                    "A Segment capability payload pin must be a canonical content hash.");
        }
    }
}