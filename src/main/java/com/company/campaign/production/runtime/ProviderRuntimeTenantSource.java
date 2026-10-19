package com.company.campaign.production.runtime;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The narrow, host-owned source of the tenants a production cycle may scan.
 *
 * <p>Tenant identity is restored from this trusted source and from persisted records only — never from a browser
 * request, a model request, a request header or any other caller-supplied value. A runtime cycle names one exact
 * tenant to the workers, and the workers re-prove every related record against that persisted owner.
 *
 * <p>The list is bounded and validated: a blank entry, a duplicate entry or a list longer than the configured
 * tenant bound fails the whole cycle closed with one bounded, redacted code before any work is claimed, so a
 * misbehaving source can never widen a scan silently. The entries themselves are never logged or exposed.
 */
public interface ProviderRuntimeTenantSource {

    /** Every tenant this cycle may scan, in a bounded list. */
    List<String> tenants();

    /**
     * The validated, bounded tenant list of one source, or a bounded failure when it is unusable.
     *
     * <p>Duplicates, blanks and over-limit lists are refused here rather than being silently de-duplicated or
     * truncated: a cycle that cannot trust the list it was given must not claim any work at all.
     */
    static List<String> bounded(ProviderRuntimeTenantSource source, int maxTenants) {
        if (source == null) {
            throw new ProductionProviderRuntimeConfigurationException(
                    ProductionProviderRuntimeErrorCodes.TENANT_SOURCE_INVALID,
                    "The production provider runtime requires a bounded tenant source.");
        }
        List<String> listed = source.tenants();
        if (listed == null || listed.size() > maxTenants) {
            throw new ProductionProviderRuntimeConfigurationException(
                    ProductionProviderRuntimeErrorCodes.TENANT_SOURCE_INVALID,
                    "The bounded tenant source returned an unusable tenant list.");
        }
        Set<String> distinct = new HashSet<>();
        for (String tenant : listed) {
            if (tenant == null || tenant.isBlank() || tenant.length() > 128 || !distinct.add(tenant)) {
                throw new ProductionProviderRuntimeConfigurationException(
                        ProductionProviderRuntimeErrorCodes.TENANT_SOURCE_INVALID,
                        "The bounded tenant source returned a blank, duplicate or oversized tenant identity.");
            }
        }
        return List.copyOf(listed);
    }
}