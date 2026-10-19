package com.company.campaign.builder.assembly;

import com.company.campaign.builder.capability.catalog.MessageTemplateCatalog;
import com.company.campaign.builder.capability.composition.T19PackIndex;
import com.company.campaign.builder.capability.catalog.MessageTemplateVersion;
import com.company.campaign.builder.capability.catalog.RewardCatalog;
import com.company.campaign.builder.capability.catalog.RewardVersion;
import com.company.campaign.builder.capability.governance.GovernanceLimitPack;
import com.company.campaign.builder.capability.governance.GovernanceLimitVersions;
import com.company.campaign.builder.capability.message.MessageTemplateErrorCodes;
import com.company.campaign.builder.capability.message.MessageTemplatePack;
import com.company.campaign.builder.capability.promotion.PromotionReferencePack;
import com.company.campaign.builder.capability.promotion.RewardReferenceErrorCodes;
import com.company.campaign.builder.capability.schedule.ScheduleBasicForm;
import com.company.campaign.builder.capability.schedule.ScheduleBasicPack;
import com.company.campaign.builder.capability.schedule.ScheduleBasicVersions;
import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.builder.compiler.CapabilityRequest;
import com.company.campaign.builder.compiler.CapabilityResolution;
import com.company.campaign.builder.compiler.FrozenAuthoringPolicy;
import com.company.campaign.builder.compiler.FrozenCapabilityCatalog;
import com.company.campaign.builder.journey.JourneyAuthoringException;
import com.company.campaign.builder.journey.JourneyComposition;
import com.company.campaign.builder.journey.JourneyGraphAdapter;
import com.company.campaign.builder.journey.JourneyGraphDraft;
import com.company.campaign.builder.journey.JourneyNodeCapability;
import com.company.campaign.builder.journey.JourneySimulationSummary;
import com.company.campaign.builder.journey.JourneyValidationOutcome;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.contracts.Issue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The one accepted implementation of every available Campaign Tool.
 *
 * <p>Each handler drives the accepted immutable catalog and the accepted capability pack: discovery reads the
 * frozen catalog directly, resolution is an exact reference-code lookup, and validation, exposure and preview
 * run through the genuine {@code message.template} / {@code promotion.reference} / {@code schedule.basic} /
 * {@code governance.limit} pack. The inline Message preview handler is the one exception: its typed request
 * already carries the exact canonical channel and bounded body, so it drives no catalog or pack and reads no
 * host evidence at all. No business rule is copied into this orchestration layer, and nothing here
 * reads a clock, a random source, a global registry, a Provider, a repository or a database.
 *
 * <p>The Schedule and Governance handlers add no occurrence, execution key, published definition, work item,
 * inventory fact, reservation or member fact: they publish only bounded codes, hashes, normalized-form facts
 * and the arithmetic totals the accepted declaration itself implies.
 *
 * <p>Every handler fails closed with a bounded code when its host evidence is missing, and never echoes a
 * rejected reference, variable, amount, declaration or thrown message.
 */
public final class StandardCampaignToolHandlers {

    /**
     * The one host-owned pure Journey authoring adapter, which is where the T19 Flow packs and the T20 graph
     * compiler are really driven. It is stateless, so one shared instance is enough.
     */
    private static final JourneyGraphAdapter JOURNEY = new JourneyGraphAdapter();

    private StandardCampaignToolHandlers() {
    }

    /** The complete handler registry of this build, keyed by Tool identifier. */
    public static Map<String, CampaignToolHandler> all() {
        Map<String, CampaignToolHandler> handlers = new LinkedHashMap<>();
        handlers.put(CampaignToolIds.MESSAGE_TEMPLATE_DISCOVER, StandardCampaignToolHandlers::messageDiscover);
        handlers.put(CampaignToolIds.MESSAGE_TEMPLATE_RESOLVE, StandardCampaignToolHandlers::messageResolve);
        handlers.put(CampaignToolIds.MESSAGE_VARIABLES_VALIDATE, StandardCampaignToolHandlers::messageValidate);
        handlers.put(CampaignToolIds.MESSAGE_PREVIEW_SAFE, StandardCampaignToolHandlers::messagePreview);
        handlers.put(CampaignToolIds.MESSAGE_INLINE_PREVIEW_SAFE,
                StandardCampaignToolHandlers::messageInlinePreview);
        handlers.put(CampaignToolIds.REWARD_DISCOVER, StandardCampaignToolHandlers::rewardDiscover);
        handlers.put(CampaignToolIds.REWARD_RESOLVE, StandardCampaignToolHandlers::rewardResolve);
        handlers.put(CampaignToolIds.REWARD_EXPOSURE_CALCULATE, StandardCampaignToolHandlers::rewardExposure);
        handlers.put(CampaignToolIds.REWARD_PREVIEW_SAFE, StandardCampaignToolHandlers::rewardPreview);
        handlers.put(CampaignToolIds.SCHEDULE_NORMALIZE, StandardCampaignToolHandlers::scheduleNormalize);
        handlers.put(CampaignToolIds.SCHEDULE_VALIDATE, StandardCampaignToolHandlers::scheduleValidate);
        handlers.put(CampaignToolIds.GOVERNANCE_EXPOSURE_CALCULATE,
                StandardCampaignToolHandlers::governanceExposure);
        handlers.put(CampaignToolIds.GOVERNANCE_VALIDATE, StandardCampaignToolHandlers::governanceValidate);
        handlers.put(CampaignToolIds.JOURNEY_NODE_CATALOG, StandardCampaignToolHandlers::journeyNodeCatalog);
        handlers.put(CampaignToolIds.JOURNEY_COMPOSE, StandardCampaignToolHandlers::journeyCompose);
        handlers.put(CampaignToolIds.JOURNEY_VALIDATE, StandardCampaignToolHandlers::journeyValidate);
        handlers.put(CampaignToolIds.JOURNEY_SIMULATE, StandardCampaignToolHandlers::journeySimulate);
        return Map.copyOf(handlers);
    }

