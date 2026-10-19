package com.company.campaign.builder.capability.catalog;

import com.company.campaign.builder.compiler.ResolvedCatalogReference;
import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * One immutable Reward Version a frozen catalog owns.
 *
 * <p>It freezes the exact {@code referenceCode} -> {@code logicalId}/{@code versionId} identity and the
 * canonical content hash of the version, the reward kind, its ISO-4217 currency, the exact non-negative
 * face value of one unit and the per-grant maximum that one grant may carry, plus its
 * enabled/runtime-available flags. Resolution is by exact reference code only: there is no display name, no
 * {@code latest} alias and no mutable current pointer anywhere in it.
 *
 * <p>It deliberately never exposes inventory authority, a provider credential, a mutable balance or a grant
 * result. Those are not representable here at all, so a pack that validates against a version cannot read —
 * and cannot leak — a live reward authority.
 *
 * <p>The version has exactly one canonical byte form and one content hash. {@link #of} is the only way to
 * build a version carrying a correct {@code contentHash}, and {@link #requireValidContentHash()} re-derives
 * it, so a stale or tampered pin is refused rather than trusted. Face value and per-grant maximum are
 * normalized to one exact scale, so two equal amounts always compare, serialize and hash identically.
 */
public record RewardVersion(
        String referenceCode,
        String logicalId,
        String versionId,
        ContentHash contentHash,
        String kind,
        String currency,
        BigDecimal faceValue,
        BigDecimal maxPerGrant,
        boolean enabled,
        boolean runtimeAvailable) {

    /** Largest permitted number of significant digits in any reward amount. */
    public static final int MAX_AMOUNT_PRECISION = 18;

    /** The one exact scale every reward amount is normalized to. */
    public static final int AMOUNT_SCALE = 4;

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();
    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);
    private static final Pattern KIND = Pattern.compile(ContractPatterns.MACHINE_CODE);
    private static final Pattern CURRENCY = Pattern.compile(ContractPatterns.CURRENCY);

    public RewardVersion {
        if (referenceCode == null || !IDENTIFIER.matcher(referenceCode).matches()) {
            throw CatalogSafety.invalid("rewardReferenceCode");
        }
        if (logicalId == null || !IDENTIFIER.matcher(logicalId).matches()) {
            throw CatalogSafety.invalid("rewardLogicalId");
        }
        if (versionId == null || !IDENTIFIER.matcher(versionId).matches()) {
            throw CatalogSafety.invalid("rewardVersionId");
        }
        if (kind == null || !KIND.matcher(kind).matches()) {
            throw CatalogSafety.invalid("rewardKind");
        }
        if (currency == null || !CURRENCY.matcher(currency).matches()) {
            throw CatalogSafety.invalid("rewardCurrency");
        }
        faceValue = amount(faceValue, "rewardFaceValue");
        maxPerGrant = amount(maxPerGrant, "rewardMaxPerGrant");
        if (faceValue.compareTo(maxPerGrant) > 0) {
            // A single unit that could never be granted within the per-grant maximum is not grantable at all.
            throw CatalogSafety.invalid("rewardMaxPerGrant");
        }
    }

    /**
     * Builds one reward version and stamps it with its own canonical content hash.
     *
     * <p>The hash covers the version with {@code contentHash} absent, so the pin can never be
     * self-referential.
     */
    public static RewardVersion of(
            String referenceCode,
            String logicalId,
            String versionId,
            String kind,
            String currency,
            BigDecimal faceValue,
            BigDecimal maxPerGrant,
            boolean enabled,
            boolean runtimeAvailable) {
        RewardVersion unhashed = new RewardVersion(referenceCode, logicalId, versionId, null, kind, currency,
                faceValue, maxPerGrant, enabled, runtimeAvailable);
        return unhashed.withContentHash(unhashed.recomputeContentHash());
    }

    /** The same version carrying the given content hash pin. */
    public RewardVersion withContentHash(ContentHash hash) {
        return new RewardVersion(referenceCode, logicalId, versionId, hash, kind, currency, faceValue,
                maxPerGrant, enabled, runtimeAvailable);
    }

    /** The canonical UTF-8 bytes of this version with the content hash pin excluded. */
    public byte[] canonicalBytes() {
        return CANONICAL_JSON.write(withContentHash(null));
    }

    /** The canonical content hash of this version with the content hash pin excluded. */
    public ContentHash recomputeContentHash() {
        return CANONICAL_JSON.hash(withContentHash(null));
    }

    /** Refuses a version whose declared content hash pin is absent or does not match its own content. */
    public void requireValidContentHash() {
        if (contentHash == null || !contentHash.equals(recomputeContentHash())) {
            throw CatalogSafety.invalid("rewardContentHash");
        }
    }

    /** The one stable catalog reference this version resolves to; never a display name or a "latest" alias. */
    public ResolvedCatalogReference resolvedReference() {
        return new ResolvedCatalogReference(referenceCode, RewardCatalog.ENTITY_KIND, logicalId, versionId);
    }

    /**
     * The exact total value of granting {@code quantity} units, refusing an amount that overflows the
     * declared precision.
     */
    public BigDecimal totalFor(int quantity) {
        BigDecimal total = faceValue.multiply(BigDecimal.valueOf(quantity));
        if (total.precision() > MAX_AMOUNT_PRECISION) {
            throw CatalogSafety.invalid("rewardAmountOverflow");
        }
        return total;
    }

    /**
     * Proves one amount is present, non-negative and within the declared precision, then normalizes it to
     * the one exact scale.
     */
    private static BigDecimal amount(BigDecimal value, String invariant) {
        if (value == null || value.signum() < 0) {
            throw CatalogSafety.invalid(invariant);
        }
        if (value.scale() > AMOUNT_SCALE) {
            // More precision than a reward amount may carry: refused rather than silently rounded.
            throw CatalogSafety.invalid(invariant);
        }
        if (value.stripTrailingZeros().precision() > MAX_AMOUNT_PRECISION) {
            throw CatalogSafety.invalid(invariant);
        }
        return value.setScale(AMOUNT_SCALE);
    }

    /** Redacted diagnostics: only bounded machine facts, never inventory, a credential or a balance. */
    @Override
    public String toString() {
        return "RewardVersion[referenceCode=" + referenceCode
                + ", logicalId=" + logicalId
                + ", versionId=" + versionId
                + ", kind=" + kind
                + ", currency=" + currency
                + ", faceValue=" + faceValue.toPlainString()
                + ", maxPerGrant=" + maxPerGrant.toPlainString()
                + ", enabled=" + enabled
                + ", runtimeAvailable=" + runtimeAvailable + ']';
    }
}