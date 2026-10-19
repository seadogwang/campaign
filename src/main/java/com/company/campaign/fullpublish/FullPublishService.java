package com.company.campaign.fullpublish;

import com.company.campaign.builder.compiler.CampaignPlan;
import com.company.campaign.builder.compiler.CampaignPlanStore;
import com.company.campaign.builder.compiler.PublishedIdentity;
import com.company.campaign.contracts.ContractPatterns;
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
import com.company.campaign.lifecycle.PublishPreparationState;
import com.company.campaign.lifecycle.PublishReceipt;
import com.company.campaign.lifecycle.PublishService;
import com.company.campaign.lifecycle.PublishedCampaignDefinition;
import com.company.campaign.lifecycle.ReferencePreparationLookupPort;
import com.company.campaign.lifecycle.ReferencePublishCheck;
import com.company.campaign.lifecycle.ResolvedReferenceDependencies;
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
import com.company.campaign.segment.reference.AudienceRetentionHold;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The one explicit host-only full publication confirmation boundary: it turns a server-stored
 * {@link FullPublishPreparation} into one immutable {@link FullPublishedDefinition} and its receipt, together
 * with the accepted T17 definition and receipt, the active schedule projection and one durable
 * scheduling-registration outbox event — all in one store commit.
 *
 * <p>It never calls the accepted T17 {@code PublishService.confirm} and then performs T21 writes: the accepted
 * T17 definition, receipt, published-pointer move, preparation consumption, retention holds and both applied
 * audit events are committed by the accepted T17 store primitive inside the same transaction that writes the
 * T21 artifacts, so the whole publication is one unit or none of it. The applied full-publication audit is an
 * additional audit event of that accepted commit rather than a separate write, so it can never outlive a
 * failed base commit or precede a publication that was never written.
 *
 * <p>The publication store and the schedule read are one combined {@link FullPublicationStore}: this boundary
 * is not representable over a split publication/schedule wiring, so the schedule revision it reads is always
 * the revision the commit re-proves.
 *
 * <p>Preparing is not authority to publish, so this boundary independently repeats every accepted T17 decision
 * — ownership, {@code campaign.publish}, DataScope, revision, expiry, compatibility, final-Manifest validation,
 * the exact production Segment activation and the dedicated Existing Audience Reference recheck — and then
 * re-compiles the exact T20 Journey against the current frozen Draft, re-verifies the exact T21 Schedule, and
 * re-proves the host runtime availability pin. A drift in any of them makes the preparation stale rather than
 * publishing under evidence the caller never saw.
 *
 * <p>Evaluation order, all derived from trusted host state rather than the command:
 *
 * <ol>
 *   <li>a structurally invalid command is refused;</li>
 *   <li>the full preparation is loaded by identifier; an absent preparation and one owned by another tenant,
 *       program or user share the identical non-disclosing not-found failure;</li>
 *   <li>the exact ownership is proved <em>before</em> any permission decision, and the presented hash is
 *       compared with the preparation's own hash in constant time;</li>
 *   <li>the confirmations' syntax and duplicates are validated, and the same key with a changed request is a
 *       conflict, all before any idempotent replay may be returned;</li>
 *   <li>the confirmations must be exactly the preparation's frozen risk codes;</li>
 *   <li>{@code campaign.publish} is reauthorized independently against the preparation's ownership, and the
 *       caller's DataScope is re-proved to be the frozen one;</li>
 *   <li>only then may an exact idempotent repeat return its original receipt, writing nothing;</li>
 *   <li>state is classified; a consumed or expired preparation is refused;</li>
 *   <li>the saved Draft, its root revisions, the source Plan, frozen compatibility, the final Manifest, the
 *       exact production Segment activation, the reference closure, the compiled Journey, the compiled Schedule
 *       and the runtime-availability pin are all rechecked; any drift makes the preparation stale;</li>
 *   <li>the whole publication — the accepted T17 artifacts, the full definition and receipt, the active
 *       schedule projection and the outbox event — is committed as one unit, which re-proves inside the
 *       transaction that both the published revision and the active schedule-state revision the service
 *       observed are still the ones it is replacing.</li>
 * </ol>
 */
