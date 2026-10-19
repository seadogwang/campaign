package com.company.campaign.builder.refine;

import com.company.campaign.builder.refine.testsupport.RefinementTestFixtures;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.segment.dsl.codec.SegmentDslCodec;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.dsl.contract.SegmentDslSchemaVersions;
import com.company.campaign.segment.dsl.predicate.AttributePredicate;
import com.company.campaign.segment.dsl.predicate.ConditionValue;
import com.company.campaign.segment.dsl.predicate.ExistsPredicate;
import com.company.campaign.segment.dsl.predicate.GroupPredicate;
import com.company.campaign.segment.dsl.predicate.MetricExpressionPredicate;
import com.company.campaign.segment.dsl.predicate.MetricPredicate;
import com.company.campaign.segment.dsl.predicate.NotPredicate;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.dsl.predicate.PredicateType;
import com.company.campaign.segment.dsl.value.EnumValue;
import com.company.campaign.segment.dsl.value.IntegerValue;
import com.company.campaign.segment.dsl.value.ListValue;
import com.company.campaign.segment.dsl.value.MoneyValue;
import com.company.campaign.segment.dsl.value.StringValue;
import com.company.campaign.segment.dsl.window.RollingWindow;
import com.company.campaign.segment.dsl.window.WindowUnit;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Section 2 of the task: one deterministic immutable tree editor whose complete patch is atomic, whose
 * operations address stable node, parent and sibling identity rather than array position, and whose failures are
 * bounded, stable and non-partial.
 */
class SegmentRefinementEditorTest {

    private static final SegmentRefinementEditor EDITOR = new SegmentRefinementEditor();
    private static final SegmentDslCodec DSL_CODEC = new SegmentDslCodec();

    private static void assertRefused(String code, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(SegmentRefinementException.class)
                .extracting(failure -> ((SegmentRefinementException) failure).code())
                .isEqualTo(code);
    }

    private static List<Predicate> children(SegmentDsl document) {
        return ((GroupPredicate) document.predicate()).children();
    }

    private static List<String> childIds(SegmentDsl document) {
        return children(document).stream().map(Predicate::nodeId).toList();
    }

    // ------------------------------------------------------------------ ADD_NODE

    @Test
    void addNodeAppendsWhenNoSiblingAnchorIsNamed() {
        SegmentDsl base = RefinementTestFixtures.baseDocument();
        SegmentDsl refined = EDITOR.apply(base, RefinementTestFixtures.patch(base,
                new SegmentRefinementOperation.AddNode(
                        "op-1", "n-root", null, RefinementTestFixtures.tierEqualsGold("n-added"))));

        assertThat(childIds(refined)).containsExactly("n-tier", "n-spend", "n-added");
        assertThat(refined.editableDslHash()).isNotEqualTo(base.editableDslHash());
        assertThat(children(refined).get(0)).isSameAs(children(base).get(0));
        assertThat(children(refined).get(1)).isSameAs(children(base).get(1));
    }

    @Test
    void addNodeInsertsBeforeTheNamedStableSiblingAnchor() {
        SegmentDsl base = RefinementTestFixtures.baseDocument();
        SegmentDsl refined = EDITOR.apply(base, RefinementTestFixtures.patch(base,
                new SegmentRefinementOperation.AddNode(
                        "op-1", "n-root", "n-spend", RefinementTestFixtures.tierEqualsGold("n-added"))));

        assertThat(childIds(refined)).containsExactly("n-tier", "n-added", "n-spend");
    }

    @Test
    void addNodeCanTargetANestedGroup() {
        SegmentDsl base = RefinementTestFixtures.nestedDocument();
        SegmentDsl refined = EDITOR.apply(base, RefinementTestFixtures.patch(base,
                new SegmentRefinementOperation.AddNode(
                        "op-1", "n-any", "n-city", RefinementTestFixtures.tierEqualsGold("n-added"))));

        GroupPredicate any = (GroupPredicate) children(refined).get(1);
        assertThat(any.children()).extracting(Predicate::nodeId).containsExactly("n-added", "n-city", "n-not");
    }

