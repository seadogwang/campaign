package com.company.campaign.builder.refine;

import com.company.campaign.segment.dsl.predicate.AttributePredicate;
import com.company.campaign.segment.dsl.predicate.AudienceReferencePredicate;
import com.company.campaign.segment.dsl.predicate.ExistsPredicate;
import com.company.campaign.segment.dsl.predicate.GroupPredicate;
import com.company.campaign.segment.dsl.predicate.MetricExpressionPredicate;
import com.company.campaign.segment.dsl.predicate.MetricPredicate;
import com.company.campaign.segment.dsl.predicate.NotPredicate;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.resolution.ClauseResolution;
import com.company.campaign.segment.resolution.ResolutionResult;

/**
 * The honesty test for reusing a conversation's prior grounded explanation after a structural edit.
 *
 * <p>A refinement run performs no Intent resolution, so it can only rebuild an explanation when the host still
 * holds the conversation's fully resolved prior resolution <em>and</em> the edit provably leaves every accepted
 * clause tied to the deterministic compiled clause node it was compiled to.
 *
 * <p>The proof is deliberately conservative. A patch that contains an {@code ADD_NODE} or {@code REMOVE_NODE}
 * changes which nodes exist, so the accepted clause-to-node correspondence can no longer be assumed and the
 * explanation is skipped entirely, even when a removed clause's definition is still referenced by some other
 * node. A patch containing only {@code MOVE_NODE} and supported {@code REPLACE_FIELD} operations preserves node
 * identity and every definition binding, so it may rebuild — but only when each accepted clause's definition is
 * bound by exactly one node of the refined document. Zero binding nodes (an absent lowering) or several (an
 * ambiguous or specially lowered form such as calendar month buckets) means the exact clause-to-node proof is
 * unavailable, and the explanation is skipped rather than guessed.
 *
 * <p>Nothing here produces a claim, orders one or writes prose: it only decides whether the genuine
 * {@code explanation.grounded} builder may run. The decision reads the typed patch, the prior resolution and the
 * typed refined predicate; it never inspects raw JSON, natural language or operand values.
 */
final class SegmentRefinementGrounding {

    private SegmentRefinementGrounding() {
    }

    /** True when the prior resolution can honestly ground an explanation of the refined document. */
    static boolean canRebuild(ResolutionResult prior, Predicate refined, SegmentRefinementPatch patch) {
        if (prior == null || !prior.isResolved() || refined == null || patch == null) {
            return false;
        }
        if (changesNodeExistence(patch)) {
            return false;
        }
        for (ClauseResolution clause : prior.clauses()) {
            if (clause.accepted() == null) {
                continue;
            }
            // Exactly one node must bind the accepted definition: that is the clause's compiled node, not
            // merely "the same definition somewhere else" in the refined tree.
            if (countBindingNodes(refined, clause.accepted()) != 1) {
                return false;
            }
        }
        return true;
    }

    /** True when the patch adds or removes nodes, which invalidates any clause-to-node correspondence. */
    private static boolean changesNodeExistence(SegmentRefinementPatch patch) {
        for (SegmentRefinementOperation operation : patch.operations()) {
            if (operation instanceof SegmentRefinementOperation.AddNode
                    || operation instanceof SegmentRefinementOperation.RemoveNode) {
                return true;
            }
        }
        return false;
    }

    /** How many nodes of the refined tree bind exactly the given accepted definition. */
    private static int countBindingNodes(Predicate predicate, String definitionId) {
        if (predicate == null) {
            return 0;
        }
        return switch (predicate) {
            case AttributePredicate attribute -> attribute.attributeId().equals(definitionId) ? 1 : 0;
            case MetricPredicate metric -> metric.metricId().equals(definitionId) ? 1 : 0;
            case ExistsPredicate exists -> exists.relationId().equals(definitionId) ? 1 : 0;
            // An expression has no single binding of its own; any definition it mentions is not a proof that
            // this clause's node still exists, so it contributes nothing.
            case MetricExpressionPredicate ignored -> 0;
            // A reference binds an immutable Audience Version, not a Metadata definition, so it can never be
            // the evidence that a refined clause still mentions the accepted definition ID.
            case AudienceReferencePredicate ignored -> 0;
            case NotPredicate not -> countBindingNodes(not.child(), definitionId);
            case GroupPredicate group -> {
                int total = 0;
                for (Predicate child : group.children()) {
                    total += countBindingNodes(child, definitionId);
                }
                yield total;
            }
        };
    }
}