    // ------------------------------------------------------------------ Message

    private static CampaignToolExecution messageDiscover(CampaignToolRunContext run, CampaignToolRequest request) {
        MessageTemplateCatalog catalog = run.messageCatalog();
        if (catalog == null) {
            return evidenceMissing();
        }
        CampaignToolRequest.MessageDiscover typed = (CampaignToolRequest.MessageDiscover) request;
        List<CampaignCatalogSummary> matches = new ArrayList<>();
        for (MessageTemplateVersion version : catalog.versions()) {
            if (matches.size() >= typed.limit()) {
                break;
            }
            if (typed.query() != null && !version.referenceCode().contains(typed.query())) {
                continue;
            }
            matches.add(new CampaignCatalogSummary(
                    version.referenceCode(), version.logicalId(), version.versionId(), version.contentHash(),
                    version.channel(), version.enabled(), version.runtimeAvailable()));
        }
        CampaignToolArtifact artifact = new CampaignToolArtifact.MessageDiscovery(matches, catalog.catalogHash());
        return resolved(CampaignToolIds.MESSAGE_TEMPLATE_DISCOVER, "MESSAGE_TEMPLATES_DISCOVERED",
                matches.size(), artifact, referencesOf(matches));
    }

    private static CampaignToolExecution messageResolve(CampaignToolRunContext run, CampaignToolRequest request) {
        MessageTemplateCatalog catalog = run.messageCatalog();
        if (catalog == null) {
            return evidenceMissing();
        }
        CampaignToolRequest.MessageResolve typed = (CampaignToolRequest.MessageResolve) request;
        Optional<MessageTemplateVersion> found = catalog.resolve(typed.referenceCode());
        if (found.isEmpty()) {
            return fail(Disposition.INVALID, CampaignAssemblyErrorCodes.TOOL_RESOURCE_UNAVAILABLE);
        }
        MessageTemplateVersion version = found.get();
        if (!version.enabled() || !version.runtimeAvailable()) {
            return fail(Disposition.INVALID, CampaignAssemblyErrorCodes.TOOL_RESOURCE_UNAVAILABLE);
        }
        CampaignToolArtifact artifact = new CampaignToolArtifact.MessageResolution(version, catalog.catalogHash());
        return resolved(CampaignToolIds.MESSAGE_TEMPLATE_RESOLVE, "MESSAGE_TEMPLATE_RESOLVED",
                1, artifact, List.of(version.referenceCode()));
    }

    private static CampaignToolExecution messageValidate(CampaignToolRunContext run, CampaignToolRequest request) {
        MessageTemplateCatalog catalog = run.messageCatalog();
        CampaignToolRequest.MessageVariablesValidate typed = (CampaignToolRequest.MessageVariablesValidate) request;
        Optional<MessageTemplateVersion> found = catalog == null
                ? Optional.empty()
                : catalog.resolve(typed.referenceCode());
        if (found.isEmpty()) {
            return catalog == null
                    ? evidenceMissing()
                    : fail(Disposition.INVALID, CampaignAssemblyErrorCodes.TOOL_RESOURCE_UNAVAILABLE);
        }
        MessageTemplateVersion version = found.get();
        CapabilityResolution resolution = driveMessagePack(run, typed.referenceCode(), typed.variables());
        if (resolution == null) {
            return evidenceMissing();
        }
        if (resolution instanceof CapabilityResolution.Failed failed) {
            return fail(Disposition.INVALID, mapMessageFailure(codeOf(failed)));
        }
        CampaignToolArtifact artifact =
                new CampaignToolArtifact.MessageValidation(version, typed.variables(), catalog.catalogHash());
        return resolved(CampaignToolIds.MESSAGE_VARIABLES_VALIDATE, "MESSAGE_VARIABLES_VALIDATED",
                version.variables().size(), artifact, List.of(version.referenceCode()));
    }

