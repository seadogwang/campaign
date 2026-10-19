package com.company.campaign.shared.canonical;

import com.company.campaign.shared.error.ContractErrorCodes;
import com.company.campaign.shared.error.ContractException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Deterministic canonical JSON and content hashing for the base contracts.
 *
 * <p>Guarantees, in the order they are applied:
 * <ol>
 *   <li>object keys are recursively sorted by UTF-16 code unit (RFC 8785 ordering);</li>
 *   <li>array order is preserved exactly, because arrays are semantically ordered in this system;</li>
 *   <li>temporal values are written as ISO-8601 text, never as epoch numbers;</li>
 *   <li>numeric values are normalized to plain decimal notation and never use scientific notation;</li>
 *   <li>output is compact UTF-8 without BOM and without insignificant whitespace;</li>
 *   <li>reading rejects duplicate object keys, unknown DTO fields and malformed values with stable codes.</li>
 * </ol>
 *
 * <p>Nothing here injects the current time, a random identifier, a locale default or an incidental map
 * iteration order, so {@code hash(value)} is reproducible across processes and machines.
 *
 * <p>This class deliberately performs only <em>generic</em> canonicalization. Semantic normalization that
 * belongs to a specific contract (enum code casing, currency tables, operator aliases, exclusion of
 * display-only fields) is owned by that contract and must be applied before calling this utility.
 *
 * <p>Instances are immutable and thread-safe; a single shared instance is sufficient.
 */
public final class CanonicalJson {

    public static final String HASH_PREFIX = "sha256:";

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final ObjectMapper mapper;

    public CanonicalJson() {
        this.mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                // Duplicate keys must fail before any DTO binding sees only the last occurrence.
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                // Plain notation only: 1E+3 and 1E-9 must never appear in canonical output.
                .configure(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN, true)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }

    /** Serializes a value to canonical UTF-8 JSON bytes. */
    public byte[] write(Object value) {
        Objects.requireNonNull(value, "value");
        JsonNode tree;
        try {
            tree = mapper.valueToTree(value);
        } catch (IllegalArgumentException exception) {
            throw new CanonicalJsonException(
                    ContractErrorCodes.JSON_NON_CANONICAL_VALUE,
                    "Value cannot be converted to JSON: " + exception.getMessage(),
                    exception);
        }
        try {
            return mapper.writeValueAsBytes(normalize(tree));
        } catch (JsonProcessingException exception) {
            throw new CanonicalJsonException(
                    ContractErrorCodes.JSON_NON_CANONICAL_VALUE,
                    "Value cannot be written as canonical JSON",
                    exception);
        }
    }

    /** Serializes a value to canonical JSON text; the bytes are always UTF-8. */
    public String writeString(Object value) {
        return new String(write(value), StandardCharsets.UTF_8);
    }

    /** Canonical content hash of a value. */
    public ContentHash hash(Object value) {
        return ContentHash.sha256(write(value));
    }