    @Test
    void addNodeRefusesACollidingNodeIdentity() {
        SegmentDsl base = RefinementTestFixtures.baseDocument();
        SegmentDsl rewritten = RefinementTestFixtures.baseDocument();
        SegmentRefinementPatch patch = RefinementTestFixtures.patch(base,
                new SegmentRefinementOperation.AddNode(
                        "op-1", "n-root", null, RefinementTestFixtures.tierEqualsGold("n-tier")));

        assertRefused(SegmentRefinementErrorCodes.NODE_ID_COLLISION,
                () -> EDITOR.apply(base, patch));
        assertThat(rewritten).isEqualTo(base);
    }

    @Test
    void addNodeRefusesACollisionWithAnEarlierAddition() {
        SegmentDsl base = RefinementTestFixtures.baseDocument();
        SegmentRefinementPatch patch = RefinementTestFixtures.patch(base,
                new SegmentRefinementOperation.AddNode(
                        "op-1", "n-root", null, RefinementTestFixtures.tierEqualsGold("n-added")),
                new SegmentRefinementOperation.AddNode(
                        "op-2", "n-root", null, RefinementTestFixtures.tierEqualsGold("n-added")));

        assertRefused(SegmentRefinementErrorCodes.NODE_ID_COLLISION, () -> EDITOR.apply(base, patch));
    }

    @Test
    void addNodeRefusesAMissingParentANonGroupParentAndAForeignAnchor() {
        SegmentDsl base = RefinementTestFixtures.baseDocument();
        assertRefused(SegmentRefinementErrorCodes.PARENT_NOT_FOUND, () -> EDITOR.apply(base,
                RefinementTestFixtures.patch(base, new SegmentRefinementOperation.AddNode(
                        "op-1", "n-missing", null, RefinementTestFixtures.tierEqualsGold("n-added")))));
        assertRefused(SegmentRefinementErrorCodes.PARENT_NOT_GROUP, () -> EDITOR.apply(base,
                RefinementTestFixtures.patch(base, new SegmentRefinementOperation.AddNode(
                        "op-1", "n-tier", null, RefinementTestFixtures.tierEqualsGold("n-added")))));
        assertRefused(SegmentRefinementErrorCodes.ANCHOR_NOT_FOUND, () -> EDITOR.apply(base,
                RefinementTestFixtures.patch(base, new SegmentRefinementOperation.AddNode(
                        "op-1", "n-root", "n-missing", RefinementTestFixtures.tierEqualsGold("n-added")))));
    }

    @Test
    void addNodeRefusesAnAnchorThatIsNotADirectChildOfTheExactParent() {
        SegmentDsl base = RefinementTestFixtures.nestedDocument();
        // n-amount is a descendant of n-exists, not a direct child of n-root.
        assertRefused(SegmentRefinementErrorCodes.ANCHOR_NOT_FOUND, () -> EDITOR.apply(base,
                RefinementTestFixtures.patch(base, new SegmentRefinementOperation.AddNode(
                        "op-1", "n-root", "n-amount", RefinementTestFixtures.tierEqualsGold("n-added")))));
    }

    // ------------------------------------------------------------------ REMOVE_NODE

    @Test
    void removeNodeRemovesOneGroupChildAndPreservesEveryOtherNode() {
        SegmentDsl base = RefinementTestFixtures.baseDocument();
        SegmentDsl refined = EDITOR.apply(base, RefinementTestFixtures.patch(base,
                new SegmentRefinementOperation.RemoveNode("op-1", "n-tier")));

        assertThat(childIds(refined)).containsExactly("n-spend");
        assertThat(children(refined).get(0)).isSameAs(children(base).get(1));
    }

