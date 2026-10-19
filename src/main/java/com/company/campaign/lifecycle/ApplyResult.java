package com.company.campaign.lifecycle;

import com.company.campaign.builder.compiler.ClientRefBinding;
import com.company.campaign.builder.compiler.DraftIdentity;
import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The safe outward result of one accepted host Apply.
 *
 * <p>It carries exactly what an editor needs to address the object it just saved — the Campaign and Draft
 * identities at their exact revisions, the Manifest and Draft content hashes and the applied instant — plus
 * the compiler-produced {@code clientRef -> logicalId} bindings the caller's own proposal declared. It
 * carries no Manifest, ChangeSet, component payload, Preview member or trusted identifier.
 *
 * <p>The immutable {@link ApplyReceipt} stays server side: the receipt exists so a repeated Apply can return
 * its original idempotency witness, and it covers the caller's trusted ownership in its own checksum, but
 * nothing here re-exposes the tenant, program or user it carries. This projection is built only from a
 * receipt the boundary itself produced.
 */
public record ApplyResult(
        String campaignId,
        long campaignRevision,
        String draftId,
        long draftRevision,
        ContentHash manifestHash,
        ContentHash draftHash,
        Instant appliedAt,
        List<ClientRefBinding> clientRefBindings
) {

    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public ApplyResult {
        requireIdentifier(campaignId, "campaignId");
        requireIdentifier(draftId, "draftId");
        if (campaignRevision < 1L || draftRevision < 1L) {
            throw new IllegalArgumentException("an Apply result requires its exact positive revisions");
        }
        Objects.requireNonNull(manifestHash, "manifestHash");
        Objects.requireNonNull(draftHash, "draftHash");
        Objects.requireNonNull(appliedAt, "appliedAt");
        clientRefBindings = List.copyOf(Objects.requireNonNull(clientRefBindings, "clientRefBindings"));
    }

    /** Builds the safe outward result of one receipt the lifecycle boundary produced. */
    public static ApplyResult of(ApplyReceipt receipt, List<ClientRefBinding> clientRefBindings) {
        Objects.requireNonNull(receipt, "receipt");
        return new ApplyResult(
                receipt.campaignId(), receipt.campaignRevision(), receipt.draftId(), receipt.draftRevision(),
                receipt.manifestHash(), receipt.contentHash(), receipt.appliedAt(), clientRefBindings);
    }

    /** The exact editable Draft identity and revision this Apply produced. */
    public DraftIdentity draftIdentity() {
        return new DraftIdentity(draftId, draftRevision);
    }

    private static void requireIdentifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("an Apply result requires a bounded " + field);
        }
    }

    @Override
    public String toString() {
        return "ApplyResult[campaignRevision=" + campaignRevision
                + ", draftRevision=" + draftRevision
                + ", bindings=" + clientRefBindings.size()
                + ", draftHash=" + draftHash + ']';
    }
}