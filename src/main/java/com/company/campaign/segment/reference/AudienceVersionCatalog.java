package com.company.campaign.segment.reference;

import java.util.Optional;

/**
 * Host-only port that resolves one exact immutable Audience Version.
 *
 * <p>This is the only place trusted reference facts can come from. It answers one question and nothing else:
 * "does the exact version with this audience identity, version identity and content hash exist?" There is
 * deliberately no {@code findCurrent}, {@code findLatest}, {@code findByAudience} or name lookup, so a caller
 * — and therefore a model payload — cannot ask for a moving target. A version is only ever reached through
 * its full pin.
 *
 * <p>An empty result means "not visible", which covers a version that does not exist and one another tenant
 * owns. The port does not distinguish them: the resolver maps both onto the same non-enumerating outward
 * failure, so existence can never be probed across a tenant boundary. A version whose stored content hash
 * differs from the pin is returned as-is; detecting that conflict is the resolver's job, because only the
 * resolver knows which pin the document actually declared.
 *
 * <p>Implementations must be safe for concurrent use and must never mutate published material.
 */
public interface AudienceVersionCatalog {

    /**
     * The exact version this pin names, or empty when it is not visible to this catalog.
     *
     * @param reference the exact audience/version/content-hash pin; never a mutable pointer
     */
    Optional<AudienceVersionSnapshot> findExact(AudienceVersionRef reference);
}