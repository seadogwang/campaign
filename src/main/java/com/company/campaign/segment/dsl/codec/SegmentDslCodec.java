package com.company.campaign.segment.dsl.codec;

import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.dsl.contract.SegmentDslErrorCodes;
import com.company.campaign.segment.dsl.contract.SegmentDslException;
import com.company.campaign.segment.dsl.contract.SegmentDslLimits;
import com.company.campaign.segment.dsl.contract.UnicodeTexts;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.dsl.value.TypedValue;
import com.company.campaign.segment.dsl.window.TimeWindow;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.error.ContractErrorCodes;
import com.company.campaign.shared.error.ContractException;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * The single strict codec for the editable Segment DSL.
 *
 * <p>It parses UTF-8 JSON to the typed root, serializes to deterministic compact canonical JSON, and rejects
 * duplicate keys, unknown fields, unknown discriminators and malformed scalar shapes with stable machine
 * codes. Writing goes through {@link SegmentDslWire} and T01 {@link CanonicalJson}, so canonical object-key
 * ordering, array order, plain-decimal notation and UTF-8 output are inherited rather than re-implemented.
 *
 * <p>Input bytes and predicate nesting are bounded before any allocation or recursion; Jackson global default
 * typing is never enabled, and any generic canonical-JSON failure is mapped onto a Segment DSL code without
 * echoing payload content. Parsing verifies {@code editableDslHash}; publishing computes it elsewhere.
 *
 * <p>The codec is immutable and thread-safe.
 */
public final class SegmentDslCodec {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();
    private static final SegmentDslParser PARSER = new SegmentDslParser();

    /** Parses a UTF-8 JSON document, verifying its declared {@code editableDslHash}. */
    public SegmentDsl parse(String json) {
        if (json == null) {
            throw new SegmentDslException(SegmentDslErrorCodes.JSON_MALFORMED, "A JSON document is required");
        }
        // Encoding an unpaired surrogate to UTF-8 would replace it, so the text must be well formed first.
        if (!UnicodeTexts.isWellFormed(json)) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.JSON_MALFORMED, "JSON document is not well-formed Unicode text");
        }
        return parse(json.getBytes(StandardCharsets.UTF_8));
    }

    /** Parses UTF-8 JSON bytes, verifying the declared {@code editableDslHash}. */
    public SegmentDsl parse(byte[] utf8) {
        return PARSER.parseRoot(readTree(utf8));
    }

    /** Serializes a document to deterministic compact canonical UTF-8 bytes. */
    public byte[] write(SegmentDsl document) {
        if (document == null) {
            throw new SegmentDslException(SegmentDslErrorCodes.SHAPE_INVALID, "A Segment DSL document is required");
        }
        return CANONICAL_JSON.write(SegmentDslWire.root(document));
    }

    /** Serializes a document to deterministic compact canonical JSON text. */
    public String writeString(SegmentDsl document) {
        return new String(write(document), StandardCharsets.UTF_8);
    }

    /** Parses one TypedValue wire form. */
    public TypedValue readValue(String json) {
        return PARSER.parseValue(readTree(json));
    }

    /** Serializes one TypedValue to canonical JSON text. */
    public String writeValueString(TypedValue value) {
        if (value == null) {
            throw new SegmentDslException(SegmentDslErrorCodes.TYPED_VALUE_INVALID, "A typed value is required");
        }
        return new String(CANONICAL_JSON.write(SegmentDslWire.value(value)), StandardCharsets.UTF_8);
    }

    /** Parses one TimeWindow wire form. */
    public TimeWindow readWindow(String json) {
        return PARSER.parseWindow(readTree(json));
    }

    /** Serializes one TimeWindow to canonical JSON text. */
    public String writeWindowString(TimeWindow window) {
        if (window == null) {
            throw new SegmentDslException(SegmentDslErrorCodes.TIME_WINDOW_INVALID, "A time window is required");
        }
        return new String(CANONICAL_JSON.write(SegmentDslWire.window(window)), StandardCharsets.UTF_8);
    }

    /** Parses one predicate subtree. */
    public Predicate readPredicate(String json) {
        return PARSER.parsePredicate(readTree(json), 1);
    }

    /** Serializes one predicate subtree to canonical JSON text. */
    public String writePredicateString(Predicate predicate) {
        if (predicate == null) {
            throw new SegmentDslException(SegmentDslErrorCodes.SHAPE_INVALID, "A predicate is required");
        }
        return new String(CANONICAL_JSON.write(SegmentDslWire.predicate(predicate)), StandardCharsets.UTF_8);
    }

    private static JsonNode readTree(String json) {
        if (json == null) {
            throw new SegmentDslException(SegmentDslErrorCodes.JSON_MALFORMED, "A JSON document is required");
        }
        // Encoding an unpaired surrogate to UTF-8 would replace it, so the text must be well formed first.
        if (!UnicodeTexts.isWellFormed(json)) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.JSON_MALFORMED, "JSON document is not well-formed Unicode text");
        }
        return readTree(json.getBytes(StandardCharsets.UTF_8));
    }

    private static JsonNode readTree(byte[] utf8) {
        if (utf8 == null) {
            throw new SegmentDslException(SegmentDslErrorCodes.JSON_MALFORMED, "A JSON document is required");
        }
        if (utf8.length > SegmentDslLimits.MAX_INPUT_BYTES) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.LIMIT_EXCEEDED, "Segment DSL input exceeds the byte limit");
        }
        JsonNode tree;
        try {
            tree = CANONICAL_JSON.readTree(decodeUtf8(utf8));
        } catch (ContractException exception) {
            // No cause is attached: the generic JSON failure and its source snippet could echo the payload.
            if (ContractErrorCodes.JSON_DUPLICATE_KEY.equals(exception.code())) {
                throw new SegmentDslException(
                        SegmentDslErrorCodes.JSON_DUPLICATE_KEY, "JSON document contains a duplicate key");
            }
            throw new SegmentDslException(SegmentDslErrorCodes.JSON_MALFORMED, "JSON document is malformed");
        }
        if (tree == null || tree.isMissingNode()) {
            // Jackson returns a missing node for empty input rather than throwing.
            throw new SegmentDslException(SegmentDslErrorCodes.JSON_MALFORMED, "JSON document is empty");
        }
        return tree;
    }

    /**
     * Decodes UTF-8 strictly, refusing malformed and unmappable input.
     *
     * <p>A replacing decoder would turn an invalid byte sequence into U+FFFD and thereby accept a byte payload
     * that is not valid UTF-8 as a different, valid-looking document. The rejected bytes are never echoed.
     */
    private static String decodeUtf8(byte[] utf8) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(utf8)).toString();
        } catch (CharacterCodingException exception) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.JSON_MALFORMED, "JSON document is not valid UTF-8");
        }
    }
}