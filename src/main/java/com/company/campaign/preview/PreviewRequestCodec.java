package com.company.campaign.preview;

import com.company.campaign.segment.dsl.codec.SegmentDslCodec;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.error.ContractErrorCodes;
import com.company.campaign.shared.error.ContractException;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * The single strict codec for the complete {@code POST /ai/segments/preview} HTTP body.
 *
 * <p>It admits exactly two top-level fields — {@code segmentDsl} and {@code presentation} — and exactly two
 * nested {@code presentation} booleans. Before any value is bound it proves:
 * <ul>
 *   <li>the body is present and within the policy byte bound;</li>
 *   <li>the bytes decode as strict UTF-8 with no replacement and no unmappable input;</li>
 *   <li>the text is one well-formed JSON document with no duplicate object key at any nesting level;</li>
 *   <li>both top-level fields are present, are the exact documented names (no alternate casing) and are
 *       not JSON null;</li>
 *   <li>{@code presentation} carries both booleans as genuine JSON booleans;</li>
 *   <li>{@code segmentDsl} is accepted by the already-accepted strict T05 {@link SegmentDslCodec} — this
 *       codec never parses the DSL itself, so there is exactly one DSL parser.</li>
 * </ul>
 *
 * <p>Every failure is a stable bounded {@link PreviewException} that echoes no field name, payload value,
 * identifier or thrown cause. The codec is immutable and thread-safe.
 */
public final class PreviewRequestCodec {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private static final String SEGMENT_DSL = "segmentDsl";
    private static final String PRESENTATION = "presentation";
    private static final String INCLUDE_SAMPLE = "includeSample";
    private static final String INCLUDE_CONDITION_STATS = "includeConditionStats";

    private static final Set<String> TOP_LEVEL_FIELDS = Set.of(SEGMENT_DSL, PRESENTATION);
    private static final Set<String> PRESENTATION_FIELDS = Set.of(INCLUDE_SAMPLE, INCLUDE_CONDITION_STATS);

    private final int maxRequestBytes;
    private final SegmentDslCodec dslCodec;

    /**
     * Creates the codec from the server-owned policy and the one accepted strict Segment DSL codec.
     *
     * <p>The DSL codec is mandatory so that this boundary can never invent a second, weaker DSL parser.
     */
    public PreviewRequestCodec(PreviewPolicy policy, SegmentDslCodec dslCodec) {
        if (policy == null) {
            throw PreviewException.of(PreviewErrorCodes.DEPENDENCY_MISSING, "previewPolicy");
        }
        if (dslCodec == null) {
            throw PreviewException.of(PreviewErrorCodes.DEPENDENCY_MISSING, "segmentDslCodec");
        }
        this.maxRequestBytes = policy.maxRequestBytes();
        this.dslCodec = dslCodec;
    }

    /** Decodes the complete request body, or fails with one stable bounded code. */
    public PreviewRequest parse(byte[] utf8) {
        if (utf8 == null || utf8.length == 0) {
            throw PreviewException.of(PreviewErrorCodes.REQUEST_MISSING, "requestBytes");
        }
        if (utf8.length > maxRequestBytes) {
            throw PreviewException.of(PreviewErrorCodes.REQUEST_LIMIT_EXCEEDED, "requestBytes");
        }
        JsonNode root = readTree(decodeUtf8(utf8));
        if (root == null || !root.isObject()) {
            throw PreviewException.of(PreviewErrorCodes.REQUEST_SHAPE_INVALID, "request");
        }
        requireExactFields(root, TOP_LEVEL_FIELDS, "request");

        JsonNode dslNode = root.get(SEGMENT_DSL);
        JsonNode presentationNode = root.get(PRESENTATION);
        if (dslNode == null) {
            throw PreviewException.of(PreviewErrorCodes.REQUEST_MISSING_FIELD, SEGMENT_DSL);
        }
        if (presentationNode == null) {
            throw PreviewException.of(PreviewErrorCodes.REQUEST_MISSING_FIELD, PRESENTATION);
        }
        if (dslNode.isNull()) {
            throw PreviewException.of(PreviewErrorCodes.REQUEST_NULL_FIELD, SEGMENT_DSL);
        }
        if (presentationNode.isNull()) {
            throw PreviewException.of(PreviewErrorCodes.REQUEST_NULL_FIELD, PRESENTATION);
        }
        if (!dslNode.isObject()) {
            throw PreviewException.of(PreviewErrorCodes.REQUEST_SHAPE_INVALID, SEGMENT_DSL);
        }
        if (!presentationNode.isObject()) {
            throw PreviewException.of(PreviewErrorCodes.REQUEST_SHAPE_INVALID, PRESENTATION);
        }
        requireExactFields(presentationNode, PRESENTATION_FIELDS, PRESENTATION);

        PreviewPresentation presentation = new PreviewPresentation(
                requireBoolean(presentationNode, INCLUDE_SAMPLE),
                requireBoolean(presentationNode, INCLUDE_CONDITION_STATS));

        return new PreviewRequest(parseDsl(dslNode), presentation);
    }

