package com.company.campaign.action;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * The immutable, revisioned aggregate reward-budget ledger of one Campaign Execution.
 *
 * <p>It is a projection over that Execution's own reservations, kept separately from them so a terminal
 * reservation stays byte-identical while the aggregate still moves. It carries the exact frozen limits the
 * Execution's base Manifest declared — {@code maxRewardGrants}, {@code maxRewardFaceValue} and the currency —
 * and restates them from that frozen component rather than recounting anything.
 *
 * <p>Its construction re-proves that the held, finalized and released totals never exceed the frozen limits, so
 * a ledger that has overspent is not representable at all. That is what makes "concurrent actions at the frozen
 * limit produce deterministic accepted or refused outcomes, never truncation and never overspend" a property of
 * the record rather than a hope about concurrency control. {@link #toString()} prints no member, tenant or
 * Execution identity.
 */
public record BudgetLedger(
        String formatVersion,
        String tenantId,
        String executionId,
        String currency,
        int maxRewardGrants,
        BigDecimal maxRewardFaceValue,
        int heldGrants,
        BigDecimal heldFaceValue,
        int finalizedGrants,
        BigDecimal finalizedFaceValue,
        int releasedGrants,
        BigDecimal releasedFaceValue,
        long revision,
        ContentHash ledgerHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public BudgetLedger {
        if (!ActionVersions.BUDGET_LEDGER_FORMAT_VERSION.equals(formatVersion)) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_VERSION_UNSUPPORTED, "formatVersion");
        }
        ActionSupport.requireIdentifier(tenantId, "tenantId");
        ActionSupport.requireIdentifier(executionId, "executionId");
        ActionSupport.requireCurrency(currency, "currency");
        if (maxRewardGrants < 1) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_GOVERNANCE_LIMIT_INVALID, "maxRewardGrants");
        }
        maxRewardFaceValue = ActionSupport.requireAmount(maxRewardFaceValue, "maxRewardFaceValue");
        ActionSupport.requireCount(heldGrants, maxRewardGrants, "heldGrants");
        ActionSupport.requireCount(finalizedGrants, maxRewardGrants, "finalizedGrants");
        ActionSupport.requireCount(releasedGrants, maxRewardGrants, "releasedGrants");
        heldFaceValue = ActionSupport.requireAmount(heldFaceValue, "heldFaceValue");
        finalizedFaceValue = ActionSupport.requireAmount(finalizedFaceValue, "finalizedFaceValue");
        releasedFaceValue = ActionSupport.requireAmount(releasedFaceValue, "releasedFaceValue");
        long grants = (long) heldGrants + finalizedGrants + releasedGrants;
        if (grants > maxRewardGrants) {
            // A ledger may never claim more grants than the frozen limit allows; overspend is unrepresentable.
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_BUDGET_LIMITED, "ledgerGrants");
        }
        BigDecimal value = heldFaceValue.add(finalizedFaceValue).add(releasedFaceValue);
        if (value.compareTo(maxRewardFaceValue) > 0) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_BUDGET_LIMITED, "ledgerFaceValue");
        }
        ActionSupport.requireNonNegative(revision, "revision");
        Objects.requireNonNull(ledgerHash, "ledgerHash");
        if (!ledgerHash.equals(CANONICAL_JSON.hash(material(
                formatVersion, tenantId, executionId, currency, maxRewardGrants, maxRewardFaceValue, heldGrants,
                heldFaceValue, finalizedGrants, finalizedFaceValue, releasedGrants, releasedFaceValue,
                revision)))) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_INPUT_INVALID, "ledgerHash");
        }
    }

    /** The empty ledger of one Execution at its exact frozen limits. */
    public static BudgetLedger empty(
            String tenantId,
            String executionId,
            String currency,
            int maxRewardGrants,
            BigDecimal maxRewardFaceValue) {
        return build(tenantId, executionId, currency, maxRewardGrants, maxRewardFaceValue, 0, BigDecimal.ZERO,
                0, BigDecimal.ZERO, 0, BigDecimal.ZERO, 0L);
    }

    /**
     * The same ledger with one more held reservation, or a bounded refusal when the frozen limit would be
     * exceeded.
     *
     * <p>It returns empty rather than throwing so a caller can turn the refusal into its own bounded outcome;
     * either way no truncation and no overspend is representable, because the returned ledger itself re-proves
     * the frozen limits.
     */
    public java.util.Optional<BudgetLedger> reserve(int grantCount, BigDecimal faceValue) {
        ActionSupport.requireAmount(faceValue, "reserveFaceValue");
        if (grantCount < 1) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_BUDGET_INVALID, "grantCount");
        }
        long grants = (long) heldGrants + finalizedGrants + releasedGrants + grantCount;
        BigDecimal value = heldFaceValue.add(finalizedFaceValue).add(releasedFaceValue).add(faceValue);
        if (grants > maxRewardGrants || value.compareTo(maxRewardFaceValue) > 0) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(build(tenantId, executionId, currency, maxRewardGrants, maxRewardFaceValue,
                heldGrants + grantCount, heldFaceValue.add(faceValue), finalizedGrants, finalizedFaceValue,
                releasedGrants, releasedFaceValue, revision + 1L));
    }

    /** The same ledger with one held reservation finalized; the total exposure does not move. */
    public BudgetLedger finalize(int grantCount, BigDecimal faceValue) {
        requireHeldMove(grantCount, faceValue, "finalize");
        return build(tenantId, executionId, currency, maxRewardGrants, maxRewardFaceValue,
                heldGrants - grantCount, heldFaceValue.subtract(faceValue), finalizedGrants + grantCount,
                finalizedFaceValue.add(faceValue), releasedGrants, releasedFaceValue, revision + 1L);
    }

    /** The same ledger with one held reservation released; the total exposure does not move. */
    public BudgetLedger release(int grantCount, BigDecimal faceValue) {
        requireHeldMove(grantCount, faceValue, "release");
        return build(tenantId, executionId, currency, maxRewardGrants, maxRewardFaceValue,
                heldGrants - grantCount, heldFaceValue.subtract(faceValue), finalizedGrants, finalizedFaceValue,
                releasedGrants + grantCount, releasedFaceValue.add(faceValue), revision + 1L);
    }

    /** The number of grant units this ledger still has available under the frozen limit. */
    public int availableGrants() {
        return maxRewardGrants - heldGrants - finalizedGrants - releasedGrants;
    }

    /** The exact face value this ledger still has available under the frozen limit. */
    public BigDecimal availableFaceValue() {
        return maxRewardFaceValue.subtract(heldFaceValue).subtract(finalizedFaceValue).subtract(releasedFaceValue);
    }

    private void requireHeldMove(int grantCount, BigDecimal faceValue, String field) {
        ActionSupport.requireAmount(faceValue, field + "FaceValue");
        if (grantCount < 1 || grantCount > heldGrants || faceValue.compareTo(heldFaceValue) > 0) {
            throw new ActionBoundaryException(
                    ActionErrorCodes.ACTION_BUDGET_CONFLICT, field,
                    "A budget ledger may only move an exactly held reservation.");
        }
    }

    private static BudgetLedger build(
            String tenantId,
            String executionId,
            String currency,
            int maxRewardGrants,
            BigDecimal maxRewardFaceValue,
            int heldGrants,
            BigDecimal heldFaceValue,
            int finalizedGrants,
            BigDecimal finalizedFaceValue,
            int releasedGrants,
            BigDecimal releasedFaceValue,
            long revision) {
        return new BudgetLedger(
                ActionVersions.BUDGET_LEDGER_FORMAT_VERSION,
                tenantId, executionId, currency, maxRewardGrants, maxRewardFaceValue, heldGrants, heldFaceValue,
                finalizedGrants, finalizedFaceValue, releasedGrants, releasedFaceValue, revision,
                CANONICAL_JSON.hash(material(
                        ActionVersions.BUDGET_LEDGER_FORMAT_VERSION, tenantId, executionId, currency,
                        maxRewardGrants, maxRewardFaceValue, heldGrants, heldFaceValue, finalizedGrants,
                        finalizedFaceValue, releasedGrants, releasedFaceValue, revision)));
    }

    private static Material material(
            String formatVersion,
            String tenantId,
            String executionId,
            String currency,
            int maxRewardGrants,
            BigDecimal maxRewardFaceValue,
            int heldGrants,
            BigDecimal heldFaceValue,
            int finalizedGrants,
            BigDecimal finalizedFaceValue,
            int releasedGrants,
            BigDecimal releasedFaceValue,
            long revision) {
        return new Material(formatVersion, tenantId, executionId, currency, maxRewardGrants, maxRewardFaceValue,
                heldGrants, heldFaceValue, finalizedGrants, finalizedFaceValue, releasedGrants, releasedFaceValue,
                revision);
    }

    /** Redacted diagnostics: no member, tenant or Execution identity is printed. */
    @Override
    public String toString() {
        return "BudgetLedger[grants=" + maxRewardGrants
                + ", heldGrants=" + heldGrants
                + ", finalizedGrants=" + finalizedGrants
                + ", releasedGrants=" + releasedGrants
                + ", revision=" + revision
                + ", ledgerHash=" + ledgerHash + ']';
    }

    /** Package-private hash material. */
    record Material(
            String formatVersion,
            String tenantId,
            String executionId,
            String currency,
            int maxRewardGrants,
            BigDecimal maxRewardFaceValue,
            int heldGrants,
            BigDecimal heldFaceValue,
            int finalizedGrants,
            BigDecimal finalizedFaceValue,
            int releasedGrants,
            BigDecimal releasedFaceValue,
            long revision) {
    }
}