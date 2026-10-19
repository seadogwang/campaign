package com.company.campaign.builder.refine;

import com.company.campaign.contracts.ContractVersions;
import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.segment.dsl.codec.SegmentDslCodec;
import com.company.campaign.segment.dsl.contract.SegmentDslException;
import com.company.campaign.segment.dsl.predicate.ConditionValue;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.dsl.value.TypedValue;
import com.company.campaign.segment.dsl.window.TimeWindow;
import com.company.campaign.segment.reference.AudienceVersionRef;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import com.company.campaign.shared.error.ContractErrorCodes;
import com.company.campaign.shared.error.ContractException;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * The one strict UTF-8/JSON codec of the versioned refinement patch, suitable for reading model output.
 *
 * <p>It parses UTF-8 JSON into the closed typed patch, serializes the patch to deterministic compact canonical
 * UTF-8, and rejects — with one stable machine code each — duplicate object keys, unknown fields, missing
 * required fields, present-but-null fields, alternate discriminator casing, duplicate operation identifiers,
 * invalid node identifiers, index-like paths, raw array-index JSON Patch documents, malformed or oversized
 * input, and any attempt to carry trusted context, a Metadata pin, SQL, a backend, a fixture, a sample
 * presentation flag or a Save/Publish/Runtime/executable field.
 *
 * <p>Nothing generic survives parsing: the reader produces {@link SegmentRefinementOperation} records that hold
 * only the accepted T05 {@link Predicate}, {@link TimeWindow}, {@link TypedValue} and T04
 * {@link ComparisonOperator} types. There is no retained {@code JsonNode}, no {@code Map<String, Object>} in
 * the domain model and no reflection-driven mutation.
 *
 * <p>Subtree, window and operand values are read through the accepted {@link SegmentDslCodec}, so the
 * refinement contract reuses the one T05 DSL rather than defining a second one, and every value failure is
 * mapped onto one bounded refinement code instead of leaking a generic cast or a nested message.
 *
 * <p>The codec is immutable and thread-safe.
 */
public final class SegmentRefinementPatchCodec {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();
    private static final SegmentDslCodec DSL_CODEC = new SegmentDslCodec();

    private static final Set<String> ROOT_FIELDS =
            Set.of("schemaVersion", "currentDslHash", "operations", "patchFingerprint");
    private static final Set<String> ADD_NODE_FIELDS =
            Set.of("opId", "op", "parentNodeId", "beforeNodeId", "node");
    private static final Set<String> REMOVE_NODE_FIELDS = Set.of("opId", "op", "nodeId");
    private static final Set<String> REPLACE_FIELD_FIELDS = Set.of("opId", "op", "nodeId", "field", "value");
    private static final Set<String> MOVE_NODE_FIELDS =
            Set.of("opId", "op", "nodeId", "parentNodeId", "beforeNodeId");

    /** The exact members of a reference pin replacement value: the expected pin and the new one. */
    private static final Set<String> REFERENCE_PIN_PAIR_FIELDS = Set.of("expected", "pinned");

    /** The exact members of one exact immutable audience-version pin. */
    private static final Set<String> REFERENCE_PIN_FIELDS = Set.of("audienceId", "versionId", "versionHash");

