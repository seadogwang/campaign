package com.company.campaign.builder.capability.promotion;

import com.company.campaign.builder.capability.CapabilityPlanPreviewCard;
import com.company.campaign.builder.capability.catalog.RewardCatalog;
import com.company.campaign.builder.capability.catalog.RewardVersion;
import com.company.campaign.builder.capability.pack.CapabilityFieldConstraint;
import com.company.campaign.builder.capability.pack.CapabilityFieldSchema;
import com.company.campaign.builder.capability.pack.CapabilityPackContract;
import com.company.campaign.builder.capability.pack.CapabilityPackMetadata;
import com.company.campaign.builder.capability.pack.CapabilityPayloadContract;
import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.builder.compiler.CapabilityPreviewCards;
import com.company.campaign.builder.compiler.CapabilityRequest;
import com.company.campaign.builder.compiler.CapabilityResolution;
import com.company.campaign.builder.compiler.CompositionSlot;
import com.company.campaign.builder.compiler.ResolvedCatalogReference;
import com.company.campaign.builder.compiler.RuntimeCompatibility;
import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.contracts.Issue;
import com.company.campaign.contracts.SchemaVersion;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The concrete, production {@code promotion.reference} capability pack.
 *
 * <p>It resolves one payload that selects an exact reward {@code referenceCode} and declares a positive
 * {@code quantity} (default {@code 1}), by looking the code up in the host-owned frozen {@link RewardCatalog}.
 * There is no {@code latest} resolution: the pack pins the one exact version the code names and freezes that
 * version's {@code logicalId}/{@code versionId}/content hash into both its normalized payload and its one
 * stable {@link ResolvedCatalogReference}.
 *
 * <p>It fails closed, in one deterministic order, on a missing or extra field, a non-positive or
 * non-integral quantity, a missing/disabled/unavailable version, an amount that overflows the declared
 * precision and a grant whose exact total exceeds the version's per-grant maximum. Any client attempt to
 * declare inventory, a credential, a Provider fact or a grant result is an undeclared field and is refused
 * outright — none of those is representable in a resolved component at all.
 *
 * <p>The normalized payload freezes the reward's logical/version identity and content hash, its kind, its
 * currency, the exact face value of one unit and the quantity. It carries no inventory authority, no Provider
 * credential, no mutable balance and no grant result. On success the pack contributes exactly one generic
 * capability preview card, bound to the component it just resolved.
 *
 * <p>Its frozen metadata is a fact of this contract version: the unordered multi-instance
 * {@code campaign.promotion} slot, authoring enabled, and a runtime compatibility that reports Preview as
 * supported while honestly declaring the capability <em>not</em> publishable, because production Apply,
 * Publish and Runtime do not exist in this task.
 */
public final class PromotionReferencePack implements CapabilityPackContract {

    /** The canonical capability code and exact contract version of this pack. */
    public static final String CAPABILITY_CODE = "promotion.reference";
    public static final String CONTRACT_VERSION = "1.0";
    public static final String PACK_VERSION = "t19-v1";

    /** The one composition slot this pack occupies: multi-instance and unordered. */
    public static final String SLOT_CODE = "campaign.promotion";

    /** The exact runtime schema version this pack was built against. */
    public static final String RUNTIME_SCHEMA_VERSION = "campaign-runtime/1.0";

    /** The schema version of the pack metadata itself. */
    public static final String METADATA_SCHEMA_VERSION = "1.0";

    /** The default quantity a payload that declares none receives. */
    public static final int DEFAULT_QUANTITY = 1;

    /** The inclusive upper bound the declared quantity may carry. */
    public static final long MAX_QUANTITY = 1_000_000L;

    /** The bounded summary code the pack projects when it resolves a reward version. */
    static final List<String> SUMMARY_CODES = List.of("REWARD_RESOLVED");

    private static final CompositionSlot SLOT = new CompositionSlot(SLOT_CODE, false, false);

    /**
     * Preview is supported because the accepted read-only Preview path exists; production publishing is not,
     * so the pack never claims a runtime it does not have.
     */
    private static final RuntimeCompatibility RUNTIME =
            new RuntimeCompatibility(RUNTIME_SCHEMA_VERSION, true, false);

