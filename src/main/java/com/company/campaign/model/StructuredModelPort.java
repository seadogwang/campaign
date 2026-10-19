package com.company.campaign.model;

/**
 * The one neutral, narrow structured text-completion boundary of this build.
 *
 * <p>It receives one bounded, immutable {@link StructuredModelRequest} — a system instruction, a user document
 * and a response schema — and returns one bounded text document as raw bytes. It is deliberately the whole
 * contract: there is no provider SDK object, no credential, no URL, no HTTP response, no streaming callback, no
 * tool/function-call channel and no unrestricted exception type in this interface, so no caller can reach a
 * provider through it and no provider can hand a caller anything but bounded text.
 *
 * <p>Returned bytes are never trusted: every caller decodes them itself, under its own strict contract. An
 * implementation may throw any unchecked exception to signal failure; a caller must map every failure to its own
 * bounded, non-disclosing code and must never echo provider text.
 *
 * <p>This port lives outside every {@code dev} package on purpose. Production wiring depends on this interface
 * alone, so shared Skill resolution no longer has an architectural dependency on a development-only model
 * boundary.
 */
public interface StructuredModelPort {

    /**
     * Invokes the model exactly once with one bounded, immutable request and returns one bounded text document.
     *
     * @param request the immutable bounded request; never contains a credential, a URL or host-private data
     * @return the raw response bytes, or {@code null}/an empty array to signal an empty response
     */
    byte[] invokeStructured(StructuredModelRequest request);
}