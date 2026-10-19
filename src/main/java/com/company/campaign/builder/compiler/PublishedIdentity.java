package com.company.campaign.builder.compiler;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.regex.Pattern;

/**
 * The immutable Published version a forked Draft descends from.
 *
 * <p>Published content is never edited in place. When an EDIT starts from a published Campaign, the Draft is
 * a fork and this value records the fork base: which immutable version was copied and its content hash. It
 * is recorded fact, not an editing target — a compile that would edit the published identity itself is
 * refused before merge.
 */
public record PublishedIdentity(String publishedId, long revision, ContentHash contentHash) {

    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public PublishedIdentity {
        if (publishedId == null || !IDENTIFIER.matcher(publishedId).matches()) {
            throw new IllegalArgumentException("publishedId must be a bounded canonical identifier");
        }
        if (revision < 1) {
            throw new IllegalArgumentException("published revision must be positive");
        }
        if (contentHash == null) {
            throw new IllegalArgumentException("published content hash is required");
        }
    }

    @Override
    public String toString() {
        return "PublishedIdentity[publishedId=" + publishedId + ", revision=" + revision
                + ", contentHash=" + contentHash + ']';
    }
}