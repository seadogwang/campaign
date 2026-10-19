package com.company.campaign.lifecycle;

import com.company.campaign.builder.compiler.CampaignPlan;
import com.company.campaign.builder.compiler.CampaignPlanStore;
import com.company.campaign.builder.compiler.ReferencePlanEvidence;
import com.company.campaign.evaluation.segment.gate.ReferenceApprovalLookupPort;
import com.company.campaign.evaluation.segment.gate.SegmentReadinessProjection;
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
import java.util.Objects;

/**
 * The explicit host-only Prepare Publish boundary: it turns one saved editable Draft into one expiring
 * server-stored {@link PublishPreparation}.
 *
 * <p>Preparing is <em>not</em> publishing and it is not authority to publish: it authorizes
 * {@code campaign.publish}, freezes the exact Draft revision and every frozen input a later confirmation
 * must re-prove, and stores a bounded record that expires. The preparation is generated only from a saved
 * Draft — never from a caller's Manifest, ChangeSet, readiness fact or version pin — and its semantic
 * content is exactly the applied Plan's resolved Manifest, reachable only through the hashes it freezes.
 *
 * <p>Evaluation order, all derived from trusted host state rather than the command:
 *
 * <ol>
 *   <li>a structurally invalid command is refused;</li>
 *   <li>the saved Draft is loaded by Campaign; an absent Campaign and one owned by another tenant,
 *       program or user share the identical non-disclosing not-found failure;</li>
 *   <li>the exact tenant, program and user ownership is proved <em>before</em> any permission decision;</li>
 *   <li>the exact Draft identity and revision the caller named are re-proved;</li>
 *   <li>{@code campaign.publish} is authorized against the Draft's own server-resolved ownership;</li>
 *   <li>the frozen source Plan is re-loaded and re-proved to still be the Draft's own Plan;</li>
 *   <li>frozen catalog/policy/capability/contract compatibility is rechecked;</li>
 *   <li>the final Manifest is revalidated through the deterministic host port every production step shares,
 *       so a preparation is never frozen for material that could not be applied or published;</li>
 *   <li>the exact production Segment activation is required through the accepted T16
 *       {@code SegmentActivationGuard};</li>
 *   <li>a Plan whose own trusted material carries reference evidence additionally has to prove the exact
 *       dedicated {@code segment.existing-audience-reference} activation, re-resolved by the host and
 *       re-projected over exactly the Plan's frozen reference revision, before that activation's provenance
 *       is frozen;</li>
 *   <li>the preparation and its safe audit event are committed as one unit against the exact Draft
 *       revision.</li>
 * </ol>
 *
 * <p>Exactly one bounded audit event is recorded for every command the boundary evaluates, and an audit
 * failure fails the operation closed. A command presented without its trusted context still fails closed
 * with a stable code, and when a rejection's own audit also fails the bounded reason stays primary.
 */
public final class PublishPreparationService {

    /** The audit reason code recorded for the one disposition that commits material. */
    public static final String PREPARED_REASON = "LIFECYCLE_PREPARED";

    private final CampaignPlanStore planStore;
    private final AuthorizationService authorizationService;
    private final FrozenCompatibilityPort compatibilityPort;
    private final ManifestValidationPort validationPort;
    private final SegmentReadinessLookupPort readinessPort;
    private final LifecycleStore store;
    private final LifecycleIdGenerator idGenerator;
    private final Clock clock;
    private final Duration lifetime;
    private final ReferencePreparationLookupPort referenceFactsPort;
    private final ReferenceApprovalLookupPort referenceApprovalPort;

    /** Creates a prepare boundary whose preparations live for {@link PublishPreparation#DEFAULT_LIFETIME}. */
    public PublishPreparationService(
            CampaignPlanStore planStore,
            AuthorizationService authorizationService,
            FrozenCompatibilityPort compatibilityPort,
            ManifestValidationPort validationPort,
            SegmentReadinessLookupPort readinessPort,
            LifecycleStore store,
            LifecycleIdGenerator idGenerator,
            Clock clock) {
        this(planStore, authorizationService, compatibilityPort, validationPort, readinessPort, store,
                idGenerator, clock, PublishPreparation.DEFAULT_LIFETIME);
    }