    private static CampaignToolExecution messagePreview(CampaignToolRunContext run, CampaignToolRequest request) {
        MessageTemplateCatalog catalog = run.messageCatalog();
        CampaignToolRequest.MessagePreviewSafe typed = (CampaignToolRequest.MessagePreviewSafe) request;
        Optional<MessageTemplateVersion> found = catalog == null
                ? Optional.empty()
                : catalog.resolve(typed.referenceCode());
        if (found.isEmpty()) {
            return catalog == null
                    ? evidenceMissing()
                    : fail(Disposition.INVALID, CampaignAssemblyErrorCodes.TOOL_RESOURCE_UNAVAILABLE);
        }
        MessageTemplateVersion version = found.get();
        CapabilityResolution resolution = driveMessagePack(run, typed.referenceCode(), typed.variables());
        if (resolution == null) {
            return evidenceMissing();
        }
        if (resolution instanceof CapabilityResolution.Failed failed) {
            return fail(Disposition.INVALID, mapMessageFailure(codeOf(failed)));
        }
        CapabilityPayload normalized = ((CapabilityResolution.Resolved) resolution).value();
        MessageSafePreviewCard card = new MessageSafePreviewCard(
                version.referenceCode(), version.logicalId(), version.versionId(), version.contentHash(),
                version.channel(), version.classification().name(), version.variables().size(),
                normalized.contentHash(), catalog.catalogHash());
        CampaignToolArtifact artifact =
                new CampaignToolArtifact.MessageSafePreview(version, normalized, card, catalog.catalogHash());
        return resolved(CampaignToolIds.MESSAGE_PREVIEW_SAFE, "MESSAGE_PREVIEW_READY",
                1, artifact, List.of(version.referenceCode()));
    }

    /**
     * Safely validates and previews one inline Campaign message draft.
     *
     * <p>This is the one Message handler that reads no host evidence at all: the typed request already carries
     * the canonical channel and the exact bounded body, so the handler only re-proves that input and derives
     * its deterministic hashes and stable identity. It reads no catalog, pack, Provider, fixture or clock, and
     * it neither persists the draft nor claims it is sendable.
     */
    private static CampaignToolExecution messageInlinePreview(
            CampaignToolRunContext run, CampaignToolRequest request) {
        CampaignToolRequest.MessageInlinePreview typed = (CampaignToolRequest.MessageInlinePreview) request;
        CampaignToolArtifact.MessageInlineSafePreview artifact =
                CampaignToolArtifact.MessageInlineSafePreview.of(typed.channel().name(), typed.body());
        return resolved(CampaignToolIds.MESSAGE_INLINE_PREVIEW_SAFE, "MESSAGE_INLINE_PREVIEW_READY",
                1, artifact, List.of(artifact.componentCode()));
    }

    // ------------------------------------------------------------------ Promotion

    private static CampaignToolExecution rewardDiscover(CampaignToolRunContext run, CampaignToolRequest request) {
        RewardCatalog catalog = run.rewardCatalog();
        if (catalog == null) {
            return evidenceMissing();
        }
        CampaignToolRequest.RewardDiscover typed = (CampaignToolRequest.RewardDiscover) request;
        List<CampaignCatalogSummary> matches = new ArrayList<>();
        for (RewardVersion version : catalog.versions()) {
            if (matches.size() >= typed.limit()) {
                break;
            }
            if (typed.query() != null && !version.referenceCode().contains(typed.query())) {
                continue;
            }
            matches.add(new CampaignCatalogSummary(
                    version.referenceCode(), version.logicalId(), version.versionId(), version.contentHash(),
                    version.kind(), version.enabled(), version.runtimeAvailable()));
        }
        CampaignToolArtifact artifact = new CampaignToolArtifact.RewardDiscovery(matches, catalog.catalogHash());
        return resolved(CampaignToolIds.REWARD_DISCOVER, "REWARDS_DISCOVERED",
                matches.size(), artifact, referencesOf(matches));
    }