    /**
     * Normalized member names that may never appear in a refinement patch.
     *
     * <p>Matching removes separators and case, so {@code tenantId}, {@code tenant_id}, {@code TENANT-ID} and
     * {@code tenant.id} are all refused. Refusing a legitimate-looking name is the safe direction: the contract
     * is closed, so an author renames a field, whereas a missed alias would let a forged trusted fact, a
     * Metadata pin, a SQL fragment or a Save/Publish flag into the patch.
     */
    private static final Set<String> FORBIDDEN_MEMBER_NAMES = Set.of(
            // Trusted execution context and identity.
            "tenant", "tenantid", "tenantname", "program", "programid", "programname",
            "user", "userid", "username", "currentuser", "actor", "actorid", "principal", "principalid",
            "datascope", "datascopeid", "scope", "scopes", "permission", "permissions",
            "role", "roles", "authority", "authorities", "entitlement", "entitlements",
            "securitycontext", "trustedcontext", "executioncontext", "hostcontext", "context", "environment",
            "locale", "timezone", "reportingcurrency", "asof", "datawatermark", "fixtureversion",
            // Trusted Metadata pins and snapshot identity.
            "metadatasnapshot", "metadatasnapshotid", "metadatasnapshotversion", "metadatacontenthash",
            "metadataschemaversion", "metadata", "snapshot", "snapshotid", "catalogversion", "contenthash",
            // Storage, SQL and repository semantics.
            "sql", "query", "jdbc", "datasource", "database", "ddl", "dml", "table", "column", "tables",
            "backend", "backendid", "storage", "storagetable", "catalog", "repository", "entity",
            // Fixture and evaluation facts.
            "fixture", "fixtureid", "fixtureversionid", "evaluation",
            // Sample presentation flags.
            "sample", "samples", "includesample", "sampletokens", "presentation", "includeconditionstats",
            // Host-only commands and runtime activation.
            "save", "savedraft", "apply", "publish", "unpublish", "pause", "resume", "retry", "archive",
            "reject", "confirm", "approve", "activate", "activation", "runtime",
            // Executable or reflective fragments.
            "script", "expressiontext", "executable", "eval", "callback", "reflection", "invoke",
            "class", "classname", "url", "endpoint", "provider", "apikey", "credential", "credentials",
            "secret", "token",
            // Raw natural-language or model output.
            "message", "utterance", "prose", "notes", "comment");

    /**
     * Normalized member names that belong to an array-index JSON Patch document.
     *
     * <p>They are refused with their own code so a JSON Patch attempt is reported as such rather than as a
     * merely unknown field.
     */
    private static final Set<String> JSON_PATCH_MEMBER_NAMES =
            Set.of("path", "from", "to", "pointer", "paths", "rootpath", "target", "index");

