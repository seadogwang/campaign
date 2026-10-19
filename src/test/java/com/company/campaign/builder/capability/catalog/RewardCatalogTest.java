package com.company.campaign.builder.capability.catalog;

import com.company.campaign.builder.compiler.CompilerErrorCodes;
import com.company.campaign.builder.compiler.CompilerException;
import com.company.campaign.builder.compiler.ResolvedCatalogReference;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T19 section 3: the frozen Reward catalog.
 *
 * <p>Resolution accepts only a bounded reference code and yields the one exact
 * {@code logicalId}/{@code versionId}/content hash it names — never a "latest" entry — and a duplicate
 * reference code, a repeated identity or a hash conflict fails closed.
 */
class RewardCatalogTest {

    // ------------------------------------------------------------------ exact resolution

    @Test
    void resolutionYieldsTheOneExactVersionTheReferenceCodeNames() {
        RewardVersion points = reward("rw-points-100", "rw-points-100", "rw-points-100-v1", "POINTS", "USD",
                "10.00", "100.00");
        RewardVersion coupon = reward("rw-coupon-5", "rw-coupon-5", "rw-coupon-5-v7", "COUPON", "EUR",
                "5.00", "5.00");
        RewardCatalog catalog = RewardCatalog.of("rw-catalog-1", List.of(points, coupon));

        assertThat(catalog.resolve("rw-points-100")).contains(points);
        assertThat(catalog.resolve("rw-coupon-5")).contains(coupon);
        assertThat(points.resolvedReference()).isEqualTo(
                new ResolvedCatalogReference("rw-points-100", "REWARD", "rw-points-100", "rw-points-100-v1"));
    }

    @Test
    void anAbsentOrNullReferenceCodeResolvesToNothing() {
        RewardCatalog catalog = RewardCatalog.of("rw-catalog-1",
                List.of(reward("rw-a", "a", "a-v1", "POINTS", "USD", "1.00", "1.00")));

        assertThat(catalog.resolve("rw-absent")).isEmpty();
        assertThat(catalog.resolve(null)).isEmpty();
    }

    // ------------------------------------------------------------------ fail closed on duplicates and conflicts

    @Test
    void aDuplicatedReferenceCodeFailsClosed() {
        assertInvalid(() -> RewardCatalog.of("rw-catalog-1", List.of(
                reward("rw-a", "a", "a-v1", "POINTS", "USD", "1.00", "1.00"),
                reward("rw-a", "b", "b-v1", "COUPON", "USD", "2.00", "2.00"))),
                "rewardCatalogDuplicateReference");
    }

    @Test
    void aHashConflictOnOneIdentityFailsClosed() {
        assertInvalid(() -> RewardCatalog.of("rw-catalog-1", List.of(
                reward("rw-a", "shared", "shared-v1", "POINTS", "USD", "1.00", "1.00"),
                reward("rw-b", "shared", "shared-v1", "COUPON", "USD", "2.00", "2.00"))),
                "rewardCatalogHashConflict");
    }

    // ------------------------------------------------------------------ hash binding

    @Test
    void theCatalogHasOneDeterministicByteFormAndHash() {
        RewardCatalog first = catalog();
        RewardCatalog second = catalog();

        assertThat(first.canonicalBytes()).isEqualTo(second.canonicalBytes());
        assertThat(first.catalogHash()).isEqualTo(second.catalogHash());
        assertThat(first.catalogHash()).isEqualTo(first.recomputeCatalogHash());
        assertThatCode(first::requireValidCatalogHash).doesNotThrowAnyException();
    }

    @Test
    void aChangeToAnyVersionOrTheVersionChangesTheCatalogHash() {
        ContentHash hash = catalog().recomputeCatalogHash();

        assertThat(RewardCatalog.of("rw-catalog-2",
                List.of(reward("rw-a", "a", "a-v1", "POINTS", "USD", "1.00", "1.00"))).recomputeCatalogHash())
                .isNotEqualTo(hash);
        assertThat(RewardCatalog.of("rw-catalog-1",
                List.of(reward("rw-a", "a", "a-v1", "POINTS", "USD", "2.00", "2.00"))).recomputeCatalogHash())
                .isNotEqualTo(hash);
        assertThat(RewardCatalog.of("rw-catalog-1",
                List.of(reward("rw-a", "a", "a-v1", "POINTS", "EUR", "1.00", "1.00"))).recomputeCatalogHash())
                .isNotEqualTo(hash);
    }

    @Test
    void anAbsentOrTamperedCatalogHashPinFailsClosed() {
        RewardCatalog catalog = catalog();
        assertInvalid(() -> catalog.withCatalogHash(null).requireValidCatalogHash(), "rewardCatalogHash");
        assertInvalid(() -> catalog.withCatalogHash(hash("tampered")).requireValidCatalogHash(),
                "rewardCatalogHash");
    }

    // ------------------------------------------------------------------ the version declaration

    @Test
    void aVersionBindsItsExactIdentityCurrencyAndAmounts() {
        RewardVersion version = reward("rw-points-100", "rw-points-100", "rw-points-100-v1", "POINTS", "USD",
                "10.00", "100.00");

        assertThat(version.kind()).isEqualTo("POINTS");
        assertThat(version.currency()).isEqualTo("USD");
        assertThat(version.faceValue()).isEqualByComparingTo("10.00");
        assertThat(version.maxPerGrant()).isEqualByComparingTo("100.00");
        assertThatCode(version::requireValidContentHash).doesNotThrowAnyException();
    }