    private static CampaignToolExecution rewardResolve(CampaignToolRunContext run, CampaignToolRequest request) {
        RewardCatalog catalog = run.rewardCatalog();
        if (catalog == null) {
            return evidenceMissing();
        }
        CampaignToolRequest.RewardResolve typed = (CampaignToolRequest.RewardResolve) request;
        Optional<RewardVersion> found = catalog.resolve(typed.referenceCode());
        if (found.isEmpty()) {
            return fail(Disposition.INVALID, CampaignAssemblyErrorCodes.TOOL_RESOURCE_UNAVAILABLE);
        }
        RewardVersion version = found.get();
        if (!version.enabled() || !version.runtimeAvailable()) {
            return fail(Disposition.INVALID, CampaignAssemblyErrorCodes.TOOL_RESOURCE_UNAVAILABLE);
        }
        CampaignToolArtifact artifact = new CampaignToolArtifact.RewardResolution(version, catalog.catalogHash());
        return resolved(CampaignToolIds.REWARD_RESOLVE, "REWARD_RESOLVED",
                1, artifact, List.of(version.referenceCode()));
    }

    private static CampaignToolExecution rewardExposure(CampaignToolRunContext run, CampaignToolRequest request) {
        RewardCatalog catalog = run.rewardCatalog();
        CampaignToolRequest.RewardExposureCalculate typed = (CampaignToolRequest.RewardExposureCalculate) request;
        Optional<RewardVersion> found = catalog == null
                ? Optional.empty()
                : catalog.resolve(typed.referenceCode());
        if (found.isEmpty()) {
            return catalog == null
                    ? evidenceMissing()
                    : fail(Disposition.INVALID, CampaignAssemblyErrorCodes.TOOL_RESOURCE_UNAVAILABLE);
        }
        RewardVersion version = found.get();
        CapabilityResolution resolution = driveRewardPack(run, typed.referenceCode(), typed.quantity());
        if (resolution == null) {
            return evidenceMissing();
        }
        if (resolution instanceof CapabilityResolution.Failed failed) {
            return fail(Disposition.INVALID, mapRewardFailure(codeOf(failed)));
        }
        CapabilityPayload normalized = ((CapabilityResolution.Resolved) resolution).value();
        BigDecimal perRecipient = version.faceValue();
        BigDecimal maximum = declaredMaximum(perRecipient, typed.quantity(), typed.governanceCeiling());
        CampaignToolArtifact artifact = new CampaignToolArtifact.RewardExposure(
                version, typed.quantity(), perRecipient, maximum, normalized, catalog.catalogHash());
        return resolved(CampaignToolIds.REWARD_EXPOSURE_CALCULATE, "REWARD_EXPOSURE_CALCULATED",
                1, artifact, List.of(version.referenceCode()));
    }

    private static CampaignToolExecution rewardPreview(CampaignToolRunContext run, CampaignToolRequest request) {
        RewardCatalog catalog = run.rewardCatalog();
        CampaignToolRequest.RewardPreviewSafe typed = (CampaignToolRequest.RewardPreviewSafe) request;
        Optional<RewardVersion> found = catalog == null
                ? Optional.empty()
                : catalog.resolve(typed.referenceCode());
        if (found.isEmpty()) {
            return catalog == null
                    ? evidenceMissing()
                    : fail(Disposition.INVALID, CampaignAssemblyErrorCodes.TOOL_RESOURCE_UNAVAILABLE);
        }
        RewardVersion version = found.get();
        CapabilityResolution resolution = driveRewardPack(run, typed.referenceCode(), typed.quantity());
        if (resolution == null) {
            return evidenceMissing();
        }
        if (resolution instanceof CapabilityResolution.Failed failed) {
            return fail(Disposition.INVALID, mapRewardFailure(codeOf(failed)));
        }
        CapabilityPayload normalized = ((CapabilityResolution.Resolved) resolution).value();
        BigDecimal perRecipient = version.faceValue();
        BigDecimal maximum = declaredMaximum(perRecipient, typed.quantity(), typed.governanceCeiling());
        RewardSafePreviewCard card = new RewardSafePreviewCard(
                version.referenceCode(), version.logicalId(), version.versionId(), version.contentHash(),
                version.kind(), version.currency(), version.faceValue(), typed.quantity(), perRecipient,
                maximum, normalized.contentHash(), catalog.catalogHash());
        CampaignToolArtifact artifact =
                new CampaignToolArtifact.RewardSafePreview(version, normalized, card, catalog.catalogHash());
        return resolved(CampaignToolIds.REWARD_PREVIEW_SAFE, "REWARD_PREVIEW_READY",
                1, artifact, List.of(version.referenceCode()));
    }

    // ------------------------------------------------------------------ Schedule

