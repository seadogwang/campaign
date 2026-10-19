package com.company.campaign.lifecycle;

import com.company.campaign.builder.compiler.CampaignPlan;
import com.company.campaign.builder.compiler.CampaignPlanStore;
import com.company.campaign.builder.compiler.RiskSummary;
import com.company.campaign.contracts.ContractPatterns;
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
 * The one explicit host-only Publish confirmation boundary: it turns a server-stored
 * {@link PublishPreparation} into one immutable {@link PublishedCampaignDefinition} and its receipt.
 *
 * <p>Publish is a separate command that independently reauthorizes {@code campaign.publish}: preparing a
 * Draft is deliberately not authority to publish it later. The only external input is
 * {@link PublishCommand} — a server-stored preparation, its exact hash, a bounded idempotency key and the
 * exact risk codes the preparation froze — and the boundary re-loads the preparation and the saved Draft and
 * re-proves ownership, DataScope, permission, expiry, state, revisions, frozen inputs, final validation and
 * the latest exact production Segment activation before it freezes anything.
 *
 * <p>Evaluation order, all derived from trusted host state rather than the command:
 *
 * <ol>
 *   <li>a structurally invalid command is refused;</li>
 *   <li>the preparation is loaded by identifier; an absent preparation and one owned by another tenant,
 *       program or user share the identical non-disclosing not-found failure;</li>
 *   <li>the exact tenant, program and user ownership is proved <em>before</em> any permission decision, and
 *       the presented hash is compared with the preparation's own hash in constant time;</li>
 *   <li>the confirmations' syntax and duplicates are validated, and the same key with a changed request is
 *       a conflict, all before any idempotent replay may be returned;</li>
 *   <li>the confirmations must be exactly the preparation's frozen risk codes;</li>
 *   <li>{@code campaign.publish} is reauthorized independently against the preparation's ownership, and the
 *       caller's DataScope is re-proved to be the frozen one — so a revoked permission or a moved DataScope
 *       cannot retrieve a prior receipt through this mutation command;</li>
 *   <li>only then may an exact idempotent repeat return its original receipt, writing nothing;</li>
 *   <li>state is classified; a consumed or expired preparation is refused;</li>
 *   <li>the saved Draft, its root revisions, the source Plan, frozen compatibility, the final Manifest and
 *       the exact production Segment activation are rechecked; any drift makes the preparation stale;</li>
 *   <li>the immutable definition, its receipt, the consumption of the preparation and the safe audit event
 *       are committed as one unit, which re-proves inside the transaction that the revision the service
 *       observed is still current and exactly one revision ahead.</li>
 * </ol>
 *
 * <p>Exactly one bounded audit event is recorded for every command the boundary evaluates, and an audit
 * failure fails the operation closed. A command presented without its trusted context still fails closed
 * with a stable code, and when a rejection's own audit also fails the bounded reason stays primary.
 */
public final class PublishService {

    /** The audit reason code recorded for the one disposition that commits material. */
    public static final String PUBLISHED_REASON = "LIFECYCLE_PUBLISHED";

    private static final Pattern MACHINE_CODE = Pattern.compile(ContractPatterns.MACHINE_CODE);

    private final CampaignPlanStore planStore;
    private final AuthorizationService authorizationService;
    private final FrozenCompatibilityPort compatibilityPort;
    private final ManifestValidationPort validationPort;
    private final SegmentReadinessLookupPort readinessPort;
    private final LifecycleStore store;
    private final LifecycleIdGenerator idGenerator;
    private final Clock clock;
    private final ReferencePreparationLookupPort referenceFactsPort;
    private final ReferenceApprovalLookupPort referenceApprovalPort;

    /**
     * Creates the accepted reference-free publish boundary.
     *
     * <p>It holds no host reference ports, so a reference-bearing preparation refuses rather than inheriting an
     * approval no host supplied; a reference-free preparation never consults them and keeps the accepted
     * behavior and bytes exactly.
     */
    public PublishService(
            CampaignPlanStore planStore,
            AuthorizationService authorizationService,
            FrozenCompatibilityPort compatibilityPort,
            ManifestValidationPort validationPort,
            SegmentReadinessLookupPort readinessPort,
            LifecycleStore store,
            LifecycleIdGenerator idGenerator,
            Clock clock) {
        this(planStore, authorizationService, compatibilityPort, validationPort, readinessPort, store,
                idGenerator, clock, null, null);
    }

