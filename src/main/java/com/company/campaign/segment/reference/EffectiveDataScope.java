package com.company.campaign.segment.reference;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.regex.Pattern;

/**
 * An immutable, host-computed effective DataScope: one opaque scope identity plus the hash of its exact
 * contents.
 *
 * <p>An effective scope can only ever come from a trusted port. It is never built from a caller-supplied
 * string, never copied from a parent or a referenced version unchanged, and never spelled by concatenating
 * identifiers: a real intersection produces a new scope identity and a new content hash, and the hash is what
 * downstream planning freezes.
 *
 * <p>Carrying the hash as well as the identity is what makes the value a proof rather than a label: two
 * scopes with the same identity but different contents compare unequal, so a widened scope cannot be passed
 * off as a narrower one under a familiar name.
 */
public record EffectiveDataScope(String dataScopeId, ContentHash dataScopeHash) {

    private static final Pattern REFERENCE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public EffectiveDataScope {
        if (dataScopeId == null || !REFERENCE_ID.matcher(dataScopeId).matches()) {
            throw ReferenceException.invalid("An effective DataScope requires a bounded host-resolved identity");
        }
        if (dataScopeHash == null) {
            throw ReferenceException.invalid("An effective DataScope requires the hash of its exact contents");
        }
    }
}