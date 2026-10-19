package com.company.campaign.segment.reference;

import com.company.campaign.shared.canonical.ContentHash;

/**
 * The host-owned reference-discovery context: the directory itself plus the exact revision it was read at.
 *
 * <p>The revision pins are not decoration. A clarification that offered version choices was produced against
 * one exact directory state, so a later answer can be compared against the same state instead of being applied
 * to a directory that has meanwhile published another version. The pins are host facts: no document, model
 * payload or client flag supplies, overrides or widens them.
 *
 * <p>Nothing here is reachable from an Intent, a DSL document or a model payload, which is why an Intent that
 * asks for an existing audience and no directory context fails closed rather than degrading to "no directory,
 * so the first guess is fine".
 *
 * @param directory       the host-owned read-only discovery directory; never a writable or self-authorizing one
 * @param directoryVersion the bounded version identity of the exact directory state that was read
 * @param directoryHash   the canonical content hash of that exact directory state
 */
public record AudienceReferenceDirectoryContext(
        AudienceReferenceDirectory directory,
        String directoryVersion,
        ContentHash directoryHash
) {

    /** The largest bounded directory version text this contract accepts. */
    public static final int MAX_DIRECTORY_VERSION_LENGTH = 128;

    public AudienceReferenceDirectoryContext {
        if (directory == null) {
            throw ReferenceException.invalid("A reference directory context requires the host directory");
        }
        if (directoryVersion == null || directoryVersion.isBlank()
                || directoryVersion.length() > MAX_DIRECTORY_VERSION_LENGTH) {
            throw ReferenceException.invalid("A reference directory context requires a bounded version");
        }
        if (directoryHash == null) {
            throw ReferenceException.invalid("A reference directory context requires its content hash");
        }
    }
}