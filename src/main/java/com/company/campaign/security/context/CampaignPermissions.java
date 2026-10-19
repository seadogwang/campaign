package com.company.campaign.security.context;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Immutable views over {@link CampaignPermission} sets.
 *
 * <p>{@link #minimum()} is the exact set of permissions every authenticated principal must be able to hold;
 * it is deliberately unmodifiable so a caller cannot widen the permission universe at runtime.
 */
public final class CampaignPermissions {

    private static final Set<CampaignPermission> MINIMUM =
            Collections.unmodifiableSet(EnumSet.allOf(CampaignPermission.class));

    private CampaignPermissions() {
    }

    /** The seven minimum permissions, as an immutable set. */
    public static Set<CampaignPermission> minimum() {
        return MINIMUM;
    }

    /** Returns an immutable copy of the supplied permissions; a null set is treated as empty. */
    public static Set<CampaignPermission> immutableCopy(Set<CampaignPermission> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(permissions));
    }
}