    /**
     * Creates a publish boundary wired for the dedicated Existing Audience Reference recheck.
     *
     * <p>Both reference ports are host-only and either may be absent. A reference-free Plan never consults
     * them, so leaving them absent keeps the accepted reference-free behavior exactly; a reference-bearing
     * preparation handed none fails closed, because there is no default approval to fall back to.
     */
    public PublishService(
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
        this.planStore = Objects.requireNonNull(planStore, "planStore");
        this.authorizationService = Objects.requireNonNull(authorizationService, "authorizationService");
        this.compatibilityPort = Objects.requireNonNull(compatibilityPort, "compatibilityPort");
        this.validationPort = Objects.requireNonNull(validationPort, "validationPort");
        this.readinessPort = Objects.requireNonNull(readinessPort, "readinessPort");
        this.store = Objects.requireNonNull(store, "store");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.referenceFactsPort = referenceFactsPort;
        this.referenceApprovalPort = referenceApprovalPort;
    }

    /**
     * Confirms one server-stored preparation and freezes one immutable Published Definition, or fails
     * closed.
     *
     * @throws LifecycleException for a bounded lifecycle rejection
     * @throws ResourceNotFoundException when the preparation is absent or outside the caller's boundary
     * @throws PermissionDeniedException when the caller is inside the boundary but lacks
     *     {@code campaign.publish}
     */
    public PublishResult confirm(PublishCommand command, TrustedExecutionContext context) {
        if (context == null) {
            throw LifecycleSupport.missingTrustedContext();
        }

        PublishPreparation preparation = null;
        boolean ownPreparation = false;
        try {
            if (command == null || isBlank(command.preparationId()) || command.preparationHash() == null
                    || isBlank(command.idempotencyKey()) || command.confirmations() == null
                    || command.confirmations().size() > RiskSummary.MAX_RISK_CODES) {
                throw invalidCommand();
            }

            preparation = store.findPreparation(command.preparationId())
                    .orElseThrow(ResourceNotFoundException::new);
            requireOwned(preparation, context);
            ownPreparation = true;

            requirePreparationHash(command, preparation);

            // Syntax and repetition are proved before the idempotency index is consulted, so a duplicate
            // acknowledgement can neither be normalized onto an accepted request's hash nor be replayed as
            // one after the preparation was consumed.
            requireWellFormedConfirmations(command);

            ContentHash requestHash = command.requestHash();

            // A changed request under a used key is a conflict and stays a conflict; it is decided here,
            // before exact equality with the preparation, so a caller who changes the request is told the key
            // is reused rather than being handed a mismatch that invites retrying.
            PublishReceipt existing = store.findPublishReceipt(
                    preparation.tenantId(), preparation.programId(), preparation.userId(),
                    preparation.preparationId(), command.idempotencyKey()).orElse(null);
            if (existing != null && !existing.requestHash().equals(requestHash)) {
                throw new LifecycleException(
                        LifecycleErrorCodes.LIFECYCLE_PUBLISH_CONFLICT,
                        "idempotencyKeyReuse",
                        "An idempotency key is already bound to a different publish request.");
            }

            requireExactConfirmations(command, preparation);

            Instant now = clock.instant();

            // Preparing is not authority to publish: the same decision is taken again here, against the
            // preparation's own server-resolved ownership, and the caller's DataScope is re-proved before the
            // replay is returned. A revoked permission or a moved DataScope therefore cannot retrieve a prior
            // receipt through this mutation command.
            authorizationService.requireAuthorized(new AuthorizationRequest(
                    context,
                    new OwnedResourceRef(
                            ResourceKind.CAMPAIGN_PUBLISH, preparation.campaignId(),
                            preparation.tenantId(), preparation.programId())));

            requireSameDataScope(preparation, context);

            // Only now, with ownership, permission, DataScope, the preparation hash and the exact request all
            // proved, may an exact repeat return its original immutable result without writing anything.
            if (existing != null) {
                return PublishResult.of(existing);
            }

            requirePublishableState(preparation, now);

            SavedCampaignDraft draft = requireCurrentDraft(preparation);
            CampaignPlan plan = requireSourcePlan(draft, preparation);
            requireCompatible(plan, context);
            requireValidated(plan, context);
            SegmentReadinessProjection readiness = SegmentActivationCheck.require(readinessPort, plan);
            requireSameActivation(preparation, readiness);

            // T18: a reference-bearing preparation additionally re-proves, from trusted host state, the exact
            // dedicated reference activation it froze and the exact transitive pins it depends on. Preparing was
            // not authority to publish, so this is a second, independent decision; it re-resolves and
            // reauthorizes the closure the host holds now and refuses any version, hash, availability, program,
            // scope or retention drift. A reference-free preparation consults no reference port at all.
            ResolvedReferenceDependencies references = ReferencePublishCheck.require(
                    referenceFactsPort, referenceApprovalPort, plan, preparation);

            // The next published revision is observed here but only *proved* inside the one synchronized
            // commit: the commit refuses unless this revision is still the Campaign's next revision and the
            // definition it freezes is exactly that revision, so two concurrent preparations can never both
            // publish revision N.
            long publishedRevision = store.nextPublishedRevision(preparation.campaignId());
            PublishedCampaignDefinition definition = PublishedCampaignDefinition.of(
                    idGenerator.nextId(LifecycleIdKind.PUBLISHED_DEFINITION),
                    publishedRevision,
                    preparation.tenantId(), preparation.programId(), preparation.userId(),
                    preparation.dataScopeId(),
                    preparation.campaignId(), preparation.campaignRevision(),
                    preparation.draftId(), preparation.draftRevision(),
                    preparation.forkBase(), plan.planId(), plan.planHash(), draft.draftHash(),
                    plan.catalogVersion(), plan.catalogHash(), plan.policyVersion(), plan.policyHash(),
                    plan.capabilityPins(), plan.previewCards(), preparation.requiredRiskCodes(),
                    readiness.capabilityId(), readiness.state().name(),
                    readiness.currentInputsHash(), readiness.approvedDecisionHash(),
                    draft.manifest(), now);

            PublishReceipt receipt = PublishReceipt.of(
                    idGenerator.nextId(LifecycleIdKind.PUBLISH_RECEIPT),
                    preparation.tenantId(), preparation.programId(), preparation.userId(),
                    preparation.dataScopeId(),
                    preparation.preparationId(), preparation.preparationHash(),
                    command.idempotencyKey(), requestHash,
                    definition.publishedId(), definition.publishedRevision(),
                    definition.campaignId(), definition.campaignRevision(),
                    definition.sourceDraftId(), definition.sourceDraftRevision(),
                    plan.planId(), plan.planHash(), definition.manifestHash(),
                    definition.contentChecksum(), preparation.requiredRiskCodes(), now);

            LifecycleAuditEvent audit = LifecycleAuditEvent.of(
                    LifecycleAction.PUBLISH, LifecycleDisposition.APPLIED, PUBLISHED_REASON, now, context,
                    LifecycleSupport.fingerprintOf(
                            preparation.campaignId(), preparation.tenantId(), preparation.programId()),
                    plan.planHash(), definition.contentChecksum());

            // The holds are derived from the exact pins the host just re-resolved, keyed by the publication that
            // holds them, so the one transaction acquires them alongside the definition or acquires none.
            List<AudienceRetentionHold> retentionHolds =
                    ReferencePublishCheck.holds(definition, references);

            PublishReceipt committed = store.commitPublish(new PublishCommit(
                    preparation.preparationId(), preparation.preparationHash(),
                    command.idempotencyKey(), requestHash, publishedRevision,
                    definition, receipt, audit, retentionHolds));

            return PublishResult.of(committed);
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

    // ------------------------------------------------------------------ evaluation steps

    private void requireOwned(PublishPreparation preparation, TrustedExecutionContext context) {
        if (!preparation.tenantId().equals(context.tenantId())
                || !preparation.programId().equals(context.programId())
                || !preparation.userId().equals(context.userId())) {
            throw new ResourceNotFoundException();
        }
    }

    private void requirePreparationHash(PublishCommand command, PublishPreparation preparation) {
        if (!LifecycleSupport.constantTimeEquals(
                command.preparationHash(), preparation.preparationHash())) {
            throw new LifecycleException(
                    LifecycleErrorCodes.LIFECYCLE_PREPARATION_MISMATCH,
                    "preparationHash",
                    "The presented preparation hash is not the loaded preparation's own hash.");
        }
    }

    private void requirePublishableState(PublishPreparation preparation, Instant now) {
        PublishPreparationState state = preparation.durableState(
                now, store.isPreparationConsumed(preparation.preparationId()));
        if (state == PublishPreparationState.CONSUMED) {
            throw new LifecycleException(
                    LifecycleErrorCodes.LIFECYCLE_PREPARATION_CONSUMED,
                    "preparationConsumed",
                    "A preparation may be consumed by at most one successful Publish.");
        }
        if (state == PublishPreparationState.EXPIRED) {
            throw new LifecycleException(
                    LifecycleErrorCodes.LIFECYCLE_PREPARATION_EXPIRED,
                    "preparationExpiry",
                    "The preparation is at or past its expiry instant.");
        }
    }

    private void requireSameDataScope(PublishPreparation preparation, TrustedExecutionContext context) {
        if (!preparation.dataScopeId().equals(context.dataScopeId())) {
            // The caller's DataScope is the one frozen with the preparation; a different scope sees different
            // data, so the frozen evidence no longer describes what it would publish.
            throw stale("dataScope");
        }
    }

    private SavedCampaignDraft requireCurrentDraft(PublishPreparation preparation) {
        SavedCampaignDraft draft = store.findDraft(preparation.campaignId())
                .orElseThrow(() -> stale("savedDraft"));
        if (!draft.draftId().equals(preparation.draftId())
                || draft.draftRevision() != preparation.draftRevision()
                || draft.campaignRevision() != preparation.campaignRevision()
                || !draft.manifestHash().equals(preparation.manifestHash())
                || !draft.draftHash().equals(preparation.draftHash())
                || !Objects.equals(draft.forkBase(), preparation.forkBase())) {
            throw stale("draftRevision");
        }
        return draft;
    }

    private CampaignPlan requireSourcePlan(SavedCampaignDraft draft, PublishPreparation preparation) {
        CampaignPlan plan = planStore.find(draft.sourcePlanId()).orElseThrow(() -> stale("sourcePlan"));
        if (!plan.planId().equals(preparation.planId())
                || !plan.planHash().equals(preparation.planHash())
                || !plan.manifestHash().equals(preparation.manifestHash())) {
            throw stale("planBinding");
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
            throw stale("frozenCompatibility");
        }
        if (decision == null || decision.outcome() == null) {
            throw stale("frozenCompatibility");
        }
        if (decision.outcome() != CompatibilityOutcome.COMPATIBLE) {
            throw stale("frozenCompatibility");
        }
    }

    private void requireValidated(CampaignPlan plan, TrustedExecutionContext context) {
        if (LifecycleSupport.validateFinalManifest(validationPort, plan.manifest(), context)
                != LifecycleSupport.ManifestValidationOutcome.VALID) {
            // A blocking issue and an untrustworthy answer are the same outward stale preparation here: the
            // frozen evidence no longer describes material this boundary would accept.
            throw stale("finalManifest");
        }
    }

    /**
     * Re-proves that the exact activation evidence the preparation froze is still the activation evidence in
     * force.
     *
     * <p>The accepted guard has already proved that the current host-owned projection activates this exact
     * compiled Plan; this step additionally proves the projection is the <em>same</em> one the preparation
     * froze, so a re-approved decision or moved Gate inputs make the preparation stale rather than quietly
     * publishing under evidence the caller never saw.
     */
    private void requireSameActivation(
            PublishPreparation preparation, SegmentReadinessProjection readiness) {
        ContentHash currentInputs = readiness.currentInputsHash();
        if (!readiness.capabilityId().equals(preparation.readinessCapability())
                || !readiness.state().name().equals(preparation.readinessState())
                || currentInputs == null
                || !currentInputs.equals(preparation.gateInputsHash())
                || !Objects.equals(readiness.approvedDecisionHash(), preparation.approvedDecisionHash())) {
            throw stale("activationDrift");
        }
    }

    /**
     * Proves each acknowledgement is a bounded machine code and that none is repeated.
     *
     * <p>This is deliberately separate from, and earlier than, the comparison with the preparation: a
     * repeated acknowledgement is an inconsistent request in its own right, and it must be refused before the
     * idempotency index is consulted so that it can neither collapse onto an accepted request's hash nor be
     * replayed as one once the preparation has been consumed.
     */
    private void requireWellFormedConfirmations(PublishCommand command) {
        Set<String> seen = new LinkedHashSet<>();
        for (String code : command.confirmations()) {
            if (code == null || !MACHINE_CODE.matcher(code).matches()) {
                throw confirmationMismatch("confirmationCode");
            }
            if (!seen.add(code)) {
                // A repeated acknowledgement is not "the exact required risks"; it is an inconsistent request.
                throw confirmationMismatch("confirmationRepeat");
            }
        }
    }

    private void requireExactConfirmations(PublishCommand command, PublishPreparation preparation) {
        if (!preparation.requiresExactly(new LinkedHashSet<>(command.confirmations()))) {
            throw confirmationMismatch("confirmationSet");
        }
    }

    // ------------------------------------------------------------------ audit

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
        store.recordCommandAudit(LifecycleAuditEvent.of(
                LifecycleAction.PUBLISH, disposition, LifecycleSupport.reasonOf(failure),
                clock.instant(), context, fingerprint, planHash, null));
    }

    // ------------------------------------------------------------------ helpers

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static LifecycleException invalidCommand() {
        return new LifecycleException(
                LifecycleErrorCodes.LIFECYCLE_COMMAND_INVALID,
                "command",
                "A Publish confirmation requires a preparation, its hash, an idempotency key and its risks.");
    }

    private static LifecycleException stale(String invariant) {
        return new LifecycleException(
                LifecycleErrorCodes.LIFECYCLE_PREPARATION_STALE,
                invariant,
                "The preparation's frozen evidence no longer matches the state it would publish.");
    }

    private static LifecycleException confirmationMismatch(String invariant) {
        return new LifecycleException(
                LifecycleErrorCodes.LIFECYCLE_CONFIRMATION_MISMATCH,
                invariant,
                "The confirmations are not exactly the risk codes the preparation froze.");
    }
}