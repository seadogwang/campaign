package com.company.campaign.builder.assembly;

import com.company.campaign.builder.campaignagent.CampaignMessageInputs;
import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.builder.journey.JourneyGraphDraft;
import com.company.campaign.builder.journey.JourneySimulationInput;
import com.company.campaign.contracts.ContractPatterns;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The closed union of typed Campaign Tool requests.
 *
 * <p>A Tool accepts exactly one of these typed records and never an unrestricted {@code Map}, a raw JSON
 * document or a callback: the shape of a request is decided by the closed union, so a caller cannot invent a
 * field, name a backend or smuggle a trusted fact into a Tool. Every variant reports the one Tool identifier it
 * addresses and one bounded canonical argument map, and that map is the only thing the invoker hashes or
 * measures.
 *
 * <p>Supplied variable values and supplied Schedule / Governance declarations travel as an immutable
 * {@link CapabilityPayload}, which is already proven bounded and free of reserved trusted names; supplied
 * discovery text is bounded and free of control characters. There is deliberately no variant for Save, Apply,
 * Publish, Pause, Resume, Retry, Archive, Runtime activation, Provider access, credential access or storage
 * access.
 */
public sealed interface CampaignToolRequest {

    /** The one server-owned Tool identifier this request addresses. */
    String toolId();

    /** The bounded canonical arguments this request carries, used for hashing and size measurement. */
    Map<String, Object> canonicalArguments();

    /** Largest discovery result a request may ask for. */
    int MAX_DISCOVERY_LIMIT = CampaignToolDescriptor.MAX_RESULT_ITEMS;

    /** Largest bounded discovery text length. */
    int MAX_QUERY_LENGTH = 64;

    /** Largest inclusive reward quantity. */
    long MAX_QUANTITY = 1_000_000L;

    /** The absolute scale a governance ceiling may carry. */
    int CEILING_SCALE = 4;

    /** The absolute precision a governance ceiling may carry. */
    int CEILING_PRECISION = 18;

    /** Bounded discovery over the immutable Message Template catalog. */
    record MessageDiscover(String query, int limit) implements CampaignToolRequest {


        public MessageDiscover {
            query = BoundedText.query(query);
            limit = BoundedText.limit(limit);
        }

        @Override
        public String toolId() {
            return CampaignToolIds.MESSAGE_TEMPLATE_DISCOVER;
        }

        @Override
        public Map<String, Object> canonicalArguments() {
            return BoundedText.args(query, limit);
        }

        /** A discovery of everything the catalog holds, within the bounded default. */
        public static MessageDiscover all() {
            return new MessageDiscover(null, MAX_DISCOVERY_LIMIT);
        }
    }

    /** Resolution of one Message Template reference to one exact immutable version. */
    record MessageResolve(String referenceCode) implements CampaignToolRequest {

        public MessageResolve {
            referenceCode = BoundedText.identifier(referenceCode);
        }

        @Override
        public String toolId() {
            return CampaignToolIds.MESSAGE_TEMPLATE_RESOLVE;
        }

        @Override
        public Map<String, Object> canonicalArguments() {
            return Map.of("referenceCode", referenceCode);
        }
    }

    /** Validation of supplied variables against one exact resolved template version. */
    record MessageVariablesValidate(String referenceCode, CapabilityPayload variables)
            implements CampaignToolRequest {

        public MessageVariablesValidate {
            referenceCode = BoundedText.identifier(referenceCode);
            variables = BoundedText.variables(variables);
        }

        @Override
        public String toolId() {
            return CampaignToolIds.MESSAGE_VARIABLES_VALIDATE;
        }

        @Override
        public Map<String, Object> canonicalArguments() {
            return BoundedText.payloadArgs(referenceCode, variables);
        }
    }

    /** A bounded safe projection for one resolved and validated template version. */
    record MessagePreviewSafe(String referenceCode, CapabilityPayload variables)
            implements CampaignToolRequest {

        public MessagePreviewSafe {
            referenceCode = BoundedText.identifier(referenceCode);
            variables = BoundedText.variables(variables);
        }

        @Override
        public String toolId() {
            return CampaignToolIds.MESSAGE_PREVIEW_SAFE;
        }

        @Override
        public Map<String, Object> canonicalArguments() {
            return BoundedText.payloadArgs(referenceCode, variables);
        }
    }

