package com.company.campaign.query.plan;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.metadata.MetadataKind;
import com.company.campaign.query.QueryErrorCodes;
import com.company.campaign.query.QueryException;

import java.util.Comparator;
import java.util.regex.Pattern;

/**
 * The currency action one referenced Attribute or Metric comparison definition must be evaluated under.
 *
 * <p>It replaces the former plan-level summary: a segment that references two monetary definitions with
 * different policies keeps two bindings, so a {@code SINGLE_CURRENCY_ONLY} requirement is never collapsed
 * into a {@code CONVERT_TO_REPORTING_CURRENCY} one. Each binding is keyed by Metadata kind and the stable
 * definition ID, so duplicate uses of one definition produce exactly one binding and a later executor can
 * apply the correct policy to the correct comparison.
 *
 * <p>A binding carries exactly one closed {@link Action}. A conversion additionally carries only the trusted
 * context's reporting currency as target and a {@link RateResolution#DEFERRED} rate; a single-currency or
 * no-conversion binding carries neither a target nor a deferred rate. A relation dependency is not a
 * comparison and never receives a currency binding.
 */
public record CurrencyBinding(
        MetadataKind kind,
        String definitionId,
        Action action,
        String targetCurrency,
        RateResolution rateResolution) {

    private static final Pattern CURRENCY = Pattern.compile(ContractPatterns.CURRENCY);

    /** The deterministic binding order: Metadata kind, then stable definition ID. */
    public static final Comparator<CurrencyBinding> ORDER =
            Comparator.comparingInt((CurrencyBinding binding) -> binding.kind().ordinal())
                    .thenComparing(CurrencyBinding::definitionId);

    public CurrencyBinding {
        if (kind == null || action == null || rateResolution == null) {
            throw inconsistent("currencyBinding", "A currency binding requires a kind, an action and a rate resolution.");
        }
        if (kind == MetadataKind.RELATION) {
            throw inconsistent("currencyBindingKind",
                    "A relation dependency does not receive a currency binding.");
        }
        if (definitionId == null || definitionId.isBlank()) {
            throw inconsistent("currencyBindingId", "A currency binding requires a stable definition ID.");
        }
        if (action == Action.CONVERT_TO_REPORTING_CURRENCY) {
            if (targetCurrency == null || !CURRENCY.matcher(targetCurrency).matches()) {
                throw inconsistent("currencyTarget",
                        "A conversion binding requires the trusted reporting currency as target.");
            }
            if (rateResolution != RateResolution.DEFERRED) {
                throw inconsistent("rateResolution", "A conversion binding's rate resolution must be deferred.");
            }
        } else {
            if (targetCurrency != null) {
                throw inconsistent("currencyTarget", "Only a conversion binding may name a target currency.");
            }
            if (rateResolution != RateResolution.NOT_APPLICABLE) {
                throw inconsistent("rateResolution", "Only a conversion binding defers a rate resolution.");
            }
        }
    }

    /** The closed set of per-definition currency actions. */
    public enum Action {
        NO_CONVERSION,
        SINGLE_CURRENCY_ONLY,
        CONVERT_TO_REPORTING_CURRENCY
    }

    /** Whether a currency rate still has to be resolved for this definition. */
    public enum RateResolution {
        NOT_APPLICABLE,
        DEFERRED
    }

    private static QueryException inconsistent(String invariant, String message) {
        return new QueryException(QueryErrorCodes.QUERY_PLAN_INCONSISTENT, invariant, message);
    }
}