    private SegmentDsl parseDsl(JsonNode dslNode) {
        byte[] canonical;
        try {
            canonical = CANONICAL_JSON.write(dslNode);
        } catch (ContractException exception) {
            // A node already read from strict JSON should always be writable; fail closed regardless.
            throw PreviewException.of(PreviewErrorCodes.REQUEST_DSL_INVALID, SEGMENT_DSL);
        }
        try {
            return dslCodec.parse(canonical);
        } catch (ContractException exception) {
            // The accepted T05 codec owns every DSL diagnostic; this boundary maps it to one bounded code
            // and never echoes its message, payload, node identifier or operand.
            throw PreviewException.of(PreviewErrorCodes.REQUEST_DSL_INVALID, SEGMENT_DSL);
        } catch (RuntimeException exception) {
            throw PreviewException.of(PreviewErrorCodes.REQUEST_DSL_INVALID, SEGMENT_DSL);
        }
    }

    // ------------------------------------------------------------------ shape helpers

    /**
     * Requires an object's field set to equal the allowed set exactly.
     *
     * <p>An unknown field is reported before a missing one, and the rejected field name is never echoed: an
     * attacker-controlled name must not become observable output.
     */
    private static void requireExactFields(JsonNode object, Set<String> allowed, String invariant) {
        Set<String> names = new LinkedHashSet<>();
        Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
        while (fields.hasNext()) {
            names.add(fields.next().getKey());
        }
        for (String name : names) {
            if (!allowed.contains(name)) {
                throw PreviewException.of(PreviewErrorCodes.REQUEST_UNKNOWN_FIELD, invariant);
            }
        }
        for (String name : allowed) {
            if (!names.contains(name)) {
                throw PreviewException.of(PreviewErrorCodes.REQUEST_MISSING_FIELD, name);
            }
        }
    }

    private static boolean requireBoolean(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null) {
            throw PreviewException.of(PreviewErrorCodes.REQUEST_MISSING_FIELD, field);
        }
        if (value.isNull()) {
            throw PreviewException.of(PreviewErrorCodes.REQUEST_NULL_FIELD, field);
        }
        if (!value.isBoolean()) {
            throw PreviewException.of(PreviewErrorCodes.REQUEST_SHAPE_INVALID, field);
        }
        return value.booleanValue();
    }

    // ------------------------------------------------------------------ JSON helpers

    private static JsonNode readTree(String text) {
        JsonNode tree;
        try {
            tree = CANONICAL_JSON.readTree(text);
        } catch (ContractException exception) {
            if (ContractErrorCodes.JSON_DUPLICATE_KEY.equals(exception.code())) {
                throw PreviewException.of(PreviewErrorCodes.REQUEST_DUPLICATE_KEY, "request");
            }
            throw PreviewException.of(PreviewErrorCodes.REQUEST_JSON_MALFORMED, "request");
        }
        if (tree == null || tree.isMissingNode()) {
            // Jackson returns a missing node for empty or whitespace-only input rather than throwing.
            throw PreviewException.of(PreviewErrorCodes.REQUEST_JSON_MALFORMED, "request");
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
            throw PreviewException.of(PreviewErrorCodes.REQUEST_ENCODING_INVALID, "requestBytes");
        }
    }
}