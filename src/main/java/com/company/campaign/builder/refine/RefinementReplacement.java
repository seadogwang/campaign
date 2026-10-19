package com.company.campaign.builder.refine;

import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.segment.dsl.value.TypedValue;
import com.company.campaign.segment.dsl.window.TimeWindow;
import com.company.campaign.segment.reference.AudienceVersionRef;

/**
 * The closed, typed replacement value union of one {@code REPLACE_FIELD} operation.
 *
 * <p>Each variant reuses the accepted T05/T04 type for its domain — a {@link TimeWindow}, a
 * {@link ConditionValue} and a {@link ComparisonOperator} — so a refinement can never introduce a second DSL
 * or a second value model. There is no string, map, {@code JsonNode} or wildcard variant, so a replacement
 * cannot smuggle an untyped or executable fragment into the document.
 */
public sealed interface RefinementReplacement {

    /** The one replacement value kind this variant satisfies. */
    RefinementFieldValueKind valueKind();

    /** A T05 time-window replacement. */
    record WindowReplacement(TimeWindow window) implements RefinementReplacement {

        public WindowReplacement {
            if (window == null) {
                throw SegmentRefinementException.of(
                        SegmentRefinementErrorCodes.FIELD_VALUE_INVALID,
                        "A window replacement requires a T05 time window");
            }
        }

        @Override
        public RefinementFieldValueKind valueKind() {
            return RefinementFieldValueKind.WINDOW;
        }
    }

    /**
     * A T05 typed-operand replacement.
     *
     * <p>The operand is the accepted {@link TypedValue} itself rather than a {@code ConditionValue}: the absent
     * operand of {@code IS_NULL}/{@code IS_NOT_NULL} is not a value a single-field edit can produce or remove,
     * because switching to or from a NULL operator changes the operator as well. Keeping the absent state out of
     * the union is what lets the wire form stay exactly the T05 typed value and stay round-trippable.
     */
    record ConditionReplacement(TypedValue value) implements RefinementReplacement {

        public ConditionReplacement {
            if (value == null) {
                throw SegmentRefinementException.of(
                        SegmentRefinementErrorCodes.FIELD_VALUE_INVALID,
                        "A value replacement requires a T05 typed operand");
            }
        }

        @Override
        public RefinementFieldValueKind valueKind() {
            return RefinementFieldValueKind.CONDITION;
        }
    }

    /** A T04 comparison-operator replacement. */
    record OperatorReplacement(ComparisonOperator operator) implements RefinementReplacement {

        public OperatorReplacement {
            if (operator == null) {
                throw SegmentRefinementException.of(
                        SegmentRefinementErrorCodes.FIELD_VALUE_INVALID,
                        "An operator replacement requires a T04 comparison operator");
            }
        }

        @Override
        public RefinementFieldValueKind valueKind() {
            return RefinementFieldValueKind.OPERATOR;
        }
    }

    /**
     * An exact immutable audience-version pin replacement for one {@code AUDIENCE_REFERENCE} node.
     *
     * <p>It carries two pins and nothing else, and both are reused from the accepted reference core rather than
     * re-modelled here:
     *
     * <ul>
     *   <li>{@code expected} — the pin the operation believes the target node carries. The editor refuses the
     *       operation unless the base node really carries it, which is the stale-base protection an edit
     *       computed against one immutable pin needs: a reference that has meanwhile been re-pinned to a
     *       different immutable version must fail rather than be silently overwritten;</li>
     *   <li>{@code pinned} — the exact pin the reference should carry afterwards. It is <em>not</em> trusted
     *       merely because the patch declares it: the editor publishes the refined document only when an
     *       accepted host resolution backs that exact pin for that exact node identity.</li>
     * </ul>
     *
     * <p>There is no {@code current}, {@code latest}, name, query, SQL, owner, program, DataScope, permission,
     * dependency or Gate field, so a replacement cannot declare a trusted fact about the version it names.
     */
    record ReferencePinReplacement(AudienceVersionRef expected, AudienceVersionRef pinned)
            implements RefinementReplacement {

        public ReferencePinReplacement {
            if (expected == null || pinned == null) {
                throw SegmentRefinementException.of(
                        SegmentRefinementErrorCodes.FIELD_VALUE_INVALID,
                        "A reference pin replacement requires both its expected and its new exact pin");
            }
        }

        @Override
        public RefinementFieldValueKind valueKind() {
            return RefinementFieldValueKind.REFERENCE_PIN;
        }
    }
}