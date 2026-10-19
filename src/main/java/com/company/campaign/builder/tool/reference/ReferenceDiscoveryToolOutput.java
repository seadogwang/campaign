package com.company.campaign.builder.tool.reference;

import com.company.campaign.segment.reference.AudienceSummary;

import java.util.ArrayList;
import java.util.List;

/**
 * The bounded, non-disclosing answer of the read-only discovery Tool.
 *
 * <p>It carries only already-safe {@link AudienceSummary} values: a stable audience identity and one safe
 * presentation label each. There is deliberately no owner, program, DataScope, permission, version pin,
 * publication state, member identifier or count of what the caller cannot see, so an answer cannot be used to
 * enumerate foreign tenants or forbidden programs.
 *
 * <p>The list is bounded twice over: the directory already refuses an over-bounded discovery, and this record
 * re-proves the same bound against the contract that produced it, so a corrupt or hostile directory that
 * answers with more summaries than the contract permits is refused rather than silently truncated into a
 * different, quieter answer.
 */
public record ReferenceDiscoveryToolOutput(List<AudienceSummary> audiences) {

    public ReferenceDiscoveryToolOutput {
        if (audiences == null) {
            throw ReferenceToolException.invalid(
                    ReferenceToolErrorCodes.REFERENCE_TOOL_INCONSISTENT,
                    "A discovery answer requires its bounded summary list");
        }
        if (audiences.size() > ReferenceToolContract.MAX_RESULT_ITEMS) {
            throw ReferenceToolException.invalid(
                    ReferenceToolErrorCodes.REFERENCE_TOOL_OUTPUT_LIMIT_EXCEEDED,
                    "A discovery answer exceeds the bound its contract declared");
        }
        List<AudienceSummary> copy = new ArrayList<>(audiences.size());
        for (AudienceSummary summary : audiences) {
            if (summary == null) {
                throw ReferenceToolException.invalid(
                        ReferenceToolErrorCodes.REFERENCE_TOOL_INCONSISTENT,
                        "A discovery answer must not carry a null summary");
            }
            copy.add(summary);
        }
        audiences = List.copyOf(copy);
    }

    /** The number of audiences discovered; never a count of anything the caller cannot see. */
    public int size() {
        return audiences.size();
    }

    /** True when nothing matched, which is also what an audience the caller may not see looks like. */
    public boolean isEmpty() {
        return audiences.isEmpty();
    }

    /** A bounded diagnostic: the identity and the label length, never the label text. */
    @Override
    public String toString() {
        return "ReferenceDiscoveryToolOutput[audiences=" + audiences.size() + ']';
    }
}