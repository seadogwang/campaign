package com.company.campaign.builder.segment;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The closed, bounded description of which provider call one {@link SegmentModelRequest} is.
 *
 * <p>A first-pass call carries no issue codes: there is nothing to repair yet. A repair call carries only the
 * stable machine-readable codes of the strict model-output/schema rejection it repairs — never provider prose,
 * a stack trace, an exception message, SQL, a Metadata projection, a trusted identifier or member data. The
 * code set is closed to the adapter's own stable vocabulary and bounded in size, so a repair request can never
 * widen into a second, unrestricted payload.
 */
public record SegmentModelCallContext(SegmentModelCallKind kind, List<String> schemaIssueCodes) {

    /** The exact number of schema issue codes one repair call may carry. */
    public static final int MAX_SCHEMA_ISSUE_CODES = 8;

    private static final Pattern ISSUE_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    /** The context of the one first-pass call. */
    public static final SegmentModelCallContext FIRST_PASS =
            new SegmentModelCallContext(SegmentModelCallKind.FIRST_PASS, List.of());

    public SegmentModelCallContext {
        if (kind == null) {
            throw SegmentAdapterException.invalid(
                    SegmentAdapterErrorCodes.REQUEST_INVALID, "A model call context requires its kind");
        }
        if (schemaIssueCodes == null) {
            schemaIssueCodes = List.of();
        }
        if (schemaIssueCodes.size() > MAX_SCHEMA_ISSUE_CODES) {
            throw SegmentAdapterException.invalid(
                    SegmentAdapterErrorCodes.REQUEST_INVALID, "A model call context exceeds its issue-code bound");
        }
        List<String> copy = new ArrayList<>(schemaIssueCodes.size());
        for (String code : schemaIssueCodes) {
            if (code == null || !ISSUE_CODE.matcher(code).matches()) {
                throw SegmentAdapterException.invalid(
                        SegmentAdapterErrorCodes.REQUEST_INVALID,
                        "A schema issue code must be a bounded machine code");
            }
            copy.add(code);
        }
        schemaIssueCodes = List.copyOf(copy);
        if (kind == SegmentModelCallKind.FIRST_PASS && !schemaIssueCodes.isEmpty()) {
            throw SegmentAdapterException.invalid(
                    SegmentAdapterErrorCodes.REQUEST_INVALID,
                    "A first-pass call carries no schema issue codes");
        }
        if (kind == SegmentModelCallKind.SCHEMA_REPAIR && schemaIssueCodes.isEmpty()) {
            throw SegmentAdapterException.invalid(
                    SegmentAdapterErrorCodes.REQUEST_INVALID,
                    "A schema-repair call requires the captured first-pass issue codes");
        }
    }

    /** The context of one schema-repair call, built only from captured first-pass issue codes. */
    public static SegmentModelCallContext schemaRepair(List<String> schemaIssueCodes) {
        return new SegmentModelCallContext(SegmentModelCallKind.SCHEMA_REPAIR, schemaIssueCodes);
    }
}