    /** Parses a UTF-8 JSON refinement patch. */
    public SegmentRefinementPatch parse(String json) {
        if (json == null) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.JSON_MALFORMED, "A JSON document is required");
        }
        if (!isWellFormed(json)) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.JSON_MALFORMED, "JSON document is not well-formed Unicode text");
        }
        return parse(json.getBytes(StandardCharsets.UTF_8));
    }

    /** Parses UTF-8 JSON bytes into the closed typed refinement patch. */
    public SegmentRefinementPatch parse(byte[] utf8) {
        JsonNode root = readTree(utf8);
        requireObject(root, "the refinement patch");
        rejectForbiddenMembers(root);
        requireOnlyFields(root, ROOT_FIELDS);

        SchemaVersion schemaVersion = parseSchemaVersion(requirePresent(root, "schemaVersion"));
        ContentHash currentDslHash = parseContentHash(requirePresent(root, "currentDslHash"));
        List<SegmentRefinementOperation> operations = parseOperations(requirePresent(root, "operations"));
        SegmentRefinementPatch patch =
                SegmentRefinementPatch.publish(schemaVersion, currentDslHash, operations);
        // The fingerprint is optional on input — a model cannot compute it — but a declared one is never
        // trusted: it must be the canonical fingerprint of exactly this material.
        JsonNode declared = root.get("patchFingerprint");
        if (declared != null) {
            if (declared.isNull()) {
                throw SegmentRefinementException.of(
                        SegmentRefinementErrorCodes.NULL_FIELD,
                        "An optional field must be omitted, never null");
            }
            if (!patch.patchFingerprint().equals(
                    parseContentHash(declared, SegmentRefinementErrorCodes.FINGERPRINT_MISMATCH))) {
                throw SegmentRefinementException.of(
                        SegmentRefinementErrorCodes.FINGERPRINT_MISMATCH,
                        "A declared patch fingerprint must be the canonical fingerprint of its material");
            }
        }
        return patch;
    }

    /** Serializes a patch to deterministic compact canonical UTF-8 bytes. */
    public byte[] write(SegmentRefinementPatch patch) {
        if (patch == null) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.NULL_FIELD, "A refinement patch is required");
        }
        return CANONICAL_JSON.write(SegmentRefinementWire.root(patch));
    }

    /** Serializes a patch to deterministic compact canonical JSON text. */
    public String writeString(SegmentRefinementPatch patch) {
        return new String(write(patch), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ operations

    private List<SegmentRefinementOperation> parseOperations(JsonNode node) {
        if (!node.isArray()) {
            throw shape("A refinement patch requires an operations array");
        }
        if (node.size() < RefinementLimits.MIN_OPERATIONS || node.size() > RefinementLimits.MAX_OPERATIONS) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.LIMIT_EXCEEDED,
                    "A refinement patch carries a bounded, non-empty operation list");
        }
        List<SegmentRefinementOperation> operations = new ArrayList<>(node.size());
        for (JsonNode element : node) {
            operations.add(parseOperation(element));
        }
        return operations;
    }

    private SegmentRefinementOperation parseOperation(JsonNode node) {
        requireObject(node, "a refinement operation");
        rejectForbiddenMembers(node);
        JsonNode opNode = requirePresent(node, "op");
        String opWire = text(opNode);
        if (SegmentRefinementWire.isJsonPatchVerb(opWire)) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.JSON_PATCH_REJECTED,
                    "An array-index JSON Patch operation is not a stable-node refinement");
        }
        RefinementOpKind kind = RefinementOpKind.fromWire(opWire);
        return switch (kind) {
            case ADD_NODE -> parseAddNode(node);
            case REMOVE_NODE -> parseRemoveNode(node);
            case REPLACE_FIELD -> parseReplaceField(node);
            case MOVE_NODE -> parseMoveNode(node);
        };
    }

    private SegmentRefinementOperation parseAddNode(JsonNode node) {
        requireOnlyFields(node, ADD_NODE_FIELDS);
        String operationId = text(requirePresent(node, "opId"));
        String parentNodeId = text(requirePresent(node, "parentNodeId"));
        String beforeNodeId = optionalText(node, "beforeNodeId");
        Predicate subtree = parsePredicate(requirePresent(node, "node"));
        return new SegmentRefinementOperation.AddNode(operationId, parentNodeId, beforeNodeId, subtree);
    }

    private SegmentRefinementOperation parseRemoveNode(JsonNode node) {
        requireOnlyFields(node, REMOVE_NODE_FIELDS);
        return new SegmentRefinementOperation.RemoveNode(
                text(requirePresent(node, "opId")), text(requirePresent(node, "nodeId")));
    }

    private SegmentRefinementOperation parseReplaceField(JsonNode node) {
        requireOnlyFields(node, REPLACE_FIELD_FIELDS);
        String operationId = text(requirePresent(node, "opId"));
        String nodeId = text(requirePresent(node, "nodeId"));
        RefinementField field = RefinementField.fromWire(text(requirePresent(node, "field")));
        JsonNode valueNode = requirePresent(node, "value");
        RefinementReplacement replacement = switch (field.valueKind()) {
            case WINDOW -> new RefinementReplacement.WindowReplacement(parseWindow(valueNode));
            case OPERATOR -> new RefinementReplacement.OperatorReplacement(parseOperator(valueNode));
            case CONDITION -> new RefinementReplacement.ConditionReplacement(parseCondition(valueNode));
            case REFERENCE_PIN -> parseReferencePins(valueNode);
        };
        return new SegmentRefinementOperation.ReplaceField(operationId, nodeId, field, replacement);
    }

    private SegmentRefinementOperation parseMoveNode(JsonNode node) {
        requireOnlyFields(node, MOVE_NODE_FIELDS);
        String operationId = text(requirePresent(node, "opId"));
        String nodeId = text(requirePresent(node, "nodeId"));
        String parentNodeId = text(requirePresent(node, "parentNodeId"));
        String beforeNodeId = optionalText(node, "beforeNodeId");
        return new SegmentRefinementOperation.MoveNode(operationId, nodeId, parentNodeId, beforeNodeId);
    }

    // ------------------------------------------------------------------ reused T05 wire values

    private Predicate parsePredicate(JsonNode node) {
        try {
            return DSL_CODEC.readPredicate(CANONICAL_JSON.writeString(node));
        } catch (SegmentDslException rejected) {
            // The nested T05 failure keeps its own stable code but never travels with its message or payload.
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.FIELD_VALUE_INVALID,
                    "The added subtree is not a valid typed T05 predicate");
        } catch (ContractException rejected) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.FIELD_VALUE_INVALID,
                    "The added subtree is not a valid typed T05 predicate");
        }
    }

    private TimeWindow parseWindow(JsonNode node) {
        requireObject(node, "a time-window replacement");
        try {
            return DSL_CODEC.readWindow(CANONICAL_JSON.writeString(node));
        } catch (SegmentDslException rejected) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.FIELD_VALUE_INVALID,
                    "A window replacement is not a valid typed T05 time window");
        } catch (ContractException rejected) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.FIELD_VALUE_INVALID,
                    "A window replacement is not a valid typed T05 time window");
        }
    }

    private TypedValue parseCondition(JsonNode node) {
        requireObject(node, "a value replacement");
        try {
            return DSL_CODEC.readValue(CANONICAL_JSON.writeString(node));
        } catch (SegmentDslException rejected) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.FIELD_VALUE_INVALID,
                    "A value replacement is not a valid typed T05 value");
        } catch (ContractException rejected) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.FIELD_VALUE_INVALID,
                    "A value replacement is not a valid typed T05 value");
        }
    }

    private ComparisonOperator parseOperator(JsonNode node) {
        if (!node.isTextual()) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.FIELD_VALUE_INVALID,
                    "An operator replacement must be a JSON string naming a comparison operator");
        }
        String name = node.textValue();
        for (ComparisonOperator operator : ComparisonOperator.values()) {
            if (operator.name().equals(name)) {
                return operator;
            }
        }
        throw SegmentRefinementException.of(
                SegmentRefinementErrorCodes.FIELD_VALUE_INVALID,
                "An operator replacement is not a known comparison operator");
    }

    // ------------------------------------------------------------------ reference pins

    /**
     * Reads the expected/new exact pin pair of one reference re-pin.
     *
     * <p>The pair is a closed object of exactly two pins, each of exactly the three immutable parts the
     * reference core publishes. There is no free-form member, so a patch cannot carry a name, a query, an owner,
     * a program, a DataScope, a permission, a dependency or a Gate fact beside the pins, and it cannot express a
     * {@code current} or {@code latest} pointer.
     */
    private RefinementReplacement.ReferencePinReplacement parseReferencePins(JsonNode node) {
        requireObject(node, "a reference pin replacement");
        rejectForbiddenMembers(node);
        requireOnlyFields(node, REFERENCE_PIN_PAIR_FIELDS);
        return new RefinementReplacement.ReferencePinReplacement(
                parsePin(requirePresent(node, "expected")), parsePin(requirePresent(node, "pinned")));
    }

    private static AudienceVersionRef parsePin(JsonNode node) {
        requireObject(node, "an exact audience-version pin");
        rejectForbiddenMembers(node);
        requireOnlyFields(node, REFERENCE_PIN_FIELDS);
        try {
            return new AudienceVersionRef(
                    text(requirePresent(node, "audienceId")),
                    text(requirePresent(node, "versionId")),
                    new ContentHash(text(requirePresent(node, "versionHash"))));
        } catch (SegmentRefinementException rejected) {
            throw rejected;
        } catch (RuntimeException rejected) {
            // The rejected part is caller-controlled and may carry a payload value, so it is never echoed.
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.FIELD_VALUE_INVALID,
                    "A reference pin requires bounded audience, version and hash parts");
        }
    }

    // ------------------------------------------------------------------ shape helpers

    private SchemaVersion parseSchemaVersion(JsonNode node) {
        String value = text(node);
        SchemaVersion version;
        try {
            version = SchemaVersion.parse(value);
        } catch (RuntimeException rejected) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.SCHEMA_VERSION_UNSUPPORTED,
                    "A refinement patch requires its exact schema version");
        }
        if (!ContractVersions.isCurrent(version)) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.SCHEMA_VERSION_UNSUPPORTED,
                    "A refinement patch requires the exact current schema version");
        }
        return version;
    }

    private ContentHash parseContentHash(JsonNode node) {
        return parseContentHash(node, SegmentRefinementErrorCodes.BASE_HASH_MISMATCH);
    }

    private ContentHash parseContentHash(JsonNode node, String code) {
        try {
            return new ContentHash(text(node));
        } catch (RuntimeException rejected) {
            throw SegmentRefinementException.of(code, "A canonical content hash is required");
        }
    }

    private static void rejectForbiddenMembers(JsonNode object) {
        for (Iterator<String> names = object.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            String normalized = normalize(name);
            if (JSON_PATCH_MEMBER_NAMES.contains(normalized)) {
                throw SegmentRefinementException.of(
                        SegmentRefinementErrorCodes.JSON_PATCH_REJECTED,
                        "An array-index JSON Patch member is not a stable-node refinement");
            }
            if (RefinementIdentifiers.isPositionalPath(name)) {
                throw SegmentRefinementException.of(
                        SegmentRefinementErrorCodes.INDEX_PATH_REJECTED,
                        "A positional member name is not a stable identity");
            }
            if (FORBIDDEN_MEMBER_NAMES.contains(normalized)) {
                // The rejected name is caller-controlled and may carry a secret or a payload value, so it is
                // never echoed; only the stable category is reported.
                throw SegmentRefinementException.of(
                        SegmentRefinementErrorCodes.FORBIDDEN_FIELD,
                        "A field that is not part of this contract is present");
            }
        }
    }

    private static void requireOnlyFields(JsonNode node, Set<String> allowed) {
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!allowed.contains(name)) {
                throw SegmentRefinementException.of(
                        SegmentRefinementErrorCodes.UNKNOWN_FIELD,
                        "A field that is not part of this shape is present");
            }
        }
    }

    private static JsonNode requirePresent(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.MISSING_FIELD, "A required field is missing");
        }
        if (value.isNull()) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.NULL_FIELD, "A required field must not be null");
        }
        return value;
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null) {
            return null;
        }
        if (value.isNull()) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.NULL_FIELD, "An optional field must be omitted, never null");
        }
        return text(value);
    }

    private static String text(JsonNode node) {
        if (node == null || !node.isTextual()) {
            throw shape("Expected a JSON string");
        }
        return node.textValue();
    }

    private static void requireObject(JsonNode node, String what) {
        if (node == null || !node.isObject()) {
            throw shape("Expected a JSON object for " + what);
        }
    }

    private static SegmentRefinementException shape(String message) {
        return SegmentRefinementException.of(SegmentRefinementErrorCodes.SHAPE_INVALID, message);
    }

    private static String normalize(String name) {
        StringBuilder normalized = new StringBuilder(name.length());
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            if (Character.isLetterOrDigit(character)) {
                normalized.append(Character.toLowerCase(character));
            }
        }
        return normalized.toString();
    }

    /** True when the text contains no unpaired surrogate. */
    private static boolean isWellFormed(String text) {
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(index + 1))) {
                    return false;
                }
                index++;
            } else if (Character.isLowSurrogate(character)) {
                return false;
            }
        }
        return true;
    }

    private static JsonNode readTree(byte[] utf8) {
        if (utf8 == null || utf8.length == 0) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.JSON_MALFORMED, "A JSON document is required");
        }
        if (utf8.length > RefinementLimits.MAX_INPUT_BYTES) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.LIMIT_EXCEEDED, "Refinement patch input exceeds the byte limit");
        }
        JsonNode tree;
        try {
            tree = CANONICAL_JSON.readTree(decodeUtf8(utf8));
        } catch (ContractException rejected) {
            // No cause is attached: the generic JSON failure and its source snippet could echo the payload.
            if (ContractErrorCodes.JSON_DUPLICATE_KEY.equals(rejected.code())) {
                throw SegmentRefinementException.of(
                        SegmentRefinementErrorCodes.JSON_DUPLICATE_KEY,
                        "JSON document contains a duplicate key");
            }
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.JSON_MALFORMED, "JSON document is malformed");
        }
        if (tree == null || tree.isMissingNode()) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.JSON_MALFORMED, "JSON document is empty");
        }
        return tree;
    }

    /**
     * Decodes UTF-8 strictly, refusing malformed and unmappable input.
     *
     * <p>A replacing decoder would turn an invalid byte sequence into U+FFFD and thereby accept a byte payload
     * that is not valid UTF-8 as a different, valid-looking patch. The rejected bytes are never echoed.
     */
    private static String decodeUtf8(byte[] utf8) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(utf8)).toString();
        } catch (CharacterCodingException rejected) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.JSON_MALFORMED, "JSON document is not valid UTF-8");
        }
    }
}