    /**
     * Safe validation and preview of one inline Campaign message draft.
     *
     * <p>It carries only the closed canonical channel and the exact user-authored body. There is no reference
     * code, variables object, template, Provider, credential, endpoint, member fact or lifecycle field a
     * caller could shape: an inline draft cannot name a catalog resource, and the handler re-proves both the
     * channel and the body at this host-owned boundary before deriving any hash or identity from them.
     */
    record MessageInlinePreview(CampaignMessageInputs.Channel channel, String body)
            implements CampaignToolRequest {

        public MessageInlinePreview {
            channel = BoundedText.channel(channel);
            body = BoundedText.body(body);
        }

        @Override
        public String toolId() {
            return CampaignToolIds.MESSAGE_INLINE_PREVIEW_SAFE;
        }

        @Override
        public Map<String, Object> canonicalArguments() {
            return BoundedText.inlineArgs(channel, body);
        }
    }

    /** Bounded discovery over the immutable Reward catalog. */
    record RewardDiscover(String query, int limit) implements CampaignToolRequest {


        public RewardDiscover {
            query = BoundedText.query(query);
            limit = BoundedText.limit(limit);
        }

        @Override
        public String toolId() {
            return CampaignToolIds.REWARD_DISCOVER;
        }

        @Override
        public Map<String, Object> canonicalArguments() {
            return BoundedText.args(query, limit);
        }

        /** A discovery of everything the catalog holds, within the bounded default. */
        public static RewardDiscover all() {
            return new RewardDiscover(null, MAX_DISCOVERY_LIMIT);
        }
    }

    /** Resolution of one Reward reference to one exact immutable version. */
    record RewardResolve(String referenceCode) implements CampaignToolRequest {

        public RewardResolve {
            referenceCode = BoundedText.identifier(referenceCode);
        }

        @Override
        public String toolId() {
            return CampaignToolIds.REWARD_RESOLVE;
        }

        @Override
        public Map<String, Object> canonicalArguments() {
            return Map.of("referenceCode", referenceCode);
        }
    }

    /** Deterministic exposure calculation from explicit exact inputs. */
    record RewardExposureCalculate(String referenceCode, int quantity, BigDecimal governanceCeiling)
            implements CampaignToolRequest {


        public RewardExposureCalculate {
            referenceCode = BoundedText.identifier(referenceCode);
            quantity = BoundedText.quantity(quantity);
            governanceCeiling = BoundedText.ceiling(governanceCeiling);
        }

        @Override
        public String toolId() {
            return CampaignToolIds.REWARD_EXPOSURE_CALCULATE;
        }

        @Override
        public Map<String, Object> canonicalArguments() {
            return BoundedText.exposureArgs(referenceCode, quantity, governanceCeiling);
        }
    }

    /** A bounded safe projection for one resolved reward version. */
    record RewardPreviewSafe(String referenceCode, int quantity, BigDecimal governanceCeiling)
            implements CampaignToolRequest {

        public RewardPreviewSafe {
            referenceCode = BoundedText.identifier(referenceCode);
            quantity = BoundedText.quantity(quantity);
            governanceCeiling = BoundedText.ceiling(governanceCeiling);
        }

        @Override
        public String toolId() {
            return CampaignToolIds.REWARD_PREVIEW_SAFE;
        }

        @Override
        public Map<String, Object> canonicalArguments() {
            return BoundedText.exposureArgs(referenceCode, quantity, governanceCeiling);
        }
    }

    /** Normalization of one bounded {@code schedule.basic} declaration. */
    record ScheduleNormalize(String componentId, CapabilityPayload declaration) implements CampaignToolRequest {

        public ScheduleNormalize {
            componentId = BoundedText.identifier(componentId);
            declaration = BoundedText.declaration(declaration);
        }

        @Override
        public String toolId() {
            return CampaignToolIds.SCHEDULE_NORMALIZE;
        }

        @Override
        public Map<String, Object> canonicalArguments() {
            return BoundedText.declarationArgs(componentId, declaration);
        }
    }

    /** Validation of one normalized {@code schedule.basic} declaration. */
    record ScheduleValidate(String componentId, CapabilityPayload declaration) implements CampaignToolRequest {

        public ScheduleValidate {
            componentId = BoundedText.identifier(componentId);
            declaration = BoundedText.declaration(declaration);
        }

        @Override
        public String toolId() {
            return CampaignToolIds.SCHEDULE_VALIDATE;
        }

        @Override
        public Map<String, Object> canonicalArguments() {
            return BoundedText.declarationArgs(componentId, declaration);
        }
    }

    /** Deterministic declared-exposure calculation over one bounded {@code governance.limit} declaration. */
    record GovernanceExposureCalculate(String componentId, CapabilityPayload declaration)
            implements CampaignToolRequest {

        public GovernanceExposureCalculate {
            componentId = BoundedText.identifier(componentId);
            declaration = BoundedText.declaration(declaration);
        }

        @Override
        public String toolId() {
            return CampaignToolIds.GOVERNANCE_EXPOSURE_CALCULATE;
        }

        @Override
        public Map<String, Object> canonicalArguments() {
            return BoundedText.declarationArgs(componentId, declaration);
        }
    }

