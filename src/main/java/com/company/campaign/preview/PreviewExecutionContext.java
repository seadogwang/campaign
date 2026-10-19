package com.company.campaign.preview;

/**
 * The safe execution context a Preview response may expose.
 *
 * <p>It carries only the pinned Metadata Snapshot identifier, the trusted evaluation instant, the timezone
 * identifier and the data watermark — all rendered as ISO-8601 text so the wire form is deterministic and does
 * not depend on any object-mapper date configuration. It deliberately carries no tenant, program, user,
 * membership, DataScope, permission, reporting currency or snapshot content hash.
 */
public record PreviewExecutionContext(
        String metadataSnapshotId,
        String asOf,
        String timezone,
        String dataWatermark
) {

    public PreviewExecutionContext {
        if (metadataSnapshotId == null || asOf == null || timezone == null || dataWatermark == null) {
            throw PreviewException.of(PreviewErrorCodes.EXECUTION_RESULT_INCOHERENT, "executionContext");
        }
    }
}