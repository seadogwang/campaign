package com.company.campaign.lifecycle;

import com.company.campaign.builder.compiler.CampaignCompiler;
import com.company.campaign.builder.compiler.CampaignPlan;
import com.company.campaign.builder.compiler.CampaignPlanStore;
import com.company.campaign.contracts.Issue;
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
import java.time.Instant;
import java.util.Objects;

/**
 * The one explicit host-only Apply boundary: it turns a server-stored Plan into one saved editable Draft.
 *
 * <p>Apply is not a Tool and is not reachable from a model, a Skill, a prompt or an HTTP payload. Its only
 * external input is {@link ApplyCommand} — a Plan identifier plus the exact hash — and it re-loads the
 * server-stored Plan and re-proves ownership, authorization, expiry, base revisions, frozen compatibility
 * and the final Manifest before it mutates anything. There is no caller-provided mutation document: the
 * draft's semantic content is exactly the Plan's own resolved Manifest.
 *
 * <p>Evaluation order, all derived from trusted host state rather than the command:
 *
 * <ol>
 *   <li>a structurally invalid command is refused;</li>
 *   <li>the Plan is loaded by identifier; an absent Plan and a Plan owned by another tenant, program or
 *       user share the identical non-disclosing not-found failure;</li>
 *   <li>the exact tenant, program and user ownership is proved <em>before</em> any permission decision, so a
 *       same-tenant Plan belonging to another user is never distinguishable from an absent one even by a
 *       caller who also lacks {@code campaign.edit};</li>
 *   <li>{@code campaign.edit} is authorized against the Plan's own server-resolved ownership;</li>
 *   <li>the presented hash and the Plan's self-binding hash are compared in constant time and the Plan is
 *       re-proved to self-bind to its identifier;</li>
 *   <li>an already-applied Plan returns its original receipt and writes nothing;</li>
 *   <li>expiry and blocking validation issues are rechecked;</li>
 *   <li>the exact base Campaign and Draft identity and revisions are rechecked;</li>
 *   <li>frozen catalog/policy/capability/contract compatibility is rechecked then the final Manifest is
 *       revalidated through the deterministic host port every production step shares;</li>
 *   <li>the Draft, its immutable receipt and the safe audit event are committed as one unit.</li>
 * </ol>
 *
 * <p>Exactly one bounded audit event is recorded for every command the boundary evaluates, and an audit
 * failure fails the operation closed. A command that cannot be audited at all — one presented without its
 * trusted context — still fails closed with a stable code. When an operation fails and recording its
 * rejection also fails, the bounded lifecycle reason stays the primary failure and the audit failure is
 * attached as suppressed, so an unrestricted sink exception never replaces a stable reason.
 */
public final class ApplyService {

    /** The audit reason code recorded for the one disposition that commits material. */
    static final String APPLIED_REASON = "LIFECYCLE_APPLIED";

    /** The audit reason code recorded when the accepted T02 authorization core reports a same-boundary denial. */
    static final String PERMISSION_DENIED_REASON = LifecycleSupport.PERMISSION_DENIED_REASON;

    /** The audit reason code recorded for an absent or cross-boundary resource. */
    static final String RESOURCE_NOT_FOUND_REASON = LifecycleSupport.RESOURCE_NOT_FOUND_REASON;

    private final CampaignPlanStore planStore;
    private final AuthorizationService authorizationService;
    private final FrozenCompatibilityPort compatibilityPort;
    private final ManifestValidationPort validationPort;
    private final LifecycleStore store;
    private final LifecycleIdGenerator idGenerator;
    private final Clock clock;

