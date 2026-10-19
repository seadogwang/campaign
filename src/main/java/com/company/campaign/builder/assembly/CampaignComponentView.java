package com.company.campaign.builder.assembly;

import com.company.campaign.shared.canonical.ContentHash;

/**
 * One bounded inspection fact about one staged component.
 *
 * <p>It carries only the component identity, the owning Skill, the capability code, the operation kind and the
 * accepted artifact hash. It deliberately cannot carry a capability payload, a template body, a supplied
 * variable value, a reward secret, a Provider fact, a credential, a member identifier, SQL or raw exception
 * text: none of those is representable in this record.
 */
public record CampaignComponentView(
        String componentId,
        String skillId,
        String capabilityCode,
        String operationKind,
        ContentHash artifactHash
) {

    public CampaignComponentView {
        if (componentId == null || skillId == null || operationKind == null || artifactHash == null) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.REQUEST_INVALID,
                    "A component inspection view requires its bounded identity facts.");
        }
    }

    /** The bounded inspection view of one staged component. */
    public static CampaignComponentView of(CampaignStagedComponent component) {
        return new CampaignComponentView(
                component.componentId(), component.skillId(), component.capabilityCode(),
                component.operationKind().name(), component.artifactHash());
    }
}