    @Test
    void removeNodeRefusesRootRemovalNonGroupParentsAnEmptyResultAndAMissingNode() {
        SegmentDsl base = RefinementTestFixtures.baseDocument();
        assertRefused(SegmentRefinementErrorCodes.ROOT_NOT_EDITABLE, () -> EDITOR.apply(base,
                RefinementTestFixtures.patch(base, new SegmentRefinementOperation.RemoveNode("op-1", "n-root"))));
        assertRefused(SegmentRefinementErrorCodes.NODE_NOT_FOUND, () -> EDITOR.apply(base,
                RefinementTestFixtures.patch(base, new SegmentRefinementOperation.RemoveNode("op-1", "n-missing"))));

        SegmentDsl nested = RefinementTestFixtures.nestedDocument();
        assertRefused(SegmentRefinementErrorCodes.REMOVE_FROM_NON_GROUP, () -> EDITOR.apply(nested,
                RefinementTestFixtures.patch(nested, new SegmentRefinementOperation.RemoveNode("op-1", "n-code"))));

        SegmentDsl single = RefinementTestFixtures.singleChildGroupDocument();
        assertRefused(SegmentRefinementErrorCodes.GROUP_EMPTY, () -> EDITOR.apply(single,
                RefinementTestFixtures.patch(single, new SegmentRefinementOperation.RemoveNode("op-1", "n-city"))));
    }

    // ------------------------------------------------------------------ REPLACE_FIELD

    @Test
    void replaceFieldChangesARollingNinetyDayWindowToThirtyDays() {
        SegmentDsl base = RefinementTestFixtures.baseDocument();
        SegmentDsl refined = EDITOR.apply(base, RefinementTestFixtures.patch(base,
                RefinementTestFixtures.replaceWindow("op-1", "n-spend", 30)));

        MetricPredicate metric = (MetricPredicate) children(refined).get(1);
        assertThat(metric.window()).isEqualTo(new RollingWindow(30, WindowUnit.DAY));
        assertThat(metric.metricId()).isEqualTo("order.net_paid_amount");
        assertThat(metric.operator()).isEqualTo(ComparisonOperator.GREATER_THAN);
        assertThat(metric.condition()).isEqualTo(ConditionValue.of(new MoneyValue("1000.00", "CNY")));
        assertThat(metric.nodeId()).isEqualTo("n-spend");
        assertThat(children(refined).get(0)).isSameAs(children(base).get(0));
    }

    @Test
    void replaceFieldChangesAThresholdOperand() {
        SegmentDsl base = RefinementTestFixtures.baseDocument();
        SegmentDsl refined = EDITOR.apply(base, RefinementTestFixtures.patch(base,
                RefinementTestFixtures.replaceValue(
                        "op-1", "n-spend", new MoneyValue("250.00", "CNY"))));

        MetricPredicate metric = (MetricPredicate) children(refined).get(1);
        assertThat(metric.condition()).isEqualTo(ConditionValue.of(new MoneyValue("250.00", "CNY")));
        assertThat(metric.window()).isEqualTo(new RollingWindow(90, WindowUnit.DAY));
    }

    @Test
    void replaceFieldChangesAnAttributeOperandAndAnOperator() {
        SegmentDsl base = RefinementTestFixtures.baseDocument();
        SegmentDsl operand = EDITOR.apply(base, RefinementTestFixtures.patch(base,
                RefinementTestFixtures.replaceValue("op-1", "n-tier", new EnumValue("SILVER"))));
        assertThat(((AttributePredicate) children(operand).get(0)).condition())
                .isEqualTo(ConditionValue.of(new EnumValue("SILVER")));

        SegmentDsl operator = EDITOR.apply(base, RefinementTestFixtures.patch(base,
                RefinementTestFixtures.replaceOperator("op-1", "n-tier", ComparisonOperator.NOT_EQUAL)));
        assertThat(((AttributePredicate) children(operator).get(0)).operator())
                .isEqualTo(ComparisonOperator.NOT_EQUAL);
        assertThat(((AttributePredicate) children(operator).get(0)).condition())
                .isEqualTo(ConditionValue.of(new EnumValue("GOLD")));
    }