    public ApplyService(
            CampaignPlanStore planStore,
            AuthorizationService authorizationService,
            FrozenCompatibilityPort compatibilityPort,
            ManifestValidationPort validationPort,
            LifecycleStore store,
            LifecycleIdGenerator idGenerator,
            Clock clock) {
        this.planStore = Objects.requireNonNull(planStore, "planStore");
        this.authorizationService = Objects.requireNonNull(authorizationService, "authorizationService");
        this.compatibilityPort = Objects.requireNonNull(compatibilityPort, "compatibilityPort");
        this.validationPort = Objects.requireNonNull(validationPort, "validationPort");
        this.store = Objects.requireNonNull(store, "store");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Applies one server-stored Plan to the editable Draft, or fails closed.
     *
     * @throws LifecycleException for a bounded lifecycle rejection
     * @throws ResourceNotFoundException when the Plan is absent or outside the caller's boundary
     * @throws PermissionDeniedException when the caller is inside the boundary but lacks {@code campaign.edit}
     */
    public ApplyResult apply(ApplyCommand command, TrustedExecutionContext context) {
        if (context == null) {
            // There is no trusted owner to audit a command against, so it cannot be recorded; it still fails
            // closed with a stable, bounded code rather than a raw NullPointerException.
            throw LifecycleSupport.missingTrustedContext();
        }

        CampaignPlan plan = null;
        boolean ownPlan = false;
        try {
            if (command == null || command.planId() == null || command.planId().isBlank()
                    || command.planHash() == null) {
                throw invalidCommand();
            }

            plan = planStore.find(command.planId())
                    .orElseThrow(ResourceNotFoundException::new);

            requireOwned(plan, context);
            ownPlan = true;

            requireAuthorized(plan, context);

            requirePlanIdentity(command, plan);

            ApplyReceipt existing = store.findApplyReceipt(plan.planId()).orElse(null);
            if (existing != null) {
                if (!existing.planHash().equals(plan.planHash())) {
                    throw new LifecycleException(
                            LifecycleErrorCodes.LIFECYCLE_PLAN_MISMATCH,
                            "receiptPlan",
                            "A Plan identifier is already bound to a different Plan.");
                }
                // An exact repeat returns the original receipt without another revision or audit mutation.
                return ApplyResult.of(existing, plan.clientRefBindings());
            }

            requireNotExpired(plan);
            requireApplicable(plan);
            BaseState base = resolveBaseState(plan, context);
            requireCompatible(plan, context);
            requireValidated(plan, context);

            Instant now = clock.instant();
            long campaignRevision = base.campaignRevision() + 1L;
            long draftRevision = base.draftRevision() + 1L;

            SavedCampaignDraft draft = SavedCampaignDraft.of(
                    context.tenantId(), context.programId(), context.userId(),
                    base.campaignId(), campaignRevision, base.draftId(), draftRevision,
                    plan.forkBase(), plan.manifest(), plan.planId(), now);
            ApplyReceipt receipt = ApplyReceipt.of(
                    idGenerator.nextId(LifecycleIdKind.APPLY_RECEIPT),
                    context.tenantId(), context.programId(), context.userId(),
                    plan.planId(), plan.planHash(), base.campaignId(), campaignRevision,
                    base.draftId(), draftRevision, plan.manifestHash(), draft.draftHash(), now);

            LifecycleAuditEvent audit = LifecycleAuditEvent.of(
                    LifecycleAction.APPLY, LifecycleDisposition.APPLIED, APPLIED_REASON, now, context,
                    fingerprintOf(base.campaignId(), plan.tenantId(), plan.programId()),
                    plan.planHash(), draft.draftHash());

            ApplyCommit commit = new ApplyCommit(
                    plan.planId(), plan.planHash(),
                    base.expectedCampaignId(), base.expectedCampaignRevision(),
                    base.expectedDraftId(), base.expectedDraftRevision(),
                    draft, receipt, audit);

            ApplyReceipt committed = store.commitApply(commit);
            return ApplyResult.of(committed, plan.clientRefBindings());
        } catch (RuntimeException failure) {
            try {
                recordRejection(context, plan, ownPlan, failure);
            } catch (RuntimeException auditFailure) {
                // The command is already rejected; an audit sink that cannot record it fails the operation
                // closed without losing the bounded rejection that caused it, so the stable lifecycle or
                // authorization reason stays primary and the unrestricted sink failure is attached as
                // suppressed rather than replacing it outward.
                failure.addSuppressed(auditFailure);
            }
            throw failure;
        }
    }

    // ------------------------------------------------------------------ evaluation steps

    private void requireOwned(CampaignPlan plan, TrustedExecutionContext context) {
        // Exact ownership is proved before the permission decision. The authorization core compares tenant
        // and program only, so the exact user is re-proved here, and doing it first means an absent Plan, a
        // cross-boundary Plan and a same-tenant Plan compiled by another user are all the identical
        // non-disclosing not-found failure — including for a caller who also lacks campaign.edit and would
        // otherwise have been told the resource exists.
        if (!plan.tenantId().equals(context.tenantId())
                || !plan.programId().equals(context.programId())
                || !plan.userId().equals(context.userId())) {
            throw new ResourceNotFoundException();
        }
    }

    private void requireAuthorized(CampaignPlan plan, TrustedExecutionContext context) {
        String resourceId = plan.campaignId() != null ? plan.campaignId() : CampaignCompiler.CREATE_RESOURCE_ID;
        OwnedResourceRef resource = new OwnedResourceRef(
                ResourceKind.CAMPAIGN, resourceId, plan.tenantId(), plan.programId());
        authorizationService.requireAuthorized(new AuthorizationRequest(context, resource));
    }

    private void requirePlanIdentity(ApplyCommand command, CampaignPlan plan) {
        if (!plan.planId().equals(command.planId()) || !constantTimeEquals(command.planHash(), plan.planHash())) {
            throw new LifecycleException(
                    LifecycleErrorCodes.LIFECYCLE_PLAN_MISMATCH,
                    "planHash",
                    "The presented Plan hash is not the loaded Plan's own hash.");
        }
        if (!plan.planHash().matches(plan.canonicalMaterialBytes())) {
            throw new LifecycleException(
                    LifecycleErrorCodes.LIFECYCLE_PLAN_MISMATCH,
                    "planSelfBinding",
                    "The loaded Plan no longer self-binds to its identifier and hash.");
        }
    }

    private void requireNotExpired(CampaignPlan plan) {
        if (!plan.expiresAt().isAfter(clock.instant())) {
            throw new LifecycleException(
                    LifecycleErrorCodes.LIFECYCLE_PLAN_EXPIRED,
                    "expiry",
                    "The loaded Plan is at or past its expiry instant.");
        }
    }

    private void requireApplicable(CampaignPlan plan) {
        for (Issue issue : plan.validationIssues()) {
            if (issue != null && issue.blocksProcessing()) {
                throw new LifecycleException(
                        LifecycleErrorCodes.LIFECYCLE_PLAN_INVALID,
                        "validationIssues",
                        "The loaded Plan carries a blocking validation issue.");
            }
        }
    }

    private BaseState resolveBaseState(CampaignPlan plan, TrustedExecutionContext context) {
        if (plan.campaignId() == null) {
            // CREATE: the server allocates the Campaign and Draft identity for this commit.
            String campaignId = idGenerator.nextId(LifecycleIdKind.CAMPAIGN);
            String draftId = idGenerator.nextId(LifecycleIdKind.DRAFT);
            return new BaseState(campaignId, 0L, draftId, 0L, null, null, null, null);
        }

        SavedCampaignDraft current = store.findDraft(plan.campaignId()).orElseThrow(() -> new LifecycleException(
                LifecycleErrorCodes.LIFECYCLE_DRAFT_STALE,
                "campaignMissing",
                "The Plan's target Campaign has no editable Draft."));
        if (!current.tenantId().equals(context.tenantId())
                || !current.programId().equals(context.programId())
                || !current.userId().equals(context.userId())) {
            throw new ResourceNotFoundException();
        }
        if (current.campaignRevision() != plan.baseCampaignRevision()
                || !current.draftId().equals(plan.baseDraftId())
                || current.draftRevision() != plan.baseDraftRevision()
                || !Objects.equals(current.forkBase(), plan.forkBase())) {
            throw new LifecycleException(
                    LifecycleErrorCodes.LIFECYCLE_DRAFT_STALE,
                    "baseRevision",
                    "The editable Draft no longer matches the Plan's base revision.");
        }
        return new BaseState(
                plan.campaignId(), current.campaignRevision(), current.draftId(), current.draftRevision(),
                plan.campaignId(), plan.baseCampaignRevision(), plan.baseDraftId(), plan.baseDraftRevision());
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

    private void requireValidated(CampaignPlan plan, TrustedExecutionContext context) {
        switch (LifecycleSupport.validateFinalManifest(validationPort, plan.manifest(), context)) {
            case VALID -> {
                // The Plan's final material still passes the frozen authoring policy.
            }
            case REJECTED -> throw new LifecycleException(
                    LifecycleErrorCodes.LIFECYCLE_VALIDATION_REJECTED,
                    "finalManifest",
                    "The final Manifest failed deterministic revalidation.");
            case UNAVAILABLE -> throw validationUnavailable();
        }
    }

    // ------------------------------------------------------------------ audit

    private void recordRejection(
            TrustedExecutionContext context,
            CampaignPlan plan,
            boolean ownPlan,
            RuntimeException failure) {
        LifecycleDisposition disposition = dispositionOf(failure);
        String reason = reasonOf(failure);

        AuditResourceFingerprint fingerprint = null;
        ContentHash planHash = null;
        if (ownPlan && plan != null && disposition != LifecycleDisposition.NOT_FOUND) {
            String resourceId = plan.campaignId() != null
                    ? plan.campaignId()
                    : CampaignCompiler.CREATE_RESOURCE_ID;
            fingerprint = fingerprintOf(resourceId, plan.tenantId(), plan.programId());
            planHash = plan.planHash();
        }
        store.recordCommandAudit(LifecycleAuditEvent.of(
                LifecycleAction.APPLY, disposition, reason, clock.instant(), context,
                fingerprint, planHash, null));
    }

    private static LifecycleDisposition dispositionOf(RuntimeException failure) {
        return LifecycleSupport.dispositionOf(failure);
    }

    private static String reasonOf(RuntimeException failure) {
        return LifecycleSupport.reasonOf(failure);
    }

    // ------------------------------------------------------------------ helpers

    private static AuditResourceFingerprint fingerprintOf(String resourceId, String tenantId, String programId) {
        return LifecycleSupport.fingerprintOf(resourceId, tenantId, programId);
    }

    /** Constant-time comparison of two content hashes, so a hash cannot be probed byte by byte. */
    static boolean constantTimeEquals(ContentHash left, ContentHash right) {
        return LifecycleSupport.constantTimeEquals(left, right);
    }

    private static LifecycleException invalidCommand() {
        return new LifecycleException(
                LifecycleErrorCodes.LIFECYCLE_COMMAND_INVALID,
                "command",
                "An Apply command requires exactly a Plan identifier and its hash.");
    }

    private static LifecycleException compatibilityUnavailable() {
        return new LifecycleException(
                LifecycleErrorCodes.LIFECYCLE_COMPATIBILITY_UNAVAILABLE,
                "frozenCompatibility",
                "No trustworthy frozen-input compatibility decision was available.");
    }

    private static LifecycleException validationUnavailable() {
        return new LifecycleException(
                LifecycleErrorCodes.LIFECYCLE_VALIDATION_UNAVAILABLE,
                "finalManifest",
                "The final Manifest revalidation could not produce a trustworthy result.");
    }

    /** The exact base state an Apply write is compared against, and the identity it produces. */
    private record BaseState(
            String campaignId,
            long campaignRevision,
            String draftId,
            long draftRevision,
            String expectedCampaignId,
            Long expectedCampaignRevision,
            String expectedDraftId,
            Long expectedDraftRevision) {
    }
}