package com.company.campaign.evaluation.segment.validation;

import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.segment.dsl.contract.MetadataBinding;
import com.company.campaign.segment.dsl.contract.SegmentDsl;

/**
 * Translates one reviewed expected Segment DSL onto the exact frozen Metadata snapshot.
 *
 * <p>A corpus expected document is reviewed evidence pinned by the corpus Metadata catalog: its binding
 * names the reviewed snapshot id and version and the catalog descriptor's content hash for the payload. The
 * accepted T05 binding instead pins the snapshot's own material content hash, which T04 recomputes on parse,
 * and T07 verifies exactly that. Both pins name the same reviewed snapshot — the runner proves the id,
 * version and content-hash agreement before calling this translation — so the reviewed semantics are
 * preserved and only the pin convention changes.
 *
 * <p>Only the editable hash is affected: the document's schema version, subject and predicate tree are
 * carried over unchanged. The translated document is what the genuine T07 validator, T08 planner and the
 * expected-evidence freeze consume, so an expected canonical AST hash and Query Plan hash are always produced
 * against the exact frozen Metadata the run executes under.
 */
public final class ReviewedExpectedEvidence {

    private ReviewedExpectedEvidence() {
    }

    /** The same reviewed document, re-pinned to exactly the frozen snapshot it was reviewed against. */
    public static SegmentDsl pinTo(SegmentDsl reviewed, MetadataSnapshot snapshot) {
        if (reviewed == null || snapshot == null) {
            throw new IllegalArgumentException("Reviewed evidence and its snapshot are both required");
        }
        return SegmentDsl.publish(
                reviewed.schemaVersion(), reviewed.subject(),
                MetadataBinding.fromSnapshot(snapshot), reviewed.predicate());
    }
}