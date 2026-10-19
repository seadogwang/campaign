package com.company.campaign.fullpublish;

import com.company.campaign.builder.compiler.CampaignPlan;
import com.company.campaign.builder.compiler.CampaignPlanStore;
import com.company.campaign.evaluation.segment.gate.ReferenceApprovalLookupPort;
import com.company.campaign.journey.compiler.JourneyManifest;
import com.company.campaign.lifecycle.FrozenCompatibilityPort;
import com.company.campaign.lifecycle.LifecycleAction;
import com.company.campaign.lifecycle.LifecycleAuditEvent;
import com.company.campaign.lifecycle.LifecycleDisposition;
import com.company.campaign.lifecycle.LifecycleErrorCodes;
import com.company.campaign.lifecycle.LifecycleException;
import com.company.campaign.lifecycle.LifecycleIdGenerator;
import com.company.campaign.lifecycle.LifecycleIdKind;
import com.company.campaign.lifecycle.LifecycleStore;
import com.company.campaign.lifecycle.LifecycleSupport;
import com.company.campaign.lifecycle.ManifestValidationPort;
import com.company.campaign.lifecycle.PublishPreparation;
import com.company.campaign.lifecycle.ReferencePreparationLookupPort;
import com.company.campaign.lifecycle.ReferencePublishCheck;
import com.company.campaign.lifecycle.SavedCampaignDraft;
import com.company.campaign.lifecycle.SegmentReadinessLookupPort;
import com.company.campaign.security.audit.AuditResourceFingerprint;
import com.company.campaign.security.authorization.AuthorizationRequest;
import com.company.campaign.security.authorization.AuthorizationService;
import com.company.campaign.security.authorization.OwnedResourceRef;
import com.company.campaign.security.authorization.PermissionDeniedException;
import com.company.campaign.security.authorization.ResourceKind;
import com.company.campaign.security.authorization.ResourceNotFoundException;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The explicit host-only Prepare Full Publish boundary: it extends one accepted server-stored
 * {@link PublishPreparation} with the exact T20 Journey and the exact T21 bounded Schedule and turns it into one
 * expiring server-stored {@link FullPublishPreparation}.
 *
 * <p>Preparing is <em>not</em> publishing and it is not authority to publish. The accepted T17 base boundary is
 * re-proven exactly as it was — ownership, {@code campaign.publish}, DataScope, Draft revision and hashes, the
 * frozen source Plan, compatibility, deterministic final-Manifest validation and the exact production Segment
 * activation — and the reference extension is re-proved through the same dedicated reference check. Only then is
 * the Journey compiled from the Draft's own resolver-supplied topology and pack index, the single enabled
 * {@code schedule.basic/1.0} component compiled, and the host runtime availability pinned.
 *
 * <p>Everything fails closed: a missing or drifted Journey source, a Journey that does not compile, a Manifest
 * that carries no — or more than one — enabled schedule component, a schedule the compiler refuses and an
 * absent, unavailable or mismatched runtime-availability answer all refuse the preparation rather than freezing
 * evidence a later confirmation could not honestly publish.
 *
 * <p>Exactly one bounded audit event is recorded for every command the boundary evaluates, and an audit failure
 * fails the operation closed.
 */
public final class FullPublishPreparationService {

    /** The audit reason code recorded for the one disposition that commits material. */
    public static final String PREPARED_REASON = "FULL_PUBLICATION_PREPARED";

    private final CampaignPlanStore planStore;
    private final AuthorizationService authorizationService;
    private final FrozenCompatibilityPort compatibilityPort;
    private final ManifestValidationPort validationPort;
    private final SegmentReadinessLookupPort readinessPort;
    private final LifecycleStore baseStore;
    private final FullPublicationStore store;
    private final JourneyPublishSourcePort journeySourcePort;
    private final HostRuntimeAvailabilityPort runtimeAvailabilityPort;
    private final LifecycleIdGenerator idGenerator;
    private final Clock clock;
    private final Duration lifetime;
    private final ReferencePreparationLookupPort referenceFactsPort;
    private final ReferenceApprovalLookupPort referenceApprovalPort;

