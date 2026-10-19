package com.company.campaign.builder.capability.message;

import com.company.campaign.builder.capability.CapabilityPlanPreviewCard;
import com.company.campaign.builder.capability.catalog.MessageTemplateCatalog;
import com.company.campaign.builder.capability.catalog.MessageTemplateVariable;
import com.company.campaign.builder.capability.catalog.MessageTemplateVersion;
import com.company.campaign.builder.capability.pack.CapabilityFieldConstraint;
import com.company.campaign.builder.capability.pack.CapabilityFieldSchema;
import com.company.campaign.builder.capability.pack.CapabilityPackContract;
import com.company.campaign.builder.capability.pack.CapabilityPackMetadata;
import com.company.campaign.builder.capability.pack.CapabilityPayloadContract;
import com.company.campaign.builder.capability.pack.CapabilityValueKind;
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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The concrete, production {@code message.template} capability pack.
 *
 * <p>It resolves one payload that selects an exact template {@code referenceCode} and supplies a bounded
 * variables object, by looking the code up in the host-owned frozen {@link MessageTemplateCatalog}. There is
 * no {@code latest} resolution: the pack pins the one exact version the code names and freezes that version's
 * {@code logicalId}/{@code versionId}/content hash into both its normalized payload and its one stable
 * {@link ResolvedCatalogReference}.
 *
 * <p>It fails closed, in one deterministic order, on a missing or extra field, a missing/disabled/unavailable
 * version, a channel that is not the resolved version's own channel, and any variable that is missing, extra,
 * of the wrong scalar type or over its declared length. The payload's variables are validated against the
 * schema the <em>resolved version</em> declares, so a compile can never invent a variable the template does
 * not define.
 *
 * <p>The normalized payload freezes the template's logical/version identity and content hash, its channel and
 * the canonical variables. It deliberately carries no template body, no provider, no endpoint, no credential
 * and no trusted identity — none of those is representable in a resolved component. On success the pack
 * contributes exactly one generic capability preview card, bound to the component it just resolved.
 *
 * <p>Its frozen metadata is a fact of this contract version: the unordered multi-instance
 * {@code campaign.message} slot, authoring enabled, and a runtime compatibility that reports Preview as
 * supported while honestly declaring the capability <em>not</em> publishable, because production Apply,
 * Publish and Runtime do not exist in this task.
 */
public final class MessageTemplatePack implements CapabilityPackContract {

    /** The canonical capability code and exact contract version of this pack. */
    public static final String CAPABILITY_CODE = "message.template";
    public static final String CONTRACT_VERSION = "1.0";
    public static final String PACK_VERSION = "t19-v1";

    /** The one composition slot this pack occupies: multi-instance and unordered. */
    public static final String SLOT_CODE = "campaign.message";

    /** The exact runtime schema version this pack was built against. */
    public static final String RUNTIME_SCHEMA_VERSION = "campaign-runtime/1.0";

    /** The schema version of the pack metadata itself. */
    public static final String METADATA_SCHEMA_VERSION = "1.0";

    /** The bounded summary code the pack projects when it resolves a template version. */
    static final List<String> SUMMARY_CODES = List.of("TEMPLATE_RESOLVED");

    private static final CompositionSlot SLOT = new CompositionSlot(SLOT_CODE, false, false);

    /**
     * Preview is supported because the accepted read-only Preview path exists; production publishing is not,
     * so the pack never claims a runtime it does not have.
     */
    private static final RuntimeCompatibility RUNTIME =
            new RuntimeCompatibility(RUNTIME_SCHEMA_VERSION, true, false);

