package com.company.campaign.builder.segment;

import com.company.campaign.segment.intent.IntentErrorCodes;
import com.company.campaign.segment.intent.IntentException;
import com.company.campaign.segment.intent.RegisteredDefaultSource;
import com.company.campaign.segment.intent.SegmentIntent;
import com.company.campaign.segment.intent.SegmentIntentCodec;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * The fail-closed guard between a raw model response and the strict T10 Intent codec.
 *
 * <p>It refuses a null, empty, oversized, non-UTF-8, Markdown-fenced or prose-wrapped response before the codec
 * ever sees it, and maps every strict-codec failure onto one stable {@link SegmentAdapterErrorCodes} code. It
 * never echoes any part of the response, and it grounds every source span against the exact utterance because
 * the strict codec does that as part of parsing.
 */
public final class SegmentModelOutputGuard {

    private final SegmentIntentCodec codec = new SegmentIntentCodec();
    private final CanonicalJson json = new CanonicalJson();

    /**
     * Decodes and strict-parses one raw response against the exact utterance and registered defaults.
     *
     * @throws SegmentAdapterException with one stable adapter code when the response fails closed
     */
    public SegmentIntent parse(byte[] raw, String utterance, RegisteredDefaultSource defaults) {
        if (raw == null) {
            throw SegmentAdapterException.invalid(
                    SegmentAdapterErrorCodes.OUTPUT_MISSING, "The model response is missing");
        }
        if (raw.length == 0) {
            throw SegmentAdapterException.invalid(
                    SegmentAdapterErrorCodes.OUTPUT_EMPTY, "The model response is empty");
        }
        if (raw.length > SegmentModelLimits.MAX_MODEL_OUTPUT_BYTES) {
            throw SegmentAdapterException.invalid(
                    SegmentAdapterErrorCodes.OUTPUT_OVERSIZED, "The model response exceeds the size limit");
        }
        String text = decodeUtf8(raw);
        String trimmed = text.strip();
        if (trimmed.isEmpty()) {
            throw SegmentAdapterException.invalid(
                    SegmentAdapterErrorCodes.OUTPUT_EMPTY, "The model response is empty");
        }
        if (trimmed.startsWith("```") || trimmed.contains("```")) {
            throw SegmentAdapterException.invalid(
                    SegmentAdapterErrorCodes.OUTPUT_FENCED,
                    "The model response must be one raw JSON object, not a Markdown fence");
        }
        if (trimmed.charAt(0) != '{') {
            throw SegmentAdapterException.invalid(
                    SegmentAdapterErrorCodes.OUTPUT_PROSE,
                    "The model response must be one raw JSON object, not prose");
        }
        try {
            return codec.parse(raw, utterance, defaults);
        } catch (IntentException failure) {
            IntentException effectiveFailure = failure;
            if (IntentErrorCodes.SPAN_TEXT_MISMATCH.equals(failure.code())) {
                try {
                    return codec.parse(groundUniqueSourceSpans(trimmed, utterance), utterance, defaults);
                } catch (IntentException repairedFailure) {
                    effectiveFailure = repairedFailure;
                } catch (RuntimeException ignored) {
                    effectiveFailure = failure;
                }
            }
            throw SegmentAdapterException.invalid(
                    mapCode(effectiveFailure.code()), "The model response is not a valid Intent");
        }
    }

    private byte[] groundUniqueSourceSpans(String modelJson, String utterance) {
        JsonNode root = json.readTree(modelJson);
        if (!groundUniqueSourceSpans(root, utterance)) {
            return modelJson.getBytes(StandardCharsets.UTF_8);
        }
        return json.write(root);
    }

    private static boolean groundUniqueSourceSpans(JsonNode node, String utterance) {
        boolean changed = false;
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            JsonNode type = object.get("type");
            JsonNode textNode = object.get("text");
            JsonNode startNode = object.get("start");
            JsonNode endNode = object.get("end");
            if (type != null && type.isTextual() && "SOURCE_SPAN".equals(type.textValue())
                    && textNode != null && textNode.isTextual()
                    && startNode != null && startNode.canConvertToInt()
                    && endNode != null && endNode.canConvertToInt()) {
                String text = textNode.textValue();
                int start = startNode.intValue();
                int end = endNode.intValue();
                if (!matches(utterance, text, start, end)) {
                    int uniqueStart = uniqueIndexOf(utterance, text);
                    if (uniqueStart >= 0) {
                        object.put("start", uniqueStart);
                        object.put("end", uniqueStart + text.length());
                        changed = true;
                    }
                }
            }
            var fields = object.fields();
            while (fields.hasNext()) {
                changed |= groundUniqueSourceSpans(fields.next().getValue(), utterance);
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                changed |= groundUniqueSourceSpans(element, utterance);
            }
        }
        return changed;
    }

    private static boolean matches(String utterance, String text, int start, int end) {
        return start >= 0 && end >= start && end <= utterance.length()
                && end - start == text.length()
                && utterance.regionMatches(start, text, 0, text.length());
    }

    private static int uniqueIndexOf(String utterance, String text) {
        if (text.isEmpty()) {
            return -1;
        }
        int first = utterance.indexOf(text);
        if (first < 0 || utterance.indexOf(text, first + 1) >= 0) {
            return -1;
        }
        return first;
    }

    private static String decodeUtf8(byte[] raw) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw))
                    .toString();
        } catch (CharacterCodingException failure) {
            throw SegmentAdapterException.invalid(
                    SegmentAdapterErrorCodes.OUTPUT_NOT_UTF8, "The model response is not valid UTF-8");
        }
    }

    private static String mapCode(String intentCode) {
        return switch (intentCode) {
            case IntentErrorCodes.UNKNOWN_FIELD -> SegmentAdapterErrorCodes.OUTPUT_UNKNOWN_FIELD;
            case IntentErrorCodes.RESERVED_FIELD -> SegmentAdapterErrorCodes.OUTPUT_RESERVED_FIELD;
            case IntentErrorCodes.JSON_MALFORMED, IntentErrorCodes.JSON_DUPLICATE_KEY ->
                    SegmentAdapterErrorCodes.OUTPUT_MALFORMED;
            case IntentErrorCodes.LIMIT_EXCEEDED -> SegmentAdapterErrorCodes.OUTPUT_OVERSIZED;
            case IntentErrorCodes.WINDOW_INVALID -> SegmentAdapterErrorCodes.OUTPUT_WINDOW_INVALID;
            case IntentErrorCodes.SHAPE_INVALID,
                    IntentErrorCodes.RELATION_SCOPE_INVALID,
                    IntentErrorCodes.SCOPE_FILTER_INVALID,
                    IntentErrorCodes.VALUE_INVALID,
                    IntentErrorCodes.CURRENCY_INVALID ->
                    SegmentAdapterErrorCodes.OUTPUT_SHAPE_INVALID;
            case IntentErrorCodes.PROVENANCE_INVALID,
                    IntentErrorCodes.SPAN_MISSING,
                    IntentErrorCodes.SPAN_EMPTY,
                    IntentErrorCodes.SPAN_OUT_OF_RANGE,
                    IntentErrorCodes.SPAN_SURROGATE_SPLIT,
                    IntentErrorCodes.SPAN_TEXT_MISMATCH ->
                    SegmentAdapterErrorCodes.OUTPUT_PROVENANCE_INVALID;
            default -> SegmentAdapterErrorCodes.OUTPUT_INVALID;
        };
    }
}
