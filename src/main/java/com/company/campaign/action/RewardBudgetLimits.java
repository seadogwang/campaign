package com.company.campaign.action;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.math.BigDecimal;

/**
 * The exact frozen reward-budget declaration one Execution's base Manifest carries.
 *
 * <p>It is resolved from the one stored {@code governance.limit/1.0} component and from nowhere else: never
 * from a mutable setting, a caller-supplied budget, a request field or a Preview inventory, which is
 * observational and is explicitly not a reservation. Its construction re-proves that both limits are positive
 * whole declarations inside the frozen policy bounds and that the currency is one canonical ISO-4217 code, so a
 * malformed, overflowing or cross-tenant declaration is not representable at all.
 *
 * <p>{@link #toString()} prints no member, tenant or Execution identity.
 */
public record RewardBudgetLimits(
        int maxRewardGrants,
        BigDecimal maxRewardFaceValue,
        String currency,
        ContentHash limitsHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public RewardBudgetLimits {
        if (maxRewardGrants < 1) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_GOVERNANCE_LIMIT_INVALID, "maxRewardGrants");
        }
        maxRewardFaceValue = ActionSupport.requireAmount(maxRewardFaceValue, "maxRewardFaceValue");
        if (maxRewardFaceValue.compareTo(ActionVersions.MAX_REWARD_FACE_VALUE_AMOUNT) > 0) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_GOVERNANCE_LIMIT_INVALID, "maxRewardFaceValue");
        }
        ActionSupport.requireCurrency(currency, "currency");
        if (limitsHash == null || !limitsHash.equals(
                CANONICAL_JSON.hash(new Material(maxRewardGrants, maxRewardFaceValue, currency)))) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_GOVERNANCE_LIMIT_INVALID, "limitsHash");
        }
    }

    /** One frozen budget declaration carrying its own canonical hash. */
    public static RewardBudgetLimits of(int maxRewardGrants, BigDecimal maxRewardFaceValue, String currency) {
        BigDecimal bounded = ActionSupport.requireAmount(maxRewardFaceValue, "maxRewardFaceValue");
        String code;
        if (currency == null || !ActionSupport.CURRENCY.matcher(currency).matches()) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_GOVERNANCE_LIMIT_INVALID, "currency");
        }
        code = currency;
        return new RewardBudgetLimits(
                maxRewardGrants, bounded, code,
                CANONICAL_JSON.hash(new Material(maxRewardGrants, bounded, code)));
    }

    /** Redacted diagnostics: no member or owner identity is printed. */
    @Override
    public String toString() {
        return "RewardBudgetLimits[maxRewardGrants=" + maxRewardGrants
                + ", currency=" + currency
                + ", limitsHash=" + limitsHash + ']';
    }

    /** Package-private hash material. */
    record Material(int maxRewardGrants, BigDecimal maxRewardFaceValue, String currency) {
    }
}