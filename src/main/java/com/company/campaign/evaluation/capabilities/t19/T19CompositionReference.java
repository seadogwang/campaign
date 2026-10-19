package com.company.campaign.evaluation.capabilities.t19;

import com.company.campaign.contracts.ContractPatterns;

import java.util.regex.Pattern;

/**
 * One resolved catalog reference of a composition fixture component.
 *
 * <p>It is the fixture-level mirror of the compiler's resolved reference: a bounded reference code, an
 * uppercase entity kind, and the exact logical/version identity the frozen catalog pinned. A composition
 * fixture uses it to prove that a T19 component carrying a Segment reference fails closed.
 */
public record T19CompositionReference(
        String referenceCode,
        String entityKind,
        String logicalId,
        String versionId) {

    private static final Pattern CODE = Pattern.compile(ContractPatterns.REFERENCE_ID);
    private static final Pattern KIND = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    public T19CompositionReference {
        if (referenceCode == null || !CODE.matcher(referenceCode).matches()
                || logicalId == null || !CODE.matcher(logicalId).matches()
                || versionId == null || !CODE.matcher(versionId).matches()) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.ASSET_NON_CANONICAL,
                    "A composition reference requires bounded canonical identifiers.");
        }
        if (entityKind == null || !KIND.matcher(entityKind).matches()) {
            throw new T19EvaluationException(
                    T19EvaluationErrorCodes.ASSET_NON_CANONICAL,
                    "A composition reference requires a bounded uppercase entity kind.");
        }
    }
}