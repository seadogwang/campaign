package com.company.campaign.builder.campaignagent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Immutable result of the host resource-discovery Tool for one trusted tenant and program. */
public final class CampaignBusinessResourceCatalog {

    private final List<CampaignBusinessResource> resources;

    public CampaignBusinessResourceCatalog(List<CampaignBusinessResource> resources) {
        List<CampaignBusinessResource> copy = resources == null
                ? new ArrayList<>()
                : new ArrayList<>(resources);
        copy.sort(Comparator.comparing(CampaignBusinessResource::type)
                .thenComparing(CampaignBusinessResource::code));
        Set<String> identities = new HashSet<>();
        for (CampaignBusinessResource resource : copy) {
            if (resource == null || !identities.add(resource.type().name() + ':' + resource.code())) {
                throw new IllegalArgumentException("Campaign business resources must have unique identities.");
            }
        }
        this.resources = List.copyOf(copy);
    }

    /** The fail-closed catalog returned when no discovery Tool is wired. */
    public static CampaignBusinessResourceCatalog empty() {
        return new CampaignBusinessResourceCatalog(List.of());
    }

    /** Every selectable resource of one kind, in stable code order. */
    public List<CampaignBusinessResource> selectable(CampaignBusinessResourceType type) {
        return resources.stream()
                .filter(resource -> resource.type() == type && resource.selectable())
                .toList();
    }

    /** The selectable registered resource with this exact code, or null. */
    public CampaignBusinessResource selectable(CampaignBusinessResourceType type, String code) {
        if (type == null || code == null) {
            return null;
        }
        return resources.stream()
                .filter(resource -> resource.type() == type && resource.selectable()
                        && resource.code().equals(code))
                .findFirst()
                .orElse(null);
    }

    /** Every registered resource of one kind named by user evidence, including disabled integrations. */
    public List<CampaignBusinessResource> named(CampaignBusinessResourceType type, String evidence) {
        if (type == null || evidence == null) {
            return List.of();
        }
        return resources.stream()
                .filter(resource -> resource.type() == type && resource.namedBy(evidence))
                .toList();
    }

    /** Host-owned answer options for currently usable resources. */
    public List<CampaignAnswerOption> options(CampaignBusinessResourceType type) {
        List<CampaignAnswerOption> options = new ArrayList<>();
        for (CampaignBusinessResource resource : selectable(type)) {
            options.add(new CampaignAnswerOption(resource.code(), resource.displayName()));
            if (options.size() == CampaignAgentLimits.MAX_OPTIONS) {
                break;
            }
        }
        return List.copyOf(options);
    }
}