    /** Parses JSON, rejecting duplicate object keys. */
    public JsonNode readTree(String json) {
        Objects.requireNonNull(json, "json");
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw classifyParseFailure(exception);
        }
    }

    /** Strictly deserializes a contract from JSON. */
    public <T> T read(String json, Class<T> type) {
        Objects.requireNonNull(type, "type");
        JsonNode tree = readTree(json);
        try {
            return mapper.treeToValue(tree, type);
        } catch (JsonProcessingException exception) {
            throw classifyBindingFailure(exception, type.getSimpleName());
        }
    }

    /**
     * Strictly deserializes a generic contract, such as an envelope around a typed payload.
     *
     * <p>Use this overload when the contract's type argument carries meaning of its own — for example
     * {@code ToolResultEnvelope<Map<String, Object>>}, whose result payload must keep the shape the caller
     * asked for. Reading such a contract with the erased class binds the payload to a plain map and
     * silently drops that.
     */
    public <T> T read(String json, TypeReference<T> type) {
        Objects.requireNonNull(json, "json");
        Objects.requireNonNull(type, "type");
        JsonNode tree = readTree(json);
        try {
            return mapper.readerFor(type).readValue(tree);
        } catch (JsonProcessingException exception) {
            throw classifyBindingFailure(exception, type.getType().getTypeName());
        } catch (IOException exception) {
            throw new CanonicalJsonException(
                    ContractErrorCodes.CONTRACT_INVALID_VALUE,
                    "Cannot read contract " + type.getType().getTypeName(),
                    exception);
        }
    }

    private JsonNode normalize(JsonNode node) {
        if (node.isObject()) {
            // Sort by key so map insertion order and JSON field order cannot change the bytes.
            Map<String, JsonNode> sorted = new TreeMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> fields = node.fields(); fields.hasNext(); ) {
                Map.Entry<String, JsonNode> field = fields.next();
                sorted.put(field.getKey(), normalize(field.getValue()));
            }
            ObjectNode normalized = NODES.objectNode();
            sorted.forEach(normalized::set);
            return normalized;
        }
        if (node.isArray()) {
            // Arrays keep their order: position is semantic for windows, lists and ordered node collections.
            ArrayNode normalized = NODES.arrayNode(node.size());
            node.forEach(element -> normalized.add(normalize(element)));
            return normalized;
        }
        if (node.isNumber()) {
            return normalizeNumber(node);
        }
        return node;
    }

    private JsonNode normalizeNumber(JsonNode node) {
        if (node.isIntegralNumber()) {
            return node;
        }
        if (node.isDouble() || node.isFloat()) {
            double raw = node.doubleValue();
            if (Double.isNaN(raw) || Double.isInfinite(raw)) {
                throw new CanonicalJsonException(
                        ContractErrorCodes.JSON_NON_CANONICAL_VALUE,
                        "NaN and Infinity are not valid canonical JSON numbers");
            }
        }
        BigDecimal decimal;
        try {
            decimal = node.decimalValue();
        } catch (NumberFormatException exception) {
            throw new CanonicalJsonException(
                    ContractErrorCodes.JSON_NON_CANONICAL_VALUE,
                    "Number cannot be represented as a decimal",
                    exception);
        }
        if (decimal.signum() == 0) {
            // Collapses 0, 0.0, -0.0 and 0E-7 to a single representation.
            return NODES.numberNode(BigDecimal.ZERO);
        }
        BigDecimal stripped = decimal.stripTrailingZeros();
        if (stripped.scale() < 0) {
            // 1E+3 becomes 1000; a negative scale would otherwise invite scientific notation.
            stripped = stripped.setScale(0);
        }
        return NODES.numberNode(stripped);
    }

    private ContractException classifyParseFailure(JsonProcessingException exception) {
        String message = exception.getMessage() == null ? "" : exception.getMessage();
        if (message.contains("Duplicate field")) {
            return new CanonicalJsonException(
                    ContractErrorCodes.JSON_DUPLICATE_KEY,
                    "JSON object contains a duplicate key",
                    exception);
        }
        return new CanonicalJsonException(
                ContractErrorCodes.JSON_MALFORMED,
                "JSON document is malformed",
                exception);
    }

    private ContractException classifyBindingFailure(JsonProcessingException exception, String target) {
        if (exception instanceof UnrecognizedPropertyException unrecognized) {
            return new CanonicalJsonException(
                    ContractErrorCodes.CONTRACT_UNKNOWN_FIELD,
                    "Unknown field '" + unrecognized.getPropertyName() + "' is not part of contract " + target,
                    exception);
        }
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ContractException contractException) {
                // A contract value rejected itself: keep its own stable code and structured details.
                return contractException;
            }
        }
        Throwable root = rootCause(exception);
        if (root instanceof IllegalArgumentException
                || root instanceof DateTimeException
                || root instanceof ArithmeticException) {
            return new CanonicalJsonException(
                    ContractErrorCodes.CONTRACT_INVALID_VALUE,
                    "Invalid value in contract " + target + ": " + root.getMessage(),
                    exception);
        }
        return new CanonicalJsonException(
                ContractErrorCodes.CONTRACT_INVALID_VALUE,
                "Cannot read contract " + target,
                exception);
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}