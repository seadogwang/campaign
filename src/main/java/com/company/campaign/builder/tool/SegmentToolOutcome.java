package com.company.campaign.builder.tool;

import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The bounded, immutable result value of one Tool call.
 *
 * <p>It carries only a stable outcome code, a bounded item count, the content hash of the accepted evidence and
 * a bounded list of stable semantic references (definition ids, clause ids, predicate node ids or structural
 * preview paths). It deliberately cannot carry a raw member id, a sample value, a transaction, a credential,
 * SQL, a storage catalog/table/column name, an internal exception message, the provider output or the full DSL:
 * none of those is representable in this record.
 */
public record SegmentToolOutcome(
        String toolId,
        String outcomeCode,
        int itemCount,
        ContentHash evidenceHash,
        List<String> stableReferences
) {

    private static final Pattern REFERENCE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");

    public SegmentToolOutcome {
        if (toolId == null || outcomeCode == null || evidenceHash == null) {
            throw SegmentToolException.invalid(
                    SegmentToolErrorCodes.TOOL_RESULT_INCOHERENT, "A Tool outcome requires its identity and evidence hash");
        }
        if (!outcomeCode.matches("^[A-Z][A-Z0-9_]{0,63}$")) {
            throw SegmentToolException.invalid(
                    SegmentToolErrorCodes.TOOL_RESULT_INCOHERENT, "A Tool outcome code must be a stable machine code");
        }
        if (itemCount < 0) {
            throw SegmentToolException.invalid(
                    SegmentToolErrorCodes.TOOL_RESULT_INCOHERENT, "A Tool outcome item count must not be negative");
        }
        stableReferences = stableReferences == null ? List.of() : List.copyOf(new ArrayList<>(stableReferences));
        if (stableReferences.size() > SegmentToolDescriptor.MAX_RESULT_ITEMS) {
            throw SegmentToolException.invalid(
                    SegmentToolErrorCodes.TOOL_OUTPUT_LIMIT_EXCEEDED, "A Tool outcome exceeds its reference limit");
        }
        for (String reference : stableReferences) {
            if (reference == null || !REFERENCE.matcher(reference).matches()) {
                throw SegmentToolException.invalid(
                        SegmentToolErrorCodes.TOOL_RESULT_INCOHERENT,
                        "A Tool outcome reference must be a bounded stable identifier");
            }
        }
    }
}