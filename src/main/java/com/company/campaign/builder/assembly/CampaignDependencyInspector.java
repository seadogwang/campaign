package com.company.campaign.builder.assembly;

import com.company.campaign.builder.capability.flow.FlowMessagePack;
import com.company.campaign.builder.capability.flow.FlowPackVersions;
import com.company.campaign.builder.capability.flow.FlowRewardPack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The bounded missing-dependency inspection of an ephemeral Campaign assembly.
 *
 * <p>It reports only the dependency facts a selectable Skill layer needs <em>before</em> a compile:
 *
 * <ul>
 *   <li>a staged {@code promotion.reference} component with no staged {@code governance.limit} component;</li>
 *   <li>a staged Flow Message or Flow Reward node whose declared reference names a component identity the
 *       snapshot does not hold.</li>
 * </ul>
 *
 * <p>It deliberately duplicates none of the accepted Capability Pack, composition-validator or accepted
 * compiler validation: it reads exactly the one declared reference field of the two Flow node contracts that
 * have one, decides no graph question, produces no Manifest, ChangeSet or Plan, and never rejects a snapshot.
 * It is a pure, deterministic function of the staged components alone, and it emits only fixed codes so no
 * identity or value can leak through it.
 */
public final class CampaignDependencyInspector {

    private CampaignDependencyInspector() {
    }

    /** The distinct missing-dependency codes of one staged component set, in canonical order. */
    public static List<String> inspect(List<CampaignStagedComponent> components) {
        List<CampaignStagedComponent> staged = components == null ? List.of() : components;
        Set<String> codes = new LinkedHashSet<>();
        Set<String> identities = new LinkedHashSet<>();
        boolean promotion = false;
        boolean governance = false;
        for (CampaignStagedComponent component : staged) {
            identities.add(component.componentId());
            if (CampaignSkillIds.PROMOTION_REFERENCE.equals(component.capabilityCode())) {
                promotion = true;
            }
            if (CampaignSkillIds.GOVERNANCE_LIMIT.equals(component.capabilityCode())) {
                governance = true;
            }
        }
        if (promotion && !governance) {
            codes.add(CampaignAssemblyErrorCodes.MISSING_GOVERNANCE_LIMIT);
        }
        for (CampaignStagedComponent component : staged) {
            String referenceField = flowReferenceField(component.capabilityCode());
            if (referenceField == null) {
                continue;
            }
            Object declared = component.operation().value() == null
                    ? null
                    : component.operation().value().fields().get(referenceField);
            if (declared instanceof String reference && !identities.contains(reference)) {
                codes.add(CampaignAssemblyErrorCodes.FLOW_REFERENCE_UNRESOLVED);
            }
        }
        List<String> ordered = new ArrayList<>(codes);
        ordered.sort(null);
        return List.copyOf(ordered);
    }

    /**
     * The one declared reference field of a Flow node capability, or null when it declares none.
     *
     * <p>Only the two accepted Flow node contracts that carry a cross-component reference are readable here;
     * every other node is inspected by the accepted T20 graph contract, not by this bounded layer.
     */
    private static String flowReferenceField(String capabilityCode) {
        if (FlowPackVersions.MESSAGE.equals(capabilityCode)) {
            return FlowMessagePack.REFERENCE_FIELD;
        }
        if (FlowPackVersions.REWARD.equals(capabilityCode)) {
            return FlowRewardPack.REFERENCE_FIELD;
        }
        return null;
    }
}