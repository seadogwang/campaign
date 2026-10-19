package com.company.campaign.builder.assembly;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.canonical.ContentHash;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * The bounded, non-disclosing safe card of one resolved Reward version.
 *
 * <p>It carries the reward kind and currency, bounded numeric exposure and hashes only: the stable
 * reference/identity, the immutable version content hash, the exact face value, the bounded quantity, the
 * per-recipient and declared maximum exposure, the normalized payload hash and the catalog hash. It never
 * carries a reward secret, Provider configuration, a mutable inventory quantity, a balance, a grant result, a
 * credential or SQL — none of those is representable here.
 */
public record RewardSafePreviewCard(
        String referenceCode,
        String logicalId,
        String versionId,
        ContentHash contentHash,
        String kind,
        String currency,
        BigDecimal faceValue,
        int quantity,
        BigDecimal perRecipientExposure,
        BigDecimal maximumExposure,
        ContentHash normalizedPayloadHash,
        ContentHash catalogHash
) {

    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);
    private static final Pattern KIND = Pattern.compile(ContractPatterns.MACHINE_CODE);
    private static final Pattern CURRENCY = Pattern.compile(ContractPatterns.CURRENCY);

    public RewardSafePreviewCard {
        if (referenceCode == null || !IDENTIFIER.matcher(referenceCode).matches()
                || logicalId == null || !IDENTIFIER.matcher(logicalId).matches()
                || versionId == null || !IDENTIFIER.matcher(versionId).matches()) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT,
                    "A Reward safe card requires its bounded stable identity.");
        }
        if (kind == null || !KIND.matcher(kind).matches()) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT,
                    "A Reward safe card requires its reward kind code.");
        }
        if (currency == null || !CURRENCY.matcher(currency).matches()) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT,
                    "A Reward safe card requires its ISO-4217 currency.");
        }
        if (faceValue == null || perRecipientExposure == null || maximumExposure == null) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT,
                    "A Reward safe card requires its bounded numeric exposure.");
        }
        if (quantity < 1) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT,
                    "A Reward safe card requires a positive quantity.");
        }
        if (contentHash == null || normalizedPayloadHash == null || catalogHash == null) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT,
                    "A Reward safe card requires its catalog, content and payload hashes.");
        }
    }

    /** Redacted diagnostics: only bounded machine codes, bounded amounts and hashes. */
    @Override
    public String toString() {
        return "RewardSafePreviewCard[referenceCode=" + referenceCode
                + ", versionId=" + versionId
                + ", kind=" + kind
                + ", currency=" + currency
                + ", quantity=" + quantity
                + ", maximumExposure=" + maximumExposure.toPlainString()
                + ", contentHash=" + contentHash
                + ", payloadHash=" + normalizedPayloadHash + ']';
    }
}