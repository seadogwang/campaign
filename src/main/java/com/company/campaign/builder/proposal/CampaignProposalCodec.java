package com.company.campaign.builder.proposal;

import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.builder.compiler.CompilerException;
import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.contracts.LocaleTag;
import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.journey.graph.JourneyBranchKind;
import com.company.campaign.journey.graph.JourneyTopology;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The one strict versioned codec for {@code CampaignProposalPatch}.
 *
 * <p>Two Proposal versions are readable. The legacy {@code 1.3} shape is accepted only when it carries no
 * {@code journeyTopology} member at all — a present member, including an explicit JSON null, fails closed
 * with {@link ProposalErrorCodes#PROPOSAL_TOPOLOGY_SCHEMA_VERSION_UNSUPPORTED}. The current {@code 1.4} shape
 * is the only one that may carry the optional member.
 *
 * <p>Before any value is bound it proves that the request is present, within its byte/depth/width budget,
 * strict UTF-8, one well-formed JSON document with no duplicate object key at any nesting level, and that
 * every structural object carries only the fields its position allows — no unknown name, no alternate
 * casing, no missing required name, no JSON {@code null} where a value is required. Enum-valued fields
 * accept only their exact canonical spelling.
 *
 * <p>Inside every capability payload it additionally refuses the reserved trusted, security, runtime, plan
 * and ChangeSet names at any nesting level, so a model or Visual Builder cannot smuggle a tenant,
 * membership, permission, authorization decision, revision override, lifecycle state, plan artifact,
 * storage identifier, timestamp, credential or Apply/Publish flag into the authoring document.
 *
 * <p>Every failure is a stable bounded {@link ProposalException} whose message and details are fixed: no
 * payload value, field name, identifier or thrown cause is ever echoed. The codec is immutable and
 * thread-safe.
 */
public final class CampaignProposalCodec {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);

    private static final Set<String> TOP_LEVEL_FIELDS = Set.of(
            "schemaVersion", "mode", "campaignId", "baseRevisions", "operations", "clientContext",
            "journeyTopology");
    private static final Set<String> BASE_REVISION_FIELDS =
            Set.of("campaignRevision", "catalogVersion", "catalogHash", "policyVersion", "policyHash");
    private static final Set<String> OPERATION_FIELDS =
            Set.of("op", "capability", "logicalId", "clientRef", "value", "before", "after");
    private static final Set<String> CLIENT_CONTEXT_FIELDS = Set.of("locale");
    private static final Set<String> TOPOLOGY_FIELDS = Set.of("action", "topologyFormatVersion", "edges");
    private static final Set<String> TOPOLOGY_EDGE_FIELDS = Set.of("edgeId", "from", "to", "branch");
    private static final Set<String> ENDPOINT_FIELDS = Set.of("logicalId", "clientRef");

    private static final long MAX_CAMPAIGN_REVISION = 1_000_000_000L;

    private final ProposalLimits limits;

    public CampaignProposalCodec() {
        this(ProposalLimits.DEFAULT);
    }

    public CampaignProposalCodec(ProposalLimits limits) {
        if (limits == null) {
            throw new IllegalArgumentException("limits is required");
        }
        this.limits = limits;
    }

    /** Decodes the complete wire body, or fails with one stable bounded code. */
    public CampaignProposalPatch parse(byte[] utf8) {
        if (utf8 == null || utf8.length == 0) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_MISSING);
        }
        if (utf8.length > limits.maxRequestBytes()) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_LIMIT_EXCEEDED);
        }
        JsonNode root = readTree(decodeUtf8(utf8));
        enforceLimits(root);
        if (!root.isObject()) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_SHAPE_INVALID);
        }
        requireKnownFields(root, TOP_LEVEL_FIELDS);

        SchemaVersion schemaVersion = readSchemaVersion(root);
        ProposalMode mode = readMode(root);
        String campaignId = readCampaignId(root, mode);
        ProposalBaseRevisions baseRevisions = readBaseRevisions(root, mode);
        List<ProposalOperation> operations = readOperations(root);
        ProposalClientContext clientContext = readClientContext(root);
        JourneyTopologyMutation journeyTopology = readJourneyTopology(root);

        try {
            return new CampaignProposalPatch(
                    schemaVersion, mode, campaignId, baseRevisions, operations, clientContext, journeyTopology);
        } catch (ContractException exception) {
            // The model's own constructor re-proves every invariant; keep its stable bounded code.
            throw exception;
        } catch (RuntimeException exception) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_SHAPE_INVALID);
        }
    }

    /** Decodes the complete wire body from text. */
    public CampaignProposalPatch parse(String json) {
        if (json == null) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_MISSING);
        }
        return parse(json.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ structural readers

    private static SchemaVersion readSchemaVersion(JsonNode root) {
        String text = requireText(requirePresent(root, "schemaVersion"));
        try {
            SchemaVersion version = SchemaVersion.parse(text);
            if (!ProposalSchemaVersions.isSupported(version)) {
                throw ProposalException.of(ProposalErrorCodes.PROPOSAL_SCHEMA_VERSION_UNSUPPORTED);
            }
            if (ProposalSchemaVersions.isLegacy(version) && root.has("journeyTopology")) {
                // The published 1.3 shape has no journeyTopology member in any form, including an explicit
                // JSON null: reading one under 1.3 would silently reinterpret an accepted strict version.
                throw ProposalException.of(
                        ProposalErrorCodes.PROPOSAL_TOPOLOGY_SCHEMA_VERSION_UNSUPPORTED);
            }
            return version;
        } catch (ProposalException exception) {
            throw exception;
        } catch (ContractException exception) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_SCHEMA_VERSION_UNSUPPORTED);
        } catch (RuntimeException exception) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_SCHEMA_VERSION_UNSUPPORTED);
        }
    }

    private static ProposalMode readMode(JsonNode root) {
        ProposalMode mode = ProposalMode.fromWireName(requireText(requirePresent(root, "mode")));
        if (mode == null) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_MODE_INVALID);
        }
        return mode;
    }

    private static String readCampaignId(JsonNode root, ProposalMode mode) {
        JsonNode node = root.get("campaignId");
        if (mode == ProposalMode.CREATE) {
            // CREATE must not carry a campaign identifier at all, not even a null one.
            if (node != null) {
                throw ProposalException.of(ProposalErrorCodes.PROPOSAL_CAMPAIGN_ID_INVALID);
            }
            return null;
        }
        if (node == null) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_MISSING_FIELD);
        }
        if (node.isNull()) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_NULL_FIELD);
        }
        String campaignId = requireText(node);
        if (!IDENTIFIER.matcher(campaignId).matches()) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_CAMPAIGN_ID_INVALID);
        }
        return campaignId;
    }

    private static ProposalBaseRevisions readBaseRevisions(JsonNode root, ProposalMode mode) {
        JsonNode node = requirePresent(root, "baseRevisions");
        if (!node.isObject()) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_BASE_REVISIONS_INVALID);
        }
        requireKnownFields(node, BASE_REVISION_FIELDS);

        JsonNode revisionNode = node.get("campaignRevision");
        Long campaignRevision = null;
        if (mode == ProposalMode.CREATE) {
            if (revisionNode != null) {
                throw ProposalException.of(ProposalErrorCodes.PROPOSAL_BASE_REVISIONS_INVALID);
            }
        } else {
            if (revisionNode == null) {
                throw ProposalException.of(ProposalErrorCodes.PROPOSAL_MISSING_FIELD);
            }
            if (revisionNode.isNull()) {
                throw ProposalException.of(ProposalErrorCodes.PROPOSAL_NULL_FIELD);
            }
            if (!revisionNode.isIntegralNumber() || !revisionNode.canConvertToLong()) {
                throw ProposalException.of(ProposalErrorCodes.PROPOSAL_BASE_REVISIONS_INVALID);
            }
            long value = revisionNode.longValue();
            if (value < 1 || value > MAX_CAMPAIGN_REVISION) {
                throw ProposalException.of(ProposalErrorCodes.PROPOSAL_BASE_REVISIONS_INVALID);
            }
            campaignRevision = value;
        }

        try {
            return new ProposalBaseRevisions(
                    campaignRevision,
                    requireIdentifierText(requirePresent(node, "catalogVersion")),
                    requireContentHash(requirePresent(node, "catalogHash")),
                    requireIdentifierText(requirePresent(node, "policyVersion")),
                    requireContentHash(requirePresent(node, "policyHash")));
        } catch (ProposalException exception) {
            throw exception;
        } catch (ContractException exception) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_BASE_REVISIONS_INVALID);
        } catch (RuntimeException exception) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_BASE_REVISIONS_INVALID);
        }
    }

    private static List<ProposalOperation> readOperations(JsonNode root) {
        JsonNode node = requirePresent(root, "operations");
        if (!node.isArray()) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_OPERATIONS_INVALID);
        }
        List<ProposalOperation> operations = new ArrayList<>(node.size());
        for (JsonNode element : node) {
            operations.add(readOperation(element));
        }
        return operations;
    }

    private static ProposalOperation readOperation(JsonNode element) {
        if (!element.isObject()) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_SHAPE_INVALID);
        }
        requireKnownFields(element, OPERATION_FIELDS);
        ProposalOperationKind kind = ProposalOperationKind.fromWireName(requireText(requirePresent(element, "op")));
        if (kind == null) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_SHAPE_INVALID);
        }

        String capability = readOptionalText(element, "capability");
        if (kind == ProposalOperationKind.UPSERT && !element.has("capability")) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_MISSING_FIELD);
        }
        ProposalIdentity target = readIdentity(element);
        CapabilityPayload value = readValue(element, kind);
        ProposalMoveAnchor anchor = readAnchor(element, kind);

        try {
            return new ProposalOperation(kind, capability, target, value, anchor);
        } catch (ProposalException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_SHAPE_INVALID);
        }
    }

    private static ProposalIdentity readIdentity(JsonNode element) {
        boolean hasLogicalId = element.has("logicalId");
        boolean hasClientRef = element.has("clientRef");
        if (hasLogicalId == hasClientRef) {
            // Exactly one identity form must be present; a present-but-null form is not an identity.
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_IDENTITY_INVALID);
        }
        if (hasLogicalId) {
            String logicalId = requireIdentifierText(requirePresent(element, "logicalId"));
            return ProposalIdentity.ofLogicalId(logicalId);
        }
        String clientRef = requireIdentifierText(requirePresent(element, "clientRef"));
        return ProposalIdentity.ofClientRef(clientRef);
    }

    private static CapabilityPayload readValue(JsonNode element, ProposalOperationKind kind) {
        if (kind != ProposalOperationKind.UPSERT) {
            if (element.has("value")) {
                throw ProposalException.of(ProposalErrorCodes.PROPOSAL_SHAPE_INVALID);
            }
            return null;
        }
        JsonNode valueNode = requirePresent(element, "value");
        if (!valueNode.isObject()) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_SHAPE_INVALID);
        }
        try {
            return CapabilityPayload.fromJsonNode(valueNode);
        } catch (CompilerException exception) {
            throw ProposalException.of(mapPayloadFailure(exception));
        } catch (ContractException exception) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_PAYLOAD_INVALID);
        } catch (RuntimeException exception) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_PAYLOAD_INVALID);
        }
    }

    private static String mapPayloadFailure(CompilerException exception) {
        return "payloadReservedField".equals(exception.details().get("invariant"))
                ? ProposalErrorCodes.PROPOSAL_RESERVED_FIELD
                : ProposalErrorCodes.PROPOSAL_PAYLOAD_INVALID;
    }

    private static ProposalMoveAnchor readAnchor(JsonNode element, ProposalOperationKind kind) {
        boolean hasBefore = element.has("before");
        boolean hasAfter = element.has("after");
        if (kind != ProposalOperationKind.MOVE) {
            if (hasBefore || hasAfter) {
                throw ProposalException.of(ProposalErrorCodes.PROPOSAL_SHAPE_INVALID);
            }
            return null;
        }
        if (hasBefore == hasAfter) {
            // Exactly one stable relative anchor must be present; an array index is not representable.
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_ANCHOR_INVALID);
        }
        String field = hasBefore ? "before" : "after";
        MoveAnchorKind anchorKind = hasBefore ? MoveAnchorKind.BEFORE : MoveAnchorKind.AFTER;
        String anchorValue = requireIdentifierText(requirePresent(element, field));
        try {
            return new ProposalMoveAnchor(anchorKind, anchorValue);
        } catch (ProposalException exception) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_ANCHOR_INVALID);
        }
    }

    private static ProposalClientContext readClientContext(JsonNode root) {
        JsonNode node = root.get("clientContext");
        if (node == null) {
            return ProposalClientContext.EMPTY;
        }
        if (node.isNull()) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_NULL_FIELD);
        }
        if (!node.isObject()) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_CLIENT_CONTEXT_INVALID);
        }
        requireKnownFields(node, CLIENT_CONTEXT_FIELDS);
        JsonNode localeNode = node.get("locale");
        if (localeNode == null) {
            return ProposalClientContext.EMPTY;
        }
        if (localeNode.isNull()) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_NULL_FIELD);
        }
        String localeText = requireText(localeNode);
        try {
            return new ProposalClientContext(new LocaleTag(localeText));
        } catch (ContractException exception) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_CLIENT_CONTEXT_INVALID);
        } catch (RuntimeException exception) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_CLIENT_CONTEXT_INVALID);
        }
    }

    // ------------------------------------------------------------------ Journey topology mutation

    /**
     * Reads the one optional, strictly typed Journey-topology mutation, or returns null when it is absent.
     *
     * <p>Absence is the inherit/never-clear path, so it is reported as "no mutation" rather than as an empty
     * one. A present object is closed: its action decides which of the other fields may appear, so a removal
     * that also carries edges, or a replacement that omits its version, is refused rather than reinterpreted.
     */
    private static JourneyTopologyMutation readJourneyTopology(JsonNode root) {
        JsonNode node = root.get("journeyTopology");
        if (node == null) {
            return null;
        }
        if (node.isNull()) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_NULL_FIELD);
        }
        if (!node.isObject()) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_TOPOLOGY_INVALID);
        }
        requireKnownFields(node, TOPOLOGY_FIELDS);
        JourneyTopologyAction action = JourneyTopologyAction.fromWireName(
                requireText(requirePresent(node, "action")));
        if (action == null) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_TOPOLOGY_INVALID);
        }
        try {
            return switch (action) {
                case REPLACE -> new JourneyTopologyMutation(
                        action,
                        requireText(requirePresent(node, "topologyFormatVersion")),
                        readTopologyEdges(node));
                case REMOVE -> {
                    // A removal may name no version and no edge; anything else is contradictory material.
                    if (node.has("topologyFormatVersion") || node.has("edges")) {
                        throw ProposalException.of(ProposalErrorCodes.PROPOSAL_TOPOLOGY_INVALID);
                    }
                    yield new JourneyTopologyMutation(action, null, List.of());
                }
            };
        } catch (ProposalException exception) {
            throw exception;
        } catch (ContractException exception) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_TOPOLOGY_INVALID);
        } catch (RuntimeException exception) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_TOPOLOGY_INVALID);
        }
    }

    private static List<ProposalTopologyEdge> readTopologyEdges(JsonNode topology) {
        JsonNode node = requirePresent(topology, "edges");
        if (!node.isArray() || node.isEmpty()) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_TOPOLOGY_LIMIT_EXCEEDED);
        }
        if (node.size() > JourneyTopology.MAX_EDGES) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_TOPOLOGY_LIMIT_EXCEEDED);
        }
        List<ProposalTopologyEdge> edges = new ArrayList<>(node.size());
        for (JsonNode element : node) {
            edges.add(readTopologyEdge(element));
        }
        return edges;
    }

    private static ProposalTopologyEdge readTopologyEdge(JsonNode element) {
        if (!element.isObject()) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_TOPOLOGY_INVALID);
        }
        requireKnownFields(element, TOPOLOGY_EDGE_FIELDS);
        String edgeId = requireIdentifierText(requirePresent(element, "edgeId"));
        ProposalIdentity from = readEndpoint(requirePresent(element, "from"));
        ProposalIdentity to = readEndpoint(requirePresent(element, "to"));
        JourneyBranchKind branch = JourneyBranchKind.fromWireName(
                requireText(requirePresent(element, "branch")));
        if (branch == null) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_TOPOLOGY_INVALID);
        }
        return new ProposalTopologyEdge(edgeId, from, to, branch);
    }

    private static ProposalIdentity readEndpoint(JsonNode node) {
        if (!node.isObject()) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_TOPOLOGY_ENDPOINT_INVALID);
        }
        requireKnownFields(node, ENDPOINT_FIELDS);
        boolean hasLogicalId = node.has("logicalId");
        boolean hasClientRef = node.has("clientRef");
        if (hasLogicalId == hasClientRef) {
            // Exactly one identity form must be present; a present-but-null form is not an identity.
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_TOPOLOGY_ENDPOINT_INVALID);
        }
        if (hasLogicalId) {
            return ProposalIdentity.ofLogicalId(requireIdentifierText(requirePresent(node, "logicalId")));
        }
        return ProposalIdentity.ofClientRef(requireIdentifierText(requirePresent(node, "clientRef")));
    }

    // ------------------------------------------------------------------ shape helpers

    /**
     * Requires an object to carry no field outside {@code allowed}.
     *
     * <p>The offending name is never echoed: an attacker-controlled name must not become observable output.
     */
    private static void requireKnownFields(JsonNode object, Set<String> allowed) {
        Set<String> names = new LinkedHashSet<>();
        Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
        while (fields.hasNext()) {
            names.add(fields.next().getKey());
        }
        for (String name : names) {
            if (!allowed.contains(name)) {
                throw ProposalException.of(ProposalErrorCodes.PROPOSAL_SHAPE_INVALID);
            }
        }
    }

    private static JsonNode requirePresent(JsonNode object, String field) {
        JsonNode node = object.get(field);
        if (node == null) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_MISSING_FIELD);
        }
        if (node.isNull()) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_NULL_FIELD);
        }
        return node;
    }

    private static String requireText(JsonNode node) {
        if (!node.isTextual()) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_SHAPE_INVALID);
        }
        return node.textValue();
    }

    private static String requireIdentifierText(JsonNode node) {
        String text = requireText(node);
        if (!IDENTIFIER.matcher(text).matches()) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_IDENTITY_INVALID);
        }
        return text;
    }

    private static String readOptionalText(JsonNode object, String field) {
        JsonNode node = object.get(field);
        if (node == null) {
            return null;
        }
        return requireText(requirePresent(object, field));
    }

    private static ContentHash requireContentHash(JsonNode node) {
        String text = requireText(node);
        try {
            return new ContentHash(text);
        } catch (ContractException exception) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_BASE_REVISIONS_INVALID);
        }
    }

    // ------------------------------------------------------------------ JSON and limit helpers

    private JsonNode readTree(String text) {
        JsonNode tree;
        try {
            tree = CANONICAL_JSON.readTree(text);
        } catch (ContractException exception) {
            if (ContractErrorCodes.JSON_DUPLICATE_KEY.equals(exception.code())) {
                throw ProposalException.of(ProposalErrorCodes.PROPOSAL_DUPLICATE_KEY);
            }
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_JSON_MALFORMED);
        }
        if (tree == null || tree.isMissingNode()) {
            // Jackson returns a missing node for empty or whitespace-only input rather than throwing.
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_JSON_MALFORMED);
        }
        return tree;
    }

    /**
     * Decodes UTF-8 strictly, refusing malformed and unmappable input.
     *
     * <p>A replacing decoder would turn an invalid byte sequence into U+FFFD and thereby accept a byte
     * payload that is not valid UTF-8 as a different, valid-looking document. The rejected bytes are never
     * echoed.
     */
    private static String decodeUtf8(byte[] utf8) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(utf8)).toString();
        } catch (CharacterCodingException exception) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_ENCODING_INVALID);
        }
    }

    private void enforceLimits(JsonNode root) {
        int bytes;
        try {
            bytes = CANONICAL_JSON.write(root).length;
        } catch (RuntimeException exception) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_SHAPE_INVALID);
        }
        if (bytes > limits.maxRequestBytes()) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_LIMIT_EXCEEDED);
        }
        enforceDepth(root, 1);
        if (root.has("operations") && root.get("operations").isArray()
                && root.get("operations").size() > limits.maxOperations()) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_LIMIT_EXCEEDED);
        }
    }

    private void enforceDepth(JsonNode node, int depth) {
        if (depth > limits.maxDepth()) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_LIMIT_EXCEEDED);
        }
        if (node.isObject() || node.isArray()) {
            if (node.size() > limits.maxCollectionSize()) {
                throw ProposalException.of(ProposalErrorCodes.PROPOSAL_LIMIT_EXCEEDED);
            }
            for (JsonNode child : node) {
                enforceDepth(child, depth + 1);
            }
        }
    }
}