    @Test
    void aVersionContentHashCoversEveryDeclaredFact() {
        ContentHash hash = reward("rw-a", "a", "a-v1", "POINTS", "USD", "1.00", "1.00").recomputeContentHash();

        assertThat(reward("rw-a", "a", "a-v2", "POINTS", "USD", "1.00", "1.00").recomputeContentHash())
                .isNotEqualTo(hash);
        assertThat(reward("rw-a", "a", "a-v1", "COUPON", "USD", "1.00", "1.00").recomputeContentHash())
                .isNotEqualTo(hash);
        assertThat(reward("rw-a", "a", "a-v1", "POINTS", "EUR", "1.00", "1.00").recomputeContentHash())
                .isNotEqualTo(hash);
        assertThat(reward("rw-a", "a", "a-v1", "POINTS", "USD", "2.00", "2.00").recomputeContentHash())
                .isNotEqualTo(hash);
        assertThat(RewardVersion.of("rw-a", "a", "a-v1", "POINTS", "USD", new BigDecimal("1.00"),
                new BigDecimal("1.00"), false, true).recomputeContentHash()).isNotEqualTo(hash);
    }

    @Test
    void anAbsentOrTamperedVersionHashPinFailsClosed() {
        RewardVersion version = reward("rw-a", "a", "a-v1", "POINTS", "USD", "1.00", "1.00");
        assertInvalid(() -> version.withContentHash(null).requireValidContentHash(), "rewardContentHash");
        assertInvalid(() -> version.withContentHash(hash("tampered")).requireValidContentHash(),
                "rewardContentHash");
    }

    @Test
    void aNegativeOrOverPreciseAmountFailsClosed() {
        assertInvalid(() -> RewardVersion.of("rw-a", "a", "a-v1", "POINTS", "USD", new BigDecimal("-1.00"),
                new BigDecimal("1.00"), true, true), "rewardFaceValue");
        assertInvalid(() -> RewardVersion.of("rw-a", "a", "a-v1", "POINTS", "USD", new BigDecimal("1.00000"),
                new BigDecimal("1.00"), true, true), "rewardFaceValue");
        assertInvalid(() -> RewardVersion.of("rw-a", "a", "a-v1", "POINTS", "USD", new BigDecimal("1.00"),
                new BigDecimal("-1.00"), true, true), "rewardMaxPerGrant");
    }

    @Test
    void aFaceValueAboveThePerGrantMaximumFailsClosed() {
        assertInvalid(() -> RewardVersion.of("rw-a", "a", "a-v1", "POINTS", "USD", new BigDecimal("20.00"),
                new BigDecimal("10.00"), true, true), "rewardMaxPerGrant");
    }

    @Test
    void aMalformedKindOrCurrencyOrIdentityFailsClosed() {
        assertInvalid(() -> RewardVersion.of("rw-a", "a", "a-v1", "points", "USD", new BigDecimal("1.00"),
                new BigDecimal("1.00"), true, true), "rewardKind");
        assertInvalid(() -> RewardVersion.of("rw-a", "a", "a-v1", "POINTS", "usd", new BigDecimal("1.00"),
                new BigDecimal("1.00"), true, true), "rewardCurrency");
        assertInvalid(() -> RewardVersion.of("rw-a", "a", "a-v1", "POINTS", "US", new BigDecimal("1.00"),
                new BigDecimal("1.00"), true, true), "rewardCurrency");
        assertInvalid(() -> RewardVersion.of("", "a", "a-v1", "POINTS", "USD", new BigDecimal("1.00"),
                new BigDecimal("1.00"), true, true), "rewardReferenceCode");
    }

    @Test
    void anAmountThatOverflowsTheDeclaredPrecisionIsRefused() {
        RewardVersion large = RewardVersion.of("rw-large", "large", "large-v1", "POINTS", "USD",
                new BigDecimal("100000000000000.0000"), new BigDecimal("100000000000000.0000"), true, true);

        assertInvalid(() -> large.totalFor(1_000_000), "rewardAmountOverflow");
        assertThat(catalog().resolve("rw-a").orElseThrow().totalFor(3)).isEqualByComparingTo("3.00");
    }

    // ------------------------------------------------------------------ fixtures

    private static RewardCatalog catalog() {
        return RewardCatalog.of("rw-catalog-1",
                List.of(reward("rw-a", "a", "a-v1", "POINTS", "USD", "1.00", "1.00")));
    }

    private static RewardVersion reward(String referenceCode, String logicalId, String versionId, String kind,
            String currency, String faceValue, String maxPerGrant) {
        return RewardVersion.of(referenceCode, logicalId, versionId, kind, currency,
                new BigDecimal(faceValue), new BigDecimal(maxPerGrant), true, true);
    }

    private static ContentHash hash(String value) {
        return ContentHash.sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertInvalid(Runnable construction, String invariant) {
        assertThatThrownBy(construction::run)
                .isInstanceOf(CompilerException.class)
                .satisfies(throwable -> {
                    CompilerException failure = (CompilerException) throwable;
                    assertThat(failure.code()).isEqualTo(CompilerErrorCodes.COMPILER_FROZEN_INPUT_INVALID);
                    assertThat(failure.details()).containsEntry("invariant", invariant);
                    assertThat(failure.getCause()).isNull();
                });
    }
}