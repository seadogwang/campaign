package com.company.campaign.builder.assembly;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.regex.Pattern;

/**
 * The bounded, non-disclosing safe card of one resolved Message Template version.
 *
 * <p>It carries codes, the delivery channel and hashes only: the stable reference/identity, the immutable
 * version content hash, the normalized payload hash, the catalog hash and the classification code. It never
 * carries the template body, a supplied variable value, recipient or contact data, Provider configuration, a
 * credential, SQL or a member identifier — none of those is representable here.
 */
public record MessageSafePreviewCard(
        String referenceCode,
        String logicalId,
        String versionId,
        ContentHash contentHash,
        String channel,
        String classificationCode,
        int variableCount,
        ContentHash normalizedPayloadHash,
        ContentHash catalogHash
) {

    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);
    private static final Pattern CHANNEL = Pattern.compile(ContractPatterns.MACHINE_CODE);
    private static final Pattern CLASSIFICATION = Pattern.compile(ContractPatterns.MACHINE_CODE);

    public MessageSafePreviewCard {
        if (referenceCode == null || !IDENTIFIER.matcher(referenceCode).matches()
                || logicalId == null || !IDENTIFIER.matcher(logicalId).matches()
                || versionId == null || !IDENTIFIER.matcher(versionId).matches()) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT,
                    "A Message safe card requires its bounded stable identity.");
        }
        if (channel == null || !CHANNEL.matcher(channel).matches()) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT,
                    "A Message safe card requires its channel machine code.");
        }
        if (classificationCode == null || !CLASSIFICATION.matcher(classificationCode).matches()) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT,
                    "A Message safe card requires its classification code.");
        }
        if (variableCount < 0) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT,
                    "A Message safe card variable count must not be negative.");
        }
        if (contentHash == null || normalizedPayloadHash == null || catalogHash == null) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT,
                    "A Message safe card requires its catalog, content and payload hashes.");
        }
    }

    /** Redacted diagnostics: only bounded machine codes and hashes. */
    @Override
    public String toString() {
        return "MessageSafePreviewCard[referenceCode=" + referenceCode
                + ", versionId=" + versionId
                + ", channel=" + channel
                + ", classification=" + classificationCode
                + ", variables=" + variableCount
                + ", contentHash=" + contentHash
                + ", payloadHash=" + normalizedPayloadHash + ']';
    }
}