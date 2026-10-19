package com.company.campaign.builder.assembly;

import com.company.campaign.builder.campaignagent.CampaignMessageInputs;
import com.company.campaign.builder.capability.catalog.MessageTemplateVersion;
import com.company.campaign.builder.capability.catalog.RewardVersion;
import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.builder.journey.JourneyComposition;
import com.company.campaign.builder.journey.JourneyNodeCapability;
import com.company.campaign.builder.journey.JourneySimulationSummary;
import com.company.campaign.builder.journey.JourneyValidationOutcome;
import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The closed, host-private typed result union an admitted Campaign Tool hands back.
 *
 * <p>The real accepted catalog and capability-pack operation runs <em>inside</em> the Tool boundary, so the
 * genuine artifact of a protected operation is produced by the admitted handler rather than before the call.
 * The artifact travels beside the model-visible {@link CampaignToolOutcome} and is never part of it: the public
 * envelope stays bounded, redacted and hash-based, while this union carries the typed value the host adapters
 * need.
 *
 * <p>The union is sealed, so a consumer switches over the closed set of variants instead of casting. There is
 * no {@code Object} payload, no raw map and no callback, so an artifact cannot be forged from a model payload.
 * Every admission additionally binds the artifact to the exact call identifier, Tool identifier, catalog hash
 * and public outcome hash.
 */
public sealed interface CampaignToolArtifact {

    private static CanonicalJson canonicalJson() {
        return new CanonicalJson();
    }

    /** The one server-owned Tool identifier that produced this artifact. */
    String toolId();

    /** The content hash the public Tool outcome must publish for this artifact. */
    ContentHash publicEvidenceHash();

    /** Bounded discovery over the immutable Message Template catalog. */
    record MessageDiscovery(List<CampaignCatalogSummary> matches, ContentHash catalogHash)
            implements CampaignToolArtifact {

        public MessageDiscovery {
            matches = matches == null ? List.of() : List.copyOf(new ArrayList<>(matches));
            if (catalogHash == null) {
                throw CampaignAssemblyException.invalid(
                        CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT,
                        "A Message discovery artifact requires the pinned catalog hash.");
            }
        }

        @Override
        public String toolId() {
            return CampaignToolIds.MESSAGE_TEMPLATE_DISCOVER;
        }

        @Override
        public ContentHash publicEvidenceHash() {
            return canonicalJson().hash(new DiscoveryMaterial(matches, catalogHash));
        }
    }

    /** The one exact enabled/runtime-compatible immutable Message Template version. */
    record MessageResolution(MessageTemplateVersion version, ContentHash catalogHash)
            implements CampaignToolArtifact {

        public MessageResolution {
            if (version == null) {
                throw CampaignAssemblyException.invalid(
                        CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT,
                        "A Message resolution artifact requires the exact resolved version.");
            }
        }

        @Override
        public String toolId() {
            return CampaignToolIds.MESSAGE_TEMPLATE_RESOLVE;
        }

        @Override
        public ContentHash publicEvidenceHash() {
            return version.contentHash();
        }
    }

    /** The accepted validation of supplied variables against one exact resolved version. */
    record MessageValidation(
            MessageTemplateVersion version,
            CapabilityPayload variables,
            ContentHash catalogHash) implements CampaignToolArtifact {

        public MessageValidation {
            if (version == null || variables == null) {
                throw CampaignAssemblyException.invalid(
                        CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT,
                        "A Message validation artifact requires the resolved version and its variables.");
            }
        }

        @Override
        public String toolId() {
            return CampaignToolIds.MESSAGE_VARIABLES_VALIDATE;
        }

        @Override
        public ContentHash publicEvidenceHash() {
            Map<String, Object> material = new LinkedHashMap<>();
            material.put("version", version.contentHash());
            material.put("variables", variables.contentHash());
            return canonicalJson().hash(material);
        }
    }

    /** The safe card plus the host-private normalized payload of one resolved and validated version. */
    record MessageSafePreview(
            MessageTemplateVersion version,
            CapabilityPayload normalized,
            MessageSafePreviewCard card,
            ContentHash catalogHash) implements CampaignToolArtifact {

        public MessageSafePreview {
            if (version == null || normalized == null || card == null) {
                throw CampaignAssemblyException.invalid(
                        CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT,
                        "A Message safe preview artifact requires its resolved version, payload and card.");
            }
        }

        @Override
        public String toolId() {
            return CampaignToolIds.MESSAGE_PREVIEW_SAFE;
        }

        @Override
        public ContentHash publicEvidenceHash() {
            return card.normalizedPayloadHash();
        }
    }

