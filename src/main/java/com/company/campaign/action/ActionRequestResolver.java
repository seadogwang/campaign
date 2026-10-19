package com.company.campaign.action;

import com.company.campaign.builder.capability.catalog.MessageTemplateCatalog;
import com.company.campaign.builder.capability.catalog.RewardCatalog;
import com.company.campaign.builder.capability.flow.FlowMessagePack;
import com.company.campaign.builder.capability.flow.FlowPackVersions;
import com.company.campaign.builder.capability.flow.FlowRewardPack;
import com.company.campaign.builder.capability.message.MessageTemplatePack;
import com.company.campaign.builder.capability.promotion.PromotionReferencePack;
import com.company.campaign.builder.compiler.CapabilityPreviewCards;
import com.company.campaign.builder.compiler.ResolvedCampaignManifest;
import com.company.campaign.builder.compiler.ResolvedCatalogReference;
import com.company.campaign.builder.compiler.ResolvedComponent;
import com.company.campaign.fullpublish.FullPublishedDefinition;
import com.company.campaign.journey.compiler.JourneyManifest;
import com.company.campaign.journey.graph.JourneyNode;
import com.company.campaign.lifecycle.PublishedCampaignDefinition;
import com.company.campaign.runtime.ActionHandoff;
import com.company.campaign.runtime.CampaignExecution;
import com.company.campaign.runtime.JourneyParticipant;
import com.company.campaign.shared.canonical.ContentHash;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves one exact persisted handoff to one immutable action request, re-proving every frozen pin on the way.
 *
 * <p>Nothing here resolves a mutable "latest" pointer, a Draft, a Plan, a Proposal, a Prompt, a Skill, a Tool or
 * an LLM. Every fact comes from the exact frozen full publication, the exact frozen base publication and the
 * exact frozen {@link JourneyManifest} the Execution pinned, and each of those is re-proved against the
 * Execution's own recorded hashes before a single field is trusted.
 *
 * <p>The node's stable logical identity — never an array position — selects the frozen component, and that
 * component must witness the node's exact capability code, contract version and payload hash. A MESSAGE node's
 * referenced message template and a REWARD node's referenced promotion must both exist, must be at their exact
 * contract version, and must carry the resolved catalog reference their own normalized payload pins, so the
 * frozen catalog identity is re-proved against the frozen Manifest rather than re-read from a catalog. A REWARD
 * node additionally requires the one exact {@code governance.limit/1.0} declaration, because a reward that is
 * not covered by a frozen budget declaration is never submitable.
 *
 * <p>The recipient resolver is called here, once, with no store lock held, and its answer is frozen into the
 * request: a later retry, reclaim or restart reuses the persisted evidence rather than resolving again.
 */
public final class ActionRequestResolver {

    private final ActionRuntimePort runtime;
    private final ActionRecipientResolverPort recipients;

