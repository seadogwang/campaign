package com.company.campaign.builder.compiler;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One closed, immutable, hash-bound plan preview card.
 *
 * <p>The card is the typed projection a resolved component contributes to a {@link CampaignPlan}. Every card
 * binds to the resolved component's stable logical identity and carries only already-approved safe facts: the
 * matched count, the bounded structural condition statistics, an optional opaque hashed sample and the exact
 * DSL, canonical AST, semantic plan, Preview, Metadata, Skill and trusted time pins plus the readiness state
 * and the Gate evidence the compile used.
 *
 * <p>It never carries a raw member identifier, a member set, SQL or AST text, a tenant/program/user/DataScope
 * identifier, a permission, a provider or model output, a Metadata label or operand, a credential, a storage
 * fact or an internal exception message — none of those is representable here, and every free-form value is
 * re-proved bounded at construction even when a Plan is built from raw values.
 *
 * <p>The card kind is a closed code: a plan projection may only carry a card kind this version knows, so an
 * unknown or invented projection fails closed rather than entering the plan hash material.
 *
 * <p>The readiness state is a closed code and carries the same coherent Gate rules the accepted readiness
 * input states: a card always binds the current Gate-input hash its compile ran against, and only an
 * {@code ACTIVE} card additionally binds the approved decision hash that activation rests on. A non-active
 * card may carry the hash of the not-passed or stale decision that kept it non-active, and may carry none.
 *
 * <p>The card may additionally carry the optional {@link ReferencePlanEvidence} of a Segment that depends on
 * referenced immutable Audience Versions, and carries none otherwise. That slot is genuinely optional: when it
 * is absent it is omitted from the canonical material entirely, so a reference-free card keeps the accepted
 * bytes, content hash, plan hash and equality exactly, and a reference-bearing card binds its dedicated
 * reference identity, dependency-graph hash, reference-inputs hash and development-only readiness state into
 * the plan hash.
 */
