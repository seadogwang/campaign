package com.company.campaign.builder.capability.flow;

import com.company.campaign.builder.capability.CapabilityPlanPreviewCard;
import com.company.campaign.builder.capability.pack.CapabilityPackContract;
import com.company.campaign.builder.capability.pack.CapabilityPackMetadata;
import com.company.campaign.builder.capability.pack.CapabilityPayloadContract;
import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.builder.compiler.CapabilityRequest;
import com.company.campaign.builder.compiler.CapabilityResolution;
import com.company.campaign.builder.compiler.CompilerException;
import com.company.campaign.builder.compiler.CompositionSlot;
import com.company.campaign.builder.compiler.RuntimeCompatibility;
import com.company.campaign.contracts.Issue;

import java.util.List;
import java.util.Map;

/**
 * The one shared, package-private implementation of the T19 Flow node capability packs.
 *
 * <p>It owns exactly the machinery every Flow node pack shares and nothing capability-specific: the frozen
 * pack metadata, the closed-payload boundary (a field the contract does not declare fails closed before any
 * node semantics run), the deterministic fail-closed resolution shape, and the one generic safe preview card a
 * resolved node contributes.
 *
 * <p>Each concrete node supplies only its own closed payload contract, its own bounded machine-code summary
 * and risk projection, its own in-code evaluation fixture corpus and one pure normalization step. A node
 * therefore cannot reach a repository, a network, a provider, a model, a clock, a random source or a mutable
 * registry — none of those is representable here — and it validates its own configuration only. It never
 * reasons about edges, reachability, cycles, Start/Reward cardinality or any composition dependency: T20 owns
 * all of those.
 *
 * <p>Resolution is a pure function of the request: identical input bytes always normalize to identical
 * canonical payload bytes and therefore to identical payload hashes and identical cards.
 */
abstract class FlowPackSupport implements CapabilityPackContract {

    private static final String FAILURE_MESSAGE = "The flow node capability pack resolution failed closed.";

    private final CapabilityPackMetadata metadata;
    private final List<String> summaryCodes;
    private final List<String> riskCodes;
    private final List<FlowEvaluationFixture> evaluationFixtures;

    FlowPackSupport(
            String capabilityCode,
            CapabilityPayloadContract payloadContract,
            List<String> summaryCodes,
            List<String> riskCodes,
            List<FlowEvaluationFixture> evaluationFixtures) {
        this.summaryCodes = List.copyOf(summaryCodes);
        this.riskCodes = List.copyOf(riskCodes);
        this.evaluationFixtures = List.copyOf(evaluationFixtures);
        // The metadata is stamped with its own canonical content hash (including the evaluation asset it
        // pins), so the frozen facts of one pack are provably the ones this build declares.
        this.metadata = CapabilityPackMetadata.of(
                capabilityCode,
                FlowPackVersions.PACK_VERSION,
                FlowPackVersions.CONTRACT_VERSION,
                FlowPackVersions.SCHEMA_VERSION,
                CapabilityPlanPreviewCard.CAPABILITY_PREVIEW,
                CapabilityPlanPreviewCard.CARD_CONTRACT_VERSION,
                FlowPackVersions.SLOT,
                FlowPackVersions.RUNTIME_COMPATIBILITY,
                payloadContract,
                FlowEvaluationAssets.assetFor(capabilityCode, this.evaluationFixtures));
    }

    @Override
    public final String capabilityCode() {
        return metadata.capabilityCode();
    }

    @Override
    public final String contractVersion() {
        return metadata.contractVersion();
    }

    @Override
    public final CompositionSlot slot() {
        return metadata.slot();
    }

    @Override
    public final boolean enabled() {
        // Authoring and preview of every Flow node pack are enabled; production publication and runtime stay
        // honestly unavailable, which is what the frozen runtime compatibility declares.
        return true;
    }

    @Override
    public final RuntimeCompatibility runtimeCompatibility() {
        return metadata.runtimeCompatibility();
    }

    @Override
    public final CapabilityPackMetadata packMetadata() {
        return metadata;
    }

    /** The closed payload contract this node declares, as frozen into its pack metadata. */
    final CapabilityPayloadContract payloadContract() {
        return metadata.payloadContract();
    }

    /** The ordered in-code evaluation fixture corpus this node pins and can be driven by. */
    final List<FlowEvaluationFixture> evaluationFixtures() {
        return evaluationFixtures;
    }