    /**
     * Creates a full prepare boundary with an explicit lifetime and host ports.
     *
     * <p>This is the one constructor that holds every field; every other constructor delegates here.
     */
    public FullPublishPreparationService(
            CampaignPlanStore planStore,
            AuthorizationService authorizationService,
            FrozenCompatibilityPort compatibilityPort,
            ManifestValidationPort validationPort,
            SegmentReadinessLookupPort readinessPort,
            LifecycleStore baseStore,
            FullPublicationStore store,
            JourneyPublishSourcePort journeySourcePort,
            HostRuntimeAvailabilityPort runtimeAvailabilityPort,
            LifecycleIdGenerator idGenerator,
            Clock clock,
            Duration lifetime,
            ReferencePreparationLookupPort referenceFactsPort,
            ReferenceApprovalLookupPort referenceApprovalPort) {
        this.planStore = Objects.requireNonNull(planStore, "planStore");
        this.authorizationService = Objects.requireNonNull(authorizationService, "authorizationService");
        this.compatibilityPort = Objects.requireNonNull(compatibilityPort, "compatibilityPort");
        this.validationPort = Objects.requireNonNull(validationPort, "validationPort");
        this.readinessPort = Objects.requireNonNull(readinessPort, "readinessPort");
        this.baseStore = Objects.requireNonNull(baseStore, "baseStore");
        this.store = Objects.requireNonNull(store, "store");
        this.journeySourcePort = journeySourcePort;
        this.runtimeAvailabilityPort = runtimeAvailabilityPort;
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lifetime = Objects.requireNonNull(lifetime, "lifetime");
        this.referenceFactsPort = referenceFactsPort;
        this.referenceApprovalPort = referenceApprovalPort;
        if (lifetime.compareTo(FullPublishVersions.MIN_PREPARATION_LIFETIME) < 0
                || lifetime.compareTo(FullPublishVersions.MAX_PREPARATION_LIFETIME) > 0) {
            throw new IllegalArgumentException("a full preparation lifetime must stay inside its bound");
        }
    }

    /** Creates a full prepare boundary whose preparations live for the default lifetime. */
    public FullPublishPreparationService(
            CampaignPlanStore planStore,
            AuthorizationService authorizationService,
            FrozenCompatibilityPort compatibilityPort,
            ManifestValidationPort validationPort,
            SegmentReadinessLookupPort readinessPort,
            LifecycleStore baseStore,
            FullPublicationStore store,
            JourneyPublishSourcePort journeySourcePort,
            HostRuntimeAvailabilityPort runtimeAvailabilityPort,
            LifecycleIdGenerator idGenerator,
            Clock clock) {
        this(planStore, authorizationService, compatibilityPort, validationPort, readinessPort, baseStore,
                store, journeySourcePort, runtimeAvailabilityPort, idGenerator, clock,
                FullPublishVersions.DEFAULT_PREPARATION_LIFETIME, null, null);
    }

    /**
     * Creates a full prepare boundary with the default lifetime and the dedicated reference ports.
     *
     * <p>Both reference ports are host-only and may be absent. A reference-free Plan never consults them, so
     * leaving them absent keeps the accepted reference-free behavior exactly; a reference-bearing Plan handed
     * none fails closed, because there is no default approval to fall back to.
     */
    public FullPublishPreparationService(
            CampaignPlanStore planStore,
            AuthorizationService authorizationService,
            FrozenCompatibilityPort compatibilityPort,
            ManifestValidationPort validationPort,
            SegmentReadinessLookupPort readinessPort,
            LifecycleStore baseStore,
            FullPublicationStore store,
            JourneyPublishSourcePort journeySourcePort,
            HostRuntimeAvailabilityPort runtimeAvailabilityPort,
            LifecycleIdGenerator idGenerator,
            Clock clock,
            ReferencePreparationLookupPort referenceFactsPort,
            ReferenceApprovalLookupPort referenceApprovalPort) {
        this(planStore, authorizationService, compatibilityPort, validationPort, readinessPort, baseStore,
                store, journeySourcePort, runtimeAvailabilityPort, idGenerator, clock,
                FullPublishVersions.DEFAULT_PREPARATION_LIFETIME, referenceFactsPort, referenceApprovalPort);
    }

