package com.company.campaign.builder.assembly;

import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The bounded, immutable public result value of one Campaign Tool call.
 *
 * <p>It carries only a stable outcome code, a bounded item count, the content hash of the accepted evidence
 * and a bounded list of stable semantic references (catalog reference codes and logical identifiers). It cannot
 * carry a template body, a supplied variable value, a reward secret, a Provider setting, a credential, an
 * inventory fact, SQL, a member/contact identifier or an internal exception message: none of those is
 * representable in this record.
 */
public record CampaignToolOutcome(
        String toolId,
        String outcomeCode,
        int itemCount,
        ContentHash evidenceHash,
        List<String> stableReferences
) {

    private static final Pattern REFERENCE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");

    public CampaignToolOutcome {
        if (toolId == null || outcomeCode == null || evidenceHash == null) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT,
                    "A Tool outcome requires its identity and evidence hash.");
        }
        if (!outcomeCode.matches("^[A-Z][A-Z0-9_]{0,63}$")) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT,
                    "A Tool outcome code must be a stable machine code.");
        }
        if (itemCount < 0) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT,
                    "A Tool outcome item count must not be negative.");
        }
        stableReferences = stableReferences == null ? List.of() : List.copyOf(new ArrayList<>(stableReferences));
        if (stableReferences.size() > CampaignToolDescriptor.MAX_RESULT_ITEMS) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.TOOL_RESULT_LIMIT_EXCEEDED,
                    "A Tool outcome exceeds its reference limit.");
        }
        for (String reference : stableReferences) {
            if (reference == null || !REFERENCE.matcher(reference).matches()) {
                throw CampaignAssemblyException.invalid(
                        CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT,
                        "A Tool outcome reference must be a bounded stable identifier.");
            }
        }
    }
}