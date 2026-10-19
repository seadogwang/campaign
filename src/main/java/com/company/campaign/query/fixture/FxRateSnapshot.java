package com.company.campaign.query.fixture;

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
 * Immutable, hash/version/time-pinned FX-rate snapshot.
 *
 * <p>A conversion is never assumed, defaulted to one, or fetched: it requires this explicit snapshot, whose
 * identifier, version, canonical content hash, target reporting currency and rate timestamp are all bound into
 * execution. Every rate is an exact decimal between its own ISO-4217 source currency and the snapshot's target
 * currency, so a conversion is exact decimal arithmetic and never binary floating point.
 *
 * <p>A source currency with no declared rate fails closed at {@link FixtureExecutor}; nothing here invents a
 * rate or treats an absent rate as {@code 1}.
 */
public final class FxRateSnapshot {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();
    private static final Pattern REFERENCE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);
    private static final Pattern CURRENCY = Pattern.compile(ContractPatterns.CURRENCY);

    private final String snapshotId;
    private final String snapshotVersion;
    private final String targetCurrency;
    private final Instant rateTimestamp;
    private final List<Rate> rates;
    private final Map<String, BigDecimal> ratesByCurrency;
    private final ContentHash contentHash;

    private FxRateSnapshot(
            String snapshotId,
            String snapshotVersion,
            String targetCurrency,
            Instant rateTimestamp,
            List<Rate> rates) {
        this.snapshotId = requireId(snapshotId, "fxSnapshotId");
        this.snapshotVersion = requireId(snapshotVersion, "fxSnapshotVersion");
        if (targetCurrency == null || !CURRENCY.matcher(targetCurrency).matches()) {
            throw invalid("fxTargetCurrency");
        }
        this.targetCurrency = targetCurrency;
        this.rateTimestamp = Objects.requireNonNull(rateTimestamp, "rateTimestamp");
        this.rates = List.copyOf(Objects.requireNonNull(rates, "rates"));
        Map<String, BigDecimal> index = new LinkedHashMap<>();
        for (Rate rate : this.rates) {
            if (index.putIfAbsent(rate.sourceCurrency(), rate.rate()) != null) {
                throw invalid("fxRateDuplicate");
            }
        }
        this.ratesByCurrency = Map.copyOf(index);
        this.contentHash = CANONICAL_JSON.hash(new Material(
                snapshotId, snapshotVersion, targetCurrency, rateTimestamp.toString(),
                this.rates.stream().map(Rate::sortKey).toList()));
    }

    /** Publishes a validated, hash-pinned FX-rate snapshot. */
    public static FxRateSnapshot publish(
            String snapshotId,
            String snapshotVersion,
            String targetCurrency,
            Instant rateTimestamp,
            Map<String, BigDecimal> rates) {
        List<Rate> entries = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> entry : rates.entrySet()) {
            entries.add(new Rate(entry.getKey(), entry.getValue()));
        }
        entries.sort(Comparator.comparing(Rate::sourceCurrency));
        return new FxRateSnapshot(snapshotId, snapshotVersion, targetCurrency, rateTimestamp, entries);
    }

    /** The immutable snapshot identifier bound into execution. */
    public String snapshotId() {
        return snapshotId;
    }

    /** The immutable snapshot version bound into execution. */
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

    /** The canonical hash covering every pinned fact of this snapshot. */
    public ContentHash contentHash() {
        return contentHash;
    }

    /** Every rate in deterministic source-currency order. */
    public List<Rate> rates() {
        return rates;
    }

    /** The exact rate from {@code sourceCurrency} to the target, {@code 1} for the target itself, else null. */
    BigDecimal rate(String sourceCurrency) {
        if (sourceCurrency == null) {
            return null;
        }
        if (sourceCurrency.equals(targetCurrency)) {
            return BigDecimal.ONE;
        }
        return ratesByCurrency.get(sourceCurrency);
    }

    /** One exact source-to-target conversion rate. */
    public record Rate(String sourceCurrency, BigDecimal rate) {

        public Rate {
            if (sourceCurrency == null || !CURRENCY.matcher(sourceCurrency).matches()) {
                throw invalid("fxSourceCurrency");
            }
            Objects.requireNonNull(rate, "rate");
            if (rate.signum() <= 0) {
                throw invalid("fxRateValue");
            }
        }

        String sortKey() {
            return sourceCurrency + "=" + rate.stripTrailingZeros().toPlainString();
        }
    }

    private static String requireId(String value, String invariant) {
        if (value == null || !REFERENCE_ID.matcher(value).matches()) {
            throw invalid(invariant);
        }
        return value;
    }

    private static FixtureException invalid(String invariant) {
        return new FixtureException(FixtureErrorCodes.FIXTURE_FX_INVALID, invariant,
                "A hash-pinned FX-rate snapshot is inconsistent and cannot be published.");
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
        return other instanceof FxRateSnapshot snapshot && Objects.equals(contentHash, snapshot.contentHash);
    }

    @Override
    public int hashCode() {
        return contentHash.hashCode();
    }

    @Override
    public String toString() {
        return "FxRateSnapshot[snapshotId=" + snapshotId + ", targetCurrency=" + targetCurrency
                + ", rateTimestamp=" + rateTimestamp + ", contentHash=" + contentHash + ']';
    }
}