package com.company.campaign.dev.segment;

import com.company.campaign.model.StructuredModelPort;
import com.company.campaign.model.StructuredModelRequest;

/**
 * Development-only compatibility view of the neutral {@link StructuredModelPort}.
 *
 * <p>The neutral port is the architectural boundary every shared Skill resolver and every Campaign/Segment
 * adapter consumes. This compatibility interface keeps the accepted development host and its CLIs source- and
 * binary-compatible: it adds the bounded single-prompt call the development slice has always used, and it
 * adapts that call to the neutral request through one bounded default method.
 *
 * <p>It is still functional — {@link #invokeStructured(String)} is its only abstract method — so a test can
 * keep injecting its own fake without reading a developer's machine settings or making a network call. It
 * carries no provider SDK object, credential, URL or HTTP response, exactly like the neutral port it extends.
 */
@FunctionalInterface
public interface DevelopmentStructuredModelPort extends StructuredModelPort {

    /** The accepted development call: one bounded prompt producing one bounded text document. */
    byte[] invokeStructured(String prompt);

    /**
     * Adapts the neutral request to the development single-prompt call.
     *
     * <p>The rendered prompt is the exact bounded text {@link StructuredModelRequest#prompt()} composes, so a
     * migrated caller and a compatibility caller send byte-identical prompts.
     */
    @Override
    default byte[] invokeStructured(StructuredModelRequest request) {
        return invokeStructured(request.prompt());
    }
}