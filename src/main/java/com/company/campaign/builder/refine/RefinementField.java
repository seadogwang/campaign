package com.company.campaign.builder.refine;

import java.util.List;

/**
 * The closed allowlist of semantic fields one {@code REPLACE_FIELD} operation may replace.
 *
 * <p>The allowlist is deliberately narrow. It admits exactly the T05 wire-semantic <em>scalar and value</em>
 * fields a safe refinement needs — the time window of the specification's example, an aggregate threshold and
 * the comparison operator — and nothing structural. Structural child collections, {@code nodeId}, the
 * schema/Metadata binding, the editable hash, identifier references and scope-filter collections are not
 * representable here, so a patch cannot re-shape the tree or re-bind the document through a field edit.
 *
 * <p>Each field names the one wire type its replacement must carry, so a field/value mismatch is refused while
 * the patch is being read rather than being cast at apply time.
 */
public enum RefinementField {

    /** The time window of a {@code METRIC}, {@code METRIC_EXPRESSION} or {@code EXISTS} node. */
    WINDOW("window", RefinementFieldValueKind.WINDOW),

    /** The typed comparison operand of an {@code ATTRIBUTE}, {@code METRIC} or {@code METRIC_EXPRESSION} node. */
    VALUE("value", RefinementFieldValueKind.CONDITION),

    /** The comparison operator of an {@code ATTRIBUTE}, {@code METRIC} or {@code METRIC_EXPRESSION} node. */
    OPERATOR("operator", RefinementFieldValueKind.OPERATOR),

    /**
     * The exact immutable audience-version pin of an {@code AUDIENCE_REFERENCE} node.
     *
     * <p>This is the one field whose replacement is not a free value a model may invent: changing which
     * immutable version a reference names is a resolution decision, so the replacement carries the pin the
     * operation expected to find — its stale-base guard — and the editor still requires the resulting pin to be
     * backed by an accepted host resolution before the refined document is published.
     */
    REFERENCE_PIN("referencePin", RefinementFieldValueKind.REFERENCE_PIN);

    private static final List<RefinementField> ORDERED = List.of(WINDOW, VALUE, OPERATOR, REFERENCE_PIN);

    private final String wireName;
    private final RefinementFieldValueKind valueKind;

    RefinementField(String wireName, RefinementFieldValueKind valueKind) {
        this.wireName = wireName;
        this.valueKind = valueKind;
    }

    /** Every allowlisted field, in the canonical declared order. */
    public static List<RefinementField> ordered() {
        return ORDERED;
    }

    /** The exact wire name of this field. */
    public String wireName() {
        return wireName;
    }

    /** The one replacement value kind this field requires. */
    public RefinementFieldValueKind valueKind() {
        return valueKind;
    }

    /**
     * Resolves a wire field name, or throws the stable refusal.
     *
     * <p>The comparison is exact: the specification's own example uses {@code "field": "window"}, and an
     * alternate casing is an unknown field rather than a synonym.
     */
    public static RefinementField fromWire(String wire) {
        if (wire == null) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.NULL_FIELD, "A replacement requires its field");
        }
        for (RefinementField field : ORDERED) {
            if (field.wireName.equals(wire)) {
                return field;
            }
        }
        throw SegmentRefinementException.of(
                SegmentRefinementErrorCodes.FIELD_UNKNOWN, "Unknown refinement replacement field");
    }
}