    @Test
    void replaceFieldReachesEveryWindowBearingLeafKind() {
        SegmentDsl nested = RefinementTestFixtures.nestedDocument();
        SegmentDsl existsRefined = EDITOR.apply(nested, RefinementTestFixtures.patch(nested,
                RefinementTestFixtures.replaceWindow("op-1", "n-exists", 7)));
        ExistsPredicate exists = (ExistsPredicate) children(existsRefined).get(2);
        assertThat(exists.window()).isEqualTo(new RollingWindow(7, WindowUnit.DAY));
        assertThat(exists.predicate()).isSameAs(((ExistsPredicate) children(nested).get(2)).predicate());

        SegmentDsl expression = RefinementTestFixtures.expressionDocument();
        SegmentDsl expressionRefined = EDITOR.apply(expression, RefinementTestFixtures.patch(expression,
                new SegmentRefinementOperation.ReplaceField(
                        "op-1", "n-expr", RefinementField.WINDOW,
                        new RefinementReplacement.WindowReplacement(new RollingWindow(14, WindowUnit.DAY)))));
        assertThat(((MetricExpressionPredicate) children(expressionRefined).get(0)).window())
                .isEqualTo(new RollingWindow(14, WindowUnit.DAY));
    }

    @Test
    void replaceFieldRefusesAFieldTheTargetNodeKindDoesNotSupport() {
        SegmentDsl base = RefinementTestFixtures.baseDocument();
        assertRefused(SegmentRefinementErrorCodes.FIELD_NODE_INCOMPATIBLE, () -> EDITOR.apply(base,
                RefinementTestFixtures.patch(base, RefinementTestFixtures.replaceWindow("op-1", "n-tier", 30))));
        assertRefused(SegmentRefinementErrorCodes.FIELD_NODE_INCOMPATIBLE, () -> EDITOR.apply(base,
                RefinementTestFixtures.patch(base, RefinementTestFixtures.replaceValue(
                        "op-1", "n-root", new StringValue("x")))));
        assertRefused(SegmentRefinementErrorCodes.NODE_NOT_FOUND, () -> EDITOR.apply(base,
                RefinementTestFixtures.patch(base, RefinementTestFixtures.replaceWindow("op-1", "n-missing", 30))));
    }

    @Test
    void replaceFieldRefusesAnOperatorOrOperandThatBreaksTheShape() {
        SegmentDsl nullOperator = RefinementTestFixtures.nullOperatorDocument();
        assertRefused(SegmentRefinementErrorCodes.FIELD_OPERATOR_INCOMPATIBLE, () -> EDITOR.apply(nullOperator,
                RefinementTestFixtures.patch(nullOperator, RefinementTestFixtures.replaceValue(
                        "op-1", "n-null", new StringValue("x")))));

        SegmentDsl base = RefinementTestFixtures.baseDocument();
        assertRefused(SegmentRefinementErrorCodes.FIELD_OPERATOR_INCOMPATIBLE, () -> EDITOR.apply(base,
                RefinementTestFixtures.patch(base, RefinementTestFixtures.replaceOperator(
                        "op-1", "n-tier", ComparisonOperator.IS_NULL))));
        assertRefused(SegmentRefinementErrorCodes.FIELD_OPERATOR_INCOMPATIBLE, () -> EDITOR.apply(base,
                RefinementTestFixtures.patch(base, RefinementTestFixtures.replaceValue(
                        "op-1", "n-tier", new ListValue(List.of(new EnumValue("GOLD")))))));
    }

    @Test
    void replaceFieldNeverTouchesTheNodeIdentityTheKindOrUnrelatedFields() {
        SegmentDsl base = RefinementTestFixtures.nestedDocument();
        SegmentDsl refined = EDITOR.apply(base, RefinementTestFixtures.patch(base,
                RefinementTestFixtures.replaceValue("op-1", "n-city", new StringValue("BJ"))));

        GroupPredicate any = (GroupPredicate) children(refined).get(1);
        AttributePredicate city = (AttributePredicate) any.children().get(0);
        assertThat(city.nodeId()).isEqualTo("n-city");
        assertThat(city.attributeId()).isEqualTo("member.code");
        assertThat(city.operator()).isEqualTo(ComparisonOperator.EQUAL);
        assertThat(city.condition()).isEqualTo(ConditionValue.of(new StringValue("BJ")));
        assertThat(any.children().get(1)).isSameAs(((GroupPredicate) children(base).get(1)).children().get(1));
        assertThat(children(refined).get(0)).isSameAs(children(base).get(0));
        assertThat(children(refined).get(2)).isSameAs(children(base).get(2));
    }