    /**
     * The safe validation and preview of one inline Campaign message draft.
     *
     * <p>It carries the canonical channel code, the exact user-authored body, the hash of that exact body, the
     * canonical content hash of the channel/body pair and a stable, content-addressed component identity that
     * a later assembly step can use. The body is host-private: the public outcome publishes the content hash
     * and the identity only. Nothing here claims a Provider has persisted the draft or that it is sendable —
     * the artifact is authoring evidence, never a delivery or publication fact.
     */
    record MessageInlineSafePreview(
            String channel,
            String body,
            ContentHash bodyHash,
            ContentHash contentHash,
            String componentCode) implements CampaignToolArtifact {

        private static final Pattern CHANNEL = Pattern.compile(ContractPatterns.MACHINE_CODE);
        private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);
        private static final String COMPONENT_PREFIX = "msg-inline-";

        public MessageInlineSafePreview {
            if (channel == null || !CHANNEL.matcher(channel).matches()) {
                throw CampaignAssemblyException.invalid(
                        CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT,
                        "An inline safe preview requires its canonical channel code.");
            }
            if (body == null || body.isEmpty() || body.length() > CampaignMessageInputs.MAX_BODY_LENGTH) {
                throw CampaignAssemblyException.invalid(
                        CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT,
                        "An inline safe preview requires its exact bounded body.");
            }
            if (bodyHash == null || contentHash == null || componentCode == null
                    || !IDENTIFIER.matcher(componentCode).matches()) {
                throw CampaignAssemblyException.invalid(
                        CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT,
                        "An inline safe preview requires its bounded hashes and component identity.");
            }
            // A forged artifact cannot claim hashes or an identity that do not cover its own channel and body.
            if (!bodyHash.equals(canonicalJson().hash(body))
                    || !contentHash.equals(canonicalJson().hash(inlinePayload(channel, body)))
                    || !componentCode.equals(componentCode(contentHash))) {
                throw CampaignAssemblyException.invalid(
                        CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT,
                        "An inline safe preview must hash to its own channel and body.");
            }
        }

        /** Builds the one artifact of an exact channel and body, deriving its hashes and identity itself. */
        public static MessageInlineSafePreview of(String channel, String body) {
            ContentHash bodyHash = canonicalJson().hash(body);
            ContentHash contentHash = canonicalJson().hash(inlinePayload(channel, body));
            return new MessageInlineSafePreview(
                    channel, body, bodyHash, contentHash, componentCode(contentHash));
        }

        @Override
        public String toolId() {
            return CampaignToolIds.MESSAGE_INLINE_PREVIEW_SAFE;
        }

        @Override
        public ContentHash publicEvidenceHash() {
            return contentHash;
        }