    private static CampaignToolExecution scheduleNormalize(CampaignToolRunContext run, CampaignToolRequest request) {
        CampaignToolRequest.ScheduleNormalize typed = (CampaignToolRequest.ScheduleNormalize) request;
        CapabilityResolution resolution = driveSchedulePack(run, typed.componentId(), typed.declaration());
        if (resolution == null) {
            return evidenceMissing();
        }
        if (resolution instanceof CapabilityResolution.Failed failed) {
            return fail(Disposition.INVALID, mapScheduleFailure(codeOf(failed)));
        }
        CapabilityPayload normalized = ((CapabilityResolution.Resolved) resolution).value();
        ScheduleBasicForm form = declaredForm(normalized);
        if (form == null) {
            // The accepted pack just resolved this payload, so an unreadable form is an internal incoherence and
            // not a user-facing clarification: it fails closed without echoing the payload.
            return fail(Disposition.INVALID, CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT);
        }
        CampaignToolArtifact artifact = new CampaignToolArtifact.ScheduleNormalization(
                typed.componentId(), normalized, form.summaryCode());
        return resolved(CampaignToolIds.SCHEDULE_NORMALIZE, "SCHEDULE_NORMALIZED",
                normalized.fields().size(), artifact, List.of());
    }

    private static CampaignToolExecution scheduleValidate(CampaignToolRunContext run, CampaignToolRequest request) {
        CampaignToolRequest.ScheduleValidate typed = (CampaignToolRequest.ScheduleValidate) request;
        CapabilityResolution resolution = driveSchedulePack(run, typed.componentId(), typed.declaration());
        if (resolution == null) {
            return evidenceMissing();
        }
        if (resolution instanceof CapabilityResolution.Failed failed) {
            return fail(Disposition.INVALID, mapScheduleFailure(codeOf(failed)));
        }
        CapabilityPayload normalized = ((CapabilityResolution.Resolved) resolution).value();
        CampaignToolArtifact artifact =
                new CampaignToolArtifact.ScheduleValidation(typed.componentId(), normalized);
        return resolved(CampaignToolIds.SCHEDULE_VALIDATE, "SCHEDULE_VALIDATED",
                normalized.fields().size(), artifact, List.of());
    }

    // ------------------------------------------------------------------ Governance

    private static CampaignToolExecution governanceExposure(CampaignToolRunContext run, CampaignToolRequest request) {
        CampaignToolRequest.GovernanceExposureCalculate typed =
                (CampaignToolRequest.GovernanceExposureCalculate) request;
        CapabilityResolution resolution = driveGovernancePack(run, typed.componentId(), typed.declaration());
        if (resolution == null) {
            return evidenceMissing();
        }
        if (resolution instanceof CapabilityResolution.Failed failed) {
            return fail(Disposition.INVALID, mapGovernanceFailure(codeOf(failed)));
        }
        CapabilityPayload normalized = ((CapabilityResolution.Resolved) resolution).value();
        Long grants = wholeNumber(normalized, GovernanceLimitVersions.FIELD_MAX_REWARD_GRANTS);
        BigDecimal faceValue = exactAmount(normalized, GovernanceLimitVersions.FIELD_MAX_REWARD_FACE_VALUE);
        if (grants == null || faceValue == null) {
            // The accepted pack just resolved this payload, so an unreadable limit is an internal incoherence.
            return fail(Disposition.INVALID, CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT);
        }
        BigDecimal declaredMaximum = faceValue.multiply(BigDecimal.valueOf(grants));
        CampaignToolArtifact artifact = new CampaignToolArtifact.GovernanceExposure(
                typed.componentId(), normalized, grants, faceValue, declaredMaximum);
        return resolved(CampaignToolIds.GOVERNANCE_EXPOSURE_CALCULATE, "GOVERNANCE_EXPOSURE_CALCULATED",
                1, artifact, List.of());
    }

    private static CampaignToolExecution governanceValidate(CampaignToolRunContext run, CampaignToolRequest request) {
        CampaignToolRequest.GovernanceValidate typed = (CampaignToolRequest.GovernanceValidate) request;
        CapabilityResolution resolution = driveGovernancePack(run, typed.componentId(), typed.declaration());
        if (resolution == null) {
            return evidenceMissing();
        }
        if (resolution instanceof CapabilityResolution.Failed failed) {
            return fail(Disposition.INVALID, mapGovernanceFailure(codeOf(failed)));
        }
        CapabilityPayload normalized = ((CapabilityResolution.Resolved) resolution).value();
        CampaignToolArtifact artifact =
                new CampaignToolArtifact.GovernanceValidation(typed.componentId(), normalized);
        return resolved(CampaignToolIds.GOVERNANCE_VALIDATE, "GOVERNANCE_LIMITS_VALIDATED",
                normalized.fields().size(), artifact, List.of());
    }

    // ------------------------------------------------------------------ Journey