    // ------------------------------------------------------------------ MOVE_NODE

    @Test
    void moveNodeMovesAcrossParents() {
        SegmentDsl base = RefinementTestFixtures.nestedDocument();
        SegmentDsl refined = EDITOR.apply(base, RefinementTestFixtures.patch(base,
                new SegmentRefinementOperation.MoveNode("op-1", "n-city", "n-root", "n-tier")));

        assertThat(childIds(refined)).containsExactly("n-city", "n-tier", "n-any", "n-exists");
        GroupPredicate any = (GroupPredicate) children(refined).get(2);
        assertThat(any.children()).extracting(Predicate::nodeId).containsExactly("n-not");
        assertThat(any.children().get(0)).isSameAs(((GroupPredicate) children(base).get(1)).children().get(1));
    }

    @Test
    void moveNodeWithinTheSameParentIsDeterministicAndIndexIndependent() {
        SegmentDsl base = RefinementTestFixtures.threeChildDocument();

        SegmentDsl moved = EDITOR.apply(base, RefinementTestFixtures.patch(base,
                new SegmentRefinementOperation.MoveNode("op-1", "n-city", "n-root", "n-tier")));
        assertThat(childIds(moved)).containsExactly("n-city", "n-tier", "n-spend");

        SegmentDsl backwards = EDITOR.apply(base, RefinementTestFixtures.patch(base,
                new SegmentRefinementOperation.MoveNode("op-1", "n-tier", "n-root", "n-city")));
        assertThat(childIds(backwards)).containsExactly("n-spend", "n-tier", "n-city");

        // A same-parent move onto the position a node already occupies is a deterministic no-op.
        // Moving to the position a node already occupies is a deterministic no-op, and the result does not
        // depend on where the node originally sat in the array.
        SegmentDsl noOp = EDITOR.apply(base, RefinementTestFixtures.patch(base,
                new SegmentRefinementOperation.MoveNode("op-1", "n-city", "n-root", null)));
        assertThat(noOp.editableDslHash()).isEqualTo(base.editableDslHash());
        assertThat(noOp.predicate()).isEqualTo(base.predicate());

        SegmentDsl sameOrder = EDITOR.apply(base, RefinementTestFixtures.patch(base,
                new SegmentRefinementOperation.MoveNode("op-1", "n-tier", "n-root", "n-spend")));
        assertThat(childIds(sameOrder)).containsExactly("n-tier", "n-spend", "n-city");
        assertThat(sameOrder.editableDslHash()).isEqualTo(base.editableDslHash());
    }

    @Test
    void moveNodeWithinAOneChildGroupOntoItsOwnParentIsADeterministicNoOp() {
        SegmentDsl base = RefinementTestFixtures.singleChildGroupDocument();
        SegmentDsl noOp = EDITOR.apply(base, RefinementTestFixtures.patch(base,
                new SegmentRefinementOperation.MoveNode("op-1", "n-city", "n-any", null)));

        // Exact predicate equality and the exact editable hash: nothing became empty and nothing moved.
        assertThat(noOp).isEqualTo(base);
        assertThat(noOp.predicate()).isSameAs(base.predicate());
        assertThat(noOp.editableDslHash()).isEqualTo(base.editableDslHash());
        assertThat(DSL_CODEC.writeString(noOp)).isEqualTo(DSL_CODEC.writeString(base));

        // Self-anchors and foreign anchors are still refused, and a foreign anchor is not reported as an
        // emptied source group.
        assertRefused(SegmentRefinementErrorCodes.DUPLICATE_PLACEMENT, () -> EDITOR.apply(base,
                RefinementTestFixtures.patch(base, new SegmentRefinementOperation.MoveNode(
                        "op-1", "n-city", "n-any", "n-city"))));
        assertRefused(SegmentRefinementErrorCodes.ANCHOR_NOT_FOUND, () -> EDITOR.apply(base,
                RefinementTestFixtures.patch(base, new SegmentRefinementOperation.MoveNode(
                        "op-1", "n-city", "n-any", "n-tier"))));

        // Cross-parent control: the one-child source moved to a different parent must still fail GROUP_EMPTY.
        assertRefused(SegmentRefinementErrorCodes.GROUP_EMPTY, () -> EDITOR.apply(base,
                RefinementTestFixtures.patch(base, new SegmentRefinementOperation.MoveNode(
                        "op-1", "n-city", "n-root", null))));
    }

