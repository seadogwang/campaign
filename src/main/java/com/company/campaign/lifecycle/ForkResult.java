package com.company.campaign.lifecycle;

import com.company.campaign.builder.compiler.DraftIdentity;
import com.company.campaign.builder.compiler.PublishedIdentity;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.Objects;

/**
 * The safe outward result of one accepted host fork of a Published Definition.
 *
 * <p>It carries the new editable Draft identity and revision, the Campaign identity and revision they belong
 * to, the exact immutable fork base the new Draft descends from and the content hash of the new Draft. It
 * carries no Manifest, component payload or trusted identifier beyond the identities the caller itself
 * addressed: the forked content stays server side and is reachable only by hash.
 */
public record ForkResult(
        String campaignId,
        long campaignRevision,
        DraftIdentity draftIdentity,
        PublishedIdentity forkBase,
        ContentHash draftHash
) {

    public ForkResult {
        Objects.requireNonNull(campaignId, "campaignId");
        Objects.requireNonNull(draftIdentity, "draftIdentity");
        Objects.requireNonNull(forkBase, "forkBase");
        Objects.requireNonNull(draftHash, "draftHash");
        if (campaignRevision < 1) {
            throw new IllegalArgumentException("a campaign revision must be positive");
        }
    }

    @Override
    public String toString() {
        return "ForkResult[draftRevision=" + draftIdentity.revision()
                + ", campaignRevision=" + campaignRevision
                + ", forkBaseRevision=" + forkBase.revision()
                + ", draftHash=" + draftHash + ']';
    }
}