    /** Validation of one bounded {@code governance.limit} declaration. */
    record GovernanceValidate(String componentId, CapabilityPayload declaration)
            implements CampaignToolRequest {

        public GovernanceValidate {
            componentId = BoundedText.identifier(componentId);
            declaration = BoundedText.declaration(declaration);
        }

        @Override
        public String toolId() {
            return CampaignToolIds.GOVERNANCE_VALIDATE;
        }

        @Override
        public Map<String, Object> canonicalArguments() {
            return BoundedText.declarationArgs(componentId, declaration);
        }
    }

    // ------------------------------------------------------------------ Journey

    /** Bounded projection of the closed Journey node capability catalog. */
    record JourneyNodeCatalog(int limit) implements CampaignToolRequest {

        public JourneyNodeCatalog {
            limit = BoundedText.limit(limit);
        }

        @Override
        public String toolId() {
            return CampaignToolIds.JOURNEY_NODE_CATALOG;
        }

        @Override
        public Map<String, Object> canonicalArguments() {
            return BoundedText.args(null, limit);
        }

        /** The whole closed node catalog, within the bounded default. */
        public static JourneyNodeCatalog all() {
            return new JourneyNodeCatalog(MAX_DISCOVERY_LIMIT);
        }
    }

    /**
     * Normalization of every declared Journey node through its accepted T19 Flow pack.
     *
     * <p>The whole declaration travels as one typed, bounded draft: there is no node list, edges list or payload
     * map a caller could shape into an unrepresentable request.
     */
    record JourneyCompose(JourneyGraphDraft draft) implements CampaignToolRequest {

        public JourneyCompose {
            Objects.requireNonNull(draft, "draft");
        }

        @Override
        public String toolId() {
            return CampaignToolIds.JOURNEY_COMPOSE;
        }

        @Override
        public Map<String, Object> canonicalArguments() {
            return BoundedText.draftArgs(draft);
        }
    }

    /** Validation and compilation of one complete Journey authoring graph. */
    record JourneyValidate(JourneyGraphDraft draft) implements CampaignToolRequest {

        public JourneyValidate {
            Objects.requireNonNull(draft, "draft");
        }

        @Override
        public String toolId() {
            return CampaignToolIds.JOURNEY_VALIDATE;
        }

        @Override
        public Map<String, Object> canonicalArguments() {
            return BoundedText.draftArgs(draft);
        }
    }

    /**
     * Pure preview simulation of one complete Journey authoring graph.
     *
     * <p>The simulation travels as an explicit bounded virtual-clock input; there is no clock, random source or
     * member fact a caller could substitute for it.
     */
    record JourneySimulate(JourneyGraphDraft draft, JourneySimulationInput simulation)
            implements CampaignToolRequest {

        public JourneySimulate {
            Objects.requireNonNull(draft, "draft");
            Objects.requireNonNull(simulation, "simulation");
        }

        @Override
        public String toolId() {
            return CampaignToolIds.JOURNEY_SIMULATE;
        }

        @Override
        public Map<String, Object> canonicalArguments() {
            Map<String, Object> args = BoundedText.draftArgs(draft);
            args.put("simulation", simulation);
            return args;
        }
    }

    /** The bounded-value rules every typed request shares. */
    final class BoundedText {

        private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);

        private BoundedText() {
        }

        static String identifier(String value) {
            if (value == null || !IDENTIFIER.matcher(value).matches()) {
                throw CampaignAssemblyException.invalid(
                        CampaignAssemblyErrorCodes.TOOL_ARGUMENT_INVALID,
                        "A Tool request requires a bounded catalog reference.");
            }
            return value;
        }

        static String query(String value) {
            if (value == null) {
                return null;
            }
            if (value.isEmpty() || value.length() > MAX_QUERY_LENGTH) {
                throw CampaignAssemblyException.invalid(
                        CampaignAssemblyErrorCodes.TOOL_ARGUMENT_INVALID,
                        "A discovery query must be a bounded non-empty text.");
            }
            for (int index = 0; index < value.length(); index++) {
                if (Character.isISOControl(value.charAt(index))) {
                    throw CampaignAssemblyException.invalid(
                            CampaignAssemblyErrorCodes.TOOL_ARGUMENT_INVALID,
                            "A discovery query must not carry control characters.");
                }
            }
            return value;
        }