        /** The canonical content the inline artifact hashes over: the channel code and the exact body. */
        private static Map<String, Object> inlinePayload(String channel, String body) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("channel", channel);
            payload.put("body", body);
            return payload;
        }

        /** The one stable identity of an inline draft: a content-addressed identifier of its exact content. */
        private static String componentCode(ContentHash contentHash) {
            return COMPONENT_PREFIX + contentHash.value().substring(CanonicalJson.HASH_PREFIX.length());
        }

        /** Redacted diagnostics: the channel, body length, hashes and identity, never the body text itself. */
        @Override
        public String toString() {
            return "MessageInlineSafePreview[channel=" + channel
                    + ", bodyLength=" + body.length()
                    + ", contentHash=" + contentHash
                    + ", componentCode=" + componentCode + ']';
        }
    }

    /** Bounded discovery over the immutable Reward catalog. */
    record RewardDiscovery(List<CampaignCatalogSummary> matches, ContentHash catalogHash)
            implements CampaignToolArtifact {

        public RewardDiscovery {
            matches = matches == null ? List.of() : List.copyOf(new ArrayList<>(matches));
            if (catalogHash == null) {
                throw CampaignAssemblyException.invalid(
                        CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT,
                        "A Reward discovery artifact requires the pinned catalog hash.");
            }
        }

        @Override
        public String toolId() {
            return CampaignToolIds.REWARD_DISCOVER;
        }

        @Override
        public ContentHash publicEvidenceHash() {
            return canonicalJson().hash(new DiscoveryMaterial(matches, catalogHash));
        }
    }

    /** The one exact enabled/runtime-compatible immutable Reward version. */
    record RewardResolution(RewardVersion version, ContentHash catalogHash) implements CampaignToolArtifact {

        public RewardResolution {
            if (version == null) {
                throw CampaignAssemblyException.invalid(
                        CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT,
                        "A Reward resolution artifact requires the exact resolved version.");
            }
        }

        @Override
        public String toolId() {
            return CampaignToolIds.REWARD_RESOLVE;
        }

        @Override
        public ContentHash publicEvidenceHash() {
            return version.contentHash();
        }
    }

    /** The deterministic bounded exposure of one resolved reward grant. */
    record RewardExposure(
            RewardVersion version,
            int quantity,
            BigDecimal perRecipientExposure,
            BigDecimal maximumExposure,
            CapabilityPayload normalized,
            ContentHash catalogHash) implements CampaignToolArtifact {

        public RewardExposure {
            if (version == null || normalized == null || perRecipientExposure == null || maximumExposure == null) {
                throw CampaignAssemblyException.invalid(
                        CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT,
                        "A Reward exposure artifact requires its resolved version and bounded exposure.");
            }
        }

        @Override
        public String toolId() {
            return CampaignToolIds.REWARD_EXPOSURE_CALCULATE;
        }

        @Override
        public ContentHash publicEvidenceHash() {
            return normalized.contentHash();
        }
    }

    /** The safe card plus the host-private normalized payload of one resolved reward version. */
    record RewardSafePreview(
            RewardVersion version,
            CapabilityPayload normalized,
            RewardSafePreviewCard card,
            ContentHash catalogHash) implements CampaignToolArtifact {

        public RewardSafePreview {
            if (version == null || normalized == null || card == null) {
                throw CampaignAssemblyException.invalid(
                        CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT,
                        "A Reward safe preview artifact requires its resolved version, payload and card.");
            }
        }

        @Override
        public String toolId() {
            return CampaignToolIds.REWARD_PREVIEW_SAFE;
        }

        @Override
        public ContentHash publicEvidenceHash() {
            return card.normalizedPayloadHash();
        }
    }

    /** The accepted normalized {@code schedule.basic} declaration of one admitted normalization call. */
    record ScheduleNormalization(String componentId, CapabilityPayload normalized, String formCode)
            implements CampaignToolArtifact {

        public ScheduleNormalization {
            if (componentId == null || normalized == null || formCode == null) {
                throw CampaignAssemblyException.invalid(
                        CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT,
                        "A Schedule normalization artifact requires its identity and normalized declaration.");
            }
        }

        @Override
        public String toolId() {
            return CampaignToolIds.SCHEDULE_NORMALIZE;
        }

        @Override
        public ContentHash publicEvidenceHash() {
            return declarationEvidence(componentId, normalized);
        }
    }

    /**
     * The accepted normalized {@code schedule.basic} declaration of one admitted validation call.
     *
     * <p>This is the one stageable Schedule result: it carries the exact normalized payload the accepted T19 pack
     * produced and no occurrence, execution key, published definition, work item or member fact.
     */
    record ScheduleValidation(String componentId, CapabilityPayload normalized)
            implements CampaignToolArtifact {

        public ScheduleValidation {
            if (componentId == null || normalized == null) {
                throw CampaignAssemblyException.invalid(
                        CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT,
                        "A Schedule validation artifact requires its identity and normalized declaration.");
            }
        }

        @Override
        public String toolId() {
            return CampaignToolIds.SCHEDULE_VALIDATE;
        }

        @Override
        public ContentHash publicEvidenceHash() {
            return declarationEvidence(componentId, normalized);
        }
    }

    /**
     * The deterministic authoring-only declared exposure of one admitted {@code governance.limit} declaration.
     *
     * <p>It is bounded arithmetic over explicit declaration values, never an inventory check, reservation,
     * balance or Provider fact.
     */
    record GovernanceExposure(
            String componentId,
            CapabilityPayload normalized,
            long maxRewardGrants,
            BigDecimal maxRewardFaceValue,
            BigDecimal declaredMaximumExposure) implements CampaignToolArtifact {

        public GovernanceExposure {
            if (componentId == null || normalized == null || maxRewardFaceValue == null
                    || declaredMaximumExposure == null) {
                throw CampaignAssemblyException.invalid(
                        CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT,
                        "A Governance exposure artifact requires its identity and bounded totals.");
            }
        }

        @Override
        public String toolId() {
            return CampaignToolIds.GOVERNANCE_EXPOSURE_CALCULATE;
        }

        @Override
        public ContentHash publicEvidenceHash() {
            Map<String, Object> material = new LinkedHashMap<>();
            material.put("componentId", componentId);
            material.put("declaration", normalized.contentHash());
            material.put("maxRewardGrants", maxRewardGrants);
            material.put("maxRewardFaceValue", maxRewardFaceValue);
            material.put("declaredMaximumExposure", declaredMaximumExposure);
            return canonicalJson().hash(material);
        }
    }

    /**
     * The accepted normalized {@code governance.limit} declaration of one admitted validation call.
     *
     * <p>This is the one stageable Governance result: it carries the exact normalized payload the accepted T19
     * pack produced and no reward secret, member identity or Provider fact.
     */
    record GovernanceValidation(String componentId, CapabilityPayload normalized)
            implements CampaignToolArtifact {

        public GovernanceValidation {
            if (componentId == null || normalized == null) {
                throw CampaignAssemblyException.invalid(
                        CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT,
                        "A Governance validation artifact requires its identity and normalized declaration.");
            }
        }

        @Override
        public String toolId() {
            return CampaignToolIds.GOVERNANCE_VALIDATE;
        }

        @Override
        public ContentHash publicEvidenceHash() {
            return declarationEvidence(componentId, normalized);
        }
    }

    // ---- Journey -----------------------------------------------------------------------------------

    /**
     * The closed Journey node capability catalog of one admitted catalog call.
     *
     * <p>It is discovery only: it carries the six accepted Flow node capability facts and nothing that could
     * stage a component.
     */
    record JourneyNodeCatalog(List<JourneyNodeCapability> capabilities) implements CampaignToolArtifact {

        public JourneyNodeCatalog {
            capabilities = capabilities == null ? List.of() : List.copyOf(new ArrayList<>(capabilities));
        }

        @Override
        public String toolId() {
            return CampaignToolIds.JOURNEY_NODE_CATALOG;
        }

        @Override
        public ContentHash publicEvidenceHash() {
            return canonicalJson().hash(new JourneyCatalogMaterial(capabilities));
        }
    }

    /**
     * The normalized Journey composition of one admitted compose call.
     *
     * <p>Composition proves nothing about the graph, so this is deliberately never stageable: it is the host
     * value a later validation call is built from.
     */
    record JourneyNodeComposition(JourneyComposition composition) implements CampaignToolArtifact {

        public JourneyNodeComposition {
            if (composition == null) {
                throw CampaignAssemblyException.invalid(
                        CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT,
                        "A Journey composition artifact requires its normalized composition.");
            }
        }

        @Override
        public String toolId() {
            return CampaignToolIds.JOURNEY_COMPOSE;
        }

        @Override
        public ContentHash publicEvidenceHash() {
            return composition.compositionHash();
        }
    }

    /**
     * The atomic Journey bundle of one admitted validation call: the only stageable Journey result.
     *
     * <p>It carries the normalized node components, the declared topology edges and the genuine T20 hashes the
     * compiled graph produced. Every fact is needed to emit the exact Flow operations and the one topology
     * mutation, and none of it is model-visible.
     */
    record JourneyValidation(JourneyValidationOutcome outcome) implements CampaignToolArtifact {

        public JourneyValidation {
            if (outcome == null || !outcome.valid()) {
                throw CampaignAssemblyException.invalid(
                        CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT,
                        "A Journey validation artifact requires a valid compiled outcome.");
            }
        }

        @Override
        public String toolId() {
            return CampaignToolIds.JOURNEY_VALIDATE;
        }

        @Override
        public ContentHash publicEvidenceHash() {
            Map<String, Object> material = new LinkedHashMap<>();
            material.put("journeyContentHash", outcome.journeyContentHash());
            material.put("topologyHash", outcome.topologyHash());
            material.put("nodeCount", outcome.nodeCount());
            material.put("edgeCount", outcome.edgeCount());
            return canonicalJson().hash(material);
        }
    }

    /** The bounded pure preview summary of one admitted simulation call; never stageable. */
    record JourneySimulation(JourneySimulationSummary summary) implements CampaignToolArtifact {

        public JourneySimulation {
            if (summary == null) {
                throw CampaignAssemblyException.invalid(
                        CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT,
                        "A Journey simulation artifact requires its bounded preview summary.");
            }
        }

        @Override
        public String toolId() {
            return CampaignToolIds.JOURNEY_SIMULATE;
        }

        @Override
        public ContentHash publicEvidenceHash() {
            return summary.simulationHash();
        }
    }

    /** The material a bounded discovery artifact hashes over: only safe summaries and the catalog pin. */
    record DiscoveryMaterial(List<CampaignCatalogSummary> matches, ContentHash catalogHash) {
    }

    /** The material a bounded Journey node catalog artifact hashes over: only the closed node facts. */
    record JourneyCatalogMaterial(List<JourneyNodeCapability> capabilities) {
    }

    /** The public evidence of one declaration artifact: its identity and the normalized payload hash only. */
    private static ContentHash declarationEvidence(String componentId, CapabilityPayload normalized) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("componentId", componentId);
        material.put("declaration", normalized.contentHash());
        return canonicalJson().hash(material);
    }
}