package com.company.campaign.builder.refine;

import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.dsl.predicate.PredicateNodeIds;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.List;

/**
 * The smallest immutable typed receipt of one atomically accepted T12 refinement patch.
 *
 * <p>It is produced only by {@link #of(SegmentDsl, SegmentRefinementPatch, SegmentDsl, List)}, which is called
 * only after {@link SegmentRefinementEditor#applyWithEvidence} returned the refined document and its accepted
 * operation-time parent evidence. The receipt therefore projects the accepted typed patch after atomic
 * application: it restates the patch's ordered operations with their stable identities, and it cannot describe
 * an operation the editor did not accept.
 *
 * <p>It exists because a structural diff of the base and refined trees cannot honestly observe refinement
 * semantics: a same-parent {@code MOVE_NODE} leaves an identical tree shape, ordered multi-operation patches
 * collapse into one guessed effect, and any precedence rule can pick the wrong operation. The receipt carries
 * the ordered accepted operations with their stable identities instead of inferring them, and the parent of a
 * remove or a replacement is the operation-time parent the editor observed, never the node's original
 * base-tree parent, which an earlier operation may have moved away.
 *
 * <p>It is evaluation-only observation evidence. It carries no raw JSON, no array index, no trusted context
 * and no model text, it is never exposed to the model, and it cannot change the T12 result: the returned
 * {@link SegmentRefinementResult} is published from the same accepted artifacts either way.
 */
public final class SegmentRefinementReceipt {

    private final ContentHash baseDslHash;
    private final ContentHash refinedDslHash;
    private final List<Operation> operations;

    private SegmentRefinementReceipt(
            ContentHash baseDslHash, ContentHash refinedDslHash, List<Operation> operations) {
        this.baseDslHash = baseDslHash;
        this.refinedDslHash = refinedDslHash;
        this.operations = List.copyOf(operations);
    }

    /**
     * Builds the receipt of one accepted patch from the editor's own application evidence.
     *
     * <p>Every operation is reported in the patch's declared order with its stable target, operation-time
     * parent and optional sibling anchor. {@code operationParents} is the per-operation parent list the genuine
     * editor observed while it applied the patch, so a parent for a remove or a replacement is the enclosing
     * parent at that operation's turn rather than the node's original base-tree parent. The two hashes are the
     * accepted base and refined editable DSL hashes, so a reader can bind the receipt to exactly the documents
     * that produced it.
     *
     * @throws SegmentRefinementException when the evidence does not describe exactly the accepted patch, so a
     *     receipt can never be assembled from an unrelated parent list
     */
    public static SegmentRefinementReceipt of(
            SegmentDsl base,
            SegmentRefinementPatch patch,
            SegmentDsl refined,
            List<String> operationParents) {
        if (base == null || patch == null || refined == null || operationParents == null) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.NULL_FIELD,
                    "A refinement receipt requires the base document, the accepted patch and the refined document");
        }
        if (operationParents.size() != patch.operations().size()) {
            throw SegmentRefinementException.of(
                    SegmentRefinementErrorCodes.NULL_FIELD,
                    "A refinement receipt requires one accepted parent observation per accepted operation");
        }
        List<Operation> operations = new ArrayList<>(patch.operations().size());
        for (int index = 0; index < patch.operations().size(); index++) {
            operations.add(describe(patch.operations().get(index), operationParents.get(index)));
        }
        return new SegmentRefinementReceipt(base.editableDslHash(), refined.editableDslHash(), operations);
    }

    private static Operation describe(SegmentRefinementOperation operation, String operationTimeParent) {
        return switch (operation) {
            case SegmentRefinementOperation.AddNode add -> new Operation(
                    add.operationId(), add.kind(), add.node().nodeId(), add.parentNodeId(), add.beforeNodeId());
            case SegmentRefinementOperation.RemoveNode remove -> new Operation(
                    remove.operationId(), remove.kind(), remove.nodeId(), operationTimeParent, null);
            case SegmentRefinementOperation.ReplaceField replace -> new Operation(
                    replace.operationId(), replace.kind(), replace.nodeId(), operationTimeParent, null);
            case SegmentRefinementOperation.MoveNode move -> new Operation(
                    move.operationId(), move.kind(), move.nodeId(), move.parentNodeId(), move.beforeNodeId());
        };
    }

    /** The accepted base document's editable hash. */
    public ContentHash baseDslHash() {
        return baseDslHash;
    }

    /** The accepted refined document's editable hash. */
    public ContentHash refinedDslHash() {
        return refinedDslHash;
    }

    /** The accepted operations in their declared order. */
    public List<Operation> operations() {
        return operations;
    }

    /**
     * One accepted operation's stable identities.
     *
     * <p>{@code targetNodeId} is the stable node the operation names, {@code parentNodeId} is its destination
     * parent for an add or a move and its operation-time enclosing parent for a remove or a replacement, and
     * {@code beforeNodeId} is the optional stable sibling anchor where the operation admits one.
     *
     * <p>{@code parentNodeId} is {@code null} either when the operation genuinely has no parent to report (a
     * replacement of the root node) or when the accepted editor evidence does not establish one. It is never
     * the node's original base-tree parent once an earlier operation has moved it.
     */
    public record Operation(
            String operationId,
            RefinementOpKind kind,
            String targetNodeId,
            String parentNodeId,
            String beforeNodeId) {

        public Operation {
            if (operationId == null || kind == null || targetNodeId == null) {
                throw SegmentRefinementException.of(
                        SegmentRefinementErrorCodes.NULL_FIELD,
                        "An accepted refinement operation requires its stable identity and kind");
            }
            PredicateNodeIds.requireNodeId(targetNodeId);
            if (parentNodeId != null) {
                PredicateNodeIds.requireNodeId(parentNodeId);
            }
            if (beforeNodeId != null) {
                PredicateNodeIds.requireNodeId(beforeNodeId);
            }
        }
    }
}