    /**
     * The closed Journey node capability catalog.
     *
     * <p>It reads the host-owned adapter only and projects an already-safe machine fact list; it needs no
     * catalog, policy or context evidence, and it stages nothing.
     */
    private static CampaignToolExecution journeyNodeCatalog(
            CampaignToolRunContext run, CampaignToolRequest request) {
        CampaignToolRequest.JourneyNodeCatalog typed = (CampaignToolRequest.JourneyNodeCatalog) request;
        List<JourneyNodeCapability> capabilities = JOURNEY.nodeCatalog();
        List<JourneyNodeCapability> bounded = capabilities.size() > typed.limit()
                ? List.copyOf(capabilities.subList(0, typed.limit()))
                : capabilities;
        CampaignToolArtifact artifact = new CampaignToolArtifact.JourneyNodeCatalog(bounded);
        return resolved(CampaignToolIds.JOURNEY_NODE_CATALOG, "JOURNEY_NODE_CATALOG_READY",
                bounded.size(), artifact, List.of());
    }

    /** Normalizes every declared Journey node through its accepted T19 Flow pack. */
    private static CampaignToolExecution journeyCompose(CampaignToolRunContext run, CampaignToolRequest request) {
        JourneyGraphDraft draft = ((CampaignToolRequest.JourneyCompose) request).draft();
        FrozenCapabilityCatalog catalog = run.capabilityCatalog();
        FrozenAuthoringPolicy policy = run.authoringPolicy();
        TrustedExecutionContext context = run.trustedContext();
        if (catalog == null || policy == null || context == null) {
            return evidenceMissing();
        }
        try {
            JourneyComposition composition = JOURNEY.compose(draft, catalog, policy, context);
            CampaignToolArtifact artifact = new CampaignToolArtifact.JourneyNodeComposition(composition);
            return resolved(CampaignToolIds.JOURNEY_COMPOSE, "JOURNEY_COMPOSED",
                    composition.nodes().size(), artifact, List.of());
        } catch (JourneyAuthoringException rejected) {
            // A refused node declaration is input-dependent: the runner asks a typed clarification instead of
            // fabricating a composition, and no declaration value is echoed.
            return fail(Disposition.INVALID, CampaignAssemblyErrorCodes.TOOL_GRAPH_INVALID);
        } catch (RuntimeException rejected) {
            return fail(Disposition.INVALID, CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT);
        }
    }

    /**
     * Validates the complete authoring graph and compiles the atomic Journey bundle.
     *
     * <p>This is the one stageable Journey result, and it is produced only when the accepted T20 graph
     * contract accepted the complete post-merge node and edge set against the frozen pack index the host
     * supplied.
     */
    private static CampaignToolExecution journeyValidate(CampaignToolRunContext run, CampaignToolRequest request) {
        JourneyGraphDraft draft = ((CampaignToolRequest.JourneyValidate) request).draft();
        T19PackIndex packIndex = run.journeyPackIndex();
        FrozenCapabilityCatalog catalog = run.capabilityCatalog();
        FrozenAuthoringPolicy policy = run.authoringPolicy();
        TrustedExecutionContext context = run.trustedContext();
        if (packIndex == null || catalog == null || policy == null || context == null) {
            return evidenceMissing();
        }
        try {
            JourneyValidationOutcome outcome = JOURNEY.validate(draft, packIndex, catalog, policy, context);
            if (!outcome.valid()) {
                // An invalid graph is a bounded, correctable refusal: nothing partial is ever reported.
                return fail(Disposition.INVALID, CampaignAssemblyErrorCodes.TOOL_GRAPH_INVALID);
            }
            CampaignToolArtifact artifact = new CampaignToolArtifact.JourneyValidation(outcome);
            return resolved(CampaignToolIds.JOURNEY_VALIDATE, "JOURNEY_VALIDATED",
                    outcome.nodeCount(), artifact, List.of());
        } catch (JourneyAuthoringException rejected) {
            return fail(Disposition.INVALID, CampaignAssemblyErrorCodes.TOOL_GRAPH_INVALID);
        } catch (RuntimeException rejected) {
            return fail(Disposition.INVALID, CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT);
        }
    }

    /** Projects the bounded pure preview of one virtual-clock Journey simulation. */
    private static CampaignToolExecution journeySimulate(CampaignToolRunContext run, CampaignToolRequest request) {
        CampaignToolRequest.JourneySimulate typed = (CampaignToolRequest.JourneySimulate) request;
        T19PackIndex packIndex = run.journeyPackIndex();
        FrozenCapabilityCatalog catalog = run.capabilityCatalog();
        FrozenAuthoringPolicy policy = run.authoringPolicy();
        TrustedExecutionContext context = run.trustedContext();
        if (packIndex == null || catalog == null || policy == null || context == null) {
            return evidenceMissing();
        }
        try {
            JourneySimulationSummary summary = JOURNEY.simulate(
                    typed.draft(), typed.simulation(), packIndex, catalog, policy, context);
            if (!summary.isValid()) {
                // An exhausted hard limit fails closed and reports no partial path list.
                return fail(Disposition.INVALID, CampaignAssemblyErrorCodes.TOOL_GRAPH_INVALID);
            }
            CampaignToolArtifact artifact = new CampaignToolArtifact.JourneySimulation(summary);
            return resolved(CampaignToolIds.JOURNEY_SIMULATE, "JOURNEY_SIMULATED",
                    summary.pathCount(), artifact, List.of());
        } catch (JourneyAuthoringException rejected) {
            return fail(Disposition.INVALID, CampaignAssemblyErrorCodes.TOOL_GRAPH_INVALID);
        } catch (RuntimeException rejected) {
            return fail(Disposition.INVALID, CampaignAssemblyErrorCodes.TOOL_RESULT_INCOHERENT);
        }
    }

