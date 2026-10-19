package com.company.campaign.builder.segment;

import com.company.campaign.contracts.Disposition;
import com.company.campaign.segment.dsl.contract.SegmentDsl;

/**
 * The immutable outcome of compiling one resolved Intent into a typed Segment DSL.
 *
 * <p>On success it carries {@link Disposition#RESOLVED} and the published document. On failure it carries one
 * of the four non-resolved dispositions, one stable {@link SegmentDslCompilationErrorCodes} code and the
 * optional clause id the failure belongs to. It never carries a model-generated DSL, node id or hash.
 */
public record DslCompilationResult(
        Disposition disposition,
        SegmentDsl document,
        String failureCode,
        String failureClauseId
) {

    public DslCompilationResult {
        if (disposition == null) {
            throw new IllegalArgumentException("A DSL compilation result requires a disposition");
        }
        if (disposition.isResolved() != (document != null)) {
            throw new IllegalArgumentException("Only a resolved compilation may carry a document");
        }
        if (disposition.isResolved() != (failureCode == null)) {
            throw new IllegalArgumentException("Only a non-resolved compilation may carry a failure code");
        }
    }

    /** True only when the Intent was compiled to a document. */
    public boolean isResolved() {
        return disposition.isResolved();
    }

    static DslCompilationResult resolved(SegmentDsl document) {
        return new DslCompilationResult(Disposition.RESOLVED, document, null, null);
    }

    static DslCompilationResult failed(Disposition disposition, String code, String clauseId) {
        return new DslCompilationResult(disposition, null, code, clauseId);
    }
}