    /**
     * Creates a prepare boundary with an explicit preparation lifetime.
     *
     * <p>The lifetime is bounded by {@link PublishPreparation#MIN_LIFETIME} and
     * {@link PublishPreparation#MAX_LIFETIME}: a host may shorten or lengthen the default fifteen minutes,
     * but no host may declare a preparation that is born spent or that outlives its own inputs.
     *
     * <p>This is the accepted reference-free boundary: it holds no host reference ports, so a reference-bearing
     * Plan refuses rather than inheriting an approval no host supplied.
     */
    public PublishPreparationService(
            CampaignPlanStore planStore,
            AuthorizationService authorizationService,
            FrozenCompatibilityPort compatibilityPort,
            ManifestValidationPort validationPort,
            SegmentReadinessLookupPort readinessPort,
            LifecycleStore store,
            LifecycleIdGenerator idGenerator,
            Clock clock,
            Duration lifetime) {
        this(planStore, authorizationService, compatibilityPort, validationPort, readinessPort, store,
                idGenerator, clock, lifetime, null, null);
    }

    /**
     * Creates a prepare boundary wired for the dedicated Existing Audience Reference activation.
     *
     * <p>Both reference ports are host-only and either may be absent. A reference-free Plan never consults
     * them, so leaving them absent keeps the accepted reference-free behavior and bytes exactly; a
     * reference-bearing Plan handed none fails closed, because there is no default approval to fall back to.
     */
    public PublishPreparationService(
            CampaignPlanStore planStore,
            AuthorizationService authorizationService,
            FrozenCompatibilityPort compatibilityPort,
            ManifestValidationPort validationPort,
            SegmentReadinessLookupPort readinessPort,
            LifecycleStore store,
            LifecycleIdGenerator idGenerator,
            Clock clock,
            ReferencePreparationLookupPort referenceFactsPort,
            ReferenceApprovalLookupPort referenceApprovalPort) {
        this(planStore, authorizationService, compatibilityPort, validationPort, readinessPort, store,
                idGenerator, clock, PublishPreparation.DEFAULT_LIFETIME, referenceFactsPort,
                referenceApprovalPort);
    }

    /**
     * Creates a prepare boundary with an explicit preparation lifetime and host reference ports.
     *
     * <p>This is the one constructor that holds every field; every other constructor delegates here.
     */
    public PublishPreparationService(
            CampaignPlanStore planStore,
            AuthorizationService authorizationService,
            FrozenCompatibilityPort compatibilityPort,
            ManifestValidationPort validationPort,
            SegmentReadinessLookupPort readinessPort,
            LifecycleStore store,
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
        this.store = Objects.requireNonNull(store, "store");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lifetime = Objects.requireNonNull(lifetime, "lifetime");
        this.referenceFactsPort = referenceFactsPort;
        this.referenceApprovalPort = referenceApprovalPort;
        if (lifetime.compareTo(PublishPreparation.MIN_LIFETIME) < 0
                || lifetime.compareTo(PublishPreparation.MAX_LIFETIME) > 0) {
            throw new IllegalArgumentException("a preparation lifetime must stay inside its bound");
        }
    }