    private static final Set<String> DECLARED_FIELDS = Set.of("referenceCode", "variables", "channel");

    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);
    private static final Pattern CHANNEL = Pattern.compile(ContractPatterns.MACHINE_CODE);
    private static final String FAILURE_MESSAGE = "The message.template capability resolution failed closed.";

    private final MessageTemplateCatalog catalog;
    private final CapabilityPackMetadata metadata;

    /**
     * Creates the one production {@code message.template} contract over a frozen host-owned catalog.
     *
     * <p>The catalog is an immutable, hash-bound input; the pack holds no mutable state, reads no clock,
     * registry, provider or file, and is a pure function of its request.
     */
    public MessageTemplatePack(MessageTemplateCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.metadata = CapabilityPackMetadata.of(CAPABILITY_CODE, PACK_VERSION, CONTRACT_VERSION,
                SchemaVersion.parse(METADATA_SCHEMA_VERSION),
                CapabilityPlanPreviewCard.CAPABILITY_PREVIEW,
                CapabilityPlanPreviewCard.CARD_CONTRACT_VERSION,
                SLOT, RUNTIME, payloadContract(), MessageTemplateEvaluationAssets.asset());
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
     * The closed payload declaration: one required template reference code, and the optional channel and
     * variables.
     *
     * <p>{@code variables} is declared with a bounded text constraint because the common pack vocabulary
     * declares scalar, bounded fields only; the pack accepts the bounded variables <em>object</em> under that
     * field and validates each entry against the resolved version's own variable schema, so the object's real
     * per-name contract is the frozen catalog's rather than a per-pack constant.
     */
    private static CapabilityPayloadContract payloadContract() {
        return new CapabilityPayloadContract(
                List.of(new CapabilityFieldSchema("referenceCode", new CapabilityFieldConstraint.Text(128))),
                List.of(new CapabilityFieldSchema("variables", new CapabilityFieldConstraint.Text(4096)),
                        new CapabilityFieldSchema("channel", new CapabilityFieldConstraint.Text(64))));
    }

    @Override
    public CapabilityResolution resolve(CapabilityRequest request) {
        if (request == null) {
            return failed(MessageTemplateErrorCodes.MESSAGE_TEMPLATE_PAYLOAD_INVALID);
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
                throw new Rejection(MessageTemplateErrorCodes.MESSAGE_TEMPLATE_UNKNOWN_FIELD);
            }
        }

        String referenceCode = requireIdentifier(fields.get("referenceCode"));
        String channel = requireChannel(fields.get("channel"));
        Map<String, Object> supplied = asVariables(fields.get("variables"));

        MessageTemplateVersion template = catalog.resolve(referenceCode)
                .orElseThrow(() -> new Rejection(
                        MessageTemplateErrorCodes.MESSAGE_TEMPLATE_UNKNOWN_REFERENCE));
        if (!template.enabled()) {
            throw new Rejection(MessageTemplateErrorCodes.MESSAGE_TEMPLATE_DISABLED);
        }
        if (!template.runtimeAvailable()) {
            throw new Rejection(MessageTemplateErrorCodes.MESSAGE_TEMPLATE_UNAVAILABLE);
        }
        if (channel != null && !channel.equals(template.channel())) {
            throw new Rejection(MessageTemplateErrorCodes.MESSAGE_TEMPLATE_CHANNEL_MISMATCH);
        }

        Map<String, Object> variables = canonicalVariables(template, supplied);
        CapabilityPayload normalized = normalize(template, variables);
        List<ResolvedCatalogReference> references = List.of(template.resolvedReference());
        CapabilityPlanPreviewCard card = card(request.logicalId(), normalized, references);
        return new CapabilityResolution.Resolved(normalized, references, List.of(), List.of(card));
    }

    // ------------------------------------------------------------------ payload boundary

    private static String requireIdentifier(Object raw) {
        if (raw instanceof String text && IDENTIFIER.matcher(text).matches()) {
            return text;
        }
        throw new Rejection(MessageTemplateErrorCodes.MESSAGE_TEMPLATE_PAYLOAD_INVALID);
    }

    private static String requireChannel(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof String text && CHANNEL.matcher(text).matches()) {
            return text;
        }
        throw new Rejection(MessageTemplateErrorCodes.MESSAGE_TEMPLATE_PAYLOAD_INVALID);
    }

    /** The supplied variables object, or the honest empty object when the payload omits it. */
    private static Map<String, Object> asVariables(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> supplied = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String name)) {
                    throw new Rejection(MessageTemplateErrorCodes.MESSAGE_TEMPLATE_VARIABLES_INVALID);
                }
                supplied.put(name, entry.getValue());
            }
            return supplied;
        }
        throw new Rejection(MessageTemplateErrorCodes.MESSAGE_TEMPLATE_VARIABLES_INVALID);
    }

    /**
     * Validates the supplied variables against the resolved version's schema and freezes them canonically.
     *
     * <p>Every supplied name must be declared by the version (an extra variable fails), every required
     * variable must be present (a missing one fails), and every present value must match its declared scalar
     * type and — for text — its declared maximum length. The result is in deterministic name order.
     */
    private static Map<String, Object> canonicalVariables(
            MessageTemplateVersion template, Map<String, Object> supplied) {
        for (String name : supplied.keySet()) {
            if (template.variable(name) == null) {
                throw new Rejection(MessageTemplateErrorCodes.MESSAGE_TEMPLATE_VARIABLES_INVALID);
            }
        }
        Map<String, Object> canonical = new LinkedHashMap<>();
        for (MessageTemplateVariable variable : template.variables()) {
            if (!supplied.containsKey(variable.name())) {
                if (variable.required()) {
                    throw new Rejection(MessageTemplateErrorCodes.MESSAGE_TEMPLATE_VARIABLES_INVALID);
                }
                continue;
            }
            canonical.put(variable.name(), canonicalValue(variable, supplied.get(variable.name())));
        }
        return canonical;
    }

    /** One canonical variable value, proven to match its declared kind and bound. */
    private static Object canonicalValue(MessageTemplateVariable variable, Object value) {
        CapabilityValueKind type = variable.type();
        if (type == CapabilityValueKind.STRING) {
            if (value instanceof String text && text.length() <= variable.maxLength()) {
                return text;
            }
            throw new Rejection(MessageTemplateErrorCodes.MESSAGE_TEMPLATE_VARIABLES_INVALID);
        }
        if (type == CapabilityValueKind.BOOLEAN) {
            if (value instanceof Boolean flag) {
                return flag;
            }
            throw new Rejection(MessageTemplateErrorCodes.MESSAGE_TEMPLATE_VARIABLES_INVALID);
        }
        if (type == CapabilityValueKind.INTEGER) {
            return asInteger(value);
        }
        if (type == CapabilityValueKind.DECIMAL) {
            if (value instanceof Number number) {
                return new BigDecimal(number.toString());
            }
            throw new Rejection(MessageTemplateErrorCodes.MESSAGE_TEMPLATE_VARIABLES_INVALID);
        }
        if (type == CapabilityValueKind.DATE) {
            return asDate(value);
        }
        return asDateTime(value);
    }

    private static BigInteger asInteger(Object value) {
        if (value instanceof BigInteger integer) {
            return integer;
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short
                || value instanceof Byte) {
            return BigInteger.valueOf(((Number) value).longValue());
        }
        if (value instanceof BigDecimal decimal) {
            try {
                return decimal.toBigIntegerExact();
            } catch (ArithmeticException exception) {
                throw new Rejection(MessageTemplateErrorCodes.MESSAGE_TEMPLATE_VARIABLES_INVALID);
            }
        }
        throw new Rejection(MessageTemplateErrorCodes.MESSAGE_TEMPLATE_VARIABLES_INVALID);
    }

    private static String asDate(Object value) {
        if (value instanceof String text) {
            try {
                return LocalDate.parse(text).toString();
            } catch (DateTimeParseException exception) {
                throw new Rejection(MessageTemplateErrorCodes.MESSAGE_TEMPLATE_VARIABLES_INVALID);
            }
        }
        throw new Rejection(MessageTemplateErrorCodes.MESSAGE_TEMPLATE_VARIABLES_INVALID);
    }

    private static String asDateTime(Object value) {
        if (value instanceof String text) {
            try {
                return OffsetDateTime.parse(text).toString();
            } catch (DateTimeParseException exception) {
                throw new Rejection(MessageTemplateErrorCodes.MESSAGE_TEMPLATE_VARIABLES_INVALID);
            }
        }
        throw new Rejection(MessageTemplateErrorCodes.MESSAGE_TEMPLATE_VARIABLES_INVALID);
    }

    // ------------------------------------------------------------------ normalized payload and card

    private static CapabilityPayload normalize(MessageTemplateVersion template, Map<String, Object> variables) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("templateLogicalId", template.logicalId());
        fields.put("templateVersionId", template.versionId());
        fields.put("templateContentHash", template.contentHash().value());
        fields.put("channel", template.channel());
        fields.put("variables", variables);
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
        return "MessageTemplatePack[capabilityCode=" + CAPABILITY_CODE
                + ", contractVersion=" + CONTRACT_VERSION + ']';
    }
}