    @Test
    void moveNodeRefusesRootCyclesForeignAnchorsDuplicatePlacementAndAnEmptiedSource() {
        SegmentDsl base = RefinementTestFixtures.nestedDocument();
        assertRefused(SegmentRefinementErrorCodes.ROOT_NOT_EDITABLE, () -> EDITOR.apply(base,
                RefinementTestFixtures.patch(base, new SegmentRefinementOperation.MoveNode(
                        "op-1", "n-root", "n-any", null))));
        assertRefused(SegmentRefinementErrorCodes.MOVE_INTO_DESCENDANT, () -> EDITOR.apply(base,
                RefinementTestFixtures.patch(base, new SegmentRefinementOperation.MoveNode(
                        "op-1", "n-any", "n-any", null))));
        assertRefused(SegmentRefinementErrorCodes.MOVE_INTO_DESCENDANT, () -> EDITOR.apply(base,
                RefinementTestFixtures.patch(base, new SegmentRefinementOperation.MoveNode(
                        "op-1", "n-any", "n-city", null))));
        assertRefused(SegmentRefinementErrorCodes.ANCHOR_NOT_FOUND, () -> EDITOR.apply(base,
                RefinementTestFixtures.patch(base, new SegmentRefinementOperation.MoveNode(
                        "op-1", "n-tier", "n-root", "n-amount"))));
        assertRefused(SegmentRefinementErrorCodes.DUPLICATE_PLACEMENT, () -> EDITOR.apply(base,
                RefinementTestFixtures.patch(base, new SegmentRefinementOperation.MoveNode(
                        "op-1", "n-tier", "n-root", "n-tier"))));
        assertRefused(SegmentRefinementErrorCodes.NODE_NOT_FOUND, () -> EDITOR.apply(base,
                RefinementTestFixtures.patch(base, new SegmentRefinementOperation.MoveNode(
                        "op-1", "n-missing", "n-root", null))));

        SegmentDsl single = RefinementTestFixtures.singleChildGroupDocument();
        assertRefused(SegmentRefinementErrorCodes.GROUP_EMPTY, () -> EDITOR.apply(single,
                RefinementTestFixtures.patch(single, new SegmentRefinementOperation.MoveNode(
                        "op-1", "n-city", "n-root", null))));

        SegmentDsl notBody = RefinementTestFixtures.nestedDocument();
        assertRefused(SegmentRefinementErrorCodes.GROUP_EMPTY, () -> EDITOR.apply(notBody,
                RefinementTestFixtures.patch(notBody, new SegmentRefinementOperation.MoveNode(
                        "op-1", "n-code", "n-root", null))));
    }

    // ------------------------------------------------------------------ atomicity and determinism

    @Test
    void aFailedOperationPublishesNoPartialDocument() {
        SegmentDsl base = RefinementTestFixtures.baseDocument();
        SegmentRefinementPatch patch = RefinementTestFixtures.patch(base,
                new SegmentRefinementOperation.RemoveNode("op-1", "n-tier"),
                new SegmentRefinementOperation.RemoveNode("op-2", "n-missing"));

        assertRefused(SegmentRefinementErrorCodes.NODE_NOT_FOUND, () -> EDITOR.apply(base, patch));
        assertThat(base.editableDslHash()).isEqualTo(RefinementTestFixtures.baseDocument().editableDslHash());
        assertThat(childIds(base)).containsExactly("n-tier", "n-spend");
    }

