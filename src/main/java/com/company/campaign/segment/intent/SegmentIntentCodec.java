package com.company.campaign.segment.intent;

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
 * The single strict codec for the structured Intent document.
 *
 * <p>It reuses the project-wide canonical JSON policy rather than introducing a second {@code ObjectMapper}
 * configuration: duplicate keys, unknown fields, non-well-formed Unicode and malformed values are refused by
 * the shared boundary and mapped onto stable Intent codes. Reading is grounded, not free-standing: parsing
 * requires the original utterance and the injected default registry, so a span that does not match the
 * utterance and a default the registry does not hold both fail closed before an Intent exists.
 *
 * <p>Writing goes through the same wire projection the hasher uses, so canonical bytes and the Intent hash
 * agree. The codec is immutable and thread-safe and never constructs a Segment DSL.
 */
public final class SegmentIntentCodec {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();
    private static final SegmentIntentParser PARSER = new SegmentIntentParser();

    /** Parses UTF-8 JSON text, grounding spans in the utterance and verifying cited defaults. */
    public SegmentIntent parse(String json, String utterance, RegisteredDefaultSource defaults) {
        if (json == null) {
            throw IntentException.invalid(
                    IntentErrorCodes.JSON_MALFORMED, "An Intent JSON document is required");
        }
        if (utterance == null) {
            throw IntentException.invalid(
                    IntentErrorCodes.UTTERANCE_MISSING, "The original utterance is required to parse an Intent");
        }
        if (!IntentTexts.isWellFormed(json)) {
            throw IntentException.invalid(
                    IntentErrorCodes.JSON_MALFORMED, "The Intent document is not well-formed Unicode text");
        }
        return parse(json.getBytes(StandardCharsets.UTF_8), utterance, defaults);
    }

    /** Parses UTF-8 JSON bytes, grounding spans in the utterance and verifying cited defaults. */
    public SegmentIntent parse(byte[] utf8, String utterance, RegisteredDefaultSource defaults) {
        if (utterance == null) {
            throw IntentException.invalid(
                    IntentErrorCodes.UTTERANCE_MISSING, "The original utterance is required to parse an Intent");
        }
        return PARSER.parseRoot(readTree(utf8), utterance, defaults);
    }

    /** Serializes an Intent to deterministic compact canonical UTF-8 bytes. */
    public byte[] write(SegmentIntent intent) {
        if (intent == null) {
            throw IntentException.invalid(
                    IntentErrorCodes.SHAPE_INVALID, "An Intent is required");
        }
        return CANONICAL_JSON.write(SegmentIntentWire.root(intent));
    }

    /** Serializes an Intent to deterministic compact canonical JSON text. */
    public String writeString(SegmentIntent intent) {
        return new String(write(intent), StandardCharsets.UTF_8);
    }

    private static JsonNode readTree(byte[] utf8) {
        if (utf8 == null) {
            throw IntentException.invalid(
                    IntentErrorCodes.JSON_MALFORMED, "An Intent JSON document is required");
        }
        if (utf8.length > IntentLimits.MAX_INPUT_BYTES) {
            throw new IntentException(
                    IntentErrorCodes.LIMIT_EXCEEDED, "The Intent input exceeds the byte limit");
        }
        JsonNode tree;
        try {
            tree = CANONICAL_JSON.readTree(decodeUtf8(utf8));
        } catch (ContractException exception) {
            // No cause is attached: the generic JSON failure and its source snippet could echo the payload.
            if (ContractErrorCodes.JSON_DUPLICATE_KEY.equals(exception.code())) {
                throw IntentException.invalid(
                        IntentErrorCodes.JSON_DUPLICATE_KEY, "The Intent document repeats a key");
            }
            throw IntentException.invalid(
                    IntentErrorCodes.JSON_MALFORMED, "The Intent document is malformed");
        }
        if (tree == null || tree.isMissingNode()) {
            throw IntentException.invalid(
                    IntentErrorCodes.JSON_MALFORMED, "The Intent document is empty");
        }
        return tree;
    }

    private static String decodeUtf8(byte[] utf8) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(utf8)).toString();
        } catch (CharacterCodingException exception) {
            throw IntentException.invalid(
                    IntentErrorCodes.JSON_MALFORMED, "The Intent document is not valid UTF-8");
        }
    }
}