        static int limit(int value) {
            if (value < 1 || value > MAX_DISCOVERY_LIMIT) {
                throw CampaignAssemblyException.invalid(
                        CampaignAssemblyErrorCodes.TOOL_ARGUMENT_INVALID,
                        "A discovery limit must be within its bounded range.");
            }
            return value;
        }

        static int quantity(int value) {
            if (value < 1 || value > MAX_QUANTITY) {
                throw CampaignAssemblyException.invalid(
                        CampaignAssemblyErrorCodes.TOOL_EXPOSURE_INVALID,
                        "A reward quantity must be a positive bounded whole number.");
            }
            return value;
        }

        static BigDecimal ceiling(BigDecimal value) {
            if (value == null || value.signum() < 0 || value.scale() > CEILING_SCALE
                    || value.stripTrailingZeros().precision() > CEILING_PRECISION) {
                throw CampaignAssemblyException.invalid(
                        CampaignAssemblyErrorCodes.TOOL_EXPOSURE_INVALID,
                        "A governance ceiling must be a bounded non-negative exact amount.");
            }
            return value.setScale(CEILING_SCALE);
        }

        static CapabilityPayload variables(CapabilityPayload value) {
            if (value == null) {
                throw CampaignAssemblyException.invalid(
                        CampaignAssemblyErrorCodes.TOOL_VARIABLES_INVALID,
                        "A variable request requires a bounded immutable variables object.");
            }
            return value;
        }

        /**
         * One canonical delivery channel of an inline draft.
         *
         * <p>The channel vocabulary is the closed host-owned enum, so revalidating it here is a null check
         * rather than a keyword rule: a caller can never widen it by naming a new code.
         */
        static CampaignMessageInputs.Channel channel(CampaignMessageInputs.Channel value) {
            if (value == null) {
                throw CampaignAssemblyException.invalid(
                        CampaignAssemblyErrorCodes.TOOL_ARGUMENT_INVALID,
                        "An inline draft requires a canonical delivery channel.");
            }
            return value;
        }

        /**
         * One exact bounded user-authored inline body.
         *
         * <p>The body is preserved byte-for-byte: it is never trimmed, translated, truncated or normalized,
         * and an absent, empty or oversized body is refused rather than guessed at.
         */
        static String body(String value) {
            if (value == null || value.isEmpty() || value.length() > CampaignMessageInputs.MAX_BODY_LENGTH) {
                throw CampaignAssemblyException.invalid(
                        CampaignAssemblyErrorCodes.TOOL_ARGUMENT_INVALID,
                        "An inline draft requires one bounded non-empty body.");
            }
            return value;
        }

        /**
         * One bounded immutable declaration object.
         *
         * <p>A Schedule or Governance request carries its whole behavioral input as an already-canonical
         * {@link CapabilityPayload}, so the accepted pack — never this boundary — decides which field names are
         * declared. An absent or empty declaration fails closed as a bounded argument error.
         */
        static CapabilityPayload declaration(CapabilityPayload value) {
            if (value == null || value.isEmpty()) {
                throw CampaignAssemblyException.invalid(
                        CampaignAssemblyErrorCodes.TOOL_ARGUMENT_INVALID,
                        "A declaration request requires a bounded non-empty immutable declaration object.");
            }
            return value;
        }

        static Map<String, Object> args(String query, int limit) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("limit", limit);
            if (query != null) {
                args.put("query", query);
            }
            return args;
        }

        static Map<String, Object> payloadArgs(String referenceCode, CapabilityPayload variables) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("referenceCode", referenceCode);
            args.put("variables", variables.fields());
            return args;
        }

        /** The canonical arguments of one inline draft request: the channel code and the exact body only. */
        static Map<String, Object> inlineArgs(CampaignMessageInputs.Channel channel, String body) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("channel", channel.name());
            args.put("body", body);
            return args;
        }

        static Map<String, Object> exposureArgs(String referenceCode, int quantity, BigDecimal ceiling) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("referenceCode", referenceCode);
            args.put("quantity", quantity);
            args.put("governanceCeiling", ceiling);
            return args;
        }

        /** The canonical arguments of one declaration request: the identity plus the whole declaration. */
        static Map<String, Object> declarationArgs(String componentId, CapabilityPayload declaration) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("componentId", componentId);
            args.put("declaration", declaration.fields());
            return args;
        }

        /**
         * The canonical arguments of one Journey draft request: the whole typed declaration, unchanged.
         *
         * <p>The three bounded collections are the request's entire input, so what the invoker measures and
         * fingerprints is exactly what a handler is handed.
         */
        static Map<String, Object> draftArgs(JourneyGraphDraft draft) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("nodes", draft.nodes());
            args.put("edges", draft.edges());
            args.put("references", draft.references());
            return args;
        }
    }
}