    public ActionRequestResolver(ActionRuntimePort runtime, ActionRecipientResolverPort recipients) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.recipients = Objects.requireNonNull(recipients, "recipients");
    }

    /**
     * Resolves one exact persisted handoff to its immutable request, including the host-resolved recipient.
     *
     * <p>The recipient resolver is called here, once, and its answer is frozen into the request, so a later
     * retry, reclaim or restart reuses the persisted evidence rather than resolving again.
     */
    public ActionMaterial resolve(ActionHandoff handoff) {
        return withRecipient(resolveFrozen(handoff));
    }

    /**
     * Resolves one exact persisted handoff to its immutable request <em>without</em> asking the host for a
     * recipient.
     *
     * <p>It exists so a duplicate preparation can re-prove the frozen material and decide the key without
     * resolving a recipient and without ever reaching a Provider, which is exactly what makes a duplicate
     * business key free and side-effect-free.
     */
    public ActionMaterial resolveFrozen(ActionHandoff handoff) {
        return doResolve(handoff, null);
    }

    /** The same resolved material with its host-resolved recipient target frozen into the request. */
    public ActionMaterial withRecipient(ActionMaterial material) {
        Objects.requireNonNull(material, "material");
        ActionRecipientTarget target = requireRecipient(
                material.handoff(), material.request().actionKind(), material.request().channelCode());
        return new ActionMaterial(
                material.handoff(), material.execution(), material.participant(), material.definition(),
                material.base(), material.request().withRecipient(target), material.budgetLimits());
    }

    private ActionMaterial doResolve(ActionHandoff handoff, ActionRecipientTarget recipient) {
        Objects.requireNonNull(handoff, "handoff");
        ActionKind kind = ActionKind.from(handoff.actionKind());
        CampaignExecution execution = runtime
                .findExecutionById(handoff.tenantId(), handoff.executionId())
                .orElseThrow(() -> ActionSupport.invalid(
                        ActionErrorCodes.ACTION_HANDOFF_NOT_FOUND, "execution"));
        JourneyParticipant participant = requireParticipant(handoff, execution);
        if (!participant.currentNodeLogicalId().equals(handoff.nodeLogicalId())) {
            // The Participant is no longer at the frozen node this handoff names, so the handoff is spent.
            throw ActionSupport.invalid(
                    ActionErrorCodes.ACTION_HANDOFF_BINDING_MISMATCH, "participantNode");
        }
        FullPublishedDefinition definition = requireDefinition(execution);
        PublishedCampaignDefinition base = requireBase(definition);
        ResolvedCampaignManifest manifest = base.manifest();
        if (!manifest.manifestHash().equals(definition.manifestHash())) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_DEFINITION_HASH_MISMATCH, "manifestHash");
        }
        JourneyManifest journey = definition.journey();
        if (!journey.contentHash().equals(execution.journeyHash())
                || !ActionVersions.RUNTIME_SCHEMA_VERSION.equals(journey.runtimeSchemaVersion())) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_DEFINITION_HASH_MISMATCH, "journeyBinding");
        }
        JourneyNode node = journey.node(handoff.nodeLogicalId());
        if (node == null || node.kind() != handoff.actionKind()
                || !node.payloadHash().equals(handoff.nodePayloadHash())
                || !kind.capabilityCode().equals(node.capabilityCode())
                || !FlowPackVersions.CONTRACT_VERSION.equals(node.contractVersion())) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_COMPONENT_BINDING_MISMATCH, "node");
        }
        ResolvedComponent component = requireComponent(manifest, node);
        String idempotencyKey = ActionExecution.idempotencyKey(
                handoff.tenantId(), handoff.participantId(), handoff.nodeLogicalId(), kind);
        ActionRequest request = kind == ActionKind.MESSAGE
                ? messageRequest(handoff, node, component, manifest, idempotencyKey, recipient)
                : rewardRequest(handoff, node, component, manifest, idempotencyKey, recipient);
        RewardBudgetLimits limits = kind == ActionKind.REWARD ? rewardLimits(manifest) : null;
        return new ActionMaterial(handoff, execution, participant, definition, base, request, limits);
    }

    // ------------------------------------------------------------------ frozen pins

    private JourneyParticipant requireParticipant(ActionHandoff handoff, CampaignExecution execution) {
        for (JourneyParticipant participant : runtime.findParticipants(handoff.tenantId(), execution.executionId())) {
            if (participant.participantId().equals(handoff.participantId())) {
                return participant;
            }
        }
        throw ActionSupport.invalid(ActionErrorCodes.ACTION_HANDOFF_BINDING_MISMATCH, "participant");
    }

    /**
     * The exact frozen full publication the Execution pinned, re-proved against every hash it recorded.
     *
     * <p>A definition that does not witness the Execution's own full-definition hash, owner, Campaign, Journey
     * hash and Schedule hash is refused rather than partially trusted.
     */
    public FullPublishedDefinition requireDefinition(CampaignExecution execution) {
        FullPublishedDefinition definition = runtime
                .findFullDefinition(execution.fullDefinitionId())
                .orElseThrow(() -> ActionSupport.invalid(
                        ActionErrorCodes.ACTION_DEFINITION_NOT_FOUND, "fullDefinitionId"));
        if (!definition.contentHash().equals(execution.fullDefinitionHash())
                || !definition.tenantId().equals(execution.tenantId())
                || !definition.campaignId().equals(execution.campaignId())
                || !definition.journeyHash().equals(execution.journeyHash())
                || !definition.scheduleHash().equals(execution.scheduleHash())) {
            throw ActionSupport.invalid(
                    ActionErrorCodes.ACTION_DEFINITION_HASH_MISMATCH, "fullDefinitionBinding");
        }
        return definition;
    }

    public PublishedCampaignDefinition requireBase(FullPublishedDefinition definition) {
        PublishedCampaignDefinition base = runtime
                .findBaseDefinition(definition.basePublishedId())
                .orElseThrow(() -> ActionSupport.invalid(
                        ActionErrorCodes.ACTION_DEFINITION_NOT_FOUND, "basePublishedId"));
        if (!base.contentChecksum().equals(definition.baseDefinitionChecksum())
                || !base.identity().equals(definition.baseIdentity())
                || !base.tenantId().equals(definition.tenantId())
                || !base.campaignId().equals(definition.campaignId())) {
            throw ActionSupport.invalid(
                    ActionErrorCodes.ACTION_DEFINITION_HASH_MISMATCH, "baseDefinitionBinding");
        }
        return base;
    }

    /**
     * The one frozen component matching the node's stable logical identity, with every pin re-proved.
     *
     * <p>It never selects by array position and never falls back to a similarly named component.
     */
    private static ResolvedComponent requireComponent(ResolvedCampaignManifest manifest, JourneyNode node) {
        ResolvedComponent component = manifest.component(node.logicalId());
        if (component == null
                || !component.capabilityCode().equals(node.capabilityCode())
                || !component.contractVersion().equals(node.contractVersion())
                || !component.value().contentHash().equals(node.payloadHash())) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_COMPONENT_BINDING_MISMATCH, "component");
        }
        return component;
    }

    // ------------------------------------------------------------------ message request

    private ActionRequest messageRequest(
            ActionHandoff handoff,
            JourneyNode node,
            ResolvedComponent component,
            ResolvedCampaignManifest manifest,
            String idempotencyKey,
            ActionRecipientTarget recipient) {
        String templateLogicalId = text(component, FlowMessagePack.REFERENCE_FIELD);
        ResolvedComponent template = requireCatalogComponent(
                manifest, templateLogicalId, MessageTemplatePack.CAPABILITY_CODE,
                MessageTemplatePack.CONTRACT_VERSION, "messageTemplate");
        String channel = text(template, "channel");
        String pinnedLogicalId = text(template, "templateLogicalId");
        String pinnedVersionId = text(template, "templateVersionId");
        ContentHash pinnedHash = hash(template, "templateContentHash");
        requireCatalogReference(template, MessageTemplateCatalog.ENTITY_KIND, pinnedLogicalId, pinnedVersionId);
        if (!Objects.equals(pinnedLogicalId, templateLogicalId) || pinnedVersionId == null) {
            throw ActionSupport.invalid(
                    ActionErrorCodes.ACTION_CATALOG_REFERENCE_MISMATCH, "messageTemplatePin");
        }
        return ActionRequest.of(
                ActionKind.MESSAGE, idempotencyKey, handoff.tenantId(), handoff.executionId(),
                handoff.participantId(), handoff.nodeLogicalId(), component.logicalId(),
                component.capabilityCode(), component.contractVersion(), component.value().contentHash(),
                CapabilityPreviewCards.canonicalReferenceHash(template.references()),
                channel, pinnedLogicalId, pinnedVersionId, pinnedHash,
                null, null, null, null, null, null,
                recipientRef(recipient), evidence(recipient), routingPin(component));
    }

    // ------------------------------------------------------------------ reward request

    private ActionRequest rewardRequest(
            ActionHandoff handoff,
            JourneyNode node,
            ResolvedComponent component,
            ResolvedCampaignManifest manifest,
            String idempotencyKey,
            ActionRecipientTarget recipient) {
        String promotionLogicalId = text(component, FlowRewardPack.REFERENCE_FIELD);
        ResolvedComponent promotion = requireCatalogComponent(
                manifest, promotionLogicalId, PromotionReferencePack.CAPABILITY_CODE,
                PromotionReferencePack.CONTRACT_VERSION, "promotion");
        String rewardLogicalId = text(promotion, "rewardLogicalId");
        String rewardVersionId = text(promotion, "rewardVersionId");
        ContentHash rewardHash = hash(promotion, "rewardContentHash");
        String currency = text(promotion, "currency");
        int quantity = wholeNumber(promotion, "quantity", "rewardQuantity");
        BigDecimal unit = decimal(promotion, "faceValue", "rewardFaceValue");
        if (rewardLogicalId == null || rewardVersionId == null || rewardHash == null) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_CATALOG_REFERENCE_MISSING, "rewardPin");
        }
        requireCatalogReference(promotion, RewardCatalog.ENTITY_KIND, rewardLogicalId, rewardVersionId);
        BigDecimal total = unit.multiply(BigDecimal.valueOf(quantity));
        if (total.stripTrailingZeros().precision() > ActionVersions.MAX_AMOUNT_PRECISION) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_BUDGET_INVALID, "rewardFaceValue");
        }
        return ActionRequest.of(
                ActionKind.REWARD, idempotencyKey, handoff.tenantId(), handoff.executionId(),
                handoff.participantId(), handoff.nodeLogicalId(), component.logicalId(),
                component.capabilityCode(), component.contractVersion(), component.value().contentHash(),
                CapabilityPreviewCards.canonicalReferenceHash(promotion.references()),
                null, null, null, null,
                rewardLogicalId, rewardVersionId, rewardHash, quantity, currency, total,
                recipientRef(recipient), evidence(recipient), routingPin(component));
    }

    /**
     * The one exact frozen {@code governance.limit/1.0} declaration of this Execution's base Manifest.
     *
     * <p>A Manifest that declares none, or declares one that is not the exact bounded shape, refuses the
     * reward: a reward with no frozen budget declaration can never be submitted.
     */
    private static RewardBudgetLimits rewardLimits(ResolvedCampaignManifest manifest) {
        ResolvedComponent limits = null;
        for (ResolvedComponent candidate : manifest.components()) {
            if (ActionVersions.GOVERNANCE_LIMIT_CAPABILITY.equals(candidate.capabilityCode())) {
                if (limits != null) {
                    throw ActionSupport.invalid(
                            ActionErrorCodes.ACTION_GOVERNANCE_LIMIT_INVALID, "governanceLimit");
                }
                limits = candidate;
            }
        }
        if (limits == null) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_GOVERNANCE_LIMIT_MISSING, "governanceLimit");
        }
        if (!ActionVersions.GOVERNANCE_LIMIT_CONTRACT_VERSION.equals(limits.contractVersion())) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_GOVERNANCE_LIMIT_INVALID, "governanceLimit");
        }
        int grants = wholeNumber(limits, ActionVersions.LIMIT_MAX_REWARD_GRANTS, "maxRewardGrants");
        BigDecimal faceValue = decimal(limits, ActionVersions.LIMIT_MAX_REWARD_FACE_VALUE, "maxRewardFaceValue");
        String currency = text(limits, ActionVersions.LIMIT_CURRENCY);
        return RewardBudgetLimits.of(grants, faceValue, currency);
    }

    // ------------------------------------------------------------------ shared material

    private ActionRecipientTarget requireRecipient(
            ActionHandoff handoff, ActionKind kind, String channelCode) {
        Optional<ActionRecipientTarget> resolved;
        try {
            resolved = recipients.resolve(
                    handoff.tenantId(), handoff.participantId(), handoff.nodeLogicalId(), kind, channelCode);
        } catch (RuntimeException hostFailure) {
            // A host that cannot answer is a bounded fail-closed outcome, never an unrestricted failure.
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_RECIPIENT_UNRESOLVED, "recipient");
        }
        if (resolved == null || resolved.isEmpty()) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_RECIPIENT_UNRESOLVED, "recipient");
        }
        return resolved.get();
    }

    private static String recipientRef(ActionRecipientTarget target) {
        return target == null ? null : target.recipientRef();
    }

    private static ContentHash evidence(ActionRecipientTarget target) {
        return target == null ? null : target.evidenceHash();
    }

    private static ResolvedComponent requireCatalogComponent(
            ResolvedCampaignManifest manifest,
            String logicalId,
            String capabilityCode,
            String contractVersion,
            String field) {
        if (logicalId == null) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_COMPONENT_MISSING, field);
        }
        ResolvedComponent referenced = manifest.component(logicalId);
        if (referenced == null
                || !capabilityCode.equals(referenced.capabilityCode())
                || !contractVersion.equals(referenced.contractVersion())) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_COMPONENT_BINDING_MISMATCH, field);
        }
        return referenced;
    }

    private static void requireCatalogReference(
            ResolvedComponent component, String entityKind, String logicalId, String versionId) {
        if (component.references().isEmpty()) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_CATALOG_REFERENCE_MISSING, "catalogReference");
        }
        for (ResolvedCatalogReference reference : component.references()) {
            if (reference.entityKind().equals(entityKind)
                    && reference.logicalId().equals(logicalId)
                    && reference.versionId().equals(versionId)) {
                return;
            }
        }
        throw ActionSupport.invalid(ActionErrorCodes.ACTION_CATALOG_REFERENCE_MISMATCH, "catalogReference");
    }

    private static String routingPin(ResolvedComponent component) {
        return "route-" + component.logicalId();
    }

    private static String text(ResolvedComponent component, String field) {
        Object value = component.value().fields().get(field);
        return value instanceof String string && ActionSupport.IDENTIFIER.matcher(string).matches()
                ? string
                : null;
    }

    private static ContentHash hash(ResolvedComponent component, String field) {
        Object value = component.value().fields().get(field);
        if (!(value instanceof String string)) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_CATALOG_REFERENCE_MISSING, field);
        }
        try {
            return new ContentHash(string);
        } catch (RuntimeException invalid) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_CATALOG_REFERENCE_MISMATCH, field);
        }
    }

    private static int wholeNumber(ResolvedComponent component, String field, String invariant) {
        Object value = component.value().fields().get(field);
        if (value instanceof BigInteger integer) {
            if (integer.signum() < 1 || integer.bitLength() > 31) {
                throw ActionSupport.invalid(ActionErrorCodes.ACTION_BUDGET_INVALID, invariant);
            }
            return integer.intValueExact();
        }
        if (value instanceof Number number) {
            long raw = number.longValue();
            if (raw < 1 || raw > Integer.MAX_VALUE || number.doubleValue() != (double) raw) {
                throw ActionSupport.invalid(ActionErrorCodes.ACTION_BUDGET_INVALID, invariant);
            }
            return (int) raw;
        }
        throw ActionSupport.invalid(ActionErrorCodes.ACTION_BUDGET_INVALID, invariant);
    }

    private static BigDecimal decimal(ResolvedComponent component, String field, String invariant) {
        Object value = component.value().fields().get(field);
        BigDecimal amount;
        if (value instanceof BigDecimal bigDecimal) {
            amount = bigDecimal;
        } else if (value instanceof BigInteger integer) {
            amount = new BigDecimal(integer);
        } else if (value instanceof Number number) {
            amount = new BigDecimal(number.toString());
        } else if (value instanceof String string) {
            try {
                amount = new BigDecimal(string);
            } catch (NumberFormatException malformed) {
                throw ActionSupport.invalid(ActionErrorCodes.ACTION_BUDGET_INVALID, invariant);
            }
        } else {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_BUDGET_INVALID, invariant);
        }
        return ActionSupport.requireAmount(amount, invariant);
    }

    /** Redacted diagnostics: the resolver holds no owner value. */
    @Override
    public String toString() {
        return "ActionRequestResolver[runtime=" + runtime.getClass().getSimpleName() + ']';
    }
}