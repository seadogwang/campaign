package com.company.campaign.lifecycle;

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
 * The explicit host-only fork boundary: it turns one immutable {@link PublishedCampaignDefinition} into a
 * new editable {@link SavedCampaignDraft}.
 *
 * <p>Published bytes never change. Editing a published Campaign is therefore not an edit at all but a fork:
 * the boundary reads the immutable definition, allocates a <em>new</em> Draft identity server-side, and
 * records the definition's own {@code PublishedIdentity} as the new Draft's fork base. The definition itself
 * is never written, so the version that was published keeps byte-identical content and checksum forever.
 *
 * <p>Evaluation order, all derived from trusted host state rather than the command:
 *
 * <ol>
 *   <li>a structurally invalid command is refused;</li>
 *   <li>the Published Definition is loaded by identifier; an absent one and one owned by another tenant,
 *       program or user share the identical non-disclosing not-found failure;</li>
 *   <li>the exact tenant, program and user ownership is proved <em>before</em> any permission decision;</li>
 *   <li>the exact published revision the caller named is re-proved;</li>
 *   <li>{@code campaign.edit} is authorized against the definition's own server-resolved ownership;</li>
 *   <li>the Campaign's current editable Draft is loaded, and its exact identity, revision and content hash
 *       become the compare-and-set token of the commit, which re-proves them inside the transaction;</li>
 *   <li>the forked Draft and its safe audit event are committed as one unit.</li>
 * </ol>
 *
 * <p>Exactly one bounded audit event is recorded for every command the boundary evaluates, and an audit
 * failure fails the operation closed. A command presented without its trusted context still fails closed
 * with a stable code, and when a rejection's own audit also fails the bounded reason stays primary.
 */
public final class PublishedForkService {

    /** The audit reason code recorded for the one disposition that commits material. */
    static final String FORKED_REASON = "LIFECYCLE_FORKED";

    private final AuthorizationService authorizationService;
    private final LifecycleStore store;
    private final LifecycleIdGenerator idGenerator;
    private final Clock clock;

    public PublishedForkService(
            AuthorizationService authorizationService,
            LifecycleStore store,
            LifecycleIdGenerator idGenerator,
            Clock clock) {
        this.authorizationService = Objects.requireNonNull(authorizationService, "authorizationService");
        this.store = Objects.requireNonNull(store, "store");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Forks one immutable Published Definition into a new editable Draft, or fails closed.
     *
     * @throws LifecycleException for a bounded lifecycle rejection
     * @throws ResourceNotFoundException when the Published Definition is absent or outside the caller's
     *     boundary
     * @throws PermissionDeniedException when the caller is inside the boundary but lacks {@code campaign.edit}
     */
    public ForkResult fork(ForkPublishedCommand command, TrustedExecutionContext context) {
        if (context == null) {
            throw LifecycleSupport.missingTrustedContext();
        }

        PublishedCampaignDefinition definition = null;
        boolean ownDefinition = false;
        try {
            if (command == null || isBlank(command.publishedId()) || command.publishedRevision() < 1L) {
                throw invalidCommand();
            }

            definition = store.findPublished(command.publishedId())
                    .orElseThrow(ResourceNotFoundException::new);
            requireOwned(definition, context);
            ownDefinition = true;

            requirePublishedIdentity(command, definition);

            authorizationService.requireAuthorized(new AuthorizationRequest(
                    context,
                    new OwnedResourceRef(
                            ResourceKind.CAMPAIGN, definition.campaignId(),
                            definition.tenantId(), definition.programId())));

            SavedCampaignDraft current = store.findDraft(definition.campaignId())
                    .orElseThrow(ResourceNotFoundException::new);

            Instant now = clock.instant();
            SavedCampaignDraft forked = SavedCampaignDraft.of(
                    definition.tenantId(), definition.programId(), definition.userId(),
                    definition.campaignId(), current.campaignRevision() + 1L,
                    idGenerator.nextId(LifecycleIdKind.DRAFT), 1L,
                    definition.identity(), definition.manifest(), definition.planId(), now);

            LifecycleAuditEvent audit = LifecycleAuditEvent.of(
                    LifecycleAction.FORK_PUBLISHED, LifecycleDisposition.APPLIED, FORKED_REASON, now,
                    context,
                    LifecycleSupport.fingerprintOf(
                            definition.campaignId(), definition.tenantId(), definition.programId()),
                    definition.planHash(), forked.draftHash());

            // The exact Draft observed before the fork travels with the commit, so the transaction re-proves
            // it replaced the Draft it read rather than one a concurrent Apply or fork wrote in between.
            SavedCampaignDraft committed = store.commitFork(new ForkCommit(
                    definition.campaignId(), current.campaignRevision(), current.draftIdentity(),
                    current.draftHash(), forked, audit));

            return new ForkResult(
                    committed.campaignId(), committed.campaignRevision(), committed.draftIdentity(),
                    committed.forkBase(), committed.draftHash());
        } catch (RuntimeException failure) {
            try {
                recordRejection(context, definition, ownDefinition, failure);
            } catch (RuntimeException auditFailure) {
                // The bounded rejection stays primary; the failing sink is evidence attached to it.
                failure.addSuppressed(auditFailure);
            }
            throw failure;
        }
    }

    // ------------------------------------------------------------------ evaluation steps

    private void requireOwned(PublishedCampaignDefinition definition, TrustedExecutionContext context) {
        if (!definition.tenantId().equals(context.tenantId())
                || !definition.programId().equals(context.programId())
                || !definition.userId().equals(context.userId())) {
            throw new ResourceNotFoundException();
        }
    }

    private void requirePublishedIdentity(
            ForkPublishedCommand command, PublishedCampaignDefinition definition) {
        if (!definition.publishedId().equals(command.publishedId())
                || definition.publishedRevision() != command.publishedRevision()) {
            throw new LifecycleException(
                    LifecycleErrorCodes.LIFECYCLE_PUBLISHED_MISMATCH,
                    "publishedRevision",
                    "The published identity the command named is not the loaded definition's own identity.");
        }
    }

    // ------------------------------------------------------------------ audit

    private void recordRejection(
            TrustedExecutionContext context,
            PublishedCampaignDefinition definition,
            boolean ownDefinition,
            RuntimeException failure) {
        LifecycleDisposition disposition = LifecycleSupport.dispositionOf(failure);
        AuditResourceFingerprint fingerprint = null;
        ContentHash planHash = null;
        ContentHash contentHash = null;
        if (ownDefinition && definition != null && disposition != LifecycleDisposition.NOT_FOUND) {
            fingerprint = LifecycleSupport.fingerprintOf(
                    definition.campaignId(), definition.tenantId(), definition.programId());
            planHash = definition.planHash();
            contentHash = definition.contentChecksum();
        }
        store.recordCommandAudit(LifecycleAuditEvent.of(
                LifecycleAction.FORK_PUBLISHED, disposition, LifecycleSupport.reasonOf(failure),
                clock.instant(), context, fingerprint, planHash, contentHash));
    }

    // ------------------------------------------------------------------ helpers

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static LifecycleException invalidCommand() {
        return new LifecycleException(
                LifecycleErrorCodes.LIFECYCLE_COMMAND_INVALID,
                "command",
                "A fork command requires a Published Definition identity and its exact revision.");
    }
}