public record PlanPreviewCard(
        String cardKind,
        String componentLogicalId,
        int matchedCount,
        List<PlanPreviewStatistic> conditionStatistics,
        List<String> sampleTokens,
        String metadataSnapshotId,
        Instant asOf,
        String timezone,
        Instant dataWatermark,
        ContentHash dslHash,
        ContentHash canonicalAstHash,
        ContentHash semanticPlanHash,
        ContentHash previewHash,
        ContentHash metadataHash,
        ContentHash skillHash,
        String readinessState,
        ContentHash gateInputsHash,
        ContentHash approvedDecisionHash,
        // The one annotation on the card, and it exists only for byte compatibility: an absent optional slot
        // must be omitted from the canonical material rather than serialized as a null, so every accepted
        // reference-free card serializes to exactly the bytes it always did.
        @JsonInclude(JsonInclude.Include.NON_NULL) ReferencePlanEvidence referenceEvidence) {

    /**
     * The accepted reference-free card constructor, kept as a backward-compatible overload.
     *
     * <p>It supplies no reference evidence, so every card a reference-free compile produced stays exactly the
     * accepted card it always was.
     */
    public PlanPreviewCard(
            String cardKind,
            String componentLogicalId,
            int matchedCount,
            List<PlanPreviewStatistic> conditionStatistics,
            List<String> sampleTokens,
            String metadataSnapshotId,
            Instant asOf,
            String timezone,
            Instant dataWatermark,
            ContentHash dslHash,
            ContentHash canonicalAstHash,
            ContentHash semanticPlanHash,
            ContentHash previewHash,
            ContentHash metadataHash,
            ContentHash skillHash,
            String readinessState,
            ContentHash gateInputsHash,
            ContentHash approvedDecisionHash) {
        this(cardKind, componentLogicalId, matchedCount, conditionStatistics, sampleTokens, metadataSnapshotId,
                asOf, timezone, dataWatermark, dslHash, canonicalAstHash, semanticPlanHash, previewHash,
                metadataHash, skillHash, readinessState, gateInputsHash, approvedDecisionHash, null);
    }

    /** The one card kind T16 introduces: the safe projection of a resolved rule-based Segment. */
    public static final String SEGMENT_PREVIEW = "SEGMENT_PREVIEW";

    /** The closed set of card kinds this plan format version can carry. */
    private static final Set<String> KINDS = Set.of(SEGMENT_PREVIEW);

    /** The closed set of readiness state codes a card may honestly report. */
    private static final Set<String> READINESS_STATES =
            Set.of(CapabilityReadiness.ACTIVE, CapabilityReadiness.EXPERIMENTAL,
                    CapabilityReadiness.EVALUATION_REQUIRED);

    /**
     * Bounded matched-count budget of one card.
     *
     * <p>The compiler package reaches no Preview package, so the accepted absolute bounds a Preview response
     * may never exceed are re-stated here: a card must be able to carry exactly what the accepted Preview
     * boundary approved and must refuse anything that boundary itself would have refused.
     */
    public static final int MAX_MATCHED_COUNT = 10_000_000;

    /** Bounded condition-statistic budget of one card; the accepted absolute Preview budget. */
    public static final int MAX_CONDITION_STATISTICS = 4_096;

    /** Bounded opaque-sample budget of one card; the accepted absolute Preview budget. */
    public static final int MAX_SAMPLE_TOKENS = 1_000;

    /** Bounded length of the trusted zone text, so no free-form value can enter the plan material. */
    public static final int MAX_TIMEZONE_LENGTH = 64;

    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);
    private static final Pattern SAMPLE_TOKEN = Pattern.compile("^sha256:[0-9a-f]{64}$");

    public PlanPreviewCard {
        if (cardKind == null || !KINDS.contains(cardKind)) {
            throw invalid("planPreviewCardKind");
        }
        if (componentLogicalId == null || !IDENTIFIER.matcher(componentLogicalId).matches()) {
            throw invalid("planPreviewComponent");
        }
        if (matchedCount < 0 || matchedCount > MAX_MATCHED_COUNT) {
            throw invalid("planPreviewMatchedCount");
        }
        conditionStatistics = PlanPreviewStatistic.canonical(conditionStatistics, MAX_CONDITION_STATISTICS);
        sampleTokens = canonicalTokens(sampleTokens);
        if (metadataSnapshotId == null || !IDENTIFIER.matcher(metadataSnapshotId).matches()) {
            throw invalid("planPreviewMetadataSnapshot");
        }
        if (asOf == null || dataWatermark == null || dataWatermark.isAfter(asOf)) {
            throw invalid("planPreviewTimePins");
        }
        if (!isCanonicalZone(timezone)) {
            throw invalid("planPreviewTimezone");
        }
        if (dslHash == null || canonicalAstHash == null || semanticPlanHash == null
                || previewHash == null || metadataHash == null || skillHash == null) {
            throw invalid("planPreviewPins");
        }
        if (readinessState == null || !READINESS_STATES.contains(readinessState)) {
            throw invalid("planPreviewReadiness");
        }
        if (gateInputsHash == null) {
            // Whatever the readiness, the card binds the Gate inputs its compile ran against, so a plan always
            // records which gated inputs were in force when the component was projected.
            throw invalid("planPreviewGateInputs");
        }
        if (CapabilityReadiness.ACTIVE.equals(readinessState) && approvedDecisionHash == null) {
            // Only an ACTIVE card claims activation, and activation always rests on an approved decision.
            throw invalid("planPreviewApprovedDecision");
        }
    }

    /** True only when the readiness the compile used was exactly {@code ACTIVE}. */
    public boolean activeReadiness() {
        return CapabilityReadiness.ACTIVE.equals(readinessState);
    }

    /** True when an authorized, already-hashed sample is present on the card. */
    public boolean hasSample() {
        return sampleTokens != null;
    }

    /** True when this card binds an approved decision hash, as every {@code ACTIVE} card must. */
    public boolean hasApprovedDecision() {
        return approvedDecisionHash != null;
    }

    /** The deterministic card identity within a plan: the component it binds to and its closed kind. */
    public String cardIdentity() {
        return componentLogicalId + '|' + cardKind;
    }

    /**
     * True only for a bounded zone text that is exactly the canonical form of one known zone.
     *
     * <p>The length is bounded before the zone is parsed, so an arbitrarily long injected value is never
     * handed to the parser, and the canonical round trip keeps one zone from entering the plan material under
     * several spellings.
     */
    private static boolean isCanonicalZone(String timezone) {
        if (timezone == null || timezone.isEmpty() || timezone.length() > MAX_TIMEZONE_LENGTH) {
            return false;
        }
        try {
            return ZoneId.of(timezone).getId().equals(timezone);
        } catch (DateTimeException exception) {
            // The rejected zone text is deliberately not attached to the failure.
            return false;
        }
    }

    private static List<String> canonicalTokens(List<String> tokens) {
        if (tokens == null) {
            return null;
        }
        if (tokens.size() > MAX_SAMPLE_TOKENS) {
            throw invalid("planPreviewSample");
        }
        List<String> copy = new ArrayList<>(tokens.size());
        for (String token : tokens) {
            if (token == null || !SAMPLE_TOKEN.matcher(token).matches()) {
                throw invalid("planPreviewSample");
            }
            copy.add(token);
        }
        return List.copyOf(copy);
    }

    private static CompilerException invalid(String invariant) {
        return new CompilerException(
                CompilerErrorCodes.COMPILER_OUTPUT_INCONSISTENT,
                invariant,
                "A plan preview card must be a bounded, safe, closed projection.");
    }

    /** Redacted diagnostics: every value here is already safe, and no trusted identifier is printed. */
    @Override
    public String toString() {
        return "PlanPreviewCard[cardKind=" + cardKind
                + ", matchedCount=" + matchedCount
                + ", statistics=" + (conditionStatistics == null ? 0 : conditionStatistics.size())
                + ", sample=" + hasSample()
                + ", readiness=" + readinessState
                + ", approvedDecision=" + hasApprovedDecision()
                + ", reference=" + (referenceEvidence == null ? "none" : referenceEvidence.readinessState())
                + ", previewHash=" + previewHash + ']';
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof PlanPreviewCard card)) {
            return false;
        }
        return matchedCount == card.matchedCount
                && Objects.equals(cardKind, card.cardKind)
                && Objects.equals(componentLogicalId, card.componentLogicalId)
                && Objects.equals(conditionStatistics, card.conditionStatistics)
                && Objects.equals(sampleTokens, card.sampleTokens)
                && Objects.equals(metadataSnapshotId, card.metadataSnapshotId)
                && Objects.equals(asOf, card.asOf)
                && Objects.equals(timezone, card.timezone)
                && Objects.equals(dataWatermark, card.dataWatermark)
                && Objects.equals(dslHash, card.dslHash)
                && Objects.equals(canonicalAstHash, card.canonicalAstHash)
                && Objects.equals(semanticPlanHash, card.semanticPlanHash)
                && Objects.equals(previewHash, card.previewHash)
                && Objects.equals(metadataHash, card.metadataHash)
                && Objects.equals(skillHash, card.skillHash)
                && Objects.equals(readinessState, card.readinessState)
                && Objects.equals(gateInputsHash, card.gateInputsHash)
                && Objects.equals(approvedDecisionHash, card.approvedDecisionHash)
                && Objects.equals(referenceEvidence, card.referenceEvidence);
    }

    @Override
    public int hashCode() {
        int accepted = Objects.hash(cardKind, componentLogicalId, matchedCount, conditionStatistics, sampleTokens,
                metadataSnapshotId, asOf, timezone, dataWatermark, dslHash, canonicalAstHash, semanticPlanHash,
                previewHash, metadataHash, skillHash, readinessState, gateInputsHash, approvedDecisionHash);
        // A card with no reference evidence must keep the accepted hash code exactly, so the absent slot is
        // folded in only when it is genuinely present.
        return referenceEvidence == null ? accepted : 31 * accepted + referenceEvidence.hashCode();
    }
}