    private static final Set<String> DECLARED_FIELDS = Set.of("referenceCode", "quantity");

    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);
    private static final String FAILURE_MESSAGE =
            "The promotion.reference capability resolution failed closed.";

    private final RewardCatalog catalog;
    private final CapabilityPackMetadata metadata;

    /**
     * Creates the one production {@code promotion.reference} contract over a frozen host-owned catalog.
     *
     * <p>The catalog is an immutable, hash-bound input; the pack holds no mutable state, reads no clock,
     * registry, provider, balance or file, and is a pure function of its request.
     */
    public PromotionReferencePack(RewardCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.metadata = CapabilityPackMetadata.of(CAPABILITY_CODE, PACK_VERSION, CONTRACT_VERSION,
                SchemaVersion.parse(METADATA_SCHEMA_VERSION),
                CapabilityPlanPreviewCard.CAPABILITY_PREVIEW,
                CapabilityPlanPreviewCard.CARD_CONTRACT_VERSION,
                SLOT, RUNTIME, payloadContract(), RewardReferenceEvaluationAssets.asset());
    }

    @Override
    public CapabilityPackMetadata packMetadata() {
        return metadata;
    }

    @Override
    public String capabilityCode() {
        return CAPABILITY_CODE;
    }

    @Override
    public String contractVersion() {
        return CONTRACT_VERSION;
    }

    @Override
    public CompositionSlot slot() {
        return SLOT;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public RuntimeCompatibility runtimeCompatibility() {
        return RUNTIME;
    }

    /**
     * The closed payload declaration: one required reward reference code and the optional positive quantity.
     *
     * <p>There is deliberately no declared field for inventory, a credential, a Provider fact or a grant
     * result, so a payload that declares one is an unknown field and fails closed.
     */
    private static CapabilityPayloadContract payloadContract() {
        return new CapabilityPayloadContract(
                List.of(new CapabilityFieldSchema("referenceCode", new CapabilityFieldConstraint.Text(128))),
                List.of(new CapabilityFieldSchema("quantity",
                        new CapabilityFieldConstraint.IntegerBounds(1L, MAX_QUANTITY))));
    }

    @Override
    public CapabilityResolution resolve(CapabilityRequest request) {
        if (request == null) {
            return failed(RewardReferenceErrorCodes.REWARD_REFERENCE_PAYLOAD_INVALID);
        }
        try {
            return resolveOrThrow(request);
        } catch (Rejection rejected) {
            return failed(rejected.code);
        }
    }

    // ------------------------------------------------------------------ resolution

    private CapabilityResolution resolveOrThrow(CapabilityRequest request) {
        Map<String, Object> fields = request.payload().fields();
        for (String field : fields.keySet()) {
            if (!DECLARED_FIELDS.contains(field)) {
                throw new Rejection(RewardReferenceErrorCodes.REWARD_REFERENCE_UNKNOWN_FIELD);
            }
        }

        String referenceCode = requireIdentifier(fields.get("referenceCode"));
        int quantity = requireQuantity(fields.get("quantity"));

        RewardVersion reward = catalog.resolve(referenceCode)
                .orElseThrow(() -> new Rejection(
                        RewardReferenceErrorCodes.REWARD_REFERENCE_UNKNOWN_REFERENCE));
        if (!reward.enabled()) {
            throw new Rejection(RewardReferenceErrorCodes.REWARD_REFERENCE_DISABLED);
        }
        if (!reward.runtimeAvailable()) {
            throw new Rejection(RewardReferenceErrorCodes.REWARD_REFERENCE_UNAVAILABLE);
        }

        requireGrantWithinMaximum(reward, quantity);
        CapabilityPayload normalized = normalize(reward, quantity);
        List<ResolvedCatalogReference> references = List.of(reward.resolvedReference());
        CapabilityPlanPreviewCard card = card(request.logicalId(), normalized, references);
        return new CapabilityResolution.Resolved(normalized, references, List.of(), List.of(card));
    }

    // ------------------------------------------------------------------ payload boundary

    private static String requireIdentifier(Object raw) {
        if (raw instanceof String text && IDENTIFIER.matcher(text).matches()) {
            return text;
        }
        throw new Rejection(RewardReferenceErrorCodes.REWARD_REFERENCE_PAYLOAD_INVALID);
    }

    /** The positive whole-number quantity, or the declared default when the payload omits it. */
    private static int requireQuantity(Object raw) {
        if (raw == null) {
            return DEFAULT_QUANTITY;
        }
        BigInteger value;
        if (raw instanceof BigInteger integer) {
            value = integer;
        } else if (raw instanceof Integer || raw instanceof Long || raw instanceof Short
                || raw instanceof Byte) {
            value = BigInteger.valueOf(((Number) raw).longValue());
        } else if (raw instanceof BigDecimal decimal) {
            try {
                value = decimal.toBigIntegerExact();
            } catch (ArithmeticException exception) {
                throw new Rejection(RewardReferenceErrorCodes.REWARD_REFERENCE_QUANTITY_INVALID);
            }
        } else {
            throw new Rejection(RewardReferenceErrorCodes.REWARD_REFERENCE_QUANTITY_INVALID);
        }
        if (value.signum() <= 0 || value.compareTo(BigInteger.valueOf(MAX_QUANTITY)) > 0) {
            throw new Rejection(RewardReferenceErrorCodes.REWARD_REFERENCE_QUANTITY_INVALID);
        }
        return value.intValueExact();
    }

    /**
     * Proves the exact total value of one grant neither overflows the declared precision nor exceeds the
     * version's own per-grant maximum.
     */
    private static void requireGrantWithinMaximum(RewardVersion reward, int quantity) {
        BigDecimal total = reward.faceValue().multiply(BigDecimal.valueOf(quantity));
        if (total.precision() > RewardVersion.MAX_AMOUNT_PRECISION) {
            throw new Rejection(RewardReferenceErrorCodes.REWARD_REFERENCE_AMOUNT_OVERFLOW);
        }
        if (total.compareTo(reward.maxPerGrant()) > 0) {
            throw new Rejection(RewardReferenceErrorCodes.REWARD_REFERENCE_EXCEEDS_MAX_PER_GRANT);
        }
    }

    // ------------------------------------------------------------------ normalized payload and card

    private static CapabilityPayload normalize(RewardVersion reward, int quantity) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("rewardLogicalId", reward.logicalId());
        fields.put("rewardVersionId", reward.versionId());
        fields.put("rewardContentHash", reward.contentHash().value());
        fields.put("kind", reward.kind());
        fields.put("currency", reward.currency());
        fields.put("faceValue", reward.faceValue());
        fields.put("quantity", quantity);
        return CapabilityPayload.of(fields);
    }

    private CapabilityPlanPreviewCard card(
            String logicalId, CapabilityPayload normalized, List<ResolvedCatalogReference> references) {
        return new CapabilityPlanPreviewCard(
                CapabilityPlanPreviewCard.CAPABILITY_PREVIEW,
                CapabilityPlanPreviewCard.CARD_CONTRACT_VERSION,
                logicalId,
                CAPABILITY_CODE,
                CONTRACT_VERSION,
                normalized.contentHash(),
                CapabilityPreviewCards.canonicalReferenceHash(references),
                RUNTIME,
                SUMMARY_CODES,
                List.of(),
                metadata.evaluationAsset().contentHash());
    }

    // ------------------------------------------------------------------ failure shape

    private static CapabilityResolution failed(String code) {
        return new CapabilityResolution.Failed(List.of(Issue.error(code, FAILURE_MESSAGE)));
    }

    /** One bounded, ordered rejection: the code is safe, the rejected value never is. */
    private static final class Rejection extends RuntimeException {

        private final String code;

        private Rejection(String code) {
            // The cause and message are closed constants; no rejected value is ever attached.
            super(FAILURE_MESSAGE, null, false, false);
            this.code = code;
        }
    }

    /** A bounded diagnostic: this pack holds no value that could leak, so it prints only its identity. */
    @Override
    public String toString() {
        return "PromotionReferencePack[capabilityCode=" + CAPABILITY_CODE
                + ", contractVersion=" + CONTRACT_VERSION + ']';
    }
}