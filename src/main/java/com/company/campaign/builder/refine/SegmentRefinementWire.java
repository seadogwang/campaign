package com.company.campaign.builder.refine;

import com.company.campaign.segment.dsl.codec.SegmentDslCodec;
import com.company.campaign.segment.reference.AudienceVersionRef;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one projection of the refinement patch onto canonical JSON material.
 *
 * <p>It exists so the patch fingerprint is computed through the same field names, discriminators and ordering
 * the wire contract publishes, and never through Jackson's reflective view of the record. A field that is
 * absent on the wire is absent here, so "append" and "before some anchor" can never hash the same.
 *
 * <p>Subtree, window and value operands are projected by serializing them with the accepted
 * {@link SegmentDslCodec} and re-reading that exact canonical text as a JSON tree. There is still exactly one
 * DSL serializer in the system: this class never re-derives a predicate, window or typed value wire form of its
 * own, and a written patch is therefore byte-for-byte a patch the strict reader accepts.
 *
 * <p>The maps and trees it builds are an internal, transient serialization projection, not a domain
 * representation. No parsed {@code SegmentRefinementOperation} or result type holds a {@code JsonNode} or an
 * open map, and nothing from here escapes the caller that asked for canonical bytes or a fingerprint.
 */
final class SegmentRefinementWire {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();
    private static final SegmentDslCodec DSL_CODEC = new SegmentDslCodec();

    /**
     * Lowercase JSON Patch verbs.
     *
     * <p>They are refused explicitly rather than as merely unknown operations, because a patch that carries one
     * is an array-index JSON Patch document and never a stable-node refinement.
     */
    private static final Set<String> JSON_PATCH_VERBS = Set.of(
            "add", "remove", "replace", "move", "copy", "test");

    private SegmentRefinementWire() {
    }

    /** True when the text is a lowercase array-index JSON Patch verb. */
    static boolean isJsonPatchVerb(String wire) {
        return wire != null && JSON_PATCH_VERBS.contains(wire);
    }

    /** The canonical fingerprint of one patch's semantic material. */
    static ContentHash fingerprint(SegmentRefinementPatch.Material material) {
        return CANONICAL_JSON.hash(material(material));
    }

    /** The complete canonical patch document, including its declared fingerprint. */
    static Map<String, Object> root(SegmentRefinementPatch patch) {
        Map<String, Object> wire = new LinkedHashMap<>(material(patch.hashMaterial()));
        wire.put("patchFingerprint", patch.patchFingerprint().value());
        return wire;
    }

    /** The exact canonical JSON projection the patch fingerprint covers. */
    static Map<String, Object> material(SegmentRefinementPatch.Material material) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("schemaVersion", material.schemaVersion().value());
        wire.put("currentDslHash", material.currentDslHash().value());
        wire.put("operations", operations(material.operations()));
        return wire;
    }

    private static List<Object> operations(List<SegmentRefinementOperation> operations) {
        List<Object> projected = new ArrayList<>(operations.size());
        operations.forEach(operation -> projected.add(operation(operation)));
        return projected;
    }

    private static Map<String, Object> operation(SegmentRefinementOperation operation) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("opId", operation.operationId());
        wire.put("op", operation.kind().name());
        switch (operation) {
            case SegmentRefinementOperation.AddNode add -> {
                wire.put("parentNodeId", add.parentNodeId());
                if (add.beforeNodeId() != null) {
                    wire.put("beforeNodeId", add.beforeNodeId());
                }
                wire.put("node", tree(DSL_CODEC.writePredicateString(add.node())));
            }
            case SegmentRefinementOperation.RemoveNode remove -> wire.put("nodeId", remove.nodeId());
            case SegmentRefinementOperation.ReplaceField replace -> {
                wire.put("nodeId", replace.nodeId());
                wire.put("field", replace.field().wireName());
                wire.putAll(replacement(replace.replacement()));
            }
            case SegmentRefinementOperation.MoveNode move -> {
                wire.put("nodeId", move.nodeId());
                wire.put("parentNodeId", move.parentNodeId());
                if (move.beforeNodeId() != null) {
                    wire.put("beforeNodeId", move.beforeNodeId());
                }
            }
        }
        return wire;
    }

    /**
     * The {@code value} member of a replacement, in exactly the wire shape the codec reads back.
     *
     * <p>Each variant emits its own T05 wire form — a window object, a typed value object or the bare operator
     * name — and the already-projected {@code field} member identifies which one it is. Nothing extra is added,
     * so a written patch is always a patch the strict reader accepts, and two different replacements can never
     * project to the same bytes.
     */
    private static Map<String, Object> replacement(RefinementReplacement replacement) {
        Map<String, Object> wire = new LinkedHashMap<>();
        switch (replacement) {
            case RefinementReplacement.WindowReplacement window ->
                    wire.put("value", tree(DSL_CODEC.writeWindowString(window.window())));
            case RefinementReplacement.OperatorReplacement operator ->
                    wire.put("value", operator.operator().name());
            case RefinementReplacement.ConditionReplacement condition ->
                    wire.put("value", tree(DSL_CODEC.writeValueString(condition.value())));
            case RefinementReplacement.ReferencePinReplacement pins -> wire.put("value", referencePins(pins));
        }
        return wire;
    }

    /**
     * The {@code value} member of a reference pin replacement: the expected and the new exact pin.
     *
     * <p>Both pins are projected as the same three immutable parts the reference core publishes, so a written
     * patch is exactly a patch the strict reader accepts and no extra member can be smuggled into either pin.
     */
    private static JsonNode referencePins(RefinementReplacement.ReferencePinReplacement pins) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("expected", pin(pins.expected()));
        value.put("pinned", pin(pins.pinned()));
        return CANONICAL_JSON.readTree(CANONICAL_JSON.writeString(value));
    }

    private static Map<String, Object> pin(AudienceVersionRef reference) {
        Map<String, Object> pin = new LinkedHashMap<>();
        pin.put("audienceId", reference.audienceId());
        pin.put("versionId", reference.versionId());
        pin.put("versionHash", reference.versionHash().value());
        return pin;
    }

    /**
     * Re-reads one canonical T05 text as a JSON tree, so the patch document embeds the operand as the object
     * the strict reader expects rather than as an escaped string.
     */
    private static JsonNode tree(String canonicalT05Text) {
        return CANONICAL_JSON.readTree(canonicalT05Text);
    }
}