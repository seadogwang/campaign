package com.company.campaign.builder.journey;

import com.company.campaign.contracts.ContractPatterns;

import java.util.regex.Pattern;

/**
 * One declared cross-component reference target of a Journey authoring request.
 *
 * <p>A Flow Message or Flow Reward node may reference a component the same Campaign authoring request creates.
 * At Journey-authoring time that target has no final identity yet, so the request declares its reference and its
 * exact capability; the authoring graph then re-proves that a node which names it really does name a component of
 * that capability. Nothing is staged from a declaration: it is a validation scaffold, not a component.
 */
public record JourneyReferenceDeclaration(String ref, String capabilityCode) {

    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);
    private static final Pattern CAPABILITY = Pattern.compile("^[a-z][a-z0-9.-]{0,63}$");

    public JourneyReferenceDeclaration {
        if (ref == null || !IDENTIFIER.matcher(ref).matches()) {
            throw new JourneyAuthoringException(JourneyAuthoringErrorCodes.REFERENCE_INVALID);
        }
        if (capabilityCode == null || !CAPABILITY.matcher(capabilityCode).matches()) {
            throw new JourneyAuthoringException(JourneyAuthoringErrorCodes.REFERENCE_INVALID);
        }
    }

    /** Redacted diagnostics: the declaration identity and its capability only. */
    @Override
    public String toString() {
        return "JourneyReferenceDeclaration[ref=" + ref + ", capabilityCode=" + capabilityCode + ']';
    }
}