    @Test
    void orderedOperationsAreEvaluatedAgainstTheTreeTheirPredecessorsProduced() {
        SegmentDsl base = RefinementTestFixtures.baseDocument();
        SegmentDsl refined = EDITOR.apply(base, RefinementTestFixtures.patch(base,
                new SegmentRefinementOperation.AddNode(
                        "op-1", "n-root", null, RefinementTestFixtures.tierEqualsGold("n-added")),
                RefinementTestFixtures.replaceWindow("op-2", "n-spend", 30),
                new SegmentRefinementOperation.MoveNode("op-3", "n-added", "n-root", "n-tier")));

        assertThat(childIds(refined)).containsExactly("n-added", "n-tier", "n-spend");
        assertThat(((MetricPredicate) children(refined).get(2)).window())
                .isEqualTo(new RollingWindow(30, WindowUnit.DAY));

        // The added node is addressable by the later operation only because the earlier one created it.
        assertRefused(SegmentRefinementErrorCodes.NODE_NOT_FOUND, () -> EDITOR.apply(base,
                RefinementTestFixtures.patch(base,
                        new SegmentRefinementOperation.MoveNode("op-1", "n-added", "n-root", "n-tier"))));
    }

    @Test
    void applyingTheSameValidPatchProducesByteIdenticalDslAndHash() {
        SegmentDsl base = RefinementTestFixtures.baseDocument();
        SegmentRefinementPatch patch = RefinementTestFixtures.patch(base,
                RefinementTestFixtures.replaceWindow("op-1", "n-spend", 30));

        SegmentDsl first = EDITOR.apply(base, patch);
        String bytes = DSL_CODEC.writeString(first);
        for (int iteration = 0; iteration < 100; iteration++) {
            SegmentDsl repeated = EDITOR.apply(base, patch);
            assertThat(repeated.editableDslHash()).isEqualTo(first.editableDslHash());
            assertThat(DSL_CODEC.writeString(repeated)).isEqualTo(bytes);
        }
    }

    @Test
    void reapplyingTheSamePatchToTheRefinedDocumentFailsTheBaseHashCheck() {
        SegmentDsl base = RefinementTestFixtures.baseDocument();
        SegmentRefinementPatch patch = RefinementTestFixtures.patch(base,
                new SegmentRefinementOperation.AddNode(
                        "op-1", "n-root", null, RefinementTestFixtures.tierEqualsGold("n-added")));
        SegmentDsl refined = EDITOR.apply(base, patch);

        assertRefused(SegmentRefinementErrorCodes.BASE_HASH_MISMATCH, () -> EDITOR.apply(refined, patch));
        assertThat(childIds(refined)).containsExactly("n-tier", "n-spend", "n-added");
    }

    @Test
    void aStaleOrForeignBaseHashIsRefusedBeforeAnyOperationRuns() {
        SegmentDsl base = RefinementTestFixtures.baseDocument();
        ContentHash foreign = RefinementTestFixtures.nestedDocument().editableDslHash();
        SegmentRefinementPatch stale = SegmentRefinementPatch.publish(
                SegmentDslSchemaVersions.SUPPORTED, foreign,
                List.of(new SegmentRefinementOperation.RemoveNode("op-1", "n-tier")));

        assertRefused(SegmentRefinementErrorCodes.BASE_HASH_MISMATCH, () -> EDITOR.apply(base, stale));
        assertThat(childIds(base)).containsExactly("n-tier", "n-spend");
    }

    @Test
    void theRefinedDocumentIsAPublishedT05DocumentWithItsOwnEditableHash() {
        SegmentDsl base = RefinementTestFixtures.nestedDocument();
        SegmentDsl refined = EDITOR.apply(base, RefinementTestFixtures.patch(base,
                RefinementTestFixtures.replaceWindow("op-1", "n-exists", 5)));

        assertThat(refined.schemaVersion()).isEqualTo(base.schemaVersion());
        assertThat(refined.subject()).isEqualTo(base.subject());
        assertThat(refined.metadata()).isEqualTo(base.metadata());
        assertThat(refined.editableDslHash()).isNotEqualTo(base.editableDslHash());
        assertThat(DSL_CODEC.parse(DSL_CODEC.writeString(refined))).isEqualTo(refined);
    }

