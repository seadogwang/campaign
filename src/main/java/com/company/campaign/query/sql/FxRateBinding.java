package com.company.campaign.query.sql;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable, version/hash/time-pinned FX-rate binding supplied to the compiler.
 *
 * <p>It is the only way an exact rate can be known at compile time, and it is intentionally an explicit
 * external input: a conversion is never assumed, defaulted to one or fetched. The snapshot identifier,
 * version, content hash, target reporting currency, rate timestamp and every exact rate are all bound into
 * the compiled hash, so an artifact can never be replayed under a different rate set.
 *
 * <p>The rates are carried as exact {@link BigDecimal} decimals and canonicalized to a stripped plain form, so
 * {@code 7.20} and {@code 7.2} are one rate. A rate must be strictly positive.
 */
public final class FxRateBinding {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();
    private static final Pattern REFERENCE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);
    private static final Pattern CURRENCY = Pattern.compile(ContractPatterns.CURRENCY);

    private final String snapshotId;
    private final String snapshotVersion;
    private final String targetCurrency;
    private final Instant rateTimestamp;
    private final List<Rate> rates;
    private final ContentHash contentHash;

    private FxRateBinding(
            String snapshotId,
            String snapshotVersion,
            String targetCurrency,
            Instant rateTimestamp,
            List<Rate> rates) {
        this.snapshotId = requireReferenceId(snapshotId, "fxSnapshotId");
        this.snapshotVersion = requireReferenceId(snapshotVersion, "fxSnapshotVersion");
        if (targetCurrency == null || !CURRENCY.matcher(targetCurrency).matches()) {
            throw invalid("fxTargetCurrency");
        }
        this.targetCurrency = targetCurrency;
        if (rateTimestamp == null) {
            throw invalid("fxRateTimestamp");
        }
        this.rateTimestamp = rateTimestamp;
        this.rates = List.copyOf(rates);
        Map<String, Rate> seen = new LinkedHashMap<>();
        for (Rate rate : this.rates) {
            if (seen.putIfAbsent(rate.sourceCurrency(), rate) != null) {
                throw invalid("fxRateDuplicate");
            }
        }
        this.contentHash = CANONICAL_JSON.hash(new Material(
                snapshotId, snapshotVersion, targetCurrency, rateTimestamp.toString(),
                this.rates.stream().map(Rate::canonicalText).toList()));
    }

    /** Publishes a validated, hash-pinned FX-rate binding. */
    public static FxRateBinding publish(
            String snapshotId,
            String snapshotVersion,
            String targetCurrency,
            Instant rateTimestamp,
            Map<String, BigDecimal> rates) {
        if (rates == null) {
            throw invalid("fxRates");
        }
        List<Rate> entries = new ArrayList<>(rates.size());
        for (Map.Entry<String, BigDecimal> entry : rates.entrySet()) {
            entries.add(new Rate(entry.getKey(), entry.getValue()));
        }
        entries.sort(Comparator.comparing(Rate::sourceCurrency));
        return new FxRateBinding(snapshotId, snapshotVersion, targetCurrency, rateTimestamp, entries);
    }

    /** The immutable snapshot identifier bound into the compiled hash. */
    public String snapshotId() {
        return snapshotId;
    }

    /** The immutable snapshot version bound into the compiled hash. */
    public String snapshotVersion() {
        return snapshotVersion;
    }

    /** The reporting currency every rate converts into. */
    public String targetCurrency() {
        return targetCurrency;
    }

    /** The instant the rates were observed; a conversion is never evaluated with a later rate. */
    public Instant rateTimestamp() {
        return rateTimestamp;
    }

    /** The canonical hash covering every pinned fact of this binding. */
    public ContentHash contentHash() {
        return contentHash;
    }

    /** Every rate in deterministic source-currency order. */
    public List<Rate> rates() {
        return rates;
    }

    /** The exact set of source currencies this binding declares a rate for. */
    public java.util.Set<String> sourceCurrencies() {
        java.util.Set<String> currencies = new java.util.LinkedHashSet<>();
        for (Rate rate : rates) {
            currencies.add(rate.sourceCurrency());
        }
        return java.util.Collections.unmodifiableSet(currencies);
    }

    /** The exact rate from {@code sourceCurrency} to the target, {@code 1} for the target itself, else null. */
    public BigDecimal rate(String sourceCurrency) {
        if (sourceCurrency == null) {
            return null;
        }
        if (sourceCurrency.equals(targetCurrency)) {
            return BigDecimal.ONE;
        }
        for (Rate rate : rates) {
            if (rate.sourceCurrency().equals(sourceCurrency)) {
                return rate.rate();
            }
        }
        return null;
    }

    /** One exact source-to-target conversion rate. */
    public record Rate(String sourceCurrency, BigDecimal rate) {

        public Rate {
            if (sourceCurrency == null || !CURRENCY.matcher(sourceCurrency).matches()) {
                throw invalid("fxSourceCurrency");
            }
            if (rate == null || rate.signum() <= 0) {
                throw invalid("fxRateValue");
            }
        }

        /** The exact stripped plain-decimal text of this rate. */
        public String canonicalText() {
            return sourceCurrency + "=" + canonicalDecimal(rate);
        }
    }

    private static String canonicalDecimal(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.scale() < 0) {
            stripped = stripped.setScale(0);
        }
        return stripped.toPlainString();
    }

    private static String requireReferenceId(String value, String invariant) {
        if (value == null || !REFERENCE_ID.matcher(value).matches()) {
            throw invalid(invariant);
        }
        return value;
    }

    private static SqlException invalid(String invariant) {
        return new SqlException(SqlErrorCodes.SQL_FX_INVALID, invariant,
                "A hash-pinned FX-rate binding is inconsistent and cannot be published.");
    }

    private record Material(
            String snapshotId,
            String snapshotVersion,
            String targetCurrency,
            String rateTimestamp,
            List<String> rates) {
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FxRateBinding binding && Objects.equals(contentHash, binding.contentHash);
    }

    @Override
    public int hashCode() {
        return contentHash.hashCode();
    }

    @Override
    public String toString() {
        return "FxRateBinding[snapshotId=" + snapshotId + ", targetCurrency=" + targetCurrency
                + ", rateTimestamp=" + rateTimestamp + ", contentHash=" + contentHash + ']';
    }
}