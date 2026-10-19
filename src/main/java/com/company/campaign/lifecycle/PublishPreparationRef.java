package com.company.campaign.lifecycle;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.regex.Pattern;

/**
 * The host-only reference a Publish confirmation presents: a preparation identifier and its exact hash.
 *
 * <p>It is the publish-phase analogue of the Apply command's {@code (planId, planHash)} pair and is
 * deliberately the <em>only</em> thing a confirmation identifies. It carries no Draft, Manifest, Published
 * Definition, readiness projection, version pin or ownership fact, so none of those can be supplied — or
 * overridden — by a caller. The boundary loads the server-stored preparation by identifier and re-proves
 * the hash in constant time before it looks at anything else.
 */
public record PublishPreparationRef(String preparationId, ContentHash preparationHash) {

    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public PublishPreparationRef {
        if (preparationId == null || !IDENTIFIER.matcher(preparationId).matches()) {
            throw new IllegalArgumentException("preparationId must be a bounded canonical identifier");
        }
        if (preparationHash == null) {
            throw new IllegalArgumentException("preparationHash is required");
        }
    }

    /** Redacted diagnostics: only the identifier and hash, both of which are already opaque. */
    @Override
    public String toString() {
        return "PublishPreparationRef[preparationId=" + preparationId
                + ", preparationHash=" + preparationHash + ']';
    }
}