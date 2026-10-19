package com.company.campaign.builder.campaignagent;

import com.company.campaign.contracts.ContractPatterns;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** One host-registered business resource exposed to Campaign authoring. */
public record CampaignBusinessResource(
        CampaignBusinessResourceType type,
        String code,
        String displayName,
        List<String> aliases,
        boolean enabled,
        boolean runtimeAvailable,
        String integrationKey) {

    private static final Pattern CODE = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public CampaignBusinessResource {
        if (type == null || code == null || !CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("A Campaign business resource requires a bounded type and code.");
        }
        if (displayName == null || displayName.isBlank()
                || displayName.length() > CampaignAgentLimits.MAX_OPTION_LABEL_LENGTH) {
            throw new IllegalArgumentException("A Campaign business resource requires a bounded display name.");
        }
        CampaignRoutingVocabulary.requireSafeCopy(displayName, "businessResourceDisplayName");
        Set<String> normalized = new LinkedHashSet<>();
        normalized.add(code.toLowerCase(Locale.ROOT));
        if (aliases != null) {
            for (String alias : aliases) {
                if (alias == null || alias.isBlank() || alias.length() > 96) {
                    throw new IllegalArgumentException("A Campaign business resource alias must be bounded.");
                }
                normalized.add(alias.toLowerCase(Locale.ROOT));
            }
        }
        aliases = List.copyOf(new ArrayList<>(normalized));
        if (integrationKey != null && (integrationKey.isBlank() || !CODE.matcher(integrationKey).matches())) {
            throw new IllegalArgumentException("A Campaign business resource integration key must be bounded.");
        }
    }

    /** True only when business configuration and the backing runtime integration are both available. */
    public boolean selectable() {
        return enabled && runtimeAvailable && integrationKey != null;
    }

    /** Whether bounded user evidence names this exact registered resource. */
    public boolean namedBy(String evidence) {
        if (evidence == null || evidence.isBlank()) {
            return false;
        }
        String normalized = evidence.toLowerCase(Locale.ROOT);
        for (String alias : aliases) {
            if (normalized.contains(alias)) {
                return true;
            }
        }
        return false;
    }
}
