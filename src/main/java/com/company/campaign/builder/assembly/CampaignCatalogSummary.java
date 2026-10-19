package com.company.campaign.builder.assembly;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.regex.Pattern;

/**
 * One bounded, non-disclosing summary of an immutable catalog entry.
 *
 * <p>It is what a discovery Tool projects: the exact reference code and stable identity, the content hash that
 * pins the immutable version, and the closed availability facts. It deliberately carries no template body, no
 * reward secret, no Provider configuration, no credential, no inventory quantity and no display name, so a
 * discovery result can never leak a restricted asset.
 */
public record CampaignCatalogSummary(
        String referenceCode,
        String logicalId,
        String versionId,
        ContentHash contentHash,
        String kindCode,
        boolean enabled,
        boolean runtimeAvailable
) {

    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public CampaignCatalogSummary {
        if (referenceCode == null || !IDENTIFIER.matcher(referenceCode).matches()) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT,
                    "A catalog summary requires a bounded reference code.");
        }
        if (logicalId == null || !IDENTIFIER.matcher(logicalId).matches()
                || versionId == null || !IDENTIFIER.matcher(versionId).matches()) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT,
                    "A catalog summary requires a bounded stable identity.");
        }
        if (contentHash == null || kindCode == null) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT,
                    "A catalog summary requires its hash pin and kind code.");
        }
    }

    /** Redacted diagnostics: only bounded machine facts are printed. */
    @Override
    public String toString() {
        return "CampaignCatalogSummary[referenceCode=" + referenceCode
                + ", versionId=" + versionId
                + ", kind=" + kindCode
                + ", enabled=" + enabled
                + ", runtimeAvailable=" + runtimeAvailable + ']';
    }
}