package com.company.campaign.builder.segment;

/**
 * The injected, host-owned boundary that turns one bounded safe request into raw structured Intent bytes.
 *
 * <p>This is deliberately an interface with <em>no</em> implementation in production code: the only provider
 * implementations this build ships are test-only. A host injects one adapter, the service calls it exactly
 * once, and everything it returns is untrusted raw text that must pass the strict T10 codec. The port receives
 * no credential, tenant, user, DataScope, permission, storage schema, catalog, Gate, Save/Publish or Runtime
 * field, and it is given no tool, database or execution capability.
 *
 * <p>An implementation may throw any unchecked exception to signal a provider failure; the service maps every
 * failure to one stable {@link SegmentAdapterErrorCodes} code without echoing provider text.
 */
public interface SegmentIntentModelPort {

    /**
     * Invokes the model exactly once with one immutable bounded request and returns only raw Intent bytes.
     *
     * @param request the immutable bounded safe request; never contains restricted host data
     * @return the raw response bytes, or {@code null} to signal an empty response
     */
    byte[] invoke(SegmentModelRequest request);
}