    // ------------------------------------------------------------------ accepted pack driving

    /** Drives the accepted {@code message.template} pack, or null when its frozen host evidence is absent. */
    private static CapabilityResolution driveMessagePack(
            CampaignToolRunContext run, String referenceCode, CapabilityPayload variables) {
        MessageTemplatePack pack = run.messagePack();
        FrozenCapabilityCatalog catalog = run.capabilityCatalog();
        FrozenAuthoringPolicy policy = run.authoringPolicy();
        TrustedExecutionContext context = run.trustedContext();
        if (pack == null || catalog == null || policy == null || context == null) {
            return null;
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("referenceCode", referenceCode);
        fields.put("variables", variables.fields());
        CapabilityRequest request = new CapabilityRequest(
                referenceCode, CapabilityPayload.of(fields), catalog, policy, context);
        return pack.resolve(request);
    }

    /** Drives the accepted {@code promotion.reference} pack, or null when its frozen host evidence is absent. */
    private static CapabilityResolution driveRewardPack(
            CampaignToolRunContext run, String referenceCode, int quantity) {
        PromotionReferencePack pack = run.promotionPack();
        FrozenCapabilityCatalog catalog = run.capabilityCatalog();
        FrozenAuthoringPolicy policy = run.authoringPolicy();
        TrustedExecutionContext context = run.trustedContext();
        if (pack == null || catalog == null || policy == null || context == null) {
            return null;
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("referenceCode", referenceCode);
        fields.put("quantity", quantity);
        CapabilityRequest request = new CapabilityRequest(
                referenceCode, CapabilityPayload.of(fields), catalog, policy, context);
        return pack.resolve(request);
    }

    /** Drives the accepted {@code schedule.basic} pack, or null when its frozen host evidence is absent. */
    private static CapabilityResolution driveSchedulePack(
            CampaignToolRunContext run, String componentId, CapabilityPayload declaration) {
        ScheduleBasicPack pack = run.schedulePack();
        FrozenCapabilityCatalog catalog = run.capabilityCatalog();
        FrozenAuthoringPolicy policy = run.authoringPolicy();
        TrustedExecutionContext context = run.trustedContext();
        if (pack == null || catalog == null || policy == null || context == null) {
            return null;
        }
        // The declaration is the exact payload the request carried: this boundary adds, removes and renames no
        // field, so the accepted pack alone decides which declared field names exist.
        return pack.resolve(new CapabilityRequest(componentId, declaration, catalog, policy, context));
    }

    /** Drives the accepted {@code governance.limit} pack, or null when its frozen host evidence is absent. */
    private static CapabilityResolution driveGovernancePack(
            CampaignToolRunContext run, String componentId, CapabilityPayload declaration) {
        GovernanceLimitPack pack = run.governancePack();
        FrozenCapabilityCatalog catalog = run.capabilityCatalog();
        FrozenAuthoringPolicy policy = run.authoringPolicy();
        TrustedExecutionContext context = run.trustedContext();
        if (pack == null || catalog == null || policy == null || context == null) {
            return null;
        }
        return pack.resolve(new CapabilityRequest(componentId, declaration, catalog, policy, context));
    }

    /** The declared form of one already-normalized schedule payload, or null when it is unreadable. */
    private static ScheduleBasicForm declaredForm(CapabilityPayload normalized) {
        Object declared = normalized.fields().get(ScheduleBasicVersions.FIELD_FORM);
        return declared instanceof String text ? ScheduleBasicForm.parse(text).orElse(null) : null;
    }

    /** One already-normalized whole-number governance limit, or null when it is unreadable. */
    private static Long wholeNumber(CapabilityPayload normalized, String field) {
        Object value = normalized.fields().get(field);
        if (!(value instanceof Number number)) {
            return null;
        }
        try {
            return new BigDecimal(number.toString()).longValueExact();
        } catch (NumberFormatException | ArithmeticException failure) {
            return null;
        }
    }

    /** One already-normalized exact governance amount at its declared scale, or null when it is unreadable. */
    private static BigDecimal exactAmount(CapabilityPayload normalized, String field) {
        Object value = normalized.fields().get(field);
        if (!(value instanceof Number number)) {
            return null;
        }
        try {
            BigDecimal exact = new BigDecimal(number.toString());
            return exact.scale() > GovernanceLimitVersions.FACE_VALUE_SCALE
                    ? null
                    : exact.setScale(GovernanceLimitVersions.FACE_VALUE_SCALE);
        } catch (NumberFormatException failure) {
            return null;
        }
    }

    /**
     * The exact declared maximum exposure of one grant: the exact face-value total, bounded by the supplied
     * governance ceiling. It is a declaration, never an inventory check, reservation or balance.
     */
    private static BigDecimal declaredMaximum(BigDecimal perRecipient, int quantity, BigDecimal ceiling) {
        BigDecimal total = perRecipient.multiply(BigDecimal.valueOf(quantity));
        return total.compareTo(ceiling) > 0 ? ceiling : total;
    }

    private static String codeOf(CapabilityResolution.Failed failed) {
        List<Issue> issues = failed.issues();
        return issues.isEmpty() ? "" : issues.get(0).code();
    }

    private static String mapMessageFailure(String packCode) {
        return MessageTemplateErrorCodes.MESSAGE_TEMPLATE_UNKNOWN_REFERENCE.equals(packCode)
                || MessageTemplateErrorCodes.MESSAGE_TEMPLATE_DISABLED.equals(packCode)
                || MessageTemplateErrorCodes.MESSAGE_TEMPLATE_UNAVAILABLE.equals(packCode)
                ? CampaignAssemblyErrorCodes.TOOL_RESOURCE_UNAVAILABLE
                : CampaignAssemblyErrorCodes.TOOL_VARIABLES_INVALID;
    }

    private static String mapRewardFailure(String packCode) {
        return RewardReferenceErrorCodes.REWARD_REFERENCE_UNKNOWN_REFERENCE.equals(packCode)
                || RewardReferenceErrorCodes.REWARD_REFERENCE_DISABLED.equals(packCode)
                || RewardReferenceErrorCodes.REWARD_REFERENCE_UNAVAILABLE.equals(packCode)
                ? CampaignAssemblyErrorCodes.TOOL_RESOURCE_UNAVAILABLE
                : CampaignAssemblyErrorCodes.TOOL_EXPOSURE_INVALID;
    }

    /**
     * Maps one accepted {@code schedule.basic} issue code to a bounded Campaign Tool code.
     *
     * <p>The accepted pack is the authority on what a valid declaration is; a payload it refuses is reported as
     * a declaration the host could supply differently, so the runner asks a typed clarification instead of
     * fabricating an artifact. A genuinely absent host input is reported as missing evidence.
     */
    private static String mapScheduleFailure(String packCode) {
        return ScheduleBasicVersions.ISSUE_INPUT.equals(packCode)
                ? CampaignAssemblyErrorCodes.TOOL_EVIDENCE_MISSING
                : CampaignAssemblyErrorCodes.TOOL_DECLARATION_INVALID;
    }

    /**
     * Maps one accepted {@code governance.limit} issue code to a bounded Campaign Tool code.
     *
     * <p>As with Schedule, every value refusal is input-dependent: the declared limits and currency are host
     * evidence the conversation may resolve differently on a later turn. A declared limit is never a reservation.
     */
    private static String mapGovernanceFailure(String packCode) {
        return GovernanceLimitVersions.ISSUE_INPUT.equals(packCode)
                ? CampaignAssemblyErrorCodes.TOOL_EVIDENCE_MISSING
                : CampaignAssemblyErrorCodes.TOOL_DECLARATION_INVALID;
    }

    private static List<String> referencesOf(List<CampaignCatalogSummary> matches) {
        List<String> references = new ArrayList<>(matches.size());
        for (CampaignCatalogSummary match : matches) {
            references.add(match.referenceCode());
        }
        return references;
    }

    // ------------------------------------------------------------------ execution shapes

    private static CampaignToolExecution resolved(
            String toolId, String outcomeCode, int itemCount, CampaignToolArtifact artifact,
            List<String> references) {
        CampaignToolOutcome outcome = new CampaignToolOutcome(
                toolId, outcomeCode, itemCount, artifact.publicEvidenceHash(), references);
        return CampaignToolExecution.resolved(outcome, artifact);
    }

    private static CampaignToolExecution fail(Disposition disposition, String code) {
        return CampaignToolExecution.failed(disposition, code);
    }

    private static CampaignToolExecution evidenceMissing() {
        return CampaignToolExecution.failed(
                Disposition.INVALID, CampaignAssemblyErrorCodes.TOOL_EVIDENCE_MISSING);
    }
}