public final class FullPublishService {

    /** The audit reason code recorded for the full-publication commit. */
    public static final String FULL_PUBLISHED_REASON = "LIFECYCLE_FULL_PUBLISHED";

    private static final Pattern MACHINE_CODE = Pattern.compile(ContractPatterns.MACHINE_CODE);

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
    private final ReferencePreparationLookupPort referenceFactsPort;
    private final ReferenceApprovalLookupPort referenceApprovalPort;

    /**
     * Creates the one full publication boundary that holds every collaborator.
     *
     * <p>The publication and the schedule read come from one combined {@link FullPublicationStore}, because the
     * full publication commit writes the active schedule projection: one transactional reference is the only
     * wiring in which the state revision the boundary reads is the revision the commit replaces.
     */
    public FullPublishService(
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
        this.referenceFactsPort = referenceFactsPort;
        this.referenceApprovalPort = referenceApprovalPort;
    }

    /**
     * The retained two-reference constructor: it fails closed unless both references are one combined store.
     *
     * <p>It exists only so an older host wiring keeps compiling; it never silently accepts a split publication
     * and schedule backend, because that split is exactly the atomicity hole the combined port removes. A
     * caller that passes two distinct references is refused before any command can run.
     */
    public FullPublishService(
            CampaignPlanStore planStore,
            AuthorizationService authorizationService,
            FrozenCompatibilityPort compatibilityPort,
            ManifestValidationPort validationPort,
            SegmentReadinessLookupPort readinessPort,
            LifecycleStore baseStore,
            FullPublicationStore store,
            ScheduleStore scheduleStore,
            JourneyPublishSourcePort journeySourcePort,
            HostRuntimeAvailabilityPort runtimeAvailabilityPort,
            LifecycleIdGenerator idGenerator,
            Clock clock,
            ReferencePreparationLookupPort referenceFactsPort,
            ReferenceApprovalLookupPort referenceApprovalPort) {
        this(planStore, authorizationService, compatibilityPort, validationPort, readinessPort, baseStore,
                combinedStore(store, scheduleStore), journeySourcePort, runtimeAvailabilityPort,
                idGenerator, clock, referenceFactsPort, referenceApprovalPort);
    }

