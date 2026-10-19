package com.company.campaign.evaluation.segment.contract;

/**
 * The refinement operations whose targets a corpus case may declare.
 *
 * <p>The names match Segment specification section 16 exactly. T03 only reserves the operation names so a
 * corpus case can state which edit it exercises; the patch contract itself, its stable node identity and
 * its base-hash check belong to T12 and are not implemented here.
 */
public enum RefinementOperation {
    ADD_NODE,
    REMOVE_NODE,
    REPLACE_FIELD,
    MOVE_NODE
}