package com.company.campaign.builder.journey;

import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.contracts.ContractPatterns;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One typed node declaration of a Journey authoring request.
 *
 * <p>A node is a stable reference the caller chose, the exact Flow node capability code it declares, and the
 * actual bounded {@link CapabilityPayload} that node's accepted T19 Flow pack must normalize. There is no
 * free-form map, no raw JSON, no callback and no label synonym: the capability code and the payload contract
 * together decide whether the declaration is representable at all.
 */
public record JourneyNodeDeclaration(String ref, String capabilityCode, CapabilityPayload payload) {

    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);
    private static final Pattern CAPABILITY = Pattern.compile("^[a-z][a-z0-9.-]{0,63}$");

    public JourneyNodeDeclaration {
        if (ref == null || !IDENTIFIER.matcher(ref).matches()) {
            throw new JourneyAuthoringException(JourneyAuthoringErrorCodes.NODE_INVALID);
        }
        if (capabilityCode == null || !CAPABILITY.matcher(capabilityCode).matches()) {
            throw new JourneyAuthoringException(JourneyAuthoringErrorCodes.NODE_INVALID);
        }
        Objects.requireNonNull(payload, "payload");
    }

    /** Redacted diagnostics: the declaration identity and its capability only, never a payload value. */
    @Override
    public String toString() {
        return "JourneyNodeDeclaration[ref=" + ref + ", capabilityCode=" + capabilityCode + ']';
    }
}