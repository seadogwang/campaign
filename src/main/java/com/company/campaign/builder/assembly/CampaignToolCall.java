package com.company.campaign.builder.assembly;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.contracts.SchemaVersion;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One typed Campaign Tool call: the exact identifiers a caller names plus the closed typed request body.
 *
 * <p>The envelope is deliberately not generic: {@link #request()} is a {@link CampaignToolRequest}, so a caller
 * can never bind the body to a plain {@code Map} or a raw JSON document. The declared {@code toolId} must equal
 * the identifier the typed request itself addresses; when it does not, or when no typed request is present, the
 * invoker refuses the call as incoherent <em>before</em> any handler runs. The envelope therefore cannot make
 * one Tool execute another Tool's body.
 */
public record CampaignToolCall(
        SchemaVersion schemaVersion,
        String toolCallId,
        String toolId,
        String toolVersion,
        CampaignToolRequest request
) {

    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public CampaignToolCall {
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        if (toolCallId == null || !IDENTIFIER.matcher(toolCallId).matches()) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT,
                    "A Tool call requires a bounded call identifier.");
        }
        if (toolId == null || !IDENTIFIER.matcher(toolId).matches()) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT,
                    "A Tool call requires a bounded Tool identifier.");
        }
        if (toolVersion == null || !IDENTIFIER.matcher(toolVersion).matches()) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT,
                    "A Tool call requires a bounded Tool version.");
        }
    }

    /** True when the envelope carries the typed body of exactly the Tool it names. */
    public boolean coherent() {
        return request != null && toolId.equals(request.toolId());
    }

    /** A bounded diagnostic: the identity and the version, never a request value. */
    @Override
    public String toString() {
        return "CampaignToolCall[toolId=" + toolId + ", toolVersion=" + toolVersion + ']';
    }
}