package com.company.campaign.fullpublish;

import com.company.campaign.shared.canonical.ContentHash;

import java.util.Optional;

/**
 * The host-only port that resolves the frozen Journey topology and capability pack index of one saved Draft.
 *
 * <p>It exists because a Journey's edges are deliberately not part of the T19 node payloads: the host owns the
 * exact topology of the Draft revision being published and hands it to the compiler, exactly as it owns the
 * frozen pack index the compile is decided against.
 *
 * <p>The port is asked only for the exact Draft revision and Manifest hash already frozen by the accepted T17
 * preparation, so a topology that no longer describes that revision cannot be silently substituted. An
 * absent, thrown or mismatched answer fails the operation closed.
 */
@FunctionalInterface
public interface JourneyPublishSourcePort {

    /**
     * The frozen Journey source of one exact saved Draft revision, or empty when the host owns none.
     *
     * @param campaignId the server-resolved Campaign identifier
     * @param draftId the exact saved Draft identifier
     * @param draftRevision the exact saved Draft revision
     * @param manifestHash the exact Manifest hash the Draft revision froze
     */
    Optional<JourneyPublishSource> currentSource(
            String campaignId, String draftId, long draftRevision, ContentHash manifestHash);
}