package com.company.campaign.builder.tool.reference;

import com.company.campaign.contracts.ContractPatterns;

/**
 * One registered, host-owned, read-only Existing Audience Reference Tool contract.
 *
 * <p>The contract states the four things a caller may rely on and a test can pin: the server-owned identifier,
 * its exact version, the one bounded answer shape it produces and the absolute number of items that answer may
 * carry. It declares no payload schema a caller could extend, no backend, no capability to write, retain,
 * release, delete or publish anything, and no way to register another Tool: the registry is closed and
 * server-owned.
 */
public record ReferenceToolContract(
        String toolId,
        String toolVersion,
        ReferenceToolOutputKind outputKind,
        int maxResultItems
) {

    /** The absolute bound on items any registered reference Tool answer may carry. */
    public static final int MAX_RESULT_ITEMS = 256;

    public ReferenceToolContract {
        if (toolId == null || !toolId.matches(ContractPatterns.REFERENCE_ID)) {
            throw ReferenceToolException.invalid(
                    ReferenceToolErrorCodes.REFERENCE_TOOL_UNKNOWN,
                    "A reference Tool contract requires a bounded server-owned identifier");
        }
        if (toolVersion == null || toolVersion.isBlank()) {
            throw ReferenceToolException.invalid(
                    ReferenceToolErrorCodes.REFERENCE_TOOL_UNKNOWN,
                    "A reference Tool contract requires an exact version");
        }
        if (outputKind == null) {
            throw ReferenceToolException.invalid(
                    ReferenceToolErrorCodes.REFERENCE_TOOL_UNKNOWN,
                    "A reference Tool contract requires its one answer shape");
        }
        if (maxResultItems < 1 || maxResultItems > MAX_RESULT_ITEMS) {
            throw ReferenceToolException.invalid(
                    ReferenceToolErrorCodes.REFERENCE_TOOL_OUTPUT_LIMIT_EXCEEDED,
                    "A reference Tool contract item bound is invalid");
        }
    }

    /** True only for the contract whose answer is a bounded discovery summary list. */
    public boolean isDiscovery() {
        return outputKind == ReferenceToolOutputKind.DISCOVERY;
    }

    /** True only for the contract whose answer is one deterministic resolution outcome. */
    public boolean isResolution() {
        return outputKind == ReferenceToolOutputKind.RESOLUTION;
    }
}