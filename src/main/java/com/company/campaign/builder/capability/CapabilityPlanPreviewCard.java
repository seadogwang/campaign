package com.company.campaign.builder.capability;

import com.company.campaign.builder.compiler.CompilerErrorCodes;
import com.company.campaign.builder.compiler.CompilerException;
import com.company.campaign.builder.compiler.RuntimeCompatibility;
import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One closed, immutable, hash-bound capability preview card.
 *
 * <p>This is the generic T19 projection a resolved capability component contributes to a {@code CampaignPlan},
 * alongside but independent of the accepted Segment {@code PlanPreviewCard}. It is capability-agnostic: it
 * names no capability, slot or bound of any concrete pack and carries only already-approved safe facts — the
 * resolved component's stable logical identity, its exact capability code and contract version, the normalized
 * payload hash, the optional canonical resolved-catalog-reference hash, the frozen runtime compatibility, two
 * bounded machine-code lists and the evaluation asset hash.
 *
 * <p>It never carries a name, a message body, a description, a raw variable, a credential, a provider output,
 * a member identifier, SQL, unrestricted exception text or a trusted security context — none of those is
 * representable here, and every free-form value is re-proved bounded and closed at construction even when a
 * card is built from raw values.
 *
 * <p>The card kind and the card contract version are closed codes: a card may only declare a kind and version
 * this build knows, so an invented projection fails closed rather than entering the plan material.
 *
 * <p>The two code lists are canonical: each is bounded, duplicate-free and stored in one deterministic order,
 * so two cards that carry the same machine codes always compare equal and contribute identical bytes whatever
 * order the caller supplied them in.
 *
 * <p>The resolved-catalog-reference hash is the one optional slot, because not every capability resolves a
 * frozen catalog entry. When it is absent the slot is omitted from the canonical material entirely, so a
 * reference-free card keeps its exact bytes, hash and equality.
 */
public record CapabilityPlanPreviewCard(
        String cardKind,
        String cardContractVersion,
        String componentLogicalId,
        String capabilityCode,
        String contractVersion,
        ContentHash normalizedPayloadHash,
        // The one annotation on the card, and it exists only for byte compatibility: an absent optional slot
        // must be omitted from the canonical material rather than serialized as a null, so a reference-free
        // card serializes to exactly the bytes it would have without the slot.
        @JsonInclude(JsonInclude.Include.NON_NULL) ContentHash resolvedCatalogReferenceHash,
        RuntimeCompatibility runtimeCompatibility,
        List<String> summaryCodes,
        List<String> riskCodes,
        ContentHash evaluationAssetHash) {

    /** The one generic card kind this plan format version can carry. */
    public static final String CAPABILITY_PREVIEW = "CAPABILITY_PREVIEW";

    /** The closed set of card kinds a card may declare. */
    private static final Set<String> KINDS = Set.of(CAPABILITY_PREVIEW);

    /** The card contract version this build emits. */
    public static final String CARD_CONTRACT_VERSION = "1.0";

    /** The closed set of card contract versions a card may declare. */
    private static final Set<String> CARD_CONTRACT_VERSIONS = Set.of(CARD_CONTRACT_VERSION);

    /** Bounded summary-code budget of one card. */
    public static final int MAX_SUMMARY_CODES = 32;

    /** Bounded risk-code budget of one card. */
    public static final int MAX_RISK_CODES = 32;

    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);
    private static final Pattern CAPABILITY = Pattern.compile("^[a-z][a-z0-9.-]{0,63}$");
    private static final Pattern VERSION = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._/+-]{0,63}$");
    private static final Pattern MACHINE_CODE = Pattern.compile(ContractPatterns.MACHINE_CODE);

    public CapabilityPlanPreviewCard {
        if (cardKind == null || !KINDS.contains(cardKind)) {
            throw invalid("capabilityPreviewCardKind");
        }
        if (cardContractVersion == null || !CARD_CONTRACT_VERSIONS.contains(cardContractVersion)) {
            throw invalid("capabilityPreviewCardContractVersion");
        }
        if (componentLogicalId == null || !IDENTIFIER.matcher(componentLogicalId).matches()) {
            throw invalid("capabilityPreviewComponent");
        }
        if (capabilityCode == null || !CAPABILITY.matcher(capabilityCode).matches()) {
            throw invalid("capabilityPreviewCapabilityCode");
        }
        if (contractVersion == null || !VERSION.matcher(contractVersion).matches()) {
            throw invalid("capabilityPreviewContractVersion");
        }
        if (normalizedPayloadHash == null) {
            throw invalid("capabilityPreviewPayloadHash");
        }
        if (runtimeCompatibility == null) {
            throw invalid("capabilityPreviewRuntime");
        }
        summaryCodes = canonicalCodes(summaryCodes, MAX_SUMMARY_CODES, "capabilityPreviewSummaryCodes");
        riskCodes = canonicalCodes(riskCodes, MAX_RISK_CODES, "capabilityPreviewRiskCodes");
        if (evaluationAssetHash == null) {
            throw invalid("capabilityPreviewEvaluationAsset");
        }
    }

    /** True only when this card binds a canonical resolved-catalog-reference hash. */
    public boolean hasResolvedCatalogReference() {
        return resolvedCatalogReferenceHash != null;
    }

    /**
     * Proves one code list is bounded, non-null, duplicate-free and made only of closed machine codes, then
     * freezes it in one deterministic order.
     *
     * <p>Duplicates fail closed rather than being silently collapsed, so a caller cannot smuggle a claim into
     * a card under a repeated code, and ordering is normalized so the card material is independent of the
     * order the caller supplied.
     */
    private static List<String> canonicalCodes(List<String> codes, int bound, String invariant) {
        if (codes == null) {
            throw invalid(invariant);
        }
        if (codes.size() > bound) {
            throw invalid(invariant);
        }
        Set<String> distinct = new java.util.LinkedHashSet<>(codes.size());
        for (String code : codes) {
            if (code == null || !MACHINE_CODE.matcher(code).matches()) {
                throw invalid(invariant);
            }
            distinct.add(code);
        }
        if (distinct.size() != codes.size()) {
            throw invalid(invariant);
        }
        List<String> canonical = new ArrayList<>(distinct);
        canonical.sort(null);
        return List.copyOf(canonical);
    }

    private static CompilerException invalid(String invariant) {
        return new CompilerException(
                CompilerErrorCodes.COMPILER_OUTPUT_INCONSISTENT,
                invariant,
                "A capability preview card must be a bounded, safe, closed projection.");
    }

    /** Redacted diagnostics: every value here is already safe, and no trusted identifier is printed. */
    @Override
    public String toString() {
        return "CapabilityPlanPreviewCard[cardKind=" + cardKind
                + ", cardContractVersion=" + cardContractVersion
                + ", capabilityCode=" + capabilityCode
                + ", contractVersion=" + contractVersion
                + ", runtime=" + runtimeCompatibility
                + ", summary=" + summaryCodes.size()
                + ", risk=" + riskCodes.size()
                + ", resolvedCatalogReference=" + hasResolvedCatalogReference()
                + ", payloadHash=" + normalizedPayloadHash + ']';
    }
}