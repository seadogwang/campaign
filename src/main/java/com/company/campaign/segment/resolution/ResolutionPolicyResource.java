package com.company.campaign.segment.resolution;

import java.util.Map;

/** External authoring form for a versioned definition-resolution policy. */
public record ResolutionPolicyResource(
        String policyVersion,
        String reportingCurrencyDefaultId,
        Map<String, String> conceptDefinitionIds
) {

    /** Publishes the resource through the policy validation and hashing boundary. */
    public ResolutionPolicy publish() {
        return ResolutionPolicy.publish(policyVersion, reportingCurrencyDefaultId, conceptDefinitionIds);
    }
}