    /**
     * Prepares one saved Draft for publication, or fails closed.
     *
     * @throws LifecycleException for a bounded lifecycle rejection
     * @throws ResourceNotFoundException when the Campaign is absent or outside the caller's boundary
     * @throws PermissionDeniedException when the caller is inside the boundary but lacks
     *     {@code campaign.publish}
     */
    public PreparePublishResult prepare(PreparePublishCommand command, TrustedExecutionContext context) {
        if (context == null) {
            throw LifecycleSupport.missingTrustedContext();
        }

        SavedCampaignDraft draft = null;
        boolean ownDraft = false;
        try {
            if (command == null || isBlank(command.campaignId()) || isBlank(command.draftId())
                    || command.draftRevision() < 1L) {
                throw invalidCommand();
            }

            draft = store.findDraft(command.campaignId()).orElseThrow(ResourceNotFoundException::new);
            requireOwned(draft, context);
            ownDraft = true;

            requireDraftIdentity(command, draft);

            // Authorization is re-evaluated against the Draft's own server-resolved ownership, so a caller
            // cannot prepare a Draft it may read but may not publish.
            authorizationService.requireAuthorized(new AuthorizationRequest(
                    context,
                    new OwnedResourceRef(
                            ResourceKind.CAMPAIGN_PUBLISH, draft.campaignId(),
                            draft.tenantId(), draft.programId())));

            CampaignPlan plan = requireSourcePlan(draft);
            requireCompatible(plan, context);
            requireValidated(plan, context);
            SegmentReadinessProjection readiness = SegmentActivationCheck.require(readinessPort, plan);

            // The base Segment activation above is required exactly as before and is never substituted for.
            // A Plan whose own trusted material carries reference evidence additionally has to prove the
            // dedicated Existing Audience Reference activation, and only then is that activation's provenance
            // frozen beside the base evidence. A reference-free Plan supplies none and never consults a
            // reference port, so its preparation keeps the accepted material and bytes exactly.
            ReferencePlanEvidence referenceCard = ReferencePreparationCheck.card(plan);
            ReferencePreparationEvidence referenceEvidence = referenceCard == null
                    ? null
                    : ReferencePreparationCheck.require(
                            referenceFactsPort, referenceApprovalPort, plan, referenceCard);

            Instant now = clock.instant();
            PublishPreparation preparation = PublishPreparation.of(
                    idGenerator.nextId(LifecycleIdKind.PREPARATION),
                    context.tenantId(), context.programId(), context.userId(), context.dataScopeId(),
                    draft.campaignId(), draft.campaignRevision(), draft.draftId(), draft.draftRevision(),
                    draft.forkBase(),
                    plan.planId(), plan.planHash(), draft.manifestHash(), draft.draftHash(),
                    plan.catalogVersion(), plan.catalogHash(), plan.policyVersion(), plan.policyHash(),
                    plan.capabilityPins(), plan.previewCards(), plan.riskSummary().riskCodes(),
                    readiness.capabilityId(), readiness.state().name(),
                    readiness.currentInputsHash(), readiness.approvedDecisionHash(),
                    now, now.plus(lifetime), referenceEvidence);

            LifecycleAuditEvent audit = LifecycleAuditEvent.of(
                    LifecycleAction.PREPARE_PUBLISH, LifecycleDisposition.APPLIED, PREPARED_REASON, now,
                    context,
                    LifecycleSupport.fingerprintOf(draft.campaignId(), draft.tenantId(), draft.programId()),
                    plan.planHash(), draft.draftHash());

            PublishPreparation stored = store.commitPreparation(new PublishPreparationCommit(
                    draft.campaignId(), draft.draftId(), draft.draftRevision(), preparation, audit));

            return new PreparePublishResult(
                    stored.ref(), stored.draftIdentity(), stored.readinessState(), stored.requiredRiskCodes(),
                    stored.previewCards(), stored.createdAt(), stored.expiresAt());
        } catch (RuntimeException failure) {
            try {
                recordRejection(context, draft, ownDraft, failure);
            } catch (RuntimeException auditFailure) {
                // The bounded rejection stays primary; the failing sink is evidence attached to it.
                failure.addSuppressed(auditFailure);
            }
            throw failure;
        }
    }

    // ------------------------------------------------------------------ evaluation steps

    /**
     * Re-proves the Draft belongs to this exact caller.
     *
     * <p>The accepted authorization core compares tenant and program; the exact user, and therefore the exact
     * DataScope, is re-proved here, so a Draft saved by another user inside the same boundary is not this
     * caller's to prepare.
     */
    private void requireOwned(SavedCampaignDraft draft, TrustedExecutionContext context) {
        if (!draft.tenantId().equals(context.tenantId())
                || !draft.programId().equals(context.programId())
                || !draft.userId().equals(context.userId())) {
            throw new ResourceNotFoundException();
        }
    }

    private void requireDraftIdentity(PreparePublishCommand command, SavedCampaignDraft draft) {
        if (!draft.draftId().equals(command.draftId())
                || draft.draftRevision() != command.draftRevision()) {
            throw new LifecycleException(
                    LifecycleErrorCodes.LIFECYCLE_DRAFT_STALE,
                    "draftRevision",
                    "The saved Draft no longer matches the identity and revision the command named.");
        }
    }

    /**
     * Re-loads the exact Plan the Draft was saved from and re-proves the Draft still descends from it.
     *
     * <p>The Plan is what carries the safe Preview projection and the risk summary a preparation freezes, so
     * a Draft whose source Plan is no longer available cannot be published at all rather than being published
     * with invented evidence.
     */
    private CampaignPlan requireSourcePlan(SavedCampaignDraft draft) {
        CampaignPlan plan = planStore.find(draft.sourcePlanId()).orElseThrow(() -> notPublishable("sourcePlan"));
        if (!plan.planId().equals(draft.sourcePlanId())
                || !plan.manifestHash().equals(draft.manifestHash())
                || !plan.tenantId().equals(draft.tenantId())
                || !plan.programId().equals(draft.programId())
                || !plan.userId().equals(draft.userId())) {
            throw notPublishable("sourcePlanBinding");
        }
        return plan;
    }