    /**
     * Prepares one accepted publish preparation for full publication, or fails closed.
     *
     * @throws LifecycleException for a bounded full-publication rejection
     * @throws ResourceNotFoundException when the preparation is absent or outside the caller's boundary
     * @throws PermissionDeniedException when the caller is inside the boundary but lacks
     *     {@code campaign.publish}
     */
    public PrepareFullPublishResult prepare(
            PrepareFullPublishCommand command, TrustedExecutionContext context) {
        if (context == null) {
            throw LifecycleSupport.missingTrustedContext();
        }

        PublishPreparation basePreparation = null;
        boolean ownPreparation = false;
        try {
            if (command == null || FullPublishSupport.isBlank(command.basePreparationId())
                    || command.basePreparationHash() == null) {
                throw invalidCommand();
            }
            basePreparation = baseStore.findPreparation(command.basePreparationId())
                    .orElseThrow(ResourceNotFoundException::new);
            FullPublicationRechecks.requireOwned(basePreparation, context);
            ownPreparation = true;

            if (!LifecycleSupport.constantTimeEquals(
                    command.basePreparationHash(), basePreparation.preparationHash())) {
                throw new LifecycleException(
                        LifecycleErrorCodes.LIFECYCLE_PREPARATION_MISMATCH,
                        "basePreparationHash",
                        "The presented hash is not the loaded preparation's own hash.");
            }

            Instant now = clock.instant();
            FullPublicationRechecks.requirePreparedState(baseStore, basePreparation, now);

            // Preparing is not authority to publish, so the base decision is taken again here against the
            // preparation's own server-resolved ownership, and the caller's DataScope is re-proved.
            authorizationService.requireAuthorized(new AuthorizationRequest(
                    context,
                    new OwnedResourceRef(
                            ResourceKind.CAMPAIGN_PUBLISH, basePreparation.campaignId(),
                            basePreparation.tenantId(), basePreparation.programId())));
            FullPublicationRechecks.requireSameDataScope(basePreparation, context);

            SavedCampaignDraft draft = FullPublicationRechecks.requireCurrentDraft(baseStore, basePreparation);
            CampaignPlan plan = FullPublicationRechecks.requireSourcePlan(planStore, draft, basePreparation);
            FullPublicationRechecks.requireCompatible(compatibilityPort, plan, context);
            FullPublicationRechecks.requireValidated(validationPort, plan, context);
            FullPublicationRechecks.requireActivation(readinessPort, plan, basePreparation);

            // The authoritative Existing Audience Reference recheck: a reference-bearing preparation must still
            // re-prove its dedicated activation, and a reference-free one consults no reference port at all.
            ReferencePublishCheck.require(referenceFactsPort, referenceApprovalPort, plan, basePreparation);

            JourneyPublishSource source = FullPublicationRechecks.requireJourneySource(journeySourcePort, draft);
            JourneyManifest journey = FullPublicationRechecks.compileJourney(draft, basePreparation, source);
            CompiledSchedule schedule = FullPublicationRechecks.compileSchedule(draft, journey, source);

            RuntimeAvailabilityPin runtimeAvailability = RuntimeAvailabilityCheck.require(
                    runtimeAvailabilityPort, FullPublishVersions.RUNTIME_SCHEMA_VERSION);

            FullPublishPreparation preparation = FullPublishPreparation.of(
                    idGenerator.nextId(LifecycleIdKind.FULL_PREPARATION),
                    basePreparation, journey, schedule, runtimeAvailability, mergedRiskCodes(basePreparation),
                    now, now.plus(lifetime));

            LifecycleAuditEvent audit = LifecycleAuditEvent.of(
                    LifecycleAction.FULL_PUBLISH, LifecycleDisposition.APPLIED, PREPARED_REASON, now, context,
                    LifecycleSupport.fingerprintOf(
                            basePreparation.campaignId(), basePreparation.tenantId(),
                            basePreparation.programId()),
                    plan.planHash(), schedule.scheduleHash());

            FullPublishPreparation stored = store.commitFullPreparation(new FullPreparationCommit(
                    draft.campaignId(), draft.draftId(), draft.draftRevision(), preparation, audit));

            return new PrepareFullPublishResult(
                    stored.ref(), stored.basePreparationHash(), stored.journeyHash(), stored.scheduleHash(),
                    stored.schedule().form().name(), stored.schedule().zoneId(),
                    stored.runtimeAvailability().state().name(), stored.requiredRiskCodes(),
                    stored.createdAt(), stored.expiresAt());
        } catch (RuntimeException failure) {
            try {
                recordRejection(context, basePreparation, ownPreparation, failure);
            } catch (RuntimeException auditFailure) {
                failure.addSuppressed(auditFailure);
            }
            throw failure;
        }
    }

    /** The base risks plus the full layer's own honest risks, canonical and duplicate-free. */
    private static List<String> mergedRiskCodes(PublishPreparation preparation) {
        Set<String> merged = new LinkedHashSet<>(preparation.requiredRiskCodes());
        merged.addAll(FullPublishVersions.FULL_RISK_CODES);
        List<String> ordered = new ArrayList<>(merged);
        ordered.sort(String::compareTo);
        return List.copyOf(ordered);
    }

    private void recordRejection(
            TrustedExecutionContext context,
            PublishPreparation preparation,
            boolean ownPreparation,
            RuntimeException failure) {
        LifecycleDisposition disposition = LifecycleSupport.dispositionOf(failure);
        AuditResourceFingerprint fingerprint = null;
        ContentHash planHash = null;
        if (ownPreparation && preparation != null && disposition != LifecycleDisposition.NOT_FOUND) {
            fingerprint = LifecycleSupport.fingerprintOf(
                    preparation.campaignId(), preparation.tenantId(), preparation.programId());
            planHash = preparation.planHash();
        }
        baseStore.recordCommandAudit(LifecycleAuditEvent.of(
                LifecycleAction.FULL_PUBLISH, disposition, LifecycleSupport.reasonOf(failure),
                clock.instant(), context, fingerprint, planHash, null));
    }

    private static LifecycleException invalidCommand() {
        return new LifecycleException(
                FullPublishErrorCodes.FULL_COMMAND_INVALID,
                "command",
                "A Prepare Full Publish command requires the accepted preparation and its own hash.");
    }
}