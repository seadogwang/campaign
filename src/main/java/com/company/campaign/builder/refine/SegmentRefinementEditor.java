package com.company.campaign.builder.refine;

import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.dsl.contract.SegmentDslException;
import com.company.campaign.segment.dsl.predicate.AttributePredicate;
import com.company.campaign.segment.dsl.predicate.AudienceReferencePredicate;
import com.company.campaign.segment.dsl.predicate.ConditionValue;
import com.company.campaign.segment.dsl.predicate.ExistsPredicate;
import com.company.campaign.segment.dsl.predicate.GroupPredicate;
import com.company.campaign.segment.dsl.predicate.MetricExpressionPredicate;
import com.company.campaign.segment.dsl.predicate.MetricPredicate;
import com.company.campaign.segment.dsl.predicate.NotPredicate;
import com.company.campaign.segment.dsl.predicate.OperatorValueRules;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.dsl.predicate.PredicateNodeIds;
import com.company.campaign.segment.dsl.window.TimeWindow;
import com.company.campaign.segment.reference.AudienceVersionRef;
import com.company.campaign.segment.resolution.AudienceReferenceEvidence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one deterministic, immutable tree editor of the refinement patch contract.
 *
 * <p>It verifies the declared base hash, evaluates the operations in their declared order against the tree
 * produced by the previous operations, and either returns a complete refined {@link SegmentDsl} or refuses with
 * one bounded failure. The patch is atomic: no partial document is ever published, and an operation that fails
 * leaves the caller's base document untouched.
 *
 * <p>Every operation addresses its target by stable node, parent and sibling identity. Nothing here reads an
 * array position as an edit identity: a child is located by its {@code nodeId}, an insertion point is derived
 * from the anchor child's identity after the detach, and a same-parent move therefore has the same deterministic
 * result no matter where the node originally sat.
 *
 * <p>Untouched nodes are reused as the exact same immutable record instances wherever the edit does not reach
 * them, and the resulting document is published through the accepted T05 factory, so the refined editable hash
 * is the canonical hash of exactly the document that was produced.
 *
 * <p>The editor is stateless and thread-safe.
 */
public final class SegmentRefinementEditor {

    /** Applies one atomic refinement patch to one base document. */
    public SegmentDsl apply(SegmentDsl base, SegmentRefinementPatch patch) {
        return applyWithEvidence(base, patch).document();
    }

    /**
     * Applies one atomic refinement patch with the host's accepted reference resolutions alongside it.
     *
     * <p>Every audience reference the patch <em>introduces or re-pins</em> must be backed by one of these
     * evidences: a model may propose an exact pin, but only the host resolving against its own directory may
     * decide one. A reference the patch merely moves, or leaves alone, needs no fresh evidence, because the
     * document-level base hash already attested the pin it still carries. A document with no references is
     * unaffected, which is how the accepted reference-free behavior is preserved.
     */
    public SegmentDsl apply(
            SegmentDsl base, SegmentRefinementPatch patch,
            List<AudienceReferenceEvidence> referenceEvidence) {
        return applyWithEvidence(base, patch, referenceEvidence).document();
    }

    /**
     * Applies one atomic refinement patch and reports the operation-time parent each accepted operation saw.
     *
     * <p>The refined document is exactly the one {@link #apply(SegmentDsl, SegmentRefinementPatch)} returns.
     * The parent list is the editor's own accepted evidence: element {@code n} is the stable identity of the
     * parent of operation {@code n}'s target in the tree produced by every earlier operation, before that
     * operation runs. For an {@code ADD_NODE} or a {@code MOVE_NODE} it is the declared destination parent; for
     * a {@code REMOVE_NODE} or a {@code REPLACE_FIELD} it is the enclosing parent at that moment — which a
     * prior {@code MOVE_NODE} can have changed — and {@code null} when the target is the root, which has no
     * parent. It exists so an observer can publish an honest scope instead of guessing it from the original
     * base tree.
     *
     * <p>Package-private: it is the editor's evidence channel for the T12 receipt, not a second public entry
     * point, and it changes no result.
     */
    Applied applyWithEvidence(SegmentDsl base, SegmentRefinementPatch patch) {
        return applyWithEvidence(base, patch, List.of());
    }