    @Test
    void untouchedNestedGroupsKeepTheirExactIdentity() {
        SegmentDsl base = RefinementTestFixtures.nestedDocument();
        SegmentDsl refined = EDITOR.apply(base, RefinementTestFixtures.patch(base,
                RefinementTestFixtures.replaceWindow("op-1", "n-exists", 5)));

        assertThat(children(refined).get(0)).isSameAs(children(base).get(0));
        assertThat(children(refined).get(1)).isSameAs(children(base).get(1));
        assertThat(children(refined).get(2)).isNotSameAs(children(base).get(2));
        assertThat(((ExistsPredicate) children(refined).get(2)).predicate())
                .isSameAs(((ExistsPredicate) children(base).get(2)).predicate());
    }

    @Test
    void aNotAndAnAnyGroupSurviveEverySupportedOperation() {
        SegmentDsl base = RefinementTestFixtures.nestedDocument();
        SegmentDsl refined = EDITOR.apply(base, RefinementTestFixtures.patch(base,
                new SegmentRefinementOperation.AddNode(
                        "op-1", "n-any", null, RefinementTestFixtures.tierEqualsGold("n-added")),
                new SegmentRefinementOperation.MoveNode("op-2", "n-not", "n-any", "n-added"),
                RefinementTestFixtures.replaceValue("op-3", "n-city", new StringValue("A")),
                new SegmentRefinementOperation.RemoveNode("op-4", "n-tier")));

        assertThat(childIds(refined)).containsExactly("n-any", "n-exists");
        GroupPredicate any = (GroupPredicate) children(refined).get(0);
        assertThat(any.children()).extracting(Predicate::nodeId).containsExactly("n-city", "n-not", "n-added");
        assertThat(any.children().get(1)).isInstanceOf(NotPredicate.class);
        assertThat(((NotPredicate) any.children().get(1)).child())
                .isSameAs(((NotPredicate) ((GroupPredicate) children(base).get(1)).children().get(1)).child());
        assertThat(any.children().get(2)).isInstanceOf(AttributePredicate.class);
        assertThat(((AttributePredicate) any.children().get(0)).condition())
                .isEqualTo(ConditionValue.of(new StringValue("A")));
        assertThat(((ExistsPredicate) children(refined).get(1)).window())
                .isEqualTo(new RollingWindow(30, WindowUnit.DAY));
        assertThat(((AttributePredicate) ((ExistsPredicate) children(refined).get(1)).predicate()).nodeId())
                .isEqualTo("n-amount");
    }

    @Test
    void anIntegerThresholdReplacementKeepsItsExactTypedKind() {
        SegmentDsl expression = RefinementTestFixtures.expressionDocument();
        SegmentDsl refined = EDITOR.apply(expression, RefinementTestFixtures.patch(expression,
                RefinementTestFixtures.replaceValue("op-1", "n-expr", new IntegerValue(9L))));

        MetricExpressionPredicate predicate = (MetricExpressionPredicate) children(refined).get(0);
        assertThat(predicate.condition()).isEqualTo(ConditionValue.of(new IntegerValue(9L)));
        assertThat(predicate.operator()).isEqualTo(ComparisonOperator.GREATER_THAN);
    }

    @Test
    void theEditableHashIsTheCanonicalHashOfExactlyTheProducedDocument() {
        SegmentDsl base = RefinementTestFixtures.baseDocument();
        SegmentDsl refined = EDITOR.apply(base, RefinementTestFixtures.patch(base,
                RefinementTestFixtures.replaceWindow("op-1", "n-spend", 30)));

        assertThat(refined.editableDslHash()).isEqualTo(
                com.company.campaign.segment.dsl.codec.SegmentDslHasher.hash(refined.hashMaterial()));
        assertThat(new String(DSL_CODEC.write(refined), StandardCharsets.UTF_8))
                .contains("\"amount\":30")
                .contains("\"editableDslHash\":\"" + refined.editableDslHash().value() + "\"");
    }
}