    /**
     * Validates and normalizes one payload into the one canonical component value of this node.
     *
     * <p>The base has already proven the payload carries only fields this node's contract declares, so an
     * implementation reads its own declared fields, rejects every value outside its closed node semantics, and
     * returns the deterministic canonical form. Identical inputs must produce identical bytes, and
     * re-normalizing an already normalized payload must be a fixed point.
     */
    abstract CapabilityPayload normalizeFields(Map<String, Object> fields);

    /**
     * Substitutes the compiler-owned final identity for one same-proposal reference this node declares.
     *
     * <p>The base performs no substitution at all: a node that resolves no reference to a component created by
     * the same proposal returns the fields it was given, so nothing about an ordinary payload can change here.
     * Only the exact Flow node contracts that declare an exact reference field override this, and they ask the
     * compiler-owned view for that field's exact expected capability — a generic payload string is therefore
     * never rewritten merely because it happens to match a declared {@code clientRef}.
     */
    Map<String, Object> bindReferences(Map<String, Object> fields, CapabilityRequest request) {
        return fields;
    }

    @Override
    public final CapabilityResolution resolve(CapabilityRequest request) {
        if (request == null) {
            return failed(FlowPackErrorCodes.INPUT_MISSING);
        }
        try {
            Map<String, Object> fields = request.payload().fields();
            requireDeclaredFields(fields);
            CapabilityPayload normalized = normalizeFields(bindReferences(fields, request));
            return new CapabilityResolution.Resolved(
                    normalized, List.of(), List.of(), List.of(card(request.logicalId(), normalized)));
        } catch (FlowPackException rejected) {
            // Every expected rejection is one ordered, bounded issue; the rejected value is never echoed.
            return failed(rejected.code());
        } catch (CompilerException rejected) {
            // The node normalized a payload but could not project the bound safe card for it: the pack failed
            // its own boundary and must not hand back a partially trustworthy resolution.
            return failed(FlowPackErrorCodes.PACK_BOUNDARY_INVALID);
        } catch (RuntimeException rejected) {
            return failed(FlowPackErrorCodes.VALUE_INVALID);
        }
    }

    /**
     * Reads one declared field as the scalar text this contract declares.
     *
     * <p>An absent field, an explicit null and a non-text value are three distinct bounded rejections, so a
     * missing field can never be mistaken for a present-but-wrong one.
     */
    final String requireText(Map<String, Object> fields, String fieldName) {
        if (!fields.containsKey(fieldName)) {
            throw new FlowPackException(FlowPackErrorCodes.MISSING_FIELD);
        }
        Object value = fields.get(fieldName);
        if (!(value instanceof String text)) {
            throw new FlowPackException(FlowPackErrorCodes.TYPE_INVALID);
        }
        return text;
    }

    /** Refuses any field the closed payload contract of this node does not declare. */
    private void requireDeclaredFields(Map<String, Object> fields) {
        CapabilityPayloadContract contract = metadata.payloadContract();
        for (String fieldName : fields.keySet()) {
            if (!contract.declares(fieldName)) {
                throw new FlowPackException(FlowPackErrorCodes.UNKNOWN_FIELD);
            }
        }
    }

    /**
     * The one generic safe preview card a resolved node contributes.
     *
     * <p>It binds the closed card kind and contract version, the resolved component's stable logical identity,
     * the capability code and exact contract version, the hash of the normalized payload, the frozen runtime
     * compatibility, the bounded machine-code summary and risk projection of this node and the hash of the
     * evaluation asset this pack pins. This node resolves no frozen catalog reference, so the optional
     * resolved-catalog-reference hash is genuinely absent rather than the hash of an empty list.
     *
     * <p>It carries no name, message body, reward description, raw variable, credential, provider output,
     * member identifier, statement text, unrestricted exception text or trusted security context.
     */
    private CapabilityPlanPreviewCard card(String logicalId, CapabilityPayload normalized) {
        return new CapabilityPlanPreviewCard(
                CapabilityPlanPreviewCard.CAPABILITY_PREVIEW,
                CapabilityPlanPreviewCard.CARD_CONTRACT_VERSION,
                logicalId,
                capabilityCode(),
                contractVersion(),
                normalized.contentHash(),
                null,
                metadata.runtimeCompatibility(),
                summaryCodes,
                riskCodes,
                metadata.evaluationAsset().contentHash());
    }

    private static CapabilityResolution failed(String code) {
        return new CapabilityResolution.Failed(List.of(Issue.error(code, FAILURE_MESSAGE)));
    }

    /** A bounded diagnostic: this pack holds no value that could leak, so it prints only its identity. */
    @Override
    public final String toString() {
        return "FlowNodePack[capabilityCode=" + capabilityCode()
                + ", contractVersion=" + contractVersion()
                + ", slot=" + slot().code() + ']';
    }
}