    /**
     * Applies one atomic refinement patch and reports the operation-time parent each accepted operation saw,
     * with the host's accepted reference resolutions available to prove any reference the patch introduces.
     *
     * <p>Package-private: it is the editor's evidence channel for the T12 receipt, not a second public entry
     * point. The reference proof runs after every operation and before the document is published, so a
     * reference the patch could not justify leaves no partially refined document behind.
     */
    Applied applyWithEvidence(
            SegmentDsl base, SegmentRefinementPatch patch,
            List<AudienceReferenceEvidence> referenceEvidence) {
        if (base == null || patch == null) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.NULL_FIELD, "A refinement requires its base document and patch");
        }
        if (!patch.currentDslHash().equals(base.editableDslHash())) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.BASE_HASH_MISMATCH,
                    "The declared base DSL hash is not the editable hash of this document");
        }
        Predicate tree = base.predicate();
        List<String> operationParents = new ArrayList<>(patch.operations().size());
        for (SegmentRefinementOperation operation : patch.operations()) {
            operationParents.add(operationTimeParent(tree, operation));
            tree = switch (operation) {
                case SegmentRefinementOperation.AddNode add -> addNode(tree, add);
                case SegmentRefinementOperation.RemoveNode remove -> removeNode(tree, remove.nodeId());
                case SegmentRefinementOperation.ReplaceField replace ->
                        replaceField(tree, replace.nodeId(), replace.field(), replace.replacement());
                case SegmentRefinementOperation.MoveNode move -> moveNode(tree, move);
            };
            requireBoundedTree(tree);
        }
        requireResolvedReferences(base.predicate(), tree, referenceEvidence);
        SegmentDsl document;
        try {
            document = SegmentDsl.publish(base.schemaVersion(), base.subject(), base.metadata(), tree);
        } catch (SegmentDslException rejected) {
            // Publishing re-proves node identity, nesting depth and the editable hash. A tree that fails that
            // proof is over the contract's structural bounds, so it is reported as one bounded refinement limit
            // failure rather than surfacing a nested T05 message.
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.LIMIT_EXCEEDED,
                    "The refined tree does not satisfy the accepted T05 structural bounds");
        }
        return new Applied(document, List.copyOf(operationParents));
    }

    /** One accepted patch application: the refined document plus each operation's operation-time parent. */
    record Applied(SegmentDsl document, List<String> operationParents) {
    }

    /**
     * The parent one operation observes in the tree produced by every earlier operation.
     *
     * <p>An add or a move names its destination parent directly, so that declaration is already the
     * operation-time parent. A remove or a replacement names only its target node, so its parent must be read
     * from the current tree: after an earlier move, the node's original base-tree parent is no longer true.
     */
    private static String operationTimeParent(Predicate tree, SegmentRefinementOperation operation) {
        return switch (operation) {
            case SegmentRefinementOperation.AddNode add -> add.parentNodeId();
            case SegmentRefinementOperation.MoveNode move -> move.parentNodeId();
            case SegmentRefinementOperation.RemoveNode remove -> parentIdOf(tree, remove.nodeId());
            case SegmentRefinementOperation.ReplaceField replace -> parentIdOf(tree, replace.nodeId());
        };
    }

    /** The stable identity of one node's parent in the current tree, or null when it is the root or absent. */
    private static String parentIdOf(Predicate tree, String nodeId) {
        Predicate parent = parentOf(tree, nodeId);
        return parent == null ? null : parent.nodeId();
    }

    // ------------------------------------------------------------------ ADD_NODE

    private static Predicate addNode(Predicate tree, SegmentRefinementOperation.AddNode operation) {
        requireUniqueSubtree(operation.node());
        GroupPredicate parent = requireGroup(tree, operation.parentNodeId());
        // Every added node identifier must be free in the tree produced by every earlier operation.
        for (String addedId : nodeIds(operation.node())) {
            if (contains(tree, addedId)) {
                throw SegmentRefinementException.of(
                        SegmentRefinementErrorCodes.NODE_ID_COLLISION,
                        "An added node identifier already exists in the refined tree");
            }
        }
        List<Predicate> children = insertBefore(
                parent.children(), operation.beforeNodeId(), operation.node());
        return rebuild(tree, parent.nodeId(), new GroupPredicate(parent.nodeId(), parent.type(), children));
    }

    // ------------------------------------------------------------------ REMOVE_NODE

    private static Predicate removeNode(Predicate tree, String nodeId) {
        if (tree.nodeId().equals(nodeId)) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.ROOT_NOT_EDITABLE, "The root node cannot be removed");
        }
        Predicate parent = parentOf(tree, nodeId);
        if (parent == null) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.NODE_NOT_FOUND, "The removal target does not exist");
        }
        if (!(parent instanceof GroupPredicate group)) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.REMOVE_FROM_NON_GROUP,
                    "Only removal from an ALL/ANY group is supported");
        }
        if (group.children().size() <= 1) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.GROUP_EMPTY, "The removal would leave a group with no children");
        }
        List<Predicate> remaining = new ArrayList<>(group.children());
        remaining.remove(indexOfChild(group.children(), nodeId));
        return rebuild(tree, group.nodeId(), new GroupPredicate(group.nodeId(), group.type(), remaining));
    }

    // ------------------------------------------------------------------ MOVE_NODE

    private static Predicate moveNode(Predicate tree, SegmentRefinementOperation.MoveNode operation) {
        if (tree.nodeId().equals(operation.nodeId())) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.ROOT_NOT_EDITABLE, "The root node cannot be moved");
        }
        Predicate moving = find(tree, operation.nodeId());
        if (moving == null) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.NODE_NOT_FOUND, "The move target does not exist");
        }
        if (contains(moving, operation.parentNodeId())) {
            // Covers both "into itself" and "into one of its own descendants".
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.MOVE_INTO_DESCENDANT,
                    "A node cannot be moved into itself or into its own descendant subtree");
        }
        if (operation.nodeId().equals(operation.beforeNodeId())) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.DUPLICATE_PLACEMENT, "A node cannot be anchored on itself");
        }
        // The source parent is determined by stable identity before any detach. When the destination is that
        // same one-child group and no anchor is named, the node already occupies the only position the move
        // could produce: no structure becomes empty and the operation is a stable-identity no-op. Detaching
        // first would wrongly fail it as GROUP_EMPTY.
        Predicate sourceParent = parentOf(tree, operation.nodeId());
        if (sourceParent != null
                && sourceParent.nodeId().equals(operation.parentNodeId())
                && sourceParent instanceof GroupPredicate sourceGroup
                && sourceGroup.children().size() == 1
                && operation.beforeNodeId() == null) {
            return tree;
        }
        GroupPredicate destination = requireGroup(tree, operation.parentNodeId());
        requireDirectAnchor(destination, operation.beforeNodeId());
        Detached detached = detach(tree, operation.nodeId());
        GroupPredicate destinationAfterDetach = requireGroup(detached.tree(), operation.parentNodeId());
        List<Predicate> children = insertBefore(
                destinationAfterDetach.children(), operation.beforeNodeId(), moving);
        return rebuild(
                detached.tree(), destinationAfterDetach.nodeId(),
                new GroupPredicate(destinationAfterDetach.nodeId(), destinationAfterDetach.type(), children));
    }

    /**
     * Refuses a named anchor that is not a direct child of the exact destination group before any detach runs,
     * so a foreign anchor is always reported as such and never as an emptied source group.
     */
    private static void requireDirectAnchor(GroupPredicate destination, String beforeNodeId) {
        if (beforeNodeId != null && indexOfChild(destination.children(), beforeNodeId) < 0) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.ANCHOR_NOT_FOUND,
                    "The sibling anchor is not a direct child of the exact destination parent");
        }
    }

    /** The tree with one node removed. */
    private record Detached(Predicate tree) {
    }

    private static Detached detach(Predicate tree, String nodeId) {
        Predicate parent = parentOf(tree, nodeId);
        if (parent == null) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.NODE_NOT_FOUND, "The move target does not exist");
        }
        if (!(parent instanceof GroupPredicate group)) {
            // A NOT or EXISTS holds exactly one child, so moving it away would leave the parent empty.
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.GROUP_EMPTY,
                    "A node cannot be moved out of a NOT, EXISTS or otherwise non-group parent");
        }
        if (group.children().size() <= 1) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.GROUP_EMPTY, "The move would leave the source group with no children");
        }
        int index = indexOfChild(group.children(), nodeId);
        List<Predicate> remaining = new ArrayList<>(group.children());
        remaining.remove(index);
        return new Detached(
                rebuild(tree, group.nodeId(), new GroupPredicate(group.nodeId(), group.type(), remaining)));
    }

    // ------------------------------------------------------------------ REPLACE_FIELD

    private static Predicate replaceField(
            Predicate tree, String nodeId, RefinementField field, RefinementReplacement replacement) {
        Predicate target = find(tree, nodeId);
        if (target == null) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.NODE_NOT_FOUND, "The replacement target does not exist");
        }
        Predicate replaced = switch (target) {
            case AttributePredicate attribute -> switch (field) {
                case VALUE -> {
                    ConditionValue condition = conditionOf(replacement);
                    requireOperatorShape(attribute.operator(), condition);
                    yield new AttributePredicate(
                            attribute.nodeId(), attribute.attributeId(), attribute.operator(), condition);
                }
                case OPERATOR -> {
                    ComparisonOperator operator = operatorOf(replacement);
                    requireOperatorShape(operator, attribute.condition());
                    yield new AttributePredicate(
                            attribute.nodeId(), attribute.attributeId(), operator, attribute.condition());
                }
                case WINDOW, REFERENCE_PIN -> throw incompatible(field, "ATTRIBUTE");
            };
            case MetricPredicate metric -> switch (field) {
                case WINDOW -> new MetricPredicate(
                        metric.nodeId(), metric.metricId(), windowOf(replacement), metric.scopeFilters(),
                        metric.operator(), metric.condition());
                case VALUE -> {
                    ConditionValue condition = conditionOf(replacement);
                    requireOperatorShape(metric.operator(), condition);
                    yield new MetricPredicate(
                            metric.nodeId(), metric.metricId(), metric.window(), metric.scopeFilters(),
                            metric.operator(), condition);
                }
                case OPERATOR -> {
                    ComparisonOperator operator = operatorOf(replacement);
                    requireOperatorShape(operator, metric.condition());
                    yield new MetricPredicate(
                            metric.nodeId(), metric.metricId(), metric.window(), metric.scopeFilters(),
                            operator, metric.condition());
                }
                case REFERENCE_PIN -> throw incompatible(field, "METRIC");
            };
            case MetricExpressionPredicate expression -> switch (field) {
                case WINDOW -> new MetricExpressionPredicate(
                        expression.nodeId(), expression.expression(), windowOf(replacement),
                        expression.operator(), expression.condition());
                case VALUE -> {
                    ConditionValue condition = conditionOf(replacement);
                    requireOperatorShape(expression.operator(), condition);
                    yield new MetricExpressionPredicate(
                            expression.nodeId(), expression.expression(), expression.window(),
                            expression.operator(), condition);
                }
                case OPERATOR -> {
                    ComparisonOperator operator = operatorOf(replacement);
                    requireOperatorShape(operator, expression.condition());
                    yield new MetricExpressionPredicate(
                            expression.nodeId(), expression.expression(), expression.window(),
                            operator, expression.condition());
                }
                case REFERENCE_PIN -> throw incompatible(field, "METRIC_EXPRESSION");
            };
            case ExistsPredicate exists -> switch (field) {
                case WINDOW -> new ExistsPredicate(
                        exists.nodeId(), exists.relationId(), windowOf(replacement), exists.predicate());
                case VALUE, OPERATOR, REFERENCE_PIN -> throw incompatible(field, "EXISTS");
            };
            case GroupPredicate ignored -> throw incompatible(field, "ALL/ANY");
            case NotPredicate ignored -> throw incompatible(field, "NOT");
            // Re-pinning a reference is a resolution decision, not a field rewrite. It is supported, but only
            // through the pin pair that carries its own stale-base guard, and it still has to survive the
            // host-resolution proof before the refined document is published.
            case AudienceReferencePredicate reference -> switch (field) {
                case REFERENCE_PIN -> repin(reference, replacement);
                case WINDOW, VALUE, OPERATOR -> throw incompatible(field, "AUDIENCE_REFERENCE");
            };
        };
        return rebuild(tree, nodeId, replaced);
    }

    /**
     * Re-pins one reference node to a new exact immutable version, guarded by the pin the edit expected.
     *
     * <p>The expected pin is the reference-level base binding. If the target no longer carries it, the edit was
     * computed against a reference that has since become a different immutable version, and overwriting it
     * silently is exactly the stale-base mistake the document-level {@code currentDslHash} check prevents one
     * level up. The node keeps its stable identity, so every later artifact still keys on the same reference.
     */
    private static AudienceReferencePredicate repin(
            AudienceReferencePredicate reference, RefinementReplacement replacement) {
        if (!(replacement instanceof RefinementReplacement.ReferencePinReplacement pins)) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.FIELD_VALUE_INVALID,
                    "A reference pin replacement requires the exact pin pair");
        }
        if (!pinOf(reference).equals(pins.expected())) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.REFERENCE_BASE_STALE,
                    "The reference no longer carries the immutable pin this edit was computed against");
        }
        return new AudienceReferencePredicate(
                reference.nodeId(),
                pins.pinned().audienceId(),
                pins.pinned().versionId(),
                pins.pinned().versionHash());
    }

    /** The exact immutable pin one reference node currently carries. */
    private static AudienceVersionRef pinOf(AudienceReferencePredicate reference) {
        return new AudienceVersionRef(
                reference.audienceId(), reference.versionId(), reference.versionHash());
    }

    private static SegmentRefinementException incompatible(RefinementField field, String nodeKind) {
        return SegmentRefinementException.of(
                SegmentRefinementErrorCodes.FIELD_NODE_INCOMPATIBLE,
                "The replacement field '" + field.wireName() + "' is not supported on a " + nodeKind + " node");
    }

    private static void requireOperatorShape(ComparisonOperator operator, ConditionValue condition) {
        try {
            OperatorValueRules.require(operator, condition);
        } catch (SegmentDslException rejected) {
            // A replacement operator and the node's existing operand, or a replacement operand and the node's
            // existing operator, must still form one of the accepted T05 operator/value shapes.
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.FIELD_OPERATOR_INCOMPATIBLE,
                    "The replacement operator and operand combination is not a valid T05 shape");
        }
    }

    private static TimeWindow windowOf(RefinementReplacement replacement) {
        if (replacement instanceof RefinementReplacement.WindowReplacement window) {
            return window.window();
        }
        throw SegmentRefinementException.of(
                SegmentRefinementErrorCodes.FIELD_VALUE_INVALID, "A window replacement is required");
    }

    private static ConditionValue conditionOf(RefinementReplacement replacement) {
        if (replacement instanceof RefinementReplacement.ConditionReplacement condition) {
            return ConditionValue.of(condition.value());
        }
        throw SegmentRefinementException.of(
                SegmentRefinementErrorCodes.FIELD_VALUE_INVALID, "A value replacement is required");
    }

    private static ComparisonOperator operatorOf(RefinementReplacement replacement) {
        if (replacement instanceof RefinementReplacement.OperatorReplacement operator) {
            return operator.operator();
        }
        throw SegmentRefinementException.of(
                SegmentRefinementErrorCodes.FIELD_VALUE_INVALID, "An operator replacement is required");
    }

    // ------------------------------------------------------------------ tree helpers

    private static void requireUniqueSubtree(Predicate node) {
        try {
            PredicateNodeIds.requireUnique(node);
        } catch (SegmentDslException rejected) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.NODE_ID_COLLISION,
                    "An added subtree repeats a node identifier");
        }
    }

    private static void requireBoundedTree(Predicate tree) {
        if (nodeIds(tree).size() > RefinementLimits.MAX_TREE_NODES) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.LIMIT_EXCEEDED, "The refined tree exceeds its node bound");
        }
    }

    /** The node with the given identity, or null when it is absent. */
    private static Predicate find(Predicate tree, String nodeId) {
        if (tree.nodeId().equals(nodeId)) {
            return tree;
        }
        for (Predicate child : childrenOf(tree)) {
            Predicate found = find(child, nodeId);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** True when the tree contains the given identity, including the tree root itself. */
    private static boolean contains(Predicate tree, String nodeId) {
        return find(tree, nodeId) != null;
    }

    /** The parent of the given identity, or null when the identity is the root or is absent. */
    private static Predicate parentOf(Predicate tree, String nodeId) {
        for (Predicate child : childrenOf(tree)) {
            if (child.nodeId().equals(nodeId)) {
                return tree;
            }
            Predicate found = parentOf(child, nodeId);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static GroupPredicate requireGroup(Predicate tree, String parentNodeId) {
        Predicate parent = find(tree, parentNodeId);
        if (parent == null) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.PARENT_NOT_FOUND, "The destination parent does not exist");
        }
        if (!(parent instanceof GroupPredicate group)) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.PARENT_NOT_GROUP, "The destination parent is not an ALL/ANY group");
        }
        return group;
    }

    private static int indexOfChild(List<Predicate> children, String nodeId) {
        for (int index = 0; index < children.size(); index++) {
            if (children.get(index).nodeId().equals(nodeId)) {
                return index;
            }
        }
        return -1;
    }

    private static List<Predicate> insertBefore(List<Predicate> children, String beforeNodeId, Predicate added) {
        List<Predicate> updated = new ArrayList<>(children.size() + 1);
        if (beforeNodeId == null) {
            updated.addAll(children);
            updated.add(added);
            return updated;
        }
        int index = indexOfChild(children, beforeNodeId);
        if (index < 0) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.ANCHOR_NOT_FOUND,
                    "The sibling anchor is not a direct child of the exact destination parent");
        }
        updated.addAll(children.subList(0, index));
        updated.add(added);
        updated.addAll(children.subList(index, children.size()));
        return updated;
    }

    // ------------------------------------------------------------------ audience reference proof

    /**
     * Proves that every reference the patch introduced or re-pinned is backed by an accepted host resolution.
     *
     * <p>Only <em>new</em> references are checked. A reference that survives the patch with the exact pin it
     * already carried in the base is left alone: the document-level base hash proved that pin, so moving or
     * surrounding it needs no fresh resolution, and demanding one would make an ordinary structural edit
     * impossible. A reference whose pin changed, or one that did not exist before, is a decision about which
     * immutable version is referenced, and that decision belongs to the host.
     */
    private static void requireResolvedReferences(
            Predicate base, Predicate refined, List<AudienceReferenceEvidence> evidence) {
        List<AudienceReferenceEvidence> backing = evidence == null ? List.of() : evidence;
        Map<String, AudienceVersionRef> basePins = pinsOf(base);
        for (AudienceReferencePredicate reference : referencesOf(refined)) {
            AudienceVersionRef prior = basePins.get(reference.nodeId());
            if (prior != null && prior.equals(pinOf(reference))) {
                continue;
            }
            requireBacking(reference, backing);
        }
    }

    /** The exact pin every reference node of one tree currently carries, keyed by its stable identity. */
    private static Map<String, AudienceVersionRef> pinsOf(Predicate tree) {
        Map<String, AudienceVersionRef> pins = new LinkedHashMap<>();
        for (AudienceReferencePredicate reference : referencesOf(tree)) {
            pins.put(reference.nodeId(), pinOf(reference));
        }
        return pins;
    }

    /** Every reference node of one tree, in deterministic depth-first declaration order. */
    private static List<AudienceReferencePredicate> referencesOf(Predicate tree) {
        List<AudienceReferencePredicate> references = new ArrayList<>();
        collectReferences(tree, references);
        return references;
    }

    private static void collectReferences(Predicate node, List<AudienceReferencePredicate> references) {
        if (node instanceof AudienceReferencePredicate reference) {
            references.add(reference);
            return;
        }
        for (Predicate child : childrenOf(node)) {
            collectReferences(child, references);
        }
    }

    /**
     * Requires one accepted resolution to back one reference node's exact identity and exact pin.
     *
     * <p>The evidence is matched on the reference identity, the audience identity, the exact version identity
     * <em>and</em> the exact content hash, so "the same version" can never be satisfied by a same-identity
     * different-bytes pin. A resolution of some other reference, or of another immutable version, does not
     * justify this node and the refined document is refused rather than published.
     */
    private static void requireBacking(
            AudienceReferencePredicate reference, List<AudienceReferenceEvidence> evidence) {
        AudienceVersionRef pin = pinOf(reference);
        for (AudienceReferenceEvidence candidate : evidence) {
            if (candidate != null
                    && candidate.referenceId().equals(reference.nodeId())
                    && candidate.resolved().equals(pin)) {
                return;
            }
        }
        throw SegmentRefinementException.of(
                SegmentRefinementErrorCodes.REFERENCE_PIN_UNRESOLVED,
                "A refined audience reference is not backed by an accepted resolution of that exact version");
    }

    /** The direct children of one node in their declared order. */
    private static List<Predicate> childrenOf(Predicate node) {
        return switch (node) {
            case GroupPredicate group -> group.children();
            case NotPredicate not -> List.of(not.child());
            case ExistsPredicate exists -> List.of(exists.predicate());
            case AttributePredicate ignored -> List.of();
            case MetricPredicate ignored -> List.of();
            case MetricExpressionPredicate ignored -> List.of();
            case AudienceReferencePredicate ignored -> List.of();
        };
    }

    private static List<String> nodeIds(Predicate node) {
        List<String> ids = new ArrayList<>();
        collectIds(node, ids);
        return ids;
    }

    private static void collectIds(Predicate node, List<String> ids) {
        ids.add(node.nodeId());
        for (Predicate child : childrenOf(node)) {
            collectIds(child, ids);
        }
    }

    /**
     * Rebuilds the tree with one node replaced by identity, reusing every untouched instance.
     *
     * <p>When no descendant changed, the original instance is returned unchanged, so an operation that only
     * touches one branch cannot perturb the object equality or serialization of any unrelated confirmed node.
     */
    private static Predicate rebuild(Predicate tree, String nodeId, Predicate replacement) {
        if (tree.nodeId().equals(nodeId)) {
            return replacement;
        }
        return switch (tree) {
            case GroupPredicate group -> {
                List<Predicate> children = rebuildAll(group.children(), nodeId, replacement);
                yield children == null
                        ? group
                        : new GroupPredicate(group.nodeId(), group.type(), children);
            }
            case NotPredicate not -> {
                Predicate child = rebuild(not.child(), nodeId, replacement);
                yield child == not.child() ? not : new NotPredicate(not.nodeId(), child);
            }
            case ExistsPredicate exists -> {
                Predicate nested = rebuild(exists.predicate(), nodeId, replacement);
                yield nested == exists.predicate()
                        ? exists
                        : new ExistsPredicate(exists.nodeId(), exists.relationId(), exists.window(), nested);
            }
            case AttributePredicate ignored -> tree;
            case MetricPredicate ignored -> tree;
            case MetricExpressionPredicate ignored -> tree;
            case AudienceReferencePredicate ignored -> tree;
        };
    }

    /** Rebuilds a child list, or returns null when no child changed. */
    private static List<Predicate> rebuildAll(List<Predicate> children, String nodeId, Predicate replacement) {
        List<Predicate> updated = null;
        for (int index = 0; index < children.size(); index++) {
            Predicate child = children.get(index);
            Predicate rebuilt = rebuild(child, nodeId, replacement);
            if (rebuilt != child && updated == null) {
                updated = new ArrayList<>(children.subList(0, index));
            }
            if (updated != null) {
                updated.add(rebuilt);
            }
        }
        return updated;
    }
}