package com.company.campaign.builder.compiler;

import com.company.campaign.contracts.ContractPatterns;

import java.util.regex.Pattern;

/**
 * The identity and exact revision of the one editable Draft a Campaign owns.
 *
 * <p>An EDIT compile binds to this Draft and this revision. The Draft revision is the concurrency token: a
 * proposal written against an older revision is stale and must be recompiled rather than merged onto
 * whatever the Draft happens to contain now.
 */
public record DraftIdentity(String draftId, long revision) {

    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public DraftIdentity {
        if (draftId == null || !IDENTIFIER.matcher(draftId).matches()) {
            throw new IllegalArgumentException("draftId must be a bounded canonical identifier");
        }
        if (revision < 1) {
            throw new IllegalArgumentException("draft revision must be positive");
        }
    }

    @Override
    public String toString() {
        return "DraftIdentity[draftId=" + draftId + ", revision=" + revision + ']';
    }
}