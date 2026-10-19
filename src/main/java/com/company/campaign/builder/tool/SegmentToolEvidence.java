package com.company.campaign.builder.tool;

import com.company.campaign.segment.dsl.predicate.ExistsPredicate;
import com.company.campaign.segment.dsl.predicate.GroupPredicate;
import com.company.campaign.segment.dsl.predicate.NotPredicate;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.resolution.ClauseResolution;
import com.company.campaign.segment.resolution.ResolutionResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Bounded, semantic evidence extraction for Tool outcomes.
 *
 * <p>Only stable, non-restricted semantic identifiers are ever collected: predicate node ids, clause ids and
 * accepted Metadata definition ids. Raw member ids, sample values, storage names, SQL and internal messages are
 * not producible here.
 */
final class SegmentToolEvidence {

    private SegmentToolEvidence() {
    }

    /** Every predicate node id in the document, in a deterministic pre-order. */
    static List<String> predicateNodeIds(Predicate root) {
        List<String> ids = new ArrayList<>();
        collect(root, ids);
        return List.copyOf(ids);
    }

    /** Every clause id the deterministic resolution accepted, in intent order. */
    static List<String> acceptedDefinitionIds(ResolutionResult resolution) {
        List<String> ids = new ArrayList<>();
        for (ClauseResolution clause : resolution.clauses()) {
            if (clause.accepted() != null) {
                ids.add(clause.accepted());
            }
        }
        return List.copyOf(ids);
    }

    private static void collect(Predicate predicate, List<String> ids) {
        if (predicate == null) {
            return;
        }
        ids.add(predicate.nodeId());
        if (predicate instanceof GroupPredicate group) {
            for (Predicate child : group.children()) {
                collect(child, ids);
            }
        } else if (predicate instanceof NotPredicate not) {
            collect(not.child(), ids);
        } else if (predicate instanceof ExistsPredicate exists) {
            collect(exists.predicate(), ids);
        }
    }
}