    private void requireCompatible(CampaignPlan plan, TrustedExecutionContext context) {
        FrozenCompatibilityQuery query = new FrozenCompatibilityQuery(
                plan.catalogVersion(), plan.catalogHash(), plan.policyVersion(), plan.policyHash(),
                plan.capabilityPins(), context);
        CompatibilityDecision decision;
        try {
            decision = compatibilityPort.recheck(query);
        } catch (RuntimeException exception) {
            throw compatibilityUnavailable();
        }
        if (decision == null || decision.outcome() == null) {
            throw compatibilityUnavailable();
        }
        switch (decision.outcome()) {
            case COMPATIBLE -> {
                // The Plan's frozen pins still describe the inputs in force.
            }
            case INCOMPATIBLE -> throw new LifecycleException(
                    LifecycleErrorCodes.LIFECYCLE_COMPATIBILITY_STALE,
                    "frozenCompatibility",
                    "The Plan's frozen catalog, policy or contract pins are no longer compatible.");
            case UNAVAILABLE -> throw compatibilityUnavailable();
        }
    }

    /**
     * Re-runs the deterministic final-Manifest validation Apply and Publish share before freezing anything.
     *
     * <p>A preparation freezes the exact evidence a later confirmation publishes, so a Draft whose final
     * material no longer passes the frozen authoring policy must not be preparable at all: freezing it would
     * store evidence for a publication that could never be accepted. A null, thrown, over-limit, null-entry
     * or blocking answer fails closed and stores nothing.
     */
    private void requireValidated(CampaignPlan plan, TrustedExecutionContext context) {
        switch (LifecycleSupport.validateFinalManifest(validationPort, plan.manifest(), context)) {
            case VALID -> {
                // The Plan's final material still passes the frozen authoring policy.
            }
            case REJECTED -> throw new LifecycleException(
                    LifecycleErrorCodes.LIFECYCLE_VALIDATION_REJECTED,
                    "finalManifest",
                    "The final Manifest failed deterministic revalidation.");
            case UNAVAILABLE -> throw new LifecycleException(
                    LifecycleErrorCodes.LIFECYCLE_VALIDATION_UNAVAILABLE,
                    "finalManifest",
                    "The final Manifest revalidation could not produce a trustworthy result.");
        }
    }

    // ------------------------------------------------------------------ audit

    private void recordRejection(
            TrustedExecutionContext context,
            SavedCampaignDraft draft,
            boolean ownDraft,
            RuntimeException failure) {
        LifecycleDisposition disposition = LifecycleSupport.dispositionOf(failure);
        AuditResourceFingerprint fingerprint = null;
        ContentHash draftHash = null;
        if (ownDraft && draft != null && disposition != LifecycleDisposition.NOT_FOUND) {
            fingerprint = LifecycleSupport.fingerprintOf(
                    draft.campaignId(), draft.tenantId(), draft.programId());
            draftHash = draft.draftHash();
        }
        store.recordCommandAudit(LifecycleAuditEvent.of(
                LifecycleAction.PREPARE_PUBLISH, disposition, LifecycleSupport.reasonOf(failure),
                clock.instant(), context, fingerprint, null, draftHash));
    }

    // ------------------------------------------------------------------ helpers

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static LifecycleException invalidCommand() {
        return new LifecycleException(
                LifecycleErrorCodes.LIFECYCLE_COMMAND_INVALID,
                "command",
                "A Prepare Publish command requires a Campaign, a Draft identity and its exact revision.");
    }

    private static LifecycleException notPublishable(String invariant) {
        return new LifecycleException(
                LifecycleErrorCodes.LIFECYCLE_DRAFT_NOT_PUBLISHABLE,
                invariant,
                "The saved Draft no longer descends from a stored Plan and cannot be prepared.");
    }

    private static LifecycleException compatibilityUnavailable() {
        return new LifecycleException(
                LifecycleErrorCodes.LIFECYCLE_COMPATIBILITY_UNAVAILABLE,
                "frozenCompatibility",
                "No trustworthy frozen-input compatibility decision was available.");
    }
}