    /**
     * Proves that the publication store and the schedule store are the one combined transactional backend.
     *
     * <p>Identity is the only proof available: two equal-looking references can still be two independent
     * backends, so a reference that is not the very same combined store is refused rather than trusted.
     */
    private static FullPublicationStore combinedStore(
            FullPublicationStore store, ScheduleStore scheduleStore) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(scheduleStore, "scheduleStore");
        if (store != scheduleStore || !(scheduleStore instanceof FullPublicationStore)) {
            throw new IllegalArgumentException(
                    "a full publication service requires one combined full-publication/schedule store");
        }
        return store;
    }

    /**
     * Confirms one server-stored full preparation and freezes one immutable full definition, or fails closed.
     *
     * @throws LifecycleException for a bounded full-publication rejection
     * @throws ResourceNotFoundException when the preparation is absent or outside the caller's boundary
     * @throws PermissionDeniedException when the caller is inside the boundary but lacks
     *     {@code campaign.publish}
     */
    public FullPublishResult confirm(FullPublishCommand command, TrustedExecutionContext context) {
        if (context == null) {
            throw LifecycleSupport.missingTrustedContext();
        }

        FullPublishPreparation preparation = null;
        boolean ownPreparation = false;
        try {
            if (command == null || FullPublishSupport.isBlank(command.preparationId())
                    || command.preparationHash() == null
                    || FullPublishSupport.isBlank(command.idempotencyKey())
                    || command.confirmations() == null
                    || command.confirmations().size() > FullPublishSupport.MAX_RISK_CODES) {
                throw invalidCommand();
            }

            preparation = store.findFullPreparation(command.preparationId())
                    .orElseThrow(ResourceNotFoundException::new);
            FullPublicationRechecks.requireOwned(preparation.basePreparation(), context);
            ownPreparation = true;

            if (!LifecycleSupport.constantTimeEquals(
                    command.preparationHash(), preparation.preparationHash())) {
                throw new LifecycleException(
                        LifecycleErrorCodes.LIFECYCLE_FULL_PREPARATION_MISMATCH,
                        "preparationHash",
                        "The presented full preparation hash is not the loaded preparation's own hash.");
            }

            // Syntax and repetition are proved before the idempotency index is consulted, so a duplicate
            // acknowledgement can neither be normalized onto an accepted request's hash nor be replayed as one
            // after the preparation was consumed.
            requireWellFormedConfirmations(command);

            ContentHash requestHash = command.requestHash();

            FullPublishReceipt existing = store.findFullReceipt(
                    preparation.tenantId(), preparation.programId(), preparation.userId(),
                    preparation.preparationId(), command.idempotencyKey()).orElse(null);
            if (existing != null && !existing.requestHash().equals(requestHash)) {
                throw new LifecycleException(
                        LifecycleErrorCodes.LIFECYCLE_PUBLISH_CONFLICT,
                        "idempotencyKeyReuse",
                        "An idempotency key is already bound to a different full publish request.");
            }

            requireExactConfirmations(command, preparation);

            Instant now = clock.instant();

            authorizationService.requireAuthorized(new AuthorizationRequest(
                    context,
                    new OwnedResourceRef(
                            ResourceKind.CAMPAIGN_PUBLISH, preparation.campaignId(),
                            preparation.tenantId(), preparation.programId())));
            FullPublicationRechecks.requireSameDataScope(preparation.basePreparation(), context);

            if (existing != null) {
                return fullResult(existing);
            }

            PublishPreparationState state = preparation.durableState(
                    now, store.isFullPreparationConsumed(preparation.preparationId()));
            if (state == PublishPreparationState.CONSUMED) {
                throw new LifecycleException(
                        LifecycleErrorCodes.LIFECYCLE_FULL_PREPARATION_CONSUMED,
                        "preparationConsumed",
                        "A full preparation may be consumed by at most one successful full publication.");
            }
            if (state == PublishPreparationState.EXPIRED) {
                throw new LifecycleException(
                        LifecycleErrorCodes.LIFECYCLE_FULL_PREPARATION_EXPIRED,
                        "preparationExpiry",
                        "The full preparation is at or past its expiry instant.");
            }

            PublishPreparation basePreparation = preparation.basePreparation();
            SavedCampaignDraft draft = FullPublicationRechecks.requireCurrentDraft(baseStore, basePreparation);
            CampaignPlan plan = FullPublicationRechecks.requireSourcePlan(planStore, draft, basePreparation);
            FullPublicationRechecks.requireCompatible(compatibilityPort, plan, context);
            FullPublicationRechecks.requireValidated(validationPort, plan, context);
            FullPublicationRechecks.requireActivation(readinessPort, plan, basePreparation);

            ResolvedReferenceDependencies references = ReferencePublishCheck.require(
                    referenceFactsPort, referenceApprovalPort, plan, basePreparation);

            JourneyPublishSource source = FullPublicationRechecks.requireJourneySource(journeySourcePort, draft);
            JourneyManifest journey = FullPublicationRechecks.compileJourney(draft, basePreparation, source);
            if (!journey.contentHash().equals(preparation.journeyHash())) {
                throw FullPublicationRechecks.stale("journeyDrift");
            }
            CompiledSchedule schedule = FullPublicationRechecks.compileSchedule(draft, journey, source);
            if (!schedule.equals(preparation.schedule())) {
                throw FullPublicationRechecks.stale("scheduleDrift");
            }
            RuntimeAvailabilityPin runtimeAvailability = RuntimeAvailabilityCheck.require(
                    runtimeAvailabilityPort, FullPublishVersions.RUNTIME_SCHEMA_VERSION);
            if (!runtimeAvailability.pinHash().equals(preparation.runtimeAvailability().pinHash())) {
                throw FullPublicationRechecks.stale("runtimeAvailabilityDrift");
            }

            long publishedRevision = baseStore.nextPublishedRevision(preparation.campaignId());
            PublishedCampaignDefinition baseDefinition = PublishedCampaignDefinition.of(
                    idGenerator.nextId(LifecycleIdKind.PUBLISHED_DEFINITION),
                    publishedRevision,
                    preparation.tenantId(), preparation.programId(), preparation.userId(),
                    preparation.dataScopeId(),
                    preparation.campaignId(), preparation.campaignRevision(),
                    preparation.draftId(), preparation.draftRevision(),
                    preparation.forkBase(), plan.planId(), plan.planHash(), draft.draftHash(),
                    plan.catalogVersion(), plan.catalogHash(), plan.policyVersion(), plan.policyHash(),
                    plan.capabilityPins(), plan.previewCards(), basePreparation.requiredRiskCodes(),
                    basePreparation.readinessCapability(), basePreparation.readinessState(),
                    basePreparation.gateInputsHash(), basePreparation.approvedDecisionHash(),
                    draft.manifest(), now);

            PublishReceipt baseReceipt = PublishReceipt.of(
                    idGenerator.nextId(LifecycleIdKind.PUBLISH_RECEIPT),
                    preparation.tenantId(), preparation.programId(), preparation.userId(),
                    preparation.dataScopeId(),
                    preparation.basePreparationId(), preparation.basePreparationHash(),
                    command.idempotencyKey(), requestHash,
                    baseDefinition.publishedId(), baseDefinition.publishedRevision(),
                    baseDefinition.campaignId(), baseDefinition.campaignRevision(),
                    baseDefinition.sourceDraftId(), baseDefinition.sourceDraftRevision(),
                    plan.planId(), plan.planHash(), baseDefinition.manifestHash(),
                    baseDefinition.contentChecksum(), basePreparation.requiredRiskCodes(), now);

            FullPublishedDefinition fullDefinition = FullPublishedDefinition.of(
                    idGenerator.nextId(LifecycleIdKind.FULL_PUBLISHED_DEFINITION),
                    publishedRevision,
                    preparation.tenantId(), preparation.programId(), preparation.userId(),
                    preparation.dataScopeId(), preparation.campaignId(), preparation.campaignRevision(),
                    baseDefinition.identity(), baseDefinition.manifestHash(), journey, schedule,
                    runtimeAvailability, now);

            FullPublishReceipt fullReceipt = FullPublishReceipt.of(
                    idGenerator.nextId(LifecycleIdKind.FULL_PUBLISH_RECEIPT),
                    preparation.tenantId(), preparation.programId(), preparation.userId(),
                    preparation.dataScopeId(), preparation.preparationId(), preparation.preparationHash(),
                    command.idempotencyKey(), requestHash, fullDefinition,
                    preparation.requiredRiskCodes(), now);

            long nextStateRevision = store.findScheduleState(preparation.campaignId())
                    .map(current -> current.stateRevision() + 1L)
                    .orElse(1L);
            CampaignScheduleState scheduleState = CampaignScheduleState.of(
                    preparation.campaignId(),
                    preparation.tenantId(), preparation.programId(), preparation.userId(),
                    preparation.dataScopeId(), nextStateRevision, ScheduleStatus.ACTIVE,
                    fullDefinition.fullDefinitionId(), fullDefinition.contentHash(),
                    fullDefinition.baseDefinitionChecksum(), fullDefinition.journeyHash(), schedule,
                    now, now);

            ScheduleRegistrationOutboxEvent outboxEvent = ScheduleRegistrationOutboxEvent.of(
                    idGenerator.nextId(LifecycleIdKind.SCHEDULE_REGISTRATION_EVENT),
                    preparation.campaignId(), fullDefinition.fullDefinitionId(), publishedRevision,
                    fullDefinition.contentHash(), fullDefinition.baseDefinitionChecksum(),
                    fullDefinition.journeyHash(), fullDefinition.scheduleHash(),
                    fullDefinition.runtimeAvailabilityHash(), now);

            AuditResourceFingerprint fingerprint = LifecycleSupport.fingerprintOf(
                    preparation.campaignId(), preparation.tenantId(), preparation.programId());

            LifecycleAuditEvent baseAudit = LifecycleAuditEvent.of(
                    LifecycleAction.PUBLISH, LifecycleDisposition.APPLIED, PublishService.PUBLISHED_REASON,
                    now, context, fingerprint, plan.planHash(), baseDefinition.contentChecksum());
            LifecycleAuditEvent fullAudit = LifecycleAuditEvent.of(
                    LifecycleAction.FULL_PUBLISH, LifecycleDisposition.APPLIED, FULL_PUBLISHED_REASON,
                    now, context, fingerprint, plan.planHash(), fullDefinition.contentHash());

            List<AudienceRetentionHold> retentionHolds =
                    ReferencePublishCheck.holds(baseDefinition, references);

            FullPublishReceipt committed = store.commitFullPublish(new FullPublishCommit(
                    preparation.preparationId(), preparation.preparationHash(), command.idempotencyKey(),
                    requestHash, publishedRevision, baseDefinition, baseReceipt, baseAudit, fullDefinition,
                    fullReceipt, scheduleState, outboxEvent, fullAudit, retentionHolds));

            return fullResult(committed);
        } catch (RuntimeException failure) {
            try {
                recordRejection(context, preparation, ownPreparation, failure);
            } catch (RuntimeException auditFailure) {
                // The bounded rejection stays primary; the failing sink is evidence attached to it.
                failure.addSuppressed(auditFailure);
            }
            throw failure;
        }
    }

    /** The safe outward result of one full receipt and the accepted identity it bound. */
    private static FullPublishResult fullResult(FullPublishReceipt receipt) {
        PublishedIdentity baseIdentity = new PublishedIdentity(
                receipt.basePublishedId(), receipt.publicationRevision(), receipt.baseDefinitionChecksum());
        return FullPublishResult.of(receipt, baseIdentity);
    }

    /**
     * Proves each acknowledgement is a bounded machine code and that none is repeated.
     *
     * <p>This is deliberately separate from, and earlier than, the comparison with the preparation: a repeated
     * acknowledgement is an inconsistent request in its own right, and it must be refused before the
     * idempotency index is consulted.
     */
    private void requireWellFormedConfirmations(FullPublishCommand command) {
        Set<String> seen = new LinkedHashSet<>();
        for (String code : command.confirmations()) {
            if (code == null || !MACHINE_CODE.matcher(code).matches()) {
                throw confirmationMismatch("confirmationCode");
            }
            if (!seen.add(code)) {
                throw confirmationMismatch("confirmationRepeat");
            }
        }
    }

    private void requireExactConfirmations(
            FullPublishCommand command, FullPublishPreparation preparation) {
        if (!preparation.requiresExactly(new LinkedHashSet<>(command.confirmations()))) {
            throw confirmationMismatch("confirmationSet");
        }
    }

    private void recordRejection(
            TrustedExecutionContext context,
            FullPublishPreparation preparation,
            boolean ownPreparation,
            RuntimeException failure) {
        LifecycleDisposition disposition = LifecycleSupport.dispositionOf(failure);
        AuditResourceFingerprint fingerprint = null;
        ContentHash planHash = null;
        if (ownPreparation && preparation != null && disposition != LifecycleDisposition.NOT_FOUND) {
            fingerprint = LifecycleSupport.fingerprintOf(
                    preparation.campaignId(), preparation.tenantId(), preparation.programId());
            planHash = preparation.basePreparation().planHash();
        }
        baseStore.recordCommandAudit(LifecycleAuditEvent.of(
                LifecycleAction.FULL_PUBLISH, disposition, LifecycleSupport.reasonOf(failure),
                clock.instant(), context, fingerprint, planHash, null));
    }

    private static LifecycleException invalidCommand() {
        return new LifecycleException(
                FullPublishErrorCodes.FULL_COMMAND_INVALID,
                "command",
                "A full publication confirmation requires a full preparation, its hash, a key and its risks.");
    }

    private static LifecycleException confirmationMismatch(String invariant) {
        return new LifecycleException(
                LifecycleErrorCodes.LIFECYCLE_CONFIRMATION_MISMATCH,
                invariant,
                "The confirmations are